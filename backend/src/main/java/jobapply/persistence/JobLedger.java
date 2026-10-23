package jobapply.persistence;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jobapply.domain.Actor;
import jobapply.domain.ApplicationStateMachine;
import jobapply.domain.Evidence;
import jobapply.domain.Job;
import jobapply.domain.JobEvent;
import jobapply.domain.JobPosting;
import jobapply.domain.JobStatus;
import jobapply.domain.Timestamps;
import jobapply.domain.Transition;

/**
 * Job 写操作的唯一入口。一次调用是一个工作单元：
 * 校验参数并构造事件 → 状态机校验 → 写 job.md → 追加事件 → 更新 session.md → 重新生成台账。
 * 所有校验都在第一次写文件之前完成，校验失败时什么都不写。本段假定只有一个进程，同一实例内串行。
 */
public final class JobLedger {

    private final StorageLayout layout;
    private final Clock clock;
    private final JobRepository jobs;
    private final ProjectRepository projects;
    private final SessionRepository sessions;
    private final TrackerWriter tracker;
    private final EventLog eventLog = new EventLog();

    public JobLedger(StorageLayout layout, Clock clock) {
        this.layout = layout;
        this.clock = clock;
        this.jobs = new JobRepository(layout);
        this.projects = new ProjectRepository(layout, clock);
        this.sessions = new SessionRepository(layout, clock);
        this.tracker = new TrackerWriter(layout);
    }

    public synchronized Job addLead(String projectId, String sessionId, Actor actor, JobPosting posting,
            String jdSnapshot) {
        require(actor, "actor");
        require(posting, "posting");
        requireProjectAndSession(projectId, sessionId);
        String key = posting.dedupeKey();
        for (Job existing : jobs.list(projectId)) {
            if (existing.posting().dedupeKey().equals(key)) {
                throw new DuplicateLeadException(existing.id());
            }
        }
        String id = IdSequence.next(LocalDate.now(clock) + "-", jobs.allJobIds());
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("tags", List.of("jobapply/job"));
        Job job = new Job(id, projectId, sessionId, posting, JobStatus.newLead(), extra);
        JobEvent event = event(sessionId, actor, JobEvent.Type.LEAD_CREATED, null, job.status(), null, null);

        List<Path> written = new ArrayList<>();
        written.add(jobs.create(job, jdSnapshot));
        written.add(appendEvent(job, event));
        written.addAll(afterChange(projectId, sessionId));
        return job;
    }

    public synchronized Job transition(String projectId, String jobId, String sessionId, Actor actor,
            Transition transition) {
        require(actor, "actor");
        require(transition, "transition");
        requireProjectAndSession(projectId, sessionId);
        Job current = jobs.load(projectId, jobId);
        JobStatus nextStatus = ApplicationStateMachine.next(current.status(), transition, actor);
        Job next = current.withStatus(nextStatus);
        JobEvent event = event(sessionId, actor, eventType(transition), current.status(), nextStatus,
                reason(transition), evidence(transition));

        List<Path> written = new ArrayList<>();
        written.add(jobs.update(next));
        written.add(appendEvent(next, event));
        written.addAll(afterChange(projectId, sessionId));
        return next;
    }

    /**
     * 只追加一条 {@code login_override} 审计事件（from / to 都是当前状态），不改 job.md。
     */
    public synchronized void recordLoginOverride(String projectId, String jobId, String sessionId, Actor actor,
            String reason) {
        require(actor, "actor");
        requireProjectAndSession(projectId, sessionId);
        Job current = jobs.load(projectId, jobId);
        JobEvent event = event(sessionId, actor, JobEvent.Type.LOGIN_OVERRIDE, current.status(), current.status(),
                reason, null);

        List<Path> written = new ArrayList<>();
        written.add(appendEvent(current, event));
        written.addAll(afterChange(projectId, sessionId));
    }

    public List<JobEvent> events(String projectId, String jobId) {
        return eventLog.readJobEvents(layout.jobEvents(projectId, jobId));
    }

    private void requireProjectAndSession(String projectId, String sessionId) {
        if (!projects.exists(projectId)) {
            throw new IllegalArgumentException("Project 不存在：" + projectId);
        }
        if (!sessions.exists(projectId, sessionId)) {
            throw new IllegalArgumentException("Session 不存在：" + projectId + "/" + sessionId);
        }
    }

    private JobEvent event(String sessionId, Actor actor, JobEvent.Type type, JobStatus from, JobStatus to,
            String reason, Evidence evidence) {
        return new JobEvent(Timestamps.now(clock), sessionId, actor, type,
                from == null ? null : from.label(), to.label(), reason, evidence);
    }

    private Path appendEvent(Job job, JobEvent event) {
        Path file = layout.jobEvents(job.project(), job.id());
        eventLog.appendJobEvent(file, event);
        return file;
    }

    private static void require(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }

    private List<Path> afterChange(String projectId, String sessionId) {
        List<Path> written = new ArrayList<>();
        written.add(sessions.refreshDerived(projectId, sessionId));
        written.addAll(tracker.writeProject(projectId));
        written.addAll(tracker.writeGlobal());
        return written;
    }

    private static JobEvent.Type eventType(Transition transition) {
        return switch (transition) {
            case Transition.MarkLead mark -> JobEvent.Type.LEAD_STATUS_CHANGED;
            case Transition.Convert convert -> JobEvent.Type.CONVERTED;
            case Transition.ChangePrepState change -> JobEvent.Type.PREP_STATE_CHANGED;
            case Transition.MarkApplied applied -> JobEvent.Type.PHASE_CHANGED;
            case Transition.ChangePhase change -> JobEvent.Type.PHASE_CHANGED;
            case Transition.Close close -> JobEvent.Type.CLOSED;
        };
    }

    private static String reason(Transition transition) {
        return switch (transition) {
            case Transition.MarkLead mark -> mark.reason();
            case Transition.ChangePrepState change -> change.reason();
            case Transition.Close close -> close.reason();
            default -> null;
        };
    }

    private static Evidence evidence(Transition transition) {
        return transition instanceof Transition.MarkApplied applied ? applied.evidence() : null;
    }
}
