package jobapply.config;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 测试用的模型提供方：JDK {@link HttpServer}，监听 127.0.0.1 的随机端口，模拟 OpenAI 兼容格式
 * （{@code /v1/chat/completions}、{@code /v1/models}）和 Anthropic 格式（{@code /v1/messages}、{@code /v1/models}）。
 * 不访问外部网络。返回的内容全部虚构。
 */
public final class FakeModelServer implements AutoCloseable {

    /** 测试自己生成的假 Key。 */
    public static final String OPENAI_KEY = "sk-fake-openai-0000-1111-2222-abcd";
    public static final String ANTHROPIC_KEY = "sk-ant-fake-3333-4444-5555-wxyz";

    public record Request(String method, String path, String authorization, String apiKey, String anthropicVersion,
                          String body) {
    }

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile boolean toolCalls = true;
    private volatile int status = 200;
    private volatile String errorBody = "";
    private volatile long delayMillis;
    private volatile List<String> models = List.of("fake-small", "fake-large");
    private volatile String reply = "OK";
    private volatile Function<String, String> responder;
    private final Queue<String> scripted = new ConcurrentLinkedQueue<>();
    private final AtomicInteger scriptedIds = new AtomicInteger();

    private FakeModelServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    public static FakeModelServer start() {
        return new FakeModelServer();
    }

    /** OpenAI 兼容格式的 Base URL（带 /v1）。 */
    public String openAiBase() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** Anthropic 格式的 Base URL（不带 /v1）。 */
    public String anthropicBase() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public FakeModelServer toolCalls(boolean enabled) {
        this.toolCalls = enabled;
        return this;
    }

    /** 之后每个请求都返回这个状态码和错误体。 */
    public FakeModelServer fail(int status, String body) {
        this.status = status;
        this.errorBody = body;
        return this;
    }

    public FakeModelServer delay(long millis) {
        this.delayMillis = millis;
        return this;
    }

    /** 不调用工具时回复的文字。 */
    public FakeModelServer reply(String text) {
        this.reply = text;
        return this;
    }

    /**
     * 按请求体决定回复的文字（不调用工具时，两种格式都用）：返回 null 时用 {@link #reply(String)} 的文字
     * （Anthropic 格式是 {@code OK}）。用来让清单卡、抽取、译文、画像各自拿到不同的 JSON。
     */
    public FakeModelServer respond(Function<String, String> responder) {
        this.responder = responder;
        return this;
    }

    /**
     * 脚本化的工具调用（OpenAI 兼容格式）：之后的 {@code /v1/chat/completions} 请求按顺序各返回一个工具调用，
     * 用完后回到 {@link #toolCalls(boolean)} 的行为。用来把这个服务端当成填写和登录核对的主模型，走真实的
     * 设置 → 模型注册 → 请求路径（工具不会被 SDK 执行，交回调用方）。
     *
     * @param arguments 工具参数的 JSON 对象
     */
    public FakeModelServer thenTool(String name, String arguments) {
        int id = scriptedIds.incrementAndGet();
        scripted.add("""
                {"id":"s%d","object":"chat.completion","created":1,"model":"fake-large",
                 "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                   "tool_calls":[{"id":"call-%d","type":"function","function":{"name":%s,"arguments":%s}}]}}],
                 "usage":{"prompt_tokens":1000,"completion_tokens":100,"total_tokens":1100}}"""
                .formatted(id, id, json(name), json(arguments)));
        return this;
    }

    /** 还没用掉的脚本化工具调用。 */
    public int scriptedRemaining() {
        return scripted.size();
    }

    public FakeModelServer models(List<String> ids) {
        this.models = List.copyOf(ids);
        return this;
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public Request last() {
        return requests.getLast();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
        String path = exchange.getRequestURI().getPath();
        requests.add(new Request(exchange.getRequestMethod(), path,
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("x-api-key"),
                exchange.getRequestHeaders().getFirst("anthropic-version"), body));
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (status != 200) {
            send(exchange, status, errorBody);
            return;
        }
        String response = switch (path) {
            case "/v1/models" -> modelList();
            case "/v1/chat/completions" -> {
                String next = scripted.poll();
                yield next != null ? next : toolCalls ? openAiToolCall() : openAiText(text(body, reply));
            }
            case "/v1/messages" -> toolCalls ? anthropicToolUse() : anthropicText(text(body, "OK"));
            default -> null;
        };
        if (response == null) {
            send(exchange, 404, "{\"error\":{\"message\":\"not found: " + path + "\"}}");
        } else {
            send(exchange, 200, response);
        }
    }

    private String modelList() {
        StringBuilder data = new StringBuilder();
        for (String id : models) {
            data.append(data.isEmpty() ? "" : ",").append("{\"id\":\"").append(id).append("\",\"object\":\"model\"}");
        }
        return "{\"object\":\"list\",\"data\":[" + data + "]}";
    }

    private static String openAiToolCall() {
        return """
                {"id":"c1","object":"chat.completion","created":1,"model":"fake-large-2026",
                 "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                   "tool_calls":[{"id":"t1","type":"function",
                     "function":{"name":"jobapply_connection_probe","arguments":"{\\"ok\\":true}"}}]}}],
                 "usage":{"prompt_tokens":42,"completion_tokens":7,"total_tokens":49}}""";
    }

    private String text(String body, String fallback) {
        Function<String, String> current = responder;
        String text = current == null ? null : current.apply(body);
        return text == null ? fallback : text;
    }

    private static String openAiText(String reply) {
        return """
                {"id":"c2","object":"chat.completion","created":1,"model":"fake-small",
                 "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":%s}}],
                 "usage":{"prompt_tokens":40,"completion_tokens":1,"total_tokens":41}}""".formatted(json(reply));
    }

    private static String json(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static String anthropicToolUse() {
        return """
                {"id":"m1","type":"message","role":"assistant","model":"fake-claude-2026","stop_reason":"tool_use",
                 "stop_sequence":null,
                 "content":[{"type":"tool_use","id":"tu1","name":"jobapply_connection_probe","input":{"ok":true}}],
                 "usage":{"input_tokens":50,"output_tokens":9}}""";
    }

    private static String anthropicText(String reply) {
        return """
                {"id":"m2","type":"message","role":"assistant","model":"fake-claude-2026","stop_reason":"end_turn",
                 "stop_sequence":null,"content":[{"type":"text","text":%s}],
                 "usage":{"input_tokens":48,"output_tokens":1}}""".formatted(json(reply));
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
