package jobapply.harness.context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jobapply.domain.ApplicationState;

/**
 * 申请状态（C019）给模型看的那一段：每一步接在最后一条消息的末尾（{@link Conversation}），紧跟最新页面。
 * 要短（OpenMAIC 的 summarizer：截断、只列前几项）：每个字段一行；整个区域都好了的只写一行；值、选项截断。
 */
public final class StateView {

    static final String HEAD = "# 申请状态（每一步按最新的更新重写；你用 update_state 改，填写结果系统自动记）";
    static final int VALUE_CHARS = 40;
    static final int OPTION_CHARS = 40;
    static final int OPTIONS = 40;
    private static final Map<String, String> STATUS = Map.of(
            ApplicationState.TODO, "待填",
            ApplicationState.FILLED, "已填",
            ApplicationState.VERIFIED, "已核对",
            ApplicationState.NEEDS_USER, "要问用户",
            ApplicationState.SKIPPED, "跳过");

    private StateView() {
    }

    /** @param goal 一句话目标（公司 · 岗位）；为 null 时不写 */
    public static String render(String goal, ApplicationState state) {
        StringBuilder out = new StringBuilder(HEAD).append('\n');
        if (goal != null && !goal.isBlank()) {
            out.append("目标：").append(goal.strip()).append('\n');
        }
        if (state.isEmpty()) {
            return out.append("还是空的：先用 update_state 写下现在在哪一步、这一页的字段清单（id、区域、标签、控件、要填的值和来源）"
                    + "和计划，之后照它一批一批地填。").toString();
        }
        if (state.stage() != null) {
            out.append("现在在：").append(state.stage()).append('\n');
        }
        if (!state.steps().isEmpty()) {
            out.append("计划：\n");
            state.steps().forEach(step -> out.append("- [").append(step.status()).append("] ").append(step.text())
                    .append('\n'));
        }
        if (!state.fields().isEmpty()) {
            out.append("字段：共 ").append(state.fields().size()).append(" 个");
            for (String status : List.of(ApplicationState.TODO, ApplicationState.FILLED, ApplicationState.VERIFIED,
                    ApplicationState.NEEDS_USER, ApplicationState.SKIPPED)) {
                long n = state.count(status);
                if (n > 0) {
                    out.append("，").append(STATUS.get(status)).append(' ').append(n);
                }
            }
            out.append('\n');
            sections(state.fields()).forEach((section, fields) -> {
                String name = section == null ? "（没写区域）" : section;
                if (fields.stream().allMatch(ApplicationState.Field::done)) {
                    out.append("## ").append(name).append("：").append(fields.size()).append(" 个都好了\n");
                    return;
                }
                out.append("## ").append(name).append('\n');
                fields.forEach(field -> out.append(line(field)).append('\n'));
            });
        }
        if (!state.notes().isEmpty()) {
            out.append("决定和站点规律：\n");
            state.notes().forEach(note -> out.append("- ").append(note).append('\n'));
        }
        return out.toString().stripTrailing();
    }

    /** 按区域分组，保持第一次出现的顺序。 */
    private static Map<String, List<ApplicationState.Field>> sections(List<ApplicationState.Field> fields) {
        Map<String, List<ApplicationState.Field>> out = new LinkedHashMap<>();
        fields.forEach(field -> out.computeIfAbsent(field.section(), ignored -> new ArrayList<>()).add(field));
        return out;
    }

    /** {@code - id 标签 [控件] 状态 = 值（来源） · 说明 · 见过的选项：a / b（共 n 项）} */
    private static String line(ApplicationState.Field field) {
        StringBuilder out = new StringBuilder("- ").append(field.id()).append(' ').append(field.label());
        if (field.widget() != null) {
            out.append(" [").append(field.widget()).append(']');
        }
        out.append(' ').append(STATUS.getOrDefault(field.status(), String.valueOf(field.status())));
        if (field.value() != null && !field.value().isBlank()) {
            out.append(" = ").append(cut(field.value(), VALUE_CHARS));
        }
        if (field.source() != null && !field.source().isBlank()) {
            out.append("（").append(field.source()).append("）");
        }
        if (field.note() != null && !field.note().isBlank()) {
            out.append(" · ").append(cut(field.note(), 120));
        }
        if (!field.options().isEmpty()) {
            List<String> shown = field.options().stream().limit(OPTIONS).map(option -> cut(option, OPTION_CHARS)).toList();
            out.append(" · 见过的选项：").append(String.join(" / ", shown));
            if (field.options().size() > OPTIONS) {
                out.append("（共 ").append(field.options().size()).append(" 项）");
            }
        }
        return out.toString();
    }

    /** 压成一行、截到 {@code max} 个字。 */
    private static String cut(String text, int max) {
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() > max ? flat.substring(0, max) + "…（共 " + flat.length() + " 字）" : flat;
    }
}
