package jobapply.harness.runtime;

import java.util.List;
import jobapply.domain.AgentRun;

/**
 * 循环跑的时候告诉调用方现在在做什么（C006-agent：界面上的「正在做什么」和按步推送）。对话 Agent 和申请 Agent 共用。
 * 这里不能抛异常：观察的一方出错不影响循环。
 */
public interface LoopObserver {

    LoopObserver NONE = new LoopObserver() {
    };

    /** 马上要调模型。 */
    default void thinking() {
    }

    /** 模型回了一条（文字、思考、工具调用都可能有）。 */
    default void replied(AgentRun.Turn assistant) {
    }

    /** 马上要执行这个工具。 */
    default void tool(AgentRun.ToolCall call) {
    }

    /** 用户插的话加进了对话（C010）。 */
    default void heard(List<AgentRun.Turn> said) {
    }

    /** 这个工具执行完了（结果带状态和用时）。 */
    default void done(AgentRun.ToolCall call, AgentRun.ToolResult result) {
    }
}
