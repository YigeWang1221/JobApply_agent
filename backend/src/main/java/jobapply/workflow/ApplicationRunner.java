package jobapply.workflow;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
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
import jobapply.domain.LiveRuns;
import jobapply.domain.PrepState;
import jobapply.domain.Transition;
import jobapply.harness.policies.SubmitWords;
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
import jobapply.tools.BrowserSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 申请线程的入口（agent-runtime.md 7.1）：开始、回复、停止、查状态、读报告。流程由 {@link HarnessLoop} 里的模型决定；
 * 这里负责排队（一个单线程执行器就是「浏览器串行队列」）、存取 {@link AgentRun}、把用户的回复变成下一条消息、
 * 准备上传的文件、更新台账的 {@code prep_state}，以及模型结束时把报告定稿。
 * <p>
 * 同一时间只有一条线程在跑；别的开始和回复排队（{@code queued}），按顺序轮到（C006-fix-2 第 1 条，langgraph 的
 * {@code enqueue}）。每个 Job 用自己的标签页，在等用户回复的不挡别人。「在跑 / 排队」只看内存，文件写着在跑、内存里没有的
 * 读出来是中断（{@link LiveRuns}）。所有校验都在第一次写文件之前完成，被拒绝的请求不写任何东西。
 */
public final class ApplicationRunner implements AutoCloseable {

    /** 一条回复、一个答案最多这么多字符。 */
    public static final int MAX_TEXT = 2000;

    /**
     * 用户的一次回复。
     *
     * @param text    写给模型的话（例如「登录好了」、要改什么）；可以为空
     * @param answers 问题编号 → 回答（空白的当作没回答）
     * @param files   每个上传位置一项，顺序同 {@code fileSlots}：原件键，或者 {@link #ATTACHMENT}{@code <附件编号>}
     *                （这个申请的附件）；没选写空字符串（必填的也可以不选，由模型判断、再问）
     * @param attachments 用户附的文件（{@link #attachments(String)} 里的编号，C006 附件）；可以为 null
     */
    public record Reply(String session, String text, Map<String, String> answers, List<String> files,
                        List<String> attachments) {

        public Reply(String session, String text, Map<String, String> answers, List<String> files) {
            this(session, text, answers, files, null);
        }
    }

    /** 上传位置选的是这个申请的附件时写 {@code attachment:<附件编号>}（C006-fix 第 5 节）。 */
    public static final String ATTACHMENT = "attachment:";

    /** 文件写着在跑、这个进程里没有（应用重启前被中断）时，状态里的 {@code error}。 */
    static final String INTERRUPTED_ERROR = "应用重启了，这次申请被中断，填了一半的表可能还在 Chrome 里。回复后接着做。";
    /** 排队时用户要求停下：轮到它时不跑。 */
    static final String STOPPED_IN_QUEUE = "排队时按你的要求停下了，还没有开始。回复后重新排队。";

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
    private final RuntimeEvents events;

    /** 交给执行器、还没跑完的 Job（排队的和正在跑的）。 */
    private final LiveRuns live = new LiveRuns();
    /**
     * 排着队的 Job → 这次排队的凭证，按交给执行器的顺序。任务开头凭证不在了（排队时被停止移出、或者之后又重新排了一次），
     * 这个任务就不跑（C006-fix-2 最终审查第 3 条：{@code cancel(false)} 挡不住已经被执行器取走、在等锁的任务）。
     */
    private final Map<String, Object> queued = new LinkedHashMap<>();
    /** 排着队的 Job 交给执行器的任务（排队时停止要取消它）。 */
    private final Map<String, Future<?>> waitingInLine = new HashMap<>();
    private String runningJob;
    private Future<?> current;
    private volatile Reporter reporter = (project, session, job, text) -> {
    };

    /**
     * 申请线程一次运行结束时回报给发起它的 Session（C006-fix 第 2 节，Deep Agents 的 {@code notify_parent}）。
     * 由 {@link SessionRunner} 实现：记进 transcript，不唤醒模型。
     */
    public interface Reporter {
        void report(String projectId, String sessionId, String jobId, String text);
    }

