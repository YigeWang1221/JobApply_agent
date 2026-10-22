package jobapply.persistence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 可以手动拨动的时钟。默认时区 America/New_York，确定性测试用。
 */
public final class TestClock extends Clock {

    private Instant instant;
    private final ZoneId zone;

    private TestClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    public static TestClock at(String isoInstant) {
        return new TestClock(Instant.parse(isoInstant), ZoneId.of("America/New_York"));
    }

    public void set(String isoInstant) {
        instant = Instant.parse(isoInstant);
    }

    public void advance(Duration duration) {
        instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new TestClock(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
