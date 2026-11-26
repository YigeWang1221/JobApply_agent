package jobapply.harness.runtime;

import jobapply.tools.Ending;

/** 一次运行怎么结束的。 */
public sealed interface LoopEnd {

    /** 模型只回了文字（{@code text} 是原文，可能为 null）：当成对用户说的话，等用户回复。 */
    record Answered(String text, String thinking) implements LoopEnd {
    }

    /** 用户要求停止。 */
    record Stopped() implements LoopEnd {
    }

    /** 工具（或开始时的系统动作）结束了这次运行。 */
    record Ended(Ending ending) implements LoopEnd {
    }

    /** 到了工具调用上限，模型的总结（已经去掉首尾空白，没有时是空字符串）。 */
    record Summarized(String text, String thinking) implements LoopEnd {
    }
}
