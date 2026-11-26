package jobapply.agents;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import jobapply.harness.context.SkillCatalog;

/**
 * 一种工作模式（C030 外置 Mode）：Base Agent 的同一个循环，换上不同的系统提示词、工具集、插件组合和循环参数。
 * 定义在 classpath 的 {@code modes/<name>.md}：frontmatter 是装配，正文是系统提示词（{@link Modes}）。
 *
 * @param tools                ToolRegistry 里的工具集名字，按顺序
 * @param allowTools           只给这些工具（按名字，从上面的工具集里挑）；为空时工具集里的都给。Subagent 的 Mode 用它
 *                             从已有工具集里取一小份，不另写一套
 * @param subagents            这个 Mode 能用 {@code delegate} 交给哪些子 Mode（{@link SubagentExecutor}，单向）；为空时不能委托
 * @param plugins              插件名字，按顺序（{@link BaseAgent} 建）
 * @param dropReplyWhenStopped 调模型回来时用户已经要求停止：true 时丢掉这条回复直接停
 * @param toolCallLimit        {@code per-run}（申请一次运行）或 {@code per-message}（对话每条消息），上限的值在设置页
 * @param limitNote            到了上限时的系统说明，{@code {calls}} 换成已经用掉的次数
 * @param skills               这个 Mode 的 skill 目录范围
 */
public record Mode(String name, String description, String system, List<String> tools, List<String> allowTools,
                   List<String> plugins, List<String> subagents, boolean dropReplyWhenStopped, String toolCallLimit,
                   String limitNote, SkillScope skills) {

    public static final String PER_RUN = "per-run";
    public static final String PER_MESSAGE = "per-message";

    public Mode {
        tools = List.copyOf(tools);
        allowTools = List.copyOf(allowTools);
        plugins = List.copyOf(plugins);
        subagents = List.copyOf(subagents);
    }

    /**
     * skill 目录里给这个 Mode 看哪些（只影响上下文里列出的目录，{@code read_skill} 仍能读任何一个）。
     *
     * @param groups        只要这些第一层目录；为空时不限
     * @param excludeGroups 不要这些第一层目录
     * @param exclude       不要这些名字
     */
    public record SkillScope(List<String> groups, List<String> excludeGroups, List<String> exclude)
            implements Predicate<SkillCatalog.Skill> {

        public static final SkillScope ALL = new SkillScope(List.of(), List.of(), List.of());

        public SkillScope {
            groups = List.copyOf(groups);
            excludeGroups = List.copyOf(excludeGroups);
            exclude = List.copyOf(exclude);
        }

        @Override
        public boolean test(SkillCatalog.Skill skill) {
            return (groups.isEmpty() || groups.contains(skill.group())) && !excludeGroups.contains(skill.group())
                    && !exclude.contains(skill.name());
        }

        static SkillScope of(Object value) {
            if (!(value instanceof Map<?, ?> map)) {
                return ALL;
            }
            return new SkillScope(strings(map.get("groups")), strings(map.get("exclude-groups")),
                    strings(map.get("exclude")));
        }
    }

    static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }
}
