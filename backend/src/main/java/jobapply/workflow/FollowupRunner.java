package jobapply.workflow;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import jobapply.domain.AgentRun;
import jobapply.domain.Inbox;
import jobapply.domain.Job;
import jobapply.domain.LiveRuns;
import jobapply.domain.Phase;
import jobapply.harness.runtime.FollowupLoop;
import jobapply.persistence.AttachmentStore;
import jobapply.persistence.FollowupRepository;
import jobapply.persistence.JobRepository;
import jobapply.persistence.SessionRepository;
import jobapply.persistence.StorageLayout;
import jobapply.tools.BrowserSession;
import jobapply.tools.WebFetch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 工作台后续段的入口（C022）：申请离开 prep（已投递、或者之后记了轮次）以后，同一个工作台里另起一段对话。发消息、停止、
 * 查状态，和 {@link SessionRunner} 同一个做法：每条消息在后台跑一次 {@link FollowupLoop}，对话一条一条写进
 * {@code jobs/<id>/followup/transcript.jsonl}；运行中插话放进收件箱（C010），跑完还剩的接着跑。在跑只看内存，
 * {@code followup.md} 写着在跑、内存里没有的是应用重启时中断的。同一个 Job 同一时间只跑一次。
 */
public final class FollowupRunner implements AutoCloseable {

    /** 一条消息最多这么多字符（和 Session 一样）。 */
    public static final int MAX_TEXT = SessionRunner.MAX_TEXT;
    static final String INTERRUPTED_ERROR = "应用重启了，上一次运行被中断。回复后接着做。";
    private static final Logger log = LoggerFactory.getLogger(FollowupRunner.class);

    /** 后续段现在的状态（{@code running} / {@code idle} / {@code interrupted}）。{@code error} 是上一次运行的错误。 */
    public record Status(String status, String error) {
    }

