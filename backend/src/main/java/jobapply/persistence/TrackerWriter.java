package jobapply.persistence;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import jobapply.domain.Evidence;
import jobapply.domain.Job;
import jobapply.domain.JobEvent;
import jobapply.domain.JobStatus;
import jobapply.domain.LeadStatus;
import jobapply.domain.Phase;
import jobapply.domain.HiringRound;

/**
 * 从 {@code job.md} 与 {@code events.jsonl} 生成台账：每个 Project 的 {@code tracker/leads}、
 * {@code tracker/applications}、{@code tracker/rounds}（md + csv），以及全局的 {@code jobs-index.md}、
 * {@code exports/applications.csv} 和复盘（{@link ReviewWriter}）。栏目见 plan/master/tracking.md「台账表格」。
 * 「进度」由轮次画圆点；「同步」和轮次的 unknown（用户未同步）在这里按今天算（C014-A）。
 */
public final class TrackerWriter {

    static final String GENERATED = "> 生成文件，请勿手改。\n";
    /** 没有内容的格子（没有轮次的「进度」、还没投递的「同步」）。 */
    static final String PENDING = "—";
    private static final Map<String, String> RESULT_LABEL = Map.of("pending", "待结果", "passed", "通过",
            "failed", "未通过", "cancelled", "取消", HiringRound.UNKNOWN, "未同步");
    private static final Map<String, String> RESULT_DOT = Map.of("pending", "◐", "passed", "●",
            "failed", "✕", "cancelled", "⊘", HiringRound.UNKNOWN, "◐");
    private static final List<Phase> PHASE_ORDER = List.of(
            Phase.PREP, Phase.INTERVIEW, Phase.ASSESSMENT, Phase.SCREENING, Phase.APPLIED, Phase.OFFER, Phase.CLOSED);

    private final StorageLayout layout;
    private final JobRepository jobs;
    private final EventLog eventLog = new EventLog();
    private final Clock clock;
    private final ReviewWriter reviews;

    public TrackerWriter(StorageLayout layout) {
        this(layout, Clock.systemDefaultZone());
    }

    /** @param clock 算 unknown（用户未同步）用的今天 */
    public TrackerWriter(StorageLayout layout, Clock clock) {
        this.layout = layout;
        this.jobs = new JobRepository(layout);
        this.clock = clock;
        this.reviews = new ReviewWriter(layout, clock);
    }

    /** 重新生成本 Project 的六个台账文件，返回写过的路径。 */
    public List<Path> writeProject(String projectId) {
        List<Job> all = jobs.list(projectId);
        Map<String, String> lastActivity = lastActivity(projectId, all);
        List<Job> applications = all.stream().filter(TrackerWriter::isApplication).toList();
        Map<String, Integer> numbers = new HashMap<>();
        for (int i = 0; i < applications.size(); i++) {
            numbers.put(applications.get(i).id(), i + 1);
        }

        List<Job> leads = new ArrayList<>(all);
        leads.sort(Comparator.comparing((Job job) -> isApplication(job) ? 1 : 0)
                .thenComparing(job -> normalize(job.posting().company()))
                .thenComparing(Job::id));
        List<Job> sortedApplications = new ArrayList<>(applications);
        sortedApplications.sort(Comparator.comparing((Job job) -> PHASE_ORDER.indexOf(job.status().phase()))
                .thenComparing(Job::id));

        Path dir = layout.trackerDir(projectId);
        Path leadsMd = dir.resolve("leads.md");
        Path leadsCsv = dir.resolve("leads.csv");
        Path applicationsMd = dir.resolve("applications.md");
        Path applicationsCsv = dir.resolve("applications.csv");
        AtomicFiles.write(leadsMd, leadsMarkdown(projectId, leads));
        AtomicFiles.write(leadsCsv, csv(LEAD_COLUMNS, leads, lastActivity));
        AtomicFiles.write(applicationsMd, applicationsMarkdown(projectId, sortedApplications, numbers, lastActivity));
        AtomicFiles.write(applicationsCsv, csv(APPLICATION_COLUMNS, sortedApplications, lastActivity));
        Path roundsMd = dir.resolve("rounds.md");
        Path roundsCsv = dir.resolve("rounds.csv");
        AtomicFiles.write(roundsMd, roundsMarkdown(projectId, applications));
        AtomicFiles.write(roundsCsv, roundsCsv(applications));
        return List.of(leadsMd, leadsCsv, applicationsMd, applicationsCsv, roundsMd, roundsCsv);
    }

