package jobapply.harness.runtime;

import java.util.List;
import jobapply.domain.AgentRun;
import jobapply.tools.PageSnapshot;

/** 循环里发生的事，插件在 {@code on(event)} 里听（C030 PluginChain）。 */
public sealed interface LoopEvent {

    /** 第一次调模型之前（可以加对话、取页面，或者用 {@link Loop#end} 直接结束）。 */
    record Started() implements LoopEvent {
    }

    /** 系统自己取了一份页面，放在第 {@code turn} 条对话（{@link Loop#showPage}）。 */
    record PageShown(PageSnapshot page, int turn) implements LoopEvent {
    }

    /** 用户插的话加进了对话（C010，在下一次调模型之前）。 */
    record Heard(List<AgentRun.Turn> said) implements LoopEvent {
    }

    /** 一轮的工具都执行完、结果放进对话之后。 */
    record RoundDone() implements LoopEvent {
    }

    /** 到了工具调用上限：插件在这里加那条系统说明，循环接着让模型总结。 */
    record LimitReached(int calls) implements LoopEvent {
    }

    /** 这次运行结束了（插件按结束原因写对话、写状态）。 */
    record Finished(LoopEnd end) implements LoopEvent {
    }
}
