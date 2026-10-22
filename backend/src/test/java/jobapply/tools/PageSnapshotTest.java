package jobapply.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PageSnapshotTest {

    @Test
    void parsesPageSnapshotAndModalSections() {
        PageSnapshot page = PageSnapshot.parse("""
                ### Ran Playwright code
                ```js
                await page.goto('https://jobs.example.com/1');
                ```
                ### Page
                - Page URL: https://jobs.example.com/login?next=1
                - Page Title: 登录 - 示例招聘
                - Console: 0 errors, 1 warnings
                ### Modal state
                - ["alert" dialog with message "hi"]: can be handled by browser_handle_dialog
                ### Snapshot
                ```yaml
                - textbox "密码" [ref=e3]
                - button "登录" [ref=e4]
                ```
                """);

        assertThat(page.url()).isEqualTo("https://jobs.example.com/login?next=1");
        assertThat(page.title()).isEqualTo("登录 - 示例招聘");
        assertThat(page.snapshot()).isEqualTo("- textbox \"密码\" [ref=e3]\n- button \"登录\" [ref=e4]");
        assertThat(page.modalState()).contains("alert");
        assertThat(page.visibleText()).contains("登录 - 示例招聘").contains("textbox \"密码\"");
    }

    @Test
    void toleratesCrlfAndMissingTitleAndSnapshot() {
        PageSnapshot page = PageSnapshot.parse("### Page\r\n- Page URL: https://jobs.example.com/\r\n");

        assertThat(page.url()).isEqualTo("https://jobs.example.com/");
        assertThat(page.title()).isEmpty();
        assertThat(page.snapshot()).isEmpty();
        assertThat(page.modalState()).isEmpty();
    }

    @Test
    void refusesToGuessWithoutPageSection() {
        assertThatThrownBy(() -> PageSnapshot.parse("### Snapshot\n- button \"登录\""))
                .isInstanceOf(BrowserException.ToolFailed.class)
                .hasMessageContaining("Page");
        assertThatThrownBy(() -> PageSnapshot.parse("### Page\n- Page Title: x"))
                .isInstanceOf(BrowserException.ToolFailed.class)
                .hasMessageContaining("Page URL");
        assertThatThrownBy(() -> PageSnapshot.parse(null)).isInstanceOf(BrowserException.ToolFailed.class);
    }
}
