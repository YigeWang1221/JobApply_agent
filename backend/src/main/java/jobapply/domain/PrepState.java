package jobapply.domain;

import java.util.Locale;

/**
 * {@code phase=prep} 内部的子状态（{@code prep_state}）。转为申请后从 {@code selected} 开始。
 */
public enum PrepState {
    SELECTED, PREPARING, NEEDS_INPUT, NEEDS_LOGIN, HANDOFF_USER, FAILED, AWAITING_REVIEW, USER_SUBMITTING, VERIFICATION_PENDING, UNCONFIRMED, PAUSED;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static PrepState of(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
