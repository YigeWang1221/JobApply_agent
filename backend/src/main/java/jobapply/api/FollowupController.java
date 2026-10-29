package jobapply.api;

import java.util.List;
import jobapply.domain.Job;
import jobapply.workflow.FollowupRunner;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 工作台的后续段（C022）：读对话和状态、发消息（后台跑，返回 202）、附件、停止。推送和投递段同一条
 * {@code GET /api/events?job=<id>}。还没投递时发消息 409。
 */
@RestController
@RequestMapping("/api/jobs/{id}/followup")
public class FollowupController {

    /**
     * @param open     这个申请已经离开 prep，有后续段
     * @param status   {@code running} / {@code idle} / {@code interrupted}
     * @param error    上一次运行的错误；没有时为 null
     * @param messages 后续段的对话（不含投递段）
     * @param activity 现在在做什么；没在跑时为 null
     * @param inserted 运行中插了、Agent 还没看到的消息原文（C010）
     */
    public record FollowupView(boolean open, String status, String error, List<Views.ChatMessage> messages,
                               Views.Activity activity, List<String> inserted) {
    }

    private final FollowupRunner runner;

    public FollowupController(FollowupRunner runner) {
        this.runner = runner;
    }

    @GetMapping
    public FollowupView get(@PathVariable String id) {
        return view(runner, id);
    }

    @PostMapping(path = "/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FollowupView> send(@PathVariable String id,
                                             @RequestBody SessionController.MessageRequest request) {
        runner.send(id, request == null ? null : request.text(), request == null ? null : request.attachments());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view(runner, id));
    }

    /** 上传一个附件，存进 {@code followup/attachments/}；发消息时带上返回的 {@code id}。 */
    @PostMapping(path = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Views.UploadedAttachment> attach(@PathVariable String id,
                                                           @RequestParam("file") MultipartFile file) {
        Views.UploadedAttachment uploaded = Views.uploaded(
                runner.attachments(id).save(file.getOriginalFilename(), SessionController.bytes(file)));
        runner.events().changed(null, id);
        return ResponseEntity.status(HttpStatus.CREATED).body(uploaded);
    }

    @GetMapping("/attachments/{attachmentId}")
    public ResponseEntity<Resource> attachment(@PathVariable String id, @PathVariable String attachmentId) {
        return Views.attachmentFile(runner.attachments(id), attachmentId);
    }

    @PostMapping("/stop")
    public FollowupView stop(@PathVariable String id) {
        runner.stop(id);
        return view(runner, id);
    }

    static FollowupView view(FollowupRunner runner, String jobId) {
        Job job = runner.job(jobId);
        FollowupRunner.Status status = runner.status(jobId);
        return new FollowupView(FollowupRunner.open(job), status.status(), status.error(),
                Views.timed(runner.turns(jobId), runner.events().followupInFlight(jobId),
                        "/api/jobs/" + jobId + "/followup/attachments"),
                Views.activity(runner.events().followupActivity(jobId)),
                runner.inserted(jobId).stream().map(SessionController::insertedText).toList());
    }
}
