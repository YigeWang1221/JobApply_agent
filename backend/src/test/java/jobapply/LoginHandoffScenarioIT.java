package jobapply;

import static jobapply.api.LocalApi.path;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import jobapply.api.LocalApi;
import jobapply.domain.Actor;
import jobapply.domain.Job;
import jobapply.domain.JobEvent;
import jobapply.domain.PrepState;
import jobapply.domain.Transition;
import jobapply.persistence.JobRepository;
import jobapply.persistence.SiteRegistry;
import jobapply.persistence.TestStorage;
import jobapply.tools.BrowserLaunch;
import jobapply.tools.FakeBrowser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 端到端场景：启动整个应用，只通过本地 HTTP 走完「开始 → 登录 → 断开 → 重连（新开岗位页）→ 登录 → 身份确认 → ready」。
 * MCP 服务端是走真实 stdio 的假服务端，不访问网络，不启动 Chrome。数据全部虚构。只在 {@code mvn verify} 时运行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginHandoffScenarioIT {

    private static final String PROJECT = "fall-2026-v1";
    private static final String LOGIN_URL = "https://jobs.example.com/login?next=apply";

    @TempDir
    static Path root;

    static FakeBrowser fake;

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jobapply.storage.root", () -> root.resolve("storage").toString());
        fake = FakeBrowser.create(root.resolve("browser"));
        BrowserLaunch launch = fake.launch();
        registry.add("jobapply.browser.mcp.command", launch::command);
        for (int i = 0; i < launch.args().size(); i++) {
            String arg = launch.args().get(i);
            registry.add("jobapply.browser.mcp.args[" + i + "]", () -> arg);
        }
        registry.add("jobapply.browser.mcp.call-timeout", () -> "10s");
    }

    @Test
    void loginDisconnectReconnectAndIdentityOverHttp() {
        TestStorage storage = TestStorage.create(root.resolve("storage"), true);
        storage.project(PROJECT, "live");
        String session = storage.session(PROJECT);
        Job lead = storage.ledger().addLead(PROJECT, session, Actor.AGENT, TestStorage.posting("示例公司甲", "JD-1"), null);
        storage.ledger().transition(PROJECT, lead.id(), session, Actor.USER, new Transition.Convert());
        String job = lead.id();
        fake.page(LOGIN_URL, "账号登录", FakeBrowser.loginSnapshot()).redirect(lead.posting().url(), LOGIN_URL);
        LocalApi api = new LocalApi(port);

        // 开始：打开岗位链接，被重定向到登录页
        assertThat(api.postJson(path(job), Map.of("project", PROJECT, "session", session, "accountHint", "user-2002"))
                .status()).isEqualTo(202);
        assertThat(api.settle(job)).containsEntry("waitingFor", "login").containsEntry("detected", "LOGIN");

        // 用户登录；登录后页面变成岗位页。此时连接断开（扩展被关掉）
        fake.page(LOGIN_URL, "Backend Engineer - 示例公司甲", FakeBrowser.jobPageSnapshot("u***@example.com"))
                .exitOnNextCall();
        api.postJson(path(job) + "/signal", Map.of("session", session, "signal", "logged_in"));
        assertThat(api.settle(job)).containsEntry("waitingFor", "reconnect");

        // 用户在 Chrome 里重新同意连接：系统新开一个岗位页，照常交给用户确认登录
        api.postJson(path(job) + "/signal", Map.of("session", session, "signal", "reconnected"));
        assertThat(api.settle(job)).containsEntry("waitingFor", "login").containsEntry("detected", "NONE");
        api.postJson(path(job) + "/signal", Map.of("session", session, "signal", "logged_in"));
        Map<String, Object> identity = api.settle(job);
        assertThat(identity).containsEntry("waitingFor", "identity").containsEntry("identity", "unverified");

        // 页面上显示的是打码邮箱，和填写的标识对不上；用户确认账号没问题
        api.postJson(path(job) + "/signal", Map.of("session", session, "signal", "identity_confirmed"));
        Map<String, Object> ready = api.settle(job);
        assertThat(ready).containsEntry("status", "ready").containsEntry("identity", "user_confirmed");

        // 文件与历史
        assertThat(new JobRepository(storage.layout).load(PROJECT, job).status().prepState())
                .isEqualTo(PrepState.PREPARING);
        assertThat(storage.ledger().events(PROJECT, job).stream()
                .filter(event -> event.type() == JobEvent.Type.PREP_STATE_CHANGED)
                .map(event -> event.to()))
                .containsExactly("prep:preparing", "prep:needs_login", "prep:preparing");
        assertThat(storage.read(storage.layout.trackerDir(PROJECT).resolve("applications.md")))
                .contains("prep · preparing");
        assertThat(new SiteRegistry(storage.layout, storage.clock).rows()).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("站点", "https://jobs.example.com")
                        .containsEntry("账号标识", "user-2002")
                        .containsEntry("核对方式", "user_confirmed"));
        assertThat(storage.layout.checkpointsDb()).exists();
        assertThat(storage.isClean()).as("storage/.git 工作区干净（sqlite 被忽略）").isTrue();

        // 浏览器：两次连接，只出现过白名单内的工具
        assertThat(fake.starts()).isEqualTo(2);
        assertThat(Set.copyOf(fake.toolCalls())).isSubsetOf(Set.of("browser_tabs", "browser_snapshot"));
    }
}
