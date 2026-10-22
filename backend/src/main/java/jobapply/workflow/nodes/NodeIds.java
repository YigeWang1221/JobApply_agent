package jobapply.workflow.nodes;

/**
 * 登录接力图的节点名。等待节点（{@code wait_*}、{@code done}）由图在执行前中断，靠用户的信号恢复。
 */
public final class NodeIds {

    public static final String OPEN_PAGE = "open_page";
    public static final String WAIT_LOGIN = "wait_login";
    public static final String CHECK_PAGE = "check_page";
    public static final String WAIT_IDENTITY = "wait_identity";
    public static final String WAIT_RECONNECT = "wait_reconnect";
    public static final String RECONNECT = "reconnect";
    public static final String FINISH = "finish";
    /** 线程停在这里表示已就绪；第 3 段的填写从这里接着做。 */
    public static final String DONE = "done";

    private NodeIds() {
    }
}