    private final StorageLayout layout;
    private final Clock clock;
    private final BrowserSession browser;
    private final Supplier<ChatModel> mainModel;
    private final WebFetch fetch;
    private final ApplicationRunner applications;
    private final RuntimeEvents events;
    private final JobRepository jobs;
    private final SessionRepository sessions;
    private final FollowupRepository followups;
    private final ExecutorService pool = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "followup-agent");
        thread.setDaemon(true);
        return thread;
    });
    private final LiveRuns running = new LiveRuns();
    private final Set<String> stopRequested = new HashSet<>();
    private final Inbox inbox = new Inbox();

    /** 事件日志、工具调用上限和 {@code applications} 共用。 */
    public FollowupRunner(StorageLayout layout, Clock clock, BrowserSession browser, Supplier<ChatModel> mainModel,
                          WebFetch fetch, ApplicationRunner applications) {
        this.layout = layout;
        this.clock = clock;
        this.browser = browser;
        this.mainModel = mainModel;
        this.fetch = fetch;
        this.applications = applications;
        this.events = applications.events();
        this.jobs = new JobRepository(layout);
        this.sessions = new SessionRepository(layout, clock);
        this.followups = new FollowupRepository(layout, clock);
    }

    /** 这个 Job 有没有后续段：离开了 prep（已投递、之后的阶段、结束了）。 */
    public static boolean open(Job job) {
        return job.status().phase() != Phase.PREP;
    }

    /**
     * 发一条消息，在后台跑。还在 prep（没投递）时 409；正在跑时插进这次运行（C010），Agent 下一步看到。
     * 只有附件时文字可以为空。
     */
    public synchronized void send(String jobId, String text, List<String> attachmentIds) {
        Job job = job(jobId);
        if (!open(job)) {
            throw new ApplicationException.Conflict("Job " + jobId + " 还没有投递（" + job.status().label()
                    + "），投递之后才有后续段；现在在上面的投递段回复申请 Agent");
        }
        String session = sessionOf(job);
        String message = text == null ? "" : text.strip();
        AttachmentStore store = attachments(jobId);
        List<AgentRun.Attachment> attached = (attachmentIds == null ? List.<String>of() : attachmentIds).stream()
                .distinct().map(id -> store.require(id).attachment(null)).toList();
        if (message.isEmpty() && attached.isEmpty()) {
            throw new ApplicationException.BadRequest("消息不能为空");
        }
        if (message.length() > MAX_TEXT) {
            throw new ApplicationException.BadRequest("消息不能超过 " + MAX_TEXT + " 个字符");
        }
        AgentRun.Turn turn = AgentRun.Turn.user(message, attached);
        if (running.contains(jobId)) {
            inbox.put(jobId, turn);
            events.changed(null, jobId);
            return;
        }
        followups.appendTurn(job.project(), jobId, turn);
        submit(job, session);
    }

    /** 请正在跑的后续段停下（在当前这一次工具调用之后）。没在跑时什么都不做。 */
    public synchronized void stop(String jobId) {
        job(jobId);
        if (running.contains(jobId)) {
            stopRequested.add(jobId);
        }
    }

    public synchronized Status status(String jobId) {
        Job job = job(jobId);
        if (running.contains(jobId)) {
            return new Status(SessionRunner.Status.RUNNING, null);
        }
        FollowupRepository.Status last = followups.status(job.project(), jobId);
        if (running.interrupted(jobId, SessionRunner.Status.RUNNING.equals(last.status()))) {
            return new Status(SessionRunner.Status.INTERRUPTED, INTERRUPTED_ERROR);
        }
        return new Status(SessionRunner.Status.IDLE, last.error());
    }

    /** 后续段的对话；还没有时为空。 */
    public List<SessionRepository.TimedTurn> turns(String jobId) {
        Job job = job(jobId);
        return followups.readTurns(job.project(), jobId);
    }

    /** 运行中插了、Agent 还没看到的消息（C010）。 */
    public List<AgentRun.Turn> inserted(String jobId) {
        return inbox.peek(jobId);
    }

    /** 后续段的附件（{@code followup/attachments/}）。 */
    public AttachmentStore attachments(String jobId) {
        Job job = job(jobId);
        return new AttachmentStore(layout.followupAttachmentsDir(job.project(), jobId));
    }

    public RuntimeEvents events() {
        return events;
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }

    private void submit(Job job, String session) {
        String projectId = job.project();
        String jobId = job.id();
        followups.writeStatus(projectId, jobId, SessionRunner.Status.RUNNING, null);
        running.add(jobId);
        stopRequested.remove(jobId);
        events.started(null, jobId, SessionRunner.Status.RUNNING);
        pool.submit(() -> {
            String error = null;
            try {
                List<AgentRun.Turn> history = followups.readTurns(projectId, jobId).stream()
                        .map(SessionRepository.TimedTurn::turn).toList();
                FollowupLoop loop = new FollowupLoop(mainModel.get(), browser, layout, clock, fetch, events::changed,
                        applications.toolCallLimits()::perMessage, applications.experience());
                loop.run(projectId, jobId, session, history, () -> stopRequested(jobId),
                        turn -> followups.appendTurn(projectId, jobId, turn), events.followupObserver(jobId),
                        () -> inbox.take(jobId));
            } catch (RuntimeException e) {
                error = rootMessage(e);
                log.warn("Job {} 的后续段出错：{}", jobId, error);
            }
            // 这次做完：关掉它开的页和 MCP 会话，下一次要用时再开
            browser.leave(FollowupLoop.tabOwner(jobId), BrowserSession.Leave.CLOSE_ALL);
            boolean again = false;
            synchronized (this) {
                try {
                    followups.writeStatus(projectId, jobId, SessionRunner.Status.IDLE, error);
                } catch (RuntimeException e) {
                    log.warn("Job {} 后续段的状态没有写进 followup.md：{}", jobId, rootMessage(e));
                }
                running.remove(jobId);
                stopRequested.remove(jobId);
                // 跑完时还没被看到的插话：写进对话，接着跑一次，不丢（C010）
                List<AgentRun.Turn> said = inbox.take(jobId);
                if (!said.isEmpty()) {
                    try {
                        said.forEach(turn -> followups.appendTurn(projectId, jobId, turn));
                        submit(job, session);
                        again = true;
                    } catch (RuntimeException e) {
                        log.warn("Job {} 后续段没有接着处理运行中插的话：{}", jobId, rootMessage(e));
                    }
                }
            }
            if (!again) {
                events.followupFinished(jobId, SessionRunner.Status.IDLE);
            }
            events.changed(null, jobId);
        });
    }

    /** 台账事件记在哪个 Session 下：申请线程的，没有时用发现它的（删过的也行，C013）。 */
    private String sessionOf(Job job) {
        String session = applications.run(job.id()).map(AgentRun::session).orElse(job.discoveredIn());
        if (session == null || !sessions.existsOrDeleted(job.project(), session)) {
            throw new ApplicationException.Conflict("Job " + job.id() + " 没有记录所在的 Session");
        }
        return session;
    }

    /** 这个 Job（Job id 全局唯一）；没有时 404。 */
    public Job job(String jobId) {
        return find(jobId).orElseThrow(() -> new ApplicationException.NotFound("Job 不存在：" + jobId));
    }

    private Optional<Job> find(String jobId) {
        for (String project : layout.projectIds()) {
            if (jobs.exists(project, jobId)) {
                return Optional.of(jobs.load(project, jobId));
            }
        }
        return Optional.empty();
    }

    private synchronized boolean stopRequested(String jobId) {
        return stopRequested.contains(jobId);
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }
}
