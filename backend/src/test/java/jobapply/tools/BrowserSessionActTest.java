package jobapply.tools;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import jobapply.harness.policies.BrowserToolPolicy;
import jobapply.harness.policies.ToolNotAllowedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link BrowserSession#act} 对表单模式的 {@link FakePlaywrightMcpServer}（真实的子进程，本机回环地址上的 Streamable HTTP）。
 * 不访问网络，不启动 Chrome，不调用 npx。
 */
class BrowserSessionActTest {

    private static final String JOB = "2026-10-23-001";

    @TempDir
    Path dir;

    private static BrowserToolPolicy.Context model(PageSnapshot latest, String url, Path uploads) {
        return BrowserToolPolicy.Context.model(SnapshotIndex.of(latest), List.of(url), uploads);
    }

    @Test
    void writesThenReturnsAFreshSnapshotWithTheNewValues() {
        FakeBrowser fake = FakeBrowser.create(dir).form("multi-page");
        String url = fake.formUrl();

        try (BrowserSession session = new BrowserSession(fake.launch())) {
            PageSnapshot first = session.open(url, JOB);
            assertThat(first.title()).endsWith("基本信息");

            PageSnapshot filled = session.act(JOB, BrowserToolPolicy.FILL_FORM, Map.of("fields", List.of(
                    Map.of("target", "e3", "name", "姓名", "type", "textbox", "value", "示例姓名"),
                    Map.of("target", "e4", "name", "邮箱", "type", "textbox", "value", "demo@example.com"))),
                    model(first, url, null));
            assertThat(filled.snapshot()).contains("- textbox \"姓名\" [ref=e3]: 示例姓名")
                    .contains("- textbox \"邮箱\" [ref=e4]: demo@example.com");
            assertThat(SnapshotIndex.of(filled).find("e3")).hasValueSatisfying(
                    node -> assertThat(node.text()).isEqualTo("示例姓名"));

            PageSnapshot typed = session.act(JOB, BrowserToolPolicy.TYPE,
                    Map.of("target", "e5", "element", "手机号", "text", "13800000000"), model(filled, url, null));
            assertThat(typed.snapshot()).contains("[ref=e5]: 13800000000");

            PageSnapshot selected = session.act(JOB, BrowserToolPolicy.SELECT_OPTION,
                    Map.of("target", "e6", "element", "性别", "values", List.of("女")), model(typed, url, null));
            assertThat(selected.snapshot()).contains("- combobox \"性别\" [ref=e6]: 女");

            PageSnapshot next = session.act(JOB, BrowserToolPolicy.CLICK,
                    Map.of("target", "e9", "element", "下一步"), model(selected, url, null));
            assertThat(next.title()).endsWith("教育经历");
            assertThat(next.url()).endsWith("/step-2");

            PageSnapshot back = session.act(JOB, BrowserToolPolicy.CLICK,
                    Map.of("target", "e18", "element", "上一步"), model(next, next.url(), null));
            assertThat(back.snapshot()).as("回到这一页时值还在").contains("[ref=e3]: 示例姓名");

            PageSnapshot read = session.act(JOB, BrowserToolPolicy.SNAPSHOT, Map.of(), model(back, url, null));
            assertThat(read).isEqualTo(back);
        }
        // 保活会话先列一次（建立共享连接）；开页后开焦点模拟；会改页面的操作前在自己的会话里列一次标签页（不切换），核对还是
        // 自己的那一页，只读的快照不先列（C011-fix）；选下拉先跑固定脚本（C016：原生 select 由脚本找到选项原文），再交给 MCP 的
        // browser_select_option
        assertThat(fake.toolCalls()).containsExactly("browser_tabs", "browser_tabs", "browser_run_code_unsafe",
                "browser_snapshot", "browser_tabs", "browser_fill_form", "browser_snapshot", "browser_tabs", "browser_type",
                "browser_snapshot", "browser_tabs", "browser_run_code_unsafe", "browser_select_option", "browser_snapshot",
                "browser_tabs", "browser_click", "browser_snapshot", "browser_tabs", "browser_click", "browser_snapshot",
                "browser_snapshot");
        assertThat(fake.calls("browser_select_option")).singleElement()
                .satisfies(args -> assertThat(args.get("values")).isEqualTo(List.of("女")));
    }

