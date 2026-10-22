package jobapply.workflow.nodes;

import static jobapply.workflow.state.LoginHandoffState.ACCOUNT_HINT;
import static jobapply.workflow.state.LoginHandoffState.IDENTITY;
import static jobapply.workflow.state.LoginHandoffState.NEXT;
import static jobapply.workflow.state.LoginHandoffState.PAGE_URL;
import static jobapply.workflow.state.LoginHandoffState.REASON;
import static jobapply.workflow.state.LoginHandoffState.URL;

import java.util.Map;
import jobapply.persistence.SiteRegistry;
import jobapply.workflow.state.LoginHandoffState;
import org.bsc.langgraph4j.action.NodeAction;

/**
 * 已登录、身份已核对：先确认 Job 仍在 preparing / needs_login，再写入 {@code browser/sites.md}（按当前页面的 origin），线程停到 done。
 */
public final class FinishNode implements NodeAction<LoginHandoffState> {

    private final SiteRegistry sites;
    private final PrepStateSync prep;

    public FinishNode(SiteRegistry sites, PrepStateSync prep) {
        this.sites = sites;
        this.prep = prep;
    }

    @Override
    public Map<String, Object> apply(LoginHandoffState state) {
        // 等待身份确认期间，Job 可能在别处被暂停或推进了：不符就让这次运行失败，不写登记
        prep.requireHandoffState(state);
        String origin = Nodes.origin(state.text(PAGE_URL));
        if (origin.isEmpty()) {
            origin = Nodes.origin(state.text(URL));
        }
        String method = "matched".equals(state.text(IDENTITY)) ? "matched" : "user_confirmed";
        sites.recordLogin(origin, state.text(ACCOUNT_HINT), method);
        return Map.of(
                REASON, "已登录并核对了账号；下一步是填写（里程碑第 3 段）",
                NEXT, NodeIds.DONE);
    }
}
