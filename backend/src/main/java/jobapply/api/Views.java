package jobapply.api;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jobapply.domain.AgentRun;
import jobapply.domain.Job;
import jobapply.domain.Phase;
import jobapply.domain.PrepState;
import jobapply.domain.ProjectSpec;
import jobapply.domain.SessionRecord;
import jobapply.persistence.SessionRepository;
import jobapply.workflow.ApplicationRunner;
import jobapply.workflow.ApplicationStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 界面上的只读视图（C005-ui「后端要补的只读接口」）：Job、Session、Project 的摘要，以及把一段对话
 * （{@link AgentRun.Turn}）折叠成消息流——工具结果并进前一条 Agent 消息，系统说明和页面不显示，
 * 工具调用只给名字、一句说明和结果的第一行（不给快照，也不给填进表里的值）。
 */
final class Views {

    private static final int RESULT_LINE = 160;
    private static final Set<Phase> APPLIED = EnumSet.of(Phase.APPLIED, Phase.SCREENING, Phase.ASSESSMENT,
            Phase.INTERVIEW, Phase.OFFER);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    record JobView(String id, String project, String company, String title, List<String> city, String type,
                   String url, String site, String source, String discoveredIn, String leadStatus, String skipReason,
                   String phase, String prepState, String label, String verified, String officialUrl, String note,
                   String application) {
    }

    record SessionSummary(String id, String project, String title, String createdAt, List<String> tags,
                          List<String> jobs, String handoff) {
    }

    record Counts(int leads, int shortlisted, int preparing, int waiting, int ready, int applied) {
    }

    record ProjectSummary(String id, String title, String status, List<SessionSummary> sessions, Counts counts) {
    }

    record ToolStep(String name, String summary, String result) {
    }

    /** 一条消息。{@code role} 是 {@code user} 或 {@code assistant}；用户的消息 {@code tools} 为空。 */
    record ChatMessage(String role, String text, List<ToolStep> tools, String at) {
    }

    private Views() {
    }

    static JobView job(Job job, ApplicationRunner applications) {
        String application = applications.statusIfAny(job.id()).map(ApplicationStatus::status).orElse(null);
        return new JobView(job.id(), job.project(), job.posting().company(), job.posting().title(),
                job.posting().city(), job.posting().type(), job.posting().url(), job.posting().site(),
                job.posting().source(), job.discoveredIn(), job.status().leadStatus().value(),
                job.status().skipReason(), job.status().phase() == null ? null : job.status().phase().value(),
                job.status().prepState() == null ? null : job.status().prepState().value(), job.status().label(),
                job.extraString("verified"), job.extraString("official_url"), job.extraString("lead_note"),
                application);
    }

    static SessionSummary session(SessionRecord s) {
        return new SessionSummary(s.id(), s.project(), s.title(), s.createdAt(), s.tags(), s.jobs(), s.handoff());
    }

    static ProjectSummary project(ProjectSpec spec, List<SessionRecord> sessions, List<JobView> jobs) {
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
            if (AgentRun.WAITING_USER.equals(job.application()) || AgentRun.FAILED.equals(job.application())) {
                waiting++;
            }
            if (PrepState.AWAITING_REVIEW.value().equals(job.prepState())) {
                ready++;
            }
            if (job.phase() != null && APPLIED.contains(Phase.of(job.phase()))) {
                applied++;
            }
        }
        return new ProjectSummary(spec.id(), spec.title(), spec.status(),
                sessions.stream().map(Views::session).toList(),
                new Counts(leads, shortlisted, preparing, waiting, ready, applied));
    }

    /** 申请线程的对话（没有时间）。 */
    static List<ChatMessage> messages(List<AgentRun.Turn> turns) {
        return timed(turns.stream().map(turn -> new SessionRepository.TimedTurn(null, turn)).toList());
    }

    /** 把对话折叠成消息流。 */
    static List<ChatMessage> timed(List<SessionRepository.TimedTurn> turns) {
        List<ChatMessage> out = new ArrayList<>();
        // 当前这条 Agent 消息：工具调用 id → 在 tools 里的位置
        Map<String, Integer> open = new LinkedHashMap<>();
        List<ToolStep> tools = null;
        for (SessionRepository.TimedTurn timed : turns) {
            AgentRun.Turn turn = timed.turn();
            switch (turn.role()) {
                case AgentRun.Turn.USER -> {
                    out.add(new ChatMessage(AgentRun.Turn.USER, turn.text(), List.of(), timed.at()));
                    tools = null;
                }
                case AgentRun.Turn.ASSISTANT -> {
                    tools = new ArrayList<>();
                    open.clear();
                    StringBuilder text = new StringBuilder(turn.text() == null ? "" : turn.text().strip());
                    for (AgentRun.ToolCall call : turn.calls()) {
                        Map<String, Object> args = args(call.arguments());
                        if ("ask_user".equals(call.name()) && args.get("message") instanceof String message) {
                            text.append(text.isEmpty() ? "" : "\n\n").append(message.strip());
                        }
                        open.put(call.id(), tools.size());
                        tools.add(new ToolStep(call.name(), summary(call.name(), args), null));
                    }
                    out.add(new ChatMessage(AgentRun.Turn.ASSISTANT, text.isEmpty() ? null : text.toString(), tools,
                            timed.at()));
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
                    }
                }
                default -> {
                    // note、page：只给模型看
                }
            }
        }
        return out;
    }

    /** 工具调用的一句说明：取最能说明它在做什么的参数（不取填进表里的值）。 */
    static String summary(String tool, Map<String, Object> args) {
        String value = switch (tool) {
            case "web_search" -> string(args.get("query"));
            case "web_fetch", "browser_navigate" -> string(args.get("url"));
            case "add_lead" -> join(string(args.get("company")), string(args.get("title")));
            case "update_lead" -> join(string(args.get("job")), string(args.get("status")), string(args.get("verified")));
            case "start_application" -> string(args.get("job"));
            case "list_jobs" -> args.get("status") == null ? "全部" : string(args.get("status"));
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
