package jobapply.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import jobapply.config.FakeModelServer;
import jobapply.persistence.EnvFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 设置页「模型」的 HTTP 接口。模型提供方是本地的假服务端，Key 是测试自己生成的假值；storage 在临时目录。
 * 每个响应体都检查过不含 Key。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ModelSettingsControllerTest {

    private static final String BASE = "/api/settings/models";
    private static final String KEY = FakeModelServer.OPENAI_KEY;

    @TempDir
    static Path root;

    static FakeModelServer server;

    @LocalServerPort
    int port;

    private LocalApi api;
    private final List<String> bodies = new ArrayList<>();

    @BeforeAll
    static void startServer() {
        server = FakeModelServer.start();
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jobapply.storage.root", () -> root.resolve("storage").toString());
    }

    @BeforeEach
    void setUp() throws IOException {
        api = new LocalApi(port);
        server.toolCalls(true).fail(200, "");
        Path settings = root.resolve("storage/settings");
        if (Files.exists(settings)) {
            try (Stream<Path> walk = Files.walk(settings)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        // 每个用例开始时清掉分配，免得上一个用例的模型留在 ModelRegistry 里
        api.putJson(BASE + "/assignments", Map.of());
    }

    @AfterEach
    void noResponseEverContainsTheKey() {
        assertThat(bodies).isNotEmpty().allSatisfy(body -> assertThat(body).doesNotContain(KEY));
    }

    private LocalApi.Response record(LocalApi.Response response) {
        bodies.add(response.body());
        return response;
    }

    private LocalApi.Response putProvider(String id, String baseUrl, List<String> models) {
        Map<String, Object> body = new HashMap<>();
        body.put("preset", "custom");
        body.put("format", "openai");
        body.put("baseUrl", baseUrl);
        body.put("models", models);
        return record(api.putJson(BASE + "/providers/" + id, body));
    }

    private LocalApi.Response post(String path, Map<String, Object> body, String... headers) {
        return record(api.post(path, "application/json", tools.jackson.databind.json.JsonMapper.builder().build()
                .writeValueAsString(body), headers));
    }

    @Test
    void addKeyCatalogTestAndAssignTheMainModel() throws Exception {
        assertThat(putProvider("fake-gw", server.openAiBase(), List.of()).status()).isEqualTo(200);

        LocalApi.Response key = record(api.putJson(BASE + "/providers/fake-gw/key", Map.of("key", "  " + KEY + " ")));
        assertThat(key.status()).isEqualTo(200);
        assertThat(key.json()).containsExactly(Map.entry("keyLast4", "abcd"));

        LocalApi.Response catalog = post(BASE + "/providers/fake-gw/catalog", Map.of());
        assertThat(catalog.status()).isEqualTo(200);
        assertThat(catalog.get("models")).isEqualTo(List.of("fake-large", "fake-small"));
        assertThat(server.last().authorization()).isEqualTo("Bearer " + KEY);

        putProvider("fake-gw", server.openAiBase(), List.of("fake-large"));
        LocalApi.Response test = post(BASE + "/providers/fake-gw/test", Map.of("model", "fake-large"));
        assertThat(test.status()).isEqualTo(200);
        assertThat(test.json()).containsEntry("ok", true).containsEntry("supportsTools", true)
                .containsEntry("model", "fake-large-2026").containsEntry("promptTokens", 42)
                .containsKey("latencyMs");

        LocalApi.Response assigned = record(api.putJson(BASE + "/assignments",
                Map.of("main", Map.of("provider", "fake-gw", "model", "fake-large"))));
        assertThat(assigned.status()).isEqualTo(200);

        LocalApi.Response view = record(api.get(BASE));
        assertThat(view.status()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> provider = ((List<Map<String, Object>>) view.get("providers")).getFirst();
        assertThat(provider).containsEntry("id", "fake-gw").containsEntry("keySet", true)
                .containsEntry("keyLast4", "abcd").containsEntry("keyVariable", "FAKE_GW_API_KEY")
                .containsEntry("usedBy", List.of("main"));
        assertThat(view.json()).containsKey("presets");
        assertThat(view.get("assignments")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP).containsKey("main");

        Path env = root.resolve("storage/settings/.env");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(env))).isEqualTo("rw-------");
        String yaml = Files.readString(root.resolve("storage/settings/models.yaml"));
        assertThat(yaml).contains("${FAKE_GW_API_KEY}").doesNotContain(KEY);
    }

    @Test
    void mainModelMustPassTheToolCallTest() {
        putProvider("plain", server.openAiBase(), List.of("fake-small"));
        record(api.putJson(BASE + "/providers/plain/key", Map.of("key", KEY)));

        LocalApi.Response untested = record(api.putJson(BASE + "/assignments",
                Map.of("main", Map.of("provider", "plain", "model", "fake-small"))));
        assertThat(untested.status()).isEqualTo(409);
        assertThat(untested.get("error")).asString().contains("工具调用");

        server.toolCalls(false);
        LocalApi.Response test = post(BASE + "/providers/plain/test", Map.of("model", "fake-small"));
        assertThat(test.json()).containsEntry("ok", true).containsEntry("supportsTools", false);
        assertThat(record(api.putJson(BASE + "/assignments",
                Map.of("main", Map.of("provider", "plain", "model", "fake-small")))).status()).isEqualTo(409);

        // 辅助模型没有这个要求
        assertThat(record(api.putJson(BASE + "/assignments",
                Map.of("auxiliary", Map.of("provider", "plain", "model", "fake-small")))).status()).isEqualTo(200);
    }

    @Test
    void deletingAProviderInUseIsAConflictAndDeletingRemovesItsKey() {
        putProvider("gw", server.openAiBase(), List.of("fake-small"));
        record(api.putJson(BASE + "/providers/gw/key", Map.of("key", KEY)));
        record(api.putJson(BASE + "/assignments", Map.of("auxiliary", Map.of("provider", "gw", "model", "fake-small"))));

        LocalApi.Response inUse = record(api.delete(BASE + "/providers/gw"));
        assertThat(inUse.status()).isEqualTo(409);
        assertThat(inUse.get("error")).asString().contains("auxiliary");

        record(api.putJson(BASE + "/assignments", Map.of()));
        assertThat(record(api.delete(BASE + "/providers/gw")).status()).isEqualTo(204);
        assertThat(new EnvFile(root.resolve("storage/settings/.env"), name -> null).has("GW_API_KEY")).isFalse();
        assertThat(record(api.delete(BASE + "/providers/gw")).status()).isEqualTo(404);
    }

    @Test
    void errorsHaveTheRightStatus() {
        assertThat(record(api.putJson(BASE + "/providers/nope/key", Map.of("key", KEY))).status()).isEqualTo(404);
        assertThat(post(BASE + "/providers/nope/catalog", Map.of()).status()).isEqualTo(404);
        assertThat(post(BASE + "/providers/nope/test", Map.of("model", "m")).status()).isEqualTo(404);
        assertThat(putProvider("Bad_Id", server.openAiBase(), List.of()).status()).isEqualTo(400);
        assertThat(putProvider("ok", "ftp://x", List.of()).status()).isEqualTo(400);
        assertThat(record(api.putJson(BASE + "/providers/ok", Map.of("preset", "nope"))).status()).isEqualTo(400);

        putProvider("ok", server.openAiBase(), List.of("fake-small"));
        LocalApi.Response badKey = record(api.putJson(BASE + "/providers/ok/key", Map.of("key", "a=b")));
        assertThat(badKey.status()).isEqualTo(400);
        // yaml 里有占位符，但 .env 里的那一行没了（例如用户手动删掉）
        record(api.putJson(BASE + "/providers/ok/key", Map.of("key", KEY)));
        new EnvFile(root.resolve("storage/settings/.env"), name -> null).remove("OK_API_KEY");
        LocalApi.Response noKey = post(BASE + "/providers/ok/catalog", Map.of());
        assertThat(noKey.status()).isEqualTo(400);
        assertThat(noKey.get("error")).asString().contains("OK_API_KEY").contains("先填 Key");
        assertThat(post(BASE + "/providers/ok/test", Map.of("model", "not-selected")).status()).isEqualTo(400);
        assertThat(record(api.putJson(BASE + "/assignments",
                Map.of("main", Map.of("provider", "ghost", "model", "m")))).status()).isEqualTo(400);
        assertThat(record(api.post(BASE + "/providers/ok/catalog", "text/plain", "")).status()).isEqualTo(415);
    }

    @Test
    void gatewaysWithoutAModelListAre502WithTheReason() {
        putProvider("nolist", server.anthropicBase() + "/gateway-without-models", List.of());
        record(api.putJson(BASE + "/providers/nolist/key", Map.of("key", KEY)));

        LocalApi.Response response = post(BASE + "/providers/nolist/catalog", Map.of());

        assertThat(response.status()).isEqualTo(502);
        assertThat(response.get("error")).asString().contains("HTTP 404").contains("手填");
    }

    @Test
    void localModelsNeedNoKey() {
        record(api.putJson(BASE + "/providers/local", Map.of("preset", "ollama", "baseUrl", server.openAiBase(),
                "models", List.of("fake-small"))));

        LocalApi.Response test = post(BASE + "/providers/local/test", Map.of("model", "fake-small"));

        assertThat(test.status()).isEqualTo(200);
        assertThat(server.last().authorization()).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> provider = ((List<Map<String, Object>>) record(api.get(BASE)).get("providers")).getFirst();
        assertThat(provider).containsEntry("format", "openai").containsEntry("keySet", false)
                .containsEntry("preset", "ollama");
    }

    @Test
    void onlyAllowedOriginsMayCall() {
        assertThat(record(api.get(BASE, "Origin", "http://127.0.0.1:5173")).status()).isEqualTo(200);
        assertThat(record(api.get(BASE, "Origin", "http://localhost:5173")).status()).isEqualTo(200);
        assertThat(record(api.get(BASE)).status()).isEqualTo(200);
        LocalApi.Response evil = record(api.get(BASE, "Origin", "https://evil.example.com"));
        assertThat(evil.status()).isEqualTo(403);
        assertThat(evil.get("error")).asString().contains("Origin");
        assertThat(record(api.get(BASE, "Origin", "null")).status()).isEqualTo(403);
        assertThat(record(api.get(BASE, "Origin", "http://127.0.0.1:5174")).status()).isEqualTo(403);
        assertThat(post(BASE + "/providers/x/catalog", Map.of(), "Origin", "https://evil.example.com").status())
                .isEqualTo(403);
    }
}
