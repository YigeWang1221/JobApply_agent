package jobapply.domain;

import java.util.Locale;

/**
 * 申请的固定阶段（{@code phase}）。
 */
public enum Phase {
    PREP, APPLIED, SCREENING, ASSESSMENT, INTERVIEW, OFFER, CLOSED;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Phase of(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
