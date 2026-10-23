package jobapply.persistence;

import java.nio.file.Files;
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
import jobapply.domain.Outcome;
import jobapply.domain.Phase;
import jobapply.domain.PrepState;
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

    /** 投递以后默认多少天提醒一次去看状态（3–5 天取中间，C007）。 */
    public static final int DEFAULT_CHECK_DAYS = 4;

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
     * 改 job.md 里不属于状态的几个键（例如调研 Agent 记下的核验结果、官方链接、备注），然后重新生成台账。
     * 不改状态，不写事件。
     */
    public synchronized Job annotate(String projectId, String jobId, String sessionId, Map<String, Object> changes) {
        require(changes, "changes");
        requireProjectAndSession(projectId, sessionId);
        Job next = jobs.load(projectId, jobId).withExtra(changes);
        jobs.update(next);
        afterChange(projectId, sessionId);
        return next;
    }

    /**
     * 用户确认已经在浏览器里提交了（C007）：{@code awaiting_review → user_submitting → applied}，证据是用户的确认
     * （{@code user-reported}）。记下 {@code applied_at}，下一次查看定在 {@link #checkEveryDays} 天后。
     * 两步都先过状态机，再写文件。
     *
     * @param note 用户的原话；为空时记「用户确认已提交」
     */
    public synchronized Job markSubmitted(String projectId, String jobId, String sessionId, String note) {
        requireProjectAndSession(projectId, sessionId);
        Job current = jobs.load(projectId, jobId);
        String now = Timestamps.now(clock);
        String said = note == null || note.isBlank() ? "用户确认已提交" : note.strip();
        List<JobEvent> events = new ArrayList<>();
        JobStatus status = current.status();
        if (status.phase() == Phase.PREP && status.prepState() == PrepState.AWAITING_REVIEW) {
            JobStatus submitting = ApplicationStateMachine.next(status,
                    new Transition.ChangePrepState(PrepState.USER_SUBMITTING), Actor.USER);
            events.add(event(sessionId, Actor.USER, JobEvent.Type.PREP_STATE_CHANGED, status, submitting, said, null));
            status = submitting;
        }
        Evidence evidence = new Evidence(Evidence.USER_REPORTED, said, null, now, now);
        JobStatus applied = ApplicationStateMachine.next(status, new Transition.MarkApplied(evidence), Actor.USER);
        events.add(event(sessionId, Actor.USER, JobEvent.Type.PHASE_CHANGED, status, applied, said, evidence));
        Map<String, Object> times = new LinkedHashMap<>();
        times.put("applied_at", now);
        times.put("next_check_at", LocalDate.now(clock).plusDays(checkEveryDays(projectId)).toString());
        Job next = current.withStatus(applied).withExtra(times);

        jobs.update(next);
        events.forEach(e -> appendEvent(next, e));
        afterChange(projectId, sessionId);
        return next;
    }

    /**
     * 用户反馈了一个已投递申请的状态（C007）。{@code phase}、{@code outcome} 都为空时只是看过了、没有变化；
     * 有变化时走状态机（结束用 {@code outcome}）。更新 {@code last_checked_at}，{@code next_check_at} 推到
     * {@code nextCheckInDays} 天后（为空时用 {@link #checkEveryDays}）；申请结束时清空。原话进事件的 {@code reason}，
     * {@code actor} 是 user（结果来源是用户）。
     */
    public synchronized Job recordStatus(String projectId, String jobId, String sessionId, Phase phase,
            Outcome outcome, String note, Integer nextCheckInDays) {
        requireProjectAndSession(projectId, sessionId);
        if (nextCheckInDays != null && (nextCheckInDays < 1 || nextCheckInDays > 90)) {
            throw new IllegalArgumentException("next_check_in_days 要在 1 到 90 之间");
        }
        Job current = jobs.load(projectId, jobId);
        JobStatus status = current.status();
        if (status.phase() == null || status.phase() == Phase.PREP || status.phase() == Phase.CLOSED) {
            throw new IllegalArgumentException("只有已投递、还没结束的申请才能记反馈，" + jobId + " 现在是 " + status.label());
        }
        String said = note == null || note.isBlank() ? null : note.strip();
        JobStatus nextStatus = status;
        JobEvent.Type type = JobEvent.Type.STATUS_CHECKED;
        if (outcome != null) {
            nextStatus = ApplicationStateMachine.next(status, new Transition.Close(outcome, said), Actor.USER);
            type = JobEvent.Type.CLOSED;
        } else if (phase != null && phase != status.phase()) {
            nextStatus = ApplicationStateMachine.next(status, new Transition.ChangePhase(phase), Actor.USER);
            type = JobEvent.Type.PHASE_CHANGED;
        }
        Map<String, Object> times = new LinkedHashMap<>();
        times.put("last_checked_at", Timestamps.now(clock));
        times.put("next_check_at", nextStatus.phase() == Phase.CLOSED ? null : LocalDate.now(clock)
                .plusDays(nextCheckInDays != null ? nextCheckInDays : checkEveryDays(projectId)).toString());
        Job next = current.withStatus(nextStatus).withExtra(times);
        JobEvent event = event(sessionId, Actor.USER, type, status, nextStatus, said, null);

        jobs.update(next);
        appendEvent(next, event);
        afterChange(projectId, sessionId);
        return next;
    }

    /**
     * 多少天提醒一次去看投递状态：Project 的 {@code preferences.md} frontmatter 里的 {@code check_every_days}，
     * 没有或写错时用 {@link #DEFAULT_CHECK_DAYS}。
     */
    public int checkEveryDays(String projectId) {
        Path file = layout.preferences(projectId);
        if (Files.exists(file)) {
            Object value = FrontmatterFile.read(file).frontmatter().get("check_every_days");
            try {
                int days = value == null ? 0 : Integer.parseInt(value.toString().strip());
                if (days >= 1 && days <= 90) {
                    return days;
                }
            } catch (NumberFormatException e) {
                // 写错了就用默认值
            }
        }
        return DEFAULT_CHECK_DAYS;
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
