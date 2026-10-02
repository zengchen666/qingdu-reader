package com.qingdu.reader.ai;

import com.qingdu.reader.ai.AiModels.AskResult;
import com.qingdu.reader.ai.AiModels.Chunk;
import com.qingdu.reader.ai.AiModels.Health;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 调用本地 {@code qingdu-ai} 服务的客户端。
 *
 * <h2>为什么用 JDK 自带的 {@link HttpClient}</h2>
 * JDK 11 起自带，**零新依赖**。为了发两个 HTTP 请求引入 OkHttp 或
 * Apache HttpClient 会给绿色版加几 MB，换不到什么 —— 这个项目里 JSON 库
 * 都已经决定手写了，HTTP 客户端更没必要引。
 *
 * <h2>超时为什么这么定</h2>
 * <ul>
 *   <li><b>健康探测 1.5 秒</b>：启动时调一次。这个接口在服务没起时是
 *       <i>立即</i> 返回连接错误（不是等超时），所以真正卡住的场景是
 *       "端口被别的程序占着但不响应" —— 那才是 1.5 秒该放弃的时候。
 *       界面绝不能因为 AI 服务而卡住启动。</li>
 *   <li><b>问答 120 秒</b>：一次问答要送 8 个片段给模型，
 *       中文 token 化偏慢，30 秒会在慢网络下误报失败。</li>
 * </ul>
 *
 * <h2>🔴 断网 / 服务没起时的行为</h2>
 * <b>轻读必须完全可用</b>：这是硬约束（设计文档 7.3）。所以本类所有方法
 * <b>永不抛异常给调用方</b>，失败一律转成 {@link AskOutcome} 里的失败态，
 * 界面据此提示"AI 服务未启动"而不是弹栈。
 */
public final class AiServiceClient {

    /** 服务默认地址。与 Python 侧 {@code config.DEFAULT_PORT} 保持一致。 */
    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:8000";

    private static final Duration HEALTH_TIMEOUT = Duration.ofMillis(1500);
    private static final Duration ASK_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    /**
     * 启动命令提示 —— 服务没起时直接显示它，省得用户去翻文档。
     *
     * <h2>🔴 为什么默认用 {@code .\.venv\Scripts\python.exe} 而不是 {@code python}</h2>
     * 依赖（fastapi / uvicorn / httpx / pydantic）是装进 {@code qingdu-ai\.venv}
     * 这个虚拟环境里的，而 {@code python} 指向的是**系统解释器**。
     * 本机实测：系统 Python 跑 {@code python -m qingdu_ai} 直接
     * {@code ModuleNotFoundError: No module named 'fastapi'}，
     * 而 {@code .\.venv\Scripts\python.exe -m qingdu_ai} 一次就起来。
     *
     * <p>这不是用户配错了环境，是<b>默认给的那条命令本身就是错的</b> ——
     * 在任何"按文档装了依赖"的机器上，裸 {@code python} 都不会指向 venv。
     * 所以这里必须写全路径；写成 {@code python -m qingdu_ai} 会让绝大多数人卡在
     * 一个看起来像"代码坏了"的报错上。
     */
    public static final String START_COMMAND =
            ".\\.venv\\Scripts\\python.exe -m qingdu_ai";

    /**
     * 依赖没装时的补充提示。
     *
     * <p>区分两种失败：{@code ModuleNotFoundError: fastapi}（依赖没装）
     * 和"服务根本没起"（连得上但不是 AI 服务）。前者照着 {@link #START_COMMAND}
     * 敲仍然会失败，必须先装依赖。
     */
    public static final String INSTALL_HINT =
            "cd qingdu-ai\n"
            + "python -m venv .venv\n"
            + ".\\.venv\\Scripts\\python.exe -m pip install -e \".[dev]\"";

    private final String baseUrl;
    private final HttpClient http;

    public AiServiceClient() {
        this(DEFAULT_BASE_URL);
    }

