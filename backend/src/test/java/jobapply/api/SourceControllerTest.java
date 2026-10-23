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
 * 「来源」的接口。GitHub 指向本机的假服务端；本地目录是 @TempDir 里的虚构项目。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SourceControllerTest {

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
    void registersDirectoriesAndRepositoriesListsAndDeletes() throws Exception {
        Path project = Files.createDirectories(root.resolve("demo-project"));

        LocalApi.Response dir = api.postJson("/api/sources", Map.of("type", "dir", "location", project.toString()));
        assertThat(dir.status()).isEqualTo(201);
        Map<?, ?> source = (Map<?, ?>) dir.get("source");
        assertThat(source.get("type")).isEqualTo("dir");
        assertThat(source.get("location")).isEqualTo(project.toRealPath().toString());
        String dirKey = (String) source.get("key");
        assertThat(api.postJson("/api/sources", Map.of("type", "dir", "location", project.toString())).get("duplicate"))
                .isEqualTo(true);

        LocalApi.Response repo = api.postJson("/api/sources",
                Map.of("type", "github", "location", "https://github.com/example-owner/demo"));
        assertThat(repo.status()).isEqualTo(201);
        assertThat(((Map<?, ?>) repo.get("source")).get("version")).isEqualTo(FakeGitHubServer.SHA);
        assertThat(((Map<?, ?>) repo.get("source")).get("location")).isEqualTo("example-owner/demo");

        LocalApi.Response owner = api.postJson("/api/sources", Map.of("type", "github", "location", "example-owner"));
        assertThat(owner.status()).isEqualTo(200);
        assertThat((List<?>) owner.get("repositories")).hasSize(2);
        assertThat(owner.json()).doesNotContainKey("source");

        assertThat((List<?>) api.get("/api/sources").get("sources")).hasSize(2);
        assertThat(api.delete("/api/sources/" + dirKey).status()).isEqualTo(200);
        assertThat(api.delete("/api/sources/" + dirKey).status()).isEqualTo(404);
        assertThat(Files.isDirectory(project)).isTrue();
        assertThat((List<?>) api.get("/api/sources").get("sources")).hasSize(1);
    }

    @Test
    void badRequestsHaveTheRightStatus() {
        assertThat(api.postJson("/api/sources", Map.of("type", "dir", "location", "relative")).status()).isEqualTo(400);
        assertThat(api.postJson("/api/sources", Map.of("type", "dir", "location", root.resolve("storage").toString()))
                .status()).isEqualTo(400);
        assertThat(api.postJson("/api/sources", Map.of("type", "ftp", "location", "x")).status()).isEqualTo(400);
        LocalApi.Response missing = api.postJson("/api/sources", Map.of("type", "github", "location", "example-owner/ghost"));
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.get("error")).asString().contains("不是公开的");
        assertThat(api.delete("/api/sources/bad").status()).isEqualTo(400);
        assertThat(api.post("/api/sources", "text/plain", "x").status()).isEqualTo(415);
    }

    @Test
    void rateLimitIs429() {
        GITHUB.reply("/repos/example-owner/limited", new FakeGitHubServer.Reply(403, "{}",
                Map.of("x-ratelimit-remaining", "0", "x-ratelimit-reset", "1798761600")));

        LocalApi.Response response = api.postJson("/api/sources",
                Map.of("type", "github", "location", "example-owner/limited"));

        assertThat(response.status()).isEqualTo(429);
        assertThat(response.get("error")).asString().contains("额度用完");
    }

    @Test
    void webPagesThatAreNotOurFrontendAre403() {
        assertThat(api.get("/api/sources", "Origin", "https://evil.example.com").status()).isEqualTo(403);
        assertThat(api.get("/api/sources", "Origin", "http://127.0.0.1:5173").status()).isEqualTo(200);
    }
}
