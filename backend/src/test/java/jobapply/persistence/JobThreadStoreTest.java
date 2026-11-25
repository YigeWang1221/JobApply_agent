package jobapply.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import jobapply.domain.AgentRun;
import jobapply.domain.ApplicationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** C019 之前的运行记录（只有 plan）要读得回来，否则磁盘上已有的申请接不上。 */
class JobThreadStoreTest {

    @TempDir
    Path root;

    @Test
    void legacyPlanBecomesStateSteps() throws Exception {
        TestStorage storage = TestStorage.create(root);
        StorageLayout layout = storage.layout;
        Path file = layout.agentRun("2026-10-24-001");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"project":"p","job":"2026-10-24-001","session":"s","company":"示例公司","title":"示例岗位",
                 "url":"https://jobs.example.com/1","status":"waiting_user","message":"请登录",
                 "plan":[{"text":"基本信息","status":"done"},{"text":"教育经历","status":"doing"}],
                 "round":1,"usage":{"modelCalls":3,"toolCalls":4,"tokens":5},
                 "conversation":[{"role":"user","text":"开始申请"}]}
                """);
        JobThreadStore store = new JobThreadStore(layout, storage.clock);

        AgentRun run = store.findRun("2026-10-24-001").orElseThrow();

        assertThat(run.state().steps()).containsExactly(new ApplicationState.Step("基本信息", "done"),
                new ApplicationState.Step("教育经历", "doing"));
        assertThat(run.state().fields()).isEmpty();
        assertThat(run.usage().toolCalls()).isEqualTo(4);

        store.saveRun(run);
        assertThat(Files.readString(file)).doesNotContain("\"plan\"");
        assertThat(store.findRun("2026-10-24-001").orElseThrow().state().steps()).hasSize(2);
    }
}
