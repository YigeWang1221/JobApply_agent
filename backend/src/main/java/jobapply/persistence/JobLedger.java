package jobapply.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import jobapply.domain.HiringRound;
import jobapply.domain.Timestamps;
import jobapply.domain.Transition;

/**
 * Job 写操作的唯一入口。一次调用是一个工作单元：
 * 校验参数并构造事件 → 状态机校验 → 写 job.md → 追加事件 → 更新 session.md → 重新生成台账。
 * 所有校验都在第一次写文件之前完成，校验失败时什么都不写。假定只有一个进程；进程里所有实例的写操作都在
 * {@link StorageLock} 这一把锁里串行（C006-fix：各处各自 new 的实例不再各锁各的）。
 */
public final class JobLedger {

    private final StorageLayout layout;
    private final Clock clock;
    private final JobRepository jobs;
    private final ProjectRepository projects;
    private final SessionRepository sessions;
    private final TrackerWriter tracker;
    private final EventLog eventLog = new EventLog();
    private final Listener listener;
    /** 这个线程在锁里写完、还没通知的变化（{@link #publish}）。 */
    private final ThreadLocal<List<Change>> pending = ThreadLocal.withInitial(ArrayList::new);

    /** 投递以后默认多少天提醒一次去看状态（3–5 天取中间，C007）。 */
    public static final int DEFAULT_CHECK_DAYS = 4;

    /** 每次写完台账（文件都写好以后）收到是哪个 Session、哪个 Job（C006-fix：事件日志的 {@code changed}）。 */
    public interface Listener {
        Listener NONE = (session, job) -> {
        };

        void changed(String session, String job);
    }

    public JobLedger(StorageLayout layout, Clock clock) {
        this(layout, clock, Listener.NONE);
    }

    public JobLedger(StorageLayout layout, Clock clock, Listener listener) {
        this.listener = listener == null ? Listener.NONE : listener;
        this.layout = layout;
        this.clock = clock;
        this.jobs = new JobRepository(layout);
        this.projects = new ProjectRepository(layout, clock);
        this.sessions = new SessionRepository(layout, clock);
        this.tracker = new TrackerWriter(layout, clock);
    }

    public Job addLead(String projectId, String sessionId, Actor actor, JobPosting posting, String jdSnapshot) {
        try {
            synchronized (StorageLock.WRITES) {
                require(actor, "actor");
                require(posting, "posting");
                requireProjectAndLiveSession(projectId, sessionId);
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

                jobs.create(job, jdSnapshot);
                appendEvent(job, event);
                afterChange(projectId, sessionId, id);
                return job;
            }
        } finally {
            publish();
        }
    }

    public Job transition(String projectId, String jobId, String sessionId, Actor actor, Transition transition) {
        try {
            synchronized (StorageLock.WRITES) {
                require(actor, "actor");
                require(transition, "transition");
                requireProjectAndSession(projectId, sessionId);
                Job current = jobs.load(projectId, jobId);
                JobStatus nextStatus = ApplicationStateMachine.next(current.status(), transition, actor);
                Job next = current.withStatus(nextStatus);
                if (transition instanceof Transition.Close close) {
                    next = next.withExtra(closedExtra(close.reason()));
                }
                JobEvent event = event(sessionId, actor, eventType(transition), current.status(), nextStatus,
                        reason(transition), evidence(transition));

                jobs.update(next);
                appendEvent(next, event);
                afterChange(projectId, sessionId, jobId);
                return next;
            }
        } finally {
            publish();
        }
    }

    /**
     * Agent 的进度推进 {@code prep_state}（C006-fix 第 3 节）：读状态、判断、写在同一把锁里，不会和用户同时说的事实
     * 打架。状态已经由用户定下（{@link #userDecided}）或已经是目标时什么都不写，原样返回。
     */
    public Job advancePrep(String projectId, String jobId, String sessionId, Actor actor, PrepState target,
            String reason) {
        try {
            synchronized (StorageLock.WRITES) {
                Job current = jobs.load(projectId, jobId);
                if (userDecided(current.status(), actor) || current.status().prepState() == target) {
                    return current;
                }
                return transition(projectId, jobId, sessionId, actor, new Transition.ChangePrepState(target, reason));
            }
        } finally {
            publish();
        }
    }

