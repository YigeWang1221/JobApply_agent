package jobapply.harness.runtime;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import jobapply.domain.AgentRun;

/**
 * 调用方对一次运行的控制：停止、运行中插话（C010）、观察（界面的「正在做什么」和按步推送）、工具调用上限（C018）。
 *
 * @param stop          用户要求停止时返回 true（每次调用模型和工具之前检查）
 * @param inbox         取走运行中用户插的话；没有时返回空
 * @param observer      观察的一方
 * @param toolCallLimit 这一次运行最多执行多少次工具调用，到了让模型总结；0 或负数 = 不限。每次调模型之前都读一次
 */
public record RunControl(BooleanSupplier stop, Supplier<List<AgentRun.Turn>> inbox, LoopObserver observer,
                         IntSupplier toolCallLimit) {

    public RunControl {
        stop = stop == null ? () -> false : stop;
        inbox = inbox == null ? List::of : inbox;
        observer = observer == null ? LoopObserver.NONE : observer;
        toolCallLimit = toolCallLimit == null ? () -> 0 : toolCallLimit;
    }
}
