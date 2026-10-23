package jobapply.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import jobapply.config.FakeModelServer;
import jobapply.memory.ImportFixture;
import jobapply.tools.FakeGitHubServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 估算、开始整理、查进度、逐条决定。虚构项目里放一个秘密文件，确认它的内容不出现在任何返回和模型请求里。
 * 模型是本机假服务端（按提示词回复虚构的 JSON），GitHub 是本机假服务端。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ImportRunControllerTest {

    static final String SECRET_TEXT = "SECRET-CONTENT-THAT-MUST-NOT-LEAK";
    static final String DOC_TEXT = "DOC-CONTENT-ALSO-NOT-RETURNED";

    @TempDir
    static Path root;

    static final FakeGitHubServer GITHUB = new FakeGitHubServer();
    static final FakeModelServer MODEL = FakeModelServer.start().toolCalls(false).respond(ImportFixture::answer);

    @LocalServerPort
    int port;

    private LocalApi api;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jobapply.storage.root", () -> root.resolve("storage").toString());
        registry.add("jobapply.import.github-api", () -> GITHUB.uri().toString());
    }

    @AfterAll
    static void stop() {
        GITHUB.close();
        MODEL.close();
    }

    @BeforeEach
    void setUp() {
        api = new LocalApi(port);
    }

    @Test
    void estimateCreatesARunWithoutFileContent() throws Exception {
        Path project = Files.createDirectories(root.resolve("demo-project"));
        Files.writeString(project.resolve("README.md"), "# 示例\n" + DOC_TEXT);
        Files.writeString(project.resolve(".env"), SECRET_TEXT);
        String key = (String) ((Map<?, ?>) api.postJson("/api/sources",
                Map.of("type", "dir", "location", project.toString())).get("source")).get("key");

        LocalApi.Response created = api.postJson("/api/import-runs", Map.of("sources", List.of(key)));

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.get("status")).isEqualTo("estimated");
        assertThat(created.body()).contains("README.md").contains(".env").doesNotContain(SECRET_TEXT)
                .doesNotContain(DOC_TEXT);
        Map<?, ?> estimate = (Map<?, ?>) created.get("estimate");
        assertThat(estimate.get("modelCalls")).isEqualTo(2);
        String id = (String) created.get("id");

        LocalApi.Response read = api.get("/api/import-runs/" + id);
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.json()).isEqualTo(created.json());
        assertThat(GITHUB.received()).isEmpty();
    }

    @Test
    void badRequestsHaveTheRightStatus() {
        assertThat(api.postJson("/api/import-runs", Map.of("sources", List.of())).status()).isEqualTo(400);
        LocalApi.Response unknown = api.postJson("/api/import-runs", Map.of("sources", List.of("s-2026-01-01-999")));
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(api.get("/api/import-runs/r-2026-01-01-999").status()).isEqualTo(404);
        assertThat(api.get("/api/import-runs/bad").status()).isEqualTo(400);
        assertThat(api.post("/api/import-runs", "text/plain", "x").status()).isEqualTo(415);
        assertThat(api.get("/api/import-runs/r-2026-01-01-999", "Origin", "https://evil.example.com").status())
                .isEqualTo(403);
    }

    @Test
    void startReviewAndDecide() throws Exception {
        Path project = Files.createDirectories(root.resolve("review-project"));
        Files.writeString(project.resolve("README.md"), "# 示例\n" + DOC_TEXT);
        Files.writeString(project.resolve(".env"), SECRET_TEXT);
        String key = (String) ((Map<?, ?>) api.postJson("/api/sources",
                Map.of("type", "dir", "location", project.toString())).get("source")).get("key");
        String id = (String) api.postJson("/api/import-runs", Map.of("sources", List.of(key))).get("id");

        // 还没有主模型：不开始，状态不变
        LocalApi.Response noModel = api.postJson("/api/import-runs/" + id + "/start", Map.of());
        assertThat(noModel.status()).isEqualTo(409);
        assertThat((String) noModel.get("error")).contains("主模型");
        assertThat(api.get("/api/import-runs/" + id).get("status")).isEqualTo("estimated");

        FakeMainModel.configure(api, MODEL);
        LocalApi.Response started = api.postJson("/api/import-runs/" + id + "/start", Map.of());
        assertThat(started.status()).isEqualTo(202);
        assertThat(started.get("status")).isIn("running", "reviewing");

        LocalApi.Response reviewing = settle(id);
        assertThat(reviewing.get("status")).isEqualTo("reviewing");
        assertThat(reviewing.json()).containsKeys("progress", "candidates", "warnings");
        assertThat(reviewing.body()).doesNotContain(SECRET_TEXT).doesNotContain(DOC_TEXT);
        assertThat(MODEL.requests()).allSatisfy(request -> assertThat(request.body()).doesNotContain(SECRET_TEXT));
        assertThat(api.postJson("/api/import-runs/" + id + "/start", Map.of()).status()).isEqualTo(409);

        LocalApi.Response list = api.get("/api/import-runs");
        assertThat(list.status()).isEqualTo(200);
        assertThat((List<?>) list.get("runs")).map(run -> (Map<?, ?>) run)
                .anySatisfy(run -> assertThat(List.of(run.get("id"), run.get("status"), run.get("candidates"),
                        run.get("decided"))).isEqualTo(List.of(id, "reviewing", 5, 0)));

        String decisions = "/api/import-runs/" + id + "/decisions";
        LocalApi.Response accepted = api.postJson(decisions, Map.of("candidate", "f-001", "decision", "accept"));
        assertThat(accepted.status()).isEqualTo(200);
        assertThat(api.postJson(decisions, Map.of("candidate", "f-001", "decision", "reject")).status())
                .isEqualTo(409);
        assertThat(api.postJson(decisions, Map.of("candidate", "f-999", "decision", "accept")).status())
                .isEqualTo(404);
        assertThat(api.postJson(decisions, Map.of("candidate", "f-002", "decision", "approve")).status())
                .isEqualTo(400);
        assertThat(api.postJson(decisions, Map.of("decision", "accept")).status()).isEqualTo(400);
        LocalApi.Response classC = api.postJson(decisions, Map.of("candidate", "f-003", "decision", "edit",
                "value", Map.of("zh", "签证：H1B")));
        assertThat(classC.status()).isEqualTo(400);
        assertThat(api.post(decisions, "application/json", "{\"candidate\":\"f-002\",\"decision\":\"reject\"}",
                "Origin", "https://evil.example.com").status()).isEqualTo(403);
        for (String candidate : List.of("f-002", "f-003", "f-004", "p-001")) {
            assertThat(api.postJson(decisions, Map.of("candidate", candidate, "decision", "reject")).status())
                    .isEqualTo(200);
        }
        assertThat(api.get("/api/import-runs/" + id).get("status")).isEqualTo("done");
        assertThat(api.postJson("/api/import-runs/r-2026-01-01-999/start", Map.of()).status()).isEqualTo(404);
    }

    private LocalApi.Response settle(String id) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            LocalApi.Response response = api.get("/api/import-runs/" + id);
            if (!"running".equals(response.get("status"))) {
                return response;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("整理 30 秒内没有停下：" + id);
    }
}
