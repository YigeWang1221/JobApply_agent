package jobapply.tools;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import jobapply.domain.AgentRun;
import jobapply.domain.ApplicationState;
import jobapply.domain.Fact;
import jobapply.domain.FactClass;
import jobapply.domain.Language;
import jobapply.domain.Placeholder;
import jobapply.harness.context.FillContext;
import jobapply.harness.context.SkillCatalog;
import jobapply.memory.ProfileResolver;
import jobapply.memory.ResolvedFact;
import jobapply.persistence.AssetRegistry;
import jobapply.persistence.FillReportWriter;
import jobapply.persistence.JobAnswers;
import jobapply.persistence.JobLedger;
import jobapply.persistence.JobRepository;
import jobapply.persistence.AttachmentStore;
import jobapply.persistence.JobThreadStore;
import jobapply.persistence.LedgerFile;
import jobapply.persistence.PersonalEnv;
import jobapply.persistence.RunLog;
import jobapply.persistence.StorageLayout;
import jobapply.persistence.UploadArea;

/**
 * 申请 Mode 一次运行的工作台状态（原来在 {@code HarnessLoop.Round} 里），工具和插件共用（放在 {@link RunContext} 里）：
 * 这次运行开始时的 {@link AgentRun}、可上传的文件、最新的页面、申请状态（C019）、报告草稿里字段清单以外的部分、用量，
 * 以及每次运行读一次的资料（含 C 级占位符的键名）和 skill 目录。
 * <p>
 * C 级字段的真实值不在这里：资料里只有占位符 {@code {KEY}}，真实值只在浏览器工具发给 MCP 的那一刻从 personal.env 取
 * （{@link BrowserFillTools}）。
 */
public final class Workbench {

    /** {@code finish} 时系统按申请状态补进「未解决」的行的开头（每次定稿前先去掉上一次补的）。 */
    public static final String FROM_STATE = "申请状态：";

    private final StorageLayout layout;
    private final java.time.Clock clock;
    private final AgentRun run;
    private final List<AgentRun.StagedFile> files;
    private final Draft draft = new Draft();
    private final Consumer<AgentRun> progress;
    private final FillReportWriter reports;
    private final RunLog runLog;
    /** 新开始的申请（对话里只有开场那一条）：不接上一次的报告。 */
    private final boolean fresh;
    private ApplicationState state;
    private long modelCalls;
    private long tokens;
    private PageSnapshot latest;
    private List<JobAnswers.Answer> answers = List.of();
    private Map<String, ResolvedFact> profileZh = Map.of();
    private Map<String, ResolvedFact> profileEn = Map.of();
    /** 资料里登记过的 C 级占位符的键名（C014-D）。 */
    private Set<String> placeholders = Set.of();
    private SkillCatalog catalog;

    /**
     * 报告草稿里不属于字段清单的部分：附件、未解决、守卫记录。字段行从申请状态生成（C019），不在这里累积。
     */
    public static final class Draft {

        private String company;
        private String title;
        private int round = 1;
        private final Map<String, FillReportWriter.Attachment> attachments = new LinkedHashMap<>();
        private final Set<String> unresolved = new LinkedHashSet<>();
        private final Set<FillReportWriter.GuardNote> guardNotes = new LinkedHashSet<>();

        public void attach(FillReportWriter.Attachment attachment) {
            attachments.put(attachment.fileName(), attachment);
        }

        public Set<String> unresolved() {
            return unresolved;
        }

        public void guard(FillReportWriter.GuardNote note) {
            guardNotes.add(note);
        }

        /** 从读回的报告接着累积附件、未解决和守卫记录。 */
        void restore(FillReportWriter.Report report) {
            report.attachments().forEach(this::attach);
            unresolved.addAll(report.unresolved());
            guardNotes.addAll(report.guardNotes());
        }

        FillReportWriter.Report report(List<FillReportWriter.Field> fields) {
            return new FillReportWriter.Report(company, title, round, fields,
                    List.copyOf(attachments.values()), List.copyOf(unresolved), List.copyOf(guardNotes));
        }
    }

    /**
     * 每次运行都从文件读回附件、未解决和守卫记录：应用重启后前面的还在。旧运行记录里还没有字段清单时，把报告里的字段行
     * 放进申请状态（C019 之前的申请照样接着做）。
     *
     * @param progress 每轮之后交给调用方存盘
     */
    public Workbench(StorageLayout layout, java.time.Clock clock, AgentRun run, Consumer<AgentRun> progress) {
        this.layout = layout;
        this.clock = clock;
        this.run = run;
        this.files = new ArrayList<>(run.files());
        this.progress = progress == null ? ignored -> {
        } : progress;
        this.reports = new FillReportWriter(layout, clock);
        this.runLog = new RunLog(layout, clock);
        this.state = run.state();
        this.modelCalls = run.usage().modelCalls();
        this.tokens = run.usage().tokens();
        this.fresh = run.conversation().size() <= 1;
        if (!fresh) {
            reports.load(run.project(), run.job()).ifPresent(report -> {
                draft.restore(report);
                if (state.fields().stream().noneMatch(field -> field.report() != null)) {
                    state = seeded(state, report.fields());
                }
            });
        }
        draft.company = run.company();
        draft.title = run.title();
        draft.round = run.round();
    }

