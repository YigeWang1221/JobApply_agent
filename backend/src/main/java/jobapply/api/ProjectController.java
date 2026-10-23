package jobapply.api;

import java.time.Clock;
import java.util.List;
import jobapply.domain.ProjectSpec;
import jobapply.persistence.JobRepository;
import jobapply.persistence.ProjectRepository;
import jobapply.persistence.SessionRepository;
import jobapply.persistence.StorageInitializer;
import jobapply.persistence.StorageLayout;
import jobapply.workflow.ApplicationException;
import jobapply.workflow.ApplicationRunner;
import jobapply.workflow.SessionRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Project 的列表和主页（C005-ui）：每个 Project 的 Session 和 Job 计数、全部 Job；新建 Project（继承 base、跟随最新资料）；
 * 新建 Session（Agent 在后台写开场，返回 202）。
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    public record ProjectsResponse(List<Views.ProjectSummary> projects) {
    }

    public record ProjectPage(Views.ProjectSummary project, List<Views.JobView> jobs) {
    }

    public record CreateProject(String id, String title) {
    }

    public record CreateSession(String title) {
    }

    private final StorageLayout layout;
    private final Clock clock;
    private final ProjectRepository projects;
    private final SessionRepository sessions;
    private final JobRepository jobs;
    private final ApplicationRunner applications;
    private final SessionRunner sessionRunner;

    public ProjectController(StorageLayout layout, Clock clock, ApplicationRunner applications,
                             SessionRunner sessionRunner) {
        this.layout = layout;
        this.clock = clock;
        this.projects = new ProjectRepository(layout, clock);
        this.sessions = new SessionRepository(layout, clock);
        this.jobs = new JobRepository(layout);
        this.applications = applications;
        this.sessionRunner = sessionRunner;
    }

    @GetMapping
    public ProjectsResponse list() {
        return new ProjectsResponse(layout.projectIds().stream().filter(projects::exists).map(this::summary).toList());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Views.ProjectSummary create(@RequestBody CreateProject request) {
        if (request.id() == null || request.id().isBlank()) {
            throw new IllegalArgumentException("缺少 id");
        }
        String id = request.id().strip();
        String title = request.title() == null || request.title().isBlank() ? id : request.title().strip();
        new StorageInitializer(layout, clock).initialize();
        projects.create(new ProjectSpec(id, title, List.of(ProjectSpec.BASE), ProjectSpec.LIVE, null, null));
        return summary(id);
    }

    @GetMapping("/{id}")
    public ProjectPage page(@PathVariable String id) {
        requireProject(id);
        return new ProjectPage(summary(id), jobViews(id));
    }

    @PostMapping(path = "/{id}/sessions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SessionController.SessionView> createSession(@PathVariable String id,
                                                                       @RequestBody CreateSession request) {
        requireProject(id);
        String session = sessionRunner.create(id, request == null ? null : request.title()).id();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(SessionController.view(layout, clock, sessionRunner, applications, session));
    }

    private Views.ProjectSummary summary(String id) {
        return Views.project(projects.load(id), sessions.list(id), jobViews(id));
    }

    private List<Views.JobView> jobViews(String id) {
        return jobs.list(id).stream().map(job -> Views.job(job, applications)).toList();
    }

    private void requireProject(String id) {
        if (!projects.exists(id)) {
            throw new ApplicationException.NotFound("Project 不存在：" + id);
        }
    }
}
