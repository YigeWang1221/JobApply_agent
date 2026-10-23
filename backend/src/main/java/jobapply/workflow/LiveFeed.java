package jobapply.workflow;

import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import jobapply.domain.AgentRun;
import jobapply.domain.Timestamps;
import jobapply.harness.runtime.LoopObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 正在跑的 Session / Job 现在在做什么（C006-agent，只在内存里）：当前活动、这一轮已经执行完但还没写进对话的工具结果，
 * 以及订阅的一方（SSE）。{@link SessionRunner} 和 {@link ApplicationRunner} 各有一个，按 Session id / Job id 分开。
 */
public final class LiveFeed {

    /**
     * 现在在做什么。
     *
     * @param kind  {@code thinking}（在等模型）或 {@code tool}
     * @param call  {@code tool} 时是哪个调用；{@code thinking} 时为 null
     * @param since 开始的时间（ISO 字符串）
     */
    public record Activity(String kind, AgentRun.ToolCall call, String since) {
    }

    /**
     * 模型这一轮的回复和已经执行完的工具结果，还没写进对话（申请线程每一轮结束才存盘；Session 的结果在一轮的工具都跑完才写）。
     * 视图里补在对话后面，界面上就能看到每一步刚完成的样子。
     */
    public record InFlight(AgentRun.Turn assistant, Map<String, AgentRun.ToolResult> results) {
    }

    /** 订阅的一方（SSE）。在跑循环的线程里调用，不要阻塞太久；抛异常时就不再通知它。 */
    public interface Listener {

        /** {@code activity} 为 null 时表示停下了。 */
        void activity(Activity activity);

        void step(AgentRun.ToolCall call, AgentRun.ToolResult result);

        void thinking(String text);

        void message(String text);

        void status(String status);
    }

    private static final Logger log = LoggerFactory.getLogger(LiveFeed.class);

    private final Clock clock;
    private final Map<String, Activity> activities = new HashMap<>();
    private final Map<String, InFlight> inFlight = new HashMap<>();
    private final Map<String, List<Listener>> listeners = new HashMap<>();

    public LiveFeed(Clock clock) {
        this.clock = clock;
    }

    public synchronized Activity activity(String id) {
        return activities.get(id);
    }

    public synchronized InFlight inFlight(String id) {
        InFlight current = inFlight.get(id);
        return current == null ? null : new InFlight(current.assistant(), Map.copyOf(current.results()));
    }

    /** 订阅；返回取消订阅。 */
    public synchronized Runnable subscribe(String id, Listener listener) {
        listeners.computeIfAbsent(id, key -> new CopyOnWriteArrayList<>()).add(listener);
        return () -> unsubscribe(id, listener);
    }

    /** 给这个 Session / Job 的循环用的观察者。 */
    LoopObserver observer(String id) {
        return new LoopObserver() {
            @Override
            public void thinking() {
                synchronized (LiveFeed.this) {
                    // 上一轮的工具结果已经写进对话
                    inFlight.remove(id);
                }
                setActivity(id, new Activity("thinking", null, Timestamps.now(clock)));
            }

            @Override
            public void replied(AgentRun.Turn assistant) {
                if (!assistant.calls().isEmpty()) {
                    synchronized (LiveFeed.this) {
                        inFlight.put(id, new InFlight(assistant, new LinkedHashMap<>()));
                    }
                }
                if (assistant.thinking() != null) {
                    publish(id, listener -> listener.thinking(assistant.thinking()));
                }
                if (assistant.text() != null && !assistant.text().isBlank()) {
                    publish(id, listener -> listener.message(assistant.text().strip()));
                }
            }

            @Override
            public void tool(AgentRun.ToolCall call) {
                setActivity(id, new Activity("tool", call, Timestamps.now(clock)));
            }

            @Override
            public void done(AgentRun.ToolCall call, AgentRun.ToolResult result) {
                synchronized (LiveFeed.this) {
                    InFlight current = inFlight.get(id);
                    if (current != null) {
                        current.results().put(call.id(), result);
                    }
                }
                publish(id, listener -> listener.step(call, result));
            }
        };
    }

    /** 开始跑了。 */
    void started(String id, String status) {
        publish(id, listener -> listener.status(status));
    }

    /** 跑完了：清掉活动和还没写进对话的结果，告诉订阅的一方最后的状态。 */
    void finished(String id, String status) {
        synchronized (this) {
            activities.remove(id);
            inFlight.remove(id);
        }
        publish(id, listener -> listener.activity(null));
        publish(id, listener -> listener.status(status));
    }

    private void setActivity(String id, Activity activity) {
        synchronized (this) {
            activities.put(id, activity);
        }
        publish(id, listener -> listener.activity(activity));
    }

    private void publish(String id, Consumer<Listener> event) {
        List<Listener> current;
        synchronized (this) {
            current = listeners.getOrDefault(id, List.of());
        }
        for (Listener listener : current) {
            try {
                event.accept(listener);
            } catch (RuntimeException e) {
                log.debug("{} 的订阅断开了：{}", id, e.getMessage());
                unsubscribe(id, listener);
            }
        }
    }

    private synchronized void unsubscribe(String id, Listener listener) {
        List<Listener> current = listeners.get(id);
        if (current != null) {
            current.remove(listener);
            if (current.isEmpty()) {
                listeners.remove(id);
            }
        }
    }
}