    /**
     * 状态已经由用户定下：结束了、已投递，或者已提交（user_submitting 等）、先放着（paused）。
     * {@code actor} 是用户时（回复、重新开始）先放着的不算，用户自己可以接着做。
     */
    public static boolean userDecided(JobStatus status, Actor actor) {
        if (status.phase() != Phase.PREP) {
            return true;
        }
        PrepState prep = status.prepState();
        return prep == PrepState.USER_SUBMITTING || prep == PrepState.VERIFICATION_PENDING
                || prep == PrepState.UNCONFIRMED || prep == PrepState.PAUSED && actor != Actor.USER;
    }

    /**
     * 改 job.md 里不属于状态的几个键（例如调研 Agent 记下的核验结果、官方链接、备注），然后重新生成台账。
     * 不改状态，不写事件。
     */
    public Job annotate(String projectId, String jobId, String sessionId, Map<String, Object> changes) {
        try {
            synchronized (StorageLock.WRITES) {
                require(changes, "changes");
                requireProjectAndSession(projectId, sessionId);
                Job next = jobs.load(projectId, jobId).withExtra(changes);
                jobs.update(next);
                afterChange(projectId, sessionId, jobId);
                return next;
            }
        } finally {
            publish();
        }
    }

    /**
     * 用户确认已经在浏览器里提交了（C007）：准备中的任何状态（停在提交前、先放着、等回答……）{@code → user_submitting
     * → applied}；已经在 user_submitting / verification_pending / unconfirmed 的直接进 applied。证据是用户的确认
     * （{@code user-reported}）。记下 {@code applied_at}，下一次查看定在 {@link #checkEveryDays} 天后。
     * 还在候选池（new / shortlisted、没开始申请就自己投了）的先转为申请。几步都先过状态机，再写文件。
     * <p>
     * 可重放（C006-fix 第 4 节）：已经投递过的（applied 及以后）原样返回，不写文件、不记事件，{@code applied_at}
     * 还是第一次记下的。
     *
     * @param note 用户的原话；为空时记「用户确认已提交」
     */
    public Job markSubmitted(String projectId, String jobId, String sessionId, String note) {
        try {
            synchronized (StorageLock.WRITES) {
                return markSubmittedLocked(projectId, jobId, sessionId, note);
            }
        } finally {
            publish();
        }
    }

