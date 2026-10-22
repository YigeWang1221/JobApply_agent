package jobapply.domain;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 记录里的时间统一写成带时区偏移、精确到秒的 ISO 字符串。
 */
public final class Timestamps {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private Timestamps() {
    }

    public static String now(Clock clock) {
        return format(OffsetDateTime.now(clock));
    }

    public static String format(OffsetDateTime time) {
        return time.format(FORMAT);
    }
}
