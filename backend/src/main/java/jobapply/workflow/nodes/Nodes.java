package jobapply.workflow.nodes;

import static jobapply.workflow.state.LoginHandoffState.DETECTED;
import static jobapply.workflow.state.LoginHandoffState.LAST_ERROR;
import static jobapply.workflow.state.LoginHandoffState.NEXT;
import static jobapply.workflow.state.LoginHandoffState.PAGE_TITLE;
import static jobapply.workflow.state.LoginHandoffState.PAGE_URL;
import static jobapply.workflow.state.LoginHandoffState.REASON;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import jobapply.tools.BrowserException;
import jobapply.tools.LoginPageDetector;
import jobapply.tools.PageSnapshot;

/**
 * 各节点共用的状态片段。
 */
final class Nodes {

    private Nodes() {
    }

    /** 当前页面的 URL、标题和识别结果（不含快照全文）。 */
    static Map<String, Object> page(PageSnapshot page, LoginPageDetector.Detection detection) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(PAGE_URL, page.url());
        values.put(PAGE_TITLE, page.title());
        values.put(DETECTED, detection.kind().name());
        values.put(LAST_ERROR, "");
        return values;
    }

    /** 连接不可用：停到 wait_reconnect，重连后重新打开岗位页。 */
    static Map<String, Object> disconnected(BrowserException.Disconnected e) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(REASON, "浏览器连接断开，或没有在限定时间内同意连接：请在 Chrome 里准备好"
                + "（扩展模式：打开 Playwright 扩展；远程调试模式：确认 chrome://inspect/#remote-debugging 的开关已打开），"
                + "然后发送 reconnected；Chrome 弹出确认时点同意");
        values.put(LAST_ERROR, e.getMessage() == null ? "" : e.getMessage());
        values.put(NEXT, NodeIds.WAIT_RECONNECT);
        return values;
    }

    /** {@code scheme://host[:port]}；解析不了时返回空字符串。 */
    static String origin(String url) {
        try {
            URI uri = new URI(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return "";
            }
            String origin = uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT);
            return uri.getPort() < 0 ? origin : origin + ":" + uri.getPort();
        } catch (Exception e) {
            return "";
        }
    }

    static String describe(LoginPageDetector.Detection detection) {
        return (detection.kind() == LoginPageDetector.Kind.CHALLENGE ? "安全验证" : "登录页")
                + "（" + detection.rule() + "）";
    }
}