    private Job markSubmittedLocked(String projectId, String jobId, String sessionId, String note) {
        requireProjectAndSession(projectId, sessionId);
        Job current = jobs.load(projectId, jobId);
        if (alreadyApplied(current.status())) {
            return current;
        }
        String now = Timestamps.now(clock);
        String said = note == null || note.isBlank() ? "用户确认已提交" : note.strip();
        List<JobEvent> events = new ArrayList<>();
        JobStatus status = current.status();
        if (status.phase() == null) {
            JobStatus converted = ApplicationStateMachine.next(status, new Transition.Convert(), Actor.USER);
            events.add(event(sessionId, Actor.USER, JobEvent.Type.CONVERTED, status, converted, said, null));
            status = converted;
        }
        if (status.phase() == Phase.PREP && status.prepState() != PrepState.USER_SUBMITTING
                && status.prepState() != PrepState.VERIFICATION_PENDING && status.prepState() != PrepState.UNCONFIRMED) {
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
        afterChange(projectId, sessionId, jobId);
        return next;
    }

    /** 已经投递、还没结束：applied 或之后的阶段（screening …… offer）。 */
    public static boolean alreadyApplied(JobStatus status) {
        Phase phase = status.phase();
        return phase != null && phase != Phase.PREP && phase != Phase.CLOSED;
    }

    /**
     * 用户反馈了一个已投递申请的状态（C007）。{@code phase}、{@code outcome} 都为空时只是看过了、没有变化；
     * 有变化时走状态机（结束用 {@code outcome}）。更新 {@code last_checked_at}，{@code next_check_at} 推到
     * {@code nextCheckInDays} 天后（为空时用 {@link #checkEveryDays}）；申请结束时清空。原话进事件的 {@code reason}，
     * {@code actor} 是 user（结果来源是用户）。
     * <p>
     * 用户说的事实优先（C006-fix 第 3 节）：还在准备中的申请也可以直接结束（岗位下线 {@code posting_closed}、不投了
     * {@code not_submitted}，其它结果按状态机）。可重放（第 4 节）：已经以同一个 {@code outcome} 结束的、已经在这个
     * {@code phase} 且上一条事件就是同一句话的，原样返回，不写文件、不记事件。只是看过了（没有 phase / outcome）每次都记。
     */
    public Job recordStatus(String projectId, String jobId, String sessionId, Phase phase, Outcome outcome,
            String note, Integer nextCheckInDays) {
        try {
            synchronized (StorageLock.WRITES) {
                return recordStatusLocked(projectId, jobId, sessionId, phase, outcome, note, nextCheckInDays);
            }
        } finally {
            publish();
        }
    }

    private Job recordStatusLocked(String projectId, String jobId, String sessionId, Phase phase, Outcome outcome,
            String note, Integer nextCheckInDays) {
        requireProjectAndSession(projectId, sessionId);
        if (nextCheckInDays != null && (nextCheckInDays < 1 || nextCheckInDays > 90)) {
            throw new IllegalArgumentException("next_check_in_days 要在 1 到 90 之间");
        }
        Job current = jobs.load(projectId, jobId);
        JobStatus status = current.status();
        String said = note == null || note.isBlank() ? null : note.strip();
        if (outcome != null && status.phase() == Phase.CLOSED && status.outcome() == outcome) {
            return current;
        }
        if (outcome == null && phase != null && phase == status.phase()) {
            List<JobEvent> past = events(projectId, jobId);
            JobEvent last = past.isEmpty() ? null : past.getLast();
            if (last != null && status.label().equals(last.to()) && Objects.equals(said, last.reason())) {
                return current;
            }
        }
        boolean closingPrep = status.phase() == Phase.PREP && outcome != null;
        if (!closingPrep && (status.phase() == null || status.phase() == Phase.PREP || status.phase() == Phase.CLOSED)) {
            throw new IllegalArgumentException("只有已投递、还没结束的申请才能记反馈（准备中的只能直接结束：outcome 写 "
                    + "posting_closed 或 not_submitted），" + jobId + " 现在是 " + status.label());
        }
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
        if (outcome != null) {
            times.putAll(closedExtra(said));
        }
        times.put("last_checked_at", Timestamps.now(clock));
        times.put("next_check_at", nextStatus.phase() == Phase.CLOSED ? null : LocalDate.now(clock)
                .plusDays(nextCheckInDays != null ? nextCheckInDays : checkEveryDays(projectId)).toString());
        Job next = current.withStatus(nextStatus).withExtra(times);
        JobEvent event = event(sessionId, Actor.USER, type, status, nextStatus, said, null);

        jobs.update(next);
        appendEvent(next, event);
        afterChange(projectId, sessionId, jobId);
        return next;
    }

    /**
     * 用户记下「投递失败 / 不投了」（C024）：任何还没结束的阶段都可以（候选 new / shortlisted 先转为申请），原因必填，
     * 写进 job.md 的 {@code outcome_reason}，{@code closed_at} 记时间，{@code reopen_to} 记下记之前的状态（撤销时回到它），
     * 不再提醒查看。几步都先过状态机，再写文件。已经以同一个结果结束的原样返回（可重放）。
     *
     * @param outcome {@link Outcome#APPLY_FAILED} 或 {@link Outcome#GAVE_UP}
     * @param reason  用户写的原因（原话）
     */
    public Job recordOutcome(String projectId, String jobId, String sessionId, Outcome outcome, String reason) {
        try {
            synchronized (StorageLock.WRITES) {
                require(outcome, "outcome");
                if (!outcome.userMarked()) {
                    throw new IllegalArgumentException("这里只记 apply_failed（投递失败）或 gave_up（不投了）");
                }
                if (reason == null || reason.isBlank()) {
                    throw new IllegalArgumentException("要写原因（手动写一句就行）");
                }
                requireProjectAndSession(projectId, sessionId);
                Job current = jobs.load(projectId, jobId);
                JobStatus status = current.status();
                if (status.phase() == Phase.CLOSED && status.outcome() == outcome) {
                    return current;
                }
                String said = reason.strip();
                String before = status.label();
                List<JobEvent> events = new ArrayList<>();
                if (status.phase() == null) {
                    JobStatus converted = ApplicationStateMachine.next(status, new Transition.Convert(), Actor.USER);
                    events.add(event(sessionId, Actor.USER, JobEvent.Type.CONVERTED, status, converted, said, null));
                    status = converted;
                }
                JobStatus closed = ApplicationStateMachine.next(status, new Transition.Close(outcome, said), Actor.USER);
                events.add(event(sessionId, Actor.USER, JobEvent.Type.CLOSED, status, closed, said, null));
                Map<String, Object> changes = new LinkedHashMap<>(closedExtra(said));
                changes.put("reopen_to", before);
                changes.put("next_check_at", null);
                Job next = current.withStatus(closed).withExtra(changes);

                jobs.update(next);
                events.forEach(e -> appendEvent(next, e));
                afterChange(projectId, sessionId, jobId);
                return next;
            }
        } finally {
            publish();
        }
    }

    /**
     * 撤销用户记的「投递失败 / 不投了」（C024）：回到 {@code reopen_to} 记下的状态，清掉 {@code outcome_reason}、
     * {@code closed_at}、{@code reopen_to}；回到投递后的阶段时重新排下一次查看。记一条 {@code reopened} 事件。
     */
    public Job reopen(String projectId, String jobId, String sessionId) {
        try {
            synchronized (StorageLock.WRITES) {
                requireProjectAndSession(projectId, sessionId);
                Job current = jobs.load(projectId, jobId);
                JobStatus status = current.status();
                if (status.phase() != Phase.CLOSED || !status.outcome().userMarked()
                        || current.extraString("reopen_to") == null) {
                    throw new IllegalArgumentException("只有在工作台里记的「投递失败 / 不投了」能撤销，" + jobId + " 现在是 "
                            + status.label());
                }
                String was = current.extraString("outcome_reason");
                String said = "撤销「" + status.outcome().userLabel() + "」" + (was == null ? "" : "（原因：" + was + "）");
                JobStatus reopened = ApplicationStateMachine.next(status,
                        new Transition.Reopen(current.extraString("reopen_to"), said), Actor.USER);
                Map<String, Object> changes = new LinkedHashMap<>();
                changes.put("outcome_reason", null);
                changes.put("closed_at", null);
                changes.put("reopen_to", null);
                if (alreadyApplied(reopened)) {
                    changes.put("next_check_at", LocalDate.now(clock).plusDays(checkEveryDays(projectId)).toString());
                }
                Job next = current.withStatus(reopened).withExtra(changes);
                JobEvent event = event(sessionId, Actor.USER, JobEvent.Type.REOPENED, status, reopened, said, null);

                jobs.update(next);
                appendEvent(next, event);
                afterChange(projectId, sessionId, jobId);
                return next;
            }
        } finally {
            publish();
        }
    }

    /** 能不能撤销（C024）：用户记的「投递失败 / 不投了」，并且记下了之前的状态。 */
    public static boolean canReopen(Job job) {
        Outcome outcome = job.status().outcome();
        return outcome != null && outcome.userMarked() && job.extraString("reopen_to") != null;
    }

    /** 记下结果后写给 Session 的系统通知（C024）。 */
    public static String outcomeNote(Job job) {
        return name(job) + "：记为" + job.status().outcome().userLabel() + "。原因：" + job.extraString("outcome_reason")
                + "（在工作台里可以撤销）";
    }

    /** 撤销后写给 Session 的系统通知（C024）。 */
    public static String reopenNote(Job job, Outcome was) {
        return name(job) + "：撤销了「" + was.userLabel() + "」，回到 " + job.status().label() + "。";
    }

    private static String name(Job job) {
        return job.posting().company() + " · " + job.posting().title() + "（" + job.id() + "）";
    }

    /** 结束时写进 job.md 的原因和时间（C024：所有结束都写，原因为空时只写时间）。 */
    private Map<String, Object> closedExtra(String reason) {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("outcome_reason", reason == null || reason.isBlank() ? null : reason.strip());
        changes.put("closed_at", Timestamps.now(clock));
        return changes;
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
    public void recordLoginOverride(String projectId, String jobId, String sessionId, Actor actor, String reason) {
        try {
            synchronized (StorageLock.WRITES) {
                require(actor, "actor");
                requireProjectAndSession(projectId, sessionId);
                Job current = jobs.load(projectId, jobId);
                JobEvent event = event(sessionId, actor, JobEvent.Type.LOGIN_OVERRIDE, current.status(), current.status(),
                        reason, null);
                appendEvent(current, event);
                afterChange(projectId, sessionId, jobId);
            }
        } finally {
            publish();
        }
    }

    /**
     * 记下这个岗位的简历排名（C008）：{@code choice} 整个写进 job.md 的 {@code resume_choice}（覆盖上一次），
     * 事件日志记一条 {@code resume_chosen}（from / to 都是当前状态），{@code reason} 是一句摘要。不改状态。
     */
    public Job chooseResume(String projectId, String jobId, String sessionId, Map<String, Object> choice, String reason) {
        try {
            synchronized (StorageLock.WRITES) {
                require(choice, "choice");
                requireProjectAndSession(projectId, sessionId);
                Job next = jobs.load(projectId, jobId).withExtra(Map.of("resume_choice", choice));
                jobs.update(next);
                appendEvent(next, event(sessionId, Actor.AGENT, JobEvent.Type.RESUME_CHOSEN, next.status(),
                        next.status(), reason, null));
                afterChange(projectId, sessionId, jobId);
                return next;
            }
        } finally {
            publish();
        }
    }

    /**
     * 记下或更新一轮（C014-A，tracking.md「轮次」）。{@code fields} 用 job.md 里的键（name、type、scheduled_at、
     * happened_at、format、interviewer_role、result、result_source），为 null 的不改。{@code seq} 已有时只覆盖给了的字段
     * （例如只给 result），没有时新加一轮；{@code seq} 为空时加在最后。
     * <p>
     * 阶段由轮次推出（{@link HiringRound#phaseOf}）：最新一轮 failed 时以 rejected 结束；已经在 offer 的不退回；已经结束的只改
     * 轮次、不改状态。结果从 pending 变了时记 {@code result_at}，之前已经算作 unknown 的在事件里写明。{@code next_event}
     * 是最近一个还没到的 pending 轮次。来源是用户（actor user）。和现在一样时什么都不写（可重放）。
     */
    public Job recordRound(String projectId, String jobId, String sessionId, Integer seq, Map<String, String> fields,
            String note) {
        try {
            synchronized (StorageLock.WRITES) {
                return recordRoundLocked(projectId, jobId, sessionId, seq, fields, note);
            }
        } finally {
            publish();
        }
    }

    private Job recordRoundLocked(String projectId, String jobId, String sessionId, Integer seq,
            Map<String, String> fields, String note) {
        requireProjectAndSession(projectId, sessionId);
        Job current = jobs.load(projectId, jobId);
        JobStatus status = current.status();
        if (status.phase() == null || status.phase() == Phase.PREP) {
            throw new IllegalArgumentException(jobId + " 还没记为已投递（现在是 " + status.label() + "）。用户说已经投了、"
                    + "有了笔试或面试时，先 mark_submitted，再记轮次");
        }
        List<HiringRound> rounds = new ArrayList<>(HiringRound.of(current));
        // 没给 seq 时，名称一样的那一轮就是同一轮（重复调用不多加一轮），没有才加在最后
        String name = fields.get("name") == null ? null : fields.get("name").strip();
        int target = seq != null ? seq : rounds.stream().filter(r -> r.name().equalsIgnoreCase(name))
                .mapToInt(HiringRound::seq).findFirst()
                .orElse(rounds.stream().mapToInt(HiringRound::seq).max().orElse(0) + 1);
        HiringRound old = rounds.stream().filter(r -> r.seq() == target).findFirst().orElse(null);
        Map<String, Object> merged = old == null ? new LinkedHashMap<>() : new LinkedHashMap<>(old.toMap());
        merged.put("seq", target);
        fields.forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                merged.put(key, value.strip());
            }
        });
        merged.putIfAbsent("result", "pending");
        merged.putIfAbsent("result_source", "user");
        String before = old == null ? null : old.result();
        if (!Objects.equals(before, merged.get("result")) && !"pending".equals(merged.get("result"))) {
            merged.put("result_at", Timestamps.now(clock));
        }
        HiringRound round = HiringRound.fromMap(merged);
        if (round.equals(old)) {
            return current;
        }
        rounds.removeIf(r -> r.seq() == target);
        rounds.add(round);
        rounds.sort(Comparator.comparingInt(HiringRound::seq));

