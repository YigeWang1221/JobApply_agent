package jobapply.harness.policies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import jobapply.harness.policies.BrowserToolPolicy.Context;
import jobapply.tools.SnapshotIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 白名单表的每一行：允许和拒绝。页面数据全部虚构。
 */
class BrowserToolPolicyTest {

    private static final String JOB_URL = "https://jobs.example.com/apply/1";
    private static final SnapshotIndex LATEST = SnapshotIndex.parse("""
            - textbox "姓名" [ref=e2]
            - button "下一步" [ref=e3]
            - combobox "学历" [ref=e4]
            - checkbox "同意" [ref=e5]
            - listbox "城市" [ref=e6]
            - link "隐私政策" [ref=e7]
            - iframe [ref=e8]:
              - textbox "邮箱" [ref=f1e2]""");

    @TempDir
    Path dir;

    private Context model() {
        return Context.model(LATEST, List.of(JOB_URL), dir.resolve("uploads/2026-10-22-001"));
    }

    private void allowed(String tool, Map<String, Object> args) {
        assertThatCode(() -> BrowserToolPolicy.check(tool, args, model())).doesNotThrowAnyException();
    }

    private void rejected(String tool, Map<String, Object> args, String message) {
        assertThatThrownBy(() -> BrowserToolPolicy.check(tool, args, model()))
                .isInstanceOf(ToolNotAllowedException.class)
                .hasMessageContaining(message)
                .hasMessageContaining("不要重试");
    }

    // 系统的调用（C003 起就有的三条路径）

    @Test
    void systemMayOpenTabsSnapshotAndListTabs() {
        assertThatCode(() -> BrowserToolPolicy.check("browser_snapshot", Map.of())).doesNotThrowAnyException();
        assertThatCode(() -> BrowserToolPolicy.check("browser_snapshot", null)).doesNotThrowAnyException();
        assertThatCode(() -> BrowserToolPolicy.check("browser_snapshot", Map.of("depth", 3))).doesNotThrowAnyException();
        assertThatCode(() -> BrowserToolPolicy.check("browser_tabs", Map.of("action", "list")))
                .doesNotThrowAnyException();
        assertThatCode(() -> BrowserToolPolicy.check("browser_tabs",
                Map.of("action", "new", "url", "https://jobs.example.com/1"))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"browser_click", "browser_type", "browser_fill_form", "browser_file_upload",
            "browser_press_key", "browser_select_option", "browser_wait_for", "browser_find", "browser_navigate"})
    void systemCallsCannotWrite(String tool) {
        assertThatThrownBy(() -> BrowserToolPolicy.check(tool, Map.of()))
                .isInstanceOf(ToolNotAllowedException.class)
                .hasMessageContaining("不要重试");
    }

    @ParameterizedTest
    @ValueSource(strings = {"browser_evaluate", "browser_run_code_unsafe", "browser_handle_dialog", "browser_hover",
            "browser_drag", "browser_drop", "browser_navigate_back", "browser_close", "browser_console_messages",
            "browser_network_requests", "browser_take_screenshot", "browser_mouse_click_xy", "browser_pdf_save",
            "browser_cookie_list", "unknown_tool", ""})
    void closedToolsAreRejectedForEveryone(String tool) {
        rejected(tool, Map.of(), "不在白名单内");
        assertThatThrownBy(() -> BrowserToolPolicy.check(tool, Map.of())).isInstanceOf(ToolNotAllowedException.class);
    }

    @Test
    void rejectsNullToolName() {
        assertThatThrownBy(() -> BrowserToolPolicy.check(null, Map.of())).isInstanceOf(ToolNotAllowedException.class);
    }

    // browser_tabs

    @Test
    void onlyTheSystemOpensTabs() {
        rejected("browser_tabs", Map.of("action", "list"), "只能由系统调用");
        rejected("browser_tabs", Map.of("action", "new", "url", JOB_URL), "只能由系统调用");
    }

    @ParameterizedTest
    @ValueSource(strings = {"select", "close", ""})
    void tabsMayNotSwitchOrCloseTabs(String action) {
        assertThatThrownBy(() -> BrowserToolPolicy.check("browser_tabs", Map.of("action", action, "index", 0)))
                .isInstanceOf(ToolNotAllowedException.class)
                .hasMessageContaining("action=list 或 action=new");
    }

    @Test
    void newTabNeedsAnHttpUrlAndNothingElse() {
        assertThatThrownBy(() -> BrowserToolPolicy.check("browser_tabs", Map.of("action", "new")))
                .isInstanceOf(ToolNotAllowedException.class)
                .hasMessageContaining("http / https");
        assertThatThrownBy(() -> BrowserToolPolicy.check("browser_tabs",
                Map.of("action", "new", "url", "javascript:alert(1)")))
                .isInstanceOf(ToolNotAllowedException.class);
        assertThatThrownBy(() -> BrowserToolPolicy.check("browser_tabs",
                Map.of("action", "new", "url", "https://jobs.example.com", "index", 1)))
                .isInstanceOf(ToolNotAllowedException.class)
                .hasMessageContaining("index");
        assertThatThrownBy(() -> BrowserToolPolicy.check("browser_tabs",
                Map.of("action", "list", "url", "https://x.example")))
                .isInstanceOf(ToolNotAllowedException.class);
    }

