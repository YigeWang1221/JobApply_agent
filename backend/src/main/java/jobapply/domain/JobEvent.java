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
        LOGIN_OVERRIDE;

        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Type of(String value) {
            return valueOf(value.toUpperCase(Locale.ROOT));
        }
    }
}