        LocalDate today = LocalDate.now(clock);
        StringBuilder said = new StringBuilder(round.name()).append("（").append(round.type()).append("）")
                .append(round.result());
        if (old != null && HiringRound.UNKNOWN.equals(old.effectiveResult(today)) && !"pending".equals(round.result())) {
            said.append("，之前算作 unknown（用户未同步）");
        }
        if (note != null && !note.isBlank()) {
            said.append("：").append(note.strip());
        }
        Phase derived = HiringRound.phaseOf(rounds);
        JobStatus nextStatus = status;
        JobEvent phaseEvent = null;
        if (status.phase() != Phase.CLOSED) {
            if (derived == Phase.CLOSED) {
                nextStatus = ApplicationStateMachine.next(status,
                        new Transition.Close(Outcome.REJECTED, "挂在第 " + round.seq() + " 轮 " + round.name()), Actor.USER);
                phaseEvent = event(sessionId, Actor.USER, JobEvent.Type.CLOSED, status, nextStatus,
                        "挂在第 " + round.seq() + " 轮 " + round.name(), null);
            } else if (derived != status.phase() && status.phase() != Phase.OFFER) {
                nextStatus = ApplicationStateMachine.next(status, new Transition.ChangePhase(derived), Actor.USER);
                phaseEvent = event(sessionId, Actor.USER, JobEvent.Type.PHASE_CHANGED, status, nextStatus,
                        said.toString(), null);
            }
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        if (phaseEvent != null && nextStatus.phase() == Phase.CLOSED) {
            changes.putAll(closedExtra(phaseEvent.reason()));
        }
        changes.put("rounds", rounds.stream().map(HiringRound::toMap).toList());
        HiringRound upcoming = rounds.stream()
                .filter(r -> "pending".equals(r.result()) && HiringRound.day(r.scheduledAt()) != null
                        && !HiringRound.day(r.scheduledAt()).isBefore(today))
                .min(Comparator.comparing(r -> HiringRound.day(r.scheduledAt()))).orElse(null);
        Map<String, Object> nextEvent = null;
        if (upcoming != null) {
            nextEvent = new LinkedHashMap<>();
            nextEvent.put("what", upcoming.name());
            nextEvent.put("at", upcoming.scheduledAt());
        }
        changes.put("next_event", nextEvent);
        // 下次提醒去看：这一轮还没到就从那天算，否则从今天算
        LocalDate roundDay = HiringRound.day(round.date());
        LocalDate base = roundDay != null && roundDay.isAfter(today) ? roundDay : today;
        changes.put("next_check_at", nextStatus.phase() == Phase.CLOSED ? null
                : base.plusDays(checkEveryDays(projectId)).toString());
        Job next = current.withStatus(nextStatus).withExtra(changes);
        JobEvent roundEvent = event(sessionId, Actor.USER, JobEvent.Type.ROUND_RECORDED, status, status,
                said.toString(), null);

        jobs.update(next);
        appendEvent(next, roundEvent);
        if (phaseEvent != null) {
            appendEvent(next, phaseEvent);
        }
        afterChange(projectId, sessionId, jobId);
        return next;
    }

