package jobapply.api;

import jobapply.workflow.ApplicationRunner;
import jobapply.workflow.ToolCallLimits;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设置页「工具调用上限」（C018）：申请 Agent 一次运行、对话 Agent 每条消息最多几次工具调用，0 = 不限。保存后马上生效
 * 并存进 {@code settings/tool-calls.yaml}；返回里带 {@code application.yml} 的默认值。
 */
@RestController
@RequestMapping("/api/settings/tool-calls")
public class ToolCallSettingsController {

    /** 没填（null）= 不限，和 0 一样。 */
    public record ToolCallsRequest(Integer perRun, Integer perMessage) {
    }

    private final ToolCallLimits limits;

    public ToolCallSettingsController(ApplicationRunner applications) {
        this.limits = applications.toolCallLimits();
    }

    @GetMapping
    public ToolCallLimits.View view() {
        return limits.view();
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ToolCallLimits.View save(@RequestBody ToolCallsRequest request) {
        return limits.set(request.perRun() == null ? 0 : request.perRun(),
                request.perMessage() == null ? 0 : request.perMessage());
    }
}
