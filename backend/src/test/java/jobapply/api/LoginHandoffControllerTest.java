package jobapply.api;

import static jobapply.api.LocalApi.path;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jobapply.domain.Actor;
import jobapply.domain.Job;
import jobapply.domain.Transition;
import jobapply.persistence.TestStorage;
import jobapply.tools.BrowserLaunch;
import jobapply.tools.FakeBrowser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * HTTP 层：状态码、错误体、本地请求防护。MCP 命令指向假服务端，storage 指向临时目录。数据全部虚构。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class LoginHandoffControllerTest {

    private static final String PROJECT = "fall-2026-v1";

    @TempDir
    static Path root;

    @LocalServerPort
    int port;

    private LocalApi api;
    private TestStorage storage;
    private String session;
    private String job;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jobapply.storage.root", () -> root.resolve("storage").toString());
        BrowserLaunch launch = FakeBrowser.create(root.resolve("browser")).launch();
        registry.add("jobapply.browser.mcp.command", launch::command);
        for (int i = 0; i < launch.args().size(); i++) {
            String arg = launch.args().get(i);
            registry.add("jobapply.browser.mcp.args[" + i + "]", () -> arg);
        }
        registry.add("jobapply.browser.mcp.call-timeout", () -> "10s");
    }

    @BeforeEach
    void setUp() {
        api = new LocalApi(port);
        Path storageRoot = root.resolve("storage");
        storage = storageRoot.resolve(".gitignore").toFile().exists()
                ? TestStorage.open(storageRoot)
                : TestStorage.create(storageRoot, false);
        if (!storage.projects().exists(PROJECT)) {
            storage.project(PROJECT, "live");
        }
        session = storage.session(PROJECT);
        Job lead = storage.ledger().addLead(PROJECT, session, Actor.AGENT,
                TestStorage.posting("示例公司" + System.nanoTime(), "JD-" + System.nanoTime()), null);
        storage.ledger().transition(PROJECT, lead.id(), session, Actor.USER, new Transition.Convert());
        job = lead.id();
    }

    @Test
    void startAcceptsThenGetShowsTheWaitPointAndSignalFinishes() {
        LocalApi.Response started = api.postJson(path(job), Map.of("project", PROJECT, "session", session));
        assertThat(started.status()).isEqualTo(202);
        assertThat(started.get("job")).isEqualTo(job);

        Map<String, Object> waiting = api.settle(job);
        assertThat(waiting).containsEntry("status", "waiting").containsEntry("waitingFor", "login");
        assertThat(waiting).containsKeys("reason", "detected", "pageUrl", "pageTitle", "identity", "lastError");

        LocalApi.Response signalled = api.postJson(path(job) + "/signal",
                Map.of("session", session, "signal", "logged_in"));
        assertThat(signalled.status()).isEqualTo(202);
        assertThat(api.settle(job)).containsEntry("status", "waiting").containsEntry("waitingFor", "identity");
    }

    @Test
    void unknownJobsAndThreadsAre404() {
        assertThat(api.get(path("2026-10-22-999")).status()).isEqualTo(404);
        assertThat(api.get(path(job)).status()).isEqualTo(404);
        LocalApi.Response start = api.postJson(path("2026-10-22-999"), Map.of("project", PROJECT, "session", session));
        assertThat(start.status()).isEqualTo(404);
        assertThat(start.get("error")).asString().contains("Job 不存在");
        assertThat(api.postJson(path(job) + "/signal", Map.of("session", session, "signal", "logged_in")).status())
                .isEqualTo(404);
    }

    @Test
    void mismatchedSignalIs409() {
        api.postJson(path(job), Map.of("project", PROJECT, "session", session));
        api.settle(job);

        LocalApi.Response response = api.postJson(path(job) + "/signal",
                Map.of("session", session, "signal", "identity_confirmed"));

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.get("error")).asString().contains("现在等待的是 login");
    }

    @Test
    void badRequestsAre400() {
        assertThat(api.postJson(path(job), Map.of("session", session)).get("error")).isEqualTo("缺少 project");
        assertThat(api.postJson(path(job), Map.of("project", PROJECT)).status()).isEqualTo(400);
        assertThat(api.postJson(path(job), Map.of("project", PROJECT, "session", session,
                "accountHint", "x".repeat(129))).status()).isEqualTo(400);
        assertThat(api.postJson(path(job), Map.of("project", PROJECT, "session", session,
                "accountHint", "a|b")).status()).isEqualTo(400);
        assertThat(api.postJson(path(job), Map.of("project", PROJECT, "session", session,
                "accountHint", "a\nb")).status()).isEqualTo(400);
        assertThat(api.post(path(job), "application/json", "{not json").status()).isEqualTo(400);
        assertThat(api.postJson(path(job) + "/signal", Map.of("session", session, "signal", "submit")).status())
                .isEqualTo(400);
        assertThat(api.get(path("../../etc")).status()).isIn(400, 404);
    }

    @Test
    void blankAccountHintIsFine() {
        Map<String, Object> body = new HashMap<>(Map.of("project", PROJECT, "session", session, "accountHint", "  "));

        assertThat(api.postJson(path(job), body).status()).isEqualTo(202);
        assertThat(api.settle(job)).containsEntry("waitingFor", "login");
    }

    @Test
    void requestsFromWebPagesAre403() {
        LocalApi.Response get = api.get(path(job), "Origin", "https://evil.example.com");
        LocalApi.Response post = api.post(path(job), "application/json",
                "{\"project\":\"" + PROJECT + "\",\"session\":\"" + session + "\"}", "Origin", "null");

        assertThat(get.status()).isEqualTo(403);
        assertThat(post.status()).isEqualTo(403);
        assertThat(post.get("error")).asString().contains("Origin");
        assertThat(api.get(path(job)).status()).isEqualTo(404);
    }

    @Test
    void nonJsonWritesAre415() {
        String json = "{\"project\":\"" + PROJECT + "\",\"session\":\"" + session + "\"}";

        for (String contentType : List.of("text/plain", "application/x-www-form-urlencoded", "multipart/form-data")) {
            LocalApi.Response response = api.post(path(job), contentType, json);
            assertThat(response.status()).as(contentType).isEqualTo(415);
            assertThat(response.get("error")).asString().contains("application/json");
        }
        assertThat(api.post(path(job), null, json).status()).isEqualTo(415);
        assertThat(api.get(path(job)).status()).as("没有开始任何线程").isEqualTo(404);
    }
}