    /**
     * 写第 {@code seq} 轮的复盘（C014-A）：{@code rounds/<seq>-<type>.md}，再写一次就整个换掉（这一轮的类型改过时，
     * 旧类型的那个文件删掉）。轮次摘要里记下复盘文件、题数和主题，事件记一条 {@code review_saved}，然后和别的台账一起
     * 重新生成题库和按公司汇总。这一轮要先用 {@link #recordRound} 记下。返回写好的复盘文件。
     */
    public Path saveReview(String projectId, String jobId, String sessionId, int seq, RoundReviews.Review review) {
        try {
            synchronized (StorageLock.WRITES) {
                require(review, "review");
                requireProjectAndSession(projectId, sessionId);
                Job current = jobs.load(projectId, jobId);
                List<HiringRound> rounds = new ArrayList<>(HiringRound.of(current));
                HiringRound round = rounds.stream().filter(r -> r.seq() == seq).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(jobId + " 还没有第 " + seq
                                + " 轮，先用 record_round 记下这一轮"));
                for (RoundReviews.Question q : review.questions()) {
                    if (q.title() == null || q.title().isBlank()) {
                        throw new IllegalArgumentException("每道题都要有 title");
                    }
                    if (q.status() != null && !RoundReviews.REVIEW_STATES.contains(q.status())) {
                        throw new IllegalArgumentException("复习状态只能是 todo / reviewed / mastered");
                    }
                }
                Path file = layout.roundReview(projectId, jobId, seq, round.type());
                String relative = "rounds/" + file.getFileName();
                HiringRound reviewed = round.withReview(relative, review.questions().size(), review.topics());
                rounds.replaceAll(r -> r.seq() == seq ? reviewed : r);

                if (round.review() != null && !round.review().equals(relative)) {
                    Path old = layout.jobDir(projectId, jobId).resolve(round.review()).normalize();
                    if (old.startsWith(layout.roundsDir(projectId, jobId))) {
                        Files.deleteIfExists(old);
                    }
                }
                FrontmatterFile.write(file, RoundReviews.render(current, reviewed, sessionId, review));
                StorageWrites.changed(file);
                Job next = current.withExtra(Map.of("rounds", rounds.stream().map(HiringRound::toMap).toList()));
                jobs.update(next);
                appendEvent(next, event(sessionId, Actor.USER, JobEvent.Type.REVIEW_SAVED, next.status(),
                        next.status(), round.name() + " 复盘：" + review.questions().size() + " 道题", null));
                afterChange(projectId, sessionId, jobId);
                return file;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("删除旧复盘失败", e);
        } finally {
            publish();
        }
    }

