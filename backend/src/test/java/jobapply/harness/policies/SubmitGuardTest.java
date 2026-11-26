package jobapply.harness.policies;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import jobapply.harness.policies.SubmitGuard.ClickIntent;
import jobapply.tools.PageSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 提交类按钮一律拒绝；普通确认/下一步/保存按钮在非提交语境下放行；页面状态只给提醒。
 * 页面数据全部虚构。
 */
class SubmitGuardTest {

    private static PageSnapshot page(String snapshot) {
        return page(snapshot, "");
    }

    private static PageSnapshot page(String snapshot, String modal) {
        return new PageSnapshot("https://jobs.example.com/apply/1", "申请表", snapshot, modal);
    }

    private static PageSnapshot button(String name) {
        return page("- main [ref=e1]:\n  - button \"" + name.replace("\"", "\\\"") + "\" [ref=e2]");
    }

    private static GuardDecision click(String name) {
        return SubmitGuard.click(button(name), "e2");
    }

    // 页面状态：只提醒，不拦

    @ParameterizedTest
    @ValueSource(strings = {"请输入验证码", "拖动滑块完成拼图", "Please complete the reCAPTCHA",
            "Verify you are human", "安全验证"})
    void verificationOnThePageIsAWarning(String text) {
        PageSnapshot latest = page("- text: " + text + "\n- button \"下一步\" [ref=e2]");

        assertThat(SubmitGuard.pageWarning(latest)).get().asString().contains("安全验证");
    }

    @ParameterizedTest
    @ValueSource(strings = {"投递成功", "您的申请已提交", "Application submitted", "Thank you for applying!",
            "  THANK YOU FOR APPLYING  ", "感谢您的申请，我们会尽快联系你"})
    void submittedLookingPagesAreAWarning(String text) {
        PageSnapshot latest = page("- heading \"" + text + "\" [ref=e1]\n- link \"返回首页\" [ref=e2]");

        assertThat(SubmitGuard.pageWarning(latest)).get().asString().contains("已经提交");
    }

    @Test
    void webDialogsAreAWarningButTheFileChooserIsNot() {
        String snapshot = "- button \"上传简历\" [ref=e2]";
        PageSnapshot dialog = page(snapshot,
                "- [\"confirm\" dialog with message \"确定离开？\"]: can be handled by browser_handle_dialog");
        PageSnapshot chooser = page(snapshot, "- [File chooser]: can be handled by browser_file_upload");

        assertThat(SubmitGuard.pageWarning(dialog)).isPresent();
        assertThat(SubmitGuard.pageWarning(chooser)).isEmpty();
    }

    // 判断不了

    @Test
    void unknownRefsAndUnparsableLinesAreRejected() {
        PageSnapshot latest = page("- button \"下一步\" [ref=e2]\n- ??? [ref=e3]");

        assertThat(SubmitGuard.click(latest, "e9")).isInstanceOf(GuardDecision.Reject.class);
        assertThat(SubmitGuard.click(latest, "e3")).isInstanceOf(GuardDecision.Reject.class);
        assertThat(SubmitGuard.click(null, "e2")).isInstanceOf(GuardDecision.Reject.class);
        assertThat(((GuardDecision.Reject) SubmitGuard.click(latest, "e9")).reason()).contains("不要重试");
    }

    // 提交类按钮

    @ParameterizedTest
    @ValueSource(strings = {"提交", "提交申请", "投递简历", "立即申请", "预览并提交", "确认意向", "确认提交", "Submit",
            "SUBMIT APPLICATION", "  submit  ", "Apply", "Apply now", "Send", "Finish", "确定投递", "完成申请",
            "ｓｕｂｍｉｔ", "提　交", "保存并提交", "Save and submit", "继续申请"})
    void submitButtonsAreRejected(String name) {
        GuardDecision decision = click(name);

        assertThat(decision).as(name).isInstanceOf(GuardDecision.Reject.class);
        assertThat(((GuardDecision.Reject) decision).reason()).contains("由用户自己点").contains("不要重试");
    }

