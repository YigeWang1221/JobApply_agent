package jobapply.tools;

import java.util.Map;
import java.util.function.Function;
import jobapply.harness.policies.BrowserToolPolicy;

/**
 * 全局唯一的浏览器连接持有者（浏览器只有一个）。没有连接时，第一次调用会建立连接，用户需要在 Chrome 里同意一次。
 * 连接断开后丢弃这条连接，下一次调用或 {@link #reconnect()} 时重新建立。快照里的 ref 不跨连接使用。
 * 重连之后的「当前标签页」可能是用户自己的标签页，所以重连后要先 {@link #open(String, String)}，再取快照。
 */
public final class BrowserSession implements AutoCloseable {

    private final Function<BrowserLaunch, PlaywrightMcpClient> connector;
    private final BrowserLaunch launch;
    private PlaywrightMcpClient client;
    /** 当前连接上新开的标签页属于哪个 Job；还没有新开过时为 null。 */
    private String tabOwner;

    public BrowserSession(BrowserLaunch launch) {
        this(launch, PlaywrightMcpClient::start);
    }

    BrowserSession(BrowserLaunch launch, Function<BrowserLaunch, PlaywrightMcpClient> connector) {
        this.launch = launch;
        this.connector = connector;
    }

    /**
     * 新开一个标签页打开页面，再取一次快照。新标签页随即成为当前标签页，不动用户已经打开的标签页
     * （远程调试模式下，已打开的标签页也会被收进来）。
     */
    public synchronized PageSnapshot open(String url, String jobId) {
        tabOwner = null;
        call(BrowserToolPolicy.TABS, Map.of("action", "new", "url", url));
        tabOwner = jobId;
        return snapshot(jobId);
    }

    /**
     * 只读：取这个 Job 自己新开的那个标签页的快照。标签页不是它的（还没新开过，或属于另一个 Job）时拒绝，
     * 免得读到用户自己的标签页或别的岗位的页面。
     */
    public synchronized PageSnapshot snapshot(String jobId) {
        if (jobId == null || !jobId.equals(tabOwner)) {
            throw new IllegalStateException("当前标签页不是 Job " + jobId + " 新开的岗位页，不能取快照");
        }
        return PageSnapshot.parse(call(BrowserToolPolicy.SNAPSHOT, Map.of()));
    }

    /** 当前标签页是否是这个 Job 新开的（连接断开、重连、应用重启、另一个 Job 打开页面之后都是 false）。 */
    public synchronized boolean ownsTab(String jobId) {
        return jobId != null && jobId.equals(tabOwner);
    }

    /** 关掉旧连接，建立新连接（用户需要再同意一次）。 */
    public synchronized void reconnect() {
        disconnect();
        client = connector.apply(launch);
    }

    public synchronized boolean isConnected() {
        return client != null;
    }

    @Override
    public synchronized void close() {
        disconnect();
    }

    private String call(String tool, Map<String, Object> args) {
        if (client == null) {
            client = connector.apply(launch);
        }
        try {
            return client.call(tool, args);
        } catch (BrowserException.Disconnected e) {
            disconnect();
            throw e;
        }
    }

    private void disconnect() {
        tabOwner = null;
        if (client != null) {
            PlaywrightMcpClient old = client;
            client = null;
            old.close();
        }
    }
}
