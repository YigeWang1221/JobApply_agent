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
 * 资料页的 HTTP 接口。原件是仓库里虚构的测试资源；没有配置模型，所以轻量分析会跳过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AssetControllerTest {

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

    private static byte[] resource(String name) throws Exception {
        return AssetControllerTest.class.getResourceAsStream("/assets/" + name).readAllBytes();
    }

    @Test
    void uploadRegistersOnceAndDescriptionsCanBeEdited() throws Exception {
        byte[] pdf = resource("resume-demo.pdf");

        LocalApi.Response created = api.upload("/api/assets", "file", "resume-demo.pdf", pdf);
        assertThat(created.status()).isEqualTo(201);
        @SuppressWarnings("unchecked")
        Map<String, Object> asset = (Map<String, Object>) created.get("asset");
        assertThat(asset).containsEntry("fileName", "resume-demo.pdf").containsEntry("type", "application/pdf");
        assertThat(created.json()).containsEntry("duplicate", false);
        assertThat(created.get("analysisNote")).asString().contains("还没有配置模型");
        String key = (String) asset.get("key");
        assertThat(root.resolve("storage/base/originals/resume-demo.pdf")).exists();

        LocalApi.Response again = api.upload("/api/assets", "file", "another-name.pdf", pdf,
                "Origin", "http://127.0.0.1:5173");
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.json()).containsEntry("duplicate", true);
        assertThat(((Map<?, ?>) again.get("asset")).get("key")).isEqualTo(key);

        LocalApi.Response edited = api.patchJson("/api/assets/" + key, Map.of("description", "英文简历，2025 版，2 页"));
        assertThat(edited.status()).isEqualTo(200);
        assertThat(edited.get("description")).isEqualTo("英文简历，2025 版，2 页");

        LocalApi.Response list = api.get("/api/assets");
        assertThat(list.status()).isEqualTo(200);
        assertThat((List<?>) list.get("assets")).filteredOn(item -> key.equals(((Map<?, ?>) item).get("key")))
                .hasSize(1);
        assertThat(Files.readString(root.resolve("storage/base/assets.md"))).contains("英文简历，2025 版，2 页");
    }

    @Test
    void scannedPdfsGetTheirLabelWithoutAModel() throws Exception {
        LocalApi.Response created = api.upload("/api/assets", "file", "scanned-demo.pdf", resource("scanned-demo.pdf"));

        assertThat(created.status()).isEqualTo(201);
        assertThat(((Map<?, ?>) created.get("asset")).get("description")).isEqualTo("没有文字层，可能是扫描件");
    }

    @Test
    void badRequestsHaveTheRightStatus() throws Exception {
        assertThat(api.patchJson("/api/assets/a-2026-01-01-999", Map.of("description", "x")).status()).isEqualTo(404);
        assertThat(api.patchJson("/api/assets/..%2Fx", Map.of("description", "x")).status()).isIn(400, 404);
        assertThat(api.patchJson("/api/assets/bad", Map.of("description", "x")).status()).isEqualTo(400);
        assertThat(api.upload("/api/assets", "wrong-field", "x.txt", "虚构".getBytes()).status()).isEqualTo(400);
        assertThat(api.upload("/api/assets", "file", "empty.txt", new byte[0]).status()).isEqualTo(400);
        assertThat(api.post("/api/assets", "text/plain", "x").status()).isEqualTo(415);
        // multipart 只在上传原件的接口放行
        assertThat(api.upload("/api/settings/models/providers/x/catalog", "file", "x.txt", "x".getBytes()).status())
                .isEqualTo(415);
    }

    @Test
    void filesOver20MbAre413() {
        LocalApi.Response response = api.upload("/api/assets", "file", "big.bin", new byte[20 * 1024 * 1024 + 1]);

        assertThat(response.status()).isEqualTo(413);
        assertThat(response.get("error")).asString().contains("20MB");
    }

    @Test
    void webPagesThatAreNotOurFrontendAre403() throws Exception {
        LocalApi.Response response = api.upload("/api/assets", "file", "notes-demo.txt", resource("notes-demo.txt"),
                "Origin", "https://evil.example.com");

        assertThat(response.status()).isEqualTo(403);
        assertThat(api.get("/api/assets", "Origin", "https://evil.example.com").status()).isEqualTo(403);
    }
}
