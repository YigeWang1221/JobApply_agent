package jobapply.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * <p>
 * 每个使用者（一个 Job 的申请线程、一个 Session 的调研）有自己新开的标签页，记下它在标签页列表里的序号和最近一次
 * 看到的网址（C005）。每次操作前先列一次标签页，核对记下的那一页还在（{@link #focus}）：用户关掉前面的标签页会让序号
 * 错位，这时按网址重新找到它；找不到就不操作（不会落到别的任务或用户自己的标签页上），这个使用者没有标签页了，
 * 下一次运行重新打开。
 */
public final class BrowserSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BrowserSession.class);

    private final Function<BrowserLaunch, PlaywrightMcpClient> connector;
    private final BrowserLaunch launch;
    private PlaywrightMcpClient client;
    /** {@code browser_tabs} 列表里的一行：{@code - 1: (current) [标题](网址)}。 */
    private static final Pattern TAB_LINE = Pattern.compile("(?m)^- (\\d+):( \\(current\\))? \\[.*\\]\\((.*)\\)(?: \\[crashed\\])?$");

    /** 一个使用者的标签页：序号和最近一次看到的网址。 */
    private record Tab(int index, String url) {
    }

    /** 列表里的一个标签页。 */
    private record Listed(int index, boolean current, String url) {
    }

    /** 使用者 → 它新开的标签页（当前连接上）。 */
    private final Map<String, Tab> tabs = new HashMap<>();
    /** 上一次列出来的标签页个数。 */
    private int tabCount;
    /** 当前标签页属于哪个使用者；不知道时为 null。 */
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
        tabs.remove(jobId);
        List<Listed> listed = parseTabs(call(BrowserToolPolicy.TABS, Map.of("action", "new", "url", url)));
        if (listed.stream().noneMatch(Listed::current)) {
            listed = parseTabs(call(BrowserToolPolicy.TABS, Map.of("action", "list")));
        }
        tabCount = listed.size();
        listed.stream().filter(Listed::current).findFirst()
                .ifPresent(tab -> tabs.put(jobId, new Tab(tab.index(), tab.url())));
        tabOwner = jobId;
        return read(jobId);
    }

    /**
     * 只读：取这个 Job 自己新开的那个标签页的快照。标签页不是它的（还没新开过，或属于另一个 Job）时拒绝，
     * 免得读到用户自己的标签页或别的岗位的页面。
     */
    public synchronized PageSnapshot snapshot(String jobId) {
        if (!ownsTab(jobId)) {
            throw new IllegalStateException("当前标签页不是 Job " + jobId + " 新开的岗位页，不能取快照");
        }
        focus(jobId);
        return read(jobId);
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
            return remember(jobId, PageSnapshot.parse(result));
        }
        return read(jobId);
    }

    /**
     * 模型的 {@code browser_find}：只读，返回 MCP 的原文（找到的元素）。同样先确认标签页属于这个 Job。
     */
    public synchronized String find(String jobId, Map<String, Object> args, BrowserToolPolicy.Context context) {
        requireOwnTab(jobId);
        return call(BrowserToolPolicy.FIND, args, context);
    }

    /**
     * 这个使用者在当前连接上有没有自己新开的标签页（连接断开、重连、应用重启之后都是 false）。
     * 有、但不是当前标签页时，下一次调用会先切过去。
     */
    public synchronized boolean ownsTab(String jobId) {
        return jobId != null && tabs.containsKey(jobId);
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
        focus(jobId);
    }

    /**
     * 确认这个使用者的标签页还在，并让它成为当前标签页。先列出标签页再认：
     * <ol>
     *   <li>记下的序号上还是记下的网址：就是它；</li>
     *   <li>否则网址只在一个标签页上出现：序号错位了（用户关掉了前面的标签页），改用那一个；</li>
     *   <li>否则标签页没有变少、序号还在：页面自己换了网址（跳转、单页应用改路由），还是它；</li>
     *   <li>都不是：找不到了。这个使用者不再有标签页，抛出 {@link IllegalStateException}，不在别的标签页上操作。</li>
     * </ol>
     */
    private void focus(String owner) {
        Tab tab = tabs.get(owner);
        List<Listed> listed = parseTabs(call(BrowserToolPolicy.TABS, Map.of("action", "list")));
        int previousCount = tabCount;
        tabCount = listed.size();
        Listed found = listed.stream().filter(t -> t.index() == tab.index() && t.url().equals(tab.url()))
                .findFirst().orElse(null);
        if (found == null) {
            List<Listed> sameUrl = listed.stream().filter(t -> t.url().equals(tab.url())).toList();
            if (sameUrl.size() == 1) {
                found = sameUrl.get(0);
            } else if (listed.size() >= previousCount) {
                found = listed.stream().filter(t -> t.index() == tab.index()).findFirst().orElse(null);
            }
        }
        if (found == null) {
            tabs.remove(owner);
            if (owner.equals(tabOwner)) {
                tabOwner = null;
            }
            throw new IllegalStateException("找不到 " + owner + " 的标签页（可能在 Chrome 里被关掉了），没有在别的标签页上操作；"
                    + "回复后会重新打开");
        }
        tabs.put(owner, new Tab(found.index(), found.url()));
        if (!found.current()) {
            tabOwner = null;
            call(BrowserToolPolicy.TABS, Map.of("action", "select", "index", found.index()));
        }
        tabOwner = owner;
    }

    /** 取当前标签页（已经确认是 {@code owner} 的）的快照，记下它的网址。 */
    private PageSnapshot read(String owner) {
        return remember(owner, PageSnapshot.parse(call(BrowserToolPolicy.SNAPSHOT, Map.of())));
    }

    private PageSnapshot remember(String owner, PageSnapshot page) {
        Tab tab = tabs.get(owner);
        if (tab != null && page.url() != null && !page.url().isBlank()) {
            tabs.put(owner, new Tab(tab.index(), page.url()));
        }
        return page;
    }

    private static List<Listed> parseTabs(String text) {
        List<Listed> listed = new ArrayList<>();
        Matcher m = TAB_LINE.matcher(text == null ? "" : text);
        while (m.find()) {
            listed.add(new Listed(Integer.parseInt(m.group(1)), m.group(2) != null, m.group(3)));
        }
        return listed;
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

    /**
     * 建立连接。失败时和断开一样记为要等用户（{@link #needsUser}）：之后的调用不再自己去连，免得 Chrome 反复弹出确认。
     */
    private void connect() {
        try {
            clearOutputDir();
            for (Path root : launch.roots()) {
                Files.createDirectories(root);
            }
        } catch (IOException | UncheckedIOException e) {
            throw new IllegalStateException("准备浏览器 MCP 的目录失败：" + e.getMessage(), e);
        }
        try {
            client = connector.apply(launch);
        } catch (RuntimeException e) {
            disconnect();
            needsUser = true;
            throw e instanceof BrowserException.Disconnected disconnected ? disconnected
                    : new BrowserException.Disconnected("连接浏览器失败：" + e.getMessage(), e);
        }
    }

    private void disconnect() {
        tabOwner = null;
        tabs.clear();
        tabCount = 0;
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
