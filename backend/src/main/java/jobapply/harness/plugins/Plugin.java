package jobapply.harness.plugins;

import java.util.List;
import jobapply.domain.AgentRun;
import jobapply.harness.runtime.Loop;
import jobapply.harness.runtime.LoopEvent;
import jobapply.tools.Tool;
import jobapply.tools.ToolOutcome;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * 挂在 Base Agent 循环上的插件（C030 PluginChain；钩子对齐 opencode / pi 的生命周期）。全部钩子都有默认实现，
 * 插件只覆盖它用得到的。插件由 Mode 的 {@code plugins:} 组合，每次运行建一份。
 */
public interface Plugin {

    /** 系统提示词（Mode 的正文）：插件可以补一段。 */
    default String prompt(String system) {
        return system;
    }

    /** 这次运行交给模型的工具：插件可以再过滤。 */
    default List<Tool> tools(List<Tool> tools) {
        return tools;
    }

    /** 每次调模型之前拼消息：上下文、对话视图、最新页面、接在最后的那段。 */
    default void context(ContextView view) {
    }

    /** 模型回了（每次调模型之后，包括上限时的那次总结）。 */
    default void response(ChatResponse response) {
    }

    /** 执行工具之前：返回不为 null 时不执行，直接用这个结果。 */
    default ToolOutcome toolBefore(AgentRun.ToolCall call, Loop loop) {
        return null;
    }

    /** 执行工具之后、结果交给界面和对话之前：可以追加提醒、记下结果。 */
    default ToolOutcome toolAfter(AgentRun.ToolCall call, ToolOutcome outcome, Loop loop) {
        return outcome;
    }

    /** 循环里发生的事（开始、系统取了页面、插话、一轮结束、到了上限、结束）。 */
    default void on(LoopEvent event, Loop loop) {
    }
}
