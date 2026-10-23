package jobapply.harness.policies;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import jobapply.tools.PageSnapshot;
import jobapply.tools.SnapshotIndex;

/**
 * 点击和按键守卫（C004 决定 5：硬边界 + 软边界）。纯函数：输入最新一次快照和这次调用，输出 {@link GuardDecision}。
 * 按顺序判断，命中一条就停：
 * <ol>
 *   <li>页面状态：安全验证、网页弹窗、疑似已提交 → {@link GuardDecision.Handoff}</li>
 *   <li>ref 不在最新快照里，或解析不出角色 → {@link GuardDecision.Reject}</li>
 *   <li>名字含硬边界词 → {@link GuardDecision.Reject}（含软边界词也一样，例如「保存并提交」）</li>
 *   <li>名字含软边界词 → 看模型给的用途；拿不准、像最后一步、缺参数 → {@link GuardDecision.NeedsConfirm}；
 *       用户对这个站点、这个按钮确认过的直接放行</li>
 *   <li>其它 → {@link GuardDecision.Allow}</li>
 * </ol>
 * 每次写操作之后，调用方都要重新取快照，再用 {@link #pageState(PageSnapshot)} 检查第 1 条。
 */
public final class SubmitGuard {

    static final String HARD_REASON = "这个按钮可能完成提交，由用户自己点";

    private SubmitGuard() {
    }

    /**
     * 模型点击软边界按钮时必须给出的三个参数。调用方发给 MCP 之前用 {@link #strip(Map)} 去掉。
     *
     * @param purpose            {@code next_step} / {@code save_draft} / {@code unsure}
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

        boolean clearlyNotFinal() {
            return ("next_step".equals(purpose) || "save_draft".equals(purpose))
                    && Boolean.FALSE.equals(finalStepSuspected)
                    && evidence != null && !evidence.isBlank();
        }
    }

    /** 第 1 条：页面上有安全验证、网页弹窗，或像是已经提交了。没有这些时返回空。 */
    public static Optional<GuardDecision> pageState(PageSnapshot latest) {
        if (latest == null) {
            return Optional.empty();
        }
        if (hasDialog(latest.modalState())) {
            return Optional.of(new GuardDecision.Handoff("页面弹出了确认框，由用户处理"));
        }
        String text = latest.visibleText();
        if (SubmitWords.looksLikeVerification(text)) {
            return Optional.of(new GuardDecision.Handoff("页面出现安全验证，由用户完成"));
        }
        if (SubmitWords.looksSubmitted(text)) {
            return Optional.of(new GuardDecision.Handoff("页面像是已经提交成功，停止一切写操作"));
        }
        return Optional.empty();
    }

    /**
     * 点击之前检查。
     *
     * @param confirmedSafe 用户对当前站点确认过「安全」的软边界按钮名字（C004-B 从 {@code sites/<host>.md} 读）
     */
    public static GuardDecision click(PageSnapshot latest, String ref, ClickIntent intent, Set<String> confirmedSafe) {
        if (latest == null) {
            return new GuardDecision.Reject("还没有最新快照，判断不了这个元素");
        }
        Optional<GuardDecision> state = pageState(latest);
        if (state.isPresent()) {
            return state.get();
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
            if (normalized(confirmedSafe).contains(SubmitWords.normalize(label))) {
                return GuardDecision.allow();
            }
            ClickIntent given = intent == null ? new ClickIntent(null, null, null) : intent;
            if (given.clearlyNotFinal()) {
                return GuardDecision.allow();
            }
            return new GuardDecision.NeedsConfirm(label.strip(), given.purpose(), given.finalStepSuspected(),
                    given.evidence());
        }
        return GuardDecision.allow();
    }

    /** 按键之前检查。回车、空格已经被白名单拒绝；其它允许的按键只看第 1 条。 */
    public static GuardDecision pressKey(PageSnapshot latest) {
        return pageState(latest).orElseGet(GuardDecision::allow);
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

    private static Set<String> normalized(Set<String> names) {
        return names == null ? Set.of() : names.stream().map(SubmitWords::normalize).collect(Collectors.toSet());
    }
}
