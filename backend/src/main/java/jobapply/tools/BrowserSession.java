package jobapply.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import jobapply.harness.policies.BrowserToolPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 全局唯一的浏览器连接持有者（浏览器只有一个）。连接属于应用，不属于某个 Session、Project 或 Job：应用运行期间一直
 * 挂着，不会自己关掉再重新申请（用户确认，2026-10-23）。只在两种时候建立连接（用户要在 Chrome 里同意一次）：
 * 应用启动后第一次使用浏览器；用户发了 {@code reconnected}（{@link #reconnect()}）。
 * 连接失败或断开（例如用户在 Chrome 里关掉了）以后<b>不自动重试</b>：之后的调用直接报
 * {@link BrowserException.Disconnected}，不再碰 MCP（Playwright MCP 每次重试都会让 Chrome 再弹一次确认），直到用户发
 * {@code reconnected}。快照里的 ref 不跨连接使用。
 * 重连之后的「当前标签页」可能是用户自己的标签页，所以重连后要先 {@link #open(String, String)}，再取快照。
 * 建立连接和关闭时清空 MCP 的输出目录（{@link BrowserLaunch#outputDir()}），里面可能有登录后的页面内容。
 */
public final class BrowserSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BrowserSession.class);

    private final Function<BrowserLaunch, PlaywrightMcpClient> connector;
    private final BrowserLaunch launch;
    private PlaywrightMcpClient client;
    /** 当前连接上新开的标签页属于哪个 Job；还没有新开过时为 null。 */
    private String tabOwner;
    /** 连接失败或断开过，要等用户 {@link #reconnect()}；在此之前不建立新连接。 */
    private boolean needsUser;

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

    /**
     * 模型的一次浏览器操作：先确认当前标签页是这个 Job 新开的，再按 {@code context} 过白名单并发出，最后取一次快照返回
     * （写操作之后系统自己取快照，交给守卫和模型）。{@code args} 必须已经去掉模型多给的参数
     * （{@link jobapply.harness.policies.FieldIntent}、{@link jobapply.harness.policies.SubmitGuard.ClickIntent}）；
     * 点击和填写的守卫由调用方先判断。{@code browser_snapshot} 直接返回这次的快照，不再多取一次。
     * 连接断开时抛出 {@link BrowserException.Disconnected}，标签页不再属于任何 Job。
     */
    public synchronized PageSnapshot act(String jobId, String tool, Map<String, Object> args,
                                         BrowserToolPolicy.Context context) {
        requireOwnTab(jobId);
        String result = call(tool, args, context);
        if (BrowserToolPolicy.SNAPSHOT.equals(tool)) {
            return PageSnapshot.parse(result);
        }
        return snapshot(jobId);
    }

    /**
     * 模型的 {@code browser_find}：只读，返回 MCP 的原文（找到的元素）。同样先确认标签页属于这个 Job。
     */
    public synchronized String find(String jobId, Map<String, Object> args, BrowserToolPolicy.Context context) {
        requireOwnTab(jobId);
        return call(BrowserToolPolicy.FIND, args, context);
    }

    /** 当前标签页是否是这个 Job 新开的（连接断开、重连、应用重启、另一个 Job 打开页面之后都是 false）。 */
    public synchronized boolean ownsTab(String jobId) {
        return jobId != null && jobId.equals(tabOwner);
    }

    /**
     * 用户发了 {@code reconnected}。连接还好好的（例如另一个 Job 已经重连过）就沿用，不关掉、不重新申请；
     * 连接失败或断开过（或者还没建立过）才关掉旧进程、建立新连接（用户需要再同意一次）。
     */
    public synchronized void reconnect() {
        if (client != null && !needsUser) {
            return;
        }
        disconnect();
        needsUser = false;
        connect();
    }

    public synchronized boolean isConnected() {
        return client != null && !needsUser;
    }

    /** 连接失败或断开过，正在等用户发 {@code reconnected}。 */
    public synchronized boolean needsUser() {
        return needsUser;
    }

    @Override
    public synchronized void close() {
        disconnect();
    }

    private void requireOwnTab(String jobId) {
        if (!ownsTab(jobId)) {
            throw new IllegalStateException("当前标签页不是 Job " + jobId + " 新开的岗位页，不能操作");
        }
    }

    private String call(String tool, Map<String, Object> args) {
        return call(tool, args, BrowserToolPolicy.Context.system());
    }

    private String call(String tool, Map<String, Object> args, BrowserToolPolicy.Context context) {
        if (needsUser) {
            throw new BrowserException.Disconnected(tool + "：浏览器连接已断开，在 Chrome 里准备好后发送 reconnected");
        }
        if (client == null) {
            connect();
        }
        try {
            return client.call(tool, args, context);
        } catch (BrowserException.Disconnected e) {
            disconnect();
            needsUser = true;
            throw e;
        }
    }

    private void connect() {
        try {
            clearOutputDir();
            for (Path root : launch.roots()) {
                Files.createDirectories(root);
            }
        } catch (IOException | UncheckedIOException e) {
            throw new IllegalStateException("准备浏览器 MCP 的目录失败：" + e.getMessage(), e);
        }
        client = connector.apply(launch);
    }

    private void disconnect() {
        tabOwner = null;
        if (client != null) {
            PlaywrightMcpClient old = client;
            client = null;
            old.close();
        }
        try {
            clearOutputDir();
        } catch (IOException | UncheckedIOException e) {
            log.warn("清空浏览器 MCP 输出目录失败：{}", e.getMessage());
        }
    }

    /** 删掉输出目录里的所有内容（不跟随符号链接），保留目录本身。 */
    private void clearOutputDir() throws IOException {
        Path dir = launch.outputDir();
        if (dir == null) {
            return;
        }
        Files.createDirectories(dir);
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(dir)) {
                    Files.delete(path);
                }
            }
        }
    }
}
