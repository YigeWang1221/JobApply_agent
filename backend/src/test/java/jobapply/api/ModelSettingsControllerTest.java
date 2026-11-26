package jobapply.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import jobapply.config.FakeModelServer;
import jobapply.persistence.EnvFile;
import org.junit.jupiter.api.AfterAll;
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

    private LocalApi.Response putProvider(String id, String baseUrl, List<String> models) {
        Map<String, Object> body = new HashMap<>();
        body.put("preset", "custom");
        body.put("format", "openai");
        body.put("baseUrl", baseUrl);
        body.put("models", models);
        return api.putJson(BASE + "/providers/" + id, body);
    }

    private LocalApi.Response post(String path, Map<String, Object> body, String... headers) {
        return api.post(path, "application/json", tools.jackson.databind.json.JsonMapper.builder().build()
                .writeValueAsString(body), headers);
    }

    @Test
    void addKeyCatalogTestAndAssignTheMainModel() throws Exception {
        assertThat(putProvider("fake-gw", server.openAiBase(), List.of()).status()).isEqualTo(200);

        LocalApi.Response key = api.putJson(BASE + "/providers/fake-gw/key", Map.of("key", "  " + KEY + " "));
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

        LocalApi.Response assigned = api.putJson(BASE + "/assignments",
                Map.of("main", Map.of("provider", "fake-gw", "model", "fake-large")));
        assertThat(assigned.status()).isEqualTo(200);

        LocalApi.Response view = api.get(BASE);
        assertThat(view.status()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> provider = ((List<Map<String, Object>>) view.get("providers")).getFirst();
        assertThat(provider).containsEntry("id", "fake-gw").containsEntry("keySet", true)
                .containsEntry("keyLast4", "abcd").containsEntry("keyVariable", "FAKE_GW_API_KEY")
                .containsEntry("usedBy", List.of("main"));
        assertThat(view.json()).containsKey("presets");
        assertThat(view.get("assignments")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP).containsKey("main");

        String yaml = Files.readString(root.resolve("storage/settings/models.yaml"));
        assertThat(yaml).contains("${FAKE_GW_API_KEY}").doesNotContain(KEY);
    }

    @Test
    void mainModelMustPassTheToolCallTest() {
        putProvider("plain", server.openAiBase(), List.of("fake-small"));
        api.putJson(BASE + "/providers/plain/key", Map.of("key", KEY));

        LocalApi.Response untested = api.putJson(BASE + "/assignments",
                Map.of("main", Map.of("provider", "plain", "model", "fake-small")));
        assertThat(untested.status()).isEqualTo(409);
        assertThat(untested.get("error")).asString().contains("工具调用");

        server.toolCalls(false);
        LocalApi.Response test = post(BASE + "/providers/plain/test", Map.of("model", "fake-small"));
        assertThat(test.json()).containsEntry("ok", true).containsEntry("supportsTools", false);
        assertThat(api.putJson(BASE + "/assignments",
                Map.of("main", Map.of("provider", "plain", "model", "fake-small"))).status()).isEqualTo(409);

        // 辅助模型没有这个要求
        assertThat(api.putJson(BASE + "/assignments",
                Map.of("auxiliary", Map.of("provider", "plain", "model", "fake-small"))).status()).isEqualTo(200);
    }

    @Test
    void changingTheEndpointInvalidatesOldTests() {
        putProvider("moving", server.openAiBase(), List.of("fake-large"));
        api.putJson(BASE + "/providers/moving/key", Map.of("key", KEY));
        assertThat(post(BASE + "/providers/moving/test", Map.of("model", "fake-large")).json())
                .containsEntry("supportsTools", true);

        // 端点不变、只是写法不同（末尾多了 /）：测试结果保留；其它写法差异见 ModelSettingsServiceTest
        putProvider("moving", server.openAiBase() + "/", List.of("fake-large", "fake-small"));
        assertThat(testedModels("moving")).containsExactly("fake-large");
        assertThat(api.putJson(BASE + "/assignments",
                Map.of("main", Map.of("provider", "moving", "model", "fake-large"))).status()).isEqualTo(200);

        // 真的换了端点：照常保存，用途分配不动，测试记录作废，重新分配主模型要先重测
        assertThat(putProvider("moving", "http://127.0.0.1:9/v1", List.of("fake-large")).status()).isEqualTo(200);
        assertThat(testedModels("moving")).isEmpty();
        api.putJson(BASE + "/assignments", Map.of());
        LocalApi.Response stale = api.putJson(BASE + "/assignments",
                Map.of("main", Map.of("provider", "moving", "model", "fake-large")));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.get("error")).asString().contains("工具调用");

        // 只换接口格式也一样
        putProvider("fmt", server.openAiBase(), List.of("fake-large"));
        api.putJson(BASE + "/providers/fmt/key", Map.of("key", KEY));
        post(BASE + "/providers/fmt/test", Map.of("model", "fake-large"));
        Map<String, Object> body = new HashMap<>();
        body.put("preset", "custom");
        body.put("format", "anthropic");
        body.put("baseUrl", server.openAiBase());
        body.put("models", List.of("fake-large"));
        assertThat(api.putJson(BASE + "/providers/fmt", body).status()).isEqualTo(200);
        assertThat(testedModels("fmt")).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private List<Object> testedModels(String id) {
        Map<String, Object> provider = ((List<Map<String, Object>>) api.get(BASE).get("providers")).stream()
                .filter(p -> id.equals(p.get("id"))).findFirst().orElseThrow();
        return ((List<Map<String, Object>>) provider.get("tested")).stream().map(t -> t.get("model")).toList();
    }

    @Test
    void deletingAProviderInUseClearsItsAssignmentsAndRemovesItsKey() {
        putProvider("gw", server.openAiBase(), List.of("fake-small", "fake-large"));
        api.putJson(BASE + "/providers/gw/key", Map.of("key", KEY));
        api.putJson(BASE + "/assignments", Map.of("auxiliary", Map.of("provider", "gw", "model", "fake-small"),
                "crossCheck", Map.of("provider", "gw", "model", "fake-large")));

        // 去掉正被使用的模型：保存成功，那条分配清掉
        assertThat(putProvider("gw", server.openAiBase(), List.of("fake-large")).status()).isEqualTo(200);
        assertThat(api.get(BASE).get("assignments")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.MAP).doesNotContainKey("auxiliary").containsKey("cross_check");

        assertThat(api.delete(BASE + "/providers/gw").status()).isEqualTo(204);
        assertThat(api.get(BASE).get("assignments")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.MAP).isEmpty();
        assertThat(new EnvFile(root.resolve("storage/settings/.env"), name -> null).has("GW_API_KEY")).isFalse();
        assertThat(api.delete(BASE + "/providers/gw").status()).isEqualTo(404);
    }

    @Test
    void errorsHaveTheRightStatus() {
        assertThat(api.putJson(BASE + "/providers/nope/key", Map.of("key", KEY)).status()).isEqualTo(404);
        assertThat(post(BASE + "/providers/nope/catalog", Map.of()).status()).isEqualTo(404);
        assertThat(post(BASE + "/providers/nope/test", Map.of("model", "m")).status()).isEqualTo(404);
        assertThat(putProvider("Bad_Id", server.openAiBase(), List.of()).status()).isEqualTo(400);
        assertThat(putProvider("ok", "ftp://x", List.of()).status()).isEqualTo(400);
        assertThat(api.putJson(BASE + "/providers/ok", Map.of("preset", "nope")).status()).isEqualTo(400);

        putProvider("ok", server.openAiBase(), List.of("fake-small"));
        LocalApi.Response badKey = api.putJson(BASE + "/providers/ok/key", Map.of("key", "a=b"));
        assertThat(badKey.status()).isEqualTo(400);
        // yaml 里有占位符，但 .env 里的那一行没了（例如用户手动删掉）
        api.putJson(BASE + "/providers/ok/key", Map.of("key", KEY));
        new EnvFile(root.resolve("storage/settings/.env"), name -> null).remove("OK_API_KEY");
        LocalApi.Response noKey = post(BASE + "/providers/ok/catalog", Map.of());
        assertThat(noKey.status()).isEqualTo(400);
        assertThat(noKey.get("error")).asString().contains("OK_API_KEY").contains("先填 Key");
        assertThat(post(BASE + "/providers/ok/test", Map.of("model", "not-selected")).status()).isEqualTo(400);
        assertThat(api.putJson(BASE + "/assignments",
                Map.of("main", Map.of("provider", "ghost", "model", "m"))).status()).isEqualTo(400);
        assertThat(api.post(BASE + "/providers/ok/catalog", "text/plain", "").status()).isEqualTo(415);
    }

    @Test
    void gatewaysWithoutAModelListAre502WithTheReason() {
        putProvider("nolist", server.anthropicBase() + "/gateway-without-models", List.of());
        api.putJson(BASE + "/providers/nolist/key", Map.of("key", KEY));

        LocalApi.Response response = post(BASE + "/providers/nolist/catalog", Map.of());

        assertThat(response.status()).isEqualTo(502);
        assertThat(response.get("error")).asString().contains("HTTP 404").contains("手填");
    }

    @Test
    void localModelsNeedNoKey() {
        api.putJson(BASE + "/providers/local", Map.of("preset", "ollama", "baseUrl", server.openAiBase(),
                "models", List.of("fake-small")));

        LocalApi.Response test = post(BASE + "/providers/local/test", Map.of("model", "fake-small"));

        assertThat(test.status()).isEqualTo(200);
        assertThat(server.last().authorization()).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> provider = ((List<Map<String, Object>>) api.get(BASE).get("providers")).getFirst();
        assertThat(provider).containsEntry("format", "openai").containsEntry("keySet", false)
                .containsEntry("preset", "ollama");
    }
}
