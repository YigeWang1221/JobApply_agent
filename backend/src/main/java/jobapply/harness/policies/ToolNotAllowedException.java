package jobapply.harness.policies;

/**
 * 工具调用不在白名单内，或参数不被允许。调用没有发出。
 */
public class ToolNotAllowedException extends RuntimeException {

    public ToolNotAllowedException(String message) {
        super(message + "。不要重试。");
    }
}
