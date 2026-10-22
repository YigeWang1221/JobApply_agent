package jobapply.workflow.state;

/**
 * 登录接力线程对外的状态（HTTP 接口原样返回）。
 *
 * @param status     {@code running | waiting | ready | failed}
 * @param waitingFor {@code login | identity | reconnect}，不在等待时为 null
 */
public record LoginHandoffStatus(
        String job,
        String status,
        String waitingFor,
        String reason,
        String detected,
        String pageUrl,
        String pageTitle,
        String identity,
        String lastError) {

    public static final String RUNNING = "running";
    public static final String WAITING = "waiting";
    public static final String READY = "ready";
    public static final String FAILED = "failed";
}
