package jobapply.api;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import jobapply.workflow.FollowupRunner;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 看板的批量查状态和核准列表（C026）：开始 / 停下 / 读进度（前端在有排队或在查的时候每 2 秒读一次），核准或驳回官网状态建议。
 * 每个 Job 的变化照常走 {@code GET /api/events}。批量核准 / 驳回一个一个做，失败的写进 {@code failed}，不让整批失败。
 */
@RestController
public class StatusCheckController {

    public record JobIds(List<String> jobIds) {
    }

    public record Failed(String jobId, String reason) {
    }

    /** 批量核准 / 驳回的结果：做成的和没做成的（带原因）。 */
    public record Resolved(List<String> done, List<Failed> failed) {
    }

    private final FollowupRunner runner;

    public StatusCheckController(FollowupRunner runner) {
        this.runner = runner;
    }

    @GetMapping("/api/status-checks")
    public FollowupRunner.StatusChecks checks() {
        return runner.checks();
    }

    @PostMapping(path = "/api/status-checks", consumes = MediaType.APPLICATION_JSON_VALUE)
    public FollowupRunner.StatusChecks start(@RequestBody JobIds request) {
        return runner.checkMany(ids(request));
    }

    /** 不带 body（或 {@code jobIds} 为空）停下整批；带 {@code jobIds} 只停这些（C006-fix）。 */
    @PostMapping("/api/status-checks/stop")
    public FollowupRunner.StatusChecks stop(@RequestBody(required = false) JobIds request) {
        return runner.stopChecks(ids(request));
    }

    @PostMapping(path = "/api/status-proposals/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Resolved approve(@RequestBody JobIds request) {
        return each(ids(request), runner::approve);
    }

    @PostMapping(path = "/api/status-proposals/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Resolved reject(@RequestBody JobIds request) {
        return each(ids(request), runner::reject);
    }

    private static List<String> ids(JobIds request) {
        return request == null || request.jobIds() == null ? List.of() : request.jobIds().stream().distinct().toList();
    }

    private static Resolved each(List<String> jobIds, Consumer<String> action) {
        List<String> done = new ArrayList<>();
        List<Failed> failed = new ArrayList<>();
        for (String jobId : jobIds) {
            try {
                action.accept(jobId);
                done.add(jobId);
            } catch (RuntimeException e) {
                String reason = e.getMessage();
                failed.add(new Failed(jobId, reason == null || reason.isBlank() ? e.getClass().getSimpleName() : reason));
            }
        }
        return new Resolved(done, failed);
    }
}
