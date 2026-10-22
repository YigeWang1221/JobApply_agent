package jobapply.workflow.graphs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import jobapply.domain.Actor;
import jobapply.domain.Job;
import jobapply.domain.JobEvent;
import jobapply.domain.PrepState;
import jobapply.domain.Transition;
import jobapply.persistence.JobRepository;
import jobapply.persistence.SiteRegistry;
import jobapply.persistence.TestStorage;
import jobapply.tools.BrowserSession;
import jobapply.tools.FakeBrowser;
import jobapply.workflow.state.LoginHandoffStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 登录接力线程：真实的 LangGraph4j 图 + sqlite Checkpoint + 通过 stdio 连接的假 MCP 服务端。数据全部虚构。
 */
class LoginHandoffRunnerTest {

    private static final String PROJECT = "fall-2026-v1";
    private static final String HOST = "https://jobs.example.com";
    private static final String LOGIN_URL = HOST + "/login?next=apply";
    private static final Duration WAIT = Duration.ofSeconds(30);

    @TempDir
    Path root;

    private TestStorage storage;
    private FakeBrowser fake;
    private BrowserSession browser;
    private LoginHandoffRunner runner;
    private String session;
    private String job;
    private String jobUrl;

    @BeforeEach
    void setUp() {
        storage = TestStorage.create(root.resolve("storage"), true);
        storage.project(PROJECT, "live");
        session = storage.session(PROJECT);
        Job lead = storage.ledger().addLead(PROJECT, session, Actor.AGENT, TestStorage.posting("示例公司甲", "JD-1"), null);
        storage.ledger().transition(PROJECT, lead.id(), session, Actor.USER, new Transition.Convert());
        job = lead.id();
        jobUrl = lead.posting().url();
        fake = FakeBrowser.create(root.resolve("browser"))
                .page(jobUrl, "Backend Engineer - 示例公司甲", FakeBrowser.jobPageSnapshot("user-1001"))
                .page(LOGIN_URL, "账号登录", FakeBrowser.loginSnapshot());
        browser = new BrowserSession(fake.launch());
        runner = new LoginHandoffRunner(storage.layout, storage.clock, browser);
    }

    @AfterEach
    void tearDown() {
        runner.close();
        browser.close();
    }

