package jobapply.harness.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import jobapply.tools.BrowserFillTools;
import jobapply.tools.ControlTools;
import jobapply.tools.DelegateTools;
import jobapply.tools.ExperienceTools;
import jobapply.tools.FollowupTools;
import jobapply.tools.ReflectTools;
import jobapply.tools.ResearchTools;
import jobapply.tools.RunContext;
import jobapply.tools.Tool;
import jobapply.tools.WorkbenchTools;

/**
 * 统一的工具登记（C030 Slice 2）：所有工具集按名字登记在这一处，一个工具集在一次运行开始时按 {@link RunContext}
 * 建出它的工具（定义 + 执行，拿到这次运行的上下文）。Mode 写它要哪些工具集（{@code tools:}），这里按 Mode 过滤、
 * 拼成这次运行的 {@link ToolBox}。不再有各循环里按工具名 {@code switch} 分派和只有定义的空壳。
 */
public final class ToolRegistry {

    private static final Map<String, Function<RunContext, List<Tool>>> TOOLSETS = new LinkedHashMap<>();

    static {
        // 申请（投递段）：浏览器填写（硬提交守卫、{KEY} 替换、快照遮值都在里面）、控制工具、工作台工具
        TOOLSETS.put("browser-fill", BrowserFillTools::create);
        TOOLSETS.put("apply-control", ControlTools::create);
        TOOLSETS.put("workbench-fill", WorkbenchTools::fill);
        // Session：调研、候选池、开始申请 + 只读浏览器
        TOOLSETS.put("research", ResearchTools::create);
        // 后续段：记状态、轮次、复盘、查状态 + 只读浏览器；工作台两段共用的
        TOOLSETS.put("followup", FollowupTools::create);
        // 查状态（portal-check Subagent）记官网结果
        TOOLSETS.put("portal-check", FollowupTools::portalCheck);
        TOOLSETS.put("workbench-followup", WorkbenchTools::followup);
        // 跨工作台经验检索（Subagent 的 Mode 单独要它时）
        TOOLSETS.put("experience", ctx -> List.of(ExperienceTools.create(ctx)));
        // 投递后复盘（reflect Subagent）：读脱敏后的填写记录、写经验
        TOOLSETS.put("reflect", ReflectTools::create);
        // 交给子 Mode（C030 Slice 5）：Mode 的 subagents: 写能交给哪些
        TOOLSETS.put("delegate", DelegateTools::create);
    }

    private ToolRegistry() {
    }

    /** 登记过的工具集名字。 */
    public static List<String> toolsets() {
        return List.copyOf(TOOLSETS.keySet());
    }

    /**
     * 这次运行能用的工具：{@code toolsets} 按顺序建出来；{@code allow} 不为空时只留这些名字（同名的取第一个工具集里的）。
     * 没有登记的名字抛出（Mode 写错了）。
     */
    public static ToolBox toolsFor(List<String> toolsets, List<String> allow, RunContext ctx) {
        List<Tool> tools = new ArrayList<>();
        for (String name : toolsets) {
            Function<RunContext, List<Tool>> create = TOOLSETS.get(name);
            if (create == null) {
                throw new IllegalArgumentException("没有这个工具集：" + name + "（有 " + TOOLSETS.keySet() + "）");
            }
            for (Tool tool : create.apply(ctx)) {
                if (allow.isEmpty() || allow.contains(tool.name())
                        && tools.stream().noneMatch(kept -> kept.name().equals(tool.name()))) {
                    tools.add(tool);
                }
            }
        }
        return new ToolBox(tools);
    }
}
