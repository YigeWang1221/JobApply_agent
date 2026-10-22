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
    }

    public enum Type {
        LEAD_CREATED, LEAD_STATUS_CHANGED, CONVERTED, PREP_STATE_CHANGED, PHASE_CHANGED, CLOSED;

        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Type of(String value) {
            return valueOf(value.toUpperCase(Locale.ROOT));
        }
    }
}
