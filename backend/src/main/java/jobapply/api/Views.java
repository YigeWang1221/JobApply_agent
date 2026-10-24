package jobapply.api;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import jobapply.domain.Actor;
import jobapply.domain.AgentRun;
import jobapply.domain.Job;
import jobapply.domain.JobBucket;
import jobapply.domain.JobEvent;
import jobapply.domain.JobStatus;
import jobapply.domain.LeadStatus;
import jobapply.domain.Phase;
import jobapply.domain.PrepState;
import jobapply.domain.ProjectSpec;
import jobapply.domain.SessionRecord;
import jobapply.domain.Timestamps;
import jobapply.harness.policies.SubmitWords;
import jobapply.persistence.AttachmentStore;
import jobapply.persistence.JobLedger;
import jobapply.persistence.SessionRepository;
import jobapply.workflow.ApplicationException;
import jobapply.workflow.ApplicationRunner;
import jobapply.workflow.ApplicationStatus;
import jobapply.workflow.RuntimeEvents;
import jobapply.workflow.SessionRunner;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 界面上的只读视图（C005-ui「后端要补的只读接口」）：Job、Session、Project 的摘要，以及把一段对话
 * （{@link AgentRun.Turn}）折叠成消息流——工具结果并进前一条 Agent 消息，系统说明和页面不显示。
 * {@code tools} 只给名字、一句说明和结果的第一行（旧界面用）；{@code steps}（C006-agent）按时间给出思考和每次工具调用的
 * 参数、结果（截到 {@link #RESULT_TEXT} 字）、状态和时间。对话里本来就不存快照全文。
 */
final class Views {

    private static final int RESULT_LINE = 160;
    static final int RESULT_TEXT = 4_000;
    private static final Set<Phase> APPLIED = EnumSet.of(Phase.APPLIED, Phase.SCREENING, Phase.ASSESSMENT,
            Phase.INTERVIEW, Phase.OFFER);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    record JobView(String id, String project, String company, String title, List<String> city, String type,
                   String url, String site, String source, String discoveredIn, String leadStatus, String skipReason,
                   String phase, String prepState, String label, String verified, String officialUrl, String note,
                   String application, String appliedAt, String lastCheckedAt, String nextCheckAt,
                   boolean pageLooksSubmitted, List<PhaseEvent> events, boolean due, String bucket,
                   boolean canStart, Map<?, ?> resumeChoice) {
    }

    /**
     * Dashboard 时间线和漏斗用的阶段变化（C006-fix-ui）：只有转为申请、阶段变化、结束三类。
     *
     * @param to 变化后的标签，形如 {@code applied}、{@code prep:selected}、{@code closed:rejected}
     */
    record PhaseEvent(String at, String type, String to) {
    }

    private static final Set<JobEvent.Type> PHASE_EVENTS = EnumSet.of(JobEvent.Type.CONVERTED,
            JobEvent.Type.PHASE_CHANGED, JobEvent.Type.CLOSED);

    /** @param status {@code running} / {@code idle} / {@code interrupted} */
    record SessionSummary(String id, String project, String title, String createdAt, List<String> tags,
                          List<String> jobs, String handoff, String status) {
    }

    /** @param dueChecks 「该查看了」的申请个数（C007） */
    record Counts(int leads, int shortlisted, int preparing, int waiting, int ready, int applied, int dueChecks) {
    }

    record ProjectSummary(String id, String title, String status, List<SessionSummary> sessions, Counts counts) {
    }

    record ToolStep(String name, String summary, String result) {
    }

    /**
     * 过程里的一步。
     *
     * @param kind      {@code thinking}（供应商真的给出的思考，{@code text}）或 {@code tool}
     * @param args      工具参数（JSON 对象原样）
     * @param result    工具结果，截到 {@link #RESULT_TEXT} 字；还没有结果时为 null
     * @param status    {@code running} / {@code done} / {@code failed} / {@code rejected}
     * @param startedAt 开始时间；旧记录里没有时为 null
     */
    record ProcessStep(String kind, String text, String name, String summary, Map<String, Object> args,
                       String result, String status, String startedAt, String endedAt) {
    }

    /** 现在在做什么：{@code kind} 是 {@code thinking} 或 {@code tool}。 */
    record Activity(String kind, String name, String summary, String since) {
    }

    /**
     * 一条消息。{@code role} 是 {@code user} 或 {@code assistant}；用户的消息 {@code tools}、{@code steps} 为空，
     * Agent 的消息 {@code attachments} 为空。
     */
    record ChatMessage(String role, String text, List<ToolStep> tools, List<ProcessStep> steps, String at,
                       List<ChatAttachment> attachments) {
    }

    /** 用户消息附的文件（C006 附件）；{@code url} 读原文件。 */
    record ChatAttachment(String id, String name, String contentType, boolean image, String url) {
    }

    /** 上传附件的返回。 */
    record UploadedAttachment(String id, String name, long size, String contentType, boolean image) {
    }

    static UploadedAttachment uploaded(AttachmentStore.Stored stored) {
        return new UploadedAttachment(stored.id(), stored.name(), stored.size(), stored.contentType(), stored.image());
    }

    /**
     * 原样返回附件（缩略图、点开看）。{@code sandbox} 让 HTML、SVG 这类附件在本机页面里不能执行脚本。
     */
    static ResponseEntity<Resource> attachmentFile(AttachmentStore store, String id) {
        AttachmentStore.Stored stored = store.find(id)
                .orElseThrow(() -> new ApplicationException.NotFound("没有这个附件：" + id));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(stored.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(stored.name(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox")
                .body(new FileSystemResource(stored.path()));
    }

    private Views() {
    }

    /**
     * {@code nextCheckAt} 是日期（{@code yyyy-MM-dd}），到了这一天就「该查看了」（C007）。{@code pageLooksSubmitted}：
     * 申请 Agent 结束时模型判断页面已经像投递成功了（{@link AgentRun#pageShowsSubmitted()}）。{@code events} 为 null（没读事件日志）。
     * 下面三个只在这里算（C006-fix 第 6 节），前端和 {@code list_jobs} 都读同一个判断：{@code due}「该查看了」
     * （{@link Job#checkDue}，按服务器的 {@code today}）；{@code bucket} 分组（{@link JobBucket}）；{@code canStart}
     * 可以开始申请线程：还没有申请线程、状态允许（{@link jobapply.domain.JobStatus#canStartApplication}）、有岗位链接。
     */
    static JobView job(Job job, ApplicationRunner applications, LocalDate today) {
        return job(job, applications, null, today);
    }

    /** 带阶段变化的 JobView（Project 主页、全局页的列表用）；{@code history} 是这个 Job 的全部事件。 */
    static JobView job(Job job, ApplicationRunner applications, List<JobEvent> history, LocalDate today) {
        List<PhaseEvent> events = history == null ? null : history.stream()
                .filter(e -> PHASE_EVENTS.contains(e.type()))
                .map(e -> new PhaseEvent(e.at(), e.type().value(), e.to()))
                .toList();
        Optional<ApplicationStatus> status = applications.statusIfAny(job.id());
        String application = status.map(ApplicationStatus::status).orElse(null);
        // 模型 finish 时判断的（page_shows_submitted，存在 AgentRun 里），不再从给人看的文字里找（C006-fix-runtime-2）
        // 旧记录没有 lastPage：退回原来按给人看的那句话判断
        boolean looksSubmitted = applications.run(job.id()).map(run -> run.lastPage() != null ? run.pageShowsSubmitted()
                : status.map(ApplicationStatus::message).filter(message -> message.contains(SubmitWords.SUBMITTED_NOTICE))
                        .isPresent()).orElse(false);
        return new JobView(job.id(), job.project(), job.posting().company(), job.posting().title(),
                job.posting().city(), job.posting().type(), job.posting().url(), job.posting().site(),
                job.posting().source(), job.discoveredIn(), job.status().leadStatus().value(),
                job.status().skipReason(), job.status().phase() == null ? null : job.status().phase().value(),
                job.status().prepState() == null ? null : job.status().prepState().value(), job.status().label(),
                job.extraString("verified"), job.extraString("official_url"), job.extraString("lead_note"),
                application, job.extraString("applied_at"), job.extraString("last_checked_at"),
                job.extraString("next_check_at"), looksSubmitted, events, job.checkDue(today),
                JobBucket.of(job.status(), application).value(),
                application == null && job.status().canStartApplication()
                        && job.posting().url() != null && !job.posting().url().isBlank(),
                // C008 简历排名：job.md 里 resume_choice 原样给前端（asset、file、ranking、suggestions、at，或 skipped）
                job.extra().get("resume_choice") instanceof Map<?, ?> choice ? choice : null);
    }

    /**
     * 已投递、没结束、{@code next_check_at} 到了今天（含）的申请（C007）：就是 {@link JobView#due}（建视图时按同一个
     * {@code today} 算好的，{@link Job#checkDue}）；{@code today} 只为调用处不变而保留。
     */
    static boolean checkDue(JobView job, LocalDate today) {
        return job.due();
    }

    static SessionSummary session(SessionRecord s, SessionRunner runner) {
        return new SessionSummary(s.id(), s.project(), s.title(), s.createdAt(), s.tags(), s.jobs(), s.handoff(),
                runner.status(s).status());
    }

    static Activity activity(RuntimeEvents.Activity activity) {
        if (activity == null) {
            return null;
        }
        if (activity.call() == null) {
            return new Activity(activity.kind(), null, null, activity.since());
        }
        return new Activity(activity.kind(), activity.call().name(),
                summary(activity.call().name(), args(activity.call().arguments())), activity.since());
    }

    /** 一次执行完的工具调用（推送用；和消息流里的那一步一样）。 */
    static ProcessStep step(AgentRun.ToolCall call, AgentRun.ToolResult result) {
        Map<String, Object> args = args(call.arguments());
        return withResult(new ProcessStep("tool", null, call.name(), summary(call.name(), args), args, null,
                "running", null, null), result);
    }

    static ProcessStep thinking(String text) {
        return new ProcessStep("thinking", text, null, null, null, null, AgentRun.ToolResult.DONE, null, null);
    }

    /** 申请线程停下来等用户的状态（计数「在等你」）。 */
    private static final Set<String> AGENT_WAITS_FOR_USER = Set.of(AgentRun.WAITING_USER, AgentRun.FAILED,
            AgentRun.INTERRUPTED);

    /** 准备中、状态还没有被用户定下（{@link JobLedger#userDecided}）。 */
    private static boolean agentsTurn(JobView job) {
        if (!Phase.PREP.value().equals(job.phase()) || job.prepState() == null) {
            return false;
        }
        JobStatus status = new JobStatus(LeadStatus.CONVERTED, null, Phase.PREP, PrepState.of(job.prepState()), null,
                null);
        return !JobLedger.userDecided(status, Actor.AGENT);
    }

    static ProjectSummary project(ProjectSpec spec, List<SessionRecord> sessions, List<JobView> jobs,
                                  SessionRunner runner, LocalDate today) {
        int dueChecks = (int) jobs.stream().filter(job -> checkDue(job, today)).count();
        int leads = 0;
        int shortlisted = 0;
        int preparing = 0;
        int waiting = 0;
        int ready = 0;
        int applied = 0;
        for (JobView job : jobs) {
            if ("new".equals(job.leadStatus()) || "shortlisted".equals(job.leadStatus())) {
                leads++;
            }
            if ("shortlisted".equals(job.leadStatus())) {
                shortlisted++;
            }
            if (Phase.PREP.value().equals(job.phase())) {
                preparing++;
            }
            // Agent 的进度都读申请线程的状态（从 AgentRun 和内存推出来，含中断，C006-fix-2 第 3 条）；
            // 用户已经定了的（你在提交、先放着……）不算在等你、停在提交前
            if (job.application() != null && agentsTurn(job)) {
                if (AGENT_WAITS_FOR_USER.contains(job.application())) {
                    waiting++;
                }
                if (AgentRun.READY.equals(job.application())) {
                    ready++;
                }
            }
            if (job.phase() != null && APPLIED.contains(Phase.of(job.phase()))) {
                applied++;
            }
        }
        return new ProjectSummary(spec.id(), spec.title(), spec.status(),
                sessions.stream().map(s -> session(s, runner)).toList(),
                new Counts(leads, shortlisted, preparing, waiting, ready, applied, dueChecks));
    }

    /**
     * 申请线程的对话（没有时间）。{@code live} 是这一轮还没存盘的回复和结果，没有时为 null。
     * {@code attachmentUrl} 是读附件的路径前缀（后面接附件编号）。
     */
    static List<ChatMessage> messages(List<AgentRun.Turn> turns, RuntimeEvents.InFlight live, String attachmentUrl) {
        return timed(turns.stream().map(turn -> new SessionRepository.TimedTurn(null, turn)).toList(), live,
                attachmentUrl);
    }

    /**
     * 把对话折叠成消息流。{@code live}（这一轮还没写进对话的回复和已经执行完的结果）补在后面：回复已经在对话里时只补结果。
     */
    static List<ChatMessage> timed(List<SessionRepository.TimedTurn> turns, RuntimeEvents.InFlight live,
                                   String attachmentUrl) {
        List<SessionRepository.TimedTurn> all = new ArrayList<>(turns);
        Map<String, AgentRun.ToolResult> liveResults = Map.of();
        if (live != null) {
            liveResults = live.results();
            String first = live.assistant().calls().getFirst().id();
            boolean stored = turns.stream().anyMatch(timed -> timed.turn().calls().stream()
                    .anyMatch(call -> first.equals(call.id())));
            if (!stored) {
                all.add(new SessionRepository.TimedTurn(null, live.assistant()));
            }
        }
        List<ChatMessage> out = new ArrayList<>();
        // 当前这条 Agent 消息：工具调用 id → 在 tools、steps 里的位置
        Map<String, Integer> open = new LinkedHashMap<>();
        Map<String, Integer> openSteps = new LinkedHashMap<>();
        List<ToolStep> tools = null;
        List<ProcessStep> steps = null;
        for (SessionRepository.TimedTurn timed : all) {
            AgentRun.Turn turn = timed.turn();
            switch (turn.role()) {
                case AgentRun.Turn.USER -> {
                    out.add(new ChatMessage(AgentRun.Turn.USER, turn.text(), List.of(), List.of(), timed.at(),
                            turn.attachments().stream().map(a -> new ChatAttachment(a.id(), a.name(),
                                    a.contentType(), AttachmentStore.isImage(a.contentType()),
                                    attachmentUrl + "/" + a.id())).toList()));
                    tools = null;
                    steps = null;
                }
                case AgentRun.Turn.ASSISTANT -> {
                    tools = new ArrayList<>();
                    steps = new ArrayList<>();
                    open.clear();
                    openSteps.clear();
                    if (turn.thinking() != null && !turn.thinking().isBlank()) {
                        steps.add(thinking(turn.thinking().strip()));
                    }
                    StringBuilder text = new StringBuilder(turn.text() == null ? "" : turn.text().strip());
                    for (AgentRun.ToolCall call : turn.calls()) {
                        Map<String, Object> args = args(call.arguments());
                        if ("ask_user".equals(call.name()) && args.get("message") instanceof String message) {
                            text.append(text.isEmpty() ? "" : "\n\n").append(message.strip());
                        }
                        open.put(call.id(), tools.size());
                        tools.add(new ToolStep(call.name(), summary(call.name(), args), null));
                        openSteps.put(call.id(), steps.size());
                        ProcessStep step = new ProcessStep("tool", null, call.name(), summary(call.name(), args), args,
                                null, "running", null, null);
                        AgentRun.ToolResult pending = liveResults.get(call.id());
                        steps.add(pending == null ? step : withResult(step, pending));
                    }
                    out.add(new ChatMessage(AgentRun.Turn.ASSISTANT, text.isEmpty() ? null : text.toString(), tools,
                            steps, timed.at(), List.of()));
                }
                case AgentRun.Turn.TOOL -> {
                    if (tools == null) {
                        continue;
                    }
                    for (AgentRun.ToolResult result : turn.results()) {
                        Integer at = open.get(result.id());
                        if (at != null) {
                            ToolStep step = tools.get(at);
                            tools.set(at, new ToolStep(step.name(), step.summary(), firstLine(result.text())));
                        }
                        Integer stepAt = openSteps.get(result.id());
                        if (stepAt != null) {
                            steps.set(stepAt, withResult(steps.get(stepAt), result));
                        }
                    }
                }
                case AgentRun.Turn.NOTE -> {
                    // 申请线程的回报（C006-fix 第 2 节）显示成一条 Agent 消息；别的 note 只给模型看
                    if (turn.text() != null && turn.text().startsWith(SessionRunner.REPORT)) {
                        out.add(new ChatMessage(AgentRun.Turn.ASSISTANT,
                                turn.text().substring(SessionRunner.REPORT.length()), List.of(), List.of(), timed.at(),
                                List.of()));
                        tools = null;
                        steps = null;
                    }
                }
                default -> {
                    // page：只给模型看
                }
            }
        }
        return out;
    }

    private static ProcessStep withResult(ProcessStep step, AgentRun.ToolResult result) {
        String text = result.text() == null ? "" : result.text();
        if (text.length() > RESULT_TEXT) {
            text = text.substring(0, RESULT_TEXT) + "…（结果太长，只显示开头）";
        }
        // 旧记录没有状态：按结果的开头判断
        String status = result.status() != null ? result.status()
                : AgentRun.ToolResult.statusOf(result.text());
        return new ProcessStep(step.kind(), null, step.name(), step.summary(), step.args(), text, status,
                result.startedAt(), endedAt(result));
    }

    private static String endedAt(AgentRun.ToolResult result) {
        if (result.startedAt() == null || result.ms() == null) {
            return null;
        }
        try {
            return Timestamps.format(OffsetDateTime.parse(result.startedAt()).plus(Duration.ofMillis(result.ms())));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 工具调用的一句说明：取最能说明它在做什么的参数（不取填进表里的值）。 */
    static String summary(String tool, Map<String, Object> args) {
        String value = switch (tool) {
            case "web_search" -> string(args.get("query"));
            case "web_fetch", "browser_navigate" -> string(args.get("url"));
            case "add_lead" -> join(string(args.get("company")), string(args.get("title")));
            case "update_lead" -> join(string(args.get("job")), string(args.get("status")), string(args.get("verified")));
            case "start_application", "mark_submitted" -> string(args.get("job"));
            case "record_status" -> join(string(args.get("job")), string(args.get("phase")), string(args.get("outcome")));
            case "list_jobs" -> args.get("status") == null ? "全部" : string(args.get("status"));
            case "read_resume" -> string(args.get("key"));
            case "choose_resume" -> join(string(args.get("job")), string(args.get("asset")));
            case "read_skill" -> string(args.get("name"));
            case "ask_user" -> "交给用户";
            case "update_plan" -> "更新计划";
            case "finish" -> "停在提交前";
            case "browser_fill_form" -> args.get("fields") instanceof List<?> list ? "填写 " + list.size() + " 个字段" : null;
            case "browser_file_upload" -> "上传文件";
            case "browser_click", "browser_type", "browser_select_option" -> {
                String label = string(args.get("name"));
                yield label != null ? label : string(args.get("element"));
            }
            case "browser_find" -> string(args.get("text"));
            case "browser_press_key" -> string(args.get("key"));
            default -> null;
        };
        return value == null ? "" : value;
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        String line = text.strip().split("\n", 2)[0].strip();
        return line.length() > RESULT_LINE ? line.substring(0, RESULT_LINE) + "…" : line;
    }

    private static Map<String, Object> args(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = JSON.readValue(json, MAP);
            return parsed == null ? Map.of() : parsed;
        } catch (JacksonException e) {
            return Map.of();
        }
    }

    private static String string(Object value) {
        return value instanceof String text && !text.isBlank() ? text.strip() : null;
    }

    private static String join(String... parts) {
        List<String> kept = new ArrayList<>();
        for (String part : parts) {
            if (part != null) {
                kept.add(part);
            }
        }
        return kept.isEmpty() ? null : String.join(" · ", kept);
    }
}
