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
import java.util.concurrent.ConcurrentHashMap;
import jobapply.domain.Job;
import jobapply.domain.Phase;
import jobapply.harness.policies.BrowserToolPolicy;
import jobapply.persistence.JobLedger;
import jobapply.persistence.JobRepository;
import jobapply.persistence.StorageLayout;
import jobapply.tools.BrowserSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 查状态（C026，C030 Case C）：单个（工作台按钮）和批量（看板按钮）。每一份都是一个 {@code portal-check} Subagent
 * （{@link JobThreadService#portalCheck}）：只读查官网、用 {@code record_portal_check} 记建议，对话存在 {@code subruns/}，
 * 不进工作台的对话。这里只管批量的排队、并发（同时 N 个、同一个网站 M 个，数和投递段的设置一样、分开数）、这一批的数字，
 * 以及整批查完后在用户的 Chrome 里打开要手动登录 / 没找到状态页的门户（C026-fix）。
 * <p>
 * 锁：先拿本对象，再（在 {@link JobThreadService} 的方法里）拿线程服务的锁；子运行在自己的虚拟线程上跑，不拿本对象。
 */
public final class StatusCheckBatch {

    private static final Logger log = LoggerFactory.getLogger(StatusCheckBatch.class);

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
    private final JobThreadService threads;
    private final JobRepository jobs;
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
    /** 用户要求停下的查状态（子运行每一步都查）。 */
    private final Set<String> stopRequested = ConcurrentHashMap.newKeySet();

    public StatusCheckBatch(StorageLayout layout, Clock clock, BrowserSession browser, JobThreadService threads) {
        this.layout = layout;
        this.clock = clock;
        this.browser = browser;
        this.threads = threads;
        this.jobs = new JobRepository(layout);
    }

    /**
     * 查一次这份申请在官网上的状态（工作台按钮）：起一个 {@code portal-check} 子运行，在后台跑。还没投递、已经结束时 409；
     * 已经在查或在排队时什么都不做。
     */
    public synchronized void check(String jobId) {
        Job job = threads.job(jobId);
        String problem = uncheckable(job);
        if (problem != null) {
            throw new ApplicationException.Conflict("Job " + jobId + " " + problem + "，不能查状态");
        }
        threads.checkReady(job);
        if (checkQueue.contains(jobId) || checkRunning.contains(jobId) || !singleChecks.add(jobId)) {
            // 已经在批量查状态里排着（轮到时就查）或者正在查
            return;
        }
        launch(job);
        threads.events().changed(null, jobId);
    }

    /**
     * 批量查状态（看板按钮，只查看板当前筛出来的）：按顺序排队，同时在查的不超过 N，同一个网站上不超过 M
     * （{@link JobThreadService#concurrency()}，和投递段分开数）。不存在、没投递、已结束、已经在排或在查的跳过并写明原因。上一批还没查完时接在后面；查完了就从零开始一批。
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
            threads.events().changed(null, jobId);
        }
        dispatchChecks();
        return checks();
    }

    /**
     * 停下查状态（C006-fix，Dashboard 按 Project 停；工作台停单个）：其中排队的不查了，在查的（批量的、工作台单个起的）
     * 请它停下，别的照常。{@code jobIds} 为 null 或空时停下整批（单个起的不动）。
     */
    public synchronized StatusChecks stopChecks(List<String> jobIds) {
        Set<String> only = jobIds == null || jobIds.isEmpty() ? null : new HashSet<>(jobIds);
        List<String> dropped = checkQueue.stream().filter(jobId -> only == null || only.contains(jobId)).toList();
        checkQueue.removeAll(dropped);
        checkTotal -= dropped.size();
        dropped.forEach(jobId -> threads.events().changed(null, jobId));
        checkRunning.stream().filter(jobId -> only == null || only.contains(jobId)).forEach(stopRequested::add);
        if (only != null) {
            singleChecks.stream().filter(only::contains).forEach(stopRequested::add);
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

    /** 核准这份申请等着的官网状态建议：按建议改阶段 / 记一轮。没有建议时由台账报错。 */
    public void approve(String jobId) {
        Job job = threads.job(jobId);
        ledger().approveProposal(job.project(), jobId, threads.sessionOf(job));
    }

    /** 驳回这份申请等着的官网状态建议：阶段不变。 */
    public void reject(String jobId) {
        Job job = threads.job(jobId);
        ledger().rejectProposal(job.project(), jobId, threads.sessionOf(job));
    }

    private JobLedger ledger() {
        return new JobLedger(layout, clock, threads.events()::changed);
    }

    /** 不能查状态的原因；能查时为 null。 */
    private static String uncheckable(Job job) {
        if (!JobThreadService.followupOpen(job)) {
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
            return "读不出：" + JobThreadService.rootMessage(e);
        }
        if (found.isEmpty()) {
            return "Job 不存在";
        }
        String problem = uncheckable(found.get());
        if (problem != null) {
            return problem;
        }
        try {
            threads.sessionOf(found.get());
        } catch (RuntimeException e) {
            return JobThreadService.rootMessage(e);
        }
        return null;
    }

    /** 按顺序放排队的出去查，直到到了同时 N 个；同一个网站已经有 M 个在查的先跳过，等前面的查完。在 this 上调用。 */
    private void dispatchChecks() {
        JobThreadService.Concurrency limits = threads.concurrency();
        Map<String, Integer> perSite = new HashMap<>();
        for (String jobId : checkRunning) {
            perSite.merge(Objects.toString(site(jobId), ""), 1, Integer::sum);
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
                Job job = threads.job(jobId);
                String problem = uncheckable(job);
                if (problem != null) {
                    throw new ApplicationException.Conflict(problem);
                }
                checkedBefore.put(jobId, checkedAt(job));
                checkRunning.add(jobId);
                perSite.merge(key, 1, Integer::sum);
                launch(job);
            } catch (RuntimeException e) {
                checkRunning.remove(jobId);
                checkedBefore.remove(jobId);
                checkDone.add(jobId);
                checkSkipped.add(new StatusChecks.Skipped(jobId, JobThreadService.rootMessage(e)));
                log.warn("Job {} 没能开始查状态：{}", jobId, JobThreadService.rootMessage(e));
            }
            threads.events().changed(null, jobId);
        }
    }

    /** 在自己的虚拟线程上跑一次 {@code portal-check} 子运行，跑完调 {@link #checked}。 */
    private void launch(Job job) {
        String jobId = job.id();
        stopRequested.remove(jobId);
        Thread.ofVirtual().name("status-check-" + jobId).start(() -> {
            try {
                threads.portalCheck(job, () -> stopRequested.contains(jobId));
            } catch (RuntimeException e) {
                failed(job, JobThreadService.rootMessage(e));
            } finally {
                checked(jobId);
            }
        });
    }

    /**
     * 查状态在子运行开始前就出错：批量里的记进这一批没查成的（看板的进度里显示原因），单个的记进后续段状态的 error
     * （工作台显示）。
     */
    private void failed(Job job, String message) {
        log.warn("Job {} 查状态出错：{}", job.id(), message);
        synchronized (this) {
            if (checkRunning.contains(job.id())) {
                checkSkipped.add(new StatusChecks.Skipped(job.id(), "查状态出错：" + message));
                return;
            }
        }
        try {
            threads.noteCheckFailed(job, message);
        } catch (RuntimeException e) {
            log.warn("Job {} 查状态的错误没有写进后续段状态：{}", job.id(), JobThreadService.rootMessage(e));
        }
    }

    /** 一次查状态跑完：是批量里的就记进这一批的数字，再放下一个出去。 */
    synchronized void checked(String jobId) {
        stopRequested.remove(jobId);
        singleChecks.remove(jobId);
        if (!checkRunning.remove(jobId)) {
            threads.events().changed(null, jobId);
            return;
        }
        checkDone.add(jobId);
        String before = checkedBefore.remove(jobId);
        try {
            Job now = threads.job(jobId);
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
            log.warn("Job {} 查状态的结果没读出来：{}", jobId, JobThreadService.rootMessage(e));
        }
        dispatchChecks();
        if (checkQueue.isEmpty() && checkRunning.isEmpty() && !checkToOpen.isEmpty()) {
            openForUser(List.copyOf(checkToOpen.values()));
            checkToOpen.clear();
        }
        threads.events().changed(null, jobId);
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
     * 这些页属于用户，不记在任何运行名下。另起一个线程开，不占着 this。
     */
    private void openForUser(List<String> urls) {
        Thread.ofVirtual().name("status-check-open").start(() -> {
            List<String> safe = new ArrayList<>();
            for (String url : urls) {
                try {
                    if (threads.fetch() != null) {
                        threads.fetch().checkUrl(url);
                    }
                    safe.add(url);
                } catch (RuntimeException e) {
                    log.warn("不给用户开这页 {}：{}", url, JobThreadService.rootMessage(e));
                }
            }
            try {
                List<String> opened = browser.openForUser(safe);
                synchronized (this) {
                    checkOpened.addAll(opened);
                }
            } catch (RuntimeException e) {
                log.warn("查状态结束后没能给用户开页：{}", JobThreadService.rootMessage(e));
            }
            threads.events().changed(null, null);
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

    private Optional<Job> find(String jobId) {
        for (String project : layout.projectIds()) {
            if (jobs.exists(project, jobId)) {
                return Optional.of(jobs.load(project, jobId));
            }
        }
        return Optional.empty();
    }
}
