package jobapply.harness.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jobapply.domain.IllegalTransitionException;
import jobapply.harness.policies.ToolNotAllowedException;
import jobapply.tools.Tool;
import jobapply.tools.ToolInput;
import jobapply.tools.ToolOutcome;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 一次运行能用的工具（{@link ToolRegistry} 按 Mode 建出来）：发给模型的定义，和按名字执行。参数不对、被拒绝这类
 * 异常在这里统一变成结果交回模型（「失败：」/「被拒绝：」），不结束运行。
 */
public final class ToolBox {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolBox(List<Tool> list) {
        for (Tool tool : list) {
            if (tools.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalStateException("同一个 Mode 里有两个工具叫 " + tool.name());
            }
        }
    }

    public List<Tool> tools() {
        return List.copyOf(tools.values());
    }

    public boolean has(String name) {
        return tools.containsKey(name);
    }

    /**
     * 发给模型的工具定义。Spring AI 的请求选项要 {@link ToolCallback}；工具由循环自己执行（模型的工具调用原样留在回复里），
     * 所以这里的 {@code call()} 被用到说明调用方式错了。
     */
    public List<ToolCallback> callbacks() {
        return tools.values().stream().filter(Tool::advertised)
                .<ToolCallback>map(tool -> new Advertised(tool.definition())).toList();
    }

    /** 执行一次调用。 */
    public ToolOutcome run(ToolInput input) {
        Tool tool = tools.get(input.name());
        if (tool == null) {
            return ToolOutcome.failed(input.call(), "失败：没有这个工具：" + input.name() + "。");
        }
        try {
            return tool.run(input);
        } catch (ToolNotAllowedException e) {
            return ToolOutcome.rejected(input.call(), "被拒绝：" + e.getMessage());
        } catch (IllegalArgumentException | IllegalTransitionException e) {
            return ToolOutcome.failed(input.call(), "失败：" + e.getMessage());
        }
    }

    private record Advertised(ToolDefinition definition) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            throw new IllegalStateException("工具 " + definition.name() + " 由 Agent 循环执行，不应被 SDK 调用");
        }
    }
}