    /** 读这一次运行要用的 skill 目录、本次答案和资料；重新开始的申请把上一次的报告换成这一次的（现在还是空的）。 */
    public void open(SkillCatalog skills) {
        catalog = skills;
        answers = new JobAnswers(layout, clock).list(run.project(), run.job());
        List<Fact> answerFacts = answers.stream().map(JobAnswers.Answer::toFact).toList();
        ProfileResolver resolver = new ProfileResolver(layout, clock);
        LedgerFile ledger = new LedgerFile(layout, clock);
        profileZh = FillContext.sendable(resolver.resolve(run.project(), answerFacts, Language.ZH), ledger::find);
        profileEn = FillContext.sendable(resolver.resolve(run.project(), answerFacts, Language.EN), ledger::find);
        Set<String> keys = new LinkedHashSet<>();
        profileZh.values().stream().filter(fact -> fact.factClass() == FactClass.C)
                .forEach(fact -> Placeholder.keyOf(fact.value()).ifPresent(keys::add));
        placeholders = keys;
        if (fresh && reports.read(run.project(), run.job()).isPresent()) {
            writeDraft();
        }
    }

    // ---- 读 ----

    public AgentRun run() {
        return run;
    }

    public boolean fresh() {
        return fresh;
    }

    public List<AgentRun.StagedFile> files() {
        return List.copyOf(files);
    }

    public ApplicationState state() {
        return state;
    }

    public Draft draft() {
        return draft;
    }

    public PageSnapshot latest() {
        return latest;
    }

    public SkillCatalog catalog() {
        return catalog;
    }

    public List<JobAnswers.Answer> answers() {
        return answers;
    }

    public Map<String, ResolvedFact> profileZh() {
        return profileZh;
    }

    public Map<String, ResolvedFact> profileEn() {
        return profileEn;
    }

    public Set<String> placeholders() {
        return placeholders;
    }

    /** 过程日志的步数：调了几次模型。 */
    public int step() {
        return (int) modelCalls;
    }

    public long tokens() {
        return tokens;
    }

    // ---- 改 ----

    public void setState(ApplicationState next) {
        state = next;
    }

    public void setLatest(PageSnapshot page) {
        latest = page;
    }

    /** 换掉可上传的文件清单（选定简历时）。 */
    public void setFiles(List<AgentRun.StagedFile> next) {
        files.clear();
        files.addAll(next);
    }

    /** 调了一次模型。 */
    public void used(long in, long out) {
        modelCalls++;
        tokens += in + out;
    }

    public void log(RunLog.Entry entry) {
        runLog.append(run.project(), run.job(), entry);
    }

    /** 每轮之后交给调用方存盘。 */
    public void saveProgress(List<AgentRun.Turn> turns, int callsThisRun) {
        progress.accept(current(turns, callsThisRun));
    }

    /** 报告草稿：字段行从申请状态的字段清单生成（C019），附件、未解决、守卫记录在 {@link Draft} 里。 */
    public void writeDraft() {
        List<FillReportWriter.Field> rows = new ArrayList<>();
        for (ApplicationState.Field field : state.fields()) {
            ApplicationState.ReportRow row = field.report();
            if (row == null) {
                continue;
            }
            rows.add(row.value() == null && FactClass.C.name().equals(row.grade())
                    ? new FillReportWriter.UserFilled(row.page(), field.label())
                    : new FillReportWriter.Recorded(row.page(), field.label(), row.value(), row.source(),
                    row.language(), row.confidence(), row.grade()));
        }
        reports.writeDraft(run.project(), run.job(), draft.report(rows));
    }

    /** 到现在的运行记录：文件、申请状态、用量、对话和最后的页面地址（接着做时按它认回原页）。 */
    public AgentRun current(List<AgentRun.Turn> turns, int callsThisRun) {
        AgentRun progressed = run.withFiles(files)
                .withProgress(state, new AgentRun.Usage(modelCalls, toolCalls(callsThisRun), tokens), turns);
        String url = latest != null ? latest.url() : run.lastPage() == null ? null : run.lastPage().url();
        return progressed.withLastPage(new AgentRun.LastPage(url, null, false));
    }

    /** 用过的工具调用次数：之前几次运行的，加上这一次的。 */
    public long toolCalls(int callsThisRun) {
        return run.usage().toolCalls() + callsThisRun;
    }

    // ---- 文件 ----

