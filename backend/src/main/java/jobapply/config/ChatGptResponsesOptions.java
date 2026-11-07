package jobapply.config;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.tool.ToolCallback;

/**
 * {@link ChatGptResponsesChatModel} 的单次请求选项（C028）。只有模型名、工具定义和 JSON Schema 会进请求；
 * temperature、maxTokens、topP 等字段虽然能设，但 ChatGPT 套餐的 Responses 路由不接受，一律不发。
 * 和 {@code OpenAiChatOptions} 一样同时实现工具调用和结构化输出两个接口，{@code ModelFactory.withTools/withSchema} 照常用。
 */
public final class ChatGptResponsesOptions extends DefaultToolCallingChatOptions implements StructuredOutputChatOptions {

    private final String outputSchema;

    private ChatGptResponsesOptions(List<ToolCallback> toolCallbacks, Map<String, Object> toolContext, String model,
                                    Double frequencyPenalty, Integer maxTokens, Double presencePenalty,
                                    List<String> stopSequences, Double temperature, Integer topK, Double topP,
                                    String outputSchema) {
        super(toolCallbacks, toolContext, model, frequencyPenalty, maxTokens, presencePenalty, stopSequences,
                temperature, topK, topP);
        this.outputSchema = outputSchema;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String getOutputSchema() {
        return outputSchema;
    }

    @Override
    public Builder mutate() {
        Builder b = new Builder().model(getModel()).frequencyPenalty(getFrequencyPenalty()).maxTokens(getMaxTokens())
                .presencePenalty(getPresencePenalty()).stopSequences(getStopSequences())
                .temperature(getTemperature()).topK(getTopK()).topP(getTopP())
                .toolCallbacks(getToolCallbacks()).toolContext(getToolContext());
        return b.outputSchema(outputSchema);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ChatGptResponsesOptions other && super.equals(o)
                && Objects.equals(outputSchema, other.outputSchema);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), outputSchema);
    }

    public static final class Builder extends DefaultToolCallingChatOptions.Builder<Builder>
            implements StructuredOutputChatOptions.Builder<Builder> {

        private String outputSchema;

        @Override
        public Builder outputSchema(String schema) {
            this.outputSchema = schema;
            return this;
        }

        @Override
        public Builder combineWith(org.springframework.ai.chat.prompt.ChatOptions.Builder<?> other) {
            super.combineWith(other);
            if (other instanceof Builder that && that.outputSchema != null) {
                this.outputSchema = that.outputSchema;
            }
            return this;
        }

        @Override
        public ChatGptResponsesOptions build() {
            if (toolCallbacks == null) {
                toolCallbacks = List.<ToolCallback>of();
            }
            if (toolContext == null) {
                toolContext = Map.of();
            }
            return new ChatGptResponsesOptions(toolCallbacks, toolContext, model, frequencyPenalty, maxTokens,
                    presencePenalty, stopSequences, temperature, topK, topP, outputSchema);
        }
    }
}
