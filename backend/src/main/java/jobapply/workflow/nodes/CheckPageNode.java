package jobapply.workflow.nodes;

import static jobapply.workflow.state.LoginHandoffState.ACCOUNT_HINT;
import static jobapply.workflow.state.LoginHandoffState.IDENTITY;
import static jobapply.workflow.state.LoginHandoffState.JOB;
import static jobapply.workflow.state.LoginHandoffState.NEXT;
import static jobapply.workflow.state.LoginHandoffState.OVERRIDE;
import static jobapply.workflow.state.LoginHandoffState.REASON;
import static jobapply.workflow.state.LoginHandoffState.SIGNAL;

import java.util.Locale;
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
 * 用户发了 {@code logged_in} 之后：只读取自己那个标签页的快照，确认已经离开登录页，再核对身份。
 * 当前标签页不是本 Job 新开的（应用重启后、或另一个 Job 打开过页面），就先回到 open_page 重新新开岗位页。
 * 用了 override 时追加一条 {@code login_override} 审计事件。
 * 仍在登录页时回到 wait_login，除非用户带了 override（识别规则可能误判）。
 * 身份核对：在页面可见文字里找用户填写的账号标识（忽略大小写的包含匹配）；没填或没找到就交给用户确认。
 */
public final class CheckPageNode implements NodeAction<LoginHandoffState> {

    private final BrowserSession browser;
    private final PrepStateSync prep;

    public CheckPageNode(BrowserSession browser, PrepStateSync prep) {
        this.browser = browser;
        this.prep = prep;
    }

    @Override
    public Map<String, Object> apply(LoginHandoffState state) {
        String jobId = state.text(JOB);
        if (!browser.ownsTab(jobId)) {
            // 当前标签页不是本 Job 的（应用重启后连接是新的，或另一个 Job 打开过页面）：重新新开岗位页，再交给用户确认
            return Map.of(REASON, "当前标签页不是这个岗位的，重新打开岗位页", NEXT, NodeIds.OPEN_PAGE);
        }
        PageSnapshot page;
        try {
            page = browser.snapshot(jobId);
        } catch (BrowserException.Disconnected e) {
            return Nodes.disconnected(e);
        }
        LoginPageDetector.Detection detection = LoginPageDetector.detect(page);
        boolean override = state.flag(OVERRIDE);
        Map<String, Object> values = Nodes.page(page, detection);
        values.put(OVERRIDE, false);
        values.put(SIGNAL, "");

        if (detection.blocksUser() && !override) {
            prep.moveTo(state, PrepState.NEEDS_LOGIN, Actor.SYSTEM, "仍在" + Nodes.describe(detection));
            values.put(REASON, "仍在" + Nodes.describe(detection) + "：登录后再发送 logged_in；"
                    + "如果确认已经登录，可以带 override 发送");
            values.put(NEXT, NodeIds.WAIT_LOGIN);
            return values;
        }
        if (detection.blocksUser()) {
            prep.recordOverride(state, "识别规则认为仍在" + Nodes.describe(detection) + "，用户确认已登录");
        }
        prep.moveTo(state, PrepState.PREPARING, Actor.USER, detection.blocksUser()
                ? "用户 override：识别规则认为仍在" + Nodes.describe(detection)
                : "用户确认已登录");

        String hint = state.text(ACCOUNT_HINT).trim();
        if (!hint.isEmpty() && page.visibleText().toLowerCase(Locale.ROOT).contains(hint.toLowerCase(Locale.ROOT))) {
            values.put(IDENTITY, "matched");
            values.put(REASON, "页面上找到了账号标识");
            values.put(NEXT, NodeIds.FINISH);
            return values;
        }
        values.put(IDENTITY, "unverified");
        values.put(REASON, (hint.isEmpty() ? "没有填写账号标识" : "页面上没有找到账号标识")
                + "：请确认 Chrome 里登录的是正确的账号，然后发送 identity_confirmed 或 wrong_account");
        values.put(NEXT, NodeIds.WAIT_IDENTITY);
        return values;
    }
}