    // browser_snapshot / browser_find

    @Test
    void snapshotAndFindMayNotWriteFiles() {
        allowed("browser_snapshot", Map.of());
        allowed("browser_snapshot", Map.of("target", "e2", "depth", 2));
        rejected("browser_snapshot", Map.of("filename", "page.md"), "filename");
        rejected("browser_snapshot", Map.of("target", "#form"), "ref");
        assertThatThrownBy(() -> BrowserToolPolicy.check("browser_snapshot", Map.of("filename", "page.md")))
                .isInstanceOf(ToolNotAllowedException.class);

        allowed("browser_find", Map.of("text", "教育经历"));
        allowed("browser_find", Map.of("regex", "第 \\d/\\d 步"));
        rejected("browser_find", Map.of("text", "x", "filename", "found.md"), "filename");
    }

    // browser_navigate

    @Test
    void navigateStaysOnTheJobSiteOrTheCurrentPage() {
        allowed("browser_navigate", Map.of("url", "https://jobs.example.com/apply/2"));
        allowed("browser_navigate", Map.of("url", "https://careers.example.com/login"));
        allowed("browser_navigate", Map.of("url", "https://EXAMPLE.com"));
        rejected("browser_navigate", Map.of("url", "https://example.org/"), "站点");
        rejected("browser_navigate", Map.of("url", "https://example.com.evil.org/"), "站点");

        Context withCurrent = Context.model(LATEST, List.of(JOB_URL, "https://hr.vendor-ats.com.cn/form"), null);
        assertThatCode(() -> BrowserToolPolicy.check("browser_navigate",
                Map.of("url", "https://sso.vendor-ats.com.cn/x"), withCurrent)).doesNotThrowAnyException();
        assertThatThrownBy(() -> BrowserToolPolicy.check("browser_navigate",
                Map.of("url", "https://other.com.cn/x"), withCurrent)).isInstanceOf(ToolNotAllowedException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "file:///etc/passwd", "data:text/html,hi", "chrome://settings",
            "about:blank", "not a url", "https://"})
    void navigateOnlyAcceptsHttpUrls(String url) {
        rejected("browser_navigate", Map.of("url", url), "http / https");
    }

    @Test
    void navigateRejectsMissingUrlAndExtraArguments() {
        rejected("browser_navigate", Map.of(), "http / https");
        rejected("browser_navigate", Map.of("url", JOB_URL, "extra", true), "extra");
    }

    @Test
    void siteIsTheRegistrableDomain() {
        assertThat(BrowserToolPolicy.site("jobs.example.com")).isEqualTo("example.com");
        assertThat(BrowserToolPolicy.site("a.b.example.com.cn")).isEqualTo("example.com.cn");
        assertThat(BrowserToolPolicy.site("careers.example.co.uk")).isEqualTo("example.co.uk");
        assertThat(BrowserToolPolicy.site("localhost")).isEqualTo("localhost");
        assertThat(BrowserToolPolicy.site("127.0.0.1")).isEqualTo("127.0.0.1");
    }

    // browser_click

    @Test
    void clickNeedsARefFromTheLatestSnapshot() {
        allowed("browser_click", Map.of("target", "e3"));
        allowed("browser_click", Map.of("target", "f1e2", "element", "邮箱", "button", "left"));
        allowed("browser_click", Map.of("target", "e3", "purpose", "next_step", "final_step_suspected", false,
                "evidence", "第 2/4 步"));
        rejected("browser_click", Map.of("target", "e99"), "不在最新快照里");
        rejected("browser_click", Map.of("target", "button[type=submit]"), "不接受选择器");
        rejected("browser_click", Map.of("target", "#apply"), "不接受选择器");
        rejected("browser_click", Map.of(), "ref");
        rejected("browser_click", Map.of("target", "e3", "button", "right"), "左键");
        rejected("browser_click", Map.of("target", "e3", "modifiers", List.of("Shift")), "modifiers");
        rejected("browser_click", Map.of("target", "e3", "doubleClick", true), "doubleClick");
    }

    // browser_type

    @Test
    void typeNeverPressesEnter() {
        allowed("browser_type", Map.of("target", "e2", "text", "虚构姓名"));
        allowed("browser_type", Map.of("target", "e2", "text", "虚构姓名", "submit", false, "slowly", true));
        rejected("browser_type", Map.of("target", "e2", "text", "x", "submit", true), "submit");
        rejected("browser_type", Map.of("target", "e2", "text", "x", "submit", "true"), "submit");
        rejected("browser_type", Map.of("target", "e2"), "text");
        rejected("browser_type", Map.of("target", "input[name=q]", "text", "x"), "不接受选择器");
    }

