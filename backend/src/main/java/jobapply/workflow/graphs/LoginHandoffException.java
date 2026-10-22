package jobapply.workflow.graphs;

/**
 * 登录接力拒绝了一次请求。拒绝时没有写任何东西，也没有改 Checkpoint。
 */
public abstract class LoginHandoffException extends RuntimeException {

    private LoginHandoffException(String message) {
        super(message);
    }

    /** Job、Session 或登录接力线程不存在。 */
    public static final class NotFound extends LoginHandoffException {

        public NotFound(String message) {
            super(message);
        }
    }

    /** 请求和当前状态不符：Job 的 prep_state 不对、已有一条线程在跑、信号和当前等待的内容不符等。 */
    public static final class Conflict extends LoginHandoffException {

        public Conflict(String message) {
            super(message);
        }
    }
}
