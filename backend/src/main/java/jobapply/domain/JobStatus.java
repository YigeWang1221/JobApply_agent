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
     * 可以开始申请线程：还是候选（new / shortlisted，开始时转为申请），或者还在 prep 里（C030 起 prep 的 5 个状态都可以）。
     * {@code JobThreadService.start} 和界面的「开始申请」都用它。
     */
    public boolean canStartApplication() {
        return leadStatus == LeadStatus.NEW || leadStatus == LeadStatus.SHORTLISTED || phase == Phase.PREP;
    }

    /** 投递段（{@code apply} Mode）还是后续段（{@code followup} Mode），只看 {@code phase}（{@link Phase#applySegment}）。 */
    public boolean applySegment() {
        return Phase.applySegment(phase);
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
