package jobapply.harness.plugins;

import java.util.List;
import jobapply.domain.AgentRun;
import jobapply.harness.runtime.Loop;
import jobapply.harness.runtime.LoopEvent;
import jobapply.tools.Tool;
import jobapply.tools.ToolOutcome;
import org.springframework.ai.chat.model.ChatResponse;

/** 按 Mode 里写的顺序依次调插件的同一个钩子。 */
public final class PluginChain implements Plugin {

    private final List<Plugin> plugins;

    public PluginChain(List<Plugin> plugins) {
        this.plugins = List.copyOf(plugins);
    }

    public List<Plugin> plugins() {
        return plugins;
    }

    /** 某一种插件（例如调用方要读申请状态插件结束时的运行记录）；没有时为 null。 */
    public <T extends Plugin> T find(Class<T> type) {
        return plugins.stream().filter(type::isInstance).map(type::cast).findFirst().orElse(null);
    }

    @Override
    public String prompt(String system) {
        String out = system;
        for (Plugin plugin : plugins) {
            out = plugin.prompt(out);
        }
        return out;
    }

    @Override
    public List<Tool> tools(List<Tool> tools) {
        List<Tool> out = tools;
        for (Plugin plugin : plugins) {
            out = plugin.tools(out);
        }
        return out;
    }

    @Override
    public void context(ContextView view) {
        plugins.forEach(plugin -> plugin.context(view));
    }

    @Override
    public void response(ChatResponse response) {
        plugins.forEach(plugin -> plugin.response(response));
    }

    @Override
    public ToolOutcome toolBefore(AgentRun.ToolCall call, Loop loop) {
        for (Plugin plugin : plugins) {
            ToolOutcome outcome = plugin.toolBefore(call, loop);
            if (outcome != null) {
                return outcome;
            }
        }
        return null;
    }

    @Override
    public ToolOutcome toolAfter(AgentRun.ToolCall call, ToolOutcome outcome, Loop loop) {
        ToolOutcome out = outcome;
        for (Plugin plugin : plugins) {
            out = plugin.toolAfter(call, out, loop);
        }
        return out;
    }

    @Override
    public void on(LoopEvent event, Loop loop) {
        plugins.forEach(plugin -> plugin.on(event, loop));
    }
}
