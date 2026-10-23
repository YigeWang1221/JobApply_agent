package jobapply.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jobapply.domain.AgentRun;
import jobapply.domain.Job;
import jobapply.domain.JobEvent;
import jobapply.domain.SessionRecord;
import jobapply.domain.Timestamps;

/**
 * 读写 {@code sessions/<session-id>/}：{@code session.md} 与 {@code transcript.jsonl}。
 * 自动标签和涉及的 job 列表由本 Session 的事件推导，不手工维护。
 */
public final class SessionRepository {

    static final String TAG = "jobapply/session";
    static final String RESEARCH = "research";
    static final String APPLY = "apply";
    private static final String HANDOFF_HEADING = "## 交接摘要";
    private static final Set<JobEvent.Type> APPLY_EVENTS = EnumSet.of(
            JobEvent.Type.CONVERTED, JobEvent.Type.PREP_STATE_CHANGED, JobEvent.Type.PHASE_CHANGED, JobEvent.Type.CLOSED,
            JobEvent.Type.LOGIN_OVERRIDE);

    private final StorageLayout layout;
    private final Clock clock;
    private final ProjectRepository projects;
    private final JobRepository jobs;
    private final EventLog eventLog = new EventLog();

    public SessionRepository(StorageLayout layout, Clock clock) {
        this.layout = layout;
        this.clock = clock;
        this.projects = new ProjectRepository(layout, clock);
        this.jobs = new JobRepository(layout);
    }

    public synchronized SessionRecord create(String projectId, String title) {
        if (!projects.exists(projectId)) {
            throw new IllegalArgumentException("Project 不存在：" + projectId);
        }
        String id = IdSequence.next("s-" + LocalDate.now(clock) + "-", allSessionIds());
        SessionRecord session = new SessionRecord(id, projectId, title, Timestamps.now(clock), List.of(), List.of(), "", null,
                null);
        Path file = layout.sessionFile(projectId, id);
        FrontmatterFile.write(file, new FrontmatterFile.Document(
                toFrontmatter(session, Map.of()), "\n# " + title + "\n\n" + HANDOFF_HEADING + "\n"));
        return session;
    }

    public boolean exists(String projectId, String sessionId) {
        return Files.exists(layout.sessionFile(projectId, sessionId));
    }

    public SessionRecord load(String projectId, String sessionId) {
        Path file = requireFile(projectId, sessionId);
        FrontmatterFile.Document document = FrontmatterFile.read(file);
        Map<String, Object> fm = document.frontmatter();
        List<String> tags = strings(fm.get("tags")).stream().filter(tag -> !tag.equals(TAG)).toList();
        return new SessionRecord(
                string(fm.get("id")),
                string(fm.get("project")),
                string(fm.get("title")),
                string(fm.get("created_at")),
                tags,
                strings(fm.get("jobs")),
                handoff(document.body()),
                string(fm.get("last_status")),
                string(fm.get("last_error")));
    }

    public void appendTranscript(String projectId, String sessionId, String role, String text) {
        requireFile(projectId, sessionId);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("at", Timestamps.now(clock));
        line.put("role", role);
        line.put("text", text);
        Path transcript = layout.transcript(projectId, sessionId);
        eventLog.append(transcript, line);
    }

    public List<Map<String, Object>> readTranscript(String projectId, String sessionId) {
        return eventLog.read(layout.transcript(projectId, sessionId));
    }

    /** 本 Project 的全部 Session，最新的在前（按创建时间，再按 id）。 */
    public List<SessionRecord> list(String projectId) {
        List<SessionRecord> sessions = new ArrayList<>();
        for (String id : layout.sessionIds(projectId)) {
            if (exists(projectId, id)) {
                sessions.add(load(projectId, id));
            }
        }
        sessions.sort(Comparator.comparing((SessionRecord s) -> s.createdAt() == null ? "" : s.createdAt())
                .thenComparing(SessionRecord::id).reversed());
        return sessions;
    }

    /**
     * 对话 Agent 的一条对话（C005）：{@code transcript.jsonl} 里一行一条 {@link AgentRun.Turn}，
     * 带 {@code at}、{@code role}、{@code text}，以及工具调用 {@code calls} 和结果 {@code results}。
     */
    public void appendTurn(String projectId, String sessionId, AgentRun.Turn turn) {
        requireFile(projectId, sessionId);
        eventLog.append(layout.transcript(projectId, sessionId), line(Timestamps.now(clock), turn));
    }

