package jobapply.workflow;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import jobapply.config.HarnessProperties;
import jobapply.domain.Actor;
import jobapply.domain.AgentRun;
import jobapply.domain.Job;
import jobapply.domain.LeadStatus;
import jobapply.domain.Phase;
import jobapply.domain.PrepState;
import jobapply.domain.Transition;
import jobapply.harness.runtime.HarnessLoop;
import jobapply.persistence.AgentRunStore;
import jobapply.persistence.AssetRegistry;
import jobapply.persistence.AttachmentStore;
import jobapply.persistence.FillReportWriter;
import jobapply.persistence.JobAnswers;
import jobapply.persistence.JobLedger;
import jobapply.persistence.JobMaterials;
import jobapply.persistence.JobRepository;
import jobapply.persistence.ProjectRepository;
import jobapply.persistence.RunLog;
import jobapply.persistence.SessionRepository;
import jobapply.persistence.StorageLayout;
import jobapply.persistence.UploadArea;
import jobapply.tools.BrowserException;
import jobapply.tools.BrowserSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 申请线程的入口（agent-runtime.md 7.1）：开始、回复、停止、查状态、读报告。流程由 {@link HarnessLoop} 里的模型决定；
 * 这里负责排队（一个单线程执行器就是「浏览器串行队列」）、存取 {@link AgentRun}、把用户的回复变成下一条消息、
 * 准备上传的文件、更新台账的 {@code prep_state}，以及模型结束时把报告定稿。
 * <p>
 * 同一时间只有一条线程在跑；一个 Job 在等用户回复时占着浏览器（标签页上是它填了一半的表），别的 Job 不能开始。
 * 停在提交前（{@code ready}）的不占。所有校验都在第一次写文件之前完成，被拒绝的请求不写任何东西。
 */
public final class ApplicationRunner implements AutoCloseable {

    /** 一条回复、一个答案最多这么多字符。 */
    public static final int MAX_TEXT = 2000;

    /**
     * 用户的一次回复。
     *
     * @param text    写给模型的话（例如「登录好了」、要改什么）；可以为空
     * @param answers 问题编号 → 回答（空白的当作没回答）
     * @param files   每个上传位置一个原件键，顺序同 {@code fileSlots}；跳过的选填位置写空字符串
     * @param attachments 用户附的文件（{@link #attachments(String)} 里的编号，C006 附件）；可以为 null
     */
    public record Reply(String session, String text, Map<String, String> answers, List<String> files,
                        List<String> attachments) {

        public Reply(String session, String text, Map<String, String> answers, List<String> files) {
            this(session, text, answers, files, null);
        }
    }

    private static final Set<PrepState> STARTABLE = Set.of(PrepState.SELECTED, PrepState.PREPARING,
            PrepState.NEEDS_LOGIN, PrepState.NEEDS_INPUT, PrepState.HANDOFF_USER, PrepState.FAILED,
            PrepState.AWAITING_REVIEW, PrepState.PAUSED);

    private static final Logger log = LoggerFactory.getLogger(ApplicationRunner.class);

