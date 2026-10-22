package jobapply.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
            JobEvent.Type.CONVERTED, JobEvent.Type.PREP_STATE_CHANGED, JobEvent.Type.PHASE_CHANGED, JobEvent.Type.CLOSED);

    private final StorageLayout layout;
    private final Clock clock;
    private final StorageGit git;
    private final ProjectRepository projects;
    private final JobRepository jobs;
    private final EventLog eventLog = new EventLog();

    public SessionRepository(StorageLayout layout, Clock clock) {
        this.layout = layout;
        this.clock = clock;
        this.git = new StorageGit(layout, clock);
        this.projects = new ProjectRepository(layout, clock);
        this.jobs = new JobRepository(layout);
    }

    public synchronized SessionRecord create(String projectId, String title) {
        if (!projects.exists(projectId)) {
            throw new IllegalArgumentException("Project 不存在：" + projectId);
        }
        String id = IdSequence.next("s-" + LocalDate.now(clock) + "-", allSessionIds());
        SessionRecord session = new SessionRecord(id, projectId, title, Timestamps.now(clock), List.of(), List.of(), "");
        Path file = layout.sessionFile(projectId, id);
        FrontmatterFile.write(file, new FrontmatterFile.Document(
                toFrontmatter(session, Map.of()), "\n# " + title + "\n\n" + HANDOFF_HEADING + "\n"));
        git.commit("session " + id + "：新建", List.of(file));
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
                handoff(document.body()));
    }

    public void appendTranscript(String projectId, String sessionId, String role, String text) {
        requireFile(projectId, sessionId);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("at", Timestamps.now(clock));
        line.put("role", role);
        line.put("text", text);
        Path transcript = layout.transcript(projectId, sessionId);
        eventLog.append(transcript, line);
        git.commit("session " + sessionId + "：对话", List.of(transcript));
    }

    public List<Map<String, Object>> readTranscript(String projectId, String sessionId) {
        return eventLog.read(layout.transcript(projectId, sessionId));
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
        git.commit("session " + sessionId + "：交接摘要", List.of(file));
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
                orderedTags, new ArrayList<>(jobIds), current.handoff());
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

    private static List<String> strings(Object value) {
        return value instanceof List<?> list ? list.stream().map(Object::toString).toList() : List.of();
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }
}