    /** 整个改写对话（重新生成时去掉上一次回答的文字，C006-agent）。每一条保留原来的时间。 */
    public synchronized void rewriteTurns(String projectId, String sessionId, List<TimedTurn> turns) {
        requireFile(projectId, sessionId);
        eventLog.rewrite(layout.transcript(projectId, sessionId),
                turns.stream().map(timed -> line(timed.at(), timed.turn())).toList());
    }

    /** 记下对话 Agent 这一次运行的状态和错误（{@code session.md} 的 {@code last_status}、{@code last_error}）。 */
    public synchronized void writeStatus(String projectId, String sessionId, String status, String error) {
        Path file = requireFile(projectId, sessionId);
        FrontmatterFile.Document document = FrontmatterFile.read(file);
        Map<String, Object> fm = new LinkedHashMap<>(document.frontmatter());
        fm.put("last_status", status);
        if (error == null) {
            fm.remove("last_error");
        } else {
            fm.put("last_error", error);
        }
        FrontmatterFile.write(file, new FrontmatterFile.Document(fm, document.body()));
    }

    static final int TITLE_MAX = 80;

    /** 用户改的标题：空白合成一个空格，不能为空，不超过 {@link #TITLE_MAX} 个字。 */
    public static String checkTitle(String title) {
        String flat = title == null ? "" : title.strip().replaceAll("\\s+", " ");
        if (flat.isEmpty()) {
            throw new IllegalArgumentException("标题不能为空");
        }
        if (flat.codePointCount(0, flat.length()) > TITLE_MAX) {
            throw new IllegalArgumentException("标题不能超过 " + TITLE_MAX + " 个字");
        }
        return flat;
    }

    /** 改标题（frontmatter 和正文第一行的标题）。 */
    public synchronized void rename(String projectId, String sessionId, String title) {
        Path file = requireFile(projectId, sessionId);
        FrontmatterFile.Document document = FrontmatterFile.read(file);
        Map<String, Object> fm = new LinkedHashMap<>(document.frontmatter());
        String old = string(fm.get("title"));
        fm.put("title", title);
        String body = document.body();
        String heading = "# " + old + "\n";
        int at = old == null ? -1 : body.indexOf(heading);
        if (at >= 0) {
            body = body.substring(0, at) + "# " + title + "\n" + body.substring(at + heading.length());
        }
        FrontmatterFile.write(file, new FrontmatterFile.Document(fm, body));
    }

