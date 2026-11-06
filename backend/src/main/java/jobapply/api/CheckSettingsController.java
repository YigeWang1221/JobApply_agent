package jobapply.api;

import java.time.Clock;
import jobapply.persistence.JobLedger;
import jobapply.persistence.ProjectRepository;
import jobapply.persistence.StorageLayout;
import jobapply.workflow.ApplicationException;
import jobapply.workflow.ApplicationRunner;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 多少天查看一次投递状态（C026）：全局的存在 {@code settings/checks.yaml}，Project 自己的写在 {@code preferences.md}
 * 的 {@code check_every_days}（为 null 时跟全局）。改了以后马上按新间隔重排没有固定日期的申请。
 */
@RestController
public class CheckSettingsController {

    public record DaysRequest(Integer days) {
    }

    public record GlobalDays(int days, int defaultDays) {
    }

    /** @param days 这个 Project 自己设的（没设为 null）；{@code global} 全局的；{@code effective} 实际用的。 */
    public record ProjectDays(Integer days, int global, int effective) {
    }

    private final JobLedger ledger;
    private final ProjectRepository projects;

    public CheckSettingsController(StorageLayout layout, Clock clock, ApplicationRunner applications) {
        this.ledger = new JobLedger(layout, clock, applications.events()::changed);
        this.projects = new ProjectRepository(layout, clock);
    }

    @GetMapping("/api/settings/checks")
    public GlobalDays global() {
        return new GlobalDays(globalDays(), JobLedger.DEFAULT_CHECK_DAYS);
    }

    @PutMapping(value = "/api/settings/checks", consumes = MediaType.APPLICATION_JSON_VALUE)
    public GlobalDays saveGlobal(@RequestBody DaysRequest request) {
        if (request == null || request.days() == null) {
            throw new IllegalArgumentException("需要 days");
        }
        ledger.setGlobalCheckDays(request.days());
        return global();
    }

    @GetMapping("/api/projects/{id}/check-days")
    public ProjectDays project(@PathVariable String id) {
        requireProject(id);
        return new ProjectDays(ledger.projectCheckDays(id).orElse(null), globalDays(), ledger.checkEveryDays(id));
    }

    @PutMapping(value = "/api/projects/{id}/check-days", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ProjectDays saveProject(@PathVariable String id, @RequestBody DaysRequest request) {
        requireProject(id);
        ledger.setProjectCheckDays(id, request == null ? null : request.days());
        return project(id);
    }

    private int globalDays() {
        return ledger.globalCheckDays().orElse(JobLedger.DEFAULT_CHECK_DAYS);
    }

    private void requireProject(String id) {
        if (!projects.exists(id)) {
            throw new ApplicationException.NotFound("没有这个 Project：" + id);
        }
    }
}