    private final StorageLayout layout;
    private final Clock clock;
    private final BrowserSession browser;
    private final Supplier<ChatModel> mainModel;
    private final HarnessProperties limits;
    private final AgentRunStore runs;
    private final JobRepository jobs;
    private final ProjectRepository projects;
    private final SessionRepository sessions;
    private final JobLedger ledger;
    private final JobAnswers answers;
    private final AssetRegistry assets;
    private final JobMaterials materials;
    private final UploadArea uploads;
    private final FillReportWriter reports;
    private final RunLog runLog;
    private final ExecutorService queue = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "browser-queue");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<String> stopRequested = new HashSet<>();
    private final LiveFeed feed;

    private HarnessLoop loop;
    private String runningJob;
    private Future<?> current;

    /**
     * @param mainModel 主模型，用到时才取（没有配置时抛出的异常会显示在状态的 {@code error} 里）
     * @param limits    兜底上限与重复提醒；为 null 时用默认值
     */
    public ApplicationRunner(StorageLayout layout, Clock clock, BrowserSession browser, Supplier<ChatModel> mainModel,
                             HarnessProperties limits) {
        this.layout = layout;
        this.clock = clock;
        this.browser = browser;
        this.mainModel = mainModel;
        this.limits = limits == null ? HarnessProperties.defaults() : limits;
        this.runs = new AgentRunStore(layout);
        this.jobs = new JobRepository(layout);
        this.projects = new ProjectRepository(layout, clock);
        this.sessions = new SessionRepository(layout, clock);
        this.ledger = new JobLedger(layout, clock);
        this.answers = new JobAnswers(layout, clock);
        this.assets = new AssetRegistry(layout, clock);
        this.materials = new JobMaterials(layout, assets, clock);
        this.uploads = new UploadArea(layout, assets);
        this.reports = new FillReportWriter(layout, clock);
        this.runLog = new RunLog(layout, clock);
        this.feed = new LiveFeed(clock);
    }

    /** 每个 Job 的申请线程现在在做什么、订阅推送（C006-agent）。 */
    public LiveFeed feed() {
        return feed;
    }

    /**
     * 对一个 Job 开始申请线程。还是候选（新发现 / 想投）时先转为申请（C005：候选池和对话 Agent 都从这里开始）。
     * 对同一个 Job 再次开始会丢掉上一次的对话，重新开始。
     */
    public synchronized ApplicationStatus start(String jobId, String projectId, String sessionId) {
        requireSession(projectId, sessionId);
        if (!jobs.exists(projectId, jobId)) {
            throw new ApplicationException.NotFound("Job 不存在：" + projectId + "/" + jobId);
        }
        Job job = jobs.load(projectId, jobId);
        LeadStatus lead = job.status().leadStatus();
        boolean convert = lead == LeadStatus.NEW || lead == LeadStatus.SHORTLISTED;
        if (!convert && (job.status().phase() != Phase.PREP || !STARTABLE.contains(job.status().prepState()))) {
            throw new ApplicationException.Conflict("Job 现在是 " + job.status().label() + "，只有候选和 prep 阶段可以开始申请线程");
        }
        String url = job.posting().url();
        if (url == null || url.isBlank()) {
            throw new ApplicationException.Conflict("Job " + jobId + " 没有岗位链接");
        }
        requireBrowserFree(jobId);
        if (convert) {
            ledger.transition(projectId, jobId, sessionId, Actor.USER, new Transition.Convert());
        }

        String opening = "开始申请：" + job.posting().company() + " · " + job.posting().title()
                + "。先看页面：判断要不要登录、怎么进入申请表，然后按工作方法填写，停在最终提交之前。";
        AgentRun run = AgentRun.start(projectId, jobId, sessionId, job.posting().company(), job.posting().title(), url,
                opening);
        moveTo(run, PrepState.PREPARING, Actor.USER, "开始申请线程");
        uploads.reset(jobId);
        runs.save(run);
        submit(run);
        return status(jobId);
    }

    /**
     * 回复：回答问题、选文件、告诉模型做好了，或者在提交前要求修改。不在跑的线程都可以回复；
     * 内容不对时拒绝（{@link ApplicationException.BadRequest}），不写任何东西。
     */
    public synchronized ApplicationStatus reply(String jobId, Reply reply) {
        AgentRun run = find(jobId);
        if (jobId.equals(runningJob)) {
            throw new ApplicationException.Conflict("Job " + jobId + " 正在运行，等它停下来再回复（或者先停止）");
        }
        requireSession(run.project(), reply.session());
        requireBrowserFree(jobId);
        String text = reply.text() == null ? "" : reply.text().strip();
        if (text.length() > MAX_TEXT) {
            throw new ApplicationException.BadRequest("回复不能超过 " + MAX_TEXT + " 个字符");
        }
        List<JobAnswers.NewAnswer> newAnswers = answers(run, reply.answers());
        List<JobMaterials.Selection> chosen = files(run, reply.files());
        AttachmentStore attached = attachments(jobId);
        List<AttachmentStore.Stored> sent = (reply.attachments() == null ? List.<String>of() : reply.attachments())
                .stream().distinct().map(attached::require).toList();

        // 校验到此为止，下面开始写
        StringBuilder message = new StringBuilder();
        message.append(!text.isEmpty() ? "用户回复：" + text : sent.isEmpty() ? "用户让你继续。" : "用户发来了附件。");
        if (!newAnswers.isEmpty()) {
            List<JobAnswers.Answer> saved;
            try {
                saved = answers.append(run.project(), jobId, reply.session(), newAnswers);
            } catch (JobAnswers.ClassCQuestionException e) {
                throw new ApplicationException.BadRequest(e.getMessage());
            }
            message.append("\n用户回答了问题（已经放进「本次答案」，fact 写答案编号）：");
            saved.forEach(answer -> message.append("\n- ").append(answer.id()).append("「").append(answer.question())
                    .append("」：").append(answer.value()));
        }
        List<AgentRun.StagedFile> files = new ArrayList<>(run.files());
        if (!chosen.isEmpty()) {
            files = stage(run, chosen);
            message.append("\n用户为上传位置选好了文件，用 browser_file_upload 按编号上传：");
            for (int i = run.files().size(); i < files.size(); i++) {
                AgentRun.StagedFile file = files.get(i);
                message.append("\n- 文件 ").append(i + 1).append("：").append(file.uploadName());
                if (file.slot() != null && !file.slot().isBlank()) {
                    message.append("（").append(file.slot()).append("）");
                }
            }
        }
        if (reply.files() != null && chosen.isEmpty()) {
            message.append("\n用户没有为上传位置选文件（都是选填，跳过了）。");
        }
        // 附件接着文件编号往后排，模型可以直接 browser_file_upload（编号写在发给模型的附件那一行里）
        List<AgentRun.Attachment> turnAttachments = new ArrayList<>();
        for (AttachmentStore.Stored stored : sent) {
            files.add(new AgentRun.StagedFile(null, stored.name(), null, stored.size(), stored.sha256(),
                    stored.id()));
            turnAttachments.add(stored.attachment(files.size()));
        }
        int round = run.round();
        if (AgentRun.READY.equals(run.status())) {
            round++;
            message.insert(0, "用户在提交前审阅后要求修改（第 " + round + " 轮）。");
            runLog.append(run.project(), jobId, RunLog.Entry.requestChanges(reply.session(), step(run), text));
        } else {
            runLog.append(run.project(), jobId, RunLog.Entry.userSignal(reply.session(), step(run), "reply"));
        }
        AgentRun resumed = run.resumed(AgentRun.Turn.user(message.toString(), turnAttachments), files, round);
        moveTo(resumed, PrepState.PREPARING, Actor.USER, "用户回复，继续申请");
        runs.save(resumed);
        submit(resumed);
        return status(jobId);
    }

    /** 这个 Job 申请线程的附件（C006 附件）；没有申请线程时 404。运行中也可以上传，发出去要等它停下。 */
    public synchronized AttachmentStore attachments(String jobId) {
        find(jobId);
        return new AttachmentStore(layout.jobAttachmentsDir(jobId));
    }

    /** 请正在跑的线程停下（在当前这一次工具调用之后）。没在跑时什么都不做。 */
    public synchronized ApplicationStatus stop(String jobId) {
        find(jobId);
        if (jobId.equals(runningJob)) {
            stopRequested.add(jobId);
        }
        return status(jobId);
    }

    /** 这个 Job 有申请线程时返回它的状态；没有时为空（界面上的 Job 列表用）。 */
    public synchronized Optional<ApplicationStatus> statusIfAny(String jobId) {
        return runs.find(jobId).isPresent() ? Optional.of(status(jobId)) : Optional.empty();
    }

    /** 申请线程的对话（工作台显示每一步）；没有申请线程时为空。 */
    public synchronized Optional<AgentRun> run(String jobId) {
        return runs.find(jobId);
    }

    public synchronized ApplicationStatus status(String jobId) {
        AgentRun run = find(jobId);
        String status = jobId.equals(runningJob) ? AgentRun.RUNNING : run.status();
        String prepState = null;
        try {
            prepState = jobs.load(run.project(), jobId).status().prepState().value();
        } catch (RuntimeException e) {
            // job.md 读不出来时只是不显示
        }
        String report = reports.isDraft(run.project(), jobId).map(draft -> draft ? "draft" : "final").orElse(null);
        return new ApplicationStatus(jobId, run.project(), run.session(), status,
                AgentRun.RUNNING.equals(status) ? null : run.message(), run.questions(), run.fileSlots(),
                run.fileSlots().isEmpty() ? List.of() : assets.list(), run.disconnected(), run.plan(), run.usage(),
                prepState, report, run.round(), run.error());
    }

    /** 这个 Job 的填写报告（Markdown，草稿或定稿）；还没有报告时为空。 */
    public synchronized Optional<String> report(String jobId) {
        return reports.read(find(jobId).project(), jobId);
    }

    /** 等当前这次执行结束（手动试跑用）。超时返回 false。 */
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

    // ---- 运行 ----

    private void submit(AgentRun run) {
        String jobId = run.job();
        runningJob = jobId;
        stopRequested.remove(jobId);
        feed.started(jobId, AgentRun.RUNNING);
        current = queue.submit(() -> {
            AgentRun result;
            try {
                result = execute(run);
            } catch (RuntimeException e) {
                String message = rootMessage(e);
                log.warn("Job {} 的申请线程出错：{}", jobId, message);
                result = runs.find(jobId).orElse(run).failed(message);
            }
            try {
                settle(result);
            } catch (RuntimeException e) {
                log.warn("Job {} 结束时更新台账出错：{}", jobId, rootMessage(e));
                result = result.failed(rootMessage(e));
            }
            synchronized (this) {
                runs.save(result);
                runningJob = null;
                stopRequested.remove(jobId);
            }
            feed.finished(jobId, result.status());
        });
    }

    private AgentRun execute(AgentRun run) {
        if (run.disconnected()) {
            try {
                browser.reconnect();
            } catch (BrowserException.Disconnected e) {
                return run.waiting("还是连不上浏览器（" + e.getMessage() + "）。请确认 Chrome 开着、chrome://inspect/#remote-debugging 里的远程调试开关已经打开，然后再回复。",
                        List.of(), List.of(), true);
            }
            run = run.reconnected();
        }
        String jobId = run.job();
        return loop().run(run, () -> stopRequested(jobId), runs::save, feed.observer(jobId));
    }

    /** 一次运行结束：按结果更新 {@code prep_state}；停在提交前时把报告定稿、清空上传区。 */
    private void settle(AgentRun run) {
        switch (run.status()) {
            case AgentRun.READY -> {
                FillReportWriter.Report report = reports.load(run.project(), run.job()).orElseGet(() ->
                        new FillReportWriter.Report(run.company(), run.title(), run.round(), List.of(), List.of(),
                                List.of(), List.of()));
                reports.writeFinal(run.project(), run.job(), report);
                moveTo(run, PrepState.AWAITING_REVIEW, Actor.AGENT, "填写完成，等用户审阅（第 " + run.round() + " 轮）");
                uploads.clear(run.job());
            }
            case AgentRun.WAITING_USER -> moveTo(run, PrepState.NEEDS_INPUT, Actor.AGENT, "等用户回复");
            case AgentRun.FAILED -> moveTo(run, PrepState.FAILED, Actor.SYSTEM, "申请线程出错");
            default -> {
            }
        }
    }

    private synchronized boolean stopRequested(String jobId) {
        return stopRequested.contains(jobId);
    }

    private synchronized HarnessLoop loop() {
        if (loop == null) {
            loop = new HarnessLoop(mainModel.get(), browser, layout, clock, limits);
        }
        return loop;
    }

    // ---- 校验和准备 ----

    /** 把问题编号换成问题原文；C 级问题、不存在的编号都拒绝。空白的回答当作没回答。 */
    private static List<JobAnswers.NewAnswer> answers(AgentRun run, Map<String, String> given) {
        List<JobAnswers.NewAnswer> result = new ArrayList<>();
        if (given == null) {
            return result;
        }
        for (Map.Entry<String, String> entry : given.entrySet()) {
            AgentRun.Question question = run.questions().stream().filter(q -> q.id().equals(entry.getKey()))
                    .findFirst().orElseThrow(() -> new ApplicationException.BadRequest("没有这个问题：" + entry.getKey()));
            if ("C".equals(question.fieldClass())) {
                throw new ApplicationException.BadRequest("「" + question.text() + "」是 C 级信息，不能在这里回答，"
                        + "只能由你在浏览器里亲自填");
            }
            String value = entry.getValue() == null ? "" : entry.getValue().strip();
            if (value.isEmpty()) {
                continue;
            }
            if (value.length() > MAX_TEXT) {
                throw new ApplicationException.BadRequest("「" + question.text() + "」的回答不能超过 " + MAX_TEXT + " 个字符");
            }
            result.add(new JobAnswers.NewAnswer(question.text(), value));
        }
        return result;
    }

    /** 每个上传位置一个原件键；必填的位置不能空，原件必须已经登记。没给 {@code files} 时为空。 */
    private List<JobMaterials.Selection> files(AgentRun run, List<String> given) {
        if (given == null) {
            return List.of();
        }
        List<AgentRun.FileSlot> slots = run.fileSlots();
        if (given.size() != slots.size()) {
            throw new ApplicationException.BadRequest("有 " + slots.size() + " 个上传位置，选了 " + given.size() + " 个文件");
        }
        List<JobMaterials.Selection> chosen = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            String key = given.get(i) == null ? "" : given.get(i).strip();
            AgentRun.FileSlot slot = slots.get(i);
            if (key.isEmpty()) {
                if (slot.required()) {
                    throw new ApplicationException.BadRequest("上传位置「" + slot.label() + "」是必填的");
                }
                continue;
            }
            if (assets.find(key).isEmpty()) {
                throw new ApplicationException.BadRequest("原件不存在：" + key);
            }
            chosen.add(new JobMaterials.Selection(slot.label(), key));
        }
        return chosen;
    }

    /**
     * 把新选的原件写进 {@code job.md} 的 {@code materials}（连同之前选的原件；附件不进资料库，不写），复制到上传区；
     * 编号接着之前的往后排。
     */
    private List<AgentRun.StagedFile> stage(AgentRun run, List<JobMaterials.Selection> chosen) {
        List<JobMaterials.Selection> all = new ArrayList<>();
        run.files().stream().filter(file -> file.attachment() == null)
                .forEach(file -> all.add(new JobMaterials.Selection(file.slot(), file.assetKey())));
        all.addAll(chosen);
        List<JobMaterials.Material> written = materials.write(run.project(), run.job(), all);
        List<AgentRun.StagedFile> files = new ArrayList<>(run.files());
        for (int i = written.size() - chosen.size(); i < written.size(); i++) {
            JobMaterials.Material material = written.get(i);
            Path copy = uploads.stage(run.job(), material.asset());
            try {
                files.add(new AgentRun.StagedFile(material.asset(), material.uploadName(), material.slot(),
                        Files.size(copy), material.sha256()));
            } catch (IOException e) {
                throw new UncheckedIOException("读不到上传区的副本：" + copy, e);
            }
        }
        return files;
    }

    private void requireSession(String projectId, String sessionId) {
        if (!projects.exists(projectId)) {
            throw new ApplicationException.NotFound("Project 不存在：" + projectId);
        }
        if (sessionId == null || !sessions.exists(projectId, sessionId)) {
            throw new ApplicationException.NotFound("Session 不存在：" + projectId + "/" + sessionId);
        }
    }

    /** 浏览器没被别的 Job 占着：没有别的线程在跑，也没有别的 Job 停在半路等用户。 */
    private void requireBrowserFree(String jobId) {
        if (runningJob != null) {
            throw new ApplicationException.Conflict("浏览器正在执行 Job " + runningJob + " 的申请线程，请稍后再试");
        }
        for (String project : layout.projectIds()) {
            for (String other : layout.jobIds(project)) {
                if (other.equals(jobId)) {
                    continue;
                }
                Optional<AgentRun> run = runs.find(other);
                if (run.isPresent() && AgentRun.WAITING_USER.equals(run.get().status())) {
                    throw new ApplicationException.Conflict("浏览器正被 Job " + other + " 使用（在等你回复），请先完成它");
                }
            }
        }
    }

    private AgentRun find(String jobId) {
        return runs.find(jobId).orElseThrow(() -> new ApplicationException.NotFound("Job " + jobId + " 没有申请线程"));
    }

    /** 推进 {@code prep_state}（经过台账，事件跟着更新）。已经是目标状态时什么都不做。 */
    private void moveTo(AgentRun run, PrepState target, Actor actor, String reason) {
        Job job = jobs.load(run.project(), run.job());
        if (job.status().phase() != Phase.PREP) {
            throw new ApplicationException.Conflict("Job " + run.job() + " 已经不在 prep 阶段（现在是 " + job.status().label()
                    + "）");
        }
        if (job.status().prepState() != target) {
            ledger.transition(run.project(), run.job(), run.session(), actor,
                    new Transition.ChangePrepState(target, reason));
        }
    }

    private static int step(AgentRun run) {
        return (int) run.usage().modelCalls();
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
