package jobapply.workflow.nodes;

import static jobapply.workflow.state.LoginHandoffState.JOB;
import static jobapply.workflow.state.LoginHandoffState.NEXT;
import static jobapply.workflow.state.LoginHandoffState.REASON;
import static jobapply.workflow.state.LoginHandoffState.URL;

import java.util.Map;
import jobapply.domain.Actor;
import jobapply.domain.PrepState;
import jobapply.tools.BrowserException;
import jobapply.tools.BrowserSession;
import jobapply.tools.LoginPageDetector;
import jobapply.tools.PageSnapshot;
import jobapply.workflow.state.LoginHandoffState;
import org.bsc.langgraph4j.action.NodeAction;

/**
 * 打开岗位链接并取快照。不管有没有识别到登录页，接下来都交给用户（每次申请都交给用户登录）；
 * 识别到时 {@code prep_state} 改为 {@code needs_login}。
 */
public final class OpenPageNode implements NodeAction<LoginHandoffState> {

    private final BrowserSession browser;
    private final PrepStateSync prep;

    public OpenPageNode(BrowserSession browser, PrepStateSync prep) {
        this.browser = browser;
        this.prep = prep;
    }

    @Override
    public Map<String, Object> apply(LoginHandoffState state) {
        PageSnapshot page;
        try {
            page = browser.open(state.text(URL), state.text(JOB));
        } catch (BrowserException.Disconnected e) {
            return Nodes.disconnected(e);
        }
        LoginPageDetector.Detection detection = LoginPageDetector.detect(page);
        Map<String, Object> values = Nodes.page(page, detection);
        if (detection.blocksUser()) {
            prep.moveTo(state, PrepState.NEEDS_LOGIN, Actor.SYSTEM, "检测到" + Nodes.describe(detection));
            values.put(REASON, "检测到" + Nodes.describe(detection) + "：请在 Chrome 里完成登录，然后发送 logged_in");
        } else {
            values.put(REASON, "已打开岗位页面：请确认已经登录（需要时在 Chrome 里登录），然后发送 logged_in");
        }
        values.put(NEXT, NodeIds.WAIT_LOGIN);
        return values;
    }
}
