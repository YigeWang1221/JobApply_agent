package jobapply.workflow.state;

import java.util.Arrays;

/**
 * 用户发给登录接力线程的信号，以及各自只能在哪个等待点使用。
 */
public enum LoginHandoffSignal {
    /** 已登录（或确认已经登录）。可以带 override。 */
    LOGGED_IN("logged_in", "login"),
    IDENTITY_CONFIRMED("identity_confirmed", "identity"),
    WRONG_ACCOUNT("wrong_account", "identity"),
    /** 已在 Chrome 里准备好扩展，可以重新连接。 */
    RECONNECTED("reconnected", "reconnect");

    private final String value;
    private final String waitingFor;

    LoginHandoffSignal(String value, String waitingFor) {
        this.value = value;
        this.waitingFor = waitingFor;
    }

    public String value() {
        return value;
    }

    /** 这个信号能回应的等待（{@link LoginHandoffStatus#waitingFor()}）。 */
    public String waitingFor() {
        return waitingFor;
    }

    public static LoginHandoffSignal of(String value) {
        return Arrays.stream(values())
                .filter(signal -> signal.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知的信号：" + value
                        + "（可用：logged_in、identity_confirmed、wrong_account、reconnected）"));
    }
}
