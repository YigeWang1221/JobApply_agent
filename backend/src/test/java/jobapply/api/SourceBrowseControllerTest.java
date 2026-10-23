package jobapply.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 目录选择框的接口，以及用「文件来自于「路径」」这类句子登记目录（C004-A-2 追加）。目录是 @TempDir 里的虚构项目。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SourceBrowseControllerTest {

    @TempDir
    static Path root;

    @LocalServerPort
    int port;

    private LocalApi api;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jobapply.storage.root", () -> root.resolve("storage").toString());
    }

    @BeforeEach
    void setUp() {
        api = new LocalApi(port);
    }

    @Test
    void browseAndRegisterFromASentence() throws Exception {
        Path projects = Files.createDirectories(root.resolve("projects/demo-app"));
        Files.writeString(projects.resolve("README.md"), "FILE-CONTENT-NOT-LISTED");

        LocalApi.Response listing = api.get("/api/sources/browse?path=" + root.resolve("projects").toRealPath());
        assertThat(listing.status()).isEqualTo(200);
        assertThat((List<?>) listing.get("directories")).singleElement()
                .satisfies(entry -> assertThat(((Map<?, ?>) entry).get("name")).isEqualTo("demo-app"));
        assertThat(listing.body()).doesNotContain("README").doesNotContain("FILE-CONTENT");
        assertThat(api.get("/api/sources/browse").status()).isEqualTo(200);
        assertThat(api.get("/api/sources/browse?path=relative").status()).isEqualTo(400);
        assertThat(api.get("/api/sources/browse", "Origin", "https://evil.example.com").status()).isEqualTo(403);

        LocalApi.Response registered = api.postJson("/api/sources",
                Map.of("type", "dir", "location", "文件来自于「" + projects + "」"));
        assertThat(registered.status()).isEqualTo(201);
        assertThat(((Map<?, ?>) registered.get("source")).get("location")).isEqualTo(projects.toRealPath().toString());
    }
}
