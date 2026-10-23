package jobapply.api;

import java.time.Clock;
import java.util.List;
import jobapply.domain.Actor;
import jobapply.domain.Job;
import jobapply.domain.JobEvent;
import jobapply.domain.LeadStatus;
import jobapply.domain.Transition;
import jobapply.persistence.JobLedger;
import jobapply.persistence.JobRepository;
import jobapply.persistence.StorageLayout;
import jobapply.workflow.ApplicationException;
import jobapply.workflow.ApplicationRunner;
import jobapply.workflow.ApplicationStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
     * @param steps       申请线程每一步做了什么（工具名、说明、给用户的话；不含快照）
     */
    public record JobDetail(Views.JobView job, List<EventView> events, ApplicationStatus application,
                            List<Views.ChatMessage> steps) {
    }

    /** @param status {@code new} / {@code shortlisted} / {@code skipped}；不投时 {@code reason} 必填 */
    public record LeadRequest(String status, String reason) {
    }

    private final StorageLayout layout;
    private final JobRepository jobs;
    private final JobLedger ledger;
    private final ApplicationRunner applications;

    public JobController(StorageLayout layout, Clock clock, ApplicationRunner applications) {
        this.layout = layout;
        this.jobs = new JobRepository(layout);
        this.ledger = new JobLedger(layout, clock);
        this.applications = applications;
    }

    @GetMapping
    public JobDetail get(@PathVariable String id) {
        Job job = find(id);
        List<EventView> events = ledger.events(job.project(), id).stream().map(JobController::event).toList();
        return new JobDetail(Views.job(job, applications), events, applications.statusIfAny(id).orElse(null),
                applications.run(id).map(run -> Views.messages(run.conversation())).orElse(List.of()));
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
        return Views.job(next, applications);
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