    public ApplicationRunner(StorageLayout layout, Clock clock, BrowserSession browser, Supplier<ChatModel> mainModel,
                             HarnessProperties limits) {
        this(layout, clock, browser, mainModel, limits, new RuntimeEvents(clock));
    }

    /**
     * @param mainModel 主模型，用到时才取（没有配置时抛出的异常会显示在状态的 {@code error} 里）
     * @param limits    兜底上限与重复提醒；为 null 时用默认值
     * @param events    事件日志（和 {@link SessionRunner}、台账共用）
     */
    public ApplicationRunner(StorageLayout layout, Clock clock, BrowserSession browser, Supplier<ChatModel> mainModel,
                             HarnessProperties limits, RuntimeEvents events) {
        this.events = events;
        this.layout = layout;
        this.clock = clock;
        this.browser = browser;
        this.mainModel = mainModel;
        this.limits = limits == null ? HarnessProperties.defaults() : limits;
        this.runs = new AgentRunStore(layout);
        this.jobs = new JobRepository(layout);
        this.projects = new ProjectRepository(layout, clock);
        this.sessions = new SessionRepository(layout, clock);
        this.ledger = new JobLedger(layout, clock, events::changed);
        this.answers = new JobAnswers(layout, clock);
        this.assets = new AssetRegistry(layout, clock);
        this.materials = new JobMaterials(layout, assets, clock);
        this.uploads = new UploadArea(layout, assets);
        this.reports = new FillReportWriter(layout, clock);
        this.runLog = new RunLog(layout, clock);
    }

    /** 事件日志：每个 Job 的申请线程现在在做什么、推送（C006-fix）。 */
    public RuntimeEvents events() {
        return events;
    }

