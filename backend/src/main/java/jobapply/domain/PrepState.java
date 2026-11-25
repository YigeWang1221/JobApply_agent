package jobapply.domain;

import java.util.Locale;

/**
 * {@code phase=prep} 内部的子状态（{@code prep_state}），C030 收敛为 5 个：转为申请后从 {@code preparing} 开始，
 * 停下来要用户做事（补信息、登录、验证码、接手）是 {@code needs_input}，填完停在提交前是 {@code awaiting_review}，
 * 另有 {@code paused}（先放着）和 {@code failed}（Agent 出错，可重试）。有提交证据后离开 prep 进入 {@code applied}。
 * <p>
 * 旧 {@code job.md} 里可能还写着 C030 之前的细粒度值，读存下来的值一律经过 {@link #fromStorage}。
 */
public enum PrepState {
    PREPARING, NEEDS_INPUT, AWAITING_REVIEW, PAUSED, FAILED;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 只认当前的 5 个值（API、工具参数）。存下来的值请用 {@link #fromStorage}。 */
    public static PrepState of(String value) {
        return valueOf(value.strip().toUpperCase(Locale.ROOT));
    }

    /**
     * 读 {@code job.md} 的 {@code prep_state}、{@code reopen_to} 等存下来的值：当前的 5 个值原样，C030 之前写过的细粒度值
     * 映射到现在的状态（写回时就是新值）：
     * <ul>
     *   <li>{@code selected} → {@code preparing}（转为申请后的起点）</li>
     *   <li>{@code needs_login}、{@code handoff_user} → {@code needs_input}（都是停下来等用户做事）</li>
     *   <li>{@code user_submitting}、{@code verification_pending}、{@code unconfirmed} → {@code awaiting_review}
     *       （在用户手里、还没有提交证据；用户确认提交后照常进 applied）</li>
     * </ul>
     * 从来没有写过的值仍然报错。
     */
    public static PrepState fromStorage(String value) {
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "selected" -> PREPARING;
            case "needs_login", "handoff_user" -> NEEDS_INPUT;
            case "user_submitting", "verification_pending", "unconfirmed" -> AWAITING_REVIEW;
            default -> of(value);
        };
    }
}
