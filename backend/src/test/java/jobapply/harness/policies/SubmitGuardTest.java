package jobapply.harness.policies;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import jobapply.harness.policies.SubmitGuard.ClickIntent;
import jobapply.tools.PageSnapshot;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * 五条规则逐条测。页面数据全部虚构。
 */
class SubmitGuardTest {

    private static final ClickIntent NEXT = new ClickIntent("next_step", false, "进度条显示第 2/4 步");

    private static PageSnapshot page(String snapshot) {
        return page(snapshot, "");
    }

    private static PageSnapshot page(String snapshot, String modal) {
        return new PageSnapshot("https://jobs.example.com/apply/1", "申请表", snapshot, modal);
    }

    private static PageSnapshot button(String name) {
        return page("- main [ref=e1]:\n  - button \"" + name.replace("\"", "\\\"") + "\" [ref=e2]");
    }

    private static GuardDecision click(String name, ClickIntent intent) {
        return SubmitGuard.click(button(name), "e2", intent, Set.of());
    }

    // 1. 页面状态

    @ParameterizedTest
    @ValueSource(strings = {"请输入验证码", "拖动滑块完成拼图", "Please complete the reCAPTCHA",
            "Verify you are human", "安全验证"})
    void verificationOnThePageIsAHandoff(String text) {
        PageSnapshot latest = page("- text: " + text + "\n- button \"下一步\" [ref=e2]");

        assertThat(SubmitGuard.click(latest, "e2", NEXT, Set.of())).isInstanceOf(GuardDecision.Handoff.class);
        assertThat(SubmitGuard.pressKey(latest)).isInstanceOf(GuardDecision.Handoff.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"投递成功", "您的申请已提交", "Application submitted", "Thank you for applying!",
            "  THANK YOU FOR APPLYING  ", "感谢您的申请，我们会尽快联系你"})
    void submittedLookingPagesStopAllWrites(String text) {
        PageSnapshot latest = page("- heading \"" + text + "\" [ref=e1]\n- link \"返回首页\" [ref=e2]");

        GuardDecision decision = SubmitGuard.click(latest, "e2", null, Set.of());
        assertThat(decision).isInstanceOf(GuardDecision.Handoff.class);
        assertThat(((GuardDecision.Handoff) decision).reason()).contains("已经提交");
        assertThat(SubmitGuard.pageState(latest)).isPresent();
    }

    @Test
    void webDialogsAreAHandoffButTheFileChooserIsNot() {
        String snapshot = "- button \"上传简历\" [ref=e2]";
        PageSnapshot dialog = page(snapshot,
                "- [\"confirm\" dialog with message \"确定离开？\"]: can be handled by browser_handle_dialog");
        PageSnapshot chooser = page(snapshot, "- [File chooser]: can be handled by browser_file_upload");

        assertThat(SubmitGuard.click(dialog, "e2", null, Set.of())).isInstanceOf(GuardDecision.Handoff.class);
        assertThat(SubmitGuard.click(chooser, "e2", null, Set.of())).isInstanceOf(GuardDecision.Allow.class);
    }

    // 2. 判断不了

    @Test
    void unknownRefsAndUnparsableLinesAreRejected() {
        PageSnapshot latest = page("- button \"下一步\" [ref=e2]\n- ??? [ref=e3]");

        assertThat(SubmitGuard.click(latest, "e9", NEXT, Set.of())).isInstanceOf(GuardDecision.Reject.class);
        assertThat(SubmitGuard.click(latest, "e3", NEXT, Set.of())).isInstanceOf(GuardDecision.Reject.class);
        assertThat(SubmitGuard.click(null, "e2", NEXT, Set.of())).isInstanceOf(GuardDecision.Reject.class);
        assertThat(((GuardDecision.Reject) SubmitGuard.click(latest, "e9", NEXT, Set.of())).reason())
                .contains("不要重试");
    }

    // 3. 硬边界

    @ParameterizedTest
    @ValueSource(strings = {"提交", "提交申请", "投递简历", "立即申请", "预览并提交", "确认意向", "确认", "Submit",
            "SUBMIT APPLICATION", "  submit  ", "Apply", "Apply now", "Send", "Finish", "Confirm", "Done",
            "ｓｕｂｍｉｔ", "提　交", "保存并提交", "Save and submit", "继续申请"})
    void hardBoundaryButtonsAreRejectedEvenWithAConfidentPurpose(String name) {
        GuardDecision decision = click(name, NEXT);

        assertThat(decision).as(name).isInstanceOf(GuardDecision.Reject.class);
        assertThat(((GuardDecision.Reject) decision).reason()).contains("由用户自己点").contains("不要重试");
    }

