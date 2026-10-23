package jobapply.config;

import java.time.Duration;
import java.util.List;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;

/**
 * 按接口格式、Base URL、模型名和解析好的 Key 创建 {@link ChatModel}。不注册成 Spring Bean，不用自动配置。
 * 调用方直接调用 {@code ChatModel.call()}，不用 {@code ChatClient}、不挂 advisor：交给模型的工具只有定义，
 * 模型返回的工具调用原样留在响应里，不会被执行（C004-A 小样核实，2026-10-22）。
 * Base URL 和 Key 一律显式传入：两个 SDK 在它们为 null 时会去读进程环境变量（{@code OPENAI_API_KEY}、
 * {@code ANTHROPIC_BASE_URL} 等），那样会用到用户没在设置页里配置的值。
 */
public final class ModelFactory {

    /** 失败时重试一次（SDK 默认两次）；测试连接用 0。 */
    static final int MAX_RETRIES = 1;

    /**
     * 一个可调用的端点。{@link #toString()} 不带 Key。
     *
     * @param apiKey 解析好的 Key；不需要 Key 时为 null
     */
    public record Endpoint(String format, String baseUrl, String apiKey, String model) {

        public Endpoint {
            if (!"openai".equals(format) && !"anthropic".equals(format)) {
                throw new IllegalArgumentException("接口格式只能是 openai 或 anthropic");
            }
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("Base URL 不能为空");
            }
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("模型名不能为空");
            }
            if ("anthropic".equals(format) && (apiKey == null || apiKey.isBlank())) {
                throw new IllegalArgumentException("Anthropic 格式需要 Key");
            }
        }

        @Override
        public String toString() {
            return "Endpoint[format=" + format + ", baseUrl=" + baseUrl + ", model=" + model + ", key="
                    + (apiKey == null ? "无" : "已设置") + "]";
        }
    }

    private ModelFactory() {
    }

    public static ChatModel create(Endpoint endpoint, Duration timeout) {
        return create(endpoint, timeout, MAX_RETRIES);
    }

    static ChatModel create(Endpoint endpoint, Duration timeout, int maxRetries) {
        if ("anthropic".equals(endpoint.format())) {
            return AnthropicChatModel.builder().options(AnthropicChatOptions.builder()
                    .baseUrl(endpoint.baseUrl().strip())
                    .apiKey(endpoint.apiKey())
                    .model(endpoint.model())
                    .timeout(timeout)
                    .maxRetries(maxRetries)
                    .build()).build();
        }
        return OpenAiChatModel.builder().options(OpenAiChatOptions.builder()
                .baseUrl(endpoint.baseUrl().strip())
                // 空字符串 = 不发 Authorization（本地模型）；不能传 null，否则 SDK 会读 OPENAI_API_KEY
                .apiKey(endpoint.apiKey() == null ? "" : endpoint.apiKey())
                .model(endpoint.model())
                .timeout(timeout)
                .maxRetries(maxRetries)
                .build()).build();
    }

    /**
     * 带工具定义的单次请求选项（工具只有定义，{@code call()} 由调用方保证不会被用到）。
     * 必须写上模型名：单次请求的选项没写模型名时，SDK 会填它自己的默认值（OpenAI 是 {@code gpt-5-mini}，
     * Anthropic 是 {@code claude-haiku-4-5}），合并时还会盖掉创建模型时设的名字（C004-A 手动检查发现，2026-10-22）。
     */
    public static ChatOptions withTools(Endpoint endpoint, List<ToolCallback> tools) {
        if ("anthropic".equals(endpoint.format())) {
            return AnthropicChatOptions.builder().model(endpoint.model()).toolCallbacks(tools).build();
        }
        return OpenAiChatOptions.builder().model(endpoint.model()).toolCallbacks(tools).build();
    }

    /**
     * 要求按 JSON Schema 返回的单次请求选项（OpenAI 格式发 {@code response_format.json_schema}，Anthropic 格式发
     * {@code output_config.format}）。从模型创建时的选项复制，模型名不会被 SDK 默认值盖掉（C004-A-2 小样核实）。
     * Anthropic 的 {@code max_tokens} 默认只有 4096，抽取结果可能更长，所以改成 {@code anthropicMaxTokens}；
     * OpenAI 格式不设（较新的模型不接受 {@code max_tokens}）。
     */
    public static ChatOptions withSchema(ChatModel model, String schema, int anthropicMaxTokens) {
        if (!(model.getOptions() instanceof StructuredOutputChatOptions options)) {
            throw new IllegalArgumentException("这个模型不支持按 JSON Schema 返回");
        }
        StructuredOutputChatOptions.Builder<?> builder = options.mutate().outputSchema(schema);
        if (options instanceof AnthropicChatOptions) {
            builder.maxTokens(anthropicMaxTokens);
        }
        return builder.build();
    }
}
