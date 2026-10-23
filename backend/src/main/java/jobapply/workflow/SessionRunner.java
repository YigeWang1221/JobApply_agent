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
import jobapply.domain.SessionRecord;
import jobapply.harness.policies.ToolNotAllowedException;
import jobapply.harness.runtime.SessionLoop;
import jobapply.persistence.AttachmentStore;
import jobapply.persistence.ProjectRepository;
import jobapply.persistence.SessionRepository;
import jobapply.persistence.StorageLayout;
import jobapply.tools.BrowserSession;
import jobapply.tools.WebFetch;
import jobapply.tools.WebSearch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;

/**
 * Session 对话的入口（C005）：新建 Session（Agent 先给开场）、发消息、重新生成、停止、查状态。每条消息在后台跑一次
 * {@link SessionLoop}，对话一条一条写进 {@code transcript.jsonl}。在跑的 Session 在内存里；上一次运行的状态和错误
 * 记在 {@code session.md}（C006-agent），应用重启时还在跑的那次显示为「中断」。现在在做什么在 {@link #feed()} 里。
 * 同一个 Session 同一时间只跑一次；不同 Session 可以同时跑（浏览器按使用者切换标签页）。
 */
public final class SessionRunner implements AutoCloseable {

    /** 一条消息最多这么多字符。 */
    public static final int MAX_TEXT = 4000;
    static final String OPENING = """
            系统：用户刚新建了这个 Session。先用 list_jobs 看看这个 Project 的状态，然后用几句话给出开场：\
            候选池里有几个待投的岗位（同一家公司有几个就按公司说）、哪些申请在等用户回复、哪些停在提交前等用户审阅\
            （问一句是不是已经在 Chrome 里提交了）。标了「该查看了」的已投递申请单独列出来（「这几个申请有几天没看了：…，\
            去邮箱或官网看看有没有新消息，看完告诉我」）。最后问用户今天想先做什么（继续调研、投哪几个、看申请进度）。还什么都没有时，请用户说说想找什么岗位。""";
    private static final DateTimeFormatter TITLE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** 没写标题时的标题开头；第一条用户消息到了以后换成消息的前 {@link #TITLE_LENGTH} 个字。 */
    static final String UNTITLED = "新对话 · ";
    static final int TITLE_LENGTH = 30;
    static final String INTERRUPTED_ERROR = "应用重启了，上一次运行被中断。回复后接着做。";
    /** 有副作用的工具：重新生成时不撤销、不重做，告诉模型已经做过了（C006-agent 第 5 节）。 */
    private static final Set<String> SIDE_EFFECTS = Set.of("add_lead", "update_lead", "start_application",
            "mark_submitted", "record_status");
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
    private final Set<String> running = new HashSet<>();
    private final Set<String> stopRequested = new HashSet<>();
    private final LiveFeed feed;

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
        this.feed = new LiveFeed(clock);
    }

    /** 新建 Session，Agent 在后台读 Project 状态并写开场。{@code title} 为空时用「新对话 · 日期 时间」。 */
    public synchronized SessionRecord create(String projectId, String title) {
        if (!projects.exists(projectId)) {
            throw new ApplicationException.NotFound("Project 不存在：" + projectId);
        }
        String name = title == null || title.isBlank() ? UNTITLED + LocalDateTime.now(clock).format(TITLE)
                : title.strip();
        SessionRecord session = sessions.create(projectId, name);
        sessions.appendTurn(projectId, session.id(), AgentRun.Turn.note(OPENING));
        submit(projectId, session.id());
        return session;
    }

    /** 发一条消息，在后台跑。正在跑时 409。 */
    public synchronized void send(String sessionId, String text) {
        send(sessionId, text, null);
    }

    /** 发一条消息，带上用户附的文件（{@link #attachments(String)} 里的编号，C006 附件）。只有附件时文字可以为空。 */
    public synchronized void send(String sessionId, String text, List<String> attachmentIds) {
        String projectId = project(sessionId);
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
        if (running.contains(sessionId)) {
            throw new ApplicationException.Conflict("Agent 还在回上一条消息，等它说完再发（或者先停止）");
        }
        boolean first = sessions.readTurns(projectId, sessionId).stream()
                .noneMatch(timed -> AgentRun.Turn.USER.equals(timed.turn().role()));
        sessions.appendTurn(projectId, sessionId, AgentRun.Turn.user(message, attached));
        if (first && sessions.load(projectId, sessionId).title().startsWith(UNTITLED)) {
            sessions.rename(projectId, sessionId, title(message.isEmpty() ? attached.getFirst().name() : message));
        }
        submit(projectId, sessionId);
    }

    /** 这个 Session 的附件（C006 附件）；没有这个 Session 时 404。 */
    public AttachmentStore attachments(String sessionId) {
        String projectId = project(sessionId);
        return new AttachmentStore(layout.sessionAttachmentsDir(projectId, sessionId));
    }

