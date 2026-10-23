package jobapply.harness.policies;

import static org.assertj.core.api.Assertions.assertThat;

import jobapply.tools.PageSnapshot;
import jobapply.tools.SnapshotIndex;
import org.junit.jupiter.api.Test;

/**
 * 快照里的值全部虚构。
 */
class SnapshotRedactorTest {

    private static final String SNAPSHOT = """
            - heading "基本信息" [level=1] [ref=e1]
            - textbox "姓名" [ref=e2]: 示例姓名
            - textbox "身份证号" [ref=e3]: 110101200001011234
            - textbox "期望薪资（月）" [ref=e4]: "30k: 可谈"
            - 'textbox "Visa status: current" [ref=e5]': H-1B
            - combobox "民族" [ref=e6]:
              - option "示例族甲" [selected] [ref=e7]
              - text: 示例族甲
            - group "是否需要工作签证担保" [ref=e8]:
              - radio "是" [checked] [ref=e9]
              - radio "否" [ref=e10]
            - checkbox "我声明以上信息真实" [checked] [ref=e11]
            - textbox "Salary expectation" [ref=e12]
            - link "健康管理平台（项目）" [ref=e13]:
              - /url: https://example.com/p
            - textbox "专业" [ref=e14]: 计算机科学""";

    @Test
    void removesValuesAndStatesOfClassCFieldsOnly() {
        String redacted = SnapshotRedactor.redact(SNAPSHOT);

        assertThat(redacted)
                .contains("- textbox \"姓名\" [ref=e2]: 示例姓名")
                .contains("- textbox \"身份证号\" [ref=e3]: [已省略]")
                .contains("- textbox \"期望薪资（月）\" [ref=e4]: [已省略]")
                .contains("- 'textbox \"Visa status: current\" [ref=e5]': [已省略]")
                .contains("- combobox \"民族\" [ref=e6]:\n")
                .contains("  - option \"示例族甲\" [ref=e7]\n")
                .contains("  - text: [已省略]")
                .contains("  - radio \"是\" [ref=e9]\n")
                .contains("- checkbox \"我声明以上信息真实\" [ref=e11]\n")
                .contains("- textbox \"Salary expectation\" [ref=e12]\n")
                .contains("- link \"健康管理平台（项目）\" [ref=e13]:\n  - /url: https://example.com/p")
                .contains("- textbox \"专业\" [ref=e14]: 计算机科学")
                .doesNotContain("110101200001011234").doesNotContain("30k").doesNotContain("H-1B")
                .doesNotContain("[checked]").doesNotContain("[selected]");
    }

    @Test
    void keepsTheRefsSoTheIndexStillParses() {
        SnapshotIndex before = SnapshotIndex.parse(SNAPSHOT);
        SnapshotIndex after = SnapshotIndex.parse(SnapshotRedactor.redact(SNAPSHOT));

        assertThat(after.size()).isEqualTo(before.size());
        assertThat(after.find("e3")).hasValueSatisfying(node -> {
            assertThat(node.role()).isEqualTo("textbox");
            assertThat(node.name()).isEqualTo("身份证号");
            assertThat(node.text()).isEqualTo(FieldClassGuard.REMOVED);
        });
        assertThat(after.find("e5")).hasValueSatisfying(node -> assertThat(node.name()).isEqualTo("Visa status: current"));
    }

    @Test
    void redactsAPageSnapshotAndLeavesOtherPartsAlone() {
        PageSnapshot page = new PageSnapshot("https://careers.example.com/apply", "申请", SNAPSHOT, "");

        PageSnapshot redacted = SnapshotRedactor.redact(page);

        assertThat(redacted.url()).isEqualTo(page.url());
        assertThat(redacted.title()).isEqualTo(page.title());
        assertThat(redacted.snapshot()).isEqualTo(SnapshotRedactor.redact(SNAPSHOT));
        assertThat(SnapshotRedactor.redact((PageSnapshot) null)).isNull();
        assertThat(SnapshotRedactor.redact((String) null)).isEmpty();
        assertThat(SnapshotRedactor.redact("- textbox \"姓名\" [ref=e2]: x")).isEqualTo("- textbox \"姓名\" [ref=e2]: x");
    }
}
