package jobapply.domain;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * 一个 Job 在界面和 {@code list_jobs} 里归哪一组（C006-fix 第 6 节）：只在服务器算一次，前端和对话 Agent 都读它。
 * 各组互不重叠：
 * <ul>
 *   <li>{@code lead}：候选池里还在考虑的（new / shortlisted）</li>
 *   <li>{@code ready}：停在提交前（prep:awaiting_review），等用户审阅、提交</li>
 *   <li>{@code waiting}：准备中、要用户做事：申请线程在等你 / 失败 / 中断，或者 prep_state 是 needs_input /
 *       needs_login / handoff_user / failed</li>
 *   <li>{@code preparing}：准备中的其余状态（选中、填写中、你在提交、先放着……）</li>
 *   <li>{@code applied}：投递以后、还没结束（applied / screening / assessment / interview / offer）</li>
 *   <li>{@code closed}：候选跳过 / 下线，或者申请已结束</li>
 * </ul>
 */
public enum JobBucket {
    LEAD, PREPARING, WAITING, READY, APPLIED, CLOSED;

    /** 投递以后、还没结束的阶段。 */
    public static final Set<Phase> AFTER_APPLIED = EnumSet.of(Phase.APPLIED, Phase.SCREENING, Phase.ASSESSMENT,
            Phase.INTERVIEW, Phase.OFFER);
    private static final Set<PrepState> WAITING_PREP = EnumSet.of(PrepState.NEEDS_INPUT, PrepState.NEEDS_LOGIN,
            PrepState.HANDOFF_USER, PrepState.FAILED);
    /** 申请线程要用户做事的状态（重启后中断的也算）。 */
    private static final Set<String> WAITING_RUN = Set.of(AgentRun.WAITING_USER, AgentRun.FAILED,
            AgentRun.INTERRUPTED);

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** @param application 申请线程的状态；没有申请线程时为 null */
    public static JobBucket of(JobStatus status, String application) {
        Phase phase = status.phase();
        if (phase == null) {
            LeadStatus lead = status.leadStatus();
            return lead == LeadStatus.NEW || lead == LeadStatus.SHORTLISTED ? LEAD : CLOSED;
        }
        if (phase == Phase.CLOSED) {
            return CLOSED;
        }
        if (phase != Phase.PREP) {
            return APPLIED;
        }
        if (status.prepState() == PrepState.AWAITING_REVIEW) {
            return READY;
        }
        boolean waiting = WAITING_PREP.contains(status.prepState())
                || application != null && WAITING_RUN.contains(application);
        return waiting ? WAITING : PREPARING;
    }
}
