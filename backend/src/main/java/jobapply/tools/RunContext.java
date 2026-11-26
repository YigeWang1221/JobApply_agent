package jobapply.tools;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import jobapply.domain.AgentRun;
import jobapply.harness.runtime.Loop;

/**
 * 一次运行的上下文（C030 Slice 2 的运行上下文注入）：这次运行属于哪个 Project / Session / Job、用哪个标签页使用者，
 * 共用的服务，以及同一次运行里工具和插件共用的状态（按类型放，例如申请 Mode 的 {@link Workbench}）。
 * 循环开始时把自己（{@link Loop}）放进来，工具需要在对话里补一条（系统打开页面）时用。
 *
 * @param job      申请 / 后续段的 Job；Session 时为 null
 * @param tabOwner 浏览器标签页的使用者（申请线程是 Job id，Session 是 Session id，后续段是 {@code followup:<job>}，Subagent 是 {@code sub:<subRunId>}）
 */
public record RunContext(AgentServices services, String project, String session, String job, String tabOwner,
                         Map<Class<?>, Object> slots) {

    public RunContext(AgentServices services, String project, String session, String job, String tabOwner) {
        this(services, project, session, job, tabOwner, new LinkedHashMap<>());
    }

    /** 这次运行里某个类型的共用状态，没有时用 {@code create} 建一个放进去。 */
    public synchronized <T> T slot(Class<T> type, Supplier<T> create) {
        return type.cast(slots.computeIfAbsent(type, ignored -> create.get()));
    }

    /** 这次运行里某个类型的共用状态；没有时抛出（Mode 和工具没配好）。 */
    public synchronized <T> T slot(Class<T> type) {
        Object value = slots.get(type);
        if (value == null) {
            throw new IllegalStateException("这次运行没有 " + type.getSimpleName());
        }
        return type.cast(value);
    }

    public synchronized <T> T put(Class<T> type, T value) {
        slots.put(type, value);
        return value;
    }

    public synchronized boolean has(Class<?> type) {
        return slots.containsKey(type);
    }

    /** 这次运行的循环（循环开始时放进来）。 */
    public Loop loop() {
        return slot(Loop.class);
    }

    /** 给对话补一条（例如系统打开页面时的说明），在这一轮的工具结果之前。 */
    public void add(AgentRun.Turn turn) {
        loop().add(turn);
    }

}
