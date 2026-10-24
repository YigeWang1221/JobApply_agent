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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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
 * 重连之后的「当前标签页」可能是用户自己的标签页，所以重连后要先 {@link #open(String, String)}（或者用户转交了一页，
 * {@link #handOver}），再取快照。
 * 建立连接和关闭时清空 MCP 的输出目录（{@link BrowserLaunch#outputDir()}），里面可能有登录后的页面内容。
 * <p>
 * 每个使用者（一个 Job 的申请线程、一个 Session 的调研）有自己的标签页：正在用的那一页，加上它点开的别的页。只按网址认
 * 标签页（C006-fix-runtime-2）：记下的序号和网址都对上，或者网址在列表里只出现一次。每次操作前先列一次标签页，核对
 * 记下的那一页还在（{@link #focus}）；认不出就不操作（不会落到别的任务或用户自己的标签页上），抛出 {@link TabLost}。
 * 点击打开了新标签页时（Playwright MCP 的返回里附了标签页列表），新标签页记给这个使用者，系统切过去。
 * 申请 Agent 可以用 {@link #tabs} / {@link #select} 在<b>自己的</b>标签页之间切换：它新开的、它点击弹出的、用户转交给它的
 * （{@link #handOver}）。不属于它的标签页（用户自己的、别的使用者的、没人占用的）不列给它、不切过去、不取快照；归属只在
 * 内存里，重启或断开后不按网址自动认回（C006-fix-browser 第 2 节，用户 2026-10-23）。没人占用的标签页只通过
 * {@link #unowned()} 列给界面，由用户决定交不交。
 */
public final class BrowserSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BrowserSession.class);

    private final Function<BrowserLaunch, PlaywrightMcpClient> connector;
    private final BrowserLaunch launch;
    private PlaywrightMcpClient client;
    /** {@code browser_tabs} 列表里的一行：{@code - 1: (current) [标题](网址)}。 */
    private static final Pattern TAB_LINE = Pattern.compile(
            "(?m)^- (\\d+):( \\(current\\))? \\[(.*)\\]\\((.*)\\)(?: \\[crashed\\])?$");

    /** 一个使用者的标签页：序号和最近一次看到的网址。 */
    private record Tab(int index, String url) {
    }

    /** 列表里的一个标签页。 */
    private record Listed(int index, boolean current, String title, String url) {
    }

    /**
     * 一个标签页（序号、标题、网址）。给模型的列表里只有这个使用者自己的；{@link #unowned()} 给界面的列表里是没人占用的。
     *
     * @param inUse 这个使用者现在正在用的那一页（界面的列表里总是 false）
     */
    public record TabInfo(int index, String title, String url, boolean inUse) {
    }

    /** 用户要转交的标签页已经不在、网址变了，或者已经有主人了：没有转交（接口 409）。 */
    public static final class HandoverRefused extends RuntimeException {

        HandoverRefused(String message) {
            super(message);
        }
    }

    /** 这个使用者的标签页认不出了，或者它还没有标签页：没有在别的标签页上操作。 */
    public static final class TabLost extends IllegalStateException {

        TabLost(String message) {
            super(message);
        }
    }

    /** 使用者 → 它正在用的标签页（当前连接上）。 */
    private final Map<String, Tab> tabs = new HashMap<>();
    /** 使用者 → 它的别的标签页（点开的新标签页、切走之前用的那页）。 */
    private final Map<String, List<Tab>> others = new HashMap<>();
    /** 使用者 → 还没交给它的说明（点击打开了新标签页、系统切过去了）。 */
    private final Map<String, String> notices = new HashMap<>();
    /** 使用者 → 用户刚转交给它、还没告诉模型的标签页（{@link #takeHandover}）。 */
    private final Map<String, TabInfo> handed = new HashMap<>();
    /**
     * 使用者 → 它的主站点（{@link BrowserToolPolicy#siteOf}）：{@link #open} 打开的网址和落地网址，{@code browser_navigate}
     * 之后换成导航去的。记下的序号上的标签页换了网址、但还在这些站点上时，仍认作它的（{@link #claim} 第 ③ 条）。
     */
    private final Map<String, Set<String>> homes = new HashMap<>();
    /** 上一次列出来的标签页个数。 */
    private int tabCount;
    /**
     * 最近这次列出来的个数比上一次少（有页被关、序号可能整体挪了）：这一次 {@link #claim} 只按网址认，认不出的记录去掉
     * （C006-fix-runtime-2 最终审查第 2 条）。
     */
    private boolean shrank;
    /** 当前标签页属于哪个使用者；不知道时为 null。 */
    private String tabOwner;
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
     * 新开一个标签页打开页面，再取一次快照。新标签页随即成为当前标签页，不动用户已经打开的标签页
     * （远程调试模式下，已打开的标签页也会被收进来）。这个使用者原来的标签页（还认得出的话）记成它的别的标签页。
     */
    public synchronized PageSnapshot open(String url, String jobId) {
        tabOwner = null;
        Tab previous = tabs.remove(jobId);
        if (previous != null) {
            others.computeIfAbsent(jobId, k -> new ArrayList<>()).add(previous);
        }
        List<Listed> listed = parseTabs(call(BrowserToolPolicy.TABS, Map.of("action", "new", "url", url)));
        if (listed.stream().noneMatch(Listed::current)) {
            listed = parseTabs(call(BrowserToolPolicy.TABS, Map.of("action", "list")));
        }
        // 和上次比的是新开那页以外的个数
        shrank = listed.size() - 1 < tabCount;
        tabCount = listed.size();
        Listed opened = listed.stream().filter(Listed::current).findFirst().orElse(null);
        if (opened != null) {
            // 先按其余的标签页刷新所有人的序号（用户可能关掉了前面的标签页），再去掉序号正好是新标签页的过时记录
            claim(listed.stream().filter(tab -> tab != opened).toList());
            release(opened.index());
            tabs.put(jobId, at(opened));
        }
        homes.put(jobId, sites(url, opened == null ? null : opened.url()));
        tabOwner = jobId;
        return read(jobId);
    }

    private static Set<String> sites(String... urls) {
        Set<String> out = new HashSet<>();
        for (String url : urls) {
            String site = url == null ? null : BrowserToolPolicy.siteOf(url);
            if (site != null) {
                out.add(site);
            }
        }
        return out;
    }

    /**
     * 只读：取这个 Job 自己新开的那个标签页的快照。标签页不是它的（还没新开过，或属于另一个 Job）时拒绝，
     * 免得读到用户自己的标签页或别的岗位的页面。
     */
    public synchronized PageSnapshot snapshot(String jobId) {
        if (!ownsTab(jobId)) {
            throw new TabLost("当前标签页不是 Job " + jobId + " 新开的岗位页，不能取快照");
        }
        focus(jobId);
        return read(jobId);
    }

    /**
     * 模型的一次浏览器操作：先确认当前标签页是这个 Job 新开的，再按 {@code context} 过白名单并发出，最后取一次快照返回
     * （写操作之后系统自己取快照，交给守卫和模型）。{@code args} 必须已经去掉模型多给的参数
     * （{@link jobapply.harness.policies.FieldIntent}、{@link jobapply.harness.policies.SubmitGuard.ClickIntent}）；
     * 点击和填写的守卫由调用方先判断。{@code browser_snapshot} 直接返回这次的快照，不再多取一次。
     * 点击打开了新标签页时切过去，返回新标签页的快照（{@link #takeNotice} 说明一次）。
     * 连接断开时抛出 {@link BrowserException.Disconnected}，标签页不再属于任何 Job。
     */
    public synchronized PageSnapshot act(String jobId, String tool, Map<String, Object> args,
                                         BrowserToolPolicy.Context context) {
        requireOwnTab(jobId);
        int before = tabCount;
        String result = call(tool, args, context);
        if (BrowserToolPolicy.SNAPSHOT.equals(tool)) {
            return remember(jobId, PageSnapshot.parse(result));
        }
        if (BrowserToolPolicy.NAVIGATE.equals(tool) && args.get("url") instanceof String target) {
            PageSnapshot landed = read(jobId);
            homes.put(jobId, sites(target, landed.url()));
            return landed;
        }
        if (BrowserToolPolicy.CLICK.equals(tool)) {
            List<Listed> listed = parseTabs(result);
            if (before > 0 && listed.size() > before) {
                Listed opened = listed.get(listed.size() - 1);
                tabCount = listed.size();
                notices.put(jobId, "这次点击打开了新的标签页（" + opened.url() + "），系统已经切过去，下面是新标签页的快照；"
                        + "原来那页还开着，要回去用 browser_tabs。");
                return switchTo(jobId, opened);
            }
        }
        return read(jobId);
    }

    /**
     * 模型的 {@code browser_find}：只读，返回找到的元素。同样先确认标签页属于这个 Job。只留 MCP 原文的
     * {@code ### Result} 小节（{@link #findResult}）：原文里还可能有 {@code ### Open tabs}（所有标签页的标题和网址）、
     * {@code ### Page} 等，不能交给模型。
     */
    public synchronized String find(String jobId, Map<String, Object> args, BrowserToolPolicy.Context context) {
        requireOwnTab(jobId);
        return findResult(call(BrowserToolPolicy.FIND, args, context));
    }

    /**
     * MCP 原文里 {@code ### Result} 小节的正文（到下一个行首 {@code ### } 为止）；没有这一节时为空串。@playwright/mcp
     * 0.0.83 的小节顺序是 Error / Result / Ran Playwright code / Open tabs / Page / Modal state / Events，任何一个标签页
     * 的标题变了都会带上 Open tabs 和 Page；find 的结果行都以 {@code - } 或缩进开头，不会是行首 {@code ### }。
     */
    static String findResult(String raw) {
        if (raw == null) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        boolean inResult = false;
        for (String line : raw.replace("\r\n", "\n").split("\n", -1)) {
            if (line.startsWith("### ")) {
                inResult = "Result".equals(line.substring(4).trim());
            } else if (inResult) {
                kept.add(line);
            }
        }
        return String.join("\n", kept).strip();
    }

    /** 上一次操作留给这个使用者的说明（例如「点击打开了新标签页，系统切过去了」），取一次就清掉；没有时为 null。 */
    public synchronized String takeNotice(String owner) {
        return notices.remove(owner);
    }

    /**
     * 这个使用者自己的标签页（确定认出来的：它新开的、它点击弹出的、用户转交的）。用户自己的、别的使用者的、没人占用的
     * 标签页不列出，记录认不出了的（「可能是它的」）也不列出。只列，不切换。
     */
    public synchronized List<TabInfo> tabs(String owner) {
        List<Listed> listed = list();
        return mine(owner, listed, claim(listed));
    }

    /**
     * 切到这个使用者自己的一个标签页（{@link #tabs} 里的序号），取快照。原来正在用的那页记成它的别的标签页。
     *
     * @throws IllegalArgumentException 序号不是它自己的标签页
     */
    public synchronized PageSnapshot select(String owner, int index) {
        List<Listed> listed = list();
        boolean allowed = mine(owner, listed, claim(listed)).stream().anyMatch(t -> t.index() == index);
        if (!allowed) {
            throw new IllegalArgumentException("标签页 " + index + " 不能选：只能选 browser_tabs list 列出来的序号");
        }
        Listed target = listed.stream().filter(t -> t.index() == index).findFirst().orElseThrow();
        return switchTo(owner, target);
    }

    /**
     * 没有确定主人的标签页（序号、标题、网址），<b>只给界面用</b>：用户从里面挑一页交给某个申请（{@link #handOver}）。
     * 不进任何工具结果。只列，不切换、不取快照。
     */
    public synchronized List<TabInfo> unowned() {
        List<Listed> listed = list();
        Map<Integer, Claim> claimed = claim(listed);
        return listed.stream().filter(tab -> {
            Claim holder = claimed.get(tab.index());
            return holder == null || !holder.sure();
        }).map(tab -> new TabInfo(tab.index(), tab.title(), tab.url(), false)).toList();
    }

    /**
     * 用户在工作台上把一个标签页交给这个使用者。再列一次核对：序号还在、网址还是界面上看到的 {@code url}、没有确定的主人，
     * 才记给它（别的记录里写着这个序号的去掉），成为它正在用的那页，并记一条待告诉模型的转交（{@link #takeHandover}）。
     * 不切换、不取快照：模型下一次用浏览器时才读。正在用的那页记成它的别的标签页。
     *
     * @throws HandoverRefused 标签页不在了、网址变了，或者已经属于某个使用者
     */
    public synchronized TabInfo handOver(String owner, int index, String url) {
        List<Listed> listed = list();
        Map<Integer, Claim> claimed = claim(listed);
        Listed target = listed.stream().filter(tab -> tab.index() == index).findFirst()
                .orElseThrow(() -> new HandoverRefused("标签页 " + index + " 已经不在了，刷新列表再选"));
        if (url == null || !url.equals(target.url())) {
            throw new HandoverRefused("标签页 " + index + " 的网址变了（现在是 " + target.url() + "），刷新列表再选");
        }
        Claim holder = claimed.get(index);
        if (holder != null && holder.sure()) {
            throw new HandoverRefused("标签页 " + index + " 已经属于"
                    + (holder.owner().equals(owner) ? "这个申请" : "别的任务") + "，不能再交");
        }
        release(index);
        if (tabs.containsKey(owner)) {
            // 它正在用别的页（可能正在跑）：不偷换它脚下的页面（旧快照的 ref 会落到新页上），记成它的别的标签页，
            // 由模型 browser_tabs select 切过去
            others.computeIfAbsent(owner, k -> new ArrayList<>()).add(at(target));
        } else {
            tabs.put(owner, at(target));
        }
        TabInfo info = new TabInfo(target.index(), target.title(), target.url(), false);
        handed.put(owner, info);
        return info;
    }

    /** 用户转交给这个使用者、还没告诉模型的标签页，取一次就清掉；没有时为 null。 */
    public synchronized TabInfo takeHandover(String owner) {
        return handed.remove(owner);
    }

    /**
     * 这个使用者在当前连接上有没有自己新开的标签页（连接断开、重连、应用重启之后都是 false）。
     * 有、但不是当前标签页时，下一次调用会先切过去。
     */
    public synchronized boolean ownsTab(String jobId) {
        return jobId != null && tabs.containsKey(jobId);
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

    private void requireOwnTab(String jobId) {
        if (!ownsTab(jobId)) {
            throw new TabLost("当前标签页不是 Job " + jobId + " 新开的岗位页，不能操作");
        }
        focus(jobId);
    }

    /**
     * 确认这个使用者正在用的标签页还在，并让它成为当前标签页。先列出标签页，只按网址认（{@link #claim}）：记下的序号上
     * 还是记下的网址，或者网址在没认领的标签页里只出现一次。认不出：这个使用者不再有正在用的标签页，抛出 {@link TabLost}，
     * 不在别的标签页上操作。
     */
    private void focus(String owner) {
        List<Listed> listed = list();
        Map<Integer, Claim> claimed = claim(listed);
        Tab tab = tabs.get(owner);
        Claim holder = tab == null ? null : claimed.get(tab.index());
        if (tab == null || holder == null || !holder.sure() || !holder.owner().equals(owner)) {
            // 这个使用者不再有正在用的标签页（下一次重新打开，或者用户转交）；认不出的那条记录留作「可能是它的」
            Tab lost = tabs.remove(owner);
            if (lost != null && lost.index() < listed.size()) {
                others.computeIfAbsent(owner, k -> new ArrayList<>()).add(lost);
            }
            if (owner.equals(tabOwner)) {
                tabOwner = null;
            }
            throw new TabLost("找不到 " + owner + " 的标签页（可能在 Chrome 里被关掉了，或者页面自己换了网址），"
                    + "没有在别的标签页上操作");
        }
        int index = tab.index();
        Listed found = listed.stream().filter(t -> t.index() == index).findFirst().orElseThrow();
        if (!found.current()) {
            tabOwner = null;
            call(BrowserToolPolicy.TABS, Map.of("action", "select", "index", found.index()));
        }
        tabOwner = owner;
    }

    /** 列出标签页，记下个数（覆盖之前先和上次比，见 {@link #shrank}）。 */
    private List<Listed> list() {
        List<Listed> listed = parseTabs(call(BrowserToolPolicy.TABS, Map.of("action", "list")));
        shrank = listed.size() < tabCount;
        tabCount = listed.size();
        return listed;
    }

    /**
     * 标签页列表里的一页归谁。
     *
     * @param sure true：按网址认出来的，可以操作；false：网址认不出了、但记下的序号上还有标签页，只当作「可能是它的」
     *             （挡住别的使用者；不列给它自己的模型，下一次 ①②③ 认出来才又算它的；界面的 {@link #unowned()} 里列出，
     *             由用户决定要不要转交）
     */
    private record Claim(String owner, boolean sure) {
    }

    /**
     * 按网址认出各个使用者的标签页，顺手更新记下的序号和网址。① 序号和网址都对上的；② 网址在剩下的标签页里只出现一次的；
     * ③ 记下的序号上的标签页没被别人认领、和这条记录的网址或者这个使用者的主站点（{@link #homes}）同一站点的——用户在这一页
     * 登录、点下一步、单页应用换了路由，网址变了但还是它（C006-fix-runtime-2 回归修正），只有一条认不出的记录写着这个序号时
     * 才成立；④ 其余认不出的记录留着，记下的序号上还有没人认领的标签页时算作「可能是它的」；序号已经不在列表里的记录去掉。
     * 这次列出来的个数比上次少时（{@link #shrank}，序号可能整体挪了，挪过来的可能是同一站点上别人的页）只用 ① ②，
     * 认不出的记录全部去掉。返回 序号 → 归属。
     */
    private Map<Integer, Claim> claim(List<Listed> listed) {
        Map<Integer, Listed> byIndex = new HashMap<>();
        listed.forEach(t -> byIndex.put(t.index(), t));
        Map<Integer, Claim> claimed = new HashMap<>();
        Map<Tab, Listed> matched = new IdentityHashMap<>();
        List<Map.Entry<String, Tab>> all = new ArrayList<>();
        tabs.forEach((owner, tab) -> all.add(Map.entry(owner, tab)));
        others.forEach((owner, list) -> list.forEach(tab -> all.add(Map.entry(owner, tab))));
        for (Map.Entry<String, Tab> entry : all) {
            Tab tab = entry.getValue();
            listed.stream().filter(t -> t.index() == tab.index() && t.url().equals(tab.url())
                    && !claimed.containsKey(t.index())).findFirst().ifPresent(t -> {
                        claimed.put(t.index(), new Claim(entry.getKey(), true));
                        matched.put(tab, t);
                    });
        }
        for (Map.Entry<String, Tab> entry : all) {
            Tab tab = entry.getValue();
            if (matched.containsKey(tab)) {
                continue;
            }
            List<Listed> same = listed.stream()
                    .filter(t -> t.url().equals(tab.url()) && !claimed.containsKey(t.index())).toList();
            if (same.size() == 1) {
                claimed.put(same.get(0).index(), new Claim(entry.getKey(), true));
                matched.put(tab, same.get(0));
            }
        }
        // 个数变少了（序号可能整体挪了）：③ ④ 都不信序号，认不出的记录去掉
        Function<Tab, Boolean> kept = tab -> !shrank && byIndex.containsKey(tab.index());
        for (Map.Entry<String, Tab> entry : all) {
            Tab tab = entry.getValue();
            Listed there = byIndex.get(tab.index());
            if (shrank || matched.containsKey(tab) || there == null || claimed.containsKey(there.index())) {
                continue;
            }
            // 只有这一条认不出的记录写着这个序号时才成立：两条争同一页就都不认
            long rivals = all.stream().filter(e -> !matched.containsKey(e.getValue())
                    && e.getValue().index() == tab.index()).count();
            if (rivals == 1 && sameSite(entry.getKey(), tab, there)) {
                claimed.put(there.index(), new Claim(entry.getKey(), true));
                matched.put(tab, there);
            }
        }
        for (Map.Entry<String, Tab> entry : all) {
            Tab tab = entry.getValue();
            if (!matched.containsKey(tab) && kept.apply(tab)) {
                claimed.putIfAbsent(tab.index(), new Claim(entry.getKey(), false));
            }
        }
        tabs.values().removeIf(tab -> !matched.containsKey(tab) && !kept.apply(tab));
        tabs.replaceAll((owner, tab) -> matched.containsKey(tab) ? at(matched.get(tab)) : tab);
        others.replaceAll((owner, list) -> list.stream()
                .filter(tab -> matched.containsKey(tab) || kept.apply(tab))
                .map(tab -> matched.containsKey(tab) ? at(matched.get(tab)) : tab)
                .collect(Collectors.toCollection(ArrayList::new)));
        others.values().removeIf(List::isEmpty);
        return claimed;
    }

    /** 规则 ③：标签页 {@code there} 和记录 {@code tab} 的网址同一站点，或者在 {@code owner} 的主站点上。 */
    private boolean sameSite(String owner, Tab tab, Listed there) {
        String site = BrowserToolPolicy.siteOf(there.url());
        if (site == null) {
            return false;
        }
        return site.equals(BrowserToolPolicy.siteOf(tab.url())) || homes.getOrDefault(owner, Set.of()).contains(site);
    }

    /** 这个序号现在明确归新的使用者了：别的记录里还写着这个序号的，都是过时的，去掉。 */
    private void release(int index) {
        tabs.values().removeIf(tab -> tab.index() == index);
        others.values().forEach(list -> list.removeIf(tab -> tab.index() == index));
        others.values().removeIf(List::isEmpty);
    }

    private static Tab at(Listed listed) {
        return new Tab(listed.index(), listed.url());
    }

    /** 确定是这个使用者的标签页（认页规则 ①②③ 认出来的）。「可能是它的」、没人占用的、别人的都不算。 */
    private List<TabInfo> mine(String owner, List<Listed> listed, Map<Integer, Claim> claimed) {
        Tab inUse = tabs.get(owner);
        List<TabInfo> out = new ArrayList<>();
        for (Listed tab : listed) {
            Claim holder = claimed.get(tab.index());
            if (holder != null && holder.sure() && owner.equals(holder.owner())) {
                out.add(new TabInfo(tab.index(), tab.title(), tab.url(),
                        inUse != null && inUse.index() == tab.index()));
            }
        }
        return out;
    }

    /** 把 {@code target} 记成这个使用者正在用的标签页，切过去、取快照。原来那页记成它的别的标签页。 */
    private PageSnapshot switchTo(String owner, Listed target) {
        Tab previous = tabs.remove(owner);
        List<Tab> mine = others.computeIfAbsent(owner, k -> new ArrayList<>());
        mine.removeIf(tab -> tab.index() == target.index());
        if (previous != null && previous.index() != target.index()) {
            mine.add(previous);
        }
        if (mine.isEmpty()) {
            others.remove(owner);
        }
        release(target.index());
        tabs.put(owner, at(target));
        tabOwner = null;
        call(BrowserToolPolicy.TABS, Map.of("action", "select", "index", target.index()));
        tabOwner = owner;
        return read(owner);
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
            listed.add(new Listed(Integer.parseInt(m.group(1)), m.group(2) != null, m.group(3), m.group(4)));
        }
        return listed;
    }

    private String call(String tool, Map<String, Object> args) {
        return call(tool, args, BrowserToolPolicy.Context.system());
    }

    private String call(String tool, Map<String, Object> args, BrowserToolPolicy.Context context) {
        ensureConnected();
        try {
            String result = client.call(tool, args, context);
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
        tabOwner = null;
        tabs.clear();
        others.clear();
        notices.clear();
        handed.clear();
        homes.clear();
        tabCount = 0;
        shrank = false;
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
