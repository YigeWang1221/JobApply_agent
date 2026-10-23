package jobapply.harness.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 调主模型（申请 Agent 和对话 Agent 共用）：网络或服务端暂时出错时等一会儿再试，最多 {@link #MODEL_RETRY}
 * （用户确认，2026-10-23）；请求本身不对、Key 不对、模型不存在时直接报错。
 */
public final class ModelCaller {

    static final Duration MODEL_RETRY = Duration.ofMinutes(2);

    /** 这些错误重试也没用。按异常类名判断，不依赖具体的 SDK。 */
    private static final Set<String> NON_TRANSIENT = Set.of("BadRequestException", "UnauthorizedException",
            "PermissionDeniedException", "NotFoundException", "UnprocessableEntityException",
            "NonTransientAiException", "IllegalArgumentException");

    private ModelCaller() {
    }

    /**
     * @param stop 用户要求停止时返回 true：不再等待重试，直接把错误抛出去
     */
    public static ChatResponse call(ChatModel model, Prompt prompt, BooleanSupplier stop) {
        long deadline = System.nanoTime() + MODEL_RETRY.toNanos();
        long wait = 5_000;
        while (true) {
            try {
                return model.call(prompt);
            } catch (RuntimeException e) {
                if (!isTransient(e) || System.nanoTime() + wait * 1_000_000 > deadline || stop.getAsBoolean()) {
                    throw e;
                }
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                wait = Math.min(wait * 2, 30_000);
            }
        }
    }

    static boolean isTransient(Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause() == e ? null : e.getCause()) {
            if (NON_TRANSIENT.contains(e.getClass().getSimpleName())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 模型的回复：最后一个 generation。Anthropic 的回复里有 thinking 块时，Spring AI 把每个 thinking 块单独放成一个
     * generation 排在前面（{@code AnthropicChatModel.buildGenerations}），带文字和工具调用的那条在最后；
     * 没有 thinking 时只有一个，和 {@code getResult()} 一样。
     */
    public static AssistantMessage message(ChatResponse response) {
        List<Generation> results = response == null ? List.of() : response.getResults();
        return results.isEmpty() ? null : results.getLast().getOutput();
    }

    /**
     * 供应商真的给出的思考原文；没有时为 null（C006-agent）。只读回复里实际存在的字段，不推测：
     * <ul>
     *   <li>Anthropic：前面那些带 {@code signature} 属性的 generation（thinking 块），文字就是思考原文；</li>
     *   <li>OpenAI 兼容：Spring AI 把 {@code reasoning_content}（或 {@code reasoning}）放进 AssistantMessage 的
     *       metadata {@code reasoningContent}（{@code OpenAiChatModel.getReasoningContent}），没有时是空字符串。</li>
     * </ul>
     */
    public static String thinking(ChatResponse response) {
        List<Generation> results = response == null ? List.of() : response.getResults();
        if (results.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (Generation generation : results.subList(0, results.size() - 1)) {
            AssistantMessage block = generation.getOutput();
            if (block != null && block.getMetadata().containsKey("signature") && block.getText() != null
                    && !block.getText().isBlank()) {
                parts.add(block.getText().strip());
            }
        }
        AssistantMessage last = results.getLast().getOutput();
        if (last != null && last.getMetadata().get("reasoningContent") instanceof String reasoning
                && !reasoning.isBlank()) {
            parts.add(reasoning.strip());
        }
        return parts.isEmpty() ? null : String.join("\n\n", parts);
    }

    /** 这次调用的 token 数（输入 + 输出）；没有用量信息时为 0。 */
    public static long tokens(ChatResponse response) {
        var usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
        if (usage == null) {
            return 0;
        }
        return (usage.getPromptTokens() == null ? 0 : usage.getPromptTokens())
                + (usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens());
    }
}
