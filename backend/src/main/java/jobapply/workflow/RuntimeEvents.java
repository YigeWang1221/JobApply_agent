package jobapply.workflow;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Predicate;
import jobapply.domain.AgentRun;
import jobapply.domain.Timestamps;
import jobapply.harness.runtime.LoopObserver;
import jobapply.tools.BrowserState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 应用级事件日志（C006-fix 第 1 节，参考 OpenMAIC 的 Session 事件日志）：每个事件带递增序号和启动纪元，放在内存环形缓冲里；
 * 订阅的一方（{@code GET /api/events}）先补发 {@code Last-Event-ID} 之后的事件，再跟着新事件。真相仍在文件里
 * （transcript、job.md、run state），事件只说「什么变了」。
 * <p>
 * 也记着两个循环现在在做什么（当前活动）和这一轮已经执行完、还没写进对话的工具结果，视图要用。
 * 两个 Runner 和台账共用一个实例。
 */
public final class RuntimeEvents {

    /** 缓冲里最多留这么多事件；更早的要整页重读（{@code reset}）。 */
    static final int BUFFER = 2_000;

    public static final String ACTIVITY = "activity";
    public static final String STEP = "step";
    public static final String MESSAGE = "message";
    public static final String STATUS = "status";
    public static final String CHANGED = "changed";
    /** 浏览器连接状态变了（全局事件，{@code session}、{@code job} 都为 null，payload 是 {@link BrowserState}）。 */
    public static final String BROWSER = "browser";

    /**
     * 现在在做什么。
     *
     * @param kind  {@code thinking}（在等模型）或 {@code tool}
     * @param call  {@code tool} 时是哪个调用；{@code thinking} 时为 null
     * @param since 开始的时间（ISO 字符串）
     */
    public record Activity(String kind, AgentRun.ToolCall call, String since) {
    }

    /** 模型这一轮的回复和已经执行完的工具结果，还没写进对话；视图里补在对话后面。 */
    public record InFlight(AgentRun.Turn assistant, Map<String, AgentRun.ToolResult> results) {
    }

    /** 一步：执行完的工具调用（{@code thinking} 为 null），或者模型的思考（{@code call} 为 null）。 */
    public record Step(AgentRun.ToolCall call, AgentRun.ToolResult result, String thinking) {
    }

    /**
     * 一个事件。{@code payload}：{@code activity} 是 {@link Activity}（null 表示空闲）；{@code step} 是 {@link Step}；
     * {@code message} 是文字；{@code status} 是状态；{@code changed} 为 null。
     */
    public record Event(long seq, String name, String session, String job, Object payload) {
    }

    /** 一次订阅：先发 {@code reset}（纪元不对或缓冲里已经没有）或补发 {@code backlog}，之后新事件交给 {@code sink}。 */
    public record Subscription(boolean reset, List<Event> backlog, String position, Runnable cancel) {
    }

    private static final Logger log = LoggerFactory.getLogger(RuntimeEvents.class);

    private final Clock clock;
    private final String epoch = Long.toString(System.currentTimeMillis());
    private final ArrayDeque<Event> buffer = new ArrayDeque<>();
    private final List<Subscriber> subscribers = new ArrayList<>();
    private final Map<String, Activity> activities = new HashMap<>();
    private final Map<String, InFlight> inFlight = new HashMap<>();
    /** Job → 和它一起出现过的 Session（Session 的过滤用）。 */
    private final Map<String, Set<String>> links = new HashMap<>();
    private long seq;

