package jobapply.workflow;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import jobapply.config.OpenCodeSession;
import jobapply.domain.AgentRun;
import jobapply.domain.Inbox;
import jobapply.domain.Job;
import jobapply.domain.JobBucket;
import jobapply.domain.LiveRuns;
import jobapply.domain.Phase;
import jobapply.harness.policies.BrowserToolPolicy;
import jobapply.harness.runtime.FollowupLoop;
import jobapply.persistence.AttachmentStore;
import jobapply.persistence.FollowupRepository;
import jobapply.persistence.JobLedger;
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

    /** 查状态时发进后续段的那条用户消息（C026）；FollowupAgent 按「查状态：」开头认出来。 */
    static final String CHECK_TEXT = "查状态：去官网的候选人中心 / 我的申请页看这份申请现在的状态，用 record_portal_check 记下"
            + "（只读，不登录）。";

    /**
     * 批量查状态的进度（C026）：排队的、在查的、查完的（按顺序），这一批一共几个，查完的里面查到 / 要手动登录 / 没找到状态页
     * 各几个，没放进来的和原因；{@code opened}：整批查完后给用户在 Chrome 里开的页（C026-fix）。一批查完后数字留着，
     * 下一次 {@link #checkMany} 时清零。
     */
    public record StatusChecks(List<String> queued, List<String> running, List<String> done, int total, int found,
                               int needsLogin, int notFound, List<Skipped> skipped, List<String> opened) {

        public record Skipped(String jobId, String reason) {
        }
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
    // ---- 查状态（C026），都在 this 上同步 ----
    private final LinkedHashSet<String> checkQueue = new LinkedHashSet<>();
    private final LinkedHashSet<String> checkRunning = new LinkedHashSet<>();
    /** 单个查状态（工作台按钮）在跑的，不算进批量的数字和上限。 */
    private final Set<String> singleChecks = new HashSet<>();
    /** 开始查之前 portal_check 的 checked_at：查完后不一样才算这次记下了结果。 */
    private final Map<String, String> checkedBefore = new HashMap<>();
    private final List<String> checkDone = new ArrayList<>();
    private final List<StatusChecks.Skipped> checkSkipped = new ArrayList<>();
    private int checkTotal;
    private int checkFound;
    private int checkNeedsLogin;
    private int checkNotFound;
    /** 这一批里要登录、没找到状态页的：Job id → 整批查完后给用户开的网址（C026-fix）。 */
    private final Map<String, String> checkToOpen = new LinkedHashMap<>();
    private final List<String> checkOpened = new ArrayList<>();

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

    /**
     * 这个 Job 有没有后续段：投递过才有（C024）。现在在投递后的阶段，或者记过投递时间 / 投递证据（投递以后才结束的）。
     * 候选、准备中、准备中就结束的（不投了、投递失败、岗位下线）没有。
     */
    public static boolean open(Job job) {
        Phase phase = job.status().phase();
        return JobBucket.AFTER_APPLIED.contains(phase) || phase == Phase.CLOSED
                && (job.extraString("applied_at") != null || job.status().evidence() != null);
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

    /**
     * 查一次这份申请在官网上的状态（C026，工作台按钮）：往后续段发一条「查状态：」消息，在后台跑。还没投递、已经结束时 409；
     * 正在跑时插进这次运行，和 {@link #send} 一样。
     */
    public synchronized void check(String jobId) {
        Job job = job(jobId);
        String problem = uncheckable(job);
        if (problem != null) {
            throw new ApplicationException.Conflict("Job " + jobId + " " + problem + "，不能查状态");
        }
        String session = sessionOf(job);
        if (checkQueue.contains(jobId)) {
            // 已经在批量查状态里排着，轮到时就查
            return;
        }
        AgentRun.Turn turn = AgentRun.Turn.user(CHECK_TEXT, List.of());
        if (running.contains(jobId)) {
            inbox.put(jobId, turn);
            events.changed(null, jobId);
            return;
        }
        followups.appendTurn(job.project(), jobId, turn);
        singleChecks.add(jobId);
        submit(job, session);
    }

    /**
     * 批量查状态（C026，看板按钮，只查看板当前筛出来的）：按顺序排队，同时在查的不超过申请线程的上限 N，同一个网站上
     * 不超过 M（{@link ApplicationRunner#concurrency()}，和申请线程分开数）。不存在、没投递、已结束、已经在排或在查、
     * 后续段正在跑的跳过并写明原因。上一批还没查完时接在后面；查完了就从零开始一批。
     */
    public synchronized StatusChecks checkMany(List<String> jobIds) {
        if (checkQueue.isEmpty() && checkRunning.isEmpty()) {
            checkDone.clear();
            checkSkipped.clear();
            checkTotal = 0;
            checkFound = 0;
            checkNeedsLogin = 0;
            checkNotFound = 0;
            checkToOpen.clear();
            checkOpened.clear();
        }
        for (String jobId : jobIds == null ? List.<String>of() : jobIds.stream().distinct().toList()) {
            String reason = queueProblem(jobId);
            if (reason != null) {
                checkSkipped.add(new StatusChecks.Skipped(jobId, reason));
                continue;
            }
            checkQueue.add(jobId);
            checkTotal++;
            events.changed(null, jobId);
        }
        dispatchChecks();
        return checks();
    }

    /** 停下批量查状态：排队的不查了，在查的请它停下（在当前这一次工具调用之后）。 */
    public synchronized StatusChecks stopChecks() {
        return stopChecks(null);
    }

    /**
     * 只停下这些 Job 的批量查状态（C006-fix，Dashboard 按 Project 停）：其中排队的不查了，在查的请它停下，别的照常。
     * {@code jobIds} 为 null 或空时停下整批。
     */
    public synchronized StatusChecks stopChecks(List<String> jobIds) {
        Set<String> only = jobIds == null || jobIds.isEmpty() ? null : new HashSet<>(jobIds);
        List<String> dropped = checkQueue.stream().filter(jobId -> only == null || only.contains(jobId)).toList();
        checkQueue.removeAll(dropped);
        checkTotal -= dropped.size();
        dropped.forEach(jobId -> events.changed(null, jobId));
        for (String jobId : checkRunning.stream().filter(jobId -> only == null || only.contains(jobId)).toList()) {
            try {
                stop(jobId);
            } catch (RuntimeException e) {
                log.warn("Job {} 的查状态没能停下：{}", jobId, rootMessage(e));
            }
        }
        return checks();
    }

    /** 批量查状态现在的进度。 */
    public synchronized StatusChecks checks() {
        return new StatusChecks(List.copyOf(checkQueue), List.copyOf(checkRunning), List.copyOf(checkDone), checkTotal,
                checkFound, checkNeedsLogin, checkNotFound, List.copyOf(checkSkipped), List.copyOf(checkOpened));
    }

    /** 这个 Job 的查状态：{@code queued} / {@code running}；没在查时为 null。 */
    public synchronized String checkingOf(String jobId) {
        if (checkQueue.contains(jobId)) {
            return "queued";
        }
        return checkRunning.contains(jobId) || singleChecks.contains(jobId) ? "running" : null;
    }

    /** 核准这份申请等着的官网状态建议（C026）：按建议改阶段 / 记一轮。没有建议时由台账报错。 */
    public void approve(String jobId) {
        Job job = job(jobId);
        ledger().approveProposal(job.project(), jobId, sessionOf(job));
    }

    /** 驳回这份申请等着的官网状态建议（C026）：阶段不变。 */
    public void reject(String jobId) {
        Job job = job(jobId);
        ledger().rejectProposal(job.project(), jobId, sessionOf(job));
    }

    private JobLedger ledger() {
        return new JobLedger(layout, clock, events::changed);
    }

    /** 不能查状态的原因；能查时为 null。 */
    private static String uncheckable(Job job) {
        if (!open(job)) {
            return "还没有投递（" + job.status().label() + "）";
        }
        if (job.status().phase() == Phase.CLOSED) {
            return "已经结束（" + job.status().label() + "）";
        }
        return null;
    }

    /** 放不进批量查状态的原因；能放时为 null。 */
    private String queueProblem(String jobId) {
        if (checkQueue.contains(jobId)) {
            return "已经在排队";
        }
        if (checkRunning.contains(jobId) || singleChecks.contains(jobId)) {
            return "正在查";
        }
        Optional<Job> found;
        try {
            found = find(jobId);
        } catch (RuntimeException e) {
            return "读不出：" + rootMessage(e);
        }
        if (found.isEmpty()) {
            return "Job 不存在";
        }
        String problem = uncheckable(found.get());
        if (problem != null) {
            return problem;
        }
        if (running.contains(jobId)) {
            return "后续段正在跑";
        }
        try {
            sessionOf(found.get());
        } catch (RuntimeException e) {
            return rootMessage(e);
        }
        return null;
    }

    /** 按顺序放排队的出去查，直到到了同时 N 个；同一个网站已经有 M 个在查的先跳过，等前面的查完。 */
    private void dispatchChecks() {
        ApplicationRunner.Concurrency limits = applications.concurrency();
        Map<String, Integer> perSite = new HashMap<>();
        Map<String, String> siteOf = new HashMap<>();
        for (String jobId : checkRunning) {
            String site = site(jobId);
            siteOf.put(jobId, site);
            perSite.merge(Objects.toString(site, ""), 1, Integer::sum);
        }
        for (String jobId : List.copyOf(checkQueue)) {
            if (checkRunning.size() >= limits.workers()) {
                break;
            }
            String site = site(jobId);
            String key = Objects.toString(site, "");
            if (site != null && perSite.getOrDefault(key, 0) >= limits.perSite()) {
                continue;
            }
            checkQueue.remove(jobId);
            try {
                Job job = job(jobId);
                String problem = uncheckable(job);
                if (problem != null) {
                    throw new ApplicationException.Conflict(problem);
                }
                String session = sessionOf(job);
                checkedBefore.put(jobId, checkedAt(job));
                checkRunning.add(jobId);
                perSite.merge(key, 1, Integer::sum);
                AgentRun.Turn turn = AgentRun.Turn.user(CHECK_TEXT, List.of());
                if (running.contains(jobId)) {
                    // 期间用户在这个工作台发了消息：插进那次运行，跑完时一起算查完
                    inbox.put(jobId, turn);
                } else {
                    followups.appendTurn(job.project(), jobId, turn);
                    submit(job, session);
                }
            } catch (RuntimeException e) {
                checkRunning.remove(jobId);
                checkedBefore.remove(jobId);
                checkDone.add(jobId);
                checkSkipped.add(new StatusChecks.Skipped(jobId, rootMessage(e)));
                log.warn("Job {} 没能开始查状态：{}", jobId, rootMessage(e));
            }
            events.changed(null, jobId);
        }
    }

    /** 一次后续段运行彻底跑完（插话也处理完了）时：是查状态的就记进这一批的数字，再放下一个出去。在 this 上调用。 */
    private void checkFinished(Job job) {
        String jobId = job.id();
        singleChecks.remove(jobId);
        if (!checkRunning.remove(jobId)) {
            return;
        }
        checkDone.add(jobId);
        String before = checkedBefore.remove(jobId);
        try {
            Job now = jobs.load(job.project(), jobId);
            String at = checkedAt(now);
            if (at != null && !at.equals(before) && now.extra().get("portal_check") instanceof Map<?, ?> check) {
                switch (String.valueOf(check.get("result"))) {
                    case "found" -> checkFound++;
                    case "needs_login" -> {
                        checkNeedsLogin++;
                        toOpen(now, check);
                    }
                    case "not_found" -> {
                        checkNotFound++;
                        toOpen(now, check);
                    }
                    default -> {
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("Job {} 查状态的结果没读出来：{}", jobId, rootMessage(e));
        }
        dispatchChecks();
        if (checkQueue.isEmpty() && checkRunning.isEmpty() && !checkToOpen.isEmpty()) {
            openForUser(List.copyOf(checkToOpen.values()));
            checkToOpen.clear();
        }
    }

    /** 记下这份申请整批查完后要给用户开的网址：Agent 停下时的那页，没有时用投递网址。 */
    private void toOpen(Job job, Map<?, ?> check) {
        Object url = check.get("url");
        String target = url == null || String.valueOf(url).isBlank() ? job.posting().url() : String.valueOf(url);
        if (target != null && !target.isBlank()) {
            checkToOpen.put(job.id(), target);
        }
    }

    /**
     * 整批查完（C026-fix）：要登录、没找到状态页的门户在用户的 Chrome 里一次开好，一个网站一页，已经开着的不再开。
     * 这些页属于用户，不记在任何运行名下。在线程池里开，不占着 this。
     */
    private void openForUser(List<String> urls) {
        pool.submit(() -> {
            List<String> safe = new ArrayList<>();
            for (String url : urls) {
                try {
                    fetch.checkUrl(url);
                    safe.add(url);
                } catch (RuntimeException e) {
                    log.warn("不给用户开这页 {}：{}", url, rootMessage(e));
                }
            }
            try {
                List<String> opened = browser.openForUser(safe);
                synchronized (this) {
                    checkOpened.addAll(opened);
                }
            } catch (RuntimeException e) {
                log.warn("查状态结束后没能给用户开页：{}", rootMessage(e));
            }
            events.changed(null, null);
        });
    }

    private static String checkedAt(Job job) {
        return job.extra().get("portal_check") instanceof Map<?, ?> check && check.get("checked_at") != null
                ? String.valueOf(check.get("checked_at")) : null;
    }

    /** 这个 Job 投递网址所在的网站（{@link BrowserToolPolicy#siteOf}）；读不出时为 null（不限这个网站）。 */
    private String site(String jobId) {
        try {
            return find(jobId).map(job -> BrowserToolPolicy.siteOf(job.posting().url())).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 请正在跑的后续段停下（在当前这一次工具调用之后）。没在跑时什么都不做。 */
    public synchronized void stop(String jobId) {
        job(jobId);
        if (running.contains(jobId)) {
            stopRequested.add(jobId);
        }
    }

    public synchronized Status status(String jobId) {
        return status(job(jobId));
    }

    /**
     * 列表里每个 Job 的后续段运行状态（C024-fix：侧栏的点、看板的 chip 用），不再按 id 找一遍 Job。
     * 没有后续段（{@link #open} 为 false）时为 null。
     */
    public synchronized String statusOf(Job job) {
        return open(job) ? status(job).status() : null;
    }

    private Status status(Job job) {
        String jobId = job.id();
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
                        applications.toolCallLimits()::perMessage, applications.experience(), applications::noteToSession);
                // 发往 OpenCode 的模型请求带这份申请后续段的 id；查状态（C026）也是发进后续段的消息，同一个（C025-fix）
                OpenCodeSession.run("jobapply-followup-" + jobId, () -> loop.run(projectId, jobId, session, history,
                        () -> stopRequested(jobId), turn -> followups.appendTurn(projectId, jobId, turn),
                        events.followupObserver(jobId), () -> inbox.take(jobId)));
            } catch (RuntimeException e) {
                error = rootMessage(e);
                log.warn("Job {} 的后续段出错：{}", jobId, error);
                SessionRunner.noteCrash(error, turn -> followups.appendTurn(projectId, jobId, turn));
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
                if (!again) {
                    try {
                        checkFinished(job);
                    } catch (RuntimeException e) {
                        log.warn("Job {} 查状态收尾出错：{}", jobId, rootMessage(e));
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
