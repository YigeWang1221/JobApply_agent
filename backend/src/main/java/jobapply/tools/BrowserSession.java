package jobapply.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jobapply.harness.policies.BrowserToolPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 全局唯一的浏览器连接持有者（浏览器只有一个）。连接属于应用，不属于某个 Session、Project 或 Job：应用运行期间一直
 * 挂着，不会自己关掉再重新申请（用户确认，2026-10-23）。
 * <p>
 * 连接状态只有这一份（{@link BrowserState}，C006-fix-browser 第 1 节，参考 Hermes 的 {@code SUPERVISOR_REGISTRY.get_or_start}）：
 * 每次浏览器调用都先过 {@link #ensureConnected()}，幂等，同一时间只有一个调用在建立连接（方法都在同一把锁里，别的调用
 * 等这一次的结果）。用过的连接断开以后，下一次使用时自动重连一次（远程调试模式下重连不用再确认）；这一次也连不上就
 * 停在 {@code disconnected}，之后的调用直接报 {@link BrowserException.Disconnected}、不再碰 MCP，不在后台重试，等用户点
 * 「重新连接」（{@link #reconnect()}）或者发下一条消息（{@link #retryOnNextUse()}）。快照里的 ref 不跨连接使用。
 * 建立连接和关闭时清空 MCP 的输出目录（{@link BrowserLaunch#outputDir()}），里面可能有登录后的页面内容。
 * <p>
 * 标签页只做系统自己需要的记账（C006-fix-browser 第 3 节，用户 2026-10-23）：每个使用者（一个 Job 的申请线程、一个
 * Session 的调研）正在用哪一页（序号 + 最近一次看到的网址），操作前先切回那一页（调研和申请会轮流用浏览器）；它新开的、
 * 点击弹出的、用户转交的标签页记在它名下，列表里标出来。<b>能碰哪些标签页由提示词和 skill 约束</b>，代码不猜归属、
 * 不拒绝：{@link #tabs} 列出所有标签页，{@link #select} 能切到任何一个。正在用的那页认不出时（序号和网址都对不上、
 * 网址也不唯一、序号也不在了）抛出 {@link TabLost}，说明交给模型。
 */
public final class BrowserSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BrowserSession.class);

    private final Function<BrowserLaunch, PlaywrightMcpClient> connector;
    private final BrowserLaunch launch;
    private PlaywrightMcpClient client;
    /** {@code browser_tabs} 列表里的一行：{@code - 1: (current) [标题](网址)}。 */
    private static final Pattern TAB_LINE = Pattern.compile(
            "(?m)^- (\\d+):( \\(current\\))? \\[(.*)\\]\\((.*)\\)(?: \\[crashed\\])?$");

    /** 认不出正在用的那一页时给模型的话。 */
    static final String LOST = "找不到你正在用的标签页（可能被关掉了）。申请里可以用 browser_tabs list 看一下再 select；"
            + "也可以用 browser_navigate 重新打开要用的网址（系统会新开一个标签页）";

    /** 一个标签页的记录：序号和最近一次看到的网址。 */
    private record Tab(int index, String url) {
    }

    /** 列表里的一个标签页。 */
    private record Listed(int index, boolean current, String title, String url) {
    }

    /**
     * 一个标签页（序号、标题、网址）。
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

    /** 使用者 → 它正在用的标签页（当前连接上）。 */
    private final Map<String, Tab> tabs = new HashMap<>();
    /** 使用者 → 记在它名下的标签页（它新开的、点击弹出的、用户转交的；只用来在列表里标出来）。 */
    private final Map<String, List<Tab>> yours = new HashMap<>();
    /** 使用者 → 还没交给它的说明（点击打开了新标签页、系统切过去了）。 */
    private final Map<String, String> notices = new HashMap<>();
    /** 使用者 → 用户刚转交给它、还没告诉模型的标签页（{@link #takeHandover}）。 */
    private final Map<String, TabInfo> handed = new HashMap<>();
    /** 上一次列出来的标签页个数（点击后多了一个 = 点开了新标签页）。 */
    private int tabCount;
    /** 连接状态（{@link BrowserState}）；读不拿锁，界面查询不被一次长的浏览器调用挡住。 */
    private volatile BrowserState state;
    /**
     * {@code disconnected} 时还能不能自己再连一次（用过的连接断开时留一次；用户发消息、点重新连接时再留一次）。
     * {@code volatile}：{@link #retryOnNextUse()} 不拿锁写它。
     */
    private volatile boolean retryLeft;
    /** 状态变了通知谁（配置里接到全局事件 {@code browser}）。 */
    private volatile Consumer<BrowserState> listener = ignored -> {
    };
    private final Clock clock;

    public BrowserSession(BrowserLaunch launch) {
        this(launch, PlaywrightMcpClient::start);
    }

    BrowserSession(BrowserLaunch launch, Function<BrowserLaunch, PlaywrightMcpClient> connector) {
        this.launch = launch;
        this.connector = connector;
        this.clock = Clock.systemUTC();
        this.state = new BrowserState(BrowserState.NOT_CONNECTED, null, clock.instant(), 0);
    }

    /**
     * 新开一个标签页打开页面，再取一次快照。新标签页成为这个使用者正在用的那页，记在它名下；不动别的标签页。
     */
    public synchronized PageSnapshot open(String url, String owner) {
        List<Listed> listed = parseTabs(call(BrowserToolPolicy.TABS, Map.of("action", "new", "url", url)));
        if (listed.stream().noneMatch(Listed::current)) {
            listed = parseTabs(call(BrowserToolPolicy.TABS, Map.of("action", "list")));
        }
        tabCount = listed.size();
        Listed opened = listed.stream().filter(Listed::current).findFirst().orElse(null);
        if (opened == null) {
            tabs.remove(owner);
        } else {
            refresh(listed.stream().filter(tab -> tab != opened).toList());
            tabs.put(owner, at(opened));
            yours.computeIfAbsent(owner, k -> new ArrayList<>()).add(at(opened));
        }
        return read(owner);
    }

    /** 只读：切到这个使用者正在用的那页，取快照。还没有或者认不出时抛出 {@link TabLost}。 */
    public synchronized PageSnapshot snapshot(String owner) {
        if (!ownsTab(owner)) {
            throw new TabLost("还没有你在用的标签页，不能取快照");
        }
        if (!focus(owner)) {
            throw new TabLost(LOST);
        }
        return read(owner);
    }

    /**
     * 模型的一次浏览器操作：先切到这个使用者正在用的那页，再按 {@code context} 过白名单并发出，最后取一次快照返回
     * （写操作之后系统自己取快照，交给守卫和模型）。{@code args} 必须已经去掉模型多给的参数
     * （{@link jobapply.harness.policies.FieldIntent}、{@link jobapply.harness.policies.SubmitGuard.ClickIntent}）；
     * 点击和填写的守卫由调用方先判断。{@code browser_snapshot} 直接返回这次的快照，不再多取一次。
     * 点击打开了新标签页时切过去，返回新标签页的快照（{@link #takeNotice} 说明一次）。
     * 点击、输入、选择动作超时时系统检查一次原因写进错误；点击的目标只是被别的层盖住时直接点一次（{@link #afterTimeout}）。
     * 正在用的那页认不出时：{@code browser_navigate} 过了白名单就新开一个标签页打开它，别的操作抛出 {@link TabLost}。
     * 连接断开时抛出 {@link BrowserException.Disconnected}，标签页记录清空。
     */
    public synchronized PageSnapshot act(String owner, String tool, Map<String, Object> args,
                                         BrowserToolPolicy.Context context) {
        boolean had = ownsTab(owner);
        if (!had || !focus(owner)) {
            if (BrowserToolPolicy.NAVIGATE.equals(tool) && args.get("url") instanceof String target) {
                BrowserToolPolicy.check(tool, args, context);
                return open(target, owner);
            }
            throw new TabLost(had ? LOST : "还没有你在用的标签页，不能操作");
        }
        int before = tabCount;
        String result;
        try {
            result = call(tool, args, context);
        } catch (BrowserException.ToolFailed e) {
            result = afterTimeout(owner, tool, args, e);
        }
        if (BrowserToolPolicy.SNAPSHOT.equals(tool)) {
            return remember(owner, PageSnapshot.parse(result));
        }
        if (BrowserToolPolicy.CLICK.equals(tool)) {
            List<Listed> listed = parseTabs(result);
            if (before > 0 && listed.size() > before) {
                Listed opened = listed.get(listed.size() - 1);
                tabCount = listed.size();
                yours.computeIfAbsent(owner, k -> new ArrayList<>()).add(at(opened));
                notices.put(owner, "这次点击打开了新的标签页（" + opened.url() + "），系统已经切过去，下面是新标签页的快照；"
                        + "原来那页还开着，要回去用 browser_tabs。");
                return switchTo(owner, opened);
            }
        }
        return read(owner);
    }

    /**
     * 点击、输入、选择动作超时（C006-fix-real-run 第 20 条）：MCP 的超时错误看不出原因，系统用固定模板检查一次
     * （{@link PlaywrightMcpClient#diagnose}，只读），把实情写进错误交给模型。点击的目标看得见、只是被别的层盖住
     * （底部固定栏、没关的浮层）时，系统在同一个 ref 上直接点一次（{@link PlaywrightMcpClient#clickThrough}），返回它的
     * 结果，之后照常取快照，并留一句说明给模型核对。只试一次，失败照实报。调用方在这之前已经过了点击守卫：
     * 提交类按钮在发出任何点击之前就被拒绝了，走不到这里。别的错误、检查本身失败时原样抛出 {@code failed}。
     */
    private String afterTimeout(String owner, String tool, Map<String, Object> args, BrowserException.ToolFailed failed) {
        boolean click = BrowserToolPolicy.CLICK.equals(tool);
        if (!(click || BrowserToolPolicy.TYPE.equals(tool) || BrowserToolPolicy.SELECT_OPTION.equals(tool))
                || !(args.get("target") instanceof String ref) || !PlaywrightMcpClient.isActionTimeout(failed.getMessage())) {
            throw failed;
        }
        PlaywrightMcpClient.Diagnosis diagnosis;
        try {
            diagnosis = system(c -> c.diagnose(ref));
        } catch (BrowserException.ToolFailed e) {
            throw failed;
        }
        String timedOut = tool + " 返回错误：动作超时，元素在等待时间内一直不能操作，这次操作没有发生（不是浏览器的安全限制）。"
                + diagnosis.describe();
        if (!click || !diagnosis.coveredButVisible()) {
            throw new BrowserException.ToolFailed(timedOut);
        }
        String result;
        try {
            result = system(c -> c.clickThrough(ref));
        } catch (BrowserException.ToolFailed e) {
            throw new BrowserException.ToolFailed(timedOut + "系统又在这个元素上直接点了一次，也失败了：" + e.getMessage());
        }
        notices.put(owner, "这个元素被别的层盖住，普通点击超时了；系统在同一个元素上直接点了一次（不经过屏幕坐标）。"
                + diagnosis.describe() + "下面是点击之后的快照，核对一下是不是真的生效了（例如多了一张新卡片、弹出层打开了）；"
                + "没有生效时照上面的说明处理，不要自己猜原因。");
        return result;
    }

    /** 模型的 {@code browser_find}：只读，先切到这个使用者正在用的那页，返回 MCP 的原文。 */
    public synchronized String find(String owner, Map<String, Object> args, BrowserToolPolicy.Context context) {
        if (!ownsTab(owner)) {
            throw new TabLost("还没有你在用的标签页，不能操作");
        }
        if (!focus(owner)) {
            throw new TabLost(LOST);
        }
        return call(BrowserToolPolicy.FIND, args, context);
    }

    /** 上一次操作留给这个使用者的说明（例如「点击打开了新标签页，系统切过去了」），取一次就清掉；没有时为 null。 */
    public synchronized String takeNotice(String owner) {
        return notices.remove(owner);
    }

    /** Chrome 里所有的标签页，标出这个使用者正在用的、记在它名下的。只列，不切换。能碰哪些由提示词约束。 */
    public synchronized List<TabInfo> tabs(String owner) {
        List<Listed> listed = list();
        Tab inUse = tabs.get(owner);
        List<Tab> mine = yours.getOrDefault(owner, List.of());
        return listed.stream().map(tab -> new TabInfo(tab.index(), tab.title(), tab.url(),
                inUse != null && inUse.index() == tab.index(),
                mine.stream().anyMatch(t -> t.index() == tab.index()))).toList();
    }

    /**
     * 切到序号 {@code index} 的标签页，取快照，它成为这个使用者正在用的那页。
     *
     * @throws IllegalArgumentException 没有这个序号
     */
    public synchronized PageSnapshot select(String owner, int index) {
        Listed target = list().stream().filter(t -> t.index() == index).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("没有序号 " + index + " 的标签页，先 browser_tabs list"));
        return switchTo(owner, target);
    }

    /**
     * 没有记在任何使用者名下、也没人正在用的标签页（序号、标题、网址），<b>给界面用</b>：用户从里面挑一页交给某个申请
     * （{@link #handOver}）。只列，不切换、不取快照。
     */
    public synchronized List<TabInfo> unowned() {
        List<Listed> listed = list();
        Set<Integer> taken = new HashSet<>();
        tabs.values().forEach(tab -> taken.add(tab.index()));
        yours.values().forEach(list -> list.forEach(tab -> taken.add(tab.index())));
        return listed.stream().filter(tab -> !taken.contains(tab.index()))
                .map(tab -> new TabInfo(tab.index(), tab.title(), tab.url(), false, false)).toList();
    }

    /**
     * 用户在工作台上把一个标签页交给这个使用者。再列一次核对：序号还在、网址还是界面上看到的 {@code url}、别的使用者
     * 没在用，才记在它名下，并记一条待告诉模型的转交（{@link #takeHandover}）。它还没有正在用的页时，这一页就是；
     * 已经有时不偷换它脚下的页面（旧快照的 ref 会落到新页上），由模型 {@code browser_tabs select} 切过去。不切换、不取快照。
     *
     * @throws HandoverRefused 标签页不在了、网址变了，或者别的使用者正在用
     */
    public synchronized TabInfo handOver(String owner, int index, String url) {
        Listed target = list().stream().filter(tab -> tab.index() == index).findFirst()
                .orElseThrow(() -> new HandoverRefused("标签页 " + index + " 已经不在了，刷新列表再选"));
        if (url == null || !url.equals(target.url())) {
            throw new HandoverRefused("标签页 " + index + " 的网址变了（现在是 " + target.url() + "），刷新列表再选");
        }
        boolean inUseByOther = tabs.entrySet().stream()
                .anyMatch(e -> !e.getKey().equals(owner) && e.getValue().index() == index);
        if (inUseByOther) {
            throw new HandoverRefused("标签页 " + index + " 别的任务正在用，不能再交");
        }
        yours.computeIfAbsent(owner, k -> new ArrayList<>()).add(at(target));
        tabs.putIfAbsent(owner, at(target));
        TabInfo info = new TabInfo(target.index(), target.title(), target.url(), false, true);
        handed.put(owner, info);
        return info;
    }

    /** 用户转交给这个使用者、还没告诉模型的标签页，取一次就清掉；没有时为 null。 */
    public synchronized TabInfo takeHandover(String owner) {
        return handed.remove(owner);
    }

    /**
     * 这个使用者在当前连接上有没有记下的正在用的标签页（连接断开、重连、应用重启之后都是 false）。
     * 有、但不是当前标签页时，下一次调用会先切过去。
     */
    public synchronized boolean ownsTab(String owner) {
        return owner != null && tabs.containsKey(owner);
    }

    /**
     * 用户点了「重新连接」。连接还好好的（例如别的调用已经重连过）就沿用，不关掉、不重新申请；否则建立连接，用一次
     * {@code browser_tabs list} 试试（结果丢掉，不碰标签页记录）。连不上不抛异常，返回的状态里写着原因。
     */
    public synchronized BrowserState reconnect() {
        if (client != null && state.connected()) {
            return state;
        }
        retryLeft = true;
        try {
            call(BrowserToolPolicy.TABS, Map.of("action", "list"));
        } catch (BrowserException e) {
            // 状态里已经记下了（连不上是 disconnected；工具报错说明连接本身是好的）
        }
        return state;
    }

    /**
     * 唯一的连接入口（幂等）：连着就直接返回；还没连过、或者断开后还留着一次自动重连时建立连接；否则抛出
     * {@link BrowserException.Disconnected}，不碰 MCP。在锁里，同一时间只有一个调用在建立连接，别的调用等它的结果。
     * 建立以后第一次调用成功才算连上（Playwright MCP 进程起来时还没碰 Chrome）。
     */
    public synchronized void ensureConnected() {
        if (client != null && !state.disconnected()) {
            return;
        }
        if (state.disconnected()) {
            if (!retryLeft) {
                // 等用户点「重新连接」或者发下一条消息，不再自己连
                throw new BrowserException.Disconnected("还没有重新连上，上次断开的原因：" + state.reason());
            }
            retryLeft = false;
        }
        connect();
    }

    /**
     * 用户发了下一条消息（对话、申请的开始和回复）：断开了的话再留一次自动重连，下一次真要用浏览器时才连
     * （不用浏览器的消息不碰 Chrome）。不拿锁：和浏览器无关的消息不排在一次长的浏览器调用后面（和正在建立的连接碰上，
     * 最坏多留一次自动重连）。
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

    /** 状态每次变化时通知 {@code listener}（在持有这把锁的线程里调用，不要在里面用浏览器）。 */
    public void onChange(Consumer<BrowserState> listener) {
        this.listener = listener == null ? ignored -> {
        } : listener;
    }

    public boolean isConnected() {
        return state.connected();
    }

    @Override
    public synchronized void close() {
        disconnect();
    }

    /**
     * 切到这个使用者正在用的那页（不是当前标签页时）。先列出标签页、刷新记录（{@link #refresh}）；认不出就返回 false，
     * 没有操作。
     */
    private boolean focus(String owner) {
        List<Listed> listed = list();
        Tab tab = tabs.get(owner);
        Listed found = tab == null ? null
                : listed.stream().filter(t -> t.index() == tab.index()).findFirst().orElse(null);
        if (found == null) {
            tabs.remove(owner);
            return false;
        }
        if (!found.current()) {
            call(BrowserToolPolicy.TABS, Map.of("action", "select", "index", found.index()));
        }
        return true;
    }

    /** 列出标签页，记下个数，刷新记录。 */
    private List<Listed> list() {
        List<Listed> listed = parseTabs(call(BrowserToolPolicy.TABS, Map.of("action", "list")));
        tabCount = listed.size();
        refresh(listed);
        return listed;
    }

    /**
     * 按这次的列表更新记录（序号可能因为用户关掉前面的页而整体挪了）。正在用的那页：序号和网址都对上，或者网址在列表里
     * 只出现一次；都不成立、记下的序号还在时认作同一页（网址变了，例如用户在这一页登录了、点了下一步），留一句说明给模型；
     * 序号也不在了就去掉。记在名下的别的页只按网址认，认不出就去掉（只影响列表里的标记）。
     */
    private void refresh(List<Listed> listed) {
        Set<Integer> taken = new HashSet<>();
        Map<String, Tab> found = new HashMap<>();
        tabs.forEach((owner, tab) -> {
            Listed match = byUrl(tab, listed, taken);
            if (match != null) {
                found.put(owner, at(match));
                taken.add(match.index());
            }
        });
        tabs.forEach((owner, tab) -> {
            if (found.containsKey(owner) || taken.contains(tab.index())) {
                return;
            }
            listed.stream().filter(t -> t.index() == tab.index()).findFirst().ifPresent(match -> {
                found.put(owner, at(match));
                taken.add(match.index());
                notices.put(owner, "你在用的标签页 " + match.index() + " 网址变了（原来 " + tab.url() + "，现在 "
                        + match.url() + "），系统按序号认作同一页；不是你的页面时用 browser_tabs list 看一下。");
            });
        });
        tabs.clear();
        tabs.putAll(found);
        yours.replaceAll((owner, list) -> list.stream().map(tab -> byUrl(tab, listed, Set.of()))
                .filter(Objects::nonNull).map(BrowserSession::at).distinct()
                .collect(Collectors.toCollection(ArrayList::new)));
        yours.values().removeIf(List::isEmpty);
    }

    /** 序号和网址都对上的那一页；没有时网址在列表里（{@code taken} 以外）只出现一次的那一页；否则 null。 */
    private static Listed byUrl(Tab tab, List<Listed> listed, Set<Integer> taken) {
        List<Listed> same = listed.stream()
                .filter(t -> t.url().equals(tab.url()) && !taken.contains(t.index())).toList();
        return same.stream().filter(t -> t.index() == tab.index()).findFirst()
                .orElse(same.size() == 1 ? same.get(0) : null);
    }

    private static Tab at(Listed listed) {
        return new Tab(listed.index(), listed.url());
    }

    /** 把 {@code target} 记成这个使用者正在用的标签页，切过去、取快照。 */
    private PageSnapshot switchTo(String owner, Listed target) {
        tabs.put(owner, at(target));
        call(BrowserToolPolicy.TABS, Map.of("action", "select", "index", target.index()));
        return read(owner);
    }

    /** 取当前标签页（已经切到 {@code owner} 正在用的那页）的快照，记下它的网址。 */
    private PageSnapshot read(String owner) {
        return remember(owner, PageSnapshot.parse(call(BrowserToolPolicy.SNAPSHOT, Map.of())));
    }

    private PageSnapshot remember(String owner, PageSnapshot page) {
        Tab tab = tabs.get(owner);
        if (tab != null && page.url() != null && !page.url().isBlank()) {
            Tab now = new Tab(tab.index(), page.url());
            tabs.put(owner, now);
            List<Tab> mine = yours.get(owner);
            if (mine != null) {
                mine.replaceAll(t -> t.index() == now.index() ? now : t);
            }
        }
        return page;
    }

    private static List<Listed> parseTabs(String text) {
        List<Listed> listed = new ArrayList<>();
        Matcher m = TAB_LINE.matcher(text == null ? "" : text);
        while (m.find()) {
            listed.add(new Listed(Integer.parseInt(m.group(1)), m.group(2) != null, m.group(3), m.group(4)));
        }
        return listed;
    }

    private String call(String tool, Map<String, Object> args) {
        return call(tool, args, BrowserToolPolicy.Context.system());
    }

    private String call(String tool, Map<String, Object> args, BrowserToolPolicy.Context context) {
        return system(c -> c.call(tool, args, context));
    }

    /** 在当前连接上做一次调用：连接状态的记账（答话了 = 连接好；断开 = 记下、清空标签页记录）都在这里。 */
    private <T> T system(Function<PlaywrightMcpClient, T> call) {
        ensureConnected();
        try {
            T result = call.apply(client);
            answered();
            return result;
        } catch (BrowserException.ToolFailed e) {
            // 工具报错，但 MCP 答话了：连接本身是好的
            answered();
            throw e;
        } catch (BrowserException.Disconnected e) {
            // 用过的连接断了：下一次使用时自动重连一次；这条连接一次都没成功过（就是那一次重连）：不再自己连
            boolean used = state.connected();
            disconnect();
            moveTo(BrowserState.DISCONNECTED, e.getMessage(), state.generation());
            retryLeft = used;
            throw e;
        }
    }

    /** 这条连接上 MCP 答话了：还在建立的话记为连上（第 N 次）。 */
    private void answered() {
        if (BrowserState.CONNECTING.equals(state.state())) {
            moveTo(BrowserState.CONNECTED, null, state.generation() + 1);
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

    /**
     * 建立连接（只由 {@link #ensureConnected()} 调用）。失败时记为断开、不留自动重连：之后的调用不再自己去连，免得
     * Chrome 反复弹窗、空转。
     */
    private void connect() {
        moveTo(BrowserState.CONNECTING, null, state.generation());
        try {
            clearOutputDir();
            for (Path root : launch.roots()) {
                Files.createDirectories(root);
            }
        } catch (IOException | UncheckedIOException e) {
            moveTo(BrowserState.DISCONNECTED, "准备浏览器 MCP 的目录失败：" + e.getMessage(), state.generation());
            retryLeft = false;
            // 记为断开（不是别的失败）：重新连接接口照旧 200 + 状态，申请 Agent 停下等你
            throw new BrowserException.Disconnected("准备浏览器 MCP 的目录失败：" + e.getMessage(), e);
        }
        try {
            client = connector.apply(launch);
        } catch (RuntimeException e) {
            disconnect();
            BrowserException.Disconnected failed = e instanceof BrowserException.Disconnected disconnected ? disconnected
                    : new BrowserException.Disconnected("连接浏览器失败：" + e.getMessage(), e);
            moveTo(BrowserState.DISCONNECTED, failed.getMessage(), state.generation());
            retryLeft = false;
            throw failed;
        }
    }

    private void disconnect() {
        tabs.clear();
        yours.clear();
        notices.clear();
        handed.clear();
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
