package jobapply.harness.runtime;

import java.time.Duration;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
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
