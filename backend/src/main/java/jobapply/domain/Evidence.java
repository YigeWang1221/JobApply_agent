package jobapply.domain;

import java.util.Set;

/**
 * 提交证据（{@code evidence.submitted}）：成功页或官方投递历史的原文与时间；或者用户自己确认已经提交
 * （{@code user-reported}，原文是用户的话或「点了确认」，C007 用户选 A，2026-10-23）。
 */
public record Evidence(String kind, String text, String officialTime, String localTime, String verifiedAt) {

    public static final String USER_REPORTED = "user-reported";
    public static final Set<String> KINDS = Set.of("success-page", "history-record", USER_REPORTED);

    public boolean isComplete() {
        return KINDS.contains(kind) && !isBlank(text) && !isBlank(localTime) && !isBlank(verifiedAt);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
