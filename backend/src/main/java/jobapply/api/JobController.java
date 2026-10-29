package jobapply.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import jobapply.domain.Actor;
import jobapply.domain.AgentRun;
import jobapply.domain.Job;
import jobapply.domain.JobEvent;
import jobapply.domain.JobStatus;
import jobapply.domain.LeadStatus;
import jobapply.domain.Outcome;
import jobapply.domain.PrepState;
import jobapply.domain.HiringRound;
import jobapply.domain.Transition;
import jobapply.persistence.JobLedger;
import jobapply.persistence.JobRepository;
import jobapply.persistence.SessionRepository;
import jobapply.persistence.StorageLayout;
import jobapply.workflow.ApplicationException;
import jobapply.workflow.ApplicationRunner;
import jobapply.workflow.ApplicationStatus;
import jobapply.workflow.FollowupRunner;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Job 详情（申请工作台用）和候选池上的「想投 / 不投」（C005）。Job id 全局唯一，按 id 找它所在的 Project。
 */
@RestController
@RequestMapping("/api/jobs/{id}")
public class JobController {

    public record EventView(String at, String session, String actor, String type, String from, String to,
                            String reason) {
    }

    /**
     * @param application 申请线程的状态；没有申请线程时为 null
     * @param steps       申请线程每一步做了什么（思考、工具参数和结果、给用户的话；不含快照）
     * @param activity    申请线程现在在做什么；没在跑时为 null
     * @param session     从这个页面发消息用哪个 Session：申请线程的、发现它的，都删了时用这个 Project 最新的一个；
     *                    一个都没有时为 null（C013）
     * @param deletedSessions 记录里引用、但已经删除的 Session（界面上显示「会话已删除」，C013）
     * @param followup    工作台的后续段（C022）：离开 prep 以后才有；还在 prep 时为 null
     */
    public record JobDetail(Views.JobView job, List<EventView> events, ApplicationStatus application,
                            List<Views.ChatMessage> steps, Views.Activity activity, String session,
                            List<String> deletedSessions, FollowupController.FollowupView followup) {
    }

    /** @param status {@code new} / {@code shortlisted} / {@code skipped}；不投时 {@code reason} 必填 */
    public record LeadRequest(String status, String reason) {
    }

    /** 用户确认已经提交（C007）。{@code note} 是用户的话，可以为空。 */
    public record SubmittedRequest(String note) {
    }

    /** 还没提交（{@code giveUp=false}，暂停）或不投了（{@code giveUp=true}，以 not_submitted 结束）（C007）。 */
    public record PostingClosedRequest(String note) {
    }

    public record NotSubmittedRequest(Boolean giveUp, String note) {
    }

    private final StorageLayout layout;
    private final JobRepository jobs;
    private final JobLedger ledger;
    private final ApplicationRunner applications;
    private final Clock clock;
    private final SessionRepository sessions;
    private final FollowupRunner followups;

    public JobController(StorageLayout layout, Clock clock, ApplicationRunner applications, FollowupRunner followups) {
        this.layout = layout;
        this.jobs = new JobRepository(layout);
        this.ledger = new JobLedger(layout, clock, applications.events()::changed);
        this.applications = applications;
        this.clock = clock;
        this.sessions = new SessionRepository(layout, clock);
        this.followups = followups;
    }

    /** 这个 Job 的视图；「该查看了」按服务器今天的日期算。 */
    private Views.JobView view(Job job) {
        return Views.job(job, applications, LocalDate.now(clock));
    }

    @GetMapping
    public JobDetail get(@PathVariable String id) {
        Job job = find(id);
        List<EventView> events = ledger.events(job.project(), id).stream().map(JobController::event).toList();
        ApplicationStatus status = applications.statusIfAny(id).orElse(null);
        List<String> referenced = Stream.of(status == null ? null : status.session(), job.discoveredIn())
                .filter(Objects::nonNull).distinct().toList();
        String usable = referenced.stream().filter(s -> sessions.exists(job.project(), s)).findFirst()
                .orElseGet(() -> sessions.list(job.project()).stream().findFirst().map(s -> s.id()).orElse(null));
        return new JobDetail(view(job), events, status,
                applications.run(id).map(run -> Views.messages(run.conversation(), applications.events().jobInFlight(id),
                                "/api/jobs/" + id + "/application/attachments"))
                        .orElse(List.of()),
                Views.activity(applications.events().jobActivity(id)), usable,
                referenced.stream().filter(s -> !sessions.exists(job.project(), s)).toList(),
                FollowupRunner.open(job) ? FollowupController.view(followups, id) : null);
    }

