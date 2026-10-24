package jobapply.api;

import jobapply.workflow.ApplicationRunner;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设置页「同时申请」（C012）：同时跑几个申请、同一个网站最多几个。保存后马上生效并存进 {@code settings/workers.yaml}；
 * 返回里带此刻在跑、排队的个数。
 */
@RestController
@RequestMapping("/api/settings/workers")
public class WorkerSettingsController {

    public record WorkersRequest(Integer workers, Integer perSite) {
    }

    private final ApplicationRunner applications;

    public WorkerSettingsController(ApplicationRunner applications) {
        this.applications = applications;
    }

    @GetMapping
    public ApplicationRunner.Concurrency view() {
        return applications.concurrency();
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApplicationRunner.Concurrency save(@RequestBody WorkersRequest request) {
        if (request.workers() == null || request.perSite() == null) {
            throw new IllegalArgumentException("需要 workers 和 perSite");
        }
        return applications.setConcurrency(request.workers(), request.perSite());
    }
}
