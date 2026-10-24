package jobapply.workflow;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import jobapply.domain.AgentRun;
import jobapply.domain.Inbox;
import jobapply.domain.LiveRuns;
import jobapply.domain.SessionRecord;
import jobapply.harness.runtime.SessionLoop;
import jobapply.persistence.AttachmentStore;
import jobapply.persistence.ProjectRepository;
import jobapply.persistence.SessionRepository;
import jobapply.persistence.StorageLayout;
import jobapply.persistence.Trash;
import jobapply.tools.BrowserSession;
import jobapply.tools.WebFetch;
import jobapply.tools.WebSearch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;

/**
 * Session 对话的入口（C005）：新建 Session（空白对话，C005-fix 开场）、发消息、重新生成、停止、查状态。每条消息在后台跑一次
 * {@link SessionLoop}，对话一条一条写进 {@code transcript.jsonl}。在跑的 Session 在内存里；上一次运行的状态和错误
 * 记在 {@code session.md}（C006-agent），应用重启时还在跑的那次显示为「中断」。现在在做什么在 {@link #events()} 里。
 * 同一个 Session 同一时间只跑一次；不同 Session 可以同时跑（浏览器按使用者切换标签页）。
 */
public final class SessionRunner implements AutoCloseable {

    /** 一条消息最多这么多字符。 */
    public static final int MAX_TEXT = 4000;
    private static final DateTimeFormatter TITLE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** 没写标题时的标题开头；第一条用户消息到了以后换成消息的前 {@link #TITLE_LENGTH} 个字。 */
    static final String UNTITLED = "新对话 · ";
    static final int TITLE_LENGTH = 30;
    static final String INTERRUPTED_ERROR = "应用重启了，上一次运行被中断。回复后接着做。";
    /**
     * 重新生成时追加的说明（C006-fix 第 4 节）。只说发生了什么，不替模型判断哪些生效了：结果原文在上面，
     * 有副作用的工具都可重放（重复调用返回已有的结果）。
     */
    static final String REGENERATE = "系统：用户要求重新生成上一次的回答。上面保留了上一次已经执行的调用和结果"
            + "（成功还是失败看结果原文）；有副作用的工具重复调用是安全的，会返回已有的结果。请重新回答用户最后一条消息。";
    /** 申请线程回报的开头（只给模型看的 note；界面上把它显示成一条消息，去掉这个开头）。 */
    public static final String REPORT = "系统通知（申请线程的回报，不是用户说的话）：";
    private static final Logger log = LoggerFactory.getLogger(SessionRunner.class);

    /** 一个 Session 现在的状态。{@code error} 是上一次运行的错误；没有时为 null。 */
    public record Status(String status, String error) {

        public static final String RUNNING = "running";
        public static final String IDLE = "idle";
        /** 应用重启时这个 Session 还在跑。 */
        public static final String INTERRUPTED = "interrupted";
    }

    private final StorageLayout layout;
    private final Clock clock;
    private final BrowserSession browser;
    private final Supplier<ChatModel> mainModel;
    private final WebSearch search;
    private final WebFetch fetch;
    private final ApplicationRunner applications;
    private final ProjectRepository projects;
    private final SessionRepository sessions;
    private final ExecutorService pool = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "session-agent");
        thread.setDaemon(true);
        return thread;
    });
    private final LiveRuns running = new LiveRuns();
    private final Set<String> stopRequested = new HashSet<>();
    /** 删除时还在跑的 Session（C013）：已经请它停下，这次运行结束时挪进回收站。 */
    private final Set<String> deleteRequested = new HashSet<>();
    private final Trash trash;
    private final RuntimeEvents events;
    /** 申请线程的回报：Session 在跑时先放在这里，跑完再写进对话（不插进正在进行的工具调用中间）。 */
    private final Map<String, List<AgentRun.Turn>> pendingReports = new HashMap<>();
    /** 运行中用户插的话（C010）：循环下一次调模型之前取走；跑完还剩的写进对话并接着跑。 */
    private final Inbox inbox = new Inbox();

    /** 事件日志和 {@code applications} 共用；申请线程的回报写进发起它的 Session（{@link #report}）。 */
    public SessionRunner(StorageLayout layout, Clock clock, BrowserSession browser, Supplier<ChatModel> mainModel,
                         WebSearch search, WebFetch fetch, ApplicationRunner applications) {
        this.layout = layout;
        this.clock = clock;
        this.browser = browser;
        this.mainModel = mainModel;
        this.search = search;
        this.fetch = fetch;
        this.applications = applications;
        this.projects = new ProjectRepository(layout, clock);
        this.sessions = new SessionRepository(layout, clock);
        this.trash = new Trash(layout, clock);
        this.events = applications.events();
        applications.reportTo(this::report);
    }

    /**
     * 申请线程一次运行结束的回报（C006-fix 第 2 节）：在发起它的 Session 的对话里记一条 {@link #REPORT} 开头的 note，
     * 发 {@code changed}。不唤醒模型；用户下一句话时对话 Agent 看得到。Session 正在跑时等它跑完再写。
     */
    synchronized void report(String projectId, String sessionId, String jobId, String text) {
        if (projectId == null || sessionId == null || !sessions.exists(projectId, sessionId)) {
            return;
        }
        AgentRun.Turn note = AgentRun.Turn.note(REPORT + text);
        if (running.contains(sessionId)) {
            pendingReports.computeIfAbsent(sessionId, key -> new ArrayList<>()).add(note);
            return;
        }
        sessions.appendTurn(projectId, sessionId, note);
        events.changed(sessionId, jobId);
    }

    /**
     * 新建 Session：空白对话，不调模型（C005-fix 开场）。用户发第一条消息时 Agent 才开始跑；项目状态看 Project 主页。
     * {@code title} 为空时用「新对话 · 日期 时间」。
     */
    public synchronized SessionRecord create(String projectId, String title) {
        if (!projects.exists(projectId)) {
            throw new ApplicationException.NotFound("Project 不存在：" + projectId);
        }
        String name = title == null || title.isBlank() ? UNTITLED + LocalDateTime.now(clock).format(TITLE)
                : title.strip();
        SessionRecord session = sessions.create(projectId, name);
        events.changed(session.id(), null);
        return session;
    }

    /** 发一条消息，在后台跑。正在跑时插进这次运行（C010），Agent 下一步看到。 */
    public synchronized void send(String sessionId, String text) {
        send(sessionId, text, null);
    }

    /** 发一条消息，带上用户附的文件（{@link #attachments(String)} 里的编号，C006 附件）。只有附件时文字可以为空。 */
    public synchronized void send(String sessionId, String text, List<String> attachmentIds) {
        String projectId = project(sessionId);
        requireNotDeleting(sessionId);
        String message = text == null ? "" : text.strip();
        AttachmentStore store = attachments(sessionId);
        List<AgentRun.Attachment> attached = (attachmentIds == null ? List.<String>of() : attachmentIds).stream()
                .distinct().map(id -> store.require(id).attachment(null)).toList();
        if (message.isEmpty() && attached.isEmpty()) {
            throw new ApplicationException.BadRequest("消息不能为空");
        }
        if (message.length() > MAX_TEXT) {
            throw new ApplicationException.BadRequest("消息不能超过 " + MAX_TEXT + " 个字符");
        }
        boolean first = inbox.peek(sessionId).isEmpty() && sessions.readTurns(projectId, sessionId).stream()
                .noneMatch(timed -> AgentRun.Turn.USER.equals(timed.turn().role()));
        if (first && sessions.load(projectId, sessionId).title().startsWith(UNTITLED)) {
            sessions.rename(projectId, sessionId, title(message.isEmpty() ? attached.getFirst().name() : message));
            events.changed(sessionId, null);
        }
        AgentRun.Turn turn = AgentRun.Turn.user(message, attached);
        if (running.contains(sessionId)) {
            // 运行中插话（C010）：不拒绝、不打断正在执行的工具；循环下一次调模型之前取走
            inbox.put(sessionId, turn);
            events.changed(sessionId, null);
            return;
        }
        sessions.appendTurn(projectId, sessionId, turn);
        submit(projectId, sessionId);
    }

    /** 运行中插了、Agent 还没看到的消息（C010）。 */
    public List<AgentRun.Turn> inserted(String sessionId) {
        return inbox.peek(sessionId);
    }

    /** 这个 Session 的附件（C006 附件）；没有这个 Session 时 404。 */
    public AttachmentStore attachments(String sessionId) {
        String projectId = project(sessionId);
        return new AttachmentStore(layout.sessionAttachmentsDir(projectId, sessionId));
    }

    /**
     * 重新生成最后一条回复（C006-fix 第 4 节）。不撤销：去掉上一次回答的文字（连同 {@code ask_user}），已经执行的
     * 工具调用和结果原样留在对话里，追加一句 {@link #REGENERATE}，然后重新跑。有副作用的工具都可重放，模型重复调用
     * 得到「已经是这样了」的回执；上一次失败的，模型从结果原文里看得到，可以重试。
     * 正在跑、或者最后一条用户消息之后还没有回复时 409。
     */
    public synchronized void regenerate(String sessionId) {
        String projectId = project(sessionId);
        requireNotDeleting(sessionId);
        if (running.contains(sessionId)) {
            throw new ApplicationException.Conflict("Agent 还在回复，等它说完（或者先停止）再重新生成");
        }
        List<SessionRepository.TimedTurn> turns = sessions.readTurns(projectId, sessionId);
        int start = 0;
        for (int i = 0; i < turns.size(); i++) {
            if (AgentRun.Turn.USER.equals(turns.get(i).turn().role())) {
                start = i + 1;
            }
        }
        boolean replied = turns.subList(start, turns.size()).stream()
                .anyMatch(timed -> AgentRun.Turn.ASSISTANT.equals(timed.turn().role()));
        if (!replied) {
            throw new ApplicationException.Conflict("没有可以重新生成的回复");
        }
        List<SessionRepository.TimedTurn> reply = turns.subList(start, turns.size());
        // 有结果的调用才留下（没有结果的是中断的，发给模型会出错）
        Set<String> answered = new HashSet<>();
        reply.forEach(timed -> timed.turn().results().forEach(result -> answered.add(result.id())));
        List<SessionRepository.TimedTurn> kept = new ArrayList<>(turns.subList(0, start));
        Set<String> calls = new HashSet<>();
        for (SessionRepository.TimedTurn timed : reply) {
            AgentRun.Turn turn = timed.turn();
            switch (turn.role()) {
                case AgentRun.Turn.ASSISTANT -> {
                    List<AgentRun.ToolCall> executed = turn.calls().stream()
                            .filter(call -> !"ask_user".equals(call.name()) && answered.contains(call.id())).toList();
                    executed.forEach(call -> calls.add(call.id()));
                    if (!executed.isEmpty()) {
                        kept.add(new SessionRepository.TimedTurn(timed.at(),
                                AgentRun.Turn.assistant(null, executed)));
                    }
                }
                case AgentRun.Turn.TOOL -> {
                    List<AgentRun.ToolResult> results = turn.results().stream()
                            .filter(result -> calls.contains(result.id())).toList();
                    if (!results.isEmpty()) {
                        kept.add(new SessionRepository.TimedTurn(timed.at(), AgentRun.Turn.tool(results)));
                    }
                }
                default -> kept.add(timed);
            }
        }
        sessions.rewriteTurns(projectId, sessionId, kept);
        sessions.appendTurn(projectId, sessionId, AgentRun.Turn.note(REGENERATE));
        submit(projectId, sessionId);
    }

    /** 请正在跑的 Session 停下（在当前这一次工具调用之后）。没在跑时什么都不做。 */
    public synchronized void stop(String sessionId) {
        project(sessionId);
        if (running.contains(sessionId)) {
            stopRequested.add(sessionId);
        }
    }

    /** 删除结果（C013）：{@code deleted} 已经挪进回收站；{@code deleting} 还在跑，已经请它停下，停下后挪走。 */
    public enum Deletion { DELETED, DELETING }

    /**
     * 删除 Session（C013）：整个目录挪进回收站，Job 留在 Project 里（事件里的 Session 是历史）。还在跑时不拒绝：
     * 请它停下（在当前这一次工具调用之后），停下时再挪走，用户不用先按停止、再回来删一次。
     * 这个 Session 发起的申请线程不受影响，照常属于各自的 Job；回报找不到 Session 时不写。
     */
    public synchronized Deletion delete(String sessionId) {
        String projectId = project(sessionId);
        if (running.contains(sessionId)) {
            deleteRequested.add(sessionId);
            stopRequested.add(sessionId);
            inbox.take(sessionId);
            events.changed(sessionId, null);
            return Deletion.DELETING;
        }
        trash.moveSession(projectId, sessionId);
        forget(sessionId);
        events.changed(sessionId, null);
        return Deletion.DELETED;
    }

    /**
     * 删除 Project（C013）：它的 Session 有在跑的、或者它的 Job 有申请在跑 / 排队时 409（说是哪几个）；别的 Project
     * 继承它时也 409。否则整个 Project 连同它的 Job 的运行记录、上传暂存挪进回收站，全局台账重新生成。
     * 拿着本对象和申请线程的锁做，检查和挪走之间不会有新的运行开始。
     */
    public synchronized void deleteProject(String projectId) {
        if (!projects.exists(projectId)) {
            throw new ApplicationException.NotFound("Project 不存在：" + projectId);
        }
        List<String> children = layout.projectIds().stream().filter(id -> !id.equals(projectId))
                .filter(projects::exists).filter(id -> projects.load(id).parentIds().contains(projectId)).toList();
        if (!children.isEmpty()) {
            throw new ApplicationException.Conflict("这些 Project 继承它：" + String.join("、", children) + "，先删掉它们");
        }
        List<String> busy = layout.sessionIds(projectId).stream()
                .filter(id -> running.contains(id) || deleteRequested.contains(id)).toList();
        if (!busy.isEmpty()) {
            throw new ApplicationException.Conflict("还有对话在运行：" + String.join("、", busy) + "。先停止，再删除");
        }
        List<String> sessionIds = layout.sessionIds(projectId);
        applications.whileIdle(layout.jobIds(projectId), () -> trash.moveProject(projectId));
        sessionIds.forEach(this::forget);
        events.changed(null, null);
    }

    /** 删掉的 Session 在内存里剩下的东西。调用方持有锁。 */
    private void forget(String sessionId) {
        pendingReports.remove(sessionId);
        inbox.take(sessionId);
        stopRequested.remove(sessionId);
        deleteRequested.remove(sessionId);
    }

    private void requireNotDeleting(String sessionId) {
        if (deleteRequested.contains(sessionId)) {
            throw new ApplicationException.Conflict("这个对话正在删除（等 Agent 停下就挪进回收站）");
        }
    }

    public synchronized Status status(String sessionId) {
        return status(sessions.load(project(sessionId), sessionId));
    }

    /** 在跑的在内存里；不在跑、但 {@code session.md} 记着在跑的，是应用重启前被中断的。 */
    public synchronized Status status(SessionRecord session) {
        if (running.contains(session.id())) {
            return new Status(Status.RUNNING, null);
        }
        if (running.interrupted(session.id(), Status.RUNNING.equals(session.lastStatus()))) {
            return new Status(Status.INTERRUPTED, INTERRUPTED_ERROR);
        }
        return new Status(Status.INTERRUPTED.equals(session.lastStatus()) ? Status.INTERRUPTED : Status.IDLE,
                session.lastError());
    }

    /** 事件日志：每个 Session 现在在做什么、推送（C006-fix）。 */
    public RuntimeEvents events() {
        return events;
    }

    /** 用户改对话名。运行中也可以改：只动 session.md 的标题，不碰对话。 */
    public void rename(String sessionId, String title) {
        sessions.rename(project(sessionId), sessionId, SessionRepository.checkTitle(title));
        events.changed(sessionId, null);
    }

    /** Session 所在的 Project（Session id 全局唯一）。没有这个 Session 时 404。 */
    public String project(String sessionId) {
        return findProject(sessionId)
                .orElseThrow(() -> new ApplicationException.NotFound("Session 不存在：" + sessionId));
    }

    public Optional<String> findProject(String sessionId) {
        for (String projectId : layout.projectIds()) {
            if (sessionId != null && sessions.exists(projectId, sessionId)) {
                return Optional.of(projectId);
            }
        }
        return Optional.empty();
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }

    private void submit(String projectId, String sessionId) {
        sessions.writeStatus(projectId, sessionId, Status.RUNNING, null);
        running.add(sessionId);
        stopRequested.remove(sessionId);
        events.started(sessionId, null, Status.RUNNING);
        pool.submit(() -> {
            String error = null;
            try {
                List<AgentRun.Turn> history = sessions.readTurns(projectId, sessionId).stream()
                        .map(SessionRepository.TimedTurn::turn).toList();
                SessionLoop loop = new SessionLoop(mainModel.get(), browser, layout, clock, search, fetch,
                        applicationTools(), events::changed);
                loop.run(projectId, sessionId, history, () -> stopRequested(sessionId),
                        turn -> sessions.appendTurn(projectId, sessionId, turn), events.observer(sessionId, null),
                        () -> inbox.take(sessionId));
            } catch (RuntimeException e) {
                error = rootMessage(e);
                log.warn("Session {} 的对话 Agent 出错：{}", sessionId, error);
            }
            boolean again = false;
            synchronized (this) {
                try {
                    sessions.writeStatus(projectId, sessionId, Status.IDLE, error);
                } catch (RuntimeException e) {
                    log.warn("Session {} 的状态没有写进 session.md：{}", sessionId, rootMessage(e));
                }
                running.remove(sessionId);
                stopRequested.remove(sessionId);
                if (deleteRequested.contains(sessionId)) {
                    // 删除时还在跑（C013）：现在停下了，挪进回收站，不再写回报、不再接着跑
                    try {
                        trash.moveSession(projectId, sessionId);
                    } catch (RuntimeException e) {
                        log.warn("Session {} 停下后没有挪进回收站：{}", sessionId, rootMessage(e));
                    }
                    forget(sessionId);
                    events.finished(sessionId, null, Status.IDLE);
                    events.changed(sessionId, null);
                    return;
                }
                // 跑的时候到的申请线程回报，现在接在对话后面
                for (AgentRun.Turn note : pendingReports.getOrDefault(sessionId, List.of())) {
                    try {
                        sessions.appendTurn(projectId, sessionId, note);
                    } catch (RuntimeException e) {
                        log.warn("Session {} 没有记下申请线程的回报：{}", sessionId, rootMessage(e));
                    }
                }
                pendingReports.remove(sessionId);
                // 跑完时还没被看到的插话（最后一步正好到的、或者「打断并发送」）：写进对话，接着跑一次，不丢（C010）
                again = resumeWithInserted(projectId, sessionId);
            }
            if (!again) {
                events.finished(sessionId, null, Status.IDLE);
            }
            events.changed(sessionId, null);
        });
    }

    /** 有没被看到的插话时写进对话、再开始一次运行。调用方持有锁。写不进去时记日志，不再跑。 */
    private boolean resumeWithInserted(String projectId, String sessionId) {
        List<AgentRun.Turn> said = inbox.take(sessionId);
        if (said.isEmpty()) {
            return false;
        }
        try {
            said.forEach(turn -> sessions.appendTurn(projectId, sessionId, turn));
            submit(projectId, sessionId);
            return true;
        } catch (RuntimeException e) {
            log.warn("Session {} 没有接着处理运行中插的话：{}", sessionId, rootMessage(e));
            return false;
        }
    }

    /** 对话 Agent 的 {@code start_application}，以及记下用户说的事实之前停下申请线程。 */
    private SessionLoop.Applications applicationTools() {
        return new SessionLoop.Applications() {
            @Override
            public String start(String job, String project, String session) {
                // 可重放（C006-fix 第 4 节）：已经有申请线程时不重新开始（会丢掉填了一半的申请），返回回执
                if (applications.run(job).isPresent()) {
                    return "已经在申请了：" + job + " 已经有申请线程（" + applications.status(job).status()
                            + "），没有重新开始，填了一半的也还在。之后在申请工作台里继续；要从头重来请用户在工作台里操作。";
                }
                ApplicationStatus status = applications.start(job, project, session);
                if (AgentRun.QUEUED.equals(status.status())) {
                    // 排队（C011）：同时在跑的申请满了、或者同一个网站上的申请已经够多，排上了就是开始了，不用再调用
                    return "已经排上队：" + job + "。同时在跑的申请已经满了（或者同一个网站上的申请够多了），轮到它时自动开始，"
                            + "不用再调用 start_application。"
                            + "之后在申请工作台里看进度。";
                }
                return "已经开始申请 " + job + "（申请线程：" + status.status() + "）。之后在申请工作台里进行。";
            }

            @Override
            public void stop(String job) {
                applications.statusIfAny(job).ifPresent(status -> applications.stop(job));
            }

            @Override
            public String status(String job) {
                return applications.statusIfAny(job).map(ApplicationStatus::status).orElse(null);
            }
        };
    }

    /** 第一条用户消息的前 {@link #TITLE_LENGTH} 个字（换行换成空格）。 */
    static String title(String message) {
        String flat = message.strip().replaceAll("\\s+", " ");
        return flat.codePointCount(0, flat.length()) <= TITLE_LENGTH ? flat
                : flat.substring(0, flat.offsetByCodePoints(0, TITLE_LENGTH));
    }

    private synchronized boolean stopRequested(String sessionId) {
        return stopRequested.contains(sessionId);
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
