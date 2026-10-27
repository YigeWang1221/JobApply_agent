package jobapply.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 申请状态（C019）的合并：改坏了会悄悄丢掉字段清单里的字段或填写报告那一行。 */
class ApplicationStateTest {

    private static final ApplicationState.ReportRow ROW = new ApplicationState.ReportRow("简历", "示例大学", "来自 base（pin live）",
            "zh", null, "A");

    @Test
    void mergeOverridesOnlyGivenKeysAndKeepsOtherFields() {
        ApplicationState state = ApplicationState.empty()
                .merge(new ApplicationState.Field("edu1.school", "教育经历 1", "学校", "select", "示例大学",
                        "education.0.school", null, null, List.of("示例大学", "另一所大学"), null))
                .merge(new ApplicationState.Field("edu1.start", "教育经历 1", "入学时间", "date", "2021-09", null, null,
                        null, null, null));
        state = state.update("edu1.school", field -> field.filled(null, "示例大学", null, ApplicationState.VERIFIED, null,
                ROW));

        ApplicationState patched = state.merge(new ApplicationState.Field("edu1.school", null, null, null, null, null,
                ApplicationState.NEEDS_USER, "要问用户", null, null));

        assertThat(patched.fields()).hasSize(2);
        ApplicationState.Field school = patched.find("edu1.school").orElseThrow();
        assertThat(school.label()).isEqualTo("学校");
        assertThat(school.section()).isEqualTo("教育经历 1");
        assertThat(school.value()).isEqualTo("示例大学");
        assertThat(school.status()).isEqualTo(ApplicationState.NEEDS_USER);
        assertThat(school.options()).containsExactly("示例大学", "另一所大学");
        assertThat(school.report()).isEqualTo(ROW);
        assertThat(patched.find("edu1.start").orElseThrow().status()).isEqualTo(ApplicationState.TODO);
        assertThat(patched.remove("edu1.start").fields()).extracting(ApplicationState.Field::id).containsExactly("edu1.school");
    }

    @Test
    void newFieldNeedsLabel() {
        assertThatThrownBy(() -> ApplicationState.empty().merge(new ApplicationState.Field("x", null, null, null, null,
                null, null, null, null, null))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void locateByIdThenByOpenLabelThenAdds() {
        ApplicationState state = ApplicationState.empty()
                .merge(new ApplicationState.Field("edu1.start", "教育经历 1", "开始时间", null, null, null,
                        ApplicationState.FILLED, null, null, null))
                .merge(new ApplicationState.Field("edu2.start", "教育经历 2", "开始时间", null, null, null, null, null,
                        null, null));

        assertThat(state.locate("edu1.start", "开始时间").id()).isEqualTo("edu1.start");
        assertThat(state.locate(null, "开始时间：").id()).isEqualTo("edu2.start");
        ApplicationState.Located added = state.locate(null, "毕业时间");
        assertThat(added.id()).isEqualTo("f3");
        assertThat(added.state().fields()).hasSize(3);
    }

    @Test
    void answerGoesBackToTheWaitingField() {
        ApplicationState state = ApplicationState.empty().merge(new ApplicationState.Field("q1", null, "最早到岗时间", null,
                null, null, ApplicationState.NEEDS_USER, null, null, null));

        ApplicationState answered = state.answered("最早到岗时间", "ans-001", "2027-07");

        ApplicationState.Field field = answered.find("q1").orElseThrow();
        assertThat(field.status()).isEqualTo(ApplicationState.TODO);
        assertThat(field.source()).isEqualTo("ans-001");
        assertThat(field.value()).isEqualTo("2027-07");
    }
}
