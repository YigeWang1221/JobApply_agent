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

/**
 * 浏览器工具白名单的核心规则。页面数据全部虚构。
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

    @Test
    void closedToolsAreRejectedAndTabsAreSystemOnly() {
        rejected("browser_evaluate", Map.of(), "不在白名单内");
        rejected("browser_run_code_unsafe", Map.of(), "不在白名单内");
        rejected("browser_tabs", Map.of("action", "list"), "只能由系统调用");

        assertThatCode(() -> BrowserToolPolicy.check("browser_snapshot", Map.of())).doesNotThrowAnyException();
        assertThatCode(() -> BrowserToolPolicy.check("browser_tabs", Map.of("action", "list")))
                .doesNotThrowAnyException();
        assertThatCode(() -> BrowserToolPolicy.check("browser_tabs",
                Map.of("action", "new", "url", JOB_URL))).doesNotThrowAnyException();
        assertThatCode(() -> BrowserToolPolicy.check("browser_tabs",
                Map.of("action", "select", "index", 1))).doesNotThrowAnyException();
        assertThatCode(() -> BrowserToolPolicy.check("browser_tabs",
                Map.of("action", "close", "index", 2))).doesNotThrowAnyException();
        assertThatThrownBy(() -> BrowserToolPolicy.check("browser_click", Map.of()))
                .isInstanceOf(ToolNotAllowedException.class);
    }

    @Test
    void typeSubmitAndEnterOrSpaceKeyPressesAreRejected() {
        allowed("browser_type", Map.of("target", "e2", "text", "虚构姓名", "submit", false, "slowly", true));
        rejected("browser_type", Map.of("target", "e2", "text", "x", "submit", true), "submit");
        rejected("browser_type", Map.of("target", "e2", "text", "x", "submit", "true"), "submit");

        for (String key : List.of("Tab", "ArrowDown", "Escape")) {
            allowed("browser_press_key", Map.of("key", key));
        }
        for (String key : List.of("Enter", "enter", "NumpadEnter", " ", "Space", "Return")) {
            rejected("browser_press_key", Map.of("key", key), "回车和空格");
        }
    }

    @Test
    void snapshotRefsAreRequiredAndCoreActionsPassHappyPath() {
        allowed("browser_snapshot", Map.of("target", "e2", "depth", 2));
        allowed("browser_find", Map.of("text", "教育经历"));

        allowed("browser_click", Map.of("target", "e3"));
        allowed("browser_click", Map.of("target", "f1e2", "element", "邮箱", "button", "left"));
        rejected("browser_click", Map.of("target", "e99"), "不在最新快照里");
        rejected("browser_click", Map.of("target", "button[type=submit]"), "不接受选择器");
        rejected("browser_click", Map.of("target", "#apply"), "不接受选择器");

        allowed("browser_type", Map.of("target", "e2", "text", "虚构姓名"));
        rejected("browser_type", Map.of("target", "input[name=q]", "text", "x"), "不接受选择器");

        allowed("browser_fill_form", Map.of("fields", List.of(
                Map.of("target", "e2", "name", "姓名", "type", "textbox", "value", "虚构姓名"),
                Map.of("target", "e5", "name", "同意", "type", "checkbox", "value", "true"),
                Map.of("target", "f1e2", "name", "邮箱", "type", "textbox", "value", "demo@example.com"))));
        rejected("browser_fill_form", Map.of("fields", List.of(
                Map.of("target", "#name", "name", "x", "type", "textbox", "value", "x"))), "不接受选择器");

        allowed("browser_select_option", Map.of("target", "e4", "values", List.of("本科")));
        allowed("browser_select_option", Map.of("target", "e2", "values", List.of("广东", "深圳"), "search", true));
        rejected("browser_select_option", Map.of("target", "e7", "values", List.of("x")), "角色 link");

        allowed("browser_set_date", Map.of("target", "e2", "date", "2023-09"));
        rejected("browser_set_date", Map.of("target", "e2", "date", "2023年9月"), "YYYY-MM-DD");

        allowed("browser_wait_for", Map.of("time", 2.5));
        allowed("browser_wait_for", Map.of("text", "上传成功"));
        rejected("browser_wait_for", Map.of("time", 11), "10 秒");
    }

    @Test
    void uploadsStayInsideThisJobsUploadDirectory() throws Exception {
        Path jobDir = dir.resolve("uploads/2026-10-22-001");
        Files.createDirectories(jobDir);
        Path resume = Files.writeString(jobDir.resolve("resume-demo.pdf"), "虚构");
        Files.createDirectories(dir.resolve("outside"));
        Path outside = Files.writeString(dir.resolve("outside/other.txt"), "虚构");

        allowed("browser_file_upload", Map.of("paths", List.of(resume.toString())));
        allowed("browser_file_upload", Map.of());
        rejected("browser_file_upload", Map.of("paths", List.of(outside.toString())), "只能上传");
        rejected("browser_file_upload", Map.of("paths", List.of(dir.resolve("uploads/2026-10-22-002/x.pdf").toString())),
                "只能上传");
        rejected("browser_file_upload", Map.of("paths", List.of("resume-demo.pdf")), "只能上传");

        Context noUploads = Context.model(LATEST, List.of(JOB_URL), null);
        assertThatThrownBy(() -> BrowserToolPolicy.check("browser_file_upload",
                Map.of("paths", List.of(resume.toString())), noUploads)).isInstanceOf(ToolNotAllowedException.class);
    }

    @Test
    void navigateStaysOnTheJobSiteOrTheCurrentPage() {
        allowed("browser_navigate", Map.of("url", "https://jobs.example.com/apply/2"));
        allowed("browser_navigate", Map.of("url", "https://careers.example.com/login"));
        rejected("browser_navigate", Map.of("url", "https://example.org/"), "站点");
        rejected("browser_navigate", Map.of("url", "https://example.com.evil.org/"), "站点");
        rejected("browser_navigate", Map.of(), "http / https");

        Context withCurrent = Context.model(LATEST, List.of(JOB_URL, "https://hr.vendor-ats.com.cn/form"), null);
        assertThatCode(() -> BrowserToolPolicy.check("browser_navigate",
                Map.of("url", "https://sso.vendor-ats.com.cn/x"), withCurrent)).doesNotThrowAnyException();
        assertThatThrownBy(() -> BrowserToolPolicy.check("browser_navigate",
                Map.of("url", "https://other.com.cn/x"), withCurrent)).isInstanceOf(ToolNotAllowedException.class);
    }

    @Test
    void siteIsTheRegistrableDomain() {
        assertThat(BrowserToolPolicy.site("jobs.example.com")).isEqualTo("example.com");
        assertThat(BrowserToolPolicy.site("a.b.example.com.cn")).isEqualTo("example.com.cn");
        assertThat(BrowserToolPolicy.site("careers.example.co.uk")).isEqualTo("example.co.uk");
        assertThat(BrowserToolPolicy.site("localhost")).isEqualTo("localhost");
        assertThat(BrowserToolPolicy.site("127.0.0.1")).isEqualTo("127.0.0.1");
    }
}
