package jobapply.domain;

import java.util.Locale;

/**
 * 候选池状态（{@code lead_status}）。{@code closed}：岗位已下线 / 停止招聘（页面上看到的，或用户说的）；重新开放时可以改回 new。
 */
public enum LeadStatus {
    NEW, SHORTLISTED, SKIPPED, CLOSED, CONVERTED;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static LeadStatus of(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
