package jobapply.api;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import jobapply.workflow.ApplicationException;
import jobapply.workflow.ApplicationRunner;
import jobapply.workflow.ApplicationStatus;
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
 * 申请线程（agent-runtime.md 7.1）：开始、查询状态、回复、停止、读填写报告。写接口立即返回 202，Agent 在后台运行；
 * 用 GET 查询结果。线程正在运行、或者浏览器被别的 Job 占着时 409；回复的内容不对（例如回答了 C 级问题、
 * 文件数和上传位置对不上）时 400。
 */
@RestController
@RequestMapping("/api/jobs/{jobId}/application")
public class ApplicationController {

    public record StartRequest(String project, String session) {
    }

    /**
     * @param text    写给 Agent 的话（例如「登录好了」、要改什么）
     * @param answers 问题编号 → 回答
     * @param files   每个上传位置一个原件键，顺序同状态里的 {@code fileSlots}；跳过的选填位置写空字符串
     */
    public record ReplyRequest(String session, String text, Map<String, String> answers, List<String> files) {
    }

    static final MediaType MARKDOWN = new MediaType("text", "markdown", StandardCharsets.UTF_8);

    private final ApplicationRunner runner;

    public ApplicationController(ApplicationRunner runner) {
        this.runner = runner;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApplicationStatus> start(@PathVariable String jobId, @RequestBody StartRequest request) {
        require(request.project(), "project");
        require(request.session(), "session");
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(runner.start(jobId, request.project(), request.session()));
    }

    @GetMapping
    public ApplicationStatus status(@PathVariable String jobId) {
        return runner.status(jobId);
    }

    @PostMapping(path = "/reply", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApplicationStatus> reply(@PathVariable String jobId, @RequestBody ReplyRequest request) {
        require(request.session(), "session");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(runner.reply(jobId, new ApplicationRunner.Reply(
                request.session(), request.text(), request.answers(), request.files())));
    }

    @PostMapping("/stop")
    public ApplicationStatus stop(@PathVariable String jobId) {
        return runner.stop(jobId);
    }

    /** 填写报告的 Markdown（草稿或定稿）；没有申请线程、还没有报告时 404。 */
    @GetMapping("/report")
    public ResponseEntity<String> report(@PathVariable String jobId) {
        String report = runner.report(jobId)
                .orElseThrow(() -> new ApplicationException.NotFound("Job " + jobId + " 还没有填写报告"));
        return ResponseEntity.ok().contentType(MARKDOWN).body(report);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少 " + name);
        }
    }
}