    @Test
    void handsOverEvenWithoutLoginPageThenMatchesAccountAndRecordsSite() {
        LoginHandoffStatus started = runner.start(job, PROJECT, session, "  USER-1001 ");
        assertThat(started.status()).isIn(LoginHandoffStatus.RUNNING, LoginHandoffStatus.WAITING);

        LoginHandoffStatus waiting = settle();
        assertThat(waiting.status()).isEqualTo(LoginHandoffStatus.WAITING);
        assertThat(waiting.waitingFor()).isEqualTo("login");
        assertThat(waiting.detected()).isEqualTo("NONE");
        assertThat(waiting.reason()).contains("请确认已经登录");
        assertThat(waiting.pageUrl()).isEqualTo(jobUrl);
        assertThat(prepState()).isEqualTo(PrepState.PREPARING);

        runner.signal(job, session, "logged_in", false);
        LoginHandoffStatus ready = settle();

        assertThat(ready.status()).isEqualTo(LoginHandoffStatus.READY);
        assertThat(ready.waitingFor()).isNull();
        assertThat(ready.identity()).isEqualTo("matched");
        assertThat(prepState()).isEqualTo(PrepState.PREPARING);
        assertThat(fake.toolCalls()).containsExactly("browser_tabs", "browser_snapshot", "browser_snapshot");
        assertThat(new SiteRegistry(storage.layout, storage.clock).rows()).singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("站点", HOST)
                        .containsEntry("账号标识", "USER-1001")
                        .containsEntry("核对方式", "matched"));
        assertThat(prepEvents()).containsExactly("prep:selected → prep:preparing");
        assertThat(storage.isClean()).isTrue();
    }

    @Test
    void loginPageMovesToNeedsLoginAndStaysUntilThePageChanges() {
        fake.redirect(jobUrl, LOGIN_URL);

        runner.start(job, PROJECT, session, null);
        LoginHandoffStatus waiting = settle();
        assertThat(waiting.detected()).isEqualTo("LOGIN");
        assertThat(waiting.reason()).startsWith("检测到登录页");
        assertThat(prepState()).isEqualTo(PrepState.NEEDS_LOGIN);

        runner.signal(job, session, "logged_in", false);
        LoginHandoffStatus still = settle();
        assertThat(still.waitingFor()).isEqualTo("login");
        assertThat(still.reason()).startsWith("仍在登录页");
        assertThat(prepState()).isEqualTo(PrepState.NEEDS_LOGIN);

        // 用户在 Chrome 里登录后，这个页面变成了岗位页（没填账号标识）
        fake.page(LOGIN_URL, "Backend Engineer - 示例公司甲", FakeBrowser.jobPageSnapshot("user-1001"));
        runner.signal(job, session, "logged_in", false);
        LoginHandoffStatus identity = settle();
        assertThat(identity.waitingFor()).isEqualTo("identity");
        assertThat(identity.identity()).isEqualTo("unverified");
        assertThat(identity.reason()).startsWith("没有填写账号标识");
        assertThat(prepState()).isEqualTo(PrepState.PREPARING);

        runner.signal(job, session, "identity_confirmed", false);
        LoginHandoffStatus ready = settle();
        assertThat(ready.status()).isEqualTo(LoginHandoffStatus.READY);
        assertThat(ready.identity()).isEqualTo("user_confirmed");
        assertThat(prepEvents()).containsExactly(
                "prep:selected → prep:preparing",
                "prep:preparing → prep:needs_login",
                "prep:needs_login → prep:preparing");
        List<JobEvent> events = storage.ledger().events(PROJECT, job);
        assertThat(events.get(events.size() - 2).actor()).isEqualTo(Actor.SYSTEM);
        assertThat(events.get(events.size() - 1).actor()).isEqualTo(Actor.USER);
        assertThat(events.get(events.size() - 1).reason()).isEqualTo("用户确认已登录");
    }

    @Test
    void overrideContinuesPastAMisdetectedLoginPageAndIsRecorded() {
        fake.redirect(jobUrl, LOGIN_URL);
        runner.start(job, PROJECT, session, "user-1001");
        settle();

        runner.signal(job, session, "logged_in", true);
        LoginHandoffStatus identity = settle();

        assertThat(identity.waitingFor()).isEqualTo("identity");
        assertThat(identity.reason()).startsWith("页面上没有找到账号标识");
        assertThat(prepState()).isEqualTo(PrepState.PREPARING);
        List<JobEvent> events = storage.ledger().events(PROJECT, job);
        assertThat(events.getLast().reason()).startsWith("用户 override");
        assertThat(events.get(events.size() - 2).type()).isEqualTo(JobEvent.Type.LOGIN_OVERRIDE);
    }

    @Test
    void wrongAccountGoesBackToLogin() {
        runner.start(job, PROJECT, session, "someone-else");
        settle();
        runner.signal(job, session, "logged_in", false);
        assertThat(settle().waitingFor()).isEqualTo("identity");

        runner.signal(job, session, "wrong_account", false);
        LoginHandoffStatus back = settle();

        assertThat(back.waitingFor()).isEqualTo("login");
        assertThat(back.reason()).startsWith("账号不对");
        assertThat(back.identity()).isNull();
        assertThat(prepState()).isEqualTo(PrepState.NEEDS_LOGIN);

        runner.signal(job, session, "logged_in", false);
        settle();
        runner.signal(job, session, "identity_confirmed", false);
        assertThat(settle().status()).isEqualTo(LoginHandoffStatus.READY);
        assertThat(prepState()).isEqualTo(PrepState.PREPARING);
    }

    @Test
    void disconnectWaitsForReconnectThenOpensAFreshTabAndHandsOverAgain() {
        runner.start(job, PROJECT, session, "user-1001");
        settle();
        fake.exitOnNextCall();

        runner.signal(job, session, "logged_in", false);
        LoginHandoffStatus disconnected = settle();
        assertThat(disconnected.waitingFor()).isEqualTo("reconnect");
        assertThat(disconnected.lastError()).isNotBlank();
        assertThat(disconnected.reason()).contains("reconnected");

        runner.signal(job, session, "reconnected", false);
        LoginHandoffStatus reopened = settle();
        assertThat(reopened.waitingFor()).isEqualTo("login");
        assertThat(reopened.pageUrl()).isEqualTo(jobUrl);

        runner.signal(job, session, "logged_in", false);
        assertThat(settle().status()).isEqualTo(LoginHandoffStatus.READY);
        assertThat(fake.starts()).isEqualTo(2);
        // 重连后不读「当前标签页」，先新开岗位页
        assertThat(fake.toolCalls()).containsExactly(
                "browser_tabs", "browser_snapshot", "browser_snapshot",
                "browser_tabs", "browser_snapshot", "browser_snapshot");
    }

    @Test
    void consentTimeoutAtStartWaitsForReconnect() {
        fake.toolError("Error: Playwright extension did not connect within 30s after opening the connect page.");

        runner.start(job, PROJECT, session, null);
        LoginHandoffStatus waiting = settle();
        assertThat(waiting.waitingFor()).isEqualTo("reconnect");
        assertThat(waiting.lastError()).contains("did not connect within 30s");

        fake.toolError(null);
        runner.signal(job, session, "reconnected", false);
        LoginHandoffStatus login = settle();
        assertThat(login.waitingFor()).isEqualTo("login");
        assertThat(login.pageUrl()).isEqualTo(jobUrl);
    }

    @Test
    void mismatchedSignalsAreRejectedAndChangeNothing() {
        runner.start(job, PROJECT, session, null);
        LoginHandoffStatus before = settle();
        Map<String, String> files = textFiles();

        assertThatThrownBy(() -> runner.signal(job, session, "identity_confirmed", false))
                .isInstanceOf(LoginHandoffException.Conflict.class)
                .hasMessageContaining("login");
        assertThatThrownBy(() -> runner.signal(job, session, "reconnected", false))
                .isInstanceOf(LoginHandoffException.Conflict.class);
        assertThatThrownBy(() -> runner.signal(job, session, "submit", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runner.signal(job, session, "reconnected", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("override");
        assertThatThrownBy(() -> runner.signal(job, "s-2026-10-22-999", "logged_in", false))
                .isInstanceOf(LoginHandoffException.NotFound.class);

        assertThat(runner.status(job)).isEqualTo(before);
        assertThat(textFiles()).isEqualTo(files);
        assertThat(fake.toolCalls()).containsExactly("browser_tabs", "browser_snapshot");
    }

    @Test
    void onlyOneHandoffAtATimeAndRejectedStartsWriteNothing() {
        Job other = storage.ledger().addLead(PROJECT, session, Actor.AGENT, TestStorage.posting("示例公司乙", "JD-2"), null);
        storage.ledger().transition(PROJECT, other.id(), session, Actor.USER, new Transition.Convert());
        runner.start(job, PROJECT, session, null);
        settle();
        Map<String, String> files = textFiles();

        assertThatThrownBy(() -> runner.start(other.id(), PROJECT, session, null))
                .isInstanceOf(LoginHandoffException.Conflict.class)
                .hasMessageContaining(job);

        assertThat(textFiles()).isEqualTo(files);
        assertThat(new JobRepository(storage.layout).load(PROJECT, other.id()).status().prepState())
                .isEqualTo(PrepState.SELECTED);
    }

    @Test
    void startValidatesBeforeWritingAnything() {
        Job lead = storage.ledger().addLead(PROJECT, session, Actor.AGENT, TestStorage.posting("示例公司丙", "JD-3"), null);
        Map<String, String> files = textFiles();

        assertThatThrownBy(() -> runner.start(lead.id(), PROJECT, session, null))
                .isInstanceOf(LoginHandoffException.Conflict.class)
                .hasMessageContaining("lead:new");
        assertThatThrownBy(() -> runner.start("2026-10-22-999", PROJECT, session, null))
                .isInstanceOf(LoginHandoffException.NotFound.class);
        assertThatThrownBy(() -> runner.start(job, PROJECT, "s-2026-10-22-999", null))
                .isInstanceOf(LoginHandoffException.NotFound.class);
        assertThatThrownBy(() -> runner.start(job, "no-such-project", session, null))
                .isInstanceOf(LoginHandoffException.NotFound.class);
        assertThatThrownBy(() -> runner.start(job, PROJECT, session, "x".repeat(129)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("128");
        assertThatThrownBy(() -> runner.start(job, PROJECT, session, "a|b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runner.start(job, PROJECT, session, "a\nb"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(textFiles()).isEqualTo(files);
        assertThat(storage.layout.checkpointsDb()).doesNotExist();
        assertThat(fake.starts()).isZero();
    }

    @Test
    void resumesFromSqliteAfterRestart() {
        runner.start(job, PROJECT, session, "user-1001");
        settle();
        restart();
        LoginHandoffStatus restored = runner.status(job);
        assertThat(restored.status()).isEqualTo(LoginHandoffStatus.WAITING);
        assertThat(restored.waitingFor()).isEqualTo("login");
        assertThat(restored.pageUrl()).isEqualTo(jobUrl);

        // 新连接上没有自己的标签页：先重新新开岗位页，再交给用户确认
        runner.signal(job, session, "logged_in", false);
        LoginHandoffStatus reopened = settle();
        assertThat(reopened.waitingFor()).isEqualTo("login");
        runner.signal(job, session, "logged_in", false);
        assertThat(settle().status()).isEqualTo(LoginHandoffStatus.READY);
        assertThat(fake.starts()).isEqualTo(2);
        assertThat(fake.toolCalls()).containsExactly(
                "browser_tabs", "browser_snapshot", "browser_tabs", "browser_snapshot", "browser_snapshot");
    }

    @Test
    void afterRestartAnotherJobsTabIsNeverReadForThisJob() {
        Job other = storage.ledger().addLead(PROJECT, session, Actor.AGENT, TestStorage.posting("示例公司乙", "JD-2"), null);
        storage.ledger().transition(PROJECT, other.id(), session, Actor.USER, new Transition.Convert());
        fake.page(other.posting().url(), "Backend Engineer - 示例公司乙", FakeBrowser.jobPageSnapshot("user-1001"));
        runner.start(job, PROJECT, session, "user-1001");
        settle();
        restart();

        // 重启后内存里没有「谁在用浏览器」：B 可以开始，并新开了 B 的标签页
        runner.start(other.id(), PROJECT, session, "user-1001");
        assertThat(runner.awaitIdle(WAIT)).isTrue();
        assertThat(runner.status(other.id()).waitingFor()).isEqualTo("login");

        // B 还在等待时，A 的信号要被拒绝
        assertThatThrownBy(() -> runner.signal(job, session, "logged_in", false))
                .isInstanceOf(LoginHandoffException.Conflict.class)
                .hasMessageContaining(other.id());

        // B 结束后，A 的信号不能读 B 的标签页：A 要重新新开自己的岗位页
        runner.signal(other.id(), session, "logged_in", false);
        assertThat(runner.awaitIdle(WAIT)).isTrue();
        assertThat(runner.status(other.id()).status()).isEqualTo(LoginHandoffStatus.READY);
        runner.signal(job, session, "logged_in", false);
        LoginHandoffStatus reopened = settle();
        assertThat(reopened.status()).isEqualTo(LoginHandoffStatus.WAITING);
        assertThat(reopened.waitingFor()).isEqualTo("login");
        assertThat(reopened.pageUrl()).isEqualTo(jobUrl);
    }

    @Test
    void signalsAreRejectedOnceTheJobLeftTheHandoffStates() {
        runner.start(job, PROJECT, session, null);
        settle();
        runner.signal(job, session, "logged_in", false);
        LoginHandoffStatus identity = settle();
        assertThat(identity.waitingFor()).isEqualTo("identity");
        storage.ledger().transition(PROJECT, job, session, Actor.USER, new Transition.ChangePrepState(PrepState.PAUSED));
        Map<String, String> files = textFiles();

        assertThatThrownBy(() -> runner.signal(job, session, "identity_confirmed", false))
                .isInstanceOf(LoginHandoffException.Conflict.class)
                .hasMessageContaining("prep:paused");

        assertThat(runner.status(job)).isEqualTo(identity);
        assertThat(textFiles()).isEqualTo(files);
        assertThat(storage.layout.browserSites()).doesNotExist();
        assertThat(prepState()).isEqualTo(PrepState.PAUSED);
    }

    @Test
    void overrideIsAuditedEvenWhenPrepStateDoesNotChange() {
        runner.start(job, PROJECT, session, null);
        settle();
        assertThat(prepState()).isEqualTo(PrepState.PREPARING);
        // 识别规则这次把页面当成了登录页，用户确认自己已经登录
        fake.page(jobUrl, "账号登录", FakeBrowser.loginSnapshot());

        runner.signal(job, session, "logged_in", true);
        assertThat(settle().waitingFor()).isEqualTo("identity");

        assertThat(prepState()).isEqualTo(PrepState.PREPARING);
        JobEvent override = storage.ledger().events(PROJECT, job).getLast();
        assertThat(override.type()).isEqualTo(JobEvent.Type.LOGIN_OVERRIDE);
        assertThat(override.actor()).isEqualTo(Actor.USER);
        assertThat(override.from()).isEqualTo("prep:preparing");
        assertThat(override.to()).isEqualTo("prep:preparing");
        assertThat(override.reason()).contains("密码输入框");
        assertThat(storage.isClean()).isTrue();
    }

    @Test
    void startingAgainAfterReadyBeginsAFreshRun() {
        runner.start(job, PROJECT, session, "user-1001");
        settle();
        runner.signal(job, session, "logged_in", false);
        assertThat(settle().identity()).isEqualTo("matched");

        runner.start(job, PROJECT, session, null);
        LoginHandoffStatus fresh = settle();

        assertThat(fresh.waitingFor()).isEqualTo("login");
        assertThat(fresh.identity()).isNull();
    }

    @Test
    void unknownThreadIsNotFound() {
        assertThatThrownBy(() -> runner.status(job)).isInstanceOf(LoginHandoffException.NotFound.class);
        assertThatThrownBy(() -> runner.signal(job, session, "logged_in", false))
                .isInstanceOf(LoginHandoffException.NotFound.class);
    }

    private void restart() {
        runner.close();
        browser.close();
        browser = new BrowserSession(fake.launch());
        runner = new LoginHandoffRunner(storage.layout, storage.clock, browser);
    }

    private LoginHandoffStatus settle() {
        assertThat(runner.awaitIdle(WAIT)).as("登录接力在 %s 内停下", WAIT).isTrue();
        return runner.status(job);
    }

    private PrepState prepState() {
        return new JobRepository(storage.layout).load(PROJECT, job).status().prepState();
    }

    private List<String> prepEvents() {
        return storage.ledger().events(PROJECT, job).stream()
                .filter(event -> event.type() == JobEvent.Type.PREP_STATE_CHANGED)
                .map(event -> event.from() + " → " + event.to())
                .toList();
    }

    /** storage 里除 .git 和 sqlite 之外的文本文件。 */
    private Map<String, String> textFiles() {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(storage.layout.root())) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> !storage.layout.relative(path).startsWith(".git/"))
                    .filter(path -> !path.getFileName().toString().contains(".sqlite"))
                    .forEach(path -> files.put(storage.layout.relative(path), storage.read(path)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }
}
