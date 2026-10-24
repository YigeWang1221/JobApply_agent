package jobapply.domain;

import static jobapply.domain.PrepState.AWAITING_REVIEW;
import static jobapply.domain.PrepState.FAILED;
import static jobapply.domain.PrepState.HANDOFF_USER;
import static jobapply.domain.PrepState.NEEDS_INPUT;
import static jobapply.domain.PrepState.NEEDS_LOGIN;
import static jobapply.domain.PrepState.PAUSED;
import static jobapply.domain.PrepState.PREPARING;
import static jobapply.domain.PrepState.SELECTED;
import static jobapply.domain.PrepState.UNCONFIRMED;
import static jobapply.domain.PrepState.USER_SUBMITTING;
import static jobapply.domain.PrepState.VERIFICATION_PENDING;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 候选与申请的合法跳转。纯函数：不读写文件，非法时抛出 {@link IllegalTransitionException}。
 * 规则见 plan/master/workflow.md「状态」与 plan/coding/C002-memory-ledger.md「状态机」。
 */
public final class ApplicationStateMachine {

    private static final Map<LeadStatus, Set<LeadStatus>> LEAD_EDGES = new EnumMap<>(LeadStatus.class);
    private static final Map<PrepState, Set<PrepState>> PREP_EDGES = new EnumMap<>(PrepState.class);
    private static final Set<PrepState> APPLIED_FROM = EnumSet.of(USER_SUBMITTING, VERIFICATION_PENDING, UNCONFIRMED);
    private static final Set<Phase> AFTER_APPLIED =
            EnumSet.of(Phase.APPLIED, Phase.SCREENING, Phase.ASSESSMENT, Phase.INTERVIEW, Phase.OFFER);
    private static final Set<Phase> PHASE_TARGETS =
            EnumSet.of(Phase.SCREENING, Phase.ASSESSMENT, Phase.INTERVIEW, Phase.OFFER);

    static {
        LEAD_EDGES.put(LeadStatus.NEW, EnumSet.of(LeadStatus.SHORTLISTED, LeadStatus.SKIPPED, LeadStatus.CLOSED));
        LEAD_EDGES.put(LeadStatus.SHORTLISTED, EnumSet.of(LeadStatus.SKIPPED, LeadStatus.NEW, LeadStatus.CLOSED));
        LEAD_EDGES.put(LeadStatus.CLOSED, EnumSet.of(LeadStatus.NEW));
        LEAD_EDGES.put(LeadStatus.SKIPPED, EnumSet.noneOf(LeadStatus.class));
        LEAD_EDGES.put(LeadStatus.CONVERTED, EnumSet.noneOf(LeadStatus.class));

        PREP_EDGES.put(SELECTED, EnumSet.of(PREPARING, PAUSED));
        PREP_EDGES.put(PREPARING, EnumSet.of(NEEDS_INPUT, NEEDS_LOGIN, HANDOFF_USER, FAILED, AWAITING_REVIEW, PAUSED));
        for (PrepState blocked : EnumSet.of(NEEDS_INPUT, NEEDS_LOGIN, HANDOFF_USER, FAILED)) {
            PREP_EDGES.put(blocked, EnumSet.of(PREPARING, PAUSED));
        }
        PREP_EDGES.put(AWAITING_REVIEW, EnumSet.of(USER_SUBMITTING, PREPARING, PAUSED));
        PREP_EDGES.put(USER_SUBMITTING, EnumSet.of(VERIFICATION_PENDING, UNCONFIRMED));
        PREP_EDGES.put(VERIFICATION_PENDING, EnumSet.of(UNCONFIRMED));
        PREP_EDGES.put(UNCONFIRMED, EnumSet.noneOf(PrepState.class));
        PREP_EDGES.put(PAUSED, EnumSet.of(SELECTED, PREPARING));
    }

    private ApplicationStateMachine() {
    }

    public static JobStatus next(JobStatus current, Transition transition, Actor actor) {
        if (current.phase() == Phase.CLOSED) {
            throw new IllegalTransitionException("申请已经结束（closed），不能再变化");
        }
        return switch (transition) {
            case Transition.MarkLead mark -> markLead(current, mark);
            case Transition.Convert convert -> convert(current);
            case Transition.ChangePrepState change -> changePrepState(current, change.to(), actor);
            case Transition.MarkApplied applied -> markApplied(current, applied.evidence());
            case Transition.ChangePhase change -> changePhase(current, change.to());
            case Transition.Close close -> close(current, close.outcome(), actor);
        };
    }