    /** 一次运行结束时回报给谁（{@link SessionRunner} 创建时设置）。 */
    void reportTo(Reporter reporter) {
        this.reporter = reporter;
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
        if (!job.status().canStartApplication()) {
            throw new ApplicationException.Conflict("Job 现在是 " + job.status().label() + "，只有候选和 prep 阶段可以开始申请线程");
        }
        String url = job.posting().url();
        if (url == null || url.isBlank()) {
            throw new ApplicationException.Conflict("Job " + jobId + " 没有岗位链接");
        }
        if (live.contains(jobId)) {
            throw new ApplicationException.Conflict("Job " + jobId + " 的申请线程" + busy(jobId)
                    + "，等它停下来再重新开始（或者先停止）");
        }
        if (convert) {
            ledger.transition(projectId, jobId, sessionId, Actor.USER, new Transition.Convert());
        }

        String opening = "开始申请：" + job.posting().company() + " · " + job.posting().title()
                + "。先看页面：判断要不要登录、怎么进入申请表，然后按工作方法填写，停在最终提交之前。";
        // C008：对话 Agent 按这个岗位选的简历；上传位置用 ask_user 的 files 请用户确认，工作台默认选中它
        if (job.extra().get("resume_choice") instanceof Map<?, ?> choice && choice.get("file") instanceof String file) {
            opening += "\n拟用简历：" + file + "（" + choice.get("asset") + "）。到简历上传位置时用 ask_user 的 files 请用户"
                    + "确认（工作台已默认选中它）。";
        }
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
        if (live.contains(jobId)) {
            throw new ApplicationException.Conflict("Job " + jobId + " " + busy(jobId) + "，等它停下来再回复（或者先停止）");
        }
        // 用户已经定下了（已提交、已投递、结束了）：不再让申请 Agent 接着做（C006-fix-2 回归第 1 条）。先放着的可以回复
        Job job = jobs.load(run.project(), jobId);
        if (JobLedger.userDecided(job.status(), Actor.USER)) {
            throw new ApplicationException.Conflict("Job " + jobId + " 现在是 " + job.status().label()
                    + "，你已经定下了，申请 Agent 不再接着做；投递后的进展在 Session 对话里告诉 Agent");
        }
        requireSession(run.project(), reply.session());
        String text = reply.text() == null ? "" : reply.text().strip();
        if (text.length() > MAX_TEXT) {
            throw new ApplicationException.BadRequest("回复不能超过 " + MAX_TEXT + " 个字符");
        }
        List<JobAnswers.NewAnswer> newAnswers = answers(run, reply.answers());
        AttachmentStore attached = attachments(jobId);
        List<AttachmentStore.Stored> sent = (reply.attachments() == null ? List.<String>of() : reply.attachments())
                .stream().distinct().map(attached::require).toList();
        List<Choice> chosen = files(run, reply.files(), attached);

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
        List<AgentRun.StagedFile> files = stage(run, chosen);
        // 附件编号 → 文件编号（为上传位置选的附件，下面同一个附件不再排一个编号）
        Map<String, Integer> attachmentFiles = new HashMap<>();
        if (!chosen.isEmpty()) {
            message.append(chosen.stream().anyMatch(Choice::chosen)
                    ? "\n用户为上传位置选了文件，用 browser_file_upload 按编号上传到对应的位置：" : "\n用户没有为上传位置选文件：");
            int number = run.files().size();
            boolean requiredEmpty = false;
            for (Choice choice : chosen) {
                AgentRun.FileSlot slot = choice.slot();
                message.append("\n- 「").append(slot.label()).append("」");
                if (!choice.chosen()) {
                    message.append(slot.required() ? "（必填）：没有选" : "（选填）：跳过了");
                    requiredEmpty |= slot.required();
                    continue;
                }
                number++;
                AgentRun.StagedFile file = files.get(number - 1);
                message.append("：文件 ").append(number).append(" ").append(file.uploadName())
                        .append(choice.asset() != null ? "（资料库原件）" : "（用户附的文件）");
                if (choice.attachment() != null) {
                    attachmentFiles.put(choice.attachment().id(), number);
                }
            }
            if (requiredEmpty) {
                message.append("\n必填的位置没有选文件：先看页面上是不是真的必填、用户这次附的文件能不能用；还缺就用 ask_user 再问用户。");
            }
        }
        // 附件接着文件编号往后排，模型可以直接 browser_file_upload（编号写在发给模型的附件那一行里）
        List<AgentRun.Attachment> turnAttachments = new ArrayList<>();
        for (AttachmentStore.Stored stored : sent) {
            Integer number = attachmentFiles.get(stored.id());
            if (number == null) {
                files.add(new AgentRun.StagedFile(null, stored.name(), null, stored.size(), stored.sha256(),
                        stored.id()));
                number = files.size();
            }
            turnAttachments.add(stored.attachment(number));
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

    /**
     * 请正在跑的线程停下（在当前这一次工具调用之后）；排着队的立刻移出队列、记成等你。没在跑时（包括中断了的）什么都不做。
     */
    public synchronized ApplicationStatus stop(String jobId) {
        AgentRun run = find(jobId);
        if (!live.contains(jobId)) {
            return status(jobId);
        }
        Future<?> waiting = waitingInLine.remove(jobId);
        if (queued.containsKey(jobId) && waiting != null && waiting.cancel(false)) {
            // 排着队：立刻移出队列，记成等你（C006-fix-2 回归第 8 条）。用户按的停止，不回报。执行器已经取走、在等锁的
            // 任务看到凭证没了，自己不跑
            queued.remove(jobId);
            AgentRun result = run.waiting(STOPPED_IN_QUEUE, List.of(), List.of());
            try {
                settle(result);
            } catch (RuntimeException e) {
                log.warn("Job {} 排队时停下，更新台账出错：{}", jobId, rootMessage(e));
                result = result.failed(rootMessage(e));
            }
            try {
                runs.save(result);
            } finally {
                live.remove(jobId);
                stopRequested.remove(jobId);
            }
            events.finished(result.session(), jobId, result.status());
            return status(jobId);
        }
        // 在跑（排着队但取消没成功的，轮到时不跑）
        stopRequested.add(jobId);
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
        // 在跑、排队只看内存；文件写着在跑、内存里没有的是中断（C006-fix-2 第 3 条）
        boolean interrupted = live.interrupted(jobId, AgentRun.RUNNING.equals(run.status()));
        String prepState = null;
        boolean decided = false;
        try {
            Job job = jobs.load(run.project(), jobId);
            prepState = job.status().prepState() == null ? null : job.status().prepState().value();
            decided = JobLedger.userDecided(job.status(), Actor.USER);
        } catch (RuntimeException e) {
            // job.md 读不出来时只是不显示
        }
        // 用户已经定下了（已提交、结束了）、也不在跑：这个申请线程结束了，不能再回复（C006-fix-2 回归第 1 条）
        String status = jobId.equals(runningJob) ? AgentRun.RUNNING
                : queued.containsKey(jobId) ? AgentRun.QUEUED
                : interrupted && !decided ? AgentRun.INTERRUPTED
                : decided && !live.contains(jobId) ? AgentRun.DONE : run.status();
        interrupted = AgentRun.INTERRUPTED.equals(status);
        // 浏览器断开从全局的连接状态推出来（C006-fix-browser 第 1 节）：在等你、中断了，而浏览器现在断着
        boolean disconnected = browser.state().disconnected()
                && (AgentRun.WAITING_USER.equals(status) || interrupted);
        String report = reports.isDraft(run.project(), jobId).map(draft -> draft ? "draft" : "final").orElse(null);
        return new ApplicationStatus(jobId, run.project(), run.session(), status,
                AgentRun.RUNNING.equals(status) ? null : run.message(), run.questions(), run.fileSlots(),
                run.fileSlots().isEmpty() ? List.of() : assets.list(), storedAttachments(jobId), disconnected,
                run.plan(), run.usage(), prepState, report, run.round(), interrupted ? INTERRUPTED_ERROR : run.error());
    }

    /** 这个申请已经上传的附件（上传位置的下拉框里和原件一起列出，C006-fix 第 5 节）。 */
    private List<ApplicationStatus.Attachment> storedAttachments(String jobId) {
        String url = "/api/jobs/" + jobId + "/application/attachments/";
        return new AttachmentStore(layout.jobAttachmentsDir(jobId)).list().stream()
                .map(stored -> new ApplicationStatus.Attachment(stored.id(), stored.name(), stored.contentType(),
                        stored.image(), url + stored.id()))
                .toList();
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
        } catch (ExecutionException | CancellationException e) {
            // 被取消的是排队时停下的那个
            return true;
        }
    }

    @Override
    public void close() {
        queue.shutdownNow();
    }

    // ---- 运行 ----

    /**
     * 交给执行器（一个线程，就是浏览器的队列）。浏览器空着时直接算在跑；有别的在跑或在排队时排在最后（{@code queued}），
     * 轮到时再发 {@code running}。调用方持有锁。
     */
    private void submit(AgentRun run) {
        String jobId = run.job();
        live.add(jobId);
        stopRequested.remove(jobId);
        boolean waitInLine = runningJob != null || !queued.isEmpty();
        Object ticket = new Object();
        if (waitInLine) {
            queued.put(jobId, ticket);
        } else {
            runningJob = jobId;
        }
        events.started(run.session(), jobId, waitInLine ? AgentRun.QUEUED : AgentRun.RUNNING);
        current = queue.submit(() -> {
            boolean stoppedInQueue;
            synchronized (this) {
                boolean wasQueued = waitInLine && queued.remove(jobId, ticket);
                if (waitInLine && !wasQueued) {
                    // 排队时已经被停止移出（stop 存好了状态、发过了 finished），或者之后又排了一次（那是新的任务）：不跑
                    return;
                }
                waitingInLine.remove(jobId);
                runningJob = jobId;
                stoppedInQueue = wasQueued && stopRequested.contains(jobId);
                if (wasQueued && !stoppedInQueue) {
                    events.started(run.session(), jobId, AgentRun.RUNNING);
                }
            }
            AgentRun result;
            try {
                result = stoppedInQueue ? run.waiting(STOPPED_IN_QUEUE, List.of(), List.of())
                        : execute(run);
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
            // 用户按停止停下的（在跑时停、排队时停）：用户自己知道，不回报（C006-fix-2 回归第 6 条）
            boolean stoppedByUser;
            synchronized (this) {
                stoppedByUser = stopRequested.contains(jobId) && AgentRun.WAITING_USER.equals(result.status());
                try {
                    runs.save(result);
                } finally {
                    runningJob = null;
                    live.remove(jobId);
                    stopRequested.remove(jobId);
                }
            }
            events.finished(result.session(), jobId, result.status());
            if (!stoppedByUser) {
                reportToSession(result);
            }
        });
        if (waitInLine) {
            waitingInLine.put(jobId, current);
        }
    }

    /**
     * 回报给发起它的 Session（C006-fix 第 2 节）：停在提交前、等用户、出错时写一句结果和要用户做什么。
     * 用户已经自己定了状态（已提交、结束了、先放着）、或者是用户按停止停下的时不报：那是用户说的，不用再告诉用户。
     * 页面像已经投递了（{@link AgentRun#pageShowsSubmitted()}）时请用户确认，不说「请提交」。回报失败只记日志。
     */
    private void reportToSession(AgentRun run) {
        try {
            Job job = jobs.load(run.project(), run.job());
            // 先放着的（paused）也不报：用户让它先放着
            if (JobLedger.userDecided(job.status(), Actor.AGENT)) {
                return;
            }
            String name = "申请 " + run.company() + " · " + run.title() + "（" + run.job() + "）";
            String text = switch (run.status()) {
                case AgentRun.READY -> run.pageShowsSubmitted()
                        ? name + "：" + SubmitWords.SUBMITTED_NOTICE + "确认一下：在 Chrome 里看投递状态，"
                                + "投了就告诉我；没投的话在申请工作台里说。"
                        : name + " 已经填好，停在提交前。请在 Chrome 里检查并提交，提交后告诉我；"
                                + "要改的话在申请工作台里说。";
                case AgentRun.WAITING_USER -> name + " 在等你："
                        + shorten(run.message() == null ? "需要你回复" : run.message(), 200) + " 去申请工作台回复。";
                case AgentRun.FAILED -> name + " 出错停下了：" + shorten(run.error(), 200)
                        + " 可以在申请工作台里回复，让它再试。";
                default -> null;
            };
            if (text != null) {
                reporter.report(run.project(), run.session(), run.job(), text);
            }
        } catch (RuntimeException e) {
            log.warn("Job {} 没有回报给 Session {}：{}", run.job(), run.session(), rootMessage(e));
        }
    }

    private static String shorten(String text, int max) {
        String flat = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        return flat.length() > max ? flat.substring(0, max) + "…" : flat;
    }

    private AgentRun execute(AgentRun run) {
        // 开始和回复都是用户的动作：浏览器断着的话再留一次重连，由第一次用浏览器时的 ensureConnected 去连；
        // 连不上时 HarnessLoop 照常停下等你（C006-fix-browser 第 1 节，不再在这里单独重连）
        browser.retryOnNextUse();
        String jobId = run.job();
        // 每次运行再取模型（C006-fix-2 第 6 条）：改了模型设置，下一次运行就用新的
        HarnessLoop loop = new HarnessLoop(mainModel.get(), browser, layout, clock, limits);
        return loop.run(run, () -> stopRequested(jobId), runs::save, events.observer(run.session(), jobId));
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

    /**
     * 每个上传位置选了什么（C006-fix 第 5 节）：资料库原件的键，或者 {@code attachment:<附件编号>}（这个申请的附件）；
     * 空字符串是没选。只校验选的东西存在，不校验必填的位置填了没有：那是模型在页面上看到的事，由它判断、缺了再问。
     * 没给 {@code files} 时为空；给了时每个位置一项（顺序同 {@code fileSlots}）。
     */
    private List<Choice> files(AgentRun run, List<String> given, AttachmentStore attached) {
        if (given == null) {
            return List.of();
        }
        List<AgentRun.FileSlot> slots = run.fileSlots();
        if (given.size() != slots.size()) {
            throw new ApplicationException.BadRequest("有 " + slots.size() + " 个上传位置，选了 " + given.size() + " 个文件");
        }
        List<Choice> chosen = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            String key = given.get(i) == null ? "" : given.get(i).strip();
            AgentRun.FileSlot slot = slots.get(i);
            if (key.isEmpty()) {
                chosen.add(new Choice(slot, null, null));
            } else if (key.startsWith(ATTACHMENT)) {
                String id = key.substring(ATTACHMENT.length());
                chosen.add(new Choice(slot, null, attached.find(id)
                        .orElseThrow(() -> new ApplicationException.BadRequest("没有这个附件：" + id))));
            } else if (assets.find(key).isEmpty()) {
                throw new ApplicationException.BadRequest("原件不存在：" + key);
            } else {
                chosen.add(new Choice(slot, key, null));
            }
        }
        return chosen;
    }

    /**
     * 按位置的顺序把选的文件排上编号，接着之前的往后排。原件写进 {@code job.md} 的 {@code materials}（连同之前选的原件）、
     * 复制到上传区；附件不进资料库，直接从附件目录上传（{@link AgentRun.StagedFile#attachment()}）。返回全部文件。
     */
    private List<AgentRun.StagedFile> stage(AgentRun run, List<Choice> chosen) {
        List<AgentRun.StagedFile> files = new ArrayList<>(run.files());
        List<JobMaterials.Selection> originals = chosen.stream().filter(choice -> choice.asset() != null)
                .map(choice -> new JobMaterials.Selection(choice.slot().label(), choice.asset())).toList();
        Iterator<JobMaterials.Material> written = Collections.emptyIterator();
        if (!originals.isEmpty()) {
            List<JobMaterials.Selection> all = new ArrayList<>();
            run.files().stream().filter(file -> file.attachment() == null)
                    .forEach(file -> all.add(new JobMaterials.Selection(file.slot(), file.assetKey())));
            all.addAll(originals);
            List<JobMaterials.Material> materialList = materials.write(run.project(), run.job(), all);
            written = materialList.subList(materialList.size() - originals.size(), materialList.size()).iterator();
        }
        for (Choice choice : chosen) {
            if (choice.asset() != null) {
                JobMaterials.Material material = written.next();
                Path copy = uploads.stage(run.job(), material.asset());
                try {
                    files.add(new AgentRun.StagedFile(material.asset(), material.uploadName(), material.slot(),
                            Files.size(copy), material.sha256()));
                } catch (IOException e) {
                    throw new UncheckedIOException("读不到上传区的副本：" + copy, e);
                }
            } else if (choice.attachment() != null) {
                AttachmentStore.Stored stored = choice.attachment();
                files.add(new AgentRun.StagedFile(null, stored.name(), choice.slot().label(), stored.size(),
                        stored.sha256(), stored.id()));
            }
        }
        return files;
    }

    /** 一个上传位置选了什么：原件的键、这个申请的附件，或者都没有（没选）。 */
    private record Choice(AgentRun.FileSlot slot, String asset, AttachmentStore.Stored attachment) {

        boolean chosen() {
            return asset != null || attachment != null;
        }
    }

    private void requireSession(String projectId, String sessionId) {
        if (!projects.exists(projectId)) {
            throw new ApplicationException.NotFound("Project 不存在：" + projectId);
        }
        if (sessionId == null || !sessions.exists(projectId, sessionId)) {
            throw new ApplicationException.NotFound("Session 不存在：" + projectId + "/" + sessionId);
        }
    }

    /** 交给了执行器的 Job 现在是「正在运行」还是「在排队」（409 的文案用）。 */
    private String busy(String jobId) {
        return jobId.equals(runningJob) ? "正在运行" : "正在排队";
    }

    private AgentRun find(String jobId) {
        return runs.find(jobId).orElseThrow(() -> new ApplicationException.NotFound("Job " + jobId + " 没有申请线程"));
    }

    /**
     * 推进 {@code prep_state}（经过台账，事件跟着更新）。已经是目标状态时什么都不做。
     * 用户说的事实优先（C006-fix 第 3 节）：用户在 Agent 跑的时候已经定了状态（已提交、先放着、结束了），这里不再改；
     * 只有用户自己（回复、重新开始）能把「先放着」的接着做。
     */
    private void moveTo(AgentRun run, PrepState target, Actor actor, String reason) {
        ledger.advancePrep(run.project(), run.job(), run.session(), actor, target, reason);
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
