package jobapply.config;

/**
 * C028：重试也没用的模型错误（ChatGPT 未登录 / 登录失效、套餐额度用完、账号不能用等），消息是给用户看的中文说明。
 * Spring AI 2.0.1 的类路径上没有 {@code org.springframework.ai.retry.NonTransientAiException}，所以本仓库自己定义；
 * {@code ModelCaller} 按异常的简单类名判断是否重试，这个名字在它的不重试名单里。
 */
public class NonTransientAiException extends RuntimeException {

    public NonTransientAiException(String message) {
        super(message);
    }

    public NonTransientAiException(String message, Throwable cause) {
        super(message, cause);
    }
}
