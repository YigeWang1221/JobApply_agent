package jobapply.domain;

import java.util.Locale;

/**
 * {@code phase=closed} 时的结局（{@code outcome}）。
 */
public enum Outcome {
    ACCEPTED, DECLINED, REJECTED, WITHDRAWN, GHOSTED, NOT_SUBMITTED;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Outcome of(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
