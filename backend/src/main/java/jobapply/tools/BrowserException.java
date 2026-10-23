package jobapply.tools;

/**
 * 浏览器调用失败。两种情况分开处理：{@link Disconnected} 要用户检查 Chrome 后重连；{@link ToolFailed} 连接还在。
 */
public abstract class BrowserException extends RuntimeException {

    private BrowserException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 浏览器连接不可用：MCP 进程退出、管道断开、调用超时，或用户没有在限定时间内同意连接。需要用户检查 Chrome 后重连。
     */
    public static final class Disconnected extends BrowserException {

        public Disconnected(String message) {
            super(message, null);
        }

        public Disconnected(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 浏览器工具返回了错误（连接本身仍然可用），或返回的内容无法解析。
     */
    public static final class ToolFailed extends BrowserException {

        public ToolFailed(String message) {
            super(message, null);
        }
    }
}
