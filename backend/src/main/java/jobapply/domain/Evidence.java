package jobapply.domain;

import java.util.Set;

/**
 * 提交证据（{@code evidence.submitted}）：成功页或官方投递历史的原文与时间。
 */
public record Evidence(String kind, String text, String officialTime, String localTime, String verifiedAt) {

    public static final Set<String> KINDS = Set.of("success-page", "history-record");

    public boolean isComplete() {
        return KINDS.contains(kind) && !isBlank(text) && !isBlank(localTime) && !isBlank(verifiedAt);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
