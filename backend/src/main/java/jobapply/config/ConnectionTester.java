package jobapply.config;

import java.time.Duration;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 「测试连接」：发一条最小请求，附带一个只有定义的测试工具，要求模型调用它。
 * 响应里出现了这个工具调用，就算支持工具调用（主模型必须支持，ui.md「用途分配」）。
 * 测试工具的 {@code call()} 一律抛异常：直接调用 {@code ChatModel}，工具不会被执行。
 */
public final class ConnectionTester {

    static final String PROBE_TOOL = "jobapply_connection_probe";
    static final Duration TIMEOUT = Duration.ofSeconds(30);

    /**
     * @param model            响应里的模型名；没有时为请求的模型名
     * @param promptTokens     可能为 null（提供方没返回用量）
     * @param error            失败原因（不含 Key）；成功时为 null
     */
    public record Result(boolean ok, long latencyMs, String model, boolean supportsTools, Integer promptTokens,
                         Integer completionTokens, String error) {
    }

    /** 只有定义的测试工具。 */
    static final class ProbeTool implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(PROBE_TOOL)
                    .description("Connection test. Call this tool exactly once with ok=true.")
                    .inputSchema("""
                            {"type":"object","properties":{"ok":{"type":"boolean"}},"required":["ok"]}""")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            throw new IllegalStateException("测试工具不应被执行");
        }
    }

    private final Duration timeout;

    public ConnectionTester() {
        this(TIMEOUT);
    }

    ConnectionTester(Duration timeout) {
        this.timeout = timeout;
    }

    public Result test(ModelFactory.Endpoint endpoint) {
        ChatModel model = ModelFactory.create(endpoint, timeout, 0);
        Prompt prompt = new Prompt(List.of(
                new SystemMessage("This is a connection test. Call the tool " + PROBE_TOOL
                        + " once with {\"ok\": true}. Do not reply with text."),
                new UserMessage("Call the tool now.")),
                ModelFactory.withTools(endpoint, List.of(new ProbeTool())));
        long started = System.nanoTime();
        ChatResponse response;
        try {
            response = model.call(prompt);
        } catch (RuntimeException e) {
            return new Result(false, elapsed(started), endpoint.model(), false, null, null,
                    reason(e, endpoint.apiKey()));
        }
        long latency = elapsed(started);
        AssistantMessage output = response.getResult() == null ? null : response.getResult().getOutput();
        boolean tools = output != null && output.getToolCalls().stream()
                .anyMatch(call -> PROBE_TOOL.equals(call.name()));
        String name = response.getMetadata().getModel();
        Usage usage = response.getMetadata().getUsage();
        return new Result(true, latency, name == null || name.isBlank() ? endpoint.model() : name, tools,
                usage == null ? null : usage.getPromptTokens(), usage == null ? null : usage.getCompletionTokens(),
                null);
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static String reason(Throwable e, String apiKey) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        message = ModelCatalogClient.redact(message, apiKey).replaceAll("\\s+", " ").strip();
        return message.length() > 300 ? message.substring(0, 300) + "…" : message;
    }
}
