package jobapply.harness.plugins;

import java.util.ArrayList;
import java.util.List;
import jobapply.domain.AgentRun;
import jobapply.harness.runtime.Loop;
import jobapply.harness.runtime.LoopEvent;

/**
 * 这次运行之前的消息里读过的网页正文，发给模型时只留开头（需要时重新读），免得对话越来越长（Session）。
 * 存下的对话不变，只改发给模型的那份。
 */
public final class OldResultsPlugin implements Plugin {

    private final int maxChars;
    /** 这次运行开始时对话有多少条（最后一条是这次的用户消息）。 */
    private int firstNew;

    public OldResultsPlugin(int maxChars) {
        this.maxChars = maxChars;
    }

    @Override
    public void on(LoopEvent event, Loop loop) {
        if (event instanceof LoopEvent.Started) {
            firstNew = Math.max(0, loop.turns().size() - 1);
        }
    }

    @Override
    public void context(ContextView view) {
        List<AgentRun.Turn> turns = view.turns();
        List<AgentRun.Turn> out = new ArrayList<>(turns.size());
        for (int i = 0; i < turns.size(); i++) {
            AgentRun.Turn turn = turns.get(i);
            if (i >= firstNew || !AgentRun.Turn.TOOL.equals(turn.role())) {
                out.add(turn);
                continue;
            }
            List<AgentRun.ToolResult> results = turn.results().stream().map(result ->
                    result.text() != null && result.text().length() > maxChars
                            ? new AgentRun.ToolResult(result.id(), result.name(),
                            result.text().substring(0, maxChars) + "\n……（之前读过的内容已省略，需要时重新读）")
                            : result).toList();
            out.add(AgentRun.Turn.tool(results));
        }
        view.turns(out);
    }
}
