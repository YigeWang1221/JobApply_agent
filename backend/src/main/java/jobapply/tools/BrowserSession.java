package jobapply.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import jobapply.harness.policies.BrowserToolPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 全局唯一的浏览器连接（浏览器只有一个）。连接属于应用，不属于某个 Session、Project 或 Job：应用运行期间一直挂着，
 * 不会自己关掉再重新申请（用户确认，2026-10-23）。
 * <p>
 * <b>共享的连接</b>（C011 方案 A）：一个 Playwright MCP 服务进程（{@link PlaywrightMcpClient.Server}，
 * {@code --port --shared-browser-context}），它到 Chrome 只有一条 CDP 连接；再加一个一直空闲的「保活」会话，让这条连接
 * 在没有使用者时也不断开，用户只在建立连接时点一次允许。连接状态只有这一份（{@link BrowserState}，C006-fix-browser
 * 第 1 节）：每次浏览器调用都先过 {@link #ensureConnected()}，幂等，同一时间只有一个调用在建立连接。用过的连接断开以后，
 * 下一次使用时自动重连一次；这一次也连不上就停在 {@code disconnected}，之后的调用直接报
 * {@link BrowserException.Disconnected}、不再碰 MCP，等用户点「重新连接」（{@link #reconnect()}）或者发下一条消息
 * （{@link #retryOnNextUse()}）。建立连接和关闭时清空 MCP 的输出目录。
 * <p>
 * <b>每个使用者一个浏览器会话</b>（{@link OwnerBrowser}）：使用者是一个 Job 的申请线程或者一个 Session 的调研。它有自己的
 * MCP 会话（自己的当前标签页）、自己的标签页记录、弹窗、说明和转交；<b>锁按使用者</b>，不同使用者的浏览器调用同时进行。
 * 一个使用者的会话坏了（连接本身还好）只重建它自己的；共享连接断了，所有使用者都得到 {@code Disconnected}、记录清空。
 * 给模型的标签页列表只列使用者自己的页（{@link #tabs}），{@link #select} 也只能切到自己的页；没有归属的页由用户在工作台
 * 上转交（{@link #unowned}、{@link #handOver}）。
 */
public final class BrowserSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BrowserSession.class);

    /** 认不出正在用的那一页时给模型的话。 */
    static final String LOST = "找不到你正在用的标签页（可能被关掉了）。可以用 browser_tabs list 看一下你还开着的页再 select；"
            + "也可以用 browser_navigate 重新打开要用的网址（系统会新开一个标签页）";

    /**
     * 一个标签页（序号、标题、网址）。给模型的列表（{@link #tabs}）里序号是这个使用者自己的编号；给界面的列表
     * （{@link #unowned}）里是浏览器里的序号。
     *
     * @param inUse 这个使用者现在正在用的那一页（界面的列表里总是 false）
     * @param yours 这个使用者新开的、点击弹出的或者用户转交给它的（界面的列表里总是 false）
     */
    public record TabInfo(int index, String title, String url, boolean inUse, boolean yours) {
    }

    /** 用户要转交的标签页已经不在、网址变了，或者别的使用者正在用：没有转交（接口 409）。 */
    public static final class HandoverRefused extends RuntimeException {

        HandoverRefused(String message) {
            super(message);
        }
    }

    /** 这个使用者还没有正在用的标签页，或者那一页认不出了：这次没有操作，说明交给模型。 */
    public static final class TabLost extends IllegalStateException {

        TabLost(String message) {
            super(message);
        }
    }

    /** 一次建立起来的共享连接：服务进程和保活会话。 */
    private record Connection(PlaywrightMcpClient.Server server, PlaywrightMcpClient keeper, int serial) {
    }

    private final BrowserLaunch launch;
    private final Object connectLock = new Object();
    private final Object keeperLock = new Object();
    private volatile Connection connection;
    /** 每次连上、断开都加一；使用者的记录属于哪一次（{@link OwnerBrowser#sync}）。 */
    private volatile int serial;
    private final Map<String, OwnerBrowser> owners = new ConcurrentHashMap<>();
    private final OwnerBrowser.Calls calls = new OwnerBrowser.Calls() {
        @Override
        public <T> T use(OwnerBrowser owner, Function<PlaywrightMcpClient, T> call) {
            return BrowserSession.this.use(owner, call);
        }

        @Override
        public Set<String> othersUrls(String owner) {
            return BrowserSession.this.othersUrls(owner);
        }
    };
    /** 连接状态（{@link BrowserState}）；读不拿锁。 */
    private volatile BrowserState state;
    /** {@code disconnected} 时还能不能自己再连一次。 */
    private volatile boolean retryLeft;
    private volatile Consumer<BrowserState> listener = ignored -> {
    };
    private final Clock clock;

    public BrowserSession(BrowserLaunch launch) {
        this.launch = launch;
        this.clock = Clock.systemUTC();
        this.state = new BrowserState(BrowserState.NOT_CONNECTED, null, clock.instant(), 0);
    }

    /** 新开一个标签页打开页面（在这个使用者自己的会话里），开焦点模拟，再取一次快照。它成为这个使用者正在用的那页。 */
    public PageSnapshot open(String url, String owner) {
        OwnerBrowser o = owner(owner);
        synchronized (o) {
            o.sync(serial);
            return o.open(url);
        }
    }

    /** 只读：取这个使用者正在用的那页的快照。还没有或者认不出时抛出 {@link TabLost}。 */
    public PageSnapshot snapshot(String owner) {
        OwnerBrowser o = owner(owner);
        synchronized (o) {
            o.sync(serial);
            return o.snapshot();
        }
    }

    /**
     * 模型的一次浏览器操作：在这个使用者自己的会话里（当前页就是它在用的那页）按 {@code context} 过白名单并发出，再取一次
     * 快照返回。{@code args} 必须已经去掉模型多给的参数；点击和填写的守卫由调用方先判断。{@code browser_snapshot} 直接返回
     * 这次的快照。点击打开了自己的弹窗时切过去，返回新标签页的快照（{@link #takeNotice} 说明一次）。点击、输入、选择动作
     * 超时时系统切一次前台、检查一次原因写进错误；只是被别的层盖住的点击直接点一次。正在用的那页认不出时：
     * {@code browser_navigate} 过了白名单就新开一个标签页打开它，别的操作抛出 {@link TabLost}。共享连接断开时抛出
     * {@link BrowserException.Disconnected}，记录清空。
     */
    public PageSnapshot act(String owner, String tool, Map<String, Object> args, BrowserToolPolicy.Context context) {
        OwnerBrowser o = owner(owner);
        synchronized (o) {
            o.sync(serial);
            boolean had = o.ownsTab();
            if (had) {
                try {
                    return o.act(tool, args, context);
                } catch (TabLost e) {
                    if (!BrowserToolPolicy.NAVIGATE.equals(tool)) {
                        throw e;
                    }
                }
            }
            if (BrowserToolPolicy.NAVIGATE.equals(tool) && args.get("url") instanceof String target) {
                BrowserToolPolicy.check(tool, args, context);
                return o.open(target);
            }
            throw new TabLost(had ? LOST : "还没有你在用的标签页，不能操作");
        }
    }

    /** 模型的 {@code browser_find}：只读，在这个使用者正在用的那页上，返回 MCP 的原文。 */
    public String find(String owner, Map<String, Object> args, BrowserToolPolicy.Context context) {
        OwnerBrowser o = owner(owner);
        synchronized (o) {
            o.sync(serial);
            return o.find(args, context);
        }
    }

    /** 上一次操作留给这个使用者的说明，取一次就清掉；没有时为 null。 */
    public String takeNotice(String owner) {
        OwnerBrowser o = owner(owner);
        synchronized (o) {
            o.sync(serial);
            return o.takeNotice();
        }
    }

    /** 这个使用者自己的标签页（新开的、点击弹出的、用户转交的），序号是它自己的编号，标出正在用的。只列，不切换。 */
    public List<TabInfo> tabs(String owner) {
        OwnerBrowser o = owner(owner);
        synchronized (o) {
            o.sync(serial);
            return o.tabs();
        }
    }

    /**
     * 切到这个使用者自己的第 {@code index} 页（{@link #tabs} 的编号），取快照，它成为正在用的那页。
     *
     * @throws IllegalArgumentException 没有这一页
     */
    public PageSnapshot select(String owner, int index) {
        OwnerBrowser o = owner(owner);
        synchronized (o) {
            o.sync(serial);
            return o.select(index);
        }
    }

    /**
     * 没有记在任何使用者名下的标签页（浏览器里的序号、标题、网址），<b>给界面用</b>：用户从里面挑一页交给某个申请
     * （{@link #handOver}）。用保活会话列，不切换、不取快照。
     */
    public List<TabInfo> unowned() {
        Set<String> taken = othersUrls(null);
        return keeperTabs().stream().filter(tab -> !taken.contains(tab.url()))
                .map(tab -> new TabInfo(tab.index(), tab.title(), tab.url(), false, false)).toList();
    }

    /**
     * 用户在工作台上把一个标签页交给这个使用者。用保活会话再列一次核对：序号还在、网址还是界面上看到的 {@code url}、
     * 别的使用者没在用，才记在它名下，并记一条待告诉模型的转交（{@link #takeHandover}）。它还没有正在用的页时这一页
     * 就是（下次用时在它的会话里 select 过去、开焦点模拟）；已经有时由模型 {@code browser_tabs select}。返回的序号是
     * 这个使用者自己的编号。
     *
     * @throws HandoverRefused 标签页不在了、网址变了，或者别的使用者正在用
     */
    public TabInfo handOver(String owner, int index, String url) {
        OwnerBrowser.Listed target = keeperTabs().stream().filter(tab -> tab.index() == index).findFirst()
                .orElseThrow(() -> new HandoverRefused("标签页 " + index + " 已经不在了，刷新列表再选"));
        if (url == null || !url.equals(target.url())) {
            throw new HandoverRefused("标签页 " + index + " 的网址变了（现在是 " + target.url() + "），刷新列表再选");
        }
        int now = serial;
        boolean inUseByOther = owners.values().stream()
                .anyMatch(o -> !o.owner.equals(owner) && url.equals(o.currentUrl()));
        if (inUseByOther) {
            throw new HandoverRefused("标签页 " + index + " 别的任务正在用，不能再交");
        }
        OwnerBrowser o = owner(owner);
        synchronized (o) {
            o.sync(now);
            return o.handOver(target.title(), target.url(), target.index());
        }
    }

    /** 用户转交给这个使用者、还没告诉模型的标签页，取一次就清掉；没有时为 null。 */
    public TabInfo takeHandover(String owner) {
        OwnerBrowser o = owner(owner);
        synchronized (o) {
            o.sync(serial);
            return o.takeHandover();
        }
    }

    /** 这个使用者在当前连接上有没有正在用的标签页（连接断开、重连、应用重启之后都是 false）。 */
    public boolean ownsTab(String owner) {
        if (owner == null) {
            return false;
        }
        OwnerBrowser o = owner(owner);
        synchronized (o) {
            o.sync(serial);
            return o.ownsTab();
        }
    }

    /**
     * 用户点了「重新连接」。共享连接还好好的就沿用，不关掉、不重新申请；否则建立连接。连不上不抛异常，返回的状态里写着原因。
     */
    public BrowserState reconnect() {
        synchronized (connectLock) {
            if (connection != null && state.connected()) {
                return state;
            }
            retryLeft = true;
        }
        try {
            ensureConnected();
        } catch (BrowserException e) {
            // 状态里已经记下了
        }
        return state;
    }

    /**
     * 唯一的连接入口（幂等）：连着就直接返回；还没连过、或者断开后还留着一次自动重连时建立连接（启动服务进程、开保活会话、
     * 用它列一次标签页：这时服务进程才连 Chrome，Chrome 弹一次允许）；否则抛出 {@link BrowserException.Disconnected}，
     * 不碰 MCP。同一时间只有一个调用在建立连接，别的调用等它的结果。
     */
    public void ensureConnected() {
        connected();
    }

    private Connection connected() {
        Connection current = connection;
        if (current != null && !state.disconnected()) {
            return current;
        }
        synchronized (connectLock) {
            current = connection;
            if (current != null && !state.disconnected()) {
                return current;
            }
            if (state.disconnected()) {
                if (!retryLeft) {
                    throw new BrowserException.Disconnected("还没有重新连上，上次断开的原因：" + state.reason());
                }
                retryLeft = false;
            }
            return connect();
        }
    }

    /**
     * 用户发了下一条消息（对话、申请的开始和回复）：只有共享连接断开了才再留一次自动重连，下一次真要用浏览器时才连；
     * 连接好好的时什么都不做，不碰别的使用者的会话。不拿锁。
     */
    public void retryOnNextUse() {
        if (state.disconnected()) {
            retryLeft = true;
        }
    }

    /** 现在的连接状态（不拿锁）。 */
    public BrowserState state() {
        return state;
    }

    /** 状态每次变化时通知 {@code listener}（不要在里面用浏览器）。 */
    public void onChange(Consumer<BrowserState> listener) {
        this.listener = listener == null ? ignored -> {
        } : listener;
    }

    public boolean isConnected() {
        return state.connected();
    }

    @Override
    public void close() {
        synchronized (connectLock) {
            Connection current = connection;
            if (current != null) {
                drop(current);
            }
            owners.values().forEach(o -> {
                PlaywrightMcpClient client = o.client;
                if (client != null) {
                    closeQuietly(client);
                }
            });
        }
    }

    private OwnerBrowser owner(String owner) {
        return owners.computeIfAbsent(owner, name -> new OwnerBrowser(name, calls));
    }

    /** 别的使用者（{@code owner} 以外）记下的所有网址。 */
    private Set<String> othersUrls(String owner) {
        Set<String> urls = new HashSet<>();
        int now = serial;
        owners.values().forEach(o -> {
            if (!o.owner.equals(owner)) {
                urls.addAll(o.urls());
            }
        });
        return serial == now ? urls : Set.of();
    }

    /** 用保活会话列出浏览器里所有的标签页（给界面用）。 */
    private List<OwnerBrowser.Listed> keeperTabs() {
        Connection current = connected();
        String text;
        try {
            synchronized (keeperLock) {
                text = current.keeper().call(BrowserToolPolicy.TABS, Map.of("action", "list"));
            }
        } catch (BrowserException.Disconnected e) {
            lost(current, e);
            throw e;
        }
        return OwnerBrowser.parse(text);
    }

    /**
     * 在这个使用者自己的会话上做一次调用（会话还没有、或者属于旧的连接时先开一个）。断开时分清是谁坏了：服务进程死了或者
     * 保活会话也不答话 = 共享连接断了，所有使用者的记录作废；保活会话还好 = 只是这个使用者的会话坏了，关掉它、下次用时
     * 重建并按网址找回自己的页，这次报一个工具错误让模型取快照核对，别的使用者不受影响。
     */
    private <T> T use(OwnerBrowser owner, Function<PlaywrightMcpClient, T> call) {
        Connection current = connected();
        if (owner.sync(current.serial())) {
            // 这个调用开始以后连接断开又连上了：之前的标签页和快照不属于这条连接
            throw new BrowserException.Disconnected("浏览器重新连接过，之前的标签页记录作废");
        }
        PlaywrightMcpClient client;
        try {
            client = session(owner, current);
            return call.apply(client);
        } catch (BrowserException.Disconnected e) {
            if (!sharedAlive(current)) {
                lost(current, e);
                throw e;
            }
            owner.dropClient();
            log.info("使用者 {} 的浏览器会话断了，下次使用时重建：{}", owner.owner, e.getMessage());
            throw new BrowserException.ToolFailed("这次调用没有完成：你的浏览器会话断了（" + e.getMessage()
                    + "），系统会重建它，标签页还在。先取快照核对页面，再继续。");
        }
    }

    private PlaywrightMcpClient session(OwnerBrowser owner, Connection current) {
        PlaywrightMcpClient client = owner.client;
        if (client != null && owner.clientSerial == current.serial()) {
            return client;
        }
        if (client != null) {
            owner.dropClient();
        }
        client = current.server().open();
        owner.client = client;
        owner.clientSerial = current.serial();
        return client;
    }

    /** 共享连接还在吗：服务进程活着、保活会话答话（工具报错也算答话）。 */
    private boolean sharedAlive(Connection current) {
        if (connection != current || !current.server().alive()) {
            return false;
        }
        try {
            synchronized (keeperLock) {
                current.keeper().call(BrowserToolPolicy.TABS, Map.of("action", "list"));
            }
            return true;
        } catch (BrowserException.ToolFailed e) {
            return true;
        } catch (BrowserException.Disconnected e) {
            return false;
        }
    }

    /** 共享连接断了：记为断开，所有使用者的记录作废。用过的连接留一次自动重连。 */
    private void lost(Connection current, BrowserException.Disconnected e) {
        synchronized (connectLock) {
            if (connection != current) {
                return;
            }
            boolean used = state.connected();
            drop(current);
            moveTo(BrowserState.DISCONNECTED, e.getMessage(), state.generation());
            retryLeft = used;
        }
    }

    /**
     * 建立共享连接（只在 {@link #connected()} 的锁里调用）。保活会话的第一次调用答话了才算连上；失败时记为断开、不留自动
     * 重连：之后的调用不再自己去连，免得 Chrome 反复弹窗、空转。
     */
    private Connection connect() {
        moveTo(BrowserState.CONNECTING, null, state.generation());
        serial++;
        try {
            clearOutputDir();
            for (Path root : launch.roots()) {
                Files.createDirectories(root);
            }
        } catch (IOException | UncheckedIOException e) {
            return failed(new BrowserException.Disconnected("准备浏览器 MCP 的目录失败：" + e.getMessage(), e));
        }
        PlaywrightMcpClient.Server server;
        try {
            server = PlaywrightMcpClient.Server.start(launch);
        } catch (RuntimeException e) {
            return failed(disconnected(e));
        }
        PlaywrightMcpClient keeper;
        try {
            keeper = server.open();
        } catch (RuntimeException e) {
            server.close();
            return failed(disconnected(e));
        }
        try {
            keeper.call(BrowserToolPolicy.TABS, Map.of("action", "list"));
        } catch (BrowserException.ToolFailed e) {
            // 工具报错，但 MCP 答话了：连接本身是好的
        } catch (RuntimeException e) {
            closeQuietly(keeper);
            server.close();
            return failed(disconnected(e));
        }
        Connection made = new Connection(server, keeper, serial);
        connection = made;
        moveTo(BrowserState.CONNECTED, null, state.generation() + 1);
        return made;
    }

    private static BrowserException.Disconnected disconnected(RuntimeException e) {
        return e instanceof BrowserException.Disconnected d ? d
                : new BrowserException.Disconnected("连接浏览器失败：" + e.getMessage(), e);
    }

    private Connection failed(BrowserException.Disconnected e) {
        serial++;
        moveTo(BrowserState.DISCONNECTED, e.getMessage(), state.generation());
        retryLeft = false;
        throw e;
    }

    /** 关掉共享连接（服务进程一关，所有会话都断了），使用者的记录作废，清空输出目录。 */
    private void drop(Connection current) {
        connection = null;
        serial++;
        closeQuietly(current.keeper());
        current.server().close();
        try {
            clearOutputDir();
        } catch (IOException | UncheckedIOException e) {
            log.warn("清空浏览器 MCP 输出目录失败：{}", e.getMessage());
        }
    }

    private static void closeQuietly(PlaywrightMcpClient client) {
        try {
            client.close();
        } catch (RuntimeException e) {
            log.debug("关闭浏览器 MCP 会话时出错：{}", e.getMessage());
        }
    }

    private void moveTo(String next, String reason, int generation) {
        BrowserState changed = new BrowserState(next, reason, clock.instant(), generation);
        state = changed;
        try {
            listener.accept(changed);
        } catch (RuntimeException e) {
            log.warn("浏览器状态通知出错：{}", e.getMessage());
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
