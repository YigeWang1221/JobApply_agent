package jobapply.workflow.nodes;

import static jobapply.workflow.state.LoginHandoffState.JOB;
import static jobapply.workflow.state.LoginHandoffState.PROJECT;
import static jobapply.workflow.state.LoginHandoffState.SESSION;

import java.util.Set;
import jobapply.domain.Actor;
import jobapply.domain.Job;
import jobapply.domain.Phase;
import jobapply.domain.PrepState;
import jobapply.domain.Transition;
import jobapply.persistence.JobLedger;
import jobapply.persistence.JobRepository;
import jobapply.persistence.StorageLayout;
import jobapply.workflow.state.LoginHandoffState;

/**
 * 登录接力只在 {@code preparing} 与 {@code needs_login} 之间推进 {@code prep_state}，一律经过 {@link JobLedger}，
 * 所以事件、台账、git 提交都会跟着更新。Job 已经不在这两个状态（用户在别处改过）时报错，不覆盖。
 */
public final class PrepStateSync {

    private static final Set<PrepState> HANDOFF_STATES = Set.of(PrepState.PREPARING, PrepState.NEEDS_LOGIN);

    private final JobRepository jobs;
    private final JobLedger ledger;

    public PrepStateSync(StorageLayout layout, JobLedger ledger) {
        this.jobs = new JobRepository(layout);
        this.ledger = ledger;
    }

    public void moveTo(LoginHandoffState state, PrepState target, Actor actor, String reason) {
        PrepState current = requireHandoffState(state);
        if (current != target) {
            ledger.transition(state.text(PROJECT), state.text(JOB), state.text(SESSION), actor,
                    new Transition.ChangePrepState(target, reason));
        }
    }

    /** 用户带 override 越过了识别规则：追加一条 {@code login_override} 审计事件，不改状态。 */
    public void recordOverride(LoginHandoffState state, String reason) {
        requireHandoffState(state);
        ledger.recordLoginOverride(state.text(PROJECT), state.text(JOB), state.text(SESSION), Actor.USER, reason);
    }

    /** Job 仍在登录接力能处理的状态（prep 阶段的 preparing / needs_login）时返回当前状态，否则报错。 */
    public PrepState requireHandoffState(LoginHandoffState state) {
        Job job = jobs.load(state.text(PROJECT), state.text(JOB));
        return requireHandoffState(job);
    }

    public static PrepState requireHandoffState(Job job) {
        PrepState current = job.status().prepState();
        if (job.status().phase() != Phase.PREP || !HANDOFF_STATES.contains(current)) {
            throw new IllegalStateException("Job " + job.id() + " 已经不在 preparing / needs_login（现在是 "
                    + job.status().label() + "），登录接力停止");
        }
        return current;
    }
}
