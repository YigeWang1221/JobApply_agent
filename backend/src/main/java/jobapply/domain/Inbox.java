package jobapply.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行中插话（C010）：Agent 在跑时用户发来的消息先放在这里，循环在下一次调模型之前全部取走、作为用户消息加进对话；
 * 运行结束时还没取走的，由调用方写进对话并开始下一次运行（不丢）。参考 OpenMAIC 的 {@code agent.steer} 和 Hermes 的
 * {@code _pending_steer}：不打断正在执行的工具。Session 和申请线程各用一个，按 Session / Job 编号分开。
 */
public final class Inbox {

    private final Map<String, List<AgentRun.Turn>> waiting = new HashMap<>();

    public synchronized void put(String id, AgentRun.Turn turn) {
        waiting.computeIfAbsent(id, key -> new ArrayList<>()).add(turn);
    }

    /** 取走全部（按到达的顺序）；没有时为空。 */
    public synchronized List<AgentRun.Turn> take(String id) {
        List<AgentRun.Turn> taken = waiting.remove(id);
        return taken == null ? List.of() : List.copyOf(taken);
    }

    /** 还没被取走的（界面上显示「已插入，Agent 下一步会看到」）。 */
    public synchronized List<AgentRun.Turn> peek(String id) {
        return List.copyOf(waiting.getOrDefault(id, List.of()));
    }
}