    /** 重新生成全局的 jobs-index.md 与 exports/applications.csv（候选与申请都在内）。 */
    public List<Path> writeGlobal() {
        List<Job> all = new ArrayList<>();
        Map<String, String> lastActivity = new HashMap<>();
        for (String projectId : layout.projectIds()) {
            List<Job> projectJobs = jobs.list(projectId);
            all.addAll(projectJobs);
            lastActivity.putAll(lastActivity(projectId, projectJobs));
        }
        all.sort(Comparator.comparing(Job::project).thenComparing(Job::id));
        AtomicFiles.write(layout.jobsIndex(), jobsIndexMarkdown(all));
        AtomicFiles.write(layout.exportsApplicationsCsv(), csv(APPLICATION_COLUMNS, all, lastActivity));
        List<Path> written = new ArrayList<>(List.of(layout.jobsIndex(), layout.exportsApplicationsCsv()));
        written.addAll(reviews.write(all));
        return written;
    }

    private String leadsMarkdown(String projectId, List<Job> leads) {
        StringBuilder out = new StringBuilder(GENERATED).append("\n# 候选池 · ").append(projectId).append("\n\n");
        table(out, List.of("公司", "岗位", "城市", "类型", "状态", "评分", "资格", "发现于", "链接", "不投原因"));
        for (Job job : leads) {
            row(out, List.of(
                    cell(job.posting().company()),
                    link(job.posting().title(), "../jobs/" + job.id() + "/job.md"),
                    cell(String.join("、", job.posting().city())),
                    cell(job.posting().type()),
                    cell(job.status().leadStatus().value()),
                    cell(job.extraString("score")),
                    cell(job.extraString("eligibility")),
                    sessionLink(job.discoveredIn(), "../sessions/"),
                    officialLink(job.posting().url()),
                    cell(job.status().skipReason())));
        }
        return out.toString();
    }

    private String applicationsMarkdown(String projectId, List<Job> applications, Map<String, Integer> numbers,
            Map<String, String> lastActivity) {
        StringBuilder out = new StringBuilder(GENERATED).append("\n# 申请总表 · ").append(projectId).append("\n\n");
        table(out, List.of("#", "公司", "岗位", "类型", "城市", "投递日", "阶段", "进度", "下一件事", "最近动态", "最近核验",
                "下次查看", "同步", "下一步"));
        for (Job job : applications) {
            row(out, List.of(
                    String.valueOf(numbers.get(job.id())),
                    cell(job.posting().company()),
                    link(job.posting().title(), "../jobs/" + job.id() + "/job.md"),
                    cell(job.posting().type()),
                    cell(String.join("、", job.posting().city())),
                    cell(shortDate(submittedAt(job))),
                    cell(phaseLabel(job.status())),
                    cell(progress(job)),
                    cell(nextEvent(job)),
                    cell(shortDate(lastActivity.get(job.id()))),
                    cell(shortDate(job.extraString("last_checked_at"))),
                    cell(shortDate(job.extraString("next_check_at"))),
                    cell(sync(job, lastActivity.get(job.id()))),
                    cell(job.extraString("next_action"))));
        }
        return out.toString();
    }

    private String jobsIndexMarkdown(List<Job> all) {
        StringBuilder out = new StringBuilder(GENERATED).append("\n# 全部岗位与申请\n\n");
        table(out, List.of("Project", "公司", "岗位", "状态", "发现于", "链接"));
        for (Job job : all) {
            String jobDir = "projects/" + job.project() + "/";
            row(out, List.of(
                    cell(job.project()),
                    cell(job.posting().company()),
                    link(job.posting().title(), jobDir + "jobs/" + job.id() + "/job.md"),
                    cell(phaseLabel(job.status())),
                    sessionLink(job.discoveredIn(), jobDir + "sessions/"),
                    officialLink(job.posting().url())));
        }
        return out.toString();
    }

    /** 表 2 轮次表：一轮一行，按公司、Job、轮次排。 */
    private String roundsMarkdown(String projectId, List<Job> applications) {
        StringBuilder out = new StringBuilder(GENERATED).append("\n# 轮次表 · ").append(projectId).append("\n\n");
        table(out, List.of("公司", "岗位", "轮次", "类型", "日期", "形式", "结果", "题数", "复盘"));
        LocalDate today = LocalDate.now(clock);
        for (Job job : byCompany(applications)) {
            for (HiringRound round : HiringRound.of(job)) {
                row(out, List.of(
                        cell(job.posting().company()),
                        link(job.posting().title(), "../jobs/" + job.id() + "/job.md"),
                        cell(round.name()),
                        cell(round.type()),
                        cell(shortDate(round.date())),
                        cell(round.format()),
                        cell(round.effectiveResult(today)),
                        round.review() == null ? "" : String.valueOf(round.questionCount()),
                        round.review() == null ? "" : link("复盘", "../jobs/" + job.id() + "/" + round.review())));
            }
        }
        return out.toString();
    }

