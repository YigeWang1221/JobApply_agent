package jobapply.domain;

import java.util.Locale;

/**
 * {@code events.jsonl} 中的一行。每条事件都带 session。
 */
public record JobEvent(
        String at,
        String session,
        Actor actor,
        Type type,
        String from,
        String to,
        String reason,
        Evidence evidence) {

    public JobEvent {
        if (session == null || session.isBlank()) {
            throw new IllegalArgumentException("事件必须带 session");
        }
        if (actor == null || type == null) {
            throw new IllegalArgumentException("事件必须带 actor 和 type");
        }
    }

    public enum Type {
        LEAD_CREATED, LEAD_STATUS_CHANGED, CONVERTED, PREP_STATE_CHANGED, PHASE_CHANGED, CLOSED,
        /** 识别规则认为仍在登录页，用户带 override 继续。不改状态，只做审计。 */
        LOGIN_OVERRIDE,
        /** 用户反馈看过了、没有变化（C007）。不改状态，只推后下一次查看。 */
        STATUS_CHECKED,
        /** 对话 Agent 按这个岗位给简历排了名、选了一份（C008）。不改状态，只做记录。 */
        RESUME_CHOSEN,
        /** 记下或更新了一轮（C014-A）。阶段跟着变时另有一条 phase_changed / closed。 */
        ROUND_RECORDED,
        /** 写了一轮的复盘（C014-A）。不改状态。 */
        REVIEW_SAVED,
        /** 用户撤销了「投递失败 / 不投了」（C024），回到记之前的状态。 */
        REOPENED;

        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Type of(String value) {
            return valueOf(value.toUpperCase(Locale.ROOT));
        }
    }
}
