package jobapply.workflow.nodes;

import static jobapply.workflow.state.LoginHandoffState.IDENTITY;
import static jobapply.workflow.state.LoginHandoffState.NEXT;
import static jobapply.workflow.state.LoginHandoffState.REASON;
import static jobapply.workflow.state.LoginHandoffState.SIGNAL;

import java.util.LinkedHashMap;
import java.util.Map;
import jobapply.domain.Actor;
import jobapply.domain.PrepState;
import jobapply.workflow.state.LoginHandoffSignal;
import jobapply.workflow.state.LoginHandoffState;
import org.bsc.langgraph4j.action.NodeAction;

/**
 * 处理身份确认的信号：{@code identity_confirmed} → 结束；{@code wrong_account} → 回到登录接力，
 * {@code prep_state} 改为 {@code needs_login}。
 */
public final class WaitIdentityNode implements NodeAction<LoginHandoffState> {

    private final PrepStateSync prep;

    public WaitIdentityNode(PrepStateSync prep) {
        this.prep = prep;
    }

    @Override
    public Map<String, Object> apply(LoginHandoffState state) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(SIGNAL, "");
        if (LoginHandoffSignal.IDENTITY_CONFIRMED.value().equals(state.text(SIGNAL))) {
            values.put(IDENTITY, "user_confirmed");
            values.put(REASON, "用户确认了账号");
            values.put(NEXT, NodeIds.FINISH);
            return values;
        }
        prep.moveTo(state, PrepState.NEEDS_LOGIN, Actor.USER, "用户回报账号不对");
        values.put(IDENTITY, "");
        values.put(REASON, "账号不对：请在 Chrome 里切换到正确的账号，然后发送 logged_in");
        values.put(NEXT, NodeIds.WAIT_LOGIN);
        return values;
    }
}
