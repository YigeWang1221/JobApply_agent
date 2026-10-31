package jobapply.domain;

import java.util.Locale;

/**
 * {@code phase=closed} 时的结局（{@code outcome}）。{@code posting_closed}：岗位下线了，任何阶段都可以这样结束。
 * <p>
 * C024：{@code apply_failed}（投递失败：想投、投不成）和 {@code gave_up}（不投了：用户决定不再推进）只由用户定下，
 * 任何还没结束的阶段都能记，原因必填（{@code outcome_reason}），可以撤销。{@code not_submitted} 只留给旧记录：
 * 新的「不投了」一律记 {@code gave_up}；{@code withdrawn} 仍是「在网站上撤回了申请」（后续段 {@code record_status}）。
 */
public enum Outcome {
    ACCEPTED, DECLINED, REJECTED, WITHDRAWN, GHOSTED, NOT_SUBMITTED, POSTING_CLOSED, APPLY_FAILED, GAVE_UP;

    /** 用户在工作台里记的结果（C024）：投递失败、不投了。 */
    public boolean userMarked() {
        return this == APPLY_FAILED || this == GAVE_UP;
    }

    /** 用户看到的说法（C024 的两种；其它结局用值本身）。 */
    public String userLabel() {
        return switch (this) {
            case APPLY_FAILED -> "投递失败";
            case GAVE_UP -> "不投了";
            default -> value();
        };
    }

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Outcome of(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
