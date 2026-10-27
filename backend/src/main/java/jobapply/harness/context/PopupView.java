package jobapply.harness.context;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jobapply.harness.policies.SnapshotRedactor;
import jobapply.tools.PageSnapshot;

/**
 * 点开下拉、级联、日期面板之后给模型看的页面（C016 F，参考 Stagehand 的前后对比、Agent-E 的「新出现的元素」）：
 * 只留新出现的、带选项的那一块和一行说明，不再给整页快照。Playwright 的 ref 对同一个元素是稳定的（角色和名字不变时
 * 沿用），所以「新出现的」= ref 在上一份快照里没有。系统内部（白名单、守卫、重复检测）仍用整页快照，这里只改给模型看的
 * 那一份。判断不了（换了页面、弹窗、整页重画、新出现的部分太大或太散）时返回空，调用方给整页。
 * <p>
 * 给出弹出层的这一步，上一份整页快照不省略、照原样留在对话里（{@link Conversation.LatestPage#base}，C016 实跑后修正）：
 * 模型仍看得到页面别的部分和那些 ref，不用为了找回它们按 Escape 再取整页。
 */
public final class PopupView {

    /** 弹出层视图开头的说明；{@link #isView} 靠它认出这是弹出层视图。 */
    static final String NOTE = "（系统：这次操作打开了弹出层，这里只给出新出现的、带选项的那一块。上一份整页快照还留在对话里，"
            + "那份快照里的 ref 仍然能用；它之后页面上别的变化（之前打开、还开着的弹出层，输入框里新键入的字）这里没有列出，"
            + "要看时用 browser_snapshot 取整页。）";

    /** 选项类的角色：listitem、cell 要至少两个才算（普通列表、表格也有）。 */
    private static final Set<String> STRONG = Set.of(
            "option", "menuitem", "menuitemradio", "menuitemcheckbox", "treeitem", "gridcell");
    private static final Set<String> WEAK = Set.of("listitem", "cell");
    private static final Pattern REF = Pattern.compile("\\[ref=([^\\]\\s]+)]");
    private static final Pattern ROLE = Pattern.compile("^\\s*- '?([a-z][a-zA-Z]*)");
    /** 页面太小时整页也不贵，不省。 */
    private static final int MIN_PAGE_LINES = 40;
    private static final int MAX_BLOCKS = 3;

    private record Line(int indent, String text, String ref, String role) {
    }

    private PopupView() {
    }

    /**
     * @param before 操作之前（模型上一次看到的）的页面
     * @param after  操作之后的整页快照
     * @param target 操作的元素 ref；没有时为 null
     */
    public static Optional<PageSnapshot> of(PageSnapshot before, PageSnapshot after, String target) {
        if (before == null || after == null || !Objects.equals(before.url(), after.url())
                || (after.modalState() != null && !after.modalState().isBlank())) {
            return Optional.empty();
        }
        Set<String> old = new HashSet<>();
        Matcher seen = REF.matcher(before.snapshot());
        while (seen.find()) {
            old.add(seen.group(1));
        }
        // 先按整页脱敏（C 级字段要看祖先），再截取
        List<Line> lines = parse(SnapshotRedactor.redact(after.snapshot()));
        if (lines.size() < MIN_PAGE_LINES || old.isEmpty()) {
            return Optional.empty();
        }
        List<int[]> blocks = new ArrayList<>();
        int kept = 0;
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (line.ref() == null || old.contains(line.ref()) || !newRoot(lines, i, old)) {
                continue;
            }
            int end = end(lines, i);
            if (options(lines, i, end)) {
                blocks.add(new int[]{i, end});
                kept += end - i;
            }
            i = end - 1;
        }
        if (blocks.isEmpty() || blocks.size() > MAX_BLOCKS || kept * 10 > lines.size() * 6) {
            return Optional.empty();
        }
        StringBuilder text = new StringBuilder(NOTE);
        lines.stream().filter(line -> target != null && target.equals(line.ref())).findFirst()
                .ifPresent(line -> text.append("\n操作的元素：\n").append(line.text().strip()));
        text.append("\n弹出层：");
        for (int[] block : blocks) {
            int base = lines.get(block[0]).indent();
            for (int i = block[0]; i < block[1]; i++) {
                Line line = lines.get(i);
                text.append('\n').append(line.text().substring(Math.min(base, line.indent())));
            }
        }
        return Optional.of(new PageSnapshot(after.url(), after.title(), text.toString(), after.modalState()));
    }

    /** 这份页面是不是 {@link #of} 给出的弹出层视图（不是整页）。 */
    public static boolean isView(PageSnapshot page) {
        return page != null && page.snapshot() != null && page.snapshot().startsWith(NOTE);
    }

    /** 往上找带 ref 的祖先：都是旧的（或没有）才是新出现部分的根。 */
    private static boolean newRoot(List<Line> lines, int at, Set<String> old) {
        int indent = lines.get(at).indent();
        for (int i = at - 1; i >= 0 && indent > 0; i--) {
            Line up = lines.get(i);
            if (up.indent() < indent) {
                if (up.ref() != null && !old.contains(up.ref())) {
                    return false;
                }
                indent = up.indent();
            }
        }
        return true;
    }

    /** 子树结束的位置（下一行缩进不比它深的地方）。 */
    private static int end(List<Line> lines, int at) {
        int indent = lines.get(at).indent();
        int i = at + 1;
        while (i < lines.size() && lines.get(i).indent() > indent) {
            i++;
        }
        return i;
    }

    private static boolean options(List<Line> lines, int from, int to) {
        int weak = 0;
        for (int i = from; i < to; i++) {
            String role = lines.get(i).role();
            if (role == null) {
                continue; // 不带角色的行（- /url:、- /placeholder:、多行文字）；Set.of 的 contains(null) 会抛 NullPointerException
            }
            if (STRONG.contains(role)) {
                return true;
            }
            if (WEAK.contains(role)) {
                weak++;
            }
        }
        return weak >= 2;
    }

    private static List<Line> parse(String yaml) {
        List<Line> lines = new ArrayList<>();
        for (String raw : yaml.replace("\r\n", "\n").split("\n")) {
            if (raw.isBlank()) {
                continue;
            }
            int indent = 0;
            while (indent < raw.length() && raw.charAt(indent) == ' ') {
                indent++;
            }
            Matcher ref = REF.matcher(raw);
            Matcher role = ROLE.matcher(raw);
            lines.add(new Line(indent, raw, ref.find() ? ref.group(1) : null, role.find() ? role.group(1) : null));
        }
        return lines;
    }
}
