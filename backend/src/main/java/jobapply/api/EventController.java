package jobapply.api;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import jobapply.domain.AgentRun;
import jobapply.domain.Job;
import jobapply.persistence.JobRepository;
import jobapply.persistence.SessionRepository;
import jobapply.persistence.StorageLayout;
import jobapply.tools.BrowserState;
import jobapply.workflow.ApplicationRunner;
import jobapply.workflow.RuntimeEvents;
import jobapply.workflow.SessionRunner;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 事件流（C006-fix 第 1 节，参考 OpenMAIC {@code sessions/[id]/events}、{@code owner-events}）：{@link RuntimeEvents} 的纯读者。
 * {@code GET /api/events}（全部）、{@code ?session=<id>}（这个 Session 和它涉及的 Job）、{@code ?job=<id>}。
 * <p>
 * 带 {@code Last-Event-ID} 重连时补发之后的事件；纪元不对（应用重启过）或太旧时先发一次 {@code reset}。补完（包括没有
 * {@code Last-Event-ID} 的新连接）发 {@code caught_up}，然后跟着新事件。运行结束不关流；每 25 秒一行 {@code : ping}；
 * 连接断开时取消订阅。连接卡住、积压满了时 {@link RuntimeEvents} 丢掉订阅，这里结束这条流，浏览器带 {@code Last-Event-ID}
 * 重连。
 */
@RestController
public class EventController {

    static final Duration TIMEOUT = Duration.ofHours(12);
    static final Duration HEARTBEAT = Duration.ofSeconds(25);

    private final StorageLayout layout;
    private final RuntimeEvents events;
    private final SessionRunner sessions;
    private final ApplicationRunner applications;
    private final SessionRepository sessionFiles;
    private final JobRepository jobs;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public EventController(StorageLayout layout, Clock clock, RuntimeEvents events, SessionRunner sessions,
                           ApplicationRunner applications) {
        this.layout = layout;
        this.events = events;
        this.sessions = sessions;
        this.applications = applications;
        this.sessionFiles = new SessionRepository(layout, clock);
        this.jobs = new JobRepository(layout);
    }

    @GetMapping(path = "/api/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@RequestParam(required = false) String session, @RequestParam(required = false) String job,
                             @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        Predicate<RuntimeEvents.Event> filter = session != null && !session.isBlank() ? events.session(session,
                jobsOf(session)) : job != null && !job.isBlank() ? events.job(job) : events.all();
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        Connection connection = new Connection(emitter);
        RuntimeEvents.Subscription subscription = events.subscribe(lastEventId, filter, connection::offer,
                emitter::complete);
        ScheduledFuture<?> ping = heartbeat.scheduleAtFixedRate(connection::ping, HEARTBEAT.toMillis(),
                HEARTBEAT.toMillis(), TimeUnit.MILLISECONDS);
        Runnable close = () -> {
            ping.cancel(false);
            subscription.cancel().run();
        };
        emitter.onCompletion(close);
        emitter.onTimeout(close);
        emitter.onError(error -> close.run());
        if (!connection.start(subscription)) {
            close.run();
        }
        return emitter;
    }

    @PreDestroy
    void shutdown() {
        heartbeat.shutdownNow();
    }

    /**
     * 这个 Session 涉及的 Job：session.md 里的 jobs、在这里发现的、申请线程属于这里的。没有这个 Session 时 404。
     */
    private Set<String> jobsOf(String sessionId) {
        String project = sessions.project(sessionId);
        Set<String> found = new LinkedHashSet<>(sessionFiles.load(project, sessionId).jobs());
        for (Job job : jobs.list(project)) {
            if (sessionId.equals(job.discoveredIn())
                    || applications.run(job.id()).map(AgentRun::session).filter(sessionId::equals).isPresent()) {
                found.add(job.id());
            }
        }
        return found;
    }

    /** 事件的 JSON：都带 {@code session}、{@code job}（没有时为 null）。 */
    private Map<String, Object> data(RuntimeEvents.Event event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("session", event.session());
        data.put("job", event.job());
        switch (event.name()) {
            case RuntimeEvents.ACTIVITY -> data.put("activity",
                    Views.activity((RuntimeEvents.Activity) event.payload()));
            case RuntimeEvents.STEP -> {
                RuntimeEvents.Step step = (RuntimeEvents.Step) event.payload();
                data.put("step", step.call() == null ? Views.thinking(step.thinking())
                        : Views.step(step.call(), step.result()));
            }
            case RuntimeEvents.MESSAGE -> data.put("text", event.payload());
            case RuntimeEvents.STATUS -> data.put("status", event.payload());
            case RuntimeEvents.BROWSER -> data.put("browser", BrowserController.view((BrowserState) event.payload()));
            default -> {
            }
        }
        return data;
    }

    /** 一条连接：补发完之前来的新事件先排队，补完再按顺序发。 */
    private final class Connection {

        private final SseEmitter emitter;
        private final List<RuntimeEvents.Event> pending = new ArrayList<>();
        private boolean live;

        Connection(SseEmitter emitter) {
            this.emitter = emitter;
        }

        /** 发 reset 或补发、caught_up、排队的事件。发不出去时返回 false（调用方取消订阅，不在这把锁里做）。 */
        synchronized boolean start(RuntimeEvents.Subscription subscription) {
            try {
                if (subscription.reset()) {
                    emitter.send(SseEmitter.event().id(subscription.position()).name("reset")
                            .data(Map.of(), MediaType.APPLICATION_JSON));
                }
                for (RuntimeEvents.Event event : subscription.backlog()) {
                    send(event);
                }
                emitter.send(SseEmitter.event().id(subscription.position()).name("caught_up")
                        .data(Map.of("replayed", subscription.backlog().size()), MediaType.APPLICATION_JSON));
                for (RuntimeEvents.Event event : pending) {
                    send(event);
                }
                pending.clear();
                live = true;
                return true;
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e);
                return false;
            }
        }

        /**
         * 新事件（在 {@link RuntimeEvents} 这条订阅的投递线程里，不在发布方的锁里；这把锁只挡同一条连接）。
         * 发不出去时抛出，{@link RuntimeEvents} 就不再通知这一条。
         */
        synchronized void offer(RuntimeEvents.Event event) {
            if (!live) {
                pending.add(event);
                return;
            }
            try {
                send(event);
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e);
                throw new UncheckedIOException(new IOException("推送断开了", e));
            }
        }

        void ping() {
            try {
                emitter.send(SseEmitter.event().comment(" ping"));
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e);
            }
        }

        private void send(RuntimeEvents.Event event) throws IOException {
            emitter.send(SseEmitter.event().id(events.id(event.seq())).name(event.name())
                    .data(data(event), MediaType.APPLICATION_JSON));
        }
    }
}
