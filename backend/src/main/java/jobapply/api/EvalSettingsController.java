package jobapply.api;

import jobapply.workflow.ApplicationRunner;
import jobapply.workflow.EvalMode;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设置页「评测」（C025）：一个开关，评测模式（拦截最终提交、每步存快照）。保存后马上生效（只对之后开始的申请），
 * 存进 {@code settings/eval.yaml}；返回里带 {@code application.yml} 的默认值（关）。
 */
@RestController
@RequestMapping("/api/settings/eval")
public class EvalSettingsController {

    public record EvalRequest(Boolean enabled) {
    }

    private final EvalMode mode;

    public EvalSettingsController(ApplicationRunner applications) {
        this.mode = applications.evalMode();
    }

    @GetMapping
    public EvalMode.View view() {
        return mode.view();
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public EvalMode.View save(@RequestBody EvalRequest request) {
        return mode.set(Boolean.TRUE.equals(request.enabled()));
    }
}
