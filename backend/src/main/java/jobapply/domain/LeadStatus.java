package jobapply.domain;

import java.util.Locale;

/**
 * 候选池状态（{@code lead_status}）。
 */
public enum LeadStatus {
    NEW, SHORTLISTED, SKIPPED, CONVERTED;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static LeadStatus of(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
