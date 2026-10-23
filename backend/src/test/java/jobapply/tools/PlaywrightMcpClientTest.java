package jobapply.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jobapply.config.BrowserMcpProperties;
import jobapply.harness.policies.BrowserToolPolicy;
import jobapply.harness.policies.ToolNotAllowedException;
import jobapply.persistence.StorageLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 通过真实的 stdio 子进程连接 {@link FakePlaywrightMcpServer}。不访问网络，不启动 Chrome，不调用 npx。
 */
class PlaywrightMcpClientTest {

    private static final String JOB_URL = "https://jobs.example.com/apply/1";
    private static final String JOB = "2026-10-22-001";

    @TempDir
    Path dir;

    @Test
    void navigatesAndSnapshotsOverStdio() {
        FakeBrowser fake = FakeBrowser.create(dir).page(JOB_URL, "Backend Engineer", FakeBrowser.jobPageSnapshot("u-1"));

        try (BrowserSession session = new BrowserSession(fake.launch())) {
            PageSnapshot opened = session.open(JOB_URL, JOB);
            PageSnapshot again = session.snapshot(JOB);

            assertThat(opened.url()).isEqualTo(JOB_URL);
            assertThat(opened.title()).isEqualTo("Backend Engineer");
            assertThat(opened.snapshot()).contains("我的投递");
            assertThat(again).isEqualTo(opened);
        }
        assertThat(fake.toolCalls()).containsExactly("browser_tabs", "browser_snapshot", "browser_snapshot");
    }

    @Test
    void neverSnapshotsATabThisJobDidNotOpen() {
        FakeBrowser fake = FakeBrowser.create(dir);

        try (BrowserSession session = new BrowserSession(fake.launch())) {
            assertThat(session.ownsTab(JOB)).isFalse();
            assertThatThrownBy(() -> session.snapshot(JOB))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("不能取快照");

            // 为另一个 Job 新开的标签页，也不能拿来给这个 Job 读
            session.open(JOB_URL, "2026-10-22-002");
            assertThat(session.ownsTab(JOB)).isFalse();
            assertThatThrownBy(() -> session.snapshot(JOB)).isInstanceOf(IllegalStateException.class);
        }
        assertThat(fake.toolCalls()).containsExactly("browser_tabs", "browser_snapshot");
    }

    @Test
    void rejectedCallsNeverReachTheServer() {
        FakeBrowser fake = FakeBrowser.create(dir);

        try (PlaywrightMcpClient client = PlaywrightMcpClient.start(fake.launch())) {
            for (String tool : List.of("browser_click", "browser_type", "browser_fill_form", "browser_evaluate",
                    "browser_run_code_unsafe", "browser_file_upload")) {
                assertThatThrownBy(() -> client.call(tool, Map.of())).isInstanceOf(ToolNotAllowedException.class);
            }
            assertThatThrownBy(() -> client.call("browser_navigate", Map.of("url", "javascript:alert(1)")))
                    .isInstanceOf(ToolNotAllowedException.class);
            assertThatThrownBy(() -> client.call("browser_snapshot", Map.of("filename", "x.md")))
                    .isInstanceOf(ToolNotAllowedException.class);
            assertThatThrownBy(() -> client.call("browser_tabs", Map.of("action", "select", "index", 0)))
                    .isInstanceOf(ToolNotAllowedException.class);
            client.call("browser_tabs", Map.of("action", "list"));
        }
        assertThat(fake.toolCalls()).containsExactly("browser_tabs");
    }

