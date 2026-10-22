package jobapply.workflow.nodes;

import static jobapply.workflow.state.LoginHandoffState.LAST_ERROR;
import static jobapply.workflow.state.LoginHandoffState.NEXT;
import static jobapply.workflow.state.LoginHandoffState.REASON;

import java.util.Map;
import jobapply.tools.BrowserException;
import jobapply.tools.BrowserSession;
import jobapply.workflow.state.LoginHandoffState;
import org.bsc.langgraph4j.action.NodeAction;

/**
 * 用户发了 {@code reconnected}：关掉旧进程、启动新进程，旧的 ref 作废，然后回到 open_page 新开一个岗位页，
 * 照常交给用户确认登录。不对重连后的「当前标签页」取快照，因为在远程调试模式下那可能是用户自己的标签页。
 * 本段还没有任何表单写入，重新打开不会重放写操作。真正的连接（以及用户在 Chrome 里的确认）发生在第一次调用时。
 */
public final class ReconnectNode implements NodeAction<LoginHandoffState> {

    private final BrowserSession browser;

    public ReconnectNode(BrowserSession browser) {
        this.browser = browser;
    }

    @Override
    public Map<String, Object> apply(LoginHandoffState state) {
        try {
            browser.reconnect();
        } catch (BrowserException.Disconnected e) {
            return Nodes.disconnected(e);
        }
        return Map.of(REASON, "已重新连接", LAST_ERROR, "", NEXT, NodeIds.OPEN_PAGE);
    }
}