    public AiServiceClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                // 问答时间可能较长，HTTP 客户端自身的超时给足
                .build();
    }

    /**
     * 一次问答的结果 —— 成功与失败都用它表达。
     *
     * @param result 成功时的结果；失败时为 null
     * @param failure 失败分类，界面据此给不同提示
     * @param detail 失败详情（面向用户的中文说明，可为 null）
     */
    public record AskOutcome(AskResult result, Failure failure, String detail) {

        public static AskOutcome ok(AskResult r) {
            return new AskOutcome(r, null, null);
        }

        public static AskOutcome fail(Failure f, String detail) {
            return new AskOutcome(null, f, detail);
        }

        public boolean ok() {
            return result != null;
        }
    }

    /** 失败分类 —— 与设计文档 5.3 的错误表一一对应。 */
    public enum Failure {
        /** 服务没起（连接被拒绝）。最常见，所以单独一类。 */
        SERVICE_DOWN,
        /** 服务没配 API key。 */
        NOT_CONFIGURED,
        /** 模型调用失败（502 / 超时）。 */
        LLM_ERROR,
        /** 服务返回了看不懂的内容。 */
        BAD_RESPONSE
    }

    /**
     * 探测服务状态。
     *
     * @return 服务不可达时返回 {@code null}（而不是抛异常）
     */
    public Health health() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/health"))
                    .timeout(HEALTH_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return null;
            }
            return AiModels.parseHealth(resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            // 连接被拒绝、DNS 失败、超时、JSON 解析失败 —— 统统算"探测不到"
            return null;
        }
    }

    /**
     * 发起一次问答（阻塞）。
     *
     * <p><b>调用方必须在后台线程调</b>：网络请求会阻塞，而 JavaFX 的 UI 线程
     * 不能阻塞。
     */
    public AskOutcome ask(String question, List<Chunk> chunks, List<String> bookIds, int topK) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", question);
        List<Map<String, Object>> chunkJson = chunks.stream().map(Chunk::toJson).toList();
        body.put("chunks", chunkJson);
        body.put("bookIds", bookIds == null ? List.of() : bookIds);
        body.put("topK", topK);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/ask"))
                .timeout(ASK_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build();

        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return interpret(resp.statusCode(), resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AskOutcome.fail(Failure.SERVICE_DOWN, "请求被中断");
        } catch (java.net.ConnectException | java.net.SocketTimeoutException e) {
            return AskOutcome.fail(Failure.SERVICE_DOWN, null);
        } catch (java.net.http.HttpTimeoutException e) {
            return AskOutcome.fail(Failure.LLM_ERROR, "模型响应超时");
        } catch (Exception e) {
            return AskOutcome.fail(Failure.SERVICE_DOWN, e.getClass().getSimpleName());
        }
    }

    /**
     * 异步问答，供 UI 直接用。
     *
     * <p>用 {@link CompletableFuture} 而不是 JavaFX 的 {@code Task}：
     * 这样客户端不依赖 JavaFX，可以被单测直接测。
     */
    public CompletableFuture<AskOutcome> askAsync(String question, List<Chunk> chunks,
                                                  List<String> bookIds, int topK) {
        return CompletableFuture.supplyAsync(() -> ask(question, chunks, bookIds, topK));
    }

    /** 把 HTTP 响应翻译成结果。拆出来是为了能直接单测，不用起真服务。 */
    AskOutcome interpret(int status, String body) {
        if (status == 503) {
            return AskOutcome.fail(Failure.NOT_CONFIGURED, "未配置模型 API key");
        }
        if (status == 502) {
            return AskOutcome.fail(Failure.LLM_ERROR, "模型调用失败");
        }
        if (status != 200) {
            return AskOutcome.fail(Failure.BAD_RESPONSE, "服务返回 HTTP " + status);
        }
        try {
            return AskOutcome.ok(AiModels.parseAskResponse(body));
        } catch (RuntimeException e) {
            return AskOutcome.fail(Failure.BAD_RESPONSE, "无法解析服务响应");
        }
    }

    /** 供 {@code CompletableFuture} 之外的场景做超时兜底（测试用）。 */
    static <T> T await(CompletableFuture<T> future, Duration timeout) throws Exception {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            throw e;
        }
    }
}