    private static JobStatus markLead(JobStatus current, Transition.MarkLead mark) {
        LeadStatus to = mark.to();
        if (to == LeadStatus.CONVERTED) {
            throw new IllegalTransitionException("转为申请请使用 Convert");
        }
        if (!LEAD_EDGES.get(current.leadStatus()).contains(to)) {
            throw new IllegalTransitionException(
                    "候选状态不能从 " + current.leadStatus().value() + " 变为 " + to.value());
        }
        if (to == LeadStatus.SKIPPED && (mark.reason() == null || mark.reason().isBlank())) {
            throw new IllegalTransitionException("标记为不投（skipped）必须写原因");
        }
        String reason = to == LeadStatus.SKIPPED ? mark.reason() : null;
        return new JobStatus(to, reason, null, null, null, null);
    }

    private static JobStatus convert(JobStatus current) {
        LeadStatus lead = current.leadStatus();
        if (lead != LeadStatus.NEW && lead != LeadStatus.SHORTLISTED) {
            throw new IllegalTransitionException("只有 new 或 shortlisted 的候选可以转为申请，当前是 " + lead.value());
        }
        return new JobStatus(LeadStatus.CONVERTED, null, Phase.PREP, SELECTED, null, null);
    }

    private static JobStatus changePrepState(JobStatus current, PrepState to, Actor actor) {
        requirePrep(current);
        // 用户说的事实优先于流程（C006-fix 第 3 节）：用户随时可能自己在 Chrome 里提交了（先放着之后、等回答时……），
        // 说提交了，准备中的任何状态都能进 user_submitting；说还没提交 / 先放着，准备中的任何状态都能进 paused。
        // Agent 只能按边走（例如只能从停在提交前进 user_submitting）
        boolean userFact = actor == Actor.USER && to != current.prepState()
                && (to == USER_SUBMITTING && !APPLIED_FROM.contains(current.prepState()) || to == PAUSED);
        if (!userFact && !PREP_EDGES.get(current.prepState()).contains(to)) {
            throw new IllegalTransitionException(
                    "prep_state 不能从 " + current.prepState().value() + " 变为 " + to.value());
        }
        return new JobStatus(LeadStatus.CONVERTED, null, Phase.PREP, to, null, current.evidence());
    }

    private static JobStatus markApplied(JobStatus current, Evidence evidence) {
        requirePrep(current);
        if (!APPLIED_FROM.contains(current.prepState())) {
            throw new IllegalTransitionException(
                    "只能从 user_submitting / verification_pending / unconfirmed 进入 applied，当前是 "
                            + current.prepState().value());
        }
        if (evidence == null || !evidence.isComplete()) {
            throw new IllegalTransitionException(
                    "进入 applied 必须有完整证据：kind 为 success-page、history-record 或 user-reported，text、local_time、verified_at 不能为空");
        }
        return new JobStatus(LeadStatus.CONVERTED, null, Phase.APPLIED, null, null, evidence);
    }

    private static JobStatus changePhase(JobStatus current, Phase to) {
        if (to == Phase.APPLIED) {
            throw new IllegalTransitionException("进入 applied 请使用 MarkApplied，并附上证据");
        }
        if (to == Phase.CLOSED) {
            throw new IllegalTransitionException("结束申请请使用 Close，并给出 outcome");
        }
        if (!PHASE_TARGETS.contains(to)) {
            throw new IllegalTransitionException("不能回到 " + to.value());
        }
        if (current.phase() == null || !AFTER_APPLIED.contains(current.phase())) {
            throw new IllegalTransitionException("只有 applied 之后的申请才能变为 " + to.value());
        }
        if (current.phase() == to) {
            throw new IllegalTransitionException("申请已经处于 " + to.value());
        }
        return new JobStatus(LeadStatus.CONVERTED, null, to, null, null, current.evidence());
    }

    private static JobStatus close(JobStatus current, Outcome outcome, Actor actor) {
        if (outcome == null) {
            throw new IllegalTransitionException("结束申请必须给出 outcome");
        }
        Phase phase = current.phase();
        if (phase == null) {
            throw new IllegalTransitionException("候选还没有转为申请；不投请标记 skipped，下线了请标记 closed");
        }
        boolean allowed = switch (outcome) {
            case NOT_SUBMITTED -> phase == Phase.PREP;
            case POSTING_CLOSED -> phase != Phase.CLOSED;
            case ACCEPTED, DECLINED -> phase == Phase.OFFER;
            case REJECTED, WITHDRAWN, GHOSTED -> AFTER_APPLIED.contains(phase);
        };
        if (!allowed) {
            throw new IllegalTransitionException("阶段 " + phase.value() + " 不能以 " + outcome.value() + " 结束");
        }
        if (outcome == Outcome.GHOSTED && actor != Actor.USER) {
            throw new IllegalTransitionException("ghosted 只能由用户手动关闭时选择");
        }
        return new JobStatus(LeadStatus.CONVERTED, null, Phase.CLOSED, null, outcome, current.evidence());
    }

    private static void requirePrep(JobStatus current) {
        if (current.phase() != Phase.PREP) {
            throw new IllegalTransitionException("只有 phase=prep 的申请才有 prep_state，当前是 " + current.label());
        }
    }
}
