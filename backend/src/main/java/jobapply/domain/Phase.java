package jobapply.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * 申请的固定阶段（{@code phase}）。
 * <p>
 * {@code job.md} 的 {@code phase} 也是 Job 线程选 Mode 的唯一依据（C030），见 {@link #applySegment}。
 */
public enum Phase {
    PREP, APPLIED, SCREENING, ASSESSMENT, INTERVIEW, OFFER, CLOSED;

    /** 投递以后、还没结束的阶段。 */
    public static final Set<Phase> AFTER_APPLIED = Collections.unmodifiableSet(
            EnumSet.of(APPLIED, SCREENING, ASSESSMENT, INTERVIEW, OFFER));

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Phase of(String value) {
        return valueOf(value.strip().toUpperCase(Locale.ROOT));
    }

    /**
     * 投递段（{@code apply} Mode）：还是候选（{@code phase} 为 null）或在 {@code prep}。其余——{@code applied} 及以后、
     * {@code closed}——是后续段（{@code followup} Mode）。
     */
    public static boolean applySegment(Phase phase) {
        return phase == null || phase == PREP;
    }
}
