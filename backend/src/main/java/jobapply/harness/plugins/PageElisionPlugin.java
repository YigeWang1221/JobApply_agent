package jobapply.harness.plugins;

import jobapply.domain.AgentRun;
import jobapply.harness.context.Conversation;
import jobapply.harness.runtime.Loop;
import jobapply.harness.runtime.LoopEvent;
import jobapply.tools.ToolOutcome;

/**
 * 页面快照省略（agent-runtime.md 7.3 第 6 节）：只有最新的一份页面原样发给模型，更早的压成一行；最新一份只是点开的
 * 弹出层时（C016 F），它之前那份整页快照也照原样留着。页面来自工具结果，或者系统自己取的（{@link Loop#showPage}）；
 * 浏览器断开后不再有最新的页面。
 */
public final class PageElisionPlugin implements Plugin {

    private Conversation.LatestPage latest;

    @Override
    public ToolOutcome toolAfter(AgentRun.ToolCall call, ToolOutcome outcome, Loop loop) {
        if (outcome.pageGone()) {
            latest = null;
        }
        if (outcome.page() != null) {
            // 这一轮的结果接下来放在第 turns().size() 条
            latest = Conversation.LatestPage.next(latest, outcome.page(), loop.turns().size(), call.id());
        }
        return outcome;
    }

    @Override
    public void on(LoopEvent event, Loop loop) {
        if (event instanceof LoopEvent.PageShown shown) {
            latest = new Conversation.LatestPage(shown.page(), shown.turn(), null);
        }
    }

    @Override
    public void context(ContextView view) {
        view.latest(latest);
    }
}
