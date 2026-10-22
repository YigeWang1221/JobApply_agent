package jobapply.workflow.graphs;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import jobapply.domain.Actor;
import jobapply.domain.Job;
import jobapply.domain.Phase;
import jobapply.domain.PrepState;
import jobapply.domain.Transition;
import jobapply.persistence.JobLedger;
import jobapply.persistence.JobRepository;
import jobapply.persistence.ProjectRepository;
import jobapply.persistence.SessionRepository;
import jobapply.persistence.SiteRegistry;
import jobapply.persistence.StorageLayout;
import jobapply.tools.BrowserSession;
import jobapply.workflow.checkpoints.CheckpointStore;
import jobapply.workflow.nodes.NodeIds;
import jobapply.workflow.nodes.PrepStateSync;
import jobapply.workflow.state.LoginHandoffSignal;
import jobapply.workflow.state.LoginHandoffState;
import jobapply.workflow.state.LoginHandoffStatus;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.state.StateSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 登录接力的入口：开始、发信号、查状态。图在一个单线程执行器上运行，它就是本段的「浏览器串行队列」，
 * 所以调用方不用等用户在 Chrome 里同意连接就能拿到返回。
 * 同一时间只允许一条线程在执行或等待；对同一个 Job 重新开始会丢弃它上一次的运行。
 * 所有校验都在第一次写文件之前完成，被拒绝的请求不写任何东西、不改 Checkpoint。
 */
public final class LoginHandoffRunner implements AutoCloseable {

    public static final int MAX_ACCOUNT_HINT = 128;

    private static final Set<PrepState> STARTABLE = Set.of(
            PrepState.SELECTED, PrepState.PREPARING, PrepState.NEEDS_LOGIN, PrepState.PAUSED);
    private static final Map<String, String> WAITING_FOR = Map.of(
            NodeIds.WAIT_LOGIN, "login",
            NodeIds.WAIT_IDENTITY, "identity",
            NodeIds.WAIT_RECONNECT, "reconnect");
    private static final int RECURSION_LIMIT = 50;

    private static final Logger log = LoggerFactory.getLogger(LoginHandoffRunner.class);

