package jobapply.harness.context;

import static org.assertj.core.api.Assertions.assertThat;

import jobapply.tools.PageSnapshot;
import org.junit.jupiter.api.Test;

class PopupViewTest {

    private static final String URL = "https://example.com/resume";

    /** 点「添加」打开的新表单里有不带角色的行（{@code - /placeholder:}）：不能抛异常（2026-10-25 申请线程 NullPointerException）。 */
    @Test
    void newBlockWithLinesWithoutRoleDoesNotThrow() {
        String page = page();
        String after = page + """
                - generic [ref=e900]:
                  - textbox "Project name" [ref=e901]
                  - /placeholder: Please enter
                  - button "Confirm" [ref=e902]
                """;

        assertThat(PopupView.of(snapshot(page), snapshot(after), "e1")).isEmpty();
    }

    @Test
    void newOptionListWithLinesWithoutRoleIsShown() {
        String page = page();
        String after = page + """
                - listbox [ref=e900]:
                  - /url: about:blank
                  - option "Beijing" [ref=e901]
                  - option "Shanghai" [ref=e902]
                """;

        assertThat(PopupView.of(snapshot(page), snapshot(after), "e1")).hasValueSatisfying(
                shown -> assertThat(shown.snapshot()).contains("option \"Shanghai\"").doesNotContain("button \"B2\""));
    }

    private static String page() {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 45; i++) {
            text.append("- button \"B").append(i).append("\" [ref=e").append(i).append("]\n");
        }
        return text.toString();
    }

    private static PageSnapshot snapshot(String text) {
        return new PageSnapshot(URL, "Resume", text, null);
    }
}
