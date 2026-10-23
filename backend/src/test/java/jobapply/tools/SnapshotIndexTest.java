package jobapply.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 快照格式按 @playwright/mcp 0.0.83（playwright-core {@code renderAriaSnapshotAsYaml}）。页面数据全部虚构。
 */
class SnapshotIndexTest {

    private static final String FORM = """
            - main [ref=e1]:
              - heading "申请表" [level=1] [ref=e2]
              - group "工作授权" [ref=e3]:
                - radio "是" [ref=e4]
                - radio "否" [checked] [ref=e5]
              - textbox "手机号" [ref=e6]: "13800000000"
              - link "隐私政策" [ref=e7] [cursor=pointer]:
                - /url: https://jobs.example.com/privacy
              - 'button "Email: required" [ref=e8]'
              - button "Say \\"hi\\"" [disabled] [ref=e9]
              - text: 普通文字
              - iframe [ref=e10]:
                - button "下一步" [ref=f1e2]
              - button: 提交
              - ??? broken [ref=e11]
            """;

    @Test
    void indexesRefsWithRoleNameAndAncestors() {
        SnapshotIndex index = SnapshotIndex.parse(FORM);

        SnapshotIndex.Node radio = index.find("e5").orElseThrow();
        assertThat(radio.role()).isEqualTo("radio");
        assertThat(radio.name()).isEqualTo("否");
        assertThat(radio.known()).isTrue();
        assertThat(radio.ancestors()).containsExactly(
                new SnapshotIndex.Ancestor("main", ""), new SnapshotIndex.Ancestor("group", "工作授权"));

        assertThat(index.find("e6").orElseThrow().text()).isEqualTo("13800000000");
        assertThat(index.find("e7").orElseThrow().role()).isEqualTo("link");
        assertThat(index.find("e2").orElseThrow().ancestors()).hasSize(1);
    }

    @Test
    void decodesQuotedKeysAndJsonNames() {
        SnapshotIndex index = SnapshotIndex.parse(FORM);

        assertThat(index.find("e8").orElseThrow().name()).isEqualTo("Email: required");
        assertThat(index.find("e9").orElseThrow().name()).isEqualTo("Say \"hi\"");
    }

    @Test
    void iframeRefsHaveAPrefix() {
        SnapshotIndex.Node next = SnapshotIndex.parse(FORM).find("f1e2").orElseThrow();

        assertThat(next.name()).isEqualTo("下一步");
        assertThat(next.ancestors()).extracting(SnapshotIndex.Ancestor::role).containsExactly("main", "iframe");
        assertThat(SnapshotIndex.REF.matcher("f1e2").matches()).isTrue();
        assertThat(SnapshotIndex.REF.matcher("#submit").matches()).isFalse();
        assertThat(SnapshotIndex.REF.matcher("button").matches()).isFalse();
    }

    @Test
    void unparsableLinesWithARefAreUnknownNotGuessed() {
        SnapshotIndex index = SnapshotIndex.parse(FORM);

        SnapshotIndex.Node broken = index.find("e11").orElseThrow();
        assertThat(broken.known()).isFalse();
        assertThat(broken.role()).isNull();
        assertThat(index.find("e404")).isEmpty();
        assertThat(index.contains(null)).isFalse();
    }

    @Test
    void emptyAndNullSnapshotsHaveNoRefs() {
        assertThat(SnapshotIndex.parse(null).size()).isZero();
        assertThat(SnapshotIndex.parse("").size()).isZero();
        assertThat(SnapshotIndex.of(null).size()).isZero();
        assertThat(SnapshotIndex.of(new PageSnapshot("https://jobs.example.com", "", FORM, "")).find("e4"))
                .isPresent();
        assertThat(List.of("e1", "e2", "e3")).allMatch(SnapshotIndex.parse(FORM)::contains);
    }
}
