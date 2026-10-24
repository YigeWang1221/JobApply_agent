package jobapply.domain;

/**
 * 一个 Job 的状态部分。构造时检查各字段之间的约束，违反约束的组合无法存在。
 */
public record JobStatus(
        LeadStatus leadStatus,
        String skipReason,
        Phase phase,
        PrepState prepState,
        Outcome outcome,
        Evidence evidence) {

    public JobStatus {
        if (leadStatus == null) {
            throw new IllegalArgumentException("lead_status 不能为空");
        }
        if ((leadStatus == LeadStatus.SKIPPED) != (skipReason != null && !skipReason.isBlank())) {
            throw new IllegalArgumentException("skip_reason 只在 lead_status=skipped 时存在，且不能为空");
        }
        if ((leadStatus == LeadStatus.CONVERTED) != (phase != null)) {
            throw new IllegalArgumentException("phase 只在 lead_status=converted 时存在");
        }
        if ((phase == Phase.PREP) != (prepState != null)) {
            throw new IllegalArgumentException("prep_state 只在 phase=prep 时存在");
        }
        if ((phase == Phase.CLOSED) != (outcome != null)) {
            throw new IllegalArgumentException("outcome 只在 phase=closed 时存在");
        }
    }

    public static JobStatus newLead() {
        return new JobStatus(LeadStatus.NEW, null, null, null, null, null);
    }

    /**
     * 可以开始申请线程：还是候选（new / shortlisted，开始时转为申请），或者在 prep 里、用户还没有接手提交
     * （你在提交、等验证、没确认的不行）。{@code ApplicationRunner.start} 和界面的「开始申请」都用它。
     */
    public boolean canStartApplication() {
        if (leadStatus == LeadStatus.NEW || leadStatus == LeadStatus.SHORTLISTED) {
            return true;
        }
        return phase == Phase.PREP && switch (prepState) {
            case SELECTED, PREPARING, NEEDS_LOGIN, NEEDS_INPUT, HANDOFF_USER, FAILED, AWAITING_REVIEW, PAUSED -> true;
            case USER_SUBMITTING, VERIFICATION_PENDING, UNCONFIRMED -> false;
        };
    }

    /**
     * 用于事件和台账的状态标签，例如 {@code lead:new}、{@code prep:awaiting_review}、{@code closed:rejected}。
     */
    public String label() {
        if (phase == null) {
            return "lead:" + leadStatus.value();
        }
        return switch (phase) {
            case PREP -> "prep:" + prepState.value();
            case CLOSED -> "closed:" + outcome.value();
            default -> phase.value();
        };
    }
}
