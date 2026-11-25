package jobapply.api;

import java.util.List;
import jobapply.persistence.StorageLayout;
import jobapply.persistence.SubRunStore;
import jobapply.workflow.ApplicationException;
import jobapply.workflow.JobThreadService;
import jobapply.workflow.SessionRunner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Subagent 运行（C030 Slice 6）：对话里 {@code delegate} 那一步展开时用。列表只带头信息（任务、状态、结果），
 * 单个带步骤。开始、结束的推送是事件 {@code subrun}（{@link EventController}）。
 * <ul>
 *   <li>{@code GET /api/sessions/{id}/subruns}：这个 Session 交出去的（和它们再往下交的）</li>
 *   <li>{@code GET /api/jobs/{id}/subruns}：针对这个 Job 的（工作台交的、查状态的）</li>
 *   <li>{@code GET /api/projects/{project}/subruns/{subId}}：一次子运行和它的步骤</li>
 * </ul>
 */
@RestController
public class SubRunController {

    private final SubRunStore store;
    private final SessionRunner sessions;
    private final JobThreadService threads;

    public SubRunController(StorageLayout layout, SessionRunner sessions, JobThreadService threads) {
        this.store = new SubRunStore(layout);
        this.sessions = sessions;
        this.threads = threads;
    }

    @GetMapping("/api/sessions/{id}/subruns")
    public List<Views.SubRunView> ofSession(@PathVariable String id) {
        return store.list(sessions.project(id)).stream()
                .filter(run -> id.equals(run.session()) || id.equals(run.parentRunId()))
                .map(run -> Views.subRun(run, false))
                .toList();
    }

    @GetMapping("/api/jobs/{id}/subruns")
    public List<Views.SubRunView> ofJob(@PathVariable String id) {
        return store.list(threads.job(id).project()).stream()
                .filter(run -> id.equals(run.job()) || id.equals(run.parentRunId()))
                .map(run -> Views.subRun(run, false))
                .toList();
    }

    @GetMapping("/api/projects/{project}/subruns/{subId}")
    public Views.SubRunView get(@PathVariable String project, @PathVariable String subId) {
        return store.find(project, subId)
                .map(run -> Views.subRun(run, true))
                .orElseThrow(() -> new ApplicationException.NotFound("没有这次子任务：" + subId));
    }
}
