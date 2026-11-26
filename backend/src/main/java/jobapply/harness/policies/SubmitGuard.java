package jobapply.harness.policies;

import java.util.LinkedHashMap;
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
 * ref，输出 {@link GuardDecision}。确认类按钮（「确定」「完成」「确认」，C006-fix-real-run 第 18 条）本身放行，
 * 但所在的对话框（没有对话框时是它的上一层）文字里有提交类词时（「确认提交申请？」里的「确定」）仍是硬边界。
 * 「下一步」「继续」「保存」这类非硬边界按钮由模型自己判断，代码不再做软边界拦截。
 * 页面上出现安全验证、网页弹窗或像是已经提交了时不强制停，只用 {@link #pageWarning(PageSnapshot)} 告诉模型。
 */
public final class SubmitGuard {

    static final String HARD_REASON = "这个按钮可能完成提交，由用户自己点";
    static final String CONFIRM_REASON = "这个确认按钮所在的对话框在问要不要提交 / 投递，可能完成提交，由用户自己点";

    /** 快照里的一行：缩进、角色（解析不出时为空）。 */
    private static final Pattern LINE_ROLE = Pattern.compile("^\\s*- '?([a-z][a-zA-Z]*)");
    /** 确认类按钮往上找的容器角色：找到最近的一个就看它的文字。 */
    private static final Set<String> CONTAINERS = Set.of("dialog", "alertdialog", "tooltip");

    private SubmitGuard() {
    }

    /**
     * 模型点击时可选附带的说明，只进日志。调用方发给 MCP 之前用 {@link #strip(Map)} 去掉。
     *
     * @param purpose            {@code next_step} / {@code save_draft} / {@code confirm} / {@code open_control} / {@code unsure}
     * @param finalStepSuspected 兼容旧参数（如果模型传了也会被剥离，不再参与放行判断）
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

        /** 去掉这些可选说明参数后的调用参数，发给 MCP。 */
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

    /** 点击之前检查：只拦判断不了的元素、提交类硬边界按钮以及提交确认框里的确认按钮。 */
    public static GuardDecision click(PageSnapshot latest, String ref) {
        return readOnly(latest, ref);
    }

    /**
     * 调研（只读）点击之前检查：只拦判断不了的元素和名字含提交类词的按钮；「下一页」「继续」这类放行。
     */
    public static GuardDecision readOnly(PageSnapshot latest, String ref) {
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
        if (SubmitWords.isConfirm(label) && SubmitWords.isHard(around(latest.snapshot(), ref))) {
            return new GuardDecision.Reject(CONFIRM_REASON + "（「" + label.strip() + "」）");
        }
        return GuardDecision.allow();
    }

    /**
     * 确认类按钮所在的地方的文字：最近的对话框 / 提示框祖先的整棵子树；没有时是它的上一层的子树；在最外层时是整页。
     * 去掉 {@code /url:} 行（网址里的 apply 之类不算）。拿不准时宁可多看（整页），多看只会让它回到硬边界。
     */
    static String around(String snapshot, String ref) {
        String[] lines = (snapshot == null ? "" : snapshot).replace("\r\n", "\n").split("\n");
        int at = -1;
        for (int i = 0; i < lines.length && at < 0; i++) {
            if (lines[i].contains("[ref=" + ref + "]")) {
                at = i;
            }
        }
        if (at < 0) {
            return String.join("\n", lines);
        }
        int parent = -1;
        int container = -1;
        int indent = indent(lines[at]);
        for (int i = at - 1; i >= 0 && indent > 0 && container < 0; i--) {
            if (lines[i].isBlank() || indent(lines[i]) >= indent) {
                continue;
            }
            indent = indent(lines[i]);
            if (parent < 0) {
                parent = i;
            }
            Matcher role = LINE_ROLE.matcher(lines[i]);
            if (role.find() && CONTAINERS.contains(role.group(1))) {
                container = i;
            }
        }
        int root = container >= 0 ? container : parent;
        if (root < 0) {
            return String.join("\n", lines);
        }
        StringBuilder text = new StringBuilder(lines[root]);
        for (int i = root + 1; i < lines.length && (lines[i].isBlank() || indent(lines[i]) > indent(lines[root])); i++) {
            if (!lines[i].strip().startsWith("- /url:")) {
                text.append('\n').append(lines[i]);
            }
        }
        return text.toString();
    }

    private static int indent(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
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
