package jobapply.harness.plugins;

import jobapply.domain.AgentRun;
import jobapply.harness.runtime.Loop;
import jobapply.harness.runtime.LoopEvent;

/**
 * Subagent 的 Mode 到了工具调用上限时：加一条系统说明（Mode 的 {@code limit-note}），循环接着让模型总结交回父运行。
 * 不像对话类 Mode 那样补「回复继续」：子运行不和用户说话。
 */
public final class LimitNotePlugin implements Plugin {

    private final String limitNote;

    public LimitNotePlugin(String limitNote) {
        this.limitNote = limitNote;
    }

    @Override
    public void on(LoopEvent event, Loop loop) {
        if (event instanceof LoopEvent.LimitReached limit) {
            loop.add(AgentRun.Turn.note(ConversationEndingPlugin.limitText(limitNote, limit.calls())));
        }
    }
}