    @Test
    void refusesWhenTheTabIsNotThisJobsAndWhenTheWhitelistSaysNo() {
        FakeBrowser fake = FakeBrowser.create(dir).form("multi-page");
        String url = fake.formUrl();

        try (BrowserSession session = new BrowserSession(fake.launch())) {
            assertThatThrownBy(() -> session.act(JOB, BrowserToolPolicy.CLICK, Map.of("target", "e9"),
                    BrowserToolPolicy.Context.model(SnapshotIndex.empty(), List.of(url), null)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("不能操作");

            PageSnapshot first = session.open(url, "2026-10-23-002");
            assertThatThrownBy(() -> session.act(JOB, BrowserToolPolicy.CLICK, Map.of("target", "e9"),
                    model(first, url, null))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> session.find(JOB, Map.of("text", "下一步"), model(first, url, null)))
                    .isInstanceOf(IllegalStateException.class);

            // 多出来的参数没去掉、ref 不在最新快照里：白名单拒绝，不发出
            assertThatThrownBy(() -> session.act("2026-10-23-002", BrowserToolPolicy.TYPE,
                    Map.of("target", "e3", "text", "x", "fact", "base.name"), model(first, url, null)))
                    .isInstanceOf(ToolNotAllowedException.class);
            assertThatThrownBy(() -> session.act("2026-10-23-002", BrowserToolPolicy.CLICK,
                    Map.of("target", "e19"), model(first, url, null)))
                    .isInstanceOf(ToolNotAllowedException.class);
            assertThat(session.ownsTab("2026-10-23-002")).isTrue();
        }
        // 白名单拒绝的调用不碰浏览器（连动作前的核对也不做）
        assertThat(fake.toolCalls()).containsExactly("browser_tabs", "browser_tabs", "browser_run_code_unsafe",
                "browser_snapshot");
    }

    @Test
    void findIsReadOnlyAndReturnsTheMatchingLines() {
        FakeBrowser fake = FakeBrowser.create(dir).form("multi-page");
        String url = fake.formUrl();

        try (BrowserSession session = new BrowserSession(fake.launch())) {
            PageSnapshot first = session.open(url, JOB);
            String found = session.find(JOB, Map.of("text", "下一步"), model(first, url, null));

            assertThat(found).contains("button \"下一步\" [ref=e9]").doesNotContain("姓名");
        }
        assertThat(fake.toolCalls()).containsExactly("browser_tabs", "browser_tabs", "browser_run_code_unsafe",
                "browser_snapshot", "browser_find");
    }

    @Test
    void uploadsTheStagedCopyIntoTheOpenFileChooser() throws Exception {
        Path uploads = dir.resolve("storage/uploads/" + JOB);
        Files.createDirectories(uploads);
        Path resume = Files.writeString(uploads.resolve("resume-demo.pdf"), "虚构简历", UTF_8);
        FakeBrowser fake = FakeBrowser.create(dir.resolve("fake")).form("file-slots");
        String url = fake.formUrl();

        try (BrowserSession session = new BrowserSession(fake.launch())) {
            PageSnapshot first = session.open(url, JOB);
            assertThat(first.modalState()).isEmpty();

            // 没有文件选择框时上传是工具错误，连接还在
            BrowserTools.Call early = BrowserTools.translate(BrowserToolPolicy.FILE_UPLOAD, "{\"files\":[1]}",
                    List.of(resume));
            assertThatThrownBy(() -> session.act(JOB, early.tool(), early.args(), model(first, url, uploads)))
                    .isInstanceOf(BrowserException.ToolFailed.class);
            assertThat(session.ownsTab(JOB)).isTrue();

            PageSnapshot chooser = session.act(JOB, BrowserToolPolicy.CLICK,
                    Map.of("target", "e3", "element", "上传简历"), model(first, url, uploads));
            assertThat(chooser.modalState()).contains("File chooser");

            PageSnapshot uploaded = session.act(JOB, early.tool(), early.args(), model(chooser, url, uploads));
            assertThat(uploaded.modalState()).isEmpty();
            assertThat(uploaded.snapshot()).contains("- generic \"简历文件\" [ref=e4]: resume-demo.pdf")
                    .contains("- generic \"作品集文件\" [ref=e7]: 未上传");

            // 作品集按钮点不到：点击是动作超时，错误里写着系统检查的结果；带 target 上传不点击，直接设到文件输入框
            assertThatThrownBy(() -> session.act(JOB, BrowserToolPolicy.CLICK,
                    Map.of("target", "e6", "element", "上传作品集"), model(uploaded, url, uploads)))
                    .isInstanceOf(BrowserException.ToolFailed.class).hasMessageContaining("动作超时")
                    .hasMessageContaining("系统检查了这个元素");
            BrowserTools.Call direct = BrowserTools.translate(BrowserToolPolicy.FILE_UPLOAD,
                    "{\"target\":\"e6\",\"files\":[1]}", List.of(resume));
            PageSnapshot set = session.act(JOB, direct.tool(), direct.args(), model(uploaded, url, uploads));
            assertThat(set.snapshot()).contains("- generic \"作品集文件\" [ref=e7]: resume-demo.pdf");

            // 「添加」被底部固定栏盖住：点击超时 → 系统检查出盖住它的层 → 在同一个 ref 上直接点一次，留说明、取新快照
            PageSnapshot added = session.act(JOB, BrowserToolPolicy.CLICK,
                    Map.of("target", "e31", "element", "添加"), model(set, url, uploads));
            assertThat(added.snapshot()).contains("- generic \"已添加的项目\" [ref=e32]: 项目 1（待填写）");
            assertThat(session.takeNotice(JOB)).contains("直接点了一次").contains("class=\"bottom-bar\"")
                    .contains("position: fixed");
        }
        assertThat(fake.calls(BrowserToolPolicy.FILE_UPLOAD)).extracting(args -> args.get("paths"))
                .containsExactly(List.of(resume.toString()), List.of(resume.toString()));
        assertThat(fake.calls("browser_run_code_unsafe"))
                .filteredOn(args -> String.valueOf(args.get("code")).contains("setInputFiles")).singleElement()
                .satisfies(args -> assertThat(String.valueOf(args.get("code"))).contains("\"aria-ref=e6\"")
                        .contains("setInputFiles([\"" + resume + "\"])"));
    }

    @Test
    void disconnectDuringActDropsTheTabOwnership() {
        FakeBrowser fake = FakeBrowser.create(dir).form("multi-page");
        String url = fake.formUrl();

        try (BrowserSession session = new BrowserSession(fake.launch(java.time.Duration.ofSeconds(3)))) {
            PageSnapshot first = session.open(url, JOB);
            fake.exitOnNextCall();

            assertThatThrownBy(() -> session.act(JOB, BrowserToolPolicy.CLICK,
                    Map.of("target", "e9", "element", "下一步"), model(first, url, null)))
                    .isInstanceOf(BrowserException.Disconnected.class);
            assertThat(session.ownsTab(JOB)).isFalse();
            assertThat(session.isConnected()).isFalse();
            assertThatThrownBy(() -> session.act(JOB, BrowserToolPolicy.SNAPSHOT, Map.of(), model(first, url, null)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