    private String roundsCsv(List<Job> applications) {
        List<String> header = List.of("job", "project", "company", "title", "seq", "name", "type", "scheduled_at",
                "happened_at", "format", "interviewer_role", "result", "result_at", "result_source", "question_count",
                "review");
        List<List<String>> values = new ArrayList<>();
        LocalDate today = LocalDate.now(clock);
        for (Job job : byCompany(applications)) {
            for (HiringRound round : HiringRound.of(job)) {
                values.add(Arrays.asList(job.id(), job.project(), job.posting().company(),
                        job.posting().title(), String.valueOf(round.seq()), round.name(), round.type(),
                        round.scheduledAt(), round.happenedAt(), round.format(), round.interviewerRole(),
                        round.effectiveResult(today), round.resultAt(), round.resultSource(),
                        round.review() == null ? null : String.valueOf(round.questionCount()), round.review()));
            }
        }
        return CsvWriter.render(header, values);
    }

    private static List<Job> byCompany(List<Job> jobs) {
        List<Job> sorted = new ArrayList<>(jobs);
        sorted.sort(Comparator.comparing((Job job) -> normalize(job.posting().company())).thenComparing(Job::id));
        return sorted;
    }

    /** 「进度」：一轮一个圆点（● 通过、◐ 进行中或待结果、✕ 未通过、⊘ 取消），后面是最新一轮的名称和结果。 */
    private String progress(Job job) {
        List<HiringRound> rounds = HiringRound.of(job);
        if (rounds.isEmpty()) {
            return PENDING;
        }
        LocalDate today = LocalDate.now(clock);
        StringBuilder dots = new StringBuilder();
        rounds.forEach(round -> dots.append(RESULT_DOT.get(round.effectiveResult(today))));
        HiringRound latest = rounds.getLast();
        return dots + " " + latest.name() + " · " + RESULT_LABEL.get(latest.effectiveResult(today));
    }

    /**
     * 「同步」（tracking.md「unknown 规则」）：投递以后、没结束的申请，有一轮算作 unknown，或者最近一次动态已经过了
     * {@link HiringRound#UNKNOWN_AFTER_DAYS} 天，就是「⚠ unknown」（用户未同步）；否则 ✓。还没投递或已结束的不算。
     */
    private String sync(Job job, String lastActivity) {
        Phase phase = job.status().phase();
        if (phase == null || phase == Phase.PREP || phase == Phase.CLOSED) {
            return PENDING;
        }
        LocalDate today = LocalDate.now(clock);
        boolean unknown = HiringRound.of(job).stream().anyMatch(round -> HiringRound.UNKNOWN.equals(round.effectiveResult(today)));
        LocalDate last = HiringRound.day(lastActivity);
        if (last != null && last.plusDays(HiringRound.UNKNOWN_AFTER_DAYS).isBefore(today)) {
            unknown = true;
        }
        return unknown ? "⚠ unknown" : "✓";
    }

    private Map<String, String> lastActivity(String projectId, List<Job> projectJobs) {
        Map<String, String> result = new HashMap<>();
        for (Job job : projectJobs) {
            List<JobEvent> events = eventLog.readJobEvents(layout.jobEvents(projectId, job.id()));
            if (!events.isEmpty()) {
                result.put(job.id(), events.getLast().at());
            }
        }
        return result;
    }

    private record Column(String name, Function<Row, String> value) {
    }

    private record Row(Job job, String lastActivity, String progress, String sync) {
    }

    private static final List<Column> POSTING_COLUMNS = List.of(
            new Column("id", row -> row.job().id()),
            new Column("project", row -> row.job().project()),
            new Column("company", row -> row.job().posting().company()),
            new Column("recruitment_project", row -> row.job().posting().recruitmentProject()),
            new Column("title", row -> row.job().posting().title()),
            new Column("job_id", row -> row.job().posting().jobId()),
            new Column("type", row -> row.job().posting().type()),
            new Column("city", row -> String.join("; ", row.job().posting().city())),
            new Column("site", row -> row.job().posting().site()),
            new Column("source", row -> row.job().posting().source()),
            new Column("url", row -> row.job().posting().url()),
            new Column("history_url", row -> row.job().posting().historyUrl()),
            new Column("discovered_in", row -> row.job().discoveredIn()),
            new Column("lead_status", row -> row.job().status().leadStatus().value()),
            new Column("skip_reason", row -> row.job().status().skipReason()),
            new Column("score", row -> row.job().extraString("score")),
            new Column("eligibility", row -> row.job().extraString("eligibility")));

    private static final List<Column> LEAD_COLUMNS = POSTING_COLUMNS;

