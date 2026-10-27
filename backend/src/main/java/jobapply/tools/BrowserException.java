package jobapply.tools;

/**
 * 浏览器调用失败。三种情况分开处理：{@link Disconnected} 连接没了，要重连；{@link Stalled} 连接还在、MCP 没回话（有页面
 * 卡住了），不重连，请用户处理那一页；{@link ToolFailed} 连接还在、工具报了错。
 */
public abstract class BrowserException extends RuntimeException {

    private BrowserException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 浏览器连接不可用：MCP 进程退出、管道断开，或用户没有在限定时间内同意连接。下一次使用时自动重连一次，再不行等用户
     * 检查 Chrome 后点「重新连接」。
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
     * 浏览器卡住了（C011-fix）：MCP 在调用超时内没有回话，连接本身没断。Playwright MCP 在每个响应的末尾都读每一页的标题，
     * 只要用户 Chrome 里有一页不答（闲置久了被冻结或休眠、页面卡死、挂着对话框），所有会话的调用都等着。重连解决不了，
     * 要用户在 Chrome 里把那一页点一下或关掉，再点「重新连接」。
     */
    public static final class Stalled extends BrowserException {

        public Stalled(String message, Throwable cause) {
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
