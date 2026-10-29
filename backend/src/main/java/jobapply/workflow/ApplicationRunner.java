package jobapply.workflow;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jobapply.config.HarnessProperties;
import jobapply.domain.Actor;
import jobapply.domain.AgentRun;
import jobapply.domain.ApplicationState;
import jobapply.domain.Ids;
import jobapply.domain.Inbox;
import jobapply.domain.Job;
import jobapply.domain.LeadStatus;
import jobapply.domain.LiveRuns;
import jobapply.domain.PrepState;
import jobapply.domain.Transition;
import jobapply.harness.policies.BrowserToolPolicy;
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
import jobapply.persistence.Trash;
import jobapply.persistence.UploadArea;
import jobapply.persistence.WorkerSettingsFile;
import jobapply.tools.BrowserSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 申请线程的入口（agent-runtime.md 7.1）：开始、回复、停止、查状态、读报告。流程由 {@link HarnessLoop} 里的模型决定；
 * 这里负责调度（一个 Worker 池）、存取 {@link AgentRun}、把用户的回复变成下一条消息、准备上传的文件、更新台账的
 * {@code prep_state}，以及模型结束时把报告定稿。
 * <p>
 * 同时最多 {@code workerLimit} 个申请线程在跑（C011，默认 3），同一个网站上最多 {@code perSiteLimit} 个（礼貌限速，
 * 默认 2）；两个上限启动时取 {@code settings/workers.yaml}，没有时取 {@link HarnessProperties}，运行中可以在设置页改
 * （C012，{@link #setConcurrency}）；多出来的开始和回复排队（{@code queued}），
 * 按顺序轮到，只有网站已满时排在后面的别的网站可以先走（C006-fix-2 第 1 条，langgraph 的 {@code enqueue}）。同一个 Job
 * 同时只有一个申请线程。每个 Job 用自己的标签页，在等用户回复的不挡别人；浏览器动作之间的互斥由 {@link BrowserSession}
 * 自己负责。「在跑 / 排队」只看内存，文件写着在跑、内存里没有的读出来是中断（{@link LiveRuns}）。所有校验都在第一次写文件
 * 之前完成，被拒绝的请求不写任何东西。
 * <p>
 * 锁（C011 第 3 节）：实例锁只保护运行表（{@code queued} / {@code running}）、排队和结束时的存盘；每一步的停止检查用
 * 并发集合，不拿这把锁；{@code status} 只在锁里读运行记录和运行表，台账、报告、附件这些文件在锁外读。
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
    /** 运行中插话在对话里的开头（C010；申请 Agent 的提示词里说插话优先）。 */
    static final String INSERTED = "用户插话：";
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
    private final Trash trash;
    /** 用户丢弃/删除工作台的 Job：不再写入运行记录或更新台账。 */
    private final Set<String> discarded = ConcurrentHashMap.newKeySet();
    /** Worker 线程：按需建、空闲回收；同时跑几个只由 {@link #dispatch()} 按 {@code workerLimit} 管（C012 可以运行中改）。 */
    private final ExecutorService workers;
    private final WorkerSettingsFile workerSettings;
    /** 工具调用上限（C018，设置页可改，和 {@link SessionRunner} 共用一份）。 */
    private final ToolCallLimits toolCalls;
    /** 同时最多几个申请在跑、同一个网站最多几个。只在锁里读写。 */
    private int workerLimit;
    private int perSiteLimit;
    /** 用户要求停下的 Job：每一步都查，用并发集合，不拿实例锁（C011 第 3 节）。 */
    private final Set<String> stopRequested = ConcurrentHashMap.newKeySet();
    private final RuntimeEvents events;
    /** 运行中（或排队时）用户插的话（C010）：循环下一次调模型之前取走；跑完还剩的写进对话并接着跑。 */
    private final Inbox inbox = new Inbox();

    /** 还没跑完的 Job（排队的和正在跑的）。 */
    private final LiveRuns live = new LiveRuns();
    /** 在跑或排队时用户定下结束了的 Job（{@link #closeTabs}）：停下时关掉它的页。拿实例锁读写。 */
    private final Set<String> releaseWhenDone = new java.util.HashSet<>();
    /**
     * 排着队的 Job → 轮到时要跑的那一次，按排队的顺序。还没交给 Worker：排队时停止直接移出，不会有已经被取走、在等锁的
     * 任务（所以 C006-fix-2 最终审查第 3 条的排队凭证不再需要）。只在锁里改。
     */
    private final Map<String, AgentRun> queued = new LinkedHashMap<>();
    /** 正在跑的 Job → 它的网站和任务。只在锁里改。 */
    private final Map<String, Worker> running = new LinkedHashMap<>();
    private volatile Reporter reporter = (project, session, job, text) -> {
    };

    /**
     * 申请线程一次运行结束时回报给发起它的 Session（C006-fix 第 2 节，Deep Agents 的 {@code notify_parent}）。
     * 由 {@link SessionRunner} 实现：记进 transcript，不唤醒模型。
     */
    public interface Reporter {
        void report(String projectId, String sessionId, String jobId, String text);
    }

    /** 一个正在跑的申请：网站（同站点限速用，认不出时为 null）和交给 Worker 的任务。 */
    private record Worker(String site, Future<?> task) {
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
        this.trash = new Trash(layout, clock);
        AtomicInteger number = new AtomicInteger();
        this.workerSettings = new WorkerSettingsFile(layout);
        this.toolCalls = new ToolCallLimits(layout, this.limits);
        WorkerSettingsFile.Limits initial = initialLimits(workerSettings, this.limits);
        this.workerLimit = initial.workers();
        this.perSiteLimit = initial.perSite();
        this.workers = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "application-worker-" + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
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
        discarded.remove(jobId);
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

        // C008-fix / C021：已选定简历时作为文件 1 直接打开页面填表；尚未选定简历时先在工作台内做简历匹配与修改建议
        String resume = job.extra().get("resume_choice") instanceof Map<?, ?> choice
                && choice.get("asset") instanceof String key && assets.find(key).isPresent() ? key : null;
        boolean skipped = job.extra().get("resume_choice") instanceof Map<?, ?> choice
                && choice.get("skipped") != null;
        String opening;
        if (resume != null) {
            opening = "开始申请：" + job.posting().company() + " · " + job.posting().title()
                    + "。先看页面：判断要不要登录、怎么进入申请表，然后按工作方法填写，停在最终提交之前。"
                    + "\n简历：已选定用 " + assets.find(resume).get().fileName() + "（" + resume
                    + "），已经是文件 1，到简历上传位置直接 browser_file_upload 文件 1，不用再问。填项目、实习、教育和描述"
                    + "之前先 read_resume 读文件 1 的原文。";
        } else if (skipped) {
            opening = "开始申请：" + job.posting().company() + " · " + job.posting().title()
                    + "。先看页面：判断要不要登录、怎么进入申请表，然后按工作方法填写，停在最终提交之前。";
        } else {
            opening = "开始申请：" + job.posting().company() + " · " + job.posting().title()
                    + "。第一步先在工作台里帮用户匹配简历：调用 read_jd 读岗位要求、list_resumes 和 read_resume 读全部简历原件，"
                    + "在回复里给出一张简历匹配度排名表（名次 | 简历 | 匹配理由 | 缺口）和针对本岗位的简历修改建议，"
                    + "然后用 ask_user 请用户确认用哪一份（用户也可以直接上传改好的简历，或说不用排直接投）。"
                    + "用户确认后调用 choose_resume 选定简历，再打开岗位页面填写申请表，停在最终提交前。";
        }
        AgentRun run = AgentRun.start(projectId, jobId, sessionId, job.posting().company(), job.posting().title(), url,
                opening);
        uploads.reset(jobId);
        if (resume != null) {
            run = run.withFiles(stage(run, List.of(new Choice(
                    new AgentRun.FileSlot("resume", "简历", null, true, null), resume, null))));
        }
        moveTo(run, PrepState.PREPARING, Actor.USER, "开始申请线程");
        runs.save(run);
        submit(run);
        return status(jobId);
    }

    /**
     * 回复：回答问题、选文件、告诉模型做好了，或者在提交前要求修改。不在跑的线程都可以回复；
     * 在跑或排队时是插话（C010）：只收一句话，Agent 下一步看到。
     * 内容不对时拒绝（{@link ApplicationException.BadRequest}），不写任何东西。
     */
    public synchronized ApplicationStatus reply(String jobId, Reply reply) {
        AgentRun run = find(jobId);
        if (live.contains(jobId)) {
            return insert(run, reply);
        }
        // 用户已经定下了（已提交、已投递、结束了）：不再让申请 Agent 接着做（C006-fix-2 回归第 1 条）。先放着的可以回复
        Job job = jobs.load(run.project(), jobId);
        if (JobLedger.userDecided(job.status(), Actor.USER)) {
            throw new ApplicationException.Conflict("Job " + jobId + " 现在是 " + job.status().label()
                    + "，你已经定下了，申请 Agent 不再接着做；投递后的进展在这个工作台的后续段里告诉 Agent");
        }
        requireKnownSession(run.project(), reply.session());
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
            // 申请状态里等这个回答的字段回到待填，来源写答案编号（C019）
            ApplicationState state = run.state();
            for (JobAnswers.Answer answer : saved) {
                state = state.answered(answer.question(), answer.id(), answer.value());
            }
            run = run.withState(state);
        }
        // 停在提交前时上传区清空了（settle）；接着做时把已经排上编号的原件放回去，文件编号不变
        run.files().stream().filter(file -> file.assetKey() != null)
                .forEach(file -> uploads.stage(jobId, file.assetKey()));
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

    /**
     * 运行中插话（C010）：放进 {@link #inbox}，循环下一次调模型之前取走，不打断正在执行的工具。回答问题、选文件、
     * 附件要排文件编号、改上传区，运行中不收，等它停下来再发。
     */
    private ApplicationStatus insert(AgentRun run, Reply reply) {
        requireKnownSession(run.project(), reply.session());
        String text = reply.text() == null ? "" : reply.text().strip();
        boolean more = reply.answers() != null && !reply.answers().isEmpty()
                || reply.files() != null && reply.files().stream().anyMatch(file -> file != null && !file.isBlank())
                || reply.attachments() != null && !reply.attachments().isEmpty();
        if (more) {
            throw new ApplicationException.Conflict("Agent 正在做，运行中只能插一句话；回答问题、选文件、附件等它停下来再发");
        }
        if (text.isEmpty()) {
            throw new ApplicationException.BadRequest("插话不能为空");
        }
        if (text.length() > MAX_TEXT) {
            throw new ApplicationException.BadRequest("回复不能超过 " + MAX_TEXT + " 个字符");
        }
        inbox.put(run.job(), AgentRun.Turn.user(INSERTED + text));
        events.changed(run.session(), run.job());
        return status(run.job());
    }

    /**
     * 跑完（或排队时停下）还有没被看到的插话：合成一条用户消息接着跑，不丢（C010）。停在提交前时算一轮修改。
     * 用户已经定下了（已提交、结束了）时不再跑，只记日志。调用方持有锁；接不上时记日志，返回 false。
     */
    private boolean resumeWithInserted(AgentRun run) {
        List<AgentRun.Turn> said = inbox.take(run.job());
        if (said.isEmpty()) {
            return false;
        }
        try {
            if (JobLedger.userDecided(jobs.load(run.project(), run.job()).status(), Actor.USER)) {
                log.warn("Job {} 已经由用户定下，运行中插的 {} 句话没有再交给申请 Agent", run.job(), said.size());
                return false;
            }
            String text = said.stream().map(AgentRun.Turn::text).collect(Collectors.joining("\n"));
            int round = run.round();
            if (AgentRun.READY.equals(run.status())) {
                round++;
                text = "用户在提交前审阅后要求修改（第 " + round + " 轮）。" + text;
                runLog.append(run.project(), run.job(), RunLog.Entry.requestChanges(run.session(), step(run), text));
            } else {
                runLog.append(run.project(), run.job(), RunLog.Entry.userSignal(run.session(), step(run), "reply"));
            }
            // 停在提交前时上传区清空了（settle）；接着做时把已经排上编号的原件放回去，文件编号不变
            run.files().stream().filter(file -> file.assetKey() != null)
                    .forEach(file -> uploads.stage(run.job(), file.assetKey()));
            AgentRun resumed = run.resumed(AgentRun.Turn.user(text), new ArrayList<>(run.files()), round);
            moveTo(resumed, PrepState.PREPARING, Actor.USER, "用户插话，继续申请");
            runs.save(resumed);
            submit(resumed);
            return true;
        } catch (RuntimeException e) {
            log.warn("Job {} 没有接着处理运行中插的话：{}", run.job(), rootMessage(e));
            return false;
        }
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
        if (queued.remove(jobId) != null) {
            // 排着队：立刻移出队列，记成等你（C006-fix-2 回归第 8 条）。用户按的停止，不回报。还没交给 Worker，不会再跑
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
            if (!resumeWithInserted(result)) {
                events.finished(result.session(), jobId, result.status());
            }
            notifyAll();
            return status(jobId);
        }
        // 在跑：当前这一次工具调用之后停下
        stopRequested.add(jobId);
        return status(jobId);
    }

    /**
     * 用户定下这个申请结束了（已投递、不投了、岗位下线、Job 被删）：关掉它在浏览器里开的所有页、关会话（C011-fix
     * 2026-10-24）。用户转交的页不关。还在跑或排队时先记下，它停下时（{@link #work}）再关。
     */
    public void closeTabs(String jobId) {
        synchronized (this) {
            if (live.contains(jobId)) {
                releaseWhenDone.add(jobId);
                return;
            }
        }
        browser.release(jobId);
    }

    public record Discarded(String id, String mode) {
    }

    /**
     * 丢弃工作台 / 删除 Job：强制停止、删掉 memory（运行记录、草稿报告、答案、上传文件），
     * deleteJob 为 true 时再挪进回收站并重建台账。
     */
    public synchronized Discarded discard(String jobId, boolean deleteJob) {
        String project = findProject(jobId)
                .orElseThrow(() -> new ApplicationException.NotFound("Job 不存在：" + jobId));

        discarded.add(jobId);

        // 1. 强制停止（出队 + 标记停止，非阻塞，不要 awaitIdle 轮询）
        queued.remove(jobId);
        stopRequested.add(jobId);
        live.remove(jobId);
        releaseWhenDone.remove(jobId);
        dispatch();
        notifyAll();
        browser.release(jobId);

        // 2. 删掉这个 Job 的申请 memory
        runs.delete(jobId);
        reports.delete(project, jobId);
        answers.clear(project, jobId);
        deleteRecursively(layout.uploadsDir(jobId));

        // 3. deleteJob: true 时挪进回收站并重建台账
        String mode;
        if (deleteJob) {
            trash.moveJob(project, jobId);
            mode = "job";
        } else {
            mode = "thread";
        }

        events.changed(null, jobId);
        return new Discarded(jobId, mode);
    }

    private Optional<String> findProject(String jobId) {
        if (!Ids.isJobId(jobId)) {
            return Optional.empty();
        }
        for (String projectId : layout.projectIds()) {
            if (jobs.exists(projectId, jobId)) {
                return Optional.of(projectId);
            }
        }
        return runs.find(jobId).map(AgentRun::project);
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("删除目录失败：" + dir, e);
        }
    }

    /** 这个 Job 有申请线程时返回它的状态；没有时为空（界面上的 Job 列表用）。 */
    public Optional<ApplicationStatus> statusIfAny(String jobId) {
        return runs.find(jobId).isPresent() ? Optional.of(status(jobId)) : Optional.empty();
    }

    /** 申请线程的对话（工作台显示每一步）；没有申请线程时为空。 */
    public Optional<AgentRun> run(String jobId) {
        return runs.find(jobId);
    }

    public ApplicationStatus status(String jobId) {
        // 运行记录和运行表一起在锁里读（结束时的存盘和移出运行表也在锁里，读不到「文件写着在跑、内存里已经没了」的中间态）；
        // 别的文件在锁外读（C011 第 3 节）
        AgentRun run;
        boolean isRunning;
        boolean isQueued;
        boolean isLive;
        synchronized (this) {
            run = find(jobId);
            isRunning = running.containsKey(jobId);
            isQueued = queued.containsKey(jobId);
            isLive = live.contains(jobId);
        }
        // 在跑、排队只看内存；文件写着在跑、内存里没有的是中断（C006-fix-2 第 3 条）
        boolean interrupted = AgentRun.RUNNING.equals(run.status()) && !isLive;
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
        String status = isRunning ? AgentRun.RUNNING
                : isQueued ? AgentRun.QUEUED
                : interrupted && !decided ? AgentRun.INTERRUPTED
                : decided && !isLive ? AgentRun.DONE : run.status();
        interrupted = AgentRun.INTERRUPTED.equals(status);
        // 浏览器断开从全局的连接状态推出来（C006-fix-browser 第 1 节）：在等你、中断了，而浏览器现在断着
        boolean disconnected = browser.state().disconnected()
                && (AgentRun.WAITING_USER.equals(status) || interrupted);
        String report = reports.isDraft(run.project(), jobId).map(draft -> draft ? "draft" : "final").orElse(null);
        return new ApplicationStatus(jobId, run.project(), run.session(), status,
                AgentRun.RUNNING.equals(status) ? null : run.message(), run.questions(), run.fileSlots(),
                run.fileSlots().isEmpty() ? List.of() : assets.list(), storedAttachments(jobId), disconnected,
                run.state(), run.usage(), prepState, report, run.round(), interrupted ? INTERRUPTED_ERROR : run.error(),
                inbox.peek(jobId).stream().map(turn -> turn.text().substring(INSERTED.length())).toList());
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
    public Optional<String> report(String jobId) {
        return reports.read(find(jobId).project(), jobId);
    }

    /** 等所有申请线程（在跑的和排队的）都结束（手动试跑、测试用）。超时返回 false。 */
    public synchronized boolean awaitIdle(Duration timeout) {
        return awaitUntil(() -> running.isEmpty() && queued.isEmpty(), timeout);
    }

    /** 等这个 Job 的申请线程结束（不在跑也不在排队）。超时返回 false。 */
    public synchronized boolean awaitIdle(String jobId, Duration timeout) {
        return awaitUntil(() -> !live.contains(jobId), timeout);
    }

    /** 调用方持有锁；运行结束、排队时停下都会 {@code notifyAll}。 */
    private boolean awaitUntil(BooleanSupplier done, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!done.getAsBoolean()) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return false;
            }
            try {
                wait(Math.max(1, left / 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }

    // ---- 并发数（C012） ----

    /** Worker 数的上限（设置页可选 1 到这个数）。 */
    public static final int MAX_WORKERS = 6;

    /** 当前的两个上限，和此刻在跑、排队的个数。 */
    public record Concurrency(int workers, int perSite, int maxWorkers, int running, int queued) {
    }

    /** 正在跑和排队的 Job（换数据位置时要先等它们结束，C017）。 */
    public synchronized List<String> activeJobs() {
        List<String> ids = new java.util.ArrayList<>(running.keySet());
        ids.addAll(queued.keySet());
        return ids;
    }

    public synchronized Concurrency concurrency() {
        return new Concurrency(workerLimit, perSiteLimit, MAX_WORKERS, running.size(), queued.size());
    }

    /**
     * 改两个上限并存进 {@code settings/workers.yaml}，马上生效：调大时排队的按新上限开始；调小时在跑的不停，只是不再放新的，
     * 等在跑的少于新上限再放。超出范围抛 {@link IllegalArgumentException}（接口返回 400），不写文件。
     */
    public synchronized Concurrency setConcurrency(int workers, int perSite) {
        if (workers < 1 || workers > MAX_WORKERS) {
            throw new IllegalArgumentException("同时申请数要在 1 到 " + MAX_WORKERS + " 之间");
        }
        if (perSite < 1 || perSite > workers) {
            throw new IllegalArgumentException("同一个网站的上限要在 1 到同时申请数（" + workers + "）之间");
        }
        workerSettings.write(new WorkerSettingsFile.Limits(workers, perSite));
        workerLimit = workers;
        perSiteLimit = perSite;
        dispatch();
        return concurrency();
    }

    /** 工具调用上限（C018）：申请 Agent 每次运行、对话 Agent 每条消息，设置页改了马上生效。 */
    public ToolCallLimits toolCallLimits() {
        return toolCalls;
    }

    /** 有 {@code settings/workers.yaml} 用它，没有或读不了时用配置（读不了时日志警告，界面上看到的是生效的值）。 */
    private static WorkerSettingsFile.Limits initialLimits(WorkerSettingsFile file, HarnessProperties limits) {
        WorkerSettingsFile.Limits fallback =
                new WorkerSettingsFile.Limits(limits.applicationWorkers(), limits.applicationsPerSite());
        try {
            WorkerSettingsFile.Limits saved = file.read().orElse(fallback);
            if (saved.workers() < 1 || saved.perSite() < 1) {
                log.warn("{} 里的并发数必须是正数，改用配置的值", file.path());
                return fallback;
            }
            return saved;
        } catch (RuntimeException e) {
            log.warn("{} 读不了（{}），改用配置的值", file.path(), e.getMessage());
            return fallback;
        }
    }

    // ---- 运行 ----

    /**
     * 排进队列，再让 {@link #dispatch()} 看能不能马上开始：Worker 和这个网站都有空位时直接算在跑，否则排在最后
     * （{@code queued}），轮到时再发 {@code running}。调用方持有锁。
     */
    private void submit(AgentRun run) {
        String jobId = run.job();
        live.add(jobId);
        stopRequested.remove(jobId);
        queued.put(jobId, run);
        dispatch();
        if (queued.containsKey(jobId)) {
            events.started(run.session(), jobId, AgentRun.QUEUED);
        }
    }

    /**
     * 按排队的顺序把能开始的交给 Worker：在跑的少于 Worker 数，并且同一个网站在跑的少于每站上限。网站满了的跳过，
     * 后面别的网站的可以先走。调用方持有锁；排队、运行结束时调用。
     */
    private void dispatch() {
        Iterator<AgentRun> waiting = queued.values().iterator();
        while (running.size() < workerLimit && waiting.hasNext()) {
            AgentRun run = waiting.next();
            String site = BrowserToolPolicy.siteOf(run.url());
            if (site != null && running.values().stream().filter(worker -> site.equals(worker.site())).count()
                    >= perSiteLimit) {
                continue;
            }
            waiting.remove();
            events.started(run.session(), run.job(), AgentRun.RUNNING);
            // 任务结束时要拿这把锁才移出运行表，所以这里先放进去的一定在它移出之前
            running.put(run.job(), new Worker(site, workers.submit(() -> work(run))));
        }
    }

    /** 一个 Worker 跑一次申请：执行、更新台账、存盘，空出位置后放行排队的下一个。 */
    private void work(AgentRun run) {
        String jobId = run.job();
        if (discarded.contains(jobId)) {
            synchronized (this) {
                running.remove(jobId);
                live.remove(jobId);
                stopRequested.remove(jobId);
                releaseWhenDone.remove(jobId);
                dispatch();
                notifyAll();
            }
            return;
        }
        AgentRun result;
        try {
            result = execute(run);
        } catch (RuntimeException e) {
            String message = crashMessage(e);
            log.warn("Job {} 的申请线程出错：{}", jobId, message, e);
            result = runs.find(jobId).orElse(run).failed(message);
        }
        if (discarded.contains(jobId)) {
            synchronized (this) {
                running.remove(jobId);
                live.remove(jobId);
                stopRequested.remove(jobId);
                releaseWhenDone.remove(jobId);
                dispatch();
                notifyAll();
            }
            return;
        }
        try {
            settle(result);
        } catch (RuntimeException e) {
            log.warn("Job {} 结束时更新台账出错：{}", jobId, rootMessage(e));
            result = result.failed(rootMessage(e));
        }
        // 收拾浏览器（C011-fix 2026-10-24）：停在提交前只留填好的那页，等你、出错时页都留着；都关掉焦点模拟和 MCP 会话
        browser.leave(jobId, AgentRun.READY.equals(result.status()) ? BrowserSession.Leave.KEEP_CURRENT
                : BrowserSession.Leave.KEEP_ALL);
        // 用户按停止停下的：用户自己知道，不回报（C006-fix-2 回归第 6 条）
        boolean stoppedByUser;
        boolean again;
        boolean release;
        synchronized (this) {
            stoppedByUser = stopRequested.contains(jobId) && AgentRun.WAITING_USER.equals(result.status());
            try {
                runs.save(result);
            } finally {
                running.remove(jobId);
                live.remove(jobId);
                stopRequested.remove(jobId);
            }
            // 用户在工作台里说了「投了」（mark_submitted，C022）：这一页也不用留了
            release = releaseWhenDone.remove(jobId) | appliedNow(result);
            // 跑完时还没被看到的插话（最后一步正好到的、或者「打断并发送」）：接着跑，不回报（C010）
            again = !release && resumeWithInserted(result);
            dispatch();
            notifyAll();
        }
        if (release) {
            browser.release(jobId);
        }
        if (again) {
            return;
        }
        events.finished(result.session(), jobId, result.status());
        if (!stoppedByUser) {
            reportToSession(result);
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
                                + "投了就在申请工作台里点「我已经提交了」或者说一声；没投的话也在工作台里说。"
                        : name + " 已经填好，停在提交前。请在 Chrome 里检查并提交，提交后在申请工作台里点「我已经提交了」"
                                + "或者说一声；要改的话在工作台里说。";
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

    /** 这次运行里已经记为已投递了（申请 Agent 的 mark_submitted）。读不出时当作没有。 */
    private boolean appliedNow(AgentRun run) {
        try {
            return JobLedger.alreadyApplied(jobs.load(run.project(), run.job()).status());
        } catch (RuntimeException e) {
            return false;
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
        HarnessLoop loop = new HarnessLoop(mainModel.get(), browser, layout, clock, limits, toolCalls::perRun);
        // 每轮存盘后发一次 changed：工作台侧栏的「进度」跟着申请状态刷新（C019）
        return loop.run(run, () -> stopRequested(jobId), progressed -> {
            if (!discarded.contains(jobId)) {
                runs.save(progressed);
                events.changed(progressed.session(), progressed.job());
            }
        }, events.observer(run.session(), jobId), () -> inbox.take(jobId));
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
            case AgentRun.FAILED -> moveTo(run, PrepState.FAILED, Actor.SYSTEM, "申请线程出错：" + shorten(run.error(), 300));
            default -> {
            }
        }
    }

    private boolean stopRequested(String jobId) {
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

    /** 回复已有的申请线程：发起它的 Session 删了也行（C013，申请属于 Job，Session 只是记录里的历史）。 */
    private void requireKnownSession(String projectId, String sessionId) {
        if (!projects.exists(projectId)) {
            throw new ApplicationException.NotFound("Project 不存在：" + projectId);
        }
        if (!sessions.existsOrDeleted(projectId, sessionId)) {
            throw new ApplicationException.NotFound("Session 不存在：" + projectId + "/" + sessionId);
        }
    }

    /**
     * 这些 Job 都没有在跑、也没有在排队时，在锁里执行 {@code action}（删除 Project 用，C013）：拿着锁，
     * 中间不会有新的申请开始。有在跑的时 409，说是哪几个。
     */
    public synchronized <T> T whileIdle(List<String> jobIds, Supplier<T> action) {
        List<String> busy = jobIds.stream().filter(live::contains).toList();
        if (!busy.isEmpty()) {
            throw new ApplicationException.Conflict("还有申请在运行或排队：" + String.join("、", busy)
                    + "。先在申请工作台里停止，再删除");
        }
        return action.get();
    }

    /** 还没跑完的 Job 现在是「正在运行」还是「在排队」（409 的文案用）。调用方持有锁。 */
    private String busy(String jobId) {
        return running.containsKey(jobId) ? "正在运行" : "正在排队";
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

    /**
     * 申请线程意外出错时记进状态的说明：根因的消息；根因是 JDK 的异常（NullPointerException、IndexOutOfBounds 这类程序错误）
     * 时再带上出错位置（本项目代码里最近的一帧），只有类名时也能查到是哪一行。
     */
    private static String crashMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = rootMessage(root);
        if (!root.getClass().getName().startsWith("java.")) {
            return message;
        }
        StackTraceElement[] trace = root.getStackTrace();
        StackTraceElement at = Arrays.stream(trace)
                .filter(frame -> frame.getClassName().startsWith("jobapply.")).findFirst()
                .orElse(trace.length == 0 ? null : trace[0]);
        String where = at == null ? "" : "（位置：" + at.getClassName().substring(at.getClassName().lastIndexOf('.') + 1)
                + "." + at.getMethodName() + ":" + at.getLineNumber() + "）";
        String type = root.getClass().getSimpleName();
        return (message.equals(type) ? type : type + "：" + message) + where;
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