    /** 投递：每个订阅一个任务，按顺序把队列里的事件交给 {@code sink}，不在任何锁里（C006-fix-2 回归第 4 条）。 */
    private final ExecutorService delivery = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("event-delivery-", 0).factory());

    /**
     * 一个订阅：发布时只放进它的队列；有一个投递任务在取（{@code draining}），同一订阅里按序号顺序。
     * 队列里积了 {@link #BUFFER} 条还没发出去（连接卡住了）时丢掉这次订阅（C006-fix-2 最终审查第 4 条）。
     */
    private final class Subscriber {

        private final Predicate<Event> filter;
        private final Consumer<Event> sink;
        private final Runnable dropped;
        private final ArrayDeque<Event> queue = new ArrayDeque<>();
        private boolean draining;
        private boolean cancelled;

        Subscriber(Predicate<Event> filter, Consumer<Event> sink, Runnable dropped) {
            this.filter = filter;
            this.sink = sink;
            this.dropped = dropped;
        }

        /**
         * 在 {@link RuntimeEvents} 的锁里调用：只入队，必要时开一个投递任务。队列满了时取消这次订阅、清空队列，返回 false
         * （调用方把它移出订阅列表、通知 {@code dropped}）；已经取消的也返回 true（取消它的一方会移出）。
         */
        boolean offer(Event event) {
            synchronized (this) {
                if (cancelled) {
                    return true;
                }
                if (queue.size() >= BUFFER) {
                    cancelled = true;
                    queue.clear();
                    return false;
                }
                queue.addLast(event);
                if (draining) {
                    return true;
                }
                draining = true;
            }
            delivery.execute(this::drain);
            return true;
        }

        private void drain() {
            while (true) {
                Event event;
                synchronized (this) {
                    event = cancelled ? null : queue.pollFirst();
                    if (event == null) {
                        draining = false;
                        return;
                    }
                }
                try {
                    sink.accept(event);
                } catch (RuntimeException e) {
                    log.debug("一条推送断开了：{}", e.getMessage());
                    unsubscribe(this);
                }
            }
        }

        synchronized void cancel() {
            cancelled = true;
            queue.clear();
        }
    }

    public RuntimeEvents(Clock clock) {
        this.clock = clock;
    }

    public String epoch() {
        return epoch;
    }

    /** 事件的 SSE id：{@code <纪元>:<序号>}。 */
    public String id(long seq) {
        return epoch + ":" + seq;
    }

    // ---- 订阅 ----

    /** 全部事件。 */
    public Predicate<Event> all() {
        return event -> true;
    }

    /** 这个 Job 的事件。 */
    public Predicate<Event> job(String jobId) {
        return event -> jobId.equals(event.job());
    }

    /**
     * 这个 Session 的事件，加上它涉及的 Job 的事件：{@code jobs}（订阅时从文件里查到的：session.md 的 jobs、
     * 在这里发现的、申请线程属于这里的），以及之后和这个 Session 一起出现过的 Job。
     */
    public Predicate<Event> session(String sessionId, Set<String> jobs) {
        Set<String> known = Set.copyOf(jobs);
        return event -> sessionId.equals(event.session()) || event.job() != null
                && (known.contains(event.job()) || links.getOrDefault(event.job(), Set.of()).contains(sessionId));
    }

    /**
     * 订阅。{@code lastEventId} 是客户端最后收到的 id（没有时为 null）：同一纪元、缓冲里还有时补发之后的事件；
     * 纪元不对、太旧或比现在还新时要求 {@code reset}。补发的事件在返回值里，交给 {@code sink} 的只有之后的新事件；
     * 调用方先发完返回值里的，再处理 {@code sink}（同一把锁下注册，不会漏、不会乱序）。
     * {@code sink} 在单独的投递线程里按顺序调用（发布的一方不等它），抛异常时取消这次订阅。
     * 积压了 {@link #BUFFER} 条还没发出去时也取消，并在投递线程里调用 {@code dropped}（调用方关掉连接，客户端带
     * {@code Last-Event-ID} 重连，走补发或 {@code reset}）。
     */
    public synchronized Subscription subscribe(String lastEventId, Predicate<Event> filter, Consumer<Event> sink,
                                               Runnable dropped) {
        boolean reset = false;
        List<Event> backlog = new ArrayList<>();
        if (lastEventId != null && !lastEventId.isBlank()) {
            long after = after(lastEventId.strip());
            long oldest = buffer.isEmpty() ? seq + 1 : buffer.peekFirst().seq();
            if (after < 0 || after > seq || after < oldest - 1) {
                reset = true;
            } else {
                for (Event event : buffer) {
                    if (event.seq() > after && filter.test(event)) {
                        backlog.add(event);
                    }
                }
            }
        }
        Subscriber subscriber = new Subscriber(filter, sink, dropped);
        subscribers.add(subscriber);
        return new Subscription(reset, backlog, id(seq), () -> unsubscribe(subscriber));
    }

    /** {@code <纪元>:<序号>} 里的序号；纪元不对或写错时为 -1。 */
    private long after(String lastEventId) {
        int colon = lastEventId.lastIndexOf(':');
        if (colon < 0 || !epoch.equals(lastEventId.substring(0, colon))) {
            return -1;
        }
        try {
            return Long.parseLong(lastEventId.substring(colon + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void unsubscribe(Subscriber subscriber) {
        subscriber.cancel();
        synchronized (this) {
            subscribers.remove(subscriber);
        }
    }

    // ---- 发布 ----

    /** 台账或视图变了（JobLedger 每次写、Session 改名、附件、申请线程的回报……）。 */
    public void changed(String session, String job) {
        publish(CHANGED, session, job, null);
    }

    /** 浏览器连接状态变了（{@code BrowserSession} 的监听者）。只进全局流（{@link #all()}）。 */
    public void browser(BrowserState state) {
        publish(BROWSER, null, null, state);
    }

    private synchronized void publish(String name, String session, String job, Object payload) {
        Event event = new Event(++seq, name, session, job, payload);
        if (session != null && job != null) {
            links.computeIfAbsent(job, key -> new HashSet<>()).add(session);
        }
        buffer.addLast(event);
        while (buffer.size() > BUFFER) {
            buffer.removeFirst();
        }
        // 只入队：慢的连接不会挡住发布的一方（台账写入、两个循环）；积压满了的丢掉，关连接交给投递线程
        for (Iterator<Subscriber> it = subscribers.iterator(); it.hasNext(); ) {
            Subscriber subscriber = it.next();
            if (subscriber.filter.test(event) && !subscriber.offer(event)) {
                it.remove();
                log.debug("一条推送积压了 {} 条，断开让它重连", BUFFER);
                delivery.execute(() -> {
                    try {
                        subscriber.dropped.run();
                    } catch (RuntimeException e) {
                        log.debug("断开积压的推送出错：{}", e.getMessage());
                    }
                });
            }
        }
    }

    // ---- 循环现在在做什么 ----

    public synchronized Activity sessionActivity(String sessionId) {
        return activities.get(key(sessionId, null));
    }

    public synchronized Activity jobActivity(String jobId) {
        return activities.get(key(null, jobId));
    }

    public synchronized InFlight sessionInFlight(String sessionId) {
        return copy(inFlight.get(key(sessionId, null)));
    }

    public synchronized InFlight jobInFlight(String jobId) {
        return copy(inFlight.get(key(null, jobId)));
    }

    /** 工作台后续段（C022）现在在做什么：和同一个 Job 的申请线程分开记。 */
    public synchronized Activity followupActivity(String jobId) {
        return activities.get(followupKey(jobId));
    }

    public synchronized InFlight followupInFlight(String jobId) {
        return copy(inFlight.get(followupKey(jobId)));
    }

    private static InFlight copy(InFlight current) {
        return current == null ? null : new InFlight(current.assistant(), Map.copyOf(current.results()));
    }

    /** 对话 Agent 的循环（{@code job} 为 null）或申请线程（{@code job} 是它的 Job，{@code session} 是它所在的 Session）。 */
    LoopObserver observer(String session, String job) {
        return observer(session, job, key(session, job));
    }

    /**
     * 工作台后续段的循环（C022）：推送照样带 {@code job}（工作台订阅的 {@code ?job=} 收得到），活动和还没写进对话的结果
     * 按自己的键记，不和申请线程混。
     */
    LoopObserver followupObserver(String job) {
        return observer(null, job, followupKey(job));
    }

    private LoopObserver observer(String session, String job, String key) {
        return new LoopObserver() {
            @Override
            public void thinking() {
                synchronized (RuntimeEvents.this) {
                    // 上一轮的工具结果已经写进对话
                    inFlight.remove(key);
                }
                setActivity(session, job, key, new Activity("thinking", null, Timestamps.now(clock)));
            }

            @Override
            public void replied(AgentRun.Turn assistant) {
                if (!assistant.calls().isEmpty()) {
                    synchronized (RuntimeEvents.this) {
                        inFlight.put(key, new InFlight(assistant, new LinkedHashMap<>()));
                    }
                }
                if (assistant.thinking() != null) {
                    publish(STEP, session, job, new Step(null, null, assistant.thinking()));
                }
                if (assistant.text() != null && !assistant.text().isBlank()) {
                    publish(MESSAGE, session, job, assistant.text().strip());
                }
            }

            @Override
            public void heard(List<AgentRun.Turn> said) {
                // 用户插的话已经进了对话：界面重读，「已插入」变成对话里的一条（C010）
                changed(session, job);
            }

            @Override
            public void tool(AgentRun.ToolCall call) {
                setActivity(session, job, key, new Activity("tool", call, Timestamps.now(clock)));
            }

            @Override
            public void done(AgentRun.ToolCall call, AgentRun.ToolResult result) {
                synchronized (RuntimeEvents.this) {
                    InFlight current = inFlight.get(key);
                    if (current != null) {
                        current.results().put(call.id(), result);
                    }
                }
                publish(STEP, session, job, new Step(call, result, null));
            }
        };
    }

    /** 开始跑了。 */
    void started(String session, String job, String status) {
        publish(STATUS, session, job, status);
    }

    /** 跑完了：清掉活动和还没写进对话的结果，发最后的状态。 */
    void finished(String session, String job, String status) {
        finished(session, job, key(session, job), status);
    }

    /** 后续段跑完了（C022）。 */
    void followupFinished(String job, String status) {
        finished(null, job, followupKey(job), status);
    }

    private void finished(String session, String job, String key, String status) {
        synchronized (this) {
            activities.remove(key);
            inFlight.remove(key);
        }
        publish(ACTIVITY, session, job, null);
        publish(STATUS, session, job, status);
    }

    private void setActivity(String session, String job, String key, Activity activity) {
        synchronized (this) {
            activities.put(key, activity);
        }
        publish(ACTIVITY, session, job, activity);
    }

    private static String key(String session, String job) {
        return job != null ? "job:" + job : "session:" + session;
    }

    private static String followupKey(String job) {
        return "followup:" + job;
    }
}