    private static final List<Column> APPLICATION_COLUMNS = concat(POSTING_COLUMNS, List.of(
            new Column("phase", row -> enumValue(row.job().status().phase())),
            new Column("prep_state", row -> enumValue(row.job().status().prepState())),
            new Column("outcome", row -> enumValue(row.job().status().outcome())),
            new Column("evidence.submitted.kind", row -> evidence(row, Evidence::kind)),
            new Column("evidence.submitted.text", row -> evidence(row, Evidence::text)),
            new Column("evidence.submitted.official_time", row -> evidence(row, Evidence::officialTime)),
            new Column("evidence.submitted.local_time", row -> evidence(row, Evidence::localTime)),
            new Column("evidence.submitted.verified_at", row -> evidence(row, Evidence::verifiedAt)),
            new Column("progress", Row::progress),
            new Column("next_event.what", row -> nested(row.job(), "next_event", "what")),
            new Column("next_event.at", row -> nested(row.job(), "next_event", "at")),
            new Column("last_activity", Row::lastActivity),
            new Column("applied_at", row -> row.job().extraString("applied_at")),
            new Column("last_checked_at", row -> row.job().extraString("last_checked_at")),
            new Column("next_check_at", row -> row.job().extraString("next_check_at")),
            new Column("sync", Row::sync),
            new Column("next_action", row -> row.job().extraString("next_action")),
            new Column("blocker", row -> row.job().extraString("blocker")),
            new Column("materials.resume.asset", row -> nested(row.job(), "materials", "resume", "asset")),
            new Column("materials.resume.upload_name", row -> nested(row.job(), "materials", "resume", "upload_name")),
            new Column("materials.resume.sha256", row -> nested(row.job(), "materials", "resume", "sha256"))));

    private String csv(List<Column> columns, List<Job> rows, Map<String, String> lastActivity) {
        List<String> header = columns.stream().map(Column::name).toList();
        List<List<String>> values = new ArrayList<>();
        for (Job job : rows) {
            String last = lastActivity.get(job.id());
            Row row = new Row(job, last, HiringRound.of(job).isEmpty() ? null : progress(job), sync(job, last));
            values.add(columns.stream().map(column -> column.value().apply(row)).toList());
        }
        return CsvWriter.render(header, values);
    }

    private static boolean isApplication(Job job) {
        return job.status().leadStatus() == LeadStatus.CONVERTED;
    }

    private static String phaseLabel(JobStatus status) {
        if (status.phase() == null) {
            return status.leadStatus().value();
        }
        return switch (status.phase()) {
            case PREP -> "prep · " + status.prepState().value();
            case CLOSED -> "closed · " + status.outcome().value();
            default -> status.phase().value();
        };
    }

    private static String submittedAt(Job job) {
        Evidence evidence = job.status().evidence();
        if (evidence == null) {
            return null;
        }
        if (evidence.officialTime() != null) {
            return evidence.officialTime();
        }
        String appliedAt = job.extraString("applied_at");
        return appliedAt != null ? appliedAt : evidence.localTime();
    }

    private static String nextEvent(Job job) {
        String what = nested(job, "next_event", "what");
        String at = nested(job, "next_event", "at");
        if (what == null && at == null) {
            return null;
        }
        return (at == null ? "" : shortDate(at) + " ") + (what == null ? "" : what);
    }

    private static String nested(Job job, String... path) {
        Object current = job.extra().get(path[0]);
        for (int i = 1; i < path.length && current != null; i++) {
            current = current instanceof Map<?, ?> map ? map.get(path[i]) : null;
        }
        return current == null ? null : current.toString();
    }

    private static String evidence(Row row, Function<Evidence, String> field) {
        Evidence evidence = row.job().status().evidence();
        return evidence == null ? null : field.apply(evidence);
    }

    private static String enumValue(Enum<?> value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }

    /** {@code 2026-10-22...} → {@code 10-22}；其它写法原样返回。 */
    static String shortDate(String value) {
        if (value == null) {
            return null;
        }
        return value.matches("\\d{4}-\\d{2}-\\d{2}.*") ? value.substring(5, 10) : value;
    }

    private static void table(StringBuilder out, List<String> header) {
        row(out, header);
        out.append('|');
        out.append(" --- |".repeat(header.size()));
        out.append('\n');
    }

    private static void row(StringBuilder out, List<String> cells) {
        out.append('|');
        for (String cell : cells) {
            out.append(' ').append(cell).append(" |");
        }
        out.append('\n');
    }

    private static String cell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    private static String link(String text, String target) {
        return "[" + cell(text).replace("]", "\\]") + "](" + target + ")";
    }

    private static String sessionLink(String sessionId, String sessionsPrefix) {
        return sessionId == null ? "" : link(sessionId, sessionsPrefix + sessionId + "/session.md");
    }

    private static String officialLink(String url) {
        return url == null || url.isBlank() ? "" : "[官网](" + url.replace(" ", "%20").replace(")", "%29") + ")";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        List<T> all = new ArrayList<>(first);
        all.addAll(second);
        return List.copyOf(all);
    }
}
