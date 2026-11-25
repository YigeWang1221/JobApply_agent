package jobapply.harness.plugins;

import java.util.function.Consumer;
import jobapply.config.HarnessProperties;
import jobapply.domain.AgentRun;
import jobapply.harness.runtime.Loop;
import jobapply.harness.runtime.LoopEvent;
import jobapply.persistence.RunLog;
import jobapply.tools.BrowserFillTools;
import jobapply.tools.ToolOutcome;

/**
 * 打转提醒（agent-runtime.md 7.1「不强制转交」）：浏览器操作在重复时（同一元素连续失败、相同操作相同结果、页面一直不变），
 * 只在 {@code toolAfter} 里给工具结果末尾追加一句「这是第 N 次」，由模型换办法或问用户；不拦、不停。
 */
public final class LoopReminderPlugin implements Plugin {

    private final LoopDetector detector;
    private final Consumer<RunLog.Entry> log;

    /** @param log 提醒时记进过程日志；为 null 时不记 */
    public LoopReminderPlugin(HarnessProperties limits, Consumer<RunLog.Entry> log) {
        this.detector = new LoopDetector(limits == null ? HarnessProperties.defaults() : limits);
        this.log = log == null ? ignored -> {
        } : log;
    }

    @Override
    public ToolOutcome toolAfter(AgentRun.ToolCall call, ToolOutcome outcome, Loop loop) {
        ToolOutcome.BrowserAction action = outcome.action();
        if (action == null) {
            return outcome;
        }
        if (action.baseline() != null) {
            detector.startAt(action.baseline());
        }
        if (action.tool() == null) {
            return outcome;
        }
        return detector.record(action).map(reminder -> {
            if (action.repeated() != null) {
                log.accept(action.repeated());
            }
            return outcome.withText(outcome.result().text() + "\n" + reminder);
        }).orElse(outcome);
    }

    @Override
    public void on(LoopEvent event, Loop loop) {
        if (event instanceof LoopEvent.PageShown shown) {
            detector.startAt(BrowserFillTools.pageText(shown.page()));
        }
    }
}
