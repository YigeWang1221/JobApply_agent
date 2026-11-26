package jobapply.tools;

import java.util.List;

/**
 * 这次运行能交给哪些子 Mode、怎么交（C030 Slice 5）：由 Base Agent 按 Mode 的 {@code subagents:} 放进
 * {@link RunContext}，{@code delegate} 工具（{@link DelegateTools}）调用它。实现是 {@code SubagentExecutor}：
 * 子运行跑完才返回，只交回精简结果，子运行的对话不进这次运行。
 */
public interface Subagents {

    /** 一个能交给的子 Mode。 */
    record Choice(String name, String description) {
    }

    List<Choice> choices();

    /**
     * 跑一次子 Mode，返回交给模型的精简结果。
     *
     * @param job 子任务针对的 Job；为 null 时用这次运行的 Job（Session 里没有）
     * @throws IllegalArgumentException 不能交给这个 Mode、已经到了最深一层、缺 Job（交回模型）
     */
    String delegate(String mode, String task, String job);
}
