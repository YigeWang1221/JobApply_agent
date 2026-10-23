package jobapply.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
 * 估算接口。虚构项目里放一个秘密文件，确认它的内容不出现在任何返回里。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ImportRunControllerTest {

    static final String SECRET_TEXT = "SECRET-CONTENT-THAT-MUST-NOT-LEAK";
    static final String DOC_TEXT = "DOC-CONTENT-ALSO-NOT-RETURNED";

    @TempDir
    static Path root;

    static final FakeGitHubServer GITHUB = new FakeGitHubServer();

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
}
