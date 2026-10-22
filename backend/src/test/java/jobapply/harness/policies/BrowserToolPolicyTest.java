package jobapply.harness.policies;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class BrowserToolPolicyTest {

    @Test
    void allowsOpeningSnapshotAndListingTabs() {
        assertThatCode(() -> BrowserToolPolicy.check("browser_navigate", Map.of("url", "https://jobs.example.com/1")))
                .doesNotThrowAnyException();
        assertThatCode(() -> BrowserToolPolicy.check("browser_navigate", Map.of("url", "http://localhost:8000/a")))
                .doesNotThrowAnyException();
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
            "browser_press_key", "browser_evaluate", "browser_run_code_unsafe", "browser_handle_dialog",
            "browser_select_option", "browser_mouse_click_xy", "browser_cookie_list", "unknown_tool", ""})
    void rejectsEverythingElseAndSaysNotToRetry(String tool) {
        assertThatThrownBy(() -> BrowserToolPolicy.check(tool, Map.of()))
                .isInstanceOf(ToolNotAllowedException.class)
                .hasMessageContaining("不要重试");
    }

    @Test
    void rejectsNullToolName() {
        assertThatThrownBy(() -> BrowserToolPolicy.check(null, Map.of())).isInstanceOf(ToolNotAllowedException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "file:///etc/passwd", "data:text/html,hi", "chrome://settings",
            "about:blank", "not a url", "https://"})
    void navigateOnlyAcceptsHttpUrls(String url) {
        assertThatThrownBy(() -> BrowserToolPolicy.check("browser_navigate", Map.of("url", url)))
                .isInstanceOf(ToolNotAllowedException.class)
                .hasMessageContaining("http / https");
    }

    @Test
    void navigateRejectsMissingUrlAndExtraArguments() {
        assertThatThrownBy(() -> BrowserToolPolicy.check("browser_navigate", Map.of()))
                .isInstanceOf(ToolNotAllowedException.class);
        assertThatThrownBy(() -> BrowserToolPolicy.check("browser_navigate",
                Map.of("url", "https://jobs.example.com", "extra", true)))
                .isInstanceOf(ToolNotAllowedException.class)
                .hasMessageContaining("extra");
    }

    @Test
    void snapshotMayNotWriteFiles() {
        assertThatThrownBy(() -> BrowserToolPolicy.check("browser_snapshot", Map.of("filename", "page.md")))
                .isInstanceOf(ToolNotAllowedException.class)
                .hasMessageContaining("filename");
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
        assertThatThrownBy(() -> BrowserToolPolicy.check("browser_tabs", Map.of("action", "list", "url", "https://x.example")))
                .isInstanceOf(ToolNotAllowedException.class);
    }
}