    private static Map<String, Object> line(String at, AgentRun.Turn turn) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("at", at);
        line.put("role", turn.role());
        line.put("text", turn.text());
        if (!turn.calls().isEmpty()) {
            line.put("calls", turn.calls().stream().map(call -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", call.id());
                item.put("name", call.name());
                item.put("arguments", call.arguments());
                return item;
            }).toList());
        }
        if (!turn.results().isEmpty()) {
            line.put("results", turn.results().stream().map(result -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", result.id());
                item.put("name", result.name());
                item.put("text", result.text());
                if (result.status() != null) {
                    item.put("status", result.status());
                    item.put("started_at", result.startedAt());
                    item.put("ms", result.ms());
                }
                return item;
            }).toList());
        }
        if (turn.thinking() != null) {
            line.put("thinking", turn.thinking());
        }
        if (!turn.attachments().isEmpty()) {
            line.put("attachments", turn.attachments().stream().map(attachment -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", attachment.id());
                item.put("name", attachment.name());
                item.put("content_type", attachment.contentType());
                return item;
            }).toList());
        }
        return line;
    }

    /** 一条对话和它写下的时间。 */
    public record TimedTurn(String at, AgentRun.Turn turn) {
    }

    /** 读回全部对话。只有 {@code role} 和 {@code text} 的旧行照样读成一条。 */
    public List<TimedTurn> readTurns(String projectId, String sessionId) {
        List<TimedTurn> turns = new ArrayList<>();
        for (Map<String, Object> line : readTranscript(projectId, sessionId)) {
            List<AgentRun.ToolCall> calls = new ArrayList<>();
            for (Map<?, ?> item : maps(line.get("calls"))) {
                calls.add(new AgentRun.ToolCall(string(item.get("id")), string(item.get("name")),
                        string(item.get("arguments"))));
            }
            List<AgentRun.ToolResult> results = new ArrayList<>();
            for (Map<?, ?> item : maps(line.get("results"))) {
                results.add(new AgentRun.ToolResult(string(item.get("id")), string(item.get("name")),
                        string(item.get("text")), string(item.get("status")), string(item.get("started_at")),
                        item.get("ms") instanceof Number ms ? ms.longValue() : null));
            }
            List<AgentRun.Attachment> attachments = new ArrayList<>();
            for (Map<?, ?> item : maps(line.get("attachments"))) {
                attachments.add(new AgentRun.Attachment(string(item.get("id")), string(item.get("name")),
                        string(item.get("content_type")), null));
            }
            String role = string(line.get("role"));
            turns.add(new TimedTurn(string(line.get("at")),
                    new AgentRun.Turn(role == null ? AgentRun.Turn.USER : role, string(line.get("text")), calls,
                            results, string(line.get("thinking")), attachments)));
        }
        return turns;
    }

    /** 写入交接摘要：替换「## 交接摘要」之后的全部内容。 */
    public void writeHandoff(String projectId, String sessionId, String summary) {
        Path file = requireFile(projectId, sessionId);
        FrontmatterFile.Document document = FrontmatterFile.read(file);
        String body = document.body();
        int heading = body.indexOf(HANDOFF_HEADING);
        String head = heading < 0 ? body + (body.endsWith("\n") ? "\n" : "\n\n") : body.substring(0, heading);
        String next = head + HANDOFF_HEADING + "\n\n" + summary.strip() + "\n";
        FrontmatterFile.write(file, new FrontmatterFile.Document(document.frontmatter(), next));
    }

    /**
     * 按本 Project 所有 Job 的事件重新推导 tags 与 jobs，写回 session.md，不提交。返回写过的文件。
     */
    Path refreshDerived(String projectId, String sessionId) {
        Path file = requireFile(projectId, sessionId);
        Set<String> tags = new LinkedHashSet<>();
        Set<String> jobIds = new LinkedHashSet<>();
        for (Job job : jobs.list(projectId)) {
            for (JobEvent event : eventLog.readJobEvents(layout.jobEvents(projectId, job.id()))) {
                if (!sessionId.equals(event.session())) {
                    continue;
                }
                jobIds.add(job.id());
                if (event.type() == JobEvent.Type.LEAD_CREATED) {
                    tags.add(RESEARCH);
                }
                if (APPLY_EVENTS.contains(event.type())) {
                    tags.add(APPLY);
                }
            }
        }
        List<String> orderedTags = new ArrayList<>();
        for (String tag : List.of(RESEARCH, APPLY)) {
            if (tags.contains(tag)) {
                orderedTags.add(tag);
            }
        }
        FrontmatterFile.Document document = FrontmatterFile.read(file);
        SessionRecord current = load(projectId, sessionId);
        SessionRecord next = new SessionRecord(current.id(), current.project(), current.title(), current.createdAt(),
                orderedTags, new ArrayList<>(jobIds), current.handoff(), current.lastStatus(), current.lastError());
        FrontmatterFile.write(file, new FrontmatterFile.Document(toFrontmatter(next, document.frontmatter()), document.body()));
        return file;
    }

    private List<String> allSessionIds() {
        List<String> ids = new ArrayList<>();
        for (String projectId : layout.projectIds()) {
            ids.addAll(layout.sessionIds(projectId));
        }
        return ids;
    }

    private Path requireFile(String projectId, String sessionId) {
        Path file = layout.sessionFile(projectId, sessionId);
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("Session 不存在：" + projectId + "/" + sessionId);
        }
        return file;
    }

    private static Map<String, Object> toFrontmatter(SessionRecord session, Map<String, Object> existing) {
        List<String> tags = new ArrayList<>();
        tags.add(TAG);
        tags.addAll(session.tags());
        Map<String, Object> fm = new LinkedHashMap<>(existing);
        fm.put("id", session.id());
        fm.put("project", session.project());
        fm.put("title", session.title());
        fm.put("created_at", session.createdAt());
        fm.put("tags", tags);
        fm.put("jobs", new ArrayList<>(session.jobs()));
        return fm;
    }

    private static String handoff(String body) {
        int heading = body.indexOf(HANDOFF_HEADING);
        return heading < 0 ? "" : body.substring(heading + HANDOFF_HEADING.length()).strip();
    }

    private static List<Map<?, ?>> maps(Object value) {
        List<Map<?, ?>> maps = new ArrayList<>();
        if (value instanceof List<?> list) {
            list.forEach(item -> {
                if (item instanceof Map<?, ?> map) {
                    maps.add(map);
                }
            });
        }
        return maps;
    }

    private static List<String> strings(Object value) {
        return value instanceof List<?> list ? list.stream().map(Object::toString).toList() : List.of();
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }
}
