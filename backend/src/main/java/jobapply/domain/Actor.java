package jobapply.domain;

import java.util.Locale;

/**
 * 谁发起了一次变化。
 */
public enum Actor {
    USER, AGENT, SYSTEM;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Actor of(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