    @Test
    void modelCallsAreCheckedAgainstTheLatestSnapshotBeforeTheyAreSent() {
        FakeBrowser fake = FakeBrowser.create(dir);
        SnapshotIndex latest = SnapshotIndex.parse("""
                - textbox "姓名" [ref=e2]
                - button "下一步" [ref=e3]""");
        BrowserToolPolicy.Context model = BrowserToolPolicy.Context.model(latest, List.of(JOB_URL), null);

        try (PlaywrightMcpClient client = PlaywrightMcpClient.start(fake.launch())) {
            assertThatThrownBy(() -> client.call("browser_click", Map.of("target", "e99"), model))
                    .isInstanceOf(ToolNotAllowedException.class).hasMessageContaining("不要重试");
            assertThatThrownBy(() -> client.call("browser_click", Map.of("target", "#submit"), model))
                    .isInstanceOf(ToolNotAllowedException.class);
            assertThatThrownBy(() -> client.call("browser_type",
                    Map.of("target", "e2", "text", "x", "submit", true), model))
                    .isInstanceOf(ToolNotAllowedException.class);
            assertThatThrownBy(() -> client.call("browser_press_key", Map.of("key", "Enter"), model))
                    .isInstanceOf(ToolNotAllowedException.class);
            assertThatThrownBy(() -> client.call("browser_tabs", Map.of("action", "list"), model))
                    .isInstanceOf(ToolNotAllowedException.class);
            assertThatThrownBy(() -> client.call("browser_navigate", Map.of("url", "https://evil.example.org/"), model))
                    .isInstanceOf(ToolNotAllowedException.class);

            client.call("browser_type", Map.of("target", "e2", "text", "虚构姓名"), model);
        }
        assertThat(fake.toolCalls()).containsExactly("browser_type");
    }

    @Test
    void declaresRootsAndClearsTheOutputDirOnConnectAndClose() throws Exception {
        Path uploads = dir.resolve("storage/uploads");
        Path output = dir.resolve("storage/cache/playwright-mcp");
        Files.createDirectories(output.resolve("old"));
        Files.writeString(output.resolve("old/console.log"), "旧连接留下的内容");
        FakeBrowser fake = FakeBrowser.create(dir.resolve("fake"))
                .page(JOB_URL, "Backend Engineer", FakeBrowser.jobPageSnapshot("u-1"));

        try (BrowserSession session = new BrowserSession(fake.launch(Duration.ofSeconds(10), List.of(uploads), output))) {
            session.open(JOB_URL, JOB);
            assertThat(output).isEmptyDirectory();
            assertThat(uploads).isDirectory();
            assertThat(fake.lastRoots()).containsExactly(uploads.toUri().toString());

            Files.writeString(output.resolve("page-1.yml"), "这次连接写出的快照");
        }
        assertThat(output).isEmptyDirectory();
    }

    @Test
    void launchArgumentsKeepMcpFromWritingFilesElsewhere() {
        StorageLayout layout = new StorageLayout(dir.resolve("storage"));
        BrowserMcpProperties properties = new BrowserMcpProperties("npx",
                List.of("-y", "@playwright/mcp@0.0.83", "--extension", "--snapshot-mode", "none", "--console-level",
                        "error", "--image-responses", "omit", "--no-webmcp"), "", Duration.ofSeconds(60));

        BrowserLaunch launch = properties.toLaunch(layout);

        assertThat(launch.args()).containsSequence("--output-dir", layout.mcpOutputDir().toString());
        assertThat(launch.args()).containsSequence("--snapshot-mode", "none").contains("--no-webmcp")
                .doesNotContain("--caps");
        assertThat(launch.roots()).containsExactly(layout.uploadsDir());
        assertThat(launch.outputDir()).isEqualTo(layout.mcpOutputDir());
        assertThat(layout.mcpOutputDir()).startsWithRaw(layout.root().resolve("cache"));
    }

    @Test
    void childProcessOnlySeesPathAndHome() {
        FakeBrowser fake = FakeBrowser.create(dir);

        try (PlaywrightMcpClient ignored = PlaywrightMcpClient.start(fake.launch())) {
            // 只需要启动
        }
        List<String> names = fake.lastEnvNames();
        assertThat(names).contains("PATH", "HOME");
        assertThat(names).doesNotContain("USER", "LOGNAME", "SHELL", "TERM", "JAVA_HOME");
        // macOS 的 java 启动器会给自己加 JAVA_MAIN_CLASS_<pid> 一类的变量；除此之外不应有别的
        assertThat(names.stream().filter(name -> !Set.of("PATH", "HOME").contains(name)))
                .allMatch(name -> name.startsWith("JAVA_MAIN_CLASS_") || name.startsWith("__CF"));
    }