    /** 可上传的文件在上传区里的路径（编号 1 对应第一个）；上传副本不见了时从原件再复制一份。 */
    public List<Path> stagedPaths() {
        UploadArea uploads = new UploadArea(layout, new AssetRegistry(layout, clock));
        List<Path> paths = new ArrayList<>();
        for (AgentRun.StagedFile file : files) {
            if (file.attachment() != null) {
                // 附件可能在投递段的目录，也可能在历史的 followup/attachments/（统一 Job 线程，两处互为后备）
                paths.add(JobThreadStore.attachments(layout, run.project(), run.job(), true).find(file.attachment())
                        .map(AttachmentStore.Stored::path)
                        .orElse(layout.jobAttachmentsDir(run.job()).resolve(file.attachment()).resolve(file.uploadName())));
                continue;
            }
            Path copy = layout.uploadsDir(run.job()).resolve(file.uploadName());
            if (file.assetKey() != null && !Files.isRegularFile(copy)) {
                try {
                    copy = uploads.stage(run.job(), file.assetKey());
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("文件 " + file.uploadName()
                            + " 的上传副本不见了，从原件复制失败：" + e.getMessage());
                }
            }
            paths.add(copy);
        }
        return paths;
    }

    /** 资料库原件路径；没有 assetKey、原件登记或文件时为 null。 */
    public Path originalPath(AgentRun.StagedFile file) {
        if (file.assetKey() == null) {
            return null;
        }
        AssetRegistry assets = new AssetRegistry(layout, clock);
        return assets.find(file.assetKey()).map(assets::originalPath).filter(Files::isRegularFile).orElse(null);
    }

    /** 浏览器能去的站点：岗位链接和最新页面的地址。 */
    public List<String> siteUrls() {
        List<String> urls = new ArrayList<>();
        if (run.url() != null) {
            urls.add(run.url());
        }
        if (latest != null) {
            urls.add(latest.url());
        }
        return urls;
    }

    /** 工作台内简历匹配与选定（C021）。 */
    public ResumeMatch resumes() {
        return new ResumeMatch(new AssetRegistry(layout, clock), new JobLedger(layout, clock), clock);
    }

    public JobRepository jobs() {
        return new JobRepository(layout);
    }

    public PersonalEnv personal() {
        return new PersonalEnv(layout);
    }

    // ---- 小工具 ----

    /** 页面的主机名（小写）；解析不了时为 null。 */
    public static String host(PageSnapshot page) {
        return page == null ? null : hostOf(page.url());
    }

    public static String hostOf(String url) {
        try {
            String host = url == null ? null : URI.create(url).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static RunLog.Page page(PageSnapshot page) {
        return page == null ? null : new RunLog.Page(page.title(), page.url());
    }

    public static RunLog.Element element(PageSnapshot page, String ref) {
        if (page == null || ref == null) {
            return null;
        }
        return SnapshotIndex.of(page).find(ref).map(node -> new RunLog.Element(node.role(), node.name())).orElse(null);
    }

    public static String buttonName(PageSnapshot page, String ref) {
        if (page == null || ref == null) {
            return null;
        }
        return SnapshotIndex.of(page).find(ref)
                .map(node -> node.name().isBlank() ? node.text() : node.name())
                .orElse(null);
    }

    /**
     * C019 之前的申请：报告里已有的字段行放进字段清单（已填；「用户亲填」的是要用户填），报告那一行原样保留。
     */
    private static ApplicationState seeded(ApplicationState state, List<FillReportWriter.Field> rows) {
        ApplicationState seeded = state;
        int n = seeded.fields().size();
        for (FillReportWriter.Field row : rows) {
            String id;
            do {
                id = "f" + ++n;
            } while (seeded.find(id).isPresent());
            ApplicationState.Field field = switch (row) {
                case FillReportWriter.Recorded r -> new ApplicationState.Field(id, null, r.label(), null, r.value(), null,
                        ApplicationState.FILLED, null, null, new ApplicationState.ReportRow(r.page(), r.value(),
                        r.source(), r.language(), r.confidence(), r.grade()));
                case FillReportWriter.UserFilled u -> new ApplicationState.Field(id, null, u.label(), null, null, null,
                        ApplicationState.NEEDS_USER, "C 级：由用户在浏览器里亲自填", null,
                        new ApplicationState.ReportRow(u.page(), null, null, null, null, FactClass.C.name()));
                case FillReportWriter.Filled f -> {
                    FillReportWriter.Recorded r = FillReportWriter.asRecorded(f);
                    yield new ApplicationState.Field(id, null, r.label(), null, f.value(), null, ApplicationState.FILLED,
                            null, null, new ApplicationState.ReportRow(r.page(), r.value(), r.source(), r.language(),
                            r.confidence(), r.grade()));
                }
            };
            seeded = seeded.merge(field);
        }
        return seeded;
    }
}
