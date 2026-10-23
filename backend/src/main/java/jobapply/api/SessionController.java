package jobapply.api;

import java.time.Clock;
import java.util.List;
import jobapply.domain.SessionRecord;
import jobapply.persistence.JobRepository;
import jobapply.persistence.SessionRepository;
import jobapply.persistence.StorageLayout;
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
 * Session 对话（C005）：读对话和状态、发消息（后台跑，返回 202，前端轮询）、停止。Agent 还在回上一条消息时发消息 409。
 */
@RestController
@RequestMapping("/api/sessions/{id}")
public class SessionController {

    /**
     * @param status   {@code running} / {@code idle}
     * @param error    上一次运行的错误；没有时为 null
     * @param messages 对话（工具调用只给名字和一句说明）
     * @param jobs     这个 Session 涉及的 Job
     */
    public record SessionView(Views.SessionSummary session, String status, String error,
                              List<Views.ChatMessage> messages, List<Views.JobView> jobs) {
    }

    public record MessageRequest(String text) {
    }

    private final StorageLayout layout;
    private final Clock clock;
    private final SessionRunner runner;
    private final ApplicationRunner applications;

    public SessionController(StorageLayout layout, Clock clock, SessionRunner runner, ApplicationRunner applications) {
        this.layout = layout;
        this.clock = clock;
        this.runner = runner;
        this.applications = applications;
    }

    @GetMapping
    public SessionView get(@PathVariable String id) {
        return view(layout, clock, runner, applications, id);
    }

    @PostMapping(path = "/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SessionView> send(@PathVariable String id, @RequestBody MessageRequest request) {
        runner.send(id, request == null ? null : request.text());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view(layout, clock, runner, applications, id));
    }

    @PostMapping("/stop")
    public SessionView stop(@PathVariable String id) {
        runner.stop(id);
        return view(layout, clock, runner, applications, id);
    }

    static SessionView view(StorageLayout layout, Clock clock, SessionRunner runner, ApplicationRunner applications,
                            String sessionId) {
        String project = runner.project(sessionId);
        SessionRepository sessions = new SessionRepository(layout, clock);
        JobRepository jobs = new JobRepository(layout);
        SessionRecord session = sessions.load(project, sessionId);
        SessionRunner.Status status = runner.status(sessionId);
        List<Views.JobView> touched = session.jobs().stream().filter(job -> jobs.exists(project, job))
                .map(job -> Views.job(jobs.load(project, job), applications)).toList();
        return new SessionView(Views.session(session), status.status(), status.error(),
                Views.timed(sessions.readTurns(project, sessionId)), touched);
    }
}
