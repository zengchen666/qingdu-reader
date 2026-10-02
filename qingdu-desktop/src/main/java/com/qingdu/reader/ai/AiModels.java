package com.qingdu.reader.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨进程数据契约 —— 与 Python 侧 {@code qingdu_ai/schemas.py} 一一对应。
 *
 * <p><b>🔴 字段名必须是 camelCase</b>：Python 侧 Pydantic 配了
 * {@code alias_generator=to_camel}，轻读发 {@code bookId} 而发 {@code book_id}
 * 会被<b>静默丢弃</b>（请求照样 200，只是数据全丢）。这是实测踩过的坑，
 * Python 侧有 {@code TestCamelCaseContract} 钉住，本侧对应用测试钉住。
 */
public final class AiModels {

    private AiModels() {
    }

    /**
     * 一个原文片段。
     *
     * @param bookId         图书 ID（由路径派生，跨进程一致）
     * @param bookTitle      书名（冗余存一份：Python 侧不查库）
     * @param chapterIndex   章节序号（<b>0 起</b>）
     * @param chapterTitle   章节标题
     * @param paragraphIndex 章内第几段（<b>0 起</b>）
     * @param text           段落原文
     */
    public record Chunk(String bookId, String bookTitle, int chapterIndex, String chapterTitle,
                        int paragraphIndex, String text) {

        public Chunk {
            if (bookId == null || bookId.isBlank()) {
                throw new IllegalArgumentException("bookId 不能为空");
            }
            if (chapterIndex < 0) {
                throw new IllegalArgumentException("chapterIndex 不能为负数: " + chapterIndex);
            }
            if (paragraphIndex < 0) {
                throw new IllegalArgumentException("paragraphIndex 不能为负数: " + paragraphIndex);
            }
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("片段正文不能为空");
            }
            bookTitle = bookTitle == null ? "" : bookTitle;
            chapterTitle = chapterTitle == null ? "" : chapterTitle;
        }

        /** 序列化成 Python 侧 {@code LightChunk} 期望的 camelCase JSON 对象。 */
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bookId", bookId);
            m.put("bookTitle", bookTitle);
            m.put("chapterIndex", chapterIndex);
            m.put("chapterTitle", chapterTitle);
            m.put("paragraphIndex", paragraphIndex);
            m.put("text", text);
            return m;
        }
    }

    /**
     * 一次问答的结果。
     *
     * @param answer      答案文本；<b>为 null 表示"答不出来"</b>
     *                    （召不回原文，或模型给的引用编号全部无效）。
     *                    界面必须区分 null 与空串 —— 前者要提示换说法，后者是 bug
     * @param citations  引用列表（可点击跳转）
     * @param reason     原因码，见 {@link #REASON_*}
     * @param elapsedMs  服务端耗时
     */
    public record AskResult(String answer, List<Citation> citations, String reason,
                            long elapsedMs, int searchedBooks, int indexedBooks, int hits) {

        public AskResult {
            citations = citations == null ? List.of() : List.copyOf(citations);
            reason = reason == null ? "" : reason;
        }

        /** 是否真的答上了。 */
        public boolean answered() {
            return answer != null && !answer.isBlank();
        }

        /** 召不回任何片段 —— 界面提示"换个说法试试"。 */
        public boolean noChunks() {
            return "no-chunks".equals(reason);
        }

        /** 模型给了答案但引用全无效 —— 工程问题，措辞要说清楚。 */
        public boolean invalidCitations() {
            return "invalid-citations".equals(reason);
        }
    }

    /**
     * 一条引用。
     *
     * @param index          片段编号（1 起），对应答案里的 {@code [n]}
     * @param chapterIndex   <b>0 起</b>，与 {@code Chunk} 一致；界面显示时要 +1
     * @param paragraphIndex <b>0 起</b>，同上
     */
    public record Citation(int index, String bookId, String bookTitle, int chapterIndex,
                           String chapterTitle, int paragraphIndex, String ref, String excerpt) {

        public Citation {
            bookId = bookId == null ? "" : bookId;
            bookTitle = bookTitle == null ? "" : bookTitle;
            chapterTitle = chapterTitle == null ? "" : chapterTitle;
            ref = ref == null ? "" : ref;
            excerpt = excerpt == null ? "" : excerpt;
        }

        /** 界面显示用的"第几章"（1 起）。 */
        public int displayChapter() {
            return chapterIndex + 1;
        }
    }

    /** 召回不到任何片段。 */
    public static final String REASON_NO_CHUNKS = "no-chunks";
    /** 模型返回的引用编号一个都不合法。 */
    public static final String REASON_INVALID_CITATIONS = "invalid-citations";
    /** 正常作答。 */
    public static final String REASON_OK = "ok";

    /**
     * 解析 {@code /api/ask} 的响应。
     *
     * <p><b>answer 可能是 JSON 里的 {@code null}</b>，这与"字段不存在"要分开看：
     * 前者是"服务端明确说答不出"，后者是"服务端返回了意外结构"。
     * 前者走正常提示分支，后者要提示"服务返回异常" —— 混为一谈会让真故障被当成
     * "没找到相关内容"，用户无从判断该不该重试。
     */
    public static AskResult parseAskResponse(String json) {
        Map<String, Object> root = Json.parseObject(json);
        String answer = root.containsKey("answer") ? Json.str(root, "answer", null) : null;
        List<Citation> citations = new ArrayList<>();
        for (Map<String, Object> c : Json.childArray(root, "citations")) {
            citations.add(new Citation(
                    Json.integer(c, "index", 0),
                    Json.str(c, "bookId", ""),
                    Json.str(c, "bookTitle", ""),
                    Json.integer(c, "chapterIndex", -1),
                    Json.str(c, "chapterTitle", ""),
                    Json.integer(c, "paragraphIndex", -1),
                    Json.str(c, "ref", ""),
                    Json.str(c, "excerpt", "")));
        }
        Map<String, Object> coverage = Json.childObject(root, "coverage");
        return new AskResult(
                answer,
                citations,
                Json.str(root, "reason", REASON_OK),
                Json.integer(root, "elapsedMs", 0),
                Json.integer(coverage, "searchedBooks", 0),
                Json.integer(coverage, "indexedBooks", 0),
                Json.integer(coverage, "hits", 0));
    }

    /**
     * 解析 {@code /api/health} 的响应。
     *
     * @param llm 三态：{@code ready} / {@code no-api-key} / {@code not-configured}
     */
    public record Health(boolean ok, String llm, String version) {

        /** 服务能正常问答。 */
        public boolean ready() {
            return ok && "ready".equals(llm);
        }

        /** 服务活着但没配 key —— 与"服务没起"要区分开，提示不同。 */
        public boolean noApiKey() {
            return "no-api-key".equals(llm) || "not-configured".equals(llm);
        }
    }

    /** 解析 {@code /api/health}。 */
    public static Health parseHealth(String json) {
        Map<String, Object> root = Json.parseObject(json);
        return new Health(
                Json.integer(root, "ok", 0) == 1 || Boolean.TRUE.equals(root.get("ok")),
                Json.str(root, "llm", "not-configured"),
                Json.str(root, "version", ""));
    }
}
