package jobapply.harness.policies;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import jobapply.harness.policies.SubmitGuard.ClickIntent;
import jobapply.tools.PageSnapshot;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * 提交类按钮一律拒绝；「下一步」类按钮按模型的判断放行，怀疑是最后一步时先问用户；其它点击放行；页面状态只给提醒。
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
    @ValueSource(strings = {"提交", "提交申请", "投递简历", "立即申请", "预览并提交", "确认意向", "确认", "Submit",
            "SUBMIT APPLICATION", "  submit  ", "Apply", "Apply now", "Send", "Finish", "Confirm", "Done",
            "ｓｕｂｍｉｔ", "提　交", "保存并提交", "Save and submit", "继续申请"})
    void submitButtonsAreRejected(String name) {
        GuardDecision decision = click(name);

        assertThat(decision).as(name).isInstanceOf(GuardDecision.Reject.class);
        assertThat(((GuardDecision.Reject) decision).reason()).contains("由用户自己点").contains("不要重试");
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

    // 其它

    @ParameterizedTest
    @ValueSource(strings = {"上传简历", "添加经历", "删除", "上一步", "My Applications", "查看职位详情", "北京"})
    void everythingElseIsAllowed(String name) {
        assertThat(click(name)).as(name).isInstanceOf(GuardDecision.Allow.class);
    }

    // 「下一步」这类按钮（C006-fix-runtime-2）：模型判断是不是最后一步；怀疑是、或者页面上看得出可能直接提交时先问用户

    private static final ClickIntent NOT_LAST = new ClickIntent("next_step", false, "第 1/3 步");
    private static final ClickIntent LAST = new ClickIntent("next_step", true, "按钮旁写着「提交后不可修改」");

    @ParameterizedTest
    @ValueSource(strings = {"第 1/3 步", "第 2 步，共 4 步", "Step 1 of 2", "step 2/3", "没有步骤条"})
    void softButtonsAreAllowedWhenTheModelSaysItIsNotTheLastStep(String progress) {
        PageSnapshot latest = page("- text: " + progress + "\n- button \"下一步\" [ref=e2]\n- button \"Next\" [ref=e3]"
                + "\n- button \"保存\" [ref=e4]");

        assertThat(SubmitGuard.click(latest, "e2", NOT_LAST, null)).as(progress).isInstanceOf(GuardDecision.Allow.class);
        assertThat(SubmitGuard.click(latest, "e3", NOT_LAST, null)).as(progress).isInstanceOf(GuardDecision.Allow.class);
        assertThat(SubmitGuard.click(latest, "e4", NOT_LAST, null)).as(progress).isInstanceOf(GuardDecision.Allow.class);
    }

    @Test
    void softButtonsWithoutTheModelsJudgementGoBackToTheModelNotTheUser() {
        assertThat(SubmitGuard.click(button("继续"), "e2")).isInstanceOf(GuardDecision.Explain.class);
        assertThat(SubmitGuard.click(button("保存"), "e2", new ClickIntent("save_draft", null, null), null))
                .isInstanceOf(GuardDecision.Explain.class);
    }

    @Test
    void theUserIsAskedWhenTheModelSuspectsTheLastStepOrThePageShowsIt() {
        PageSnapshot lastStep = page("- text: 第 3/3 步\n- button \"下一步\" [ref=e2]");
        PageSnapshot withSubmit = page("- text: 第 1/2 步\n- button \"下一步\" [ref=e2]\n- button \"提交申请\" [ref=e3]");

        assertThat(SubmitGuard.click(button("继续"), "e2", LAST, null)).isInstanceOf(GuardDecision.AskUser.class);
        // 页面上看得出可能直接提交（夹具 submitted-page）：模型说不是最后一步也要先问
        assertThat(SubmitGuard.click(lastStep, "e2", NOT_LAST, null)).isInstanceOf(GuardDecision.AskUser.class);
        assertThat(SubmitGuard.click(withSubmit, "e2", NOT_LAST, null)).isEqualTo(
                new GuardDecision.AskUser("下一步", "同一页上还有提交类按钮，这个按钮也可能直接提交"));
        // 保存不看页面信号，免得每次保存都问
        PageSnapshot saveWithSubmit = page("- button \"保存\" [ref=e2]\n- button \"提交申请\" [ref=e3]");
        assertThat(SubmitGuard.click(saveWithSubmit, "e2", NOT_LAST, null)).isInstanceOf(GuardDecision.Allow.class);
    }

    @Test
    void anApprovalCoversOnlyThatButtonOnThatPageAndNeverASubmitButton() {
        PageSnapshot withSubmit = page("- button \"下一步\" [ref=e2]\n- button \"继续\" [ref=e4]"
                + "\n- button \"提交申请\" [ref=e3]");
        String url = withSubmit.url();
        SubmitGuard.Approval approved = new SubmitGuard.Approval(url, "e2", "下一步");

        assertThat(SubmitGuard.click(withSubmit, "e2", null, approved)).isInstanceOf(GuardDecision.Allow.class);
        assertThat(SubmitGuard.click(withSubmit, "e4", NOT_LAST, approved)).as("别的按钮")
                .isInstanceOf(GuardDecision.AskUser.class);
        PageSnapshot otherPage = new PageSnapshot("https://jobs.example.com/apply/2", "申请表", withSubmit.snapshot(), "");
        assertThat(SubmitGuard.click(otherPage, "e2", NOT_LAST, approved)).as("别的页面")
                .isInstanceOf(GuardDecision.AskUser.class);
        assertThat(SubmitGuard.click(withSubmit, "e3", null, new SubmitGuard.Approval(url, "e3", "提交申请")))
                .isInstanceOf(GuardDecision.Reject.class);
        assertThat(SubmitGuard.click(withSubmit, "e3", NOT_LAST, null)).isInstanceOf(GuardDecision.Reject.class);
    }

    @Test
    void researchLetsSoftButtonsThroughButNeverSubmitButtons() {
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
        args.put("final_step_suspected", false);
        args.put("evidence", "第 2/4 步");

        assertThat(ClickIntent.from(args)).isEqualTo(new ClickIntent("next_step", false, "第 2/4 步"));
        assertThat(ClickIntent.strip(args)).containsExactly(Map.entry("target", "e2"));
        assertThat(ClickIntent.from(Map.of("purpose", 3))).isEqualTo(new ClickIntent(null, null, null));
    }
}