    @Test
    void applyOnTheJobDetailPageIsRejectedToo() {
        PageSnapshot detail = page("""
                - heading "Backend Engineer" [level=1] [ref=e1]
                - text: 上海 · 全职
                - button "立即申请" [ref=e2]""");

        assertThat(SubmitGuard.click(detail, "e2", NEXT, Set.of())).isInstanceOf(GuardDecision.Reject.class);
    }

    @Test
    void userConfirmationNeverUnlocksAHardBoundaryButton() {
        assertThat(SubmitGuard.click(button("保存并提交"), "e2", NEXT, Set.of("保存并提交")))
                .isInstanceOf(GuardDecision.Reject.class);
        assertThat(SubmitGuard.click(button("Submit"), "e2", NEXT, Set.of("submit")))
                .isInstanceOf(GuardDecision.Reject.class);
    }

    @Test
    void unnamedButtonsAreJudgedByTheirText() {
        PageSnapshot latest = page("- button [ref=e2]: 提交");

        assertThat(SubmitGuard.click(latest, "e2", NEXT, Set.of())).isInstanceOf(GuardDecision.Reject.class);
    }

    // 4. 软边界

    @ParameterizedTest
    @ValueSource(strings = {"下一步", "继续", "下一页", "保存", "暂存", "Next", "Continue", "Save", "保存并继续",
            "Save & Continue", " NEXT "})
    void softButtonsWithAClearPurposeAreAllowed(String name) {
        assertThat(click(name, NEXT)).as(name).isInstanceOf(GuardDecision.Allow.class);
        assertThat(click(name, new ClickIntent("save_draft", false, "页面上写着「草稿会自动保存」")))
                .isInstanceOf(GuardDecision.Allow.class);
    }

    @Test
    void softButtonsNeedConfirmationWhenUnsureFinalOrMissingParameters() {
        assertThat(click("下一步", new ClickIntent("unsure", false, "看不到进度条")))
                .isInstanceOf(GuardDecision.NeedsConfirm.class);
        assertThat(click("下一步", new ClickIntent("next_step", true, "进度条显示第 4/4 步")))
                .isInstanceOf(GuardDecision.NeedsConfirm.class);
        assertThat(click("下一步", new ClickIntent("next_step", null, "第 2/4 步")))
                .isInstanceOf(GuardDecision.NeedsConfirm.class);
        assertThat(click("下一步", new ClickIntent("next_step", false, " ")))
                .isInstanceOf(GuardDecision.NeedsConfirm.class);
        assertThat(click("下一步", new ClickIntent("submit", false, "第 2/4 步")))
                .isInstanceOf(GuardDecision.NeedsConfirm.class);

        GuardDecision missing = click("Continue", null);
        assertThat(missing).isEqualTo(new GuardDecision.NeedsConfirm("Continue", null, null, null));
    }

    @Test
    void confirmedSoftButtonsAreAllowedWithoutParameters() {
        assertThat(SubmitGuard.click(button("下一步"), "e2", null, Set.of("下一步")))
                .isInstanceOf(GuardDecision.Allow.class);
        assertThat(SubmitGuard.click(button("Next"), "e2", null, Set.of(" next ")))
                .isInstanceOf(GuardDecision.Allow.class);
        assertThat(SubmitGuard.click(button("继续"), "e2", null, Set.of("下一步")))
                .isInstanceOf(GuardDecision.NeedsConfirm.class);
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

    // 5. 其它

    @ParameterizedTest
    @ValueSource(strings = {"上传简历", "添加经历", "删除", "上一步", "My Applications", "查看职位详情", "北京"})
    void everythingElseIsAllowed(String name) {
        assertThat(click(name, null)).as(name).isInstanceOf(GuardDecision.Allow.class);
    }

    @Test
    void linksOptionsAndTabsAreAllowed() {
        PageSnapshot latest = page("""
                - link "隐私政策" [ref=e2]
                - option "本科" [ref=e3]
                - tab "教育经历" [ref=e4]""");

        assertThat(SubmitGuard.click(latest, "e2", null, Set.of())).isInstanceOf(GuardDecision.Allow.class);
        assertThat(SubmitGuard.click(latest, "e3", null, Set.of())).isInstanceOf(GuardDecision.Allow.class);
        assertThat(SubmitGuard.click(latest, "e4", null, Set.of())).isInstanceOf(GuardDecision.Allow.class);
        assertThat(SubmitGuard.pressKey(latest)).isInstanceOf(GuardDecision.Allow.class);
    }
}
