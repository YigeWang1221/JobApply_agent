package jobapply.harness.policies;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jobapply.tools.PageSnapshot;
import jobapply.tools.SnapshotIndex;

/**
 * 点击守卫（agent-runtime.md 7.6）：名字含提交类词（硬边界）的按钮一律不点，由用户自己点。纯函数：输入最新一次快照和
 * 这次调用，输出 {@link GuardDecision}。「下一步」「继续」「保存」这类按钮（软边界）平时放行；拿不准时
 * （页面上看不到第几步、已经是最后一步、同一页上还有提交类按钮）要模型先问用户（{@link GuardDecision.AskUser}，
 * 用户确认 2026-10-23：夹具 {@code submitted-page} 里「下一步」直接完成了投递）。
 * 页面上出现安全验证、网页弹窗或像是已经提交了时不强制停，只用 {@link #pageWarning(PageSnapshot)} 告诉模型。
 */
public final class SubmitGuard {

    static final String HARD_REASON = "这个按钮可能完成提交，由用户自己点";

    private SubmitGuard() {
    }

    /**
     * 模型点击时可以多给的说明（可选，只进日志）。调用方发给 MCP 之前用 {@link #strip(Map)} 去掉。
     *
     * @param purpose            {@code next_step} / {@code save_draft} / {@code open_control} / {@code unsure}
     * @param finalStepSuspected 是否像是最后一步
     * @param evidence           依据，例如「进度条显示第 2/4 步」
     */
    public record ClickIntent(String purpose, Boolean finalStepSuspected, String evidence) {

        public static final String PURPOSE = "purpose";
        public static final String FINAL_STEP_SUSPECTED = "final_step_suspected";
        public static final String EVIDENCE = "evidence";
        static final Set<String> KEYS = Set.of(PURPOSE, FINAL_STEP_SUSPECTED, EVIDENCE);

        public static ClickIntent from(Map<String, Object> args) {
            Map<String, Object> arguments = args == null ? Map.of() : args;
            return new ClickIntent(
                    arguments.get(PURPOSE) instanceof String purpose ? purpose : null,
                    arguments.get(FINAL_STEP_SUSPECTED) instanceof Boolean last ? last : null,
                    arguments.get(EVIDENCE) instanceof String evidence ? evidence : null);
        }

        /** 去掉这三个参数后的调用参数，发给 MCP。 */
        public static Map<String, Object> strip(Map<String, Object> args) {
            Map<String, Object> rest = new LinkedHashMap<>(args == null ? Map.of() : args);
            rest.keySet().removeAll(KEYS);
            return rest;
        }
    }

    /** 页面上有安全验证、网页弹窗，或像是已经提交了时给模型的提醒；没有这些时返回空。 */
    public static Optional<String> pageWarning(PageSnapshot latest) {
        if (latest == null) {
            return Optional.empty();
        }
        if (hasDialog(latest.modalState())) {
            return Optional.of("页面弹出了确认框，你处理不了，请用 ask_user 请用户处理");
        }
        String text = latest.visibleText();
        if (SubmitWords.looksLikeVerification(text)) {
            return Optional.of("页面像是出现了安全验证（验证码、滑块等），请用 ask_user 请用户完成");
        }
        if (SubmitWords.looksSubmitted(text)) {
            return Optional.of("页面像是已经提交成功了，不要再写任何东西，用 ask_user 告诉用户核对投递状态");
        }
        return Optional.empty();
    }

    /** 步骤条：「第 2/4 步」「第 2 步，共 4 步」「Step 2 of 4」「Step 2/4」。 */
    private static final List<Pattern> STEPS = List.of(
            Pattern.compile("第\\s*(\\d+)\\s*/\\s*(\\d+)\\s*步"),
            Pattern.compile("第\\s*(\\d+)\\s*步\\s*[，,、/]?\\s*共\\s*(\\d+)\\s*步"),
            Pattern.compile("(?i)step\\s*(\\d+)\\s*(?:of|/)\\s*(\\d+)"));

    /** 点击之前检查（没有用户确认过的按钮）。 */
    public static GuardDecision click(PageSnapshot latest, String ref) {
        return click(latest, ref, null);
    }

    /**
     * 点击之前检查：ref 要在最新快照里，名字不能含提交类词；软边界按钮拿不准时要先问用户。
     *
     * @param confirmed 用户刚确认过可以点的软边界按钮名字；没有时为 null
     */
    public static GuardDecision click(PageSnapshot latest, String ref, String confirmed) {
        if (latest == null) {
            return new GuardDecision.Reject("还没有最新快照，判断不了这个元素");
        }
        Optional<SnapshotIndex.Node> found = SnapshotIndex.of(latest).find(ref);
        if (found.isEmpty()) {
            return new GuardDecision.Reject("ref " + ref + " 不在最新快照里，先重新取快照");
        }
        SnapshotIndex.Node node = found.get();
        if (!node.known()) {
            return new GuardDecision.Reject("快照里 ref " + ref + " 这一行解析不出角色和名字");
        }
        String label = label(node);
        if (SubmitWords.isHard(label)) {
            return new GuardDecision.Reject(HARD_REASON + "（「" + label.strip() + "」）");
        }
        if (SubmitWords.isSoft(label)) {
            if (confirmed != null && SubmitWords.normalize(confirmed).equals(SubmitWords.normalize(label))) {
                return GuardDecision.allow();
            }
            String doubt = doubt(latest);
            if (doubt != null) {
                return new GuardDecision.AskUser(label.strip(), doubt);
            }
        }
        return GuardDecision.allow();
    }

    /** 软边界按钮为什么拿不准；看得出只是翻页时为 null。 */
    private static String doubt(PageSnapshot latest) {
        boolean submitOnPage = SnapshotIndex.of(latest).nodes().stream()
                .anyMatch(node -> "button".equals(node.role()) && SubmitWords.isHard(label(node)));
        if (submitOnPage) {
            return "同一页上还有提交类按钮，这个按钮也可能直接提交";
        }
        String text = latest.visibleText() + "\n" + latest.snapshot();
        for (Pattern pattern : STEPS) {
            Matcher step = pattern.matcher(text);
            if (step.find()) {
                return Integer.parseInt(step.group(1)) >= Integer.parseInt(step.group(2))
                        ? "已经是最后一步（第 " + step.group(1) + "/" + step.group(2) + " 步），这个按钮可能直接提交" : null;
            }
        }
        return "页面上看不出这是第几步，这个按钮可能直接提交";
    }

    /** 判断用的名字：可访问名字；没有名字时用行尾文字（例如 {@code - button: 提交}）。 */
    private static String label(SnapshotIndex.Node node) {
        return node.name().isBlank() ? node.text() : node.name();
    }

    private static boolean hasDialog(String modalState) {
        if (modalState == null || modalState.isBlank()) {
            return false;
        }
        String lower = modalState.toLowerCase(Locale.ROOT);
        return lower.contains("dialog with message") || lower.contains("browser_handle_dialog");
    }
}
