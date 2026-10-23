package jobapply.harness.policies;

import static org.assertj.core.api.Assertions.assertThat;

import jobapply.tools.SnapshotIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * C 级字段的识别。页面数据全部虚构。
 */
class FieldClassGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {"身份证号", "证件类型", "证件号码", "护照号", "签证类型", "是否需要工作授权", "期望薪资",
            "期望薪资（月薪）", "目前薪资", "民族", "宗教信仰", "是否残障", "健康状况", "有无犯罪记录", "个人声明",
            "诚信承诺", "Will you now or in the future require sponsorship?", "Work Authorization",
            "Are you legally authorized to work in the US?", "Expected Salary", "Desired compensation",
            "Disability status", "Veteran Status", "Ethnicity", "Race", "I certify that the information is true",
            "I attest that…", "Passport number", "ＳＡＬＡＲＹ", "  visa  "})
    void classCFieldNamesAreHandedToTheUser(String label) {
        SnapshotIndex index = SnapshotIndex.parse("- textbox \"" + label.replace("\"", "\\\"") + "\" [ref=e2]");

        GuardDecision decision = FieldClassGuard.check(index, "e2", null);

        assertThat(decision).as(label).isInstanceOf(GuardDecision.Handoff.class);
        assertThat(((GuardDecision.Handoff) decision).reason()).contains("用户亲自填写");
    }

    @ParameterizedTest
    @ValueSource(strings = {"学历证明", "期望城市", "期望职位", "期望入职时间", "姓名", "手机号", "邮箱", "毕业院校",
            "专业", "工作经历", "项目经历", "自我评价", "Expected graduation date", "First name", "Email address",
            "LinkedIn profile", "Cover letter", "Years of experience", "Preferred location", "Gender pronouns",
            "Programming languages", "Visual design portfolio"})
    void ordinaryFieldsAreNotBlocked(String label) {
        SnapshotIndex index = SnapshotIndex.parse("- textbox \"" + label + "\" [ref=e2]");

        assertThat(FieldClassGuard.check(index, "e2", null)).as(label).isInstanceOf(GuardDecision.Allow.class);
    }

    @Test
    void theGroupTitleCountsToo() {
        SnapshotIndex index = SnapshotIndex.parse("""
                - group "Work Authorization" [ref=e1]:
                  - radio "Yes" [ref=e2]
                  - radio "No" [ref=e3]
                - group "到岗时间" [ref=e4]:
                  - radio "一周内" [ref=e5]""");

        assertThat(FieldClassGuard.check(index, "e2", null)).isInstanceOf(GuardDecision.Handoff.class);
        assertThat(FieldClassGuard.check(index, "e3", null)).isInstanceOf(GuardDecision.Handoff.class);
        assertThat(FieldClassGuard.check(index, "e5", null)).isInstanceOf(GuardDecision.Allow.class);
    }

    @Test
    void theLabelGivenByTheModelCountsToo() {
        SnapshotIndex index = SnapshotIndex.parse("- textbox [ref=e2]");

        assertThat(FieldClassGuard.check(index, "e2", "期望薪资")).isInstanceOf(GuardDecision.Handoff.class);
        assertThat(FieldClassGuard.check(index, "e2", "姓名")).isInstanceOf(GuardDecision.Allow.class);
        assertThat(FieldClassGuard.check(SnapshotIndex.empty(), "e2", null)).isInstanceOf(GuardDecision.Allow.class);
    }
}