    @ParameterizedTest
    @ValueSource(strings = {"确定", "确 定", "确认", "OK", "Confirm"})
    void aConfirmInADialogAskingToSubmitTheApplicationIsStillRejected(String name) {
        PageSnapshot submit = page("""
                - main [ref=e1]:
                  - textbox "姓名" [ref=e4]
                - dialog "提示" [ref=e5]:
                  - text: 确认提交申请？提交后不可修改
                  - button "取消" [ref=e6]
                  - button "%s" [ref=e2]""".formatted(name));
        PageSnapshot deliver = page("""
                - dialog [ref=e5]:
                  - generic [ref=e7]:
                    - text: 确定投递该职位吗？
                  - generic [ref=e8]:
                    - button "取消" [ref=e6]
                    - button "%s" [ref=e2]""".formatted(name));

        for (PageSnapshot latest : new PageSnapshot[]{submit, deliver}) {
            assertThat(SubmitGuard.click(latest, "e2")).as(name).isInstanceOf(GuardDecision.Reject.class);
            assertThat(SubmitGuard.readOnly(latest, "e2")).as(name).isInstanceOf(GuardDecision.Reject.class);
        }
    }

    @Test
    void applyOnTheJobDetailPageIsRejectedToo() {
        PageSnapshot detail = page("""
                - heading "Backend Engineer" [level=1] [ref=e1]
                - text: 上海 · 全职
                - button "立即申请" [ref=e2]""");

        assertThat(SubmitGuard.click(detail, "e2")).isInstanceOf(GuardDecision.Reject.class);
    }

    @Test
    void unnamedButtonsAreJudgedByTheirText() {
        assertThat(SubmitGuard.click(page("- button [ref=e2]: 提交"), "e2")).isInstanceOf(GuardDecision.Reject.class);
    }

    // 普通确认 / 下一步 / 保存与其它

    @ParameterizedTest
    @ValueSource(strings = {"确定", "下一步", "保存", "OK", "Next", "继续", "完成"})
    void normalConfirmNextAndSaveButtonsAreAllowed(String name) {
        PageSnapshot latest = page("- generic [ref=e1]:\n  - grid \"2025年9月\" [ref=e5]\n  - button \"" + name
                + "\" [ref=e2]\n- button \"提交申请\" [ref=e3]");

        assertThat(SubmitGuard.click(latest, "e2")).as(name).isInstanceOf(GuardDecision.Allow.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"上传简历", "添加经历", "删除", "上一步", "My Applications", "查看职位详情", "北京"})
    void everythingElseIsAllowed(String name) {
        assertThat(click(name)).as(name).isInstanceOf(GuardDecision.Allow.class);
    }

    @Test
    void readOnlyLetsNormalButtonsThroughButNeverSubmitButtons() {
        PageSnapshot list = page("- button \"下一页\" [ref=e2]\n- button \"立即申请\" [ref=e3]");

        assertThat(SubmitGuard.readOnly(list, "e2")).isInstanceOf(GuardDecision.Allow.class);
        assertThat(SubmitGuard.readOnly(list, "e3")).isInstanceOf(GuardDecision.Reject.class);
        assertThat(SubmitGuard.readOnly(list, "e9")).isInstanceOf(GuardDecision.Reject.class);
    }

    @Test
    void intentParametersAreReadAndStrippedBeforeSendingToMcp() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("target", "e2");
        args.put("purpose", "next_step");
        args.put("evidence", "第 2/4 步");

        assertThat(ClickIntent.from(args).purpose()).isEqualTo("next_step");
        assertThat(ClickIntent.from(args).evidence()).isEqualTo("第 2/4 步");
        assertThat(ClickIntent.strip(args)).containsExactly(Map.entry("target", "e2"));
        assertThat(ClickIntent.from(Map.of("purpose", 3)).purpose()).isNull();
    }
}
