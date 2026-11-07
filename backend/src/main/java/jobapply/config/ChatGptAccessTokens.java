package jobapply.config;
/** C028：给 Responses 模型取当前可用的 ChatGPT access token；需要时自动刷新。 */
public interface ChatGptAccessTokens {
    /**
     * 返回有效 access token；未登录或需重新登录时抛 {@link NonTransientAiException}（中文说明）。
     * Spring AI 2.0.1 没有 {@code org.springframework.ai.retry.NonTransientAiException}，用本仓库同名的类。
     */
    String current();
    /** 收到 401 后调用：丢弃缓存的 access token，下次 current() 强制刷新。 */
    void invalidate();
}