    // browser_fill_form

    @Test
    void fillFormChecksEveryFieldRefAndRole() {
        allowed("browser_fill_form", Map.of("fields", List.of(
                Map.of("target", "e2", "name", "姓名", "type", "textbox", "value", "虚构姓名"),
                Map.of("target", "e5", "name", "同意", "type", "checkbox", "value", "true"),
                Map.of("target", "f1e2", "name", "邮箱", "type", "textbox", "value", "demo@example.com"))));
        rejected("browser_fill_form", Map.of("fields", List.of(
                Map.of("target", "e2", "name", "姓名", "type", "textbox", "value", "x"),
                Map.of("target", "e3", "name", "下一步", "type", "checkbox", "value", "true"))), "角色 button");
        rejected("browser_fill_form", Map.of("fields", List.of(
                Map.of("target", "e7", "name", "x", "type", "textbox", "value", "x"))), "角色 link");
        rejected("browser_fill_form", Map.of("fields", List.of(
                Map.of("target", "#name", "name", "x", "type", "textbox", "value", "x"))), "不接受选择器");
        rejected("browser_fill_form", Map.of("fields", List.of()), "fields");
        rejected("browser_fill_form", Map.of("fields", List.of("e2")), "对象");
    }

    // browser_select_option

    @Test
    void selectOptionOnlyOnComboboxOrListbox() {
        allowed("browser_select_option", Map.of("target", "e4", "values", List.of("本科")));
        allowed("browser_select_option", Map.of("target", "e6", "values", List.of("上海")));
        rejected("browser_select_option", Map.of("target", "e2", "values", List.of("x")), "角色 textbox");
        rejected("browser_select_option", Map.of("target", "e44", "values", List.of("x")), "不在最新快照里");
    }

    // browser_file_upload

    @Test
    void uploadsOnlyFromThisJobsUploadDirectory() throws Exception {
        Path jobDir = dir.resolve("uploads/2026-10-22-001");
        Files.createDirectories(jobDir);
        Path resume = Files.writeString(jobDir.resolve("resume-demo.pdf"), "虚构");
        Files.createDirectories(dir.resolve("outside"));
        Path secret = Files.writeString(dir.resolve("outside/other.txt"), "虚构");
        Path link = Files.createSymbolicLink(jobDir.resolve("link.txt"), secret);

        allowed("browser_file_upload", Map.of("paths", List.of(resume.toString())));
        allowed("browser_file_upload", Map.of());
        rejected("browser_file_upload", Map.of("paths", List.of(secret.toString())), "只能上传");
        rejected("browser_file_upload", Map.of("paths", List.of(jobDir.resolve("../../outside/other.txt").toString())),
                "只能上传");
        rejected("browser_file_upload", Map.of("paths", List.of(dir.resolve("uploads/2026-10-22-002/x.pdf").toString())),
                "只能上传");
        rejected("browser_file_upload", Map.of("paths", List.of("resume-demo.pdf")), "只能上传");
        rejected("browser_file_upload", Map.of("paths", List.of(link.toString())), "只能上传");
        rejected("browser_file_upload", Map.of("paths", List.of(jobDir.toString())), "只能上传");
        rejected("browser_file_upload", Map.of("paths", "x"), "列表");

        Context noUploads = Context.model(LATEST, List.of(JOB_URL), null);
        assertThatThrownBy(() -> BrowserToolPolicy.check("browser_file_upload",
                Map.of("paths", List.of(resume.toString())), noUploads)).isInstanceOf(ToolNotAllowedException.class);
    }

    // browser_press_key

    @ParameterizedTest
    @ValueSource(strings = {"Tab", "Shift+Tab", "ArrowDown", "ArrowUp", "ArrowLeft", "ArrowRight", "Escape",
            "Backspace", "Delete", "Home", "End", "PageUp", "PageDown"})
    void navigationKeysAreAllowed(String key) {
        allowed("browser_press_key", Map.of("key", key));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Enter", "enter", "NumpadEnter", "Control+Enter", " ", "Space", "Return"})
    void enterAndSpaceAreAlwaysRejected(String key) {
        rejected("browser_press_key", Map.of("key", key), "回车和空格");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "Control+A", "F5", "Meta+R"})
    void otherKeysAreRejected(String key) {
        rejected("browser_press_key", Map.of("key", key), "只允许");
    }

    // browser_wait_for

    @Test
    void waitIsAtMostTenSeconds() {
        allowed("browser_wait_for", Map.of("time", 10));
        allowed("browser_wait_for", Map.of("time", 2.5));
        allowed("browser_wait_for", Map.of("text", "上传成功"));
        allowed("browser_wait_for", Map.of("textGone", "加载中"));
        rejected("browser_wait_for", Map.of("time", 11), "10 秒");
        rejected("browser_wait_for", Map.of("time", -1), "10 秒");
        rejected("browser_wait_for", Map.of("time", "5"), "10 秒");
        rejected("browser_wait_for", Map.of("selector", "#x"), "selector");
    }
}