    @PatchMapping(path = "/lead", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Views.JobView lead(@PathVariable String id, @RequestBody LeadRequest request) {
        Job job = find(id);
        if (request.status() == null || request.status().isBlank()) {
            throw new IllegalArgumentException("缺少 status");
        }
        LeadStatus status = LeadStatus.of(request.status().strip());
        if (status == LeadStatus.CONVERTED) {
            throw new IllegalArgumentException("转为申请请用「开始申请」");
        }
        if (job.discoveredIn() == null) {
            throw new ApplicationException.Conflict("Job " + id + " 没有记录发现它的 Session");
        }
        Job next = ledger.transition(job.project(), id, job.discoveredIn(), Actor.USER,
                new Transition.MarkLead(status, request.reason()));
        return view(next);
    }

    /**
     * 用户在 Chrome 里亲自提交了：{@code → user_submitting → applied}（C007）。准备中的任何状态都接受（C006-fix
     * 第 3 节）；已经投递过的原样返回。
     */
    @PostMapping(path = "/submitted", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Views.JobView submitted(@PathVariable String id, @RequestBody(required = false) SubmittedRequest request) {
        Job job = find(id);
        // 申请 Agent 还在填时用户已经自己提交了：先请它停下
        applications.statusIfAny(id).ifPresent(status -> applications.stop(id));
        Job next = ledger.markSubmitted(job.project(), id, sessionOf(job), request == null ? null : request.note());
        applications.closeTabs(id);
        return view(next);
    }

    /**
     * 还没提交：暂停；不投了：以 {@code closed:not_submitted} 结束（C007）。用户说的事实优先（C006-fix 第 3 节）：
     * 准备中的任何状态都接受，申请线程在跑时先请它停下（它结束时不改用户定下的状态）；已经是这样了时原样返回。
     */
    @PostMapping(path = "/not-submitted", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Views.JobView notSubmitted(@PathVariable String id,
                                      @RequestBody(required = false) NotSubmittedRequest request) {
        Job job = find(id);
        boolean giveUp = request != null && Boolean.TRUE.equals(request.giveUp());
        String note = request == null || request.note() == null || request.note().isBlank() ? null
                : request.note().strip();
        JobStatus now = job.status();
        if (giveUp ? now.outcome() == Outcome.NOT_SUBMITTED : now.prepState() == PrepState.PAUSED) {
            return view(job);
        }
        applications.statusIfAny(id).ifPresent(status -> applications.stop(id));
        Transition transition = giveUp
                ? new Transition.Close(Outcome.NOT_SUBMITTED, note == null ? "用户说不投了" : note)
                : new Transition.ChangePrepState(PrepState.PAUSED, note == null ? "用户说还没提交" : note);
        Job next = ledger.transition(job.project(), id, sessionOf(job), Actor.USER, transition);
        if (giveUp) {
            applications.closeTabs(id);
        }
        return view(next);
    }

    /**
     * 岗位下线了：还在候选池的标成 {@code closed}；已经转成申请的以 {@code closed:posting_closed} 结束，
     * 申请线程在跑时先请它停下。
     */
    @PostMapping(path = "/posting-closed", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Views.JobView postingClosed(@PathVariable String id,
                                       @RequestBody(required = false) PostingClosedRequest request) {
        Job job = find(id);
        String note = request == null || request.note() == null || request.note().isBlank() ? "用户说岗位下线了"
                : request.note().strip();
        if (job.status().outcome() == Outcome.POSTING_CLOSED || job.status().leadStatus() == LeadStatus.CLOSED) {
            return view(job);
        }
        Transition transition;
        if (job.status().leadStatus() == LeadStatus.CONVERTED) {
            applications.statusIfAny(id).ifPresent(status -> applications.stop(id));
            transition = new Transition.Close(Outcome.POSTING_CLOSED, note);
        } else {
            transition = new Transition.MarkLead(LeadStatus.CLOSED, note);
        }
        Job next = ledger.transition(job.project(), id, sessionOf(job), Actor.USER, transition);
        applications.closeTabs(id);
        return view(next);
    }

    /** 第 {@code seq} 轮的复盘 Markdown（C014-A，含 frontmatter）；还没写复盘时 404。 */
    @GetMapping("/rounds/{seq}/review")
    public ResponseEntity<String> review(@PathVariable String id, @PathVariable int seq) {
        Job job = find(id);
        HiringRound round = HiringRound.of(job).stream().filter(r -> r.seq() == seq && r.review() != null).findFirst()
                .orElseThrow(() -> new ApplicationException.NotFound("第 " + seq + " 轮还没有复盘"));
        Path file = layout.jobDir(job.project(), id).resolve(round.review()).normalize();
        if (!file.startsWith(layout.roundsDir(job.project(), id)) || !Files.exists(file)) {
            throw new ApplicationException.NotFound("复盘文件不在了：" + round.review());
        }
        try {
            return ResponseEntity.ok().contentType(ApplicationController.MARKDOWN).body(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("读取复盘失败：" + round.review(), e);
        }
    }

    /** 申请线程所在的 Session；没有申请线程时用发现它的 Session。 */
    private String sessionOf(Job job) {
        String session = applications.run(job.id()).map(AgentRun::session).orElse(job.discoveredIn());
        if (session == null) {
            throw new ApplicationException.Conflict("Job " + job.id() + " 没有记录所在的 Session");
        }
        return session;
    }

    private Job find(String id) {
        for (String project : layout.projectIds()) {
            if (jobs.exists(project, id)) {
                return jobs.load(project, id);
            }
        }
        throw new ApplicationException.NotFound("Job 不存在：" + id);
    }

    private static EventView event(JobEvent e) {
        return new EventView(e.at(), e.session(), e.actor() == null ? null : e.actor().value(),
                e.type() == null ? null : e.type().value(), e.from(), e.to(), e.reason());
    }
}
