package jobapply.workflow;

/**
 * 申请线程拒绝了一次请求。拒绝时没有写任何东西，也没有改运行状态。
 */
public abstract class ApplicationException extends RuntimeException {

    private ApplicationException(String message) {
        super(message);
    }

    /** Job、Session 或申请线程不存在。 */
    public static final class NotFound extends ApplicationException {

        public NotFound(String message) {
            super(message);
        }
    }

    /** 请求和当前状态不符：Job 的 prep_state 不对、已有一条线程在跑、信号和当前等待的内容不符等。 */
    public static final class Conflict extends ApplicationException {

        public Conflict(String message) {
            super(message);
        }
    }

    /** 信号带的内容不对：答案回答了 C 级问题、问题编号不存在、文件和上传位置对不上等。 */
    public static final class BadRequest extends ApplicationException {

        public BadRequest(String message) {
            super(message);
        }
    }
}
