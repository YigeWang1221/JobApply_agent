package jobapply.workflow;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
import jobapply.harness.runtime.SessionLoop;
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
 * Session 对话的入口（C005）：新建 Session（Agent 先给开场）、发消息、停止、查状态。每条消息在后台跑一次
 * {@link SessionLoop}，对话一条一条写进 {@code transcript.jsonl}；状态（在跑 / 空闲、上一次的错误）只在内存里。
 * 同一个 Session 同一时间只跑一次；不同 Session 可以同时跑（浏览器按使用者切换标签页）。
 */
public final class SessionRunner implements AutoCloseable {

    /** 一条消息最多这么多字符。 */
    public static final int MAX_TEXT = 4000;
    static final String OPENING = """
            系统：用户刚新建了这个 Session。先用 list_jobs 看看这个 Project 的状态，然后用几句话给出开场：\
            候选池里有几个待投的岗位（同一家公司有几个就按公司说）、哪些申请在等用户回复、哪些停在提交前等用户审阅。\
            最后问用户今天想先做什么（继续调研、投哪几个、看申请进度）。还什么都没有时，请用户说说想找什么岗位。""";
    private static final DateTimeFormatter TITLE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Logger log = LoggerFactory.getLogger(SessionRunner.class);

    /** 一个 Session 现在的状态。{@code error} 是上一次运行的错误；没有时为 null。 */
    public record Status(String status, String error) {

        public static final String RUNNING = "running";
        public static final String IDLE = "idle";
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
    private final Map<String, String> errors = new HashMap<>();

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
    }

    /** 新建 Session，Agent 在后台读 Project 状态并写开场。{@code title} 为空时用「新对话 · 日期 时间」。 */
    public synchronized SessionRecord create(String projectId, String title) {
        if (!projects.exists(projectId)) {
            throw new ApplicationException.NotFound("Project 不存在：" + projectId);
        }
        String name = title == null || title.isBlank() ? "新对话 · " + LocalDateTime.now(clock).format(TITLE)
                : title.strip();
        SessionRecord session = sessions.create(projectId, name);
        sessions.appendTurn(projectId, session.id(), AgentRun.Turn.note(OPENING));
        submit(projectId, session.id());
        return session;
    }

    /** 发一条消息，在后台跑。正在跑时 409。 */
    public synchronized void send(String sessionId, String text) {
        String projectId = project(sessionId);
        String message = text == null ? "" : text.strip();
        if (message.isEmpty()) {
            throw new ApplicationException.BadRequest("消息不能为空");
        }
        if (message.length() > MAX_TEXT) {
            throw new ApplicationException.BadRequest("消息不能超过 " + MAX_TEXT + " 个字符");
        }
        if (running.contains(sessionId)) {
            throw new ApplicationException.Conflict("Agent 还在回上一条消息，等它说完再发（或者先停止）");
        }
        sessions.appendTurn(projectId, sessionId, AgentRun.Turn.user(message));
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
        return new Status(running.contains(sessionId) ? Status.RUNNING : Status.IDLE, errors.get(sessionId));
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
        running.add(sessionId);
        stopRequested.remove(sessionId);
        errors.remove(sessionId);
        pool.submit(() -> {
            String error = null;
            try {
                List<AgentRun.Turn> history = sessions.readTurns(projectId, sessionId).stream()
                        .map(SessionRepository.TimedTurn::turn).toList();
                SessionLoop loop = new SessionLoop(mainModel.get(), browser, layout, clock, search, fetch,
                        (job, project, session) -> {
                            ApplicationStatus status = applications.start(job, project, session);
                            return "已经开始申请 " + job + "（申请线程：" + status.status() + "）。之后在申请工作台里进行。";
                        });
                loop.run(projectId, sessionId, history, () -> stopRequested(sessionId),
                        turn -> sessions.appendTurn(projectId, sessionId, turn));
            } catch (RuntimeException e) {
                error = rootMessage(e);
                log.warn("Session {} 的对话 Agent 出错：{}", sessionId, error);
            }
            synchronized (this) {
                running.remove(sessionId);
                stopRequested.remove(sessionId);
                if (error != null) {
                    errors.put(sessionId, error);
                }
            }
        });
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
