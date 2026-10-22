package jobapply.api;

import jobapply.workflow.graphs.LoginHandoffRunner;
import jobapply.workflow.state.LoginHandoffStatus;
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
 * 登录接力：开始、查询状态、发信号（{@code logged_in}、{@code identity_confirmed}、{@code wrong_account}、
 * {@code reconnected}）。写接口立即返回 202，图在后台执行；用 GET 查询结果。
 * 账号标识可选（UID、用户名、邮箱、手机号都可以），不要填密码。
 */
@RestController
@RequestMapping("/api/jobs/{jobId}/login-handoff")
public class LoginHandoffController {

    public record StartRequest(String project, String session, String accountHint) {
    }

    public record SignalRequest(String session, String signal, Boolean override) {
    }

    private final LoginHandoffRunner runner;

    public LoginHandoffController(LoginHandoffRunner runner) {
        this.runner = runner;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LoginHandoffStatus> start(@PathVariable String jobId, @RequestBody StartRequest request) {
        require(request.project(), "project");
        require(request.session(), "session");
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(runner.start(jobId, request.project(), request.session(), request.accountHint()));
    }

    @GetMapping
    public LoginHandoffStatus status(@PathVariable String jobId) {
        return runner.status(jobId);
    }

    @PostMapping(path = "/signal", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LoginHandoffStatus> signal(@PathVariable String jobId, @RequestBody SignalRequest request) {
        require(request.session(), "session");
        require(request.signal(), "signal");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(runner.signal(jobId, request.session(),
                request.signal(), Boolean.TRUE.equals(request.override())));
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少 " + name);
        }
    }
}
