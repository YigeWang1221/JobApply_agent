package jobapply.domain;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 这个进程里正在跑的运行（C006-fix-2 第 3 条）。「在跑」只看这里，不看文件：记录里写着在跑、这里却没有的，
 * 是应用重启前被中断的，读的时候推出来，不另存一份。Session、申请线程、资料导入共用这一个判断，
 * 各自决定中断以后怎么显示（参考 OpenMAIC {@code interruptActiveChatSessions}、deepagents {@code _THREAD_TO_TASK_STATUS}）。
 */
public final class LiveRuns {

    private final Set<String> ids = ConcurrentHashMap.newKeySet();

    public void add(String id) {
        ids.add(id);
    }

    public void remove(String id) {
        ids.remove(id);
    }

    public boolean contains(String id) {
        return ids.contains(id);
    }

    public java.util.List<String> ids() {
        return java.util.List.copyOf(ids);
    }

    /** 记录写着在跑（{@code recordedRunning}），这个进程里却没有：中断了。 */
    public boolean interrupted(String id, boolean recordedRunning) {
        return recordedRunning && !ids.contains(id);
    }
}
