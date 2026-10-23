package jobapply.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.List;
import jobapply.domain.SessionRecord;
import jobapply.persistence.JobRepository;
import jobapply.persistence.SessionRepository;
import jobapply.persistence.StorageLayout;
import jobapply.workflow.ApplicationRunner;
import jobapply.workflow.SessionRunner;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Session 对话（C005）：读对话和状态、发消息（后台跑，返回 202）、重新生成最后一条回复、停止、按步推送（SSE，C006-agent）。
 * Agent 还在回上一条消息时发消息、重新生成都是 409。
 */
@RestController
@RequestMapping("/api/sessions/{id}")
public class SessionController {

    /**
     * @param status   {@code running} / {@code idle} / {@code interrupted}（应用重启时还在跑）
     * @param error    上一次运行的错误；没有时为 null
     * @param messages 对话（{@code steps} 带参数、结果、状态和时间）
     * @param jobs     这个 Session 涉及的 Job
     * @param activity 现在在做什么；没在跑时为 null
     */
    public record SessionView(Views.SessionSummary session, String status, String error,
                              List<Views.ChatMessage> messages, List<Views.JobView> jobs, Views.Activity activity) {
    }

    /** @param attachments 先上传的附件编号（C006 附件）；有附件时 {@code text} 可以为空 */
    public record MessageRequest(String text, List<String> attachments) {
    }

    public record RenameRequest(String title) {
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
        runner.send(id, request == null ? null : request.text(), request == null ? null : request.attachments());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view(layout, clock, runner, applications, id));
    }

    /** 上传一个附件（C006 附件），存进这个 Session；发消息时带上返回的 {@code id}。 */
    @PostMapping(path = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Views.UploadedAttachment> attach(@PathVariable String id,
                                                           @RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Views.uploaded(runner.attachments(id).save(file.getOriginalFilename(), bytes(file))));
    }

    @GetMapping("/attachments/{attachmentId}")
    public ResponseEntity<Resource> attachment(@PathVariable String id, @PathVariable String attachmentId) {
        return Views.attachmentFile(runner.attachments(id), attachmentId);
    }

    static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("读取上传文件失败", e);
        }
    }

    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public SessionView rename(@PathVariable String id, @RequestBody RenameRequest request) {
        runner.rename(id, request == null ? null : request.title());
        return view(layout, clock, runner, applications, id);
    }

    @PostMapping("/regenerate")
    public ResponseEntity<SessionView> regenerate(@PathVariable String id) {
        runner.regenerate(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view(layout, clock, runner, applications, id));
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id) {
        runner.project(id);
        return EventStream.open(runner.feed(), id);
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
        SessionRunner.Status status = runner.status(session);
        List<Views.JobView> touched = session.jobs().stream().filter(job -> jobs.exists(project, job))
                .map(job -> Views.job(jobs.load(project, job), applications)).toList();
        return new SessionView(Views.session(session, runner), status.status(), status.error(),
                Views.timed(sessions.readTurns(project, sessionId), runner.feed().inFlight(sessionId),
                        "/api/sessions/" + sessionId + "/attachments"), touched,
                Views.activity(runner.feed().activity(sessionId)));
    }
}