    /**
     * 重新生成最后一条回复（C006-agent 第 5 节）。不撤销、不重放：去掉上一次回答的文字（连同 {@code ask_user}），
     * 已经执行的工具调用和结果留在对话里；再告诉模型哪些有副作用的操作已经生效、不要重复，然后重新跑。
     * 正在跑、或者最后一条用户消息之后还没有回复时 409。
     */
    public synchronized void regenerate(String sessionId) {
        String projectId = project(sessionId);
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
        Map<String, AgentRun.ToolCall> calls = new HashMap<>();
        List<String> done = new ArrayList<>();
        for (SessionRepository.TimedTurn timed : reply) {
            AgentRun.Turn turn = timed.turn();
            switch (turn.role()) {
                case AgentRun.Turn.ASSISTANT -> {
                    List<AgentRun.ToolCall> executed = turn.calls().stream()
                            .filter(call -> !"ask_user".equals(call.name()) && answered.contains(call.id())).toList();
                    executed.forEach(call -> calls.put(call.id(), call));
                    if (!executed.isEmpty()) {
                        kept.add(new SessionRepository.TimedTurn(timed.at(),
                                AgentRun.Turn.assistant(null, executed)));
                    }
                }
                case AgentRun.Turn.TOOL -> {
                    List<AgentRun.ToolResult> results = turn.results().stream()
                            .filter(result -> calls.containsKey(result.id())).toList();
                    for (AgentRun.ToolResult result : results) {
                        AgentRun.ToolCall call = calls.get(result.id());
                        if (SIDE_EFFECTS.contains(call.name())) {
                            done.add("- " + call.name() + " " + shorten(call.arguments(), 300) + " → "
                                    + shorten(result.text(), 200));
                        }
                    }
                    if (!results.isEmpty()) {
                        kept.add(new SessionRepository.TimedTurn(timed.at(), AgentRun.Turn.tool(results)));
                    }
                }
                default -> kept.add(timed);
            }
        }
        sessions.rewriteTurns(projectId, sessionId, kept);
        sessions.appendTurn(projectId, sessionId, AgentRun.Turn.note("系统：用户要求重新生成上一次的回答。"
                + (done.isEmpty() ? "上一次回答没有做有副作用的操作。"
                : "上一次回答已经做了这些，已经生效，不要重复：\n" + String.join("\n", done))
                + "\n上面留下的搜索、读网页结果可以直接用。请重新回答用户最后一条消息。"));
        submit(projectId, sessionId);
    }

    /** 请正在跑的 Session 停下（在当前这一次工具调用之后）。没在跑时什么都不做。 */
    public synchronized void stop(String sessionId) {
        project(sessionId);
        if (running.contains(sessionId)) {
            stopRequested.add(sessionId);
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
        if (Status.RUNNING.equals(session.lastStatus())) {
            return new Status(Status.INTERRUPTED, INTERRUPTED_ERROR);
        }
        return new Status(Status.INTERRUPTED.equals(session.lastStatus()) ? Status.INTERRUPTED : Status.IDLE,
                session.lastError());
    }

    /** 每个 Session 现在在做什么、订阅推送。 */
    public LiveFeed feed() {
        return feed;
    }

    /** Session 所在的 Project（Session id 全局唯一）。没有这个 Session 时 404。 */
    /** 用户改对话名。运行中也可以改：只动 session.md 的标题，不碰对话。 */
    public void rename(String sessionId, String title) {
        sessions.rename(project(sessionId), sessionId, SessionRepository.checkTitle(title));
    }

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
        feed.started(sessionId, Status.RUNNING);
        pool.submit(() -> {
            String error = null;
            try {
                List<AgentRun.Turn> history = sessions.readTurns(projectId, sessionId).stream()
                        .map(SessionRepository.TimedTurn::turn).toList();
                SessionLoop loop = new SessionLoop(mainModel.get(), browser, layout, clock, search, fetch,
                        (job, project, session) -> {
                            // 重新开始会丢掉上一次填了一半的申请（重新生成、模型重复调用时都可能发生）
                            Optional<AgentRun> existing = applications.run(job);
                            if (existing.isPresent()) {
                                throw new ToolNotAllowedException("Job " + job + " 已经有申请线程（"
                                        + applications.status(job).status() + "），不会重新开始。之后在申请工作台里继续；"
                                        + "要重新开始请用户在工作台里操作。");
                            }
                            ApplicationStatus status = applications.start(job, project, session);
                            return "已经开始申请 " + job + "（申请线程：" + status.status() + "）。之后在申请工作台里进行。";
                        });
                loop.run(projectId, sessionId, history, () -> stopRequested(sessionId),
                        turn -> sessions.appendTurn(projectId, sessionId, turn), feed.observer(sessionId));
            } catch (RuntimeException e) {
                error = rootMessage(e);
                log.warn("Session {} 的对话 Agent 出错：{}", sessionId, error);
            }
            synchronized (this) {
                try {
                    sessions.writeStatus(projectId, sessionId, Status.IDLE, error);
                } catch (RuntimeException e) {
                    log.warn("Session {} 的状态没有写进 session.md：{}", sessionId, rootMessage(e));
                }
                running.remove(sessionId);
                stopRequested.remove(sessionId);
            }
            feed.finished(sessionId, Status.IDLE);
        });
    }

    /** 第一条用户消息的前 {@link #TITLE_LENGTH} 个字（换行换成空格）。 */
    static String title(String message) {
        String flat = message.strip().replaceAll("\\s+", " ");
        return flat.codePointCount(0, flat.length()) <= TITLE_LENGTH ? flat
                : flat.substring(0, flat.offsetByCodePoints(0, TITLE_LENGTH));
    }

    private static String shorten(String text, int max) {
        String flat = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        return flat.length() > max ? flat.substring(0, max) + "…" : flat;
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
