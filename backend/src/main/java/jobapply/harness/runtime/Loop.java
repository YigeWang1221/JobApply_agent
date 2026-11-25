package jobapply.harness.runtime;

import java.util.List;
import jobapply.domain.AgentRun;
import jobapply.tools.Ending;
import jobapply.tools.PageSnapshot;

/**
 * 正在跑的循环，交给插件和工具的那一面：看对话、往对话里加一条、告诉循环系统自己取了一份页面、开始前就结束。
 */
public interface Loop {

    /** 到现在的对话（只读）。 */
    List<AgentRun.Turn> turns();

    /** 加一条对话（每一条新对话都交给调用方，例如写进 transcript）。 */
    void add(AgentRun.Turn turn);

    /** 模型回的一条：记下，再告诉观察的一方（界面的按步推送）。 */
    void reply(AgentRun.Turn turn);

    /** 系统自己取的页面：它是最新的一条对话（{@code page} 条）。 */
    void showPage(PageSnapshot page);

    /** 这一次运行已经执行的工具调用次数。 */
    int calls();

    /** 循环开始时（{@link LoopEvent.Started}）就结束这次运行，不调模型。 */
    void end(Ending ending);
}