    private final StorageLayout layout;
    private final Clock clock;
    private final BrowserSession browser;
    private final JobRepository jobs;
    private final ProjectRepository projects;
    private final SessionRepository sessions;
    private final JobLedger ledger;
    private final ExecutorService queue = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "browser-queue");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, String> failures = new HashMap<>();

    private CompiledGraph<LoginHandoffState> graph;
    private String runningJob;
    private String activeJob;
    private Future<?> current;

    public LoginHandoffRunner(StorageLayout layout, Clock clock, BrowserSession browser) {
        this.layout = layout;
        this.clock = clock;
        this.browser = browser;
        this.jobs = new JobRepository(layout);
        this.projects = new ProjectRepository(layout, clock);
        this.sessions = new SessionRepository(layout, clock);
        this.ledger = new JobLedger(layout, clock);
    }

    /**
     * 对一个已经转为申请的 Job 开始登录接力。
     *
     * @param accountHint 可选；UID、用户名、邮箱、手机号都可以，不超过 128 个字符，不含换行或竖线
     */
    public synchronized LoginHandoffStatus start(String jobId, String projectId, String sessionId, String accountHint) {
        String hint = normalizeHint(accountHint);
        requireSession(projectId, sessionId);
        if (!jobs.exists(projectId, jobId)) {
            throw new LoginHandoffException.NotFound("Job 不存在：" + projectId + "/" + jobId);
        }
        Job job = jobs.load(projectId, jobId);
        PrepState prepState = job.status().prepState();
        if (job.status().phase() != Phase.PREP || !STARTABLE.contains(prepState)) {
            throw new LoginHandoffException.Conflict("Job 现在是 " + job.status().label()
                    + "，只有 prep 阶段的 selected / preparing / needs_login / paused 可以开始登录接力");
        }
        String url = job.posting().url();
        if (url == null || url.isBlank()) {
            throw new LoginHandoffException.Conflict("Job " + jobId + " 没有岗位链接");
        }
        if (runningJob != null) {
            throw new LoginHandoffException.Conflict("浏览器正在执行 Job " + runningJob + " 的登录接力，请稍后再试");
        }
        if (activeJob != null && !activeJob.equals(jobId) && waitingFor(activeJob).isPresent()) {
            throw new LoginHandoffException.Conflict("浏览器正被 Job " + activeJob + " 的登录接力使用（在等待 "
                    + waitingFor(activeJob).get() + "），请先完成它");
        }

        if (prepState == PrepState.SELECTED || prepState == PrepState.PAUSED) {
            ledger.transition(projectId, jobId, sessionId, Actor.USER,
                    new Transition.ChangePrepState(PrepState.PREPARING, "开始登录接力"));
        }
        failures.remove(jobId);
        activeJob = jobId;
        Map<String, Object> input = LoginHandoffState.initial(projectId, sessionId, jobId, url, hint);
        submit(jobId, () -> graph().invoke(input, config(jobId)));
        return status(jobId);
    }

    /**
     * 回应当前的等待点。信号和等待的内容不符时拒绝，Checkpoint 不变。
     *
     * @param override 只能和 {@code logged_in} 一起用：识别规则认为仍在登录页时也继续
     */
    public synchronized LoginHandoffStatus signal(String jobId, String sessionId, String signalValue, boolean override) {
        LoginHandoffSignal signal = LoginHandoffSignal.of(signalValue);
        if (override && signal != LoginHandoffSignal.LOGGED_IN) {
            throw new IllegalArgumentException("override 只能和 logged_in 一起用");
        }
        if (runningJob != null) {
            throw new LoginHandoffException.Conflict("浏览器正在执行 Job " + runningJob + "，请稍后再发信号");
        }
        StateSnapshot<LoginHandoffState> snapshot = lastState(jobId)
                .orElseThrow(() -> new LoginHandoffException.NotFound("Job " + jobId + " 没有登录接力线程"));
        String waitingFor = failures.containsKey(jobId) ? null : WAITING_FOR.get(snapshot.nextNodeId());
        if (waitingFor == null) {
            throw new LoginHandoffException.Conflict("Job " + jobId + " 的登录接力没有在等待信号（"
                    + status(jobId).status() + "）");
        }
        if (!signal.waitingFor().equals(waitingFor)) {
            throw new LoginHandoffException.Conflict("现在等待的是 " + waitingFor + "，不接受 " + signal.value());
        }
        if (activeJob != null && !activeJob.equals(jobId) && waitingFor(activeJob).isPresent()) {
            throw new LoginHandoffException.Conflict("浏览器正被 Job " + activeJob + " 的登录接力使用（在等待 "
                    + waitingFor(activeJob).get() + "），请先完成它");
        }
        String projectId = snapshot.state().text(LoginHandoffState.PROJECT);
        requireSession(projectId, sessionId);
        try {
            PrepStateSync.requireHandoffState(jobs.load(projectId, jobId));
        } catch (IllegalStateException e) {
            throw new LoginHandoffException.Conflict(e.getMessage());
        }

        try {
            graph().updateState(config(jobId), Map.of(
                    LoginHandoffState.SIGNAL, signal.value(),
                    LoginHandoffState.OVERRIDE, override,
                    LoginHandoffState.SESSION, sessionId));
        } catch (Exception e) {
            throw new IllegalStateException("写入信号失败：" + e.getMessage(), e);
        }
        activeJob = jobId;
        submit(jobId, () -> graph().invoke(GraphInput.resume(), config(jobId)));
        return status(jobId);
    }

    public synchronized LoginHandoffStatus status(String jobId) {
        Optional<StateSnapshot<LoginHandoffState>> snapshot = lastState(jobId);
        if (jobId.equals(runningJob)) {
            return snapshot.map(s -> describe(s.state(), LoginHandoffStatus.RUNNING, null, ""))
                    .orElseGet(() -> new LoginHandoffStatus(jobId, LoginHandoffStatus.RUNNING, null, "正在启动",
                            null, null, null, null, null));
        }
        if (snapshot.isEmpty()) {
            if (failures.containsKey(jobId)) {
                return new LoginHandoffStatus(jobId, LoginHandoffStatus.FAILED, null, "登录接力出错",
                        null, null, null, null, failures.get(jobId));
            }
            throw new LoginHandoffException.NotFound("Job " + jobId + " 没有登录接力线程");
        }
        LoginHandoffState state = snapshot.get().state();
        if (failures.containsKey(jobId)) {
            return describe(state, LoginHandoffStatus.FAILED, null, failures.get(jobId));
        }
        String next = snapshot.get().nextNodeId();
        if (WAITING_FOR.containsKey(next)) {
            return describe(state, LoginHandoffStatus.WAITING, WAITING_FOR.get(next), state.text(LoginHandoffState.LAST_ERROR));
        }
        if (NodeIds.DONE.equals(next)) {
            return describe(state, LoginHandoffStatus.READY, null, "");
        }
        return describe(state, LoginHandoffStatus.FAILED, null,
                "上次运行没有停在等待点（应用重启或出错），请重新开始");
    }

    /** 等当前这次执行结束（测试和手动冒烟用）。超时返回 false。 */
    public boolean awaitIdle(Duration timeout) {
        Future<?> running;
        synchronized (this) {
            running = current;
        }
        if (running == null) {
            return true;
        }
        try {
            running.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            return true;
        }
    }

    @Override
    public void close() {
        queue.shutdownNow();
    }

    static String normalizeHint(String accountHint) {
        if (accountHint == null) {
            return "";
        }
        String hint = accountHint.trim();
        if (hint.length() > MAX_ACCOUNT_HINT) {
            throw new IllegalArgumentException("账号标识不能超过 " + MAX_ACCOUNT_HINT + " 个字符");
        }
        if (hint.indexOf('|') >= 0 || hint.indexOf('\n') >= 0 || hint.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("账号标识不能含换行或竖线");
        }
        return hint;
    }

    private void submit(String jobId, Runnable task) {
        runningJob = jobId;
        current = queue.submit(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                String message = rootMessage(e);
                log.warn("Job {} 的登录接力出错：{}", jobId, message);
                synchronized (this) {
                    failures.put(jobId, message);
                }
            } finally {
                synchronized (this) {
                    runningJob = null;
                    if (failures.containsKey(jobId) || waitingFor(jobId).isEmpty()) {
                        activeJob = null;
                    }
                }
            }
        });
    }

    private Optional<String> waitingFor(String jobId) {
        return lastState(jobId).map(snapshot -> WAITING_FOR.get(snapshot.nextNodeId()));
    }

    private LoginHandoffStatus describe(LoginHandoffState state, String status, String waitingFor, String lastError) {
        return new LoginHandoffStatus(
                state.text(LoginHandoffState.JOB),
                status,
                waitingFor,
                emptyToNull(state.text(LoginHandoffState.REASON)),
                emptyToNull(state.text(LoginHandoffState.DETECTED)),
                emptyToNull(state.text(LoginHandoffState.PAGE_URL)),
                emptyToNull(state.text(LoginHandoffState.PAGE_TITLE)),
                emptyToNull(state.text(LoginHandoffState.IDENTITY)),
                emptyToNull(lastError));
    }

    private void requireSession(String projectId, String sessionId) {
        if (!projects.exists(projectId)) {
            throw new LoginHandoffException.NotFound("Project 不存在：" + projectId);
        }
        if (sessionId == null || !sessions.exists(projectId, sessionId)) {
            throw new LoginHandoffException.NotFound("Session 不存在：" + projectId + "/" + sessionId);
        }
    }

    private Optional<StateSnapshot<LoginHandoffState>> lastState(String jobId) {
        return graph().lastStateOf(config(jobId));
    }

    private synchronized CompiledGraph<LoginHandoffState> graph() {
        if (graph == null) {
            graph = LoginHandoffGraph.compile(browser, new PrepStateSync(layout, ledger),
                    new SiteRegistry(layout, clock),
                    CheckpointStore.open(layout.checkpointsDb(), LoginHandoffGraph.SERIALIZER));
        }
        return graph;
    }

    private static RunnableConfig config(String jobId) {
        return RunnableConfig.builder().threadId(jobId).recursionLimit(RECURSION_LIMIT).build();
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
