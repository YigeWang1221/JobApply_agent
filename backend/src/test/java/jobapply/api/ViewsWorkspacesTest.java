package jobapply.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import jobapply.domain.SessionRecord;
import org.junit.jupiter.api.Test;

/**
 * 左侧栏三级列表（C021）：已打开的工作台按 Session 分组。数据全部虚构。
 * <ul>
 *   <li>只有已经开始投递的岗位（有申请线程，或已经是 converted）进三级栏，候选池里的不进；</li>
 *   <li>先按 Session 的 {@code jobs} / 岗位的「发现于」认领；</li>
 *   <li>关联的旧 Session 删掉以后归到最新 Session，已打开的工作台不会消失；一个岗位只进一个 Session。</li>
 * </ul>
 */
class ViewsWorkspacesTest {

    @Test
    void onlyStartedJobsAreListedAndEachGoesToTheSessionThatTouchedIt() {
        List<SessionRecord> sessions = List.of(session("s-new", "2026-10-22T10:00:00-04:00", List.of()),
                session("s-old", "2026-10-21T10:00:00-04:00", List.of("job-002")));
        List<Views.JobView> jobs = List.of(
                job("job-001", "new", null, null),   // 候选池：不进三级栏
                job("job-002", "converted", null, null),  // 被 s-old 认领
                job("job-003", "converted", "ready", null));  // 没有 Session 认领：归最新

        Map<String, List<Views.WorkspaceSummary>> grouped = Views.workspaces(sessions, jobs);

        assertThat(grouped.get("s-old")).extracting(Views.WorkspaceSummary::id).containsExactly("job-002");
        assertThat(grouped.get("s-new")).extracting(Views.WorkspaceSummary::id).containsExactly("job-003");
    }

    @Test
    void workspaceFieldsComeFromTheJobView() {
        List<SessionRecord> sessions = List.of(session("s-1", "2026-10-22T10:00:00-04:00", List.of("job-001")));

        List<Views.WorkspaceSummary> workspaces = Views.workspaces(sessions,
                List.of(job("job-001", "converted", "running", null))).get("s-1");

        assertThat(workspaces).singleElement().satisfies(workspace -> {
            assertThat(workspace.id()).isEqualTo("job-001");
            assertThat(workspace.company()).isEqualTo("示例公司甲");
            assertThat(workspace.title()).isEqualTo("Backend Engineer");
            assertThat(workspace.application()).isEqualTo("running");
            assertThat(workspace.bucket()).isEqualTo("preparing");
            assertThat(workspace.label()).isEqualTo("填写中");
        });
    }

    @Test
    void aJobWhoseSessionWasDeletedGoesToTheNewestSession() {
        List<SessionRecord> sessions = List.of(session("s-newest", "2026-10-22T10:00:00-04:00", List.of()),
                session("s-other", "2026-10-21T10:00:00-04:00", List.of()));

        // 「发现于」指向已经删掉的旧 Session
        Map<String, List<Views.WorkspaceSummary>> grouped = Views.workspaces(sessions,
                List.of(job("job-009", "converted", null, "s-deleted")));

        assertThat(grouped.get("s-newest")).extracting(Views.WorkspaceSummary::id).containsExactly("job-009");
        assertThat(grouped.get("s-other")).isEmpty();
    }

    @Test
    void aJobIsListedOnlyOnceWhenSeveralSessionsReferenceIt() {
        List<SessionRecord> sessions = List.of(session("s-a", "2026-10-22T10:00:00-04:00", List.of("job-001")),
                session("s-b", "2026-10-21T10:00:00-04:00", List.of("job-001")));

        Map<String, List<Views.WorkspaceSummary>> grouped = Views.workspaces(sessions,
                List.of(job("job-001", "converted", null, "s-b")));

        assertThat(grouped.get("s-a")).hasSize(1);
        assertThat(grouped.get("s-b")).isEmpty();
    }

    @Test
    void withoutSessionsNothingIsListed() {
        assertThat(Views.workspaces(List.of(), List.of(job("job-001", "converted", "running", null)))).isEmpty();
    }

    private static SessionRecord session(String id, String createdAt, List<String> jobs) {
        return new SessionRecord(id, "demo", "示例对话", createdAt, List.of(), jobs, null, null, null);
    }

    /** 只需要分组相关的几个字段，其余给固定的虚构值。 */
    private static Views.JobView job(String id, String leadStatus, String application, String discoveredIn) {
        boolean preparing = application != null;
        return new Views.JobView(id, "demo", "示例公司甲", "Backend Engineer", List.of("上海"), "campus-fulltime",
                "https://jobs.example.com/" + id, null, "self-campus", discoveredIn, leadStatus, null,
                preparing ? "prep" : null, preparing ? "filling" : null, preparing ? "填写中" : "已转申请", null, null,
                null, application, null, null, null, false, null, false,
                preparing ? "preparing" : "ready", false, null, List.of(), null, null, false);
    }
}
