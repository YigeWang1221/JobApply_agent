package jobapply.domain;

import java.util.Locale;

/**
 * {@code phase=closed} 时的结局（{@code outcome}）。{@code posting_closed}：岗位下线了，任何阶段都可以这样结束。
 */
public enum Outcome {
    ACCEPTED, DECLINED, REJECTED, WITHDRAWN, GHOSTED, NOT_SUBMITTED, POSTING_CLOSED;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Outcome of(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