    public List<JobEvent> events(String projectId, String jobId) {
        return eventLog.readJobEvents(layout.jobEvents(projectId, jobId));
    }

    /**
     * 已有 Job 的写入：Session 删除过（在回收站里，C013）也接受，事件照样记原来的 Session。新建候选
     * （{@link #addLead}）要求 Session 还在（{@link #requireProjectAndLiveSession}）。
     */
    private void requireProjectAndSession(String projectId, String sessionId) {
        if (!projects.exists(projectId)) {
            throw new IllegalArgumentException("Project 不存在：" + projectId);
        }
        if (!sessions.existsOrDeleted(projectId, sessionId)) {
            throw new IllegalArgumentException("Session 不存在：" + projectId + "/" + sessionId);
        }
    }

    private void requireProjectAndLiveSession(String projectId, String sessionId) {
        requireProjectAndSession(projectId, sessionId);
        if (!sessions.exists(projectId, sessionId)) {
            throw new IllegalArgumentException("Session 已删除：" + projectId + "/" + sessionId);
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

    private List<Path> afterChange(String projectId, String sessionId, String jobId) {
        List<Path> written = new ArrayList<>();
        if (sessions.exists(projectId, sessionId)) {
            // 删掉的 Session 没有 session.md 可以更新（C013）
            written.add(sessions.refreshDerived(projectId, sessionId));
        }
        written.addAll(tracker.writeProject(projectId));
        written.addAll(tracker.writeGlobal());
        pending.get().add(new Change(sessionId, jobId));
        return written;
    }

    /**
     * 出了 {@link StorageLock#WRITES} 以后才通知（C006-fix-2 回归第 4 条）：锁里只记下，最外层的调用出锁时按顺序发，
     * 慢的订阅者不会挡住别的写入。还在锁里（嵌套调用）时什么都不做。
     */
    private void publish() {
        if (Thread.holdsLock(StorageLock.WRITES)) {
            return;
        }
        List<Change> changes = pending.get();
        if (changes.isEmpty()) {
            return;
        }
        List<Change> sending = List.copyOf(changes);
        changes.clear();
        sending.forEach(change -> listener.changed(change.session(), change.job()));
    }

    private record Change(String session, String job) {
    }

    private static JobEvent.Type eventType(Transition transition) {
        return switch (transition) {
            case Transition.MarkLead mark -> JobEvent.Type.LEAD_STATUS_CHANGED;
            case Transition.Convert convert -> JobEvent.Type.CONVERTED;
            case Transition.ChangePrepState change -> JobEvent.Type.PREP_STATE_CHANGED;
            case Transition.MarkApplied applied -> JobEvent.Type.PHASE_CHANGED;
            case Transition.ChangePhase change -> JobEvent.Type.PHASE_CHANGED;
            case Transition.Close close -> JobEvent.Type.CLOSED;
            case Transition.Reopen reopen -> JobEvent.Type.REOPENED;
        };
    }

    private static String reason(Transition transition) {
        return switch (transition) {
            case Transition.MarkLead mark -> mark.reason();
            case Transition.ChangePrepState change -> change.reason();
            case Transition.Close close -> close.reason();
            case Transition.Reopen reopen -> reopen.reason();
            default -> null;
        };
    }

    private static Evidence evidence(Transition transition) {
        return transition instanceof Transition.MarkApplied applied ? applied.evidence() : null;
    }
}