    @Test
    void missingRequiredToolFailsAndStopsTheProcess() {
        FakeBrowser fake = FakeBrowser.create(dir).omitTools("browser_snapshot");

        assertThatThrownBy(() -> PlaywrightMcpClient.start(fake.launch()))
                .isInstanceOf(BrowserException.ToolFailed.class)
                .hasMessageContaining("browser_snapshot");
    }

    @Test
    void serverExitingMidCallIsADisconnect() {
        FakeBrowser fake = FakeBrowser.create(dir).page(JOB_URL, "Backend Engineer", FakeBrowser.jobPageSnapshot("u-1"));

        try (BrowserSession session = new BrowserSession(fake.launch(Duration.ofSeconds(3)))) {
            session.open(JOB_URL, JOB);
            fake.exitOnNextCall();

            assertThatThrownBy(() -> session.snapshot(JOB)).isInstanceOf(BrowserException.Disconnected.class);
            assertThat(session.isConnected()).isFalse();
            assertThat(session.ownsTab(JOB)).isFalse();

            // 不自动重连（用户确认，2026-10-23）：用户发 reconnected 以后才重新连接（真实场景下要再同意一次），并且新开一个标签页
            assertThatThrownBy(() -> session.open(JOB_URL, JOB)).isInstanceOf(BrowserException.Disconnected.class);
            assertThat(fake.starts()).isEqualTo(1);
            session.reconnect();
            assertThat(session.open(JOB_URL, JOB).url()).isEqualTo(JOB_URL);
            assertThat(fake.starts()).isEqualTo(2);
        }
    }

    @Test
    void extensionConsentTimeoutIsADisconnect() {
        FakeBrowser fake = FakeBrowser.create(dir).toolError(
                "Error: Playwright extension did not connect within 30s after opening the connect page.");

        try (BrowserSession session = new BrowserSession(fake.launch())) {
            assertThatThrownBy(() -> session.open(JOB_URL, JOB))
                    .isInstanceOf(BrowserException.Disconnected.class)
                    .hasMessageContaining("did not connect within 30s");
            assertThat(session.isConnected()).isFalse();
        }
    }

    @Test
    void otherToolErrorsKeepTheConnection() {
        FakeBrowser fake = FakeBrowser.create(dir).toolError("Error: net::ERR_NAME_NOT_RESOLVED");

        try (BrowserSession session = new BrowserSession(fake.launch())) {
            assertThatThrownBy(() -> session.open(JOB_URL, JOB))
                    .isInstanceOf(BrowserException.ToolFailed.class)
                    .hasMessageContaining("ERR_NAME_NOT_RESOLVED");
            assertThat(session.isConnected()).isTrue();
        }
    }

    @Test
    void callTimeoutIsADisconnect() {
        FakeBrowser fake = FakeBrowser.create(dir).callDelay(Duration.ofSeconds(5));

        try (BrowserSession session = new BrowserSession(fake.launch(Duration.ofSeconds(1)))) {
            assertThatThrownBy(() -> session.open(JOB_URL, JOB)).isInstanceOf(BrowserException.Disconnected.class);
        }
    }

    @Test
    void closeStopsTheChildProcess() {
        FakeBrowser fake = FakeBrowser.create(dir);
        PlaywrightMcpClient client = PlaywrightMcpClient.start(fake.launch());
        long pid = ((Number) fake.records().get(0).get("pid")).longValue();
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive)).contains(true);

        client.close();

        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    void connectionErrorTextIsRecognised() {
        assertThat(PlaywrightMcpClient.isConnectionError("Target page, context or browser has been closed")).isTrue();
        assertThat(PlaywrightMcpClient.isConnectionError("Error: net::ERR_NAME_NOT_RESOLVED")).isFalse();
    }
}
