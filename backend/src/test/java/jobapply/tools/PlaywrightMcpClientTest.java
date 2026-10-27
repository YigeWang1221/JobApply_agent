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
 * 通过真实的子进程（本机回环地址上的 Streamable HTTP）连接 {@link FakePlaywrightMcpServer}。不访问网络，不启动 Chrome，不调用 npx。
 */
class PlaywrightMcpClientTest {

    private static final String JOB_URL = "https://jobs.example.com/apply/1";
    private static final String JOB = "2026-10-22-001";

    @TempDir
    Path dir;

    /**
     * C016：选下拉、设日期的值只以 JSON 嵌进脚本开头那一行；值里有引号、反斜杠、换行时生成的代码仍是同一个值
     * （这里错了会把错的值填进申请表）。
     */
    @Test
    void scriptArgumentsSurviveQuotesBackslashesAndNewlines() {
        List<String> values = List.of("a\"b", "c\\d", "e\nf", "g'h`${x}</script>", "第二层");
        String code = PageScripts.code(PageScripts.SELECT, Map.of("target", "aria-ref=e5", "values", values));

        assertThat(code).startsWith("async (page) => {\nconst args = ").contains("// jobapply:select").endsWith("\n}");
        String line = code.lines().skip(1).findFirst().orElseThrow();
        assertThat(line).startsWith("const args = ").endsWith(";");
        Map<?, ?> parsed = tools.jackson.databind.json.JsonMapper.builder().build()
                .readValue(line.substring("const args = ".length(), line.length() - 1), Map.class);
        assertThat(parsed.get("values")).isEqualTo(values);
        assertThat(parsed.get("target")).isEqualTo("aria-ref=e5");
    }

    @Test
    void navigatesAndSnapshotsOverHttp() {
        FakeBrowser fake = FakeBrowser.create(dir).page(JOB_URL, "Backend Engineer", FakeBrowser.jobPageSnapshot("u-1"));

        try (BrowserSession session = new BrowserSession(fake.launch())) {
            PageSnapshot opened = session.open(JOB_URL, JOB);
            PageSnapshot again = session.snapshot(JOB);

            assertThat(opened.url()).isEqualTo(JOB_URL);
            assertThat(opened.title()).isEqualTo("Backend Engineer");
            assertThat(opened.snapshot()).contains("我的投递");
            assertThat(again).isEqualTo(opened);
        }
        // 保活会话的一次列表；开页（新标签页、焦点模拟、快照）；取快照是只读的，不先列，从返回里核对（C011-fix）
        assertThat(fake.toolCalls()).containsExactly("browser_tabs", "browser_tabs", "browser_run_code_unsafe",
                "browser_snapshot", "browser_snapshot");
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
        assertThat(fake.toolCalls()).containsExactly("browser_tabs", "browser_tabs", "browser_run_code_unsafe",
                "browser_snapshot");
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
            // 关标签页只按序号（停下时系统关自己开的页）；不给序号就是关当前页，不允许
            assertThatThrownBy(() -> client.call("browser_tabs", Map.of("action", "close")))
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
    void launchArgumentsKeepMcpFromWritingFilesElsewhere() throws Exception {
        StorageLayout layout = new StorageLayout(dir.resolve("storage"));
        BrowserMcpProperties properties = new BrowserMcpProperties("npx",
                List.of("-y", "@playwright/mcp@0.0.83", "--cdp-endpoint", "chrome", "--snapshot-mode", "none",
                        "--console-level", "error", "--image-responses", "omit", "--no-webmcp"), "",
                Duration.ofSeconds(30), Duration.ofSeconds(60));

        BrowserLaunch launch = properties.toLaunch(layout);

        assertThat(launch.args()).containsSequence("--output-dir", layout.mcpOutputDir().toString());
        // MCP 切标签页时不把 Chrome 拉到前面（C011-fix）：init page 写在输出目录以外（输出目录每次连接都清空）
        Path initPage = Path.of(launch.args().get(launch.args().indexOf("--init-page") + 1));
        assertThat(initPage).isAbsolute().startsWith(layout.cacheDir().toAbsolutePath()).isRegularFile();
        assertThat(initPage.startsWith(layout.mcpOutputDir().toAbsolutePath())).isFalse();
        assertThat(Files.readString(initPage)).contains("bringToFront").contains("exports.default");
        // 睡着、卡住的标签页不拖住连接和每次调用（C011-fix 2026-10-25）：preload 也写在输出目录以外
        assertThat(launch.preload()).isAbsolute().startsWith(layout.cacheDir().toAbsolutePath()).isRegularFile();
        assertThat(Files.readString(launch.preload())).contains("_waitForAllPagesToBeInitialized").contains("headerSnapshot");
        // 连用户自己的 Chrome：不另起资料目录
        assertThat(launch.args()).doesNotContain("--user-data-dir");
        assertThat(launch.callTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(launch.connectTimeout()).isEqualTo(Duration.ofSeconds(60));
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

            // 用过的连接断开后，下一次使用自动重连一次（C006-fix-browser 第 1 节），并且新开一个标签页
            assertThat(fake.starts()).isEqualTo(1);
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
    void callTimeoutIsAStallNotADisconnect() {
        FakeBrowser fake = FakeBrowser.create(dir).page(JOB_URL, "Backend Engineer", FakeBrowser.jobPageSnapshot("u-1"));

        try (BrowserSession session = new BrowserSession(fake.launch(Duration.ofSeconds(1)))) {
            session.open(JOB_URL, JOB);
            fake.callDelay(Duration.ofSeconds(3));

            // MCP 没回话 = 卡住了（C011-fix）：不关服务进程、不重连，标签页记录留着
            assertThatThrownBy(() -> session.snapshot(JOB)).isInstanceOf(BrowserException.Stalled.class);
            assertThat(session.state().stalled()).isTrue();
            assertThat(session.ownsTab(JOB)).isTrue();

            // 卡住的页面处理好了：「重新连接」先问原来的连接，答话了就接着用，不重建
            fake.callDelay(Duration.ZERO);
            assertThat(session.reconnect().connected()).isTrue();
            assertThat(fake.starts()).isEqualTo(1);
            assertThat(session.snapshot(JOB).url()).isEqualTo(JOB_URL);
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
