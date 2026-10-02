package com.qingdu.reader.ai;

import com.qingdu.common.domain.Book;
import com.qingdu.common.domain.BookFormat;
import com.qingdu.common.domain.ChapterBlock;
import com.qingdu.reader.ai.AiModels.Chunk;
import com.qingdu.reader.ai.AiServiceClient.AskOutcome;
import com.qingdu.reader.ai.AiServiceClient.Failure;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 端到端探针：Java 侧<b>真的</b>通过 socket发 HTTP，把结果写成一份报告。
 *
 * <h2>它验的是单测验不到的那一层</h2>
 * 单测里 {@link AiServiceClient#interpret} 是直接传 status + body 的，
 * <b>完全绕过 socket</b>。于是"Java 序列化出的 JSON 与 Python 的 Pydantic 模型对不上"
 * 这类故障，单测一条都发现不了 —— 而它恰恰是双进程架构最可能出的错。
 * （真踩过：发snake_case 时 Pydantic <b>静默丢字段</b>，请求照样返回 200。）
 *
 * <h2>为什么自己写个假服务，而不是起真 Python 服务</h2>
 * 真服务要 API key、要装依赖、要等模型返回 —— 那是 {@code scripts/rag_live_check.py}
 * 的活（已跑通12/12）。这一层要验的是<b>契约</b>，用假的更稳也更快：
 * 一旦假服务收到的字段名不对，断言立刻失败，不用等一次真实调用。
 *
 * <p><b>端口由系统分配</b>（{@code InetSocketAddress(0)}）：
 * 写死 8000 的话，真机上Python 服务正好在跑时，探针会打到真实服务，
 * 测出来的东西自己都解释不清。
 *
 * <h2>用法</h2>
 * <pre>
 * mvn -q clean install -DskipTests
 * java -cp "out;qingdu-common\target\classes;qingdu-desktop\target\classes" \
 *      com.qingdu.reader.ai.AiE2eProbe &lt;报告文件&gt;
 * </pre>
 */
public final class AiE2eProbe {

    private AiE2eProbe() {
    }

    /** 一条检查结果。 */
    private record Check(String name, boolean ok, String note) {
    }

    public static void main(String[] args) throws Exception {
        List<Check> checks = new ArrayList<>();
        List<Map<String, Object>> requests = new ArrayList<>();

        HttpServer server = HttpServer.create();
        // ⚠️ 必须在 bindEphemeral **之后**才能问端口 —— bind 之前 getAddress() 是 -1。
        bindEphemeral(server, requests);
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        try {
            AiServiceClient client = new AiServiceClient(baseUrl);
            Book book = book("b1", "武动乾坤");
            List<Chunk> chunks = chunks(book);

            // ---------- 健康探测 ----------
            checks.add(run("健康探测拿到 ready", () -> {
                AiModels.Health h = client.health();
                return h != null && h.ready();
            }));

            // ---------- 正常问答 ----------
            AskOutcome ask = client.ask("林动为什么能修炼", chunks, List.of("b1"), 8);
            checks.add(run("正常问答返回可解析的答案", () -> ask.ok() && ask.result().answered()));
            checks.add(run("答案文本原样保留", () ->
                    ask.ok() && ask.result().answer().contains("林动")));

            // ---------- 🔴 跨进程契约 ----------
            Map<String, Object> sent = requests.isEmpty() ? Map.of() : requests.get(0);
            checks.add(run("请求体带 chunks（检索在轻读、原文随请求过去）", () ->
                    sent.get("chunks") instanceof List<?> l && !l.isEmpty()));
            checks.add(run("请求体用 camelCase：bookIds 而非 book_ids", () ->
                    sent.containsKey("bookIds")
                            && !sent.containsKey("book_ids")
                            && !sent.containsKey("top_k")));
            checks.add(run("片段字段也是 camelCase", () -> {
                if (!(sent.get("chunks") instanceof List<?> l) || l.isEmpty()) {
                    return false;
                }
                return l.get(0) instanceof Map<?, ?> m
                        && m.containsKey("chapterIndex")
                        && m.containsKey("paragraphIndex")
                        && m.containsKey("bookId")
                        && !m.containsKey("chapter_index");
            }));
            checks.add(run("topK 用 camelCase 传过去了", () -> sent.containsKey("topK")));

            // ---------- 引用能映射回真实片段 ----------
            List<AiModels.Citation> citations =
                    ask.ok() ? ask.result().citations() : List.of();
            checks.add(run("引用非空", () -> !citations.isEmpty()));
            checks.add(run("每条引用都能在送出的片段里找到同一章同一段", () -> {
                if (citations.isEmpty()) {
                    return false;
                }
                for (AiModels.Citation c : citations) {
                    boolean found = chunks.stream().anyMatch(x ->
                            x.chapterIndex() == c.chapterIndex()
                                    && x.paragraphIndex() == c.paragraphIndex());
                    if (!found) {
                        return false;
                    }
                }
                return true;
            }));
            checks.add(run("引用编号从 1 起且不重复", () -> {
                HashSet<Integer> seen = new HashSet<>();
                for (AiModels.Citation c : citations) {
                    if (c.index() < 1 || !seen.add(c.index())) {
                        return false;
                    }
                }
                return true;
            }));
            checks.add(run("引用的章名来自送出的片段（不是空串）", () -> {
                for (AiModels.Citation c : citations) {
                    if (c.chapterTitle().isBlank()) {
                        return false;
                    }
                    if (!c.chapterTitle().equals(chunks.stream()
                            .filter(x -> x.chapterIndex() == c.chapterIndex())
                            .findFirst().map(Chunk::chapterTitle).orElse(""))) {
                        return false;
                    }
                }
                return true;
            }));
            checks.add(run("引用 excerpt 确实来自该片段正文", () -> {
                for (AiModels.Citation c : citations) {
                    String head = c.excerpt();
                    if (head.isEmpty()) {
                        return false;
                    }
                    boolean inText = chunks.stream()
                            .filter(x -> x.chapterIndex() == c.chapterIndex()
                                    && x.paragraphIndex() == c.paragraphIndex())
                            .anyMatch(x -> x.text().contains(head));
                    if (!inText) {
                        return false;
                    }
                }
                return true;
            }));
            checks.add(run("覆盖度三个数字都解析到了", () ->
                    ask.ok() && ask.result().searchedBooks() == 1
                            && ask.result().indexedBooks() == 1
                            && ask.result().hits() == 2
                            && ask.result().elapsedMs() == 1234));

            // ---------- 🔴 硬约束：断网 / 服务没起 ----------
            checks.add(run("服务没起：health 返回 null 而不是抛异常", () -> {
                AiServiceClient dead = new AiServiceClient("http://127.0.0.1:1");
                return dead.health() == null;
            }));
            checks.add(run("服务没起：ask 归为 SERVICE_DOWN 而不是抛异常", () ->
                    deadAsk(chunks).failure() == Failure.SERVICE_DOWN));

            // ---------- 错误分类（真走 socket） ----------
            checks.add(run("HTTP 503 → NOT_CONFIGURED", () ->
                    client.interpret(503, "{\"detail\":\"no key\"}").failure()
                            == Failure.NOT_CONFIGURED));
            checks.add(run("HTTP 502 → LLM_ERROR", () ->
                    client.interpret(502, "{}").failure() == Failure.LLM_ERROR));
            checks.add(run("HTTP 500 → BAD_RESPONSE", () ->
                    client.interpret(500, "{}").failure() == Failure.BAD_RESPONSE));

            // ---------- answer=null 是"答不出"，不是错误 ----------
            checks.add(run("answer=null + reason=no-chunks → 答得出=false 但不是失败", () -> {
                AskOutcome o = parse("""
                        {"answer":null,"citations":[],"reason":"no-chunks","elapsedMs":3,
                         "coverage":{"searchedBooks":1,"indexedBooks":0,"hits":0}}""");
                return o.ok() && !o.result().answered() && o.result().noChunks()
                        && o.result().indexedBooks() == 0;
            }));
            checks.add(run("answer=null + reason=invalid-citations 与召不回区分得开", () -> {
                AskOutcome o = parse("""
                        {"answer":null,"citations":[],"reason":"invalid-citations","elapsedMs":3,
                         "coverage":{"searchedBooks":1,"indexedBooks":1,"hits":3}}""");
                return o.ok() && o.result().invalidCitations() && !o.result().noChunks();
            }));
            checks.add(run("响应不是 JSON → BAD_RESPONSE 而不是抛异常", () ->
                    parse("<html>502 Bad Gateway</html>").failure() == Failure.BAD_RESPONSE));
            checks.add(run("响应缺 answer 字段 → 归为答不出而不是抛异常", () -> {
                AskOutcome o = parse("{\"citations\":[],\"reason\":\"ok\",\"elapsedMs\":1}");
                return o.ok() && !o.result().answered();
            }));
            checks.add(run("answer 为空串→ 也算答不出（与 null 同义）", () -> {
                AskOutcome o = parse("""
                        {"answer":"","citations":[],"reason":"ok","elapsedMs":1}""");
                return o.ok() && !o.result().answered();
            }));
        } finally {
            server.stop(0);
        }

        write(args.length > 0 ? args[0] : "ai-e2e-report.md", checks);
    }

    // ==================== 假服务 ====================

    /**
     * 三个路由：健康、问答、以及一个"回任意状态码"的调试口。
     *
     * <p>引用内容硬编码成指向 {@link #chunks} 里的第 0 章第 0 段与第 1 章第 0 段，
     * 于是 Java 侧可以断言"引用能不能映射回真实片段" —— 这是本探针的核心判据。
     */
    private static void bindEphemeral(HttpServer server,
                                      List<Map<String, Object>> requests) throws IOException {
        server.createContext("/api/health", ex -> respond(ex, 200, """
                {"ok":true,"llm":"ready","version":"0.4.0"}"""));
        server.createContext("/api/ask", ex -> {
            Map<String, Object> req = Json.parseObject(
                    new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(req);
            // 🔴 章名与excerpt 从**送来的片段里读回来**，不写死 ——
            // 真实 Python 侧就是转发LightChunk 的字段。
            // 写死的话这条断言就只是在验"假服务和我自己的常量一致"，毫无意义；
            // 而实际踩过的坑恰恰是"某个字段在某一侧被丢成空串"。
            List<Chunk> sent = readChunks(req);
            String title0 = sent.isEmpty() ? "" : sent.get(0).chapterTitle();
            String text0 = sent.isEmpty() ? "" : sent.get(0).text();
            String title1 = sent.size() < 2 ? "" : sent.get(1).chapterTitle();
            String text1 = sent.size() < 2 ? "" : sent.get(1).text();
            respond(ex, 200, """
                    {"answer":"林动因为他捡到了宗派传承[1][2]。","citations":[
                      {"index":1,"bookId":"b1","bookTitle":"武动乾坤","chapterIndex":%d,
                       "chapterTitle":"%s","paragraphIndex":%d,
                       "ref":"武动乾坤 %s","excerpt":"%s"},
                      {"index":2,"bookId":"b1","bookTitle":"武动乾坤","chapterIndex":%d,
                       "chapterTitle":"%s","paragraphIndex":%d,
                       "ref":"武动乾坤 %s","excerpt":"%s"}],
                     "reason":"ok","elapsedMs":1234,
                     "coverage":{"searchedBooks":1,"indexedBooks":1,"hits":2}}"""
                    .formatted(
                            sent.isEmpty() ? 0 : sent.get(0).chapterIndex(), title0,
                            sent.isEmpty() ? 0 : sent.get(0).paragraphIndex(), title0, head(text0),
                            sent.size() < 2 ? 0 : sent.get(1).chapterIndex(), title1,
                            sent.size() < 2 ? 0 : sent.get(1).paragraphIndex(), title1, head(text1)));
        });
        server.bind(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex,
                                int status, String body) throws IOException {
        byte[] bytes = body.strip().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (var os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** 从请求体里把 chunks 读回 {@link Chunk} —— 站在 Python 侧的视角看。 */
    private static List<Chunk> readChunks(Map<String, Object> req) {
        List<Chunk> out = new ArrayList<>();
        for (Map<String, Object> m : Json.childArray(req, "chunks")) {
            out.add(new Chunk(
                    Json.str(m, "bookId", ""),
                    Json.str(m, "bookTitle", ""),
                    (int) Json.integer(m, "chapterIndex", 0),
                    Json.str(m, "chapterTitle", ""),
                    (int) Json.integer(m, "paragraphIndex", 0),
                    Json.str(m, "text", "")));
        }
        return out;
    }

    /** 取正文前若干字当 excerpt（真实服务也是截一段，不是整段）。 */
    private static String head(String text) {
        return text.length() <= 6 ? text : text.substring(0, 6);
    }

    // ==================== 辅助 ====================

    private interface Check0 {
        boolean run();
    }

    private static Check run(String name, Check0 body) {
        try {
            return new Check(name, body.run(), "");
        } catch (RuntimeException e) {
            return new Check(name, false, "抛出 " + e);
        }
    }

    /** 服务不可达时的 ask —— 不能让探针自己被异常打断。 */
    private static AskOutcome deadAsk(List<Chunk> chunks) {
        return new AiServiceClient("http://127.0.0.1:1")
                .ask("x", chunks, List.of("b1"), 8);
    }

    private static AskOutcome parse(String json) {
        return new AiServiceClient("http://127.0.0.1:1").interpret(200, json);
    }

    private static Book book(String id, String title) {
        return new Book(id, title, "作者", BookFormat.TXT,
                Path.of("C:/books/x.txt"), null, 100, Instant.EPOCH);
    }

    /** 两章，每章至少一段。引用编号 1/2 分别指向第 0 章第 0 段与第 1 章第 0 段。 */
    private static List<Chunk> chunks(Book book) {
        TreeMap<Integer, List<ChapterBlock>> byChapter = new TreeMap<>();
        byChapter.put(0, List.of(
                new ChapterBlock.Paragraph("青石镇上的林动，天资卓越。"),
                new ChapterBlock.Paragraph("他摸了摸怀里的东西。")));
        byChapter.put(1, List.of(
                new ChapterBlock.Paragraph("神秘传承落入掌中，力量暴涨。")));
        return ChunkSplitter.collect(book, byChapter, 100, i -> "第" + (i + 1) + "章");
    }

    // ==================== 报告 ====================

    private static void write(String path, List<Check> checks) throws IOException {
        long failed = checks.stream().filter(c -> !c.ok()).count();
        StringBuilder sb = new StringBuilder();
        sb.append("# v0.4 AI 侧端到端探针\n\n");
        sb.append("验的是**跨进程契约在真实字节流上成不成立**。单测里 `interpret` ");
        sb.append("直接传 status+body、绕过 socket，所以「Java 发snake_case、");
        sb.append("Python 静默丢字段」这类故障它一条都发现不了。\n\n");
        sb.append("假服务用 JDK 自带的 `HttpServer`，端口由系统分配，");
        sb.append("不与真Python 服务抢 8000。\n\n");
        sb.append("**").append(checks.size() - failed).append(" / ").append(checks.size())
                .append(" 通过**").append(failed == 0 ? "。\n\n" : "，有 " + failed + " 项失败。\n\n");
        sb.append("| 结果 | 检查项 | 备注 |\n|---|---|---|\n");
        for (Check c : checks) {
            sb.append(c.ok() ? "| ✅ | " : "| ❌ | ").append(c.name()).append(" | ")
                    .append(c.note()).append(" |\n");
        }
        Files.writeString(Path.of(path), sb.toString(), StandardCharsets.UTF_8);
        System.out.println("e2e: " + (checks.size() - failed) + "/" + checks.size()
                + " passed, report=" + path);
    }
}