package jobapply.harness.runtime;

/**
 * 工作台里记下或撤销「投递失败 / 不投了」时，往这个 Job 的 Session 写一条系统通知（C024）。由
 * {@code ApplicationRunner#noteToSession} 实现：记进 Session 的对话，不唤醒模型。
 */
@FunctionalInterface
public interface SessionNote {

    SessionNote NONE = (project, session, job, text) -> {
    };

    void note(String projectId, String sessionId, String jobId, String text);
}
