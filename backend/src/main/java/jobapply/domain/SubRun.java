package jobapply.domain;

import java.util.List;

/**
 * 一次 Subagent 运行（C030 Slice 5）：父 Mode 用 {@code delegate} 交给子 Mode 的一件事，或者系统直接起的子任务（批量查状态）。
 * 单向树：{@code parentRunId} 是父运行（Session id、Job id 或上一层的 {@code id}），{@code depth} 是在树里的层（Session 是 0，
 * Job 线程 1，再往下每层 +1）。对话、步骤和结果只存在 {@code projects/<p>/subruns/<id>.json}，不进父运行的对话；
 * 父运行只拿到 {@link #result} 这一小段。
 *
 * @param session 台账事件、入池记在哪个 Session 下（A2 的根 Session）；可以为 null
 * @param job     子任务针对的 Job；没有时为 null
 * @param task    父运行交代的事（第一条用户消息）
 * @param status  {@link #RUNNING} / {@link #DONE} / {@link #STOPPED} / {@link #FAILED}
 * @param result  交回父运行的精简结果；还在跑时为 null
 * @param turns   子运行的对话（事件里不带）
 */
public record SubRun(String id, String mode, int depth, String parentRunId, String project, String session, String job,
                     String task, String status, String startedAt, String endedAt, String result,
                     List<AgentRun.Turn> turns) {

    public static final String RUNNING = "running";
    public static final String DONE = "done";
    public static final String STOPPED = "stopped";
    public static final String FAILED = "failed";

    public SubRun {
        turns = turns == null ? List.of() : List.copyOf(turns);
    }

    public SubRun withTurns(List<AgentRun.Turn> turns) {
        return new SubRun(id, mode, depth, parentRunId, project, session, job, task, status, startedAt, endedAt, result,
                turns);
    }

    public SubRun ended(String status, String result, String endedAt, List<AgentRun.Turn> turns) {
        return new SubRun(id, mode, depth, parentRunId, project, session, job, task, status, startedAt, endedAt, result,
                turns);
    }

    /** 事件里用的那一份：不带对话。 */
    public SubRun header() {
        return withTurns(List.of());
    }

    /** Subagent 开始、结束时通知（事件总线的 {@code subrun}）。不能抛异常。 */
    @FunctionalInterface
    public interface Listener {

        Listener NONE = run -> {
        };

        void changed(SubRun run);
    }
}
