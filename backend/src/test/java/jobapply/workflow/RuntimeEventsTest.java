package jobapply.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RuntimeEventsTest {

    /** 连接卡住时积压到上限就断开（C006-fix-2 最终审查第 4 条）；发布的一方不被挡住。 */
    @Test
    void stalledSubscriberIsDroppedOnceItsBacklogIsFull() throws InterruptedException {
        RuntimeEvents events = new RuntimeEvents(Clock.systemUTC());
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch dropped = new CountDownLatch(1);
        events.subscribe(null, events.all(), event -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, dropped::countDown);
        try {
            // 投递任务最多取走一条卡在 sink 里，其余在队列里；多两条一定超过上限
            for (int i = 0; i < RuntimeEvents.BUFFER + 2; i++) {
                events.changed("s1", "j1");
            }
            assertThat(dropped.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
        }
    }
}
