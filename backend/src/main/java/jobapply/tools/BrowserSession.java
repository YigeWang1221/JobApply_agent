package jobapply.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import jobapply.harness.policies.BrowserToolPolicy;
import jobapply.harness.policies.SnapshotRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 全局唯一的浏览器连接（浏览器只有一个）。连接属于应用，不属于某个 Session、Project 或 Job：应用运行期间一直挂着，
 * 不会自己关掉再重新申请（用户确认，2026-10-23）。
 * <p>
 * <b>共享的连接</b>（C011 方案 A）：一个 Playwright MCP 服务进程（{@link PlaywrightMcpClient.Server}，
 * {@code --port --shared-browser-context}），它到 Chrome 只有一条 CDP 连接；再加一个一直空闲的「保活」会话，让这条连接
 * 在没有使用者时也不断开，用户只在建立连接时点一次允许。连接状态只有这一份（{@link BrowserState}，C006-fix-browser
 * 第 1 节）：每次浏览器调用都先过 {@link #ensureConnected()}，幂等，同一时间只有一个调用在建立连接。用过的连接断开以后、
 * 第一次建立连接失败以后（C011-fix 2026-10-25），下一次使用时自动再连一次；这一次也连不上就停在 {@code disconnected}，之后的调用直接报
 * {@link BrowserException.Disconnected}、不再碰 MCP，等用户点「重新连接」（{@link #reconnect()}）或者发下一条消息
 * （{@link #retryOnNextUse()}）。建立连接和关闭时清空 MCP 的输出目录。
 * <p>
 * <b>卡住不等于断开</b>（C011-fix）：MCP 在调用超时内没回话（{@link BrowserException.Stalled}）时记为 {@code stalled}，
 * 不关服务进程、不重连、不清记录（重连解决不了，卡住的那一页还在）；之后哪一次调用答话了就回到 {@code connected}。
 * 用户点「重新连接」时先用保活会话问一次，答话了就接着用，答不了才重建。
 * <p>
 * <b>每个使用者一个浏览器会话</b>（{@link OwnerBrowser}）：使用者是一个 Job 的申请线程或者一个 Session 的调研。它有自己的
 * MCP 会话（自己的当前标签页）、自己的标签页记录、弹窗、说明和转交；<b>锁按使用者</b>，不同使用者的浏览器调用同时进行。
 * 一个使用者的会话坏了（连接本身还好）只重建它自己的；共享连接断了，所有使用者都得到 {@code Disconnected}、记录清空。
 * 给模型的标签页列表是 Chrome 里所有的页（{@link #tabs}），标出它自己的和别的使用者的，{@link #select} 能切到任何一页；
 * 用哪一页由提示词约束（AGENTS.md：浏览器就是用户正在用的 Chrome）。用户也可以在工作台上转交（{@link #unowned}、
 * {@link #handOver}）。
 * <p>
 * <b>停下时收拾</b>（C011-fix 2026-10-24）：使用者停下时调用 {@link #leave}（关掉它自己开的页、留下那页上的焦点模拟、
 * 它的 MCP 会话），彻底结束时调用 {@link #release}。原来开过的页从不关、每页都一直开着焦点模拟（Chrome 不节流、照常渲染），
 * 用久了整台 Mac 越来越卡。
 */
public final class BrowserSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BrowserSession.class);

    /** 认不出正在用的那一页时给模型的话。 */
    static final String LOST = "找不到你正在用的标签页（可能被关掉了）。可以用 browser_tabs list 看一下你还开着的页再 select；"
            + "也可以用 browser_navigate 重新打开要用的网址（系统会新开一个标签页）";

    /**
     * 一个标签页（浏览器里的序号、标题、网址）。
     *
     * @param inUse  这个使用者现在正在用的那一页（界面的列表里总是 false）
     * @param yours  这个使用者新开的、点击弹出的或者用户转交给它的（界面的列表里总是 false）
     * @param others 别的使用者（别的申请、调研）记下的页（界面的列表里总是 false）
     */
    public record TabInfo(int index, String title, String url, boolean inUse, boolean yours, boolean others) {
    }

    /** 使用者停下时怎么收拾它的标签页（{@link #leave}）。用户转交的、它切过去用的用户自己的页从不关。 */
    public enum Leave {
        /** 等用户回复、出错停下：页都留着。 */
        KEEP_ALL,
        /** 停在提交前等用户审阅：留下正在用的那页（填好的表），自己开的别的页（弹窗、多开的页）关掉。 */
        KEEP_CURRENT,
        /** 一次调研做完、申请彻底结束：自己开的页都关掉。 */
        CLOSE_ALL
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
    /** personal.env 里的真实值（C014-D）：交出去的快照、查找结果和报错里出现时换回占位符。 */
    private final Supplier<Map<String, String>> secrets;

    public BrowserSession(BrowserLaunch launch) {
        this(launch, Map::of);
    }

    /** @param secrets 每次取一份 personal.env 的 {@code KEY → 值}（{@link SnapshotRedactor#withoutValues}） */
    public BrowserSession(BrowserLaunch launch, Supplier<Map<String, String>> secrets) {
        this.launch = launch;
        this.secrets = secrets;
        this.clock = Clock.systemUTC();
        this.state = new BrowserState(BrowserState.NOT_CONNECTED, null, clock.instant(), 0);
    }

    /** 新开一个标签页打开页面（在这个使用者自己的会话里），开焦点模拟，再取一次快照。它成为这个使用者正在用的那页。 */
    public PageSnapshot open(String url, String owner) {
        OwnerBrowser o = owner(owner);
        synchronized (o) {
            o.sync(serial);
            return masked(o.open(url));
        }
    }

    /** 只读：取这个使用者正在用的那页的快照。还没有或者认不出时抛出 {@link TabLost}。 */
    public PageSnapshot snapshot(String owner) {
        OwnerBrowser o = owner(owner);
        synchronized (o) {
            o.sync(serial);
            return masked(o.snapshot());
        }
    }

    /**
     * 模型的一次浏览器操作：在这个使用者自己的会话里（当前页就是它在用的那页）按 {@code context} 过白名单并发出，再取一次
     * 快照返回。{@code args} 必须已经去掉模型多给的参数；点击和填写的守卫由调用方先判断。{@code browser_snapshot} 直接返回
     * 这次的快照。点击打开了自己的弹窗时切过去，返回新标签页的快照（{@link #takeNotice} 说明一次）。点击、输入、选择动作
     * 超时时系统检查一次原因写进错误；只是被别的层盖住的点击直接点一次。正在用的那页认不出时：
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
                    return masked(o.act(tool, args, context));
                } catch (TabLost e) {
                    if (!BrowserToolPolicy.NAVIGATE.equals(tool)) {
                        throw e;
                    }
                } catch (BrowserException.ToolFailed e) {
                    throw new BrowserException.ToolFailed(masked(e.getMessage()));
                }
            }
            if (BrowserToolPolicy.NAVIGATE.equals(tool) && args.get("url") instanceof String target) {
                BrowserToolPolicy.check(tool, args, context);
                return masked(o.open(target));
            }
            throw new TabLost(had ? LOST : "还没有你在用的标签页，不能操作");
        }
    }

    /** 模型的 {@code browser_find}：只读，在这个使用者正在用的那页上，返回 MCP 的原文。 */
    public String find(String owner, Map<String, Object> args, BrowserToolPolicy.Context context) {
        OwnerBrowser o = owner(owner);
        synchronized (o) {
            o.sync(serial);
            try {
                return masked(o.find(args, context));
            } catch (BrowserException.ToolFailed e) {
                throw new BrowserException.ToolFailed(masked(e.getMessage()));
            }
        }
    }

    /** 上一次操作留给这个使用者的说明，取一次就清掉；没有时为 null。 */
    public String takeNotice(String owner) {
        OwnerBrowser o = owner(owner);
        synchronized (o) {
            o.sync(serial);
            String notice = o.takeNotice();
            return notice == null ? null : masked(notice);
        }
    }

    /**
     * 填写之前的只读检查（C016 A）：{@code fields} 是 ref → 字段类型（{@code browser_type} 写 textbox），返回键入 / 填写
     * 不会生效的那些 ref → 原因。检查本身出错、还没有标签页时返回空（照常发出，由 MCP 报错）；连接断开、卡住照常抛出。
     */
    public Map<String, String> cannotFill(String owner, Map<String, String> fields) {
        OwnerBrowser o = owner(owner);
        synchronized (o) {
            o.sync(serial);
            if (!o.ownsTab() || fields.isEmpty()) {
                return Map.of();
            }
            Map<String, Map<String, Object>> found;
            try {
                found = o.inspect(List.copyOf(fields.keySet()));
            } catch (BrowserException.ToolFailed | TabLost e) {
                return Map.of();
            }
            Map<String, String> out = new LinkedHashMap<>();
            fields.forEach((ref, type) -> {
                String reason = PlaywrightMcpClient.cannotFill(found.get(ref), type);
                if (reason != null) {
                    out.put(ref, reason);
                }
            });
            return out;
        }
    }

    /** Chrome 里所有的标签页（浏览器里的序号），标出正在用的、这个使用者的、别的使用者的。只列，不切换。 */
    public List<TabInfo> tabs(String owner) {
        OwnerBrowser o = owner(owner);
        synchronized (o) {
            o.sync(serial);
            return o.tabs();
        }
    }

    /**
     * 切到第 {@code index} 页（{@link #tabs} 列出的序号），取快照，它成为这个使用者正在用的那页（还不是它的就记在名下）。
     *
     * @throws IllegalArgumentException 没有这一页
     */
    public PageSnapshot select(String owner, int index) {
        OwnerBrowser o = owner(owner);
        synchronized (o) {
            o.sync(serial);
            return masked(o.select(index));
        }
    }

    /**
     * 没有记在任何使用者名下的标签页（浏览器里的序号、标题、网址），<b>给界面用</b>：用户从里面挑一页交给某个申请
     * （{@link #handOver}）。用保活会话列，不切换、不取快照。
     */
    public List<TabInfo> unowned() {
        Set<String> taken = othersUrls(null);
        return keeperTabs().stream().filter(tab -> !taken.contains(tab.url()))
                .map(tab -> new TabInfo(tab.index(), tab.title(), tab.url(), false, false, false)).toList();
    }

    /**
     * 用户在工作台上把一个标签页交给这个使用者。用保活会话再列一次核对：序号还在、网址还是界面上看到的 {@code url}、
     * 别的使用者没在用，才记在它名下，并记一条待告诉模型的转交（{@link #takeHandover}）。它还没有正在用的页时这一页
     * 就是（下次用时在它的会话里 select 过去、开焦点模拟）；已经有时由模型 {@code browser_tabs select}。返回的序号是
     * 浏览器里的序号。
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
     * 使用者停下了：按 {@code keep} 关掉它自己开的页，关掉留下那页上的焦点模拟，再关掉它的 MCP 会话（Playwright MCP 每个
     * 会话都给每一页记请求和事件）。记录留着，接着做时在新会话里按网址找回、重新开焦点模拟。浏览器没连着（断开、卡住、
     * 还没连）时不碰浏览器、不去连，只关会话。不抛异常，出错记日志。
     */
    public void leave(String owner, Leave keep) {
        OwnerBrowser o = owner == null ? null : owners.get(owner);
        if (o == null) {
            return;
        }
        synchronized (o) {
            try {
                o.sync(serial);
                o.leave(keep, connection != null && state.connected());
            } catch (RuntimeException e) {
                log.warn("使用者 {} 停下时收拾标签页出错：{}", owner, e.getMessage());
            }
        }
    }

    /** 使用者彻底结束了（申请已投递、不投了、岗位下线、被删除，Session 被删除）：自己开的页都关掉、关会话、去掉记录。 */
    public void release(String owner) {
        leave(owner, Leave.CLOSE_ALL);
        if (owner != null) {
            owners.remove(owner);
        }
    }

    /**
     * 用户点了「重新连接」。共享连接还好好的就沿用，不关掉、不重新建立。卡住了（{@code stalled}）时先用保活会话问一次：
     * 答话了就接着用（连接、标签页记录都不动）；还是不答才关掉重建。断开了就建立连接。连不上不抛异常，返回的状态里写着
     * 原因。
     */
    public BrowserState reconnect() {
        Connection stuck;
        synchronized (connectLock) {
            if (connection != null && state.connected()) {
                return state;
            }
            stuck = connection != null && state.stalled() ? connection : null;
            retryLeft = true;
        }
        if (stuck != null) {
            try {
                synchronized (keeperLock) {
                    stuck.keeper().call(BrowserToolPolicy.TABS, Map.of("action", "list"));
                }
                answered(stuck);
                return state;
            } catch (BrowserException.ToolFailed e) {
                // 工具报错，但 MCP 答话了
                answered(stuck);
                return state;
            } catch (BrowserException.Stalled | BrowserException.Disconnected e) {
                log.info("重新连接：原来的连接还是不答话，关掉重建：{}", e.getMessage());
                lost(stuck, new BrowserException.Disconnected("重新连接时浏览器还是没有回应，已重新建立连接："
                        + e.getMessage(), e));
                retryLeft = true;
            }
        }
        try {
            ensureConnected();
        } catch (BrowserException e) {
            // 状态里已经记下了
        }
        return state;
    }

    /**
     * 唯一的连接入口（幂等）：连着（包括卡住了）就直接返回；还没连过、或者断开后还留着一次自动重连时建立连接（启动服务
     * 进程、开保活会话、用它列一次标签页：这时服务进程才连 Chrome，Chrome 弹一次允许）；否则抛出 {@link BrowserException.Disconnected}，
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
            boolean retrying = state.disconnected();
            if (retrying) {
                if (!retryLeft) {
                    throw new BrowserException.Disconnected("还没有重新连上，上次的原因：" + state.reason()
                            + "。用户发下一条消息或点「重新连接」时会再连");
                }
                retryLeft = false;
            }
            return connect(retrying);
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
            // 先关使用者的会话、再关服务进程：反过来时每个会话关闭时的请求都连不上服务进程，只留一串警告
            owners.values().forEach(o -> {
                PlaywrightMcpClient client = o.client;
                if (client != null) {
                    closeQuietly(client);
                }
            });
            Connection current = connection;
            if (current != null) {
                drop(current);
            }
        }
    }

    private PageSnapshot masked(PageSnapshot page) {
        return SnapshotRedactor.withoutValues(page, secrets.get());
    }

    private String masked(String text) {
        return SnapshotRedactor.withoutValues(text, secrets.get());
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
        } catch (BrowserException.Stalled e) {
            throw stuckOrGone(current, e);
        } catch (BrowserException.Disconnected e) {
            lost(current, e);
            throw e;
        }
        answered(current);
        return OwnerBrowser.parse(text);
    }

    /**
     * 在这个使用者自己的会话上做一次调用（会话还没有、或者属于旧的连接时先开一个）。断开时分清是谁坏了：服务进程死了或者
     * 保活会话也不答话 = 共享连接断了，所有使用者的记录作废；保活会话还好 = 只是这个使用者的会话坏了，关掉它、下次用时
     * 重建并按网址找回自己的页，这次报一个工具错误让模型取快照核对，别的使用者不受影响。MCP 没回话（卡住了）只记下
     * {@code stalled}，会话和记录都不动。
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
            T result = call.apply(client);
            answered(current);
            return result;
        } catch (BrowserException.Stalled e) {
            throw stuckOrGone(current, e);
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

    /** 共享连接还在吗：服务进程活着、保活会话答话（工具报错也算答话）。保活会话也不答话（卡住了）时抛出那个异常。 */
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
        } catch (BrowserException.Stalled e) {
            stuck(current, e);
            throw e;
        } catch (BrowserException.Disconnected e) {
            return false;
        }
    }

    /**
     * MCP 没回话：服务进程还活着 = 卡住了（记为 {@code stalled}，不关服务进程、不清记录），返回原来的异常；进程已经退出
     * （HTTP 传输在服务端死掉时也只会等到超时）= 断开了，返回 {@link BrowserException.Disconnected}。
     */
    private BrowserException stuckOrGone(Connection current, BrowserException.Stalled e) {
        if (current.server().alive()) {
            stuck(current, e);
            return e;
        }
        BrowserException.Disconnected gone = new BrowserException.Disconnected("浏览器 MCP 进程退出了，连接断开", e);
        lost(current, gone);
        return gone;
    }

    /** 记为卡住（原因写进状态）。 */
    private void stuck(Connection current, BrowserException.Stalled e) {
        synchronized (connectLock) {
            if (connection == current && !state.disconnected()) {
                moveTo(BrowserState.STALLED, e.getMessage(), state.generation());
            }
        }
    }

    /** 卡住以后又有调用答话了：回到已连接（同一次连接，代数不变）。 */
    private void answered(Connection current) {
        if (!state.stalled()) {
            return;
        }
        synchronized (connectLock) {
            if (connection == current && state.stalled()) {
                moveTo(BrowserState.CONNECTED, null, state.generation());
            }
        }
    }

    /** 共享连接断了：记为断开，所有使用者的记录作废。用过的连接留一次自动重连。 */
    private void lost(Connection current, BrowserException.Disconnected e) {
        synchronized (connectLock) {
            if (connection != current) {
                return;
            }
            boolean used = state.connected() || state.stalled();
            drop(current);
            moveTo(BrowserState.DISCONNECTED, e.getMessage(), state.generation());
            retryLeft = used;
        }
    }

    /**
     * 建立共享连接（只在 {@link #connected()} 的锁里调用）。保活会话的第一次调用答话了才算连上。失败时记为断开：这是一次
     * 新的尝试就留一次自动重连（下一次使用时再连，不用用户点）；已经是那一次自动重连（{@code retrying}）就不再自己去连，
     * 等用户发消息或点「重新连接」，免得 Chrome 反复弹允许、空转。
     */
    private Connection connect(boolean retrying) {
        moveTo(BrowserState.CONNECTING, null, state.generation());
        serial++;
        try {
            clearOutputDir();
            for (Path root : launch.roots()) {
                Files.createDirectories(root);
            }
        } catch (IOException | UncheckedIOException e) {
            return failed(new BrowserException.Disconnected("准备浏览器 MCP 的目录失败：" + e.getMessage(), e), retrying);
        }
        PlaywrightMcpClient.Server server;
        try {
            server = PlaywrightMcpClient.Server.start(launch);
        } catch (RuntimeException e) {
            return failed(disconnected(e), retrying);
        }
        PlaywrightMcpClient keeper;
        try {
            keeper = server.openKeeper();
        } catch (RuntimeException e) {
            server.close();
            return failed(disconnected(e), retrying);
        }
        try {
            // 第一次调用：MCP 这时才连 Chrome（Chrome 弹一次允许）
            keeper.call(BrowserToolPolicy.TABS, Map.of("action", "list"));
        } catch (BrowserException.ToolFailed e) {
            // 工具报错，但 MCP 答话了：连接本身是好的
        } catch (BrowserException.Stalled e) {
            closeQuietly(keeper);
            server.close();
            return failed(new BrowserException.Disconnected("连 Chrome 等了 " + launch.connectTimeout().toSeconds()
                    + " 秒没有回应：Chrome 可能在等你允许这次连接（看看有没有弹出提示），也可能 Chrome 本身没有响应", e), retrying);
        } catch (RuntimeException e) {
            closeQuietly(keeper);
            server.close();
            return failed(disconnected(e), retrying);
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

    private Connection failed(BrowserException.Disconnected e, boolean retrying) {
        serial++;
        moveTo(BrowserState.DISCONNECTED, e.getMessage(), state.generation());
        retryLeft = !retrying;
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
