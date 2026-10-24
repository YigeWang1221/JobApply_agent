package jobapply.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jobapply.harness.policies.BrowserToolPolicy;

/**
 * 一个使用者（一个 Job 的申请线程、一个 Session 的调研）的浏览器会话（C011 方案 A）：自己的 MCP 会话（自己的「当前
 * 标签页」）、自己的标签页记录、弹窗、说明和转交。<b>锁按使用者</b>：方法都在这个对象的锁里，同一个使用者的调用一个接一个，
 * 不同使用者互不等待。共享的那条连接（服务进程、空闲会话、状态）在 {@link BrowserSession}。
 * <p>
 * 会话的当前页就是这个使用者在用的页，动作不再先 {@code select}：只有开页、切到自己的弹窗、用户转交、会话重建后找回
 * 自己的页时才 {@code select}（它会把页切到前台），切过去的页都开焦点模拟（{@link PlaywrightMcpClient#focusEmulation}）。
 * 每次动作前列一次自己会话里的标签页（不切换），核对当前页还是自己的：自己的页被关掉时 MCP 会把当前页换成旁边那一页，
 * 那一页可能是别人的。标签页按网址认（记下的序号只当提示），因为别人开页、关页会让全局序号移动。
 */
final class OwnerBrowser {

    /** {@code browser_tabs} 列表里的一行：{@code - 1: (current) [标题](网址)}。 */
    private static final Pattern TAB_LINE = Pattern.compile(
            "(?m)^- (\\d+):( \\(current\\))? \\[(.*)\\]\\((.*)\\)(?: \\[crashed\\])?$");

    /** 列表里的一个标签页（这个会话里的序号）。 */
    record Listed(int index, boolean current, String title, String url) {
    }

    /** 记在这个使用者名下的一页：最近一次看到的网址，和上次在列表里的序号（只当提示）。 */
    private static final class Tab {
        String url;
        int hint;

        Tab(String url, int hint) {
            this.url = url;
            this.hint = hint;
        }
    }

    /** 对共享连接的调用（连接状态的记账在 {@link BrowserSession} 里）。 */
    interface Calls {
        <T> T use(OwnerBrowser owner, Function<PlaywrightMcpClient, T> call);

        /** 别的使用者记下的网址（不含这个使用者），用来认「当前页被换成了别人的页」、排除别人的弹窗。 */
        Set<String> othersUrls(String owner);
    }

    final String owner;
    private final Calls calls;

    /** 这个使用者的 MCP 会话；属于第 {@link #clientSerial} 次连接。 */
    volatile PlaywrightMcpClient client;
    int clientSerial = -1;
    /** 记录属于第几次连接（{@link BrowserSession} 每次连上、断开都加一；对不上就清空）。 */
    private int serial = -1;
    /** 记在名下的页（新开的、点击弹出的、用户转交的），按开的顺序；给模型的序号就是这里的位置。 */
    private final List<Tab> tabs = new ArrayList<>();
    /** 正在用的那页（{@link #tabs} 里的一个），没有时为 null。 */
    private Tab current;
    /** MCP 会话的当前页是不是 {@link #current}（新会话、转交之后还不是，用之前先 select 过去）。 */
    private boolean selected;
    /** 这个会话上一次看到的标签页列表（点击后多出来的页 = 可能是弹窗）。 */
    private List<Listed> lastListed = List.of();
    private String notice;
    private BrowserSession.TabInfo handed;
    /** 给别的使用者看的网址快照（不拿这个对象的锁也能读）。 */
    private volatile Set<String> urls = Set.of();

    OwnerBrowser(String owner, Calls calls) {
        this.owner = owner;
        this.calls = calls;
    }

    /**
     * 连接换了（连上或断开）：记录全部作废，旧会话关掉。返回作废前有没有记录（调用中途连接换了时，调用方据此报断开）。
     */
    synchronized boolean sync(int serial) {
        if (this.serial == serial) {
            return false;
        }
        boolean had = !tabs.isEmpty() || handed != null;
        this.serial = serial;
        tabs.clear();
        current = null;
        selected = false;
        lastListed = List.of();
        notice = null;
        handed = null;
        publish();
        dropClient();
        return had;
    }

    /** 会话坏了（连接本身还好）：关掉会话，下次用时重建；记录留着，新会话里按网址找回自己的页。 */
    synchronized void dropClient() {
        PlaywrightMcpClient old = client;
        client = null;
        selected = false;
        if (old != null) {
            try {
                old.close();
            } catch (RuntimeException e) {
                // 已经断了
            }
        }
    }

    Set<String> urls() {
        return urls;
    }

    synchronized boolean ownsTab() {
        return current != null;
    }

    /** 新开一个标签页打开 {@code url}，它成为正在用的页，开焦点模拟，取快照。 */
    synchronized PageSnapshot open(String url) {
        List<Listed> listed = parse(call(BrowserToolPolicy.TABS, Map.of("action", "new", "url", url)));
        if (listed.stream().noneMatch(Listed::current)) {
            listed = list();
        }
        lastListed = listed;
        Listed opened = listed.stream().filter(Listed::current).findFirst().orElse(null);
        if (opened == null) {
            current = null;
            selected = false;
            publish();
            throw new BrowserSession.TabLost("新开的标签页没有出现在列表里，再试一次");
        }
        Tab tab = new Tab(opened.url(), opened.index());
        tabs.add(tab);
        current = tab;
        selected = true;
        publish();
        focus();
        return read();
    }

    synchronized PageSnapshot snapshot() {
        if (current == null) {
            throw new BrowserSession.TabLost("还没有你在用的标签页，不能取快照");
        }
        ensureCurrent();
        return read();
    }

    /**
     * 模型的一次操作（白名单在 {@link PlaywrightMcpClient#call} 里），操作后取快照。点击打开了自己的弹窗时切过去；
     * 动作超时时检查一次原因（{@link #afterTimeout}）。
     */
    synchronized PageSnapshot act(String tool, Map<String, Object> args, BrowserToolPolicy.Context context) {
        if (current == null) {
            throw new BrowserSession.TabLost("还没有你在用的标签页，不能操作");
        }
        ensureCurrent();
        String result;
        try {
            result = call(tool, args, context);
        } catch (BrowserException.ToolFailed e) {
            result = afterTimeout(tool, args, e);
        }
        if (BrowserToolPolicy.SNAPSHOT.equals(tool)) {
            return remember(PageSnapshot.parse(result));
        }
        if (BrowserToolPolicy.CLICK.equals(tool)) {
            PageSnapshot popup = popup(parse(result));
            if (popup != null) {
                return popup;
            }
        }
        return read();
    }

    synchronized String find(Map<String, Object> args, BrowserToolPolicy.Context context) {
        if (current == null) {
            throw new BrowserSession.TabLost("还没有你在用的标签页，不能操作");
        }
        ensureCurrent();
        return call(BrowserToolPolicy.FIND, args, context);
    }

    synchronized String takeNotice() {
        String taken = notice;
        notice = null;
        return taken;
    }

    synchronized BrowserSession.TabInfo takeHandover() {
        BrowserSession.TabInfo taken = handed;
        handed = null;
        return taken;
    }

    /** 自己的标签页（序号是自己的编号，从 0 开始），标出正在用的。关掉了的去掉。没有记录时不碰浏览器。 */
    synchronized List<BrowserSession.TabInfo> tabs() {
        if (tabs.isEmpty()) {
            return List.of();
        }
        List<Listed> listed = list();
        List<BrowserSession.TabInfo> out = new ArrayList<>();
        for (Tab tab : List.copyOf(tabs)) {
            Listed found = find(tab, listed);
            if (found == null) {
                forget(tab);
                continue;
            }
            tab.hint = found.index();
        }
        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            Listed found = find(tab, listed);
            out.add(new BrowserSession.TabInfo(i, found == null ? "" : found.title(), tab.url, tab == current, true));
        }
        return out;
    }

    /**
     * 切到自己的第 {@code index} 页（在自己的会话里 select，开焦点模拟），取快照，它成为正在用的页。
     *
     * @throws IllegalArgumentException 没有这一页，或者它已经不在了
     */
    synchronized PageSnapshot select(int index) {
        if (index < 0 || index >= tabs.size()) {
            throw new IllegalArgumentException("你没有序号 " + index + " 的标签页，先 browser_tabs list");
        }
        Tab tab = tabs.get(index);
        Listed found = find(tab, list());
        if (found == null) {
            forget(tab);
            throw new IllegalArgumentException("你的标签页 " + index + "（" + tab.url + "）已经不在了，先 browser_tabs list");
        }
        switchTo(tab, found);
        return read();
    }

    /**
     * 用户转交的一页（网址已经由调用方核对过）：记在名下；还没有正在用的页时它就是（下次用时 select 过去、开焦点模拟），
     * 已经有时不偷换脚下的页，由模型 select。返回给模型的序号是自己的编号。
     */
    synchronized BrowserSession.TabInfo handOver(String title, String url, int hint) {
        Tab tab = tabs.stream().filter(t -> t.url.equals(url)).findFirst().orElse(null);
        if (tab == null) {
            tab = new Tab(url, hint);
            tabs.add(tab);
        }
        if (current == null) {
            current = tab;
            selected = false;
        }
        publish();
        BrowserSession.TabInfo info = new BrowserSession.TabInfo(tabs.indexOf(tab), title, url, false, true);
        handed = info;
        return info;
    }

    /** 正在用的那页的网址（别人想要这一页时拒绝），没有时为 null。 */
    String currentUrl() {
        return currentUrl;
    }

    private volatile String currentUrl;

    /**
     * 保证 MCP 会话的当前页是自己正在用的那页。会话新建（或重建）时按网址在自己的会话里找回来 select 过去；平时只列一次
     * 核对：当前页的网址对得上就行；变成了别人记下的网址，或者标签页变少了、自己的网址也不在了 = 自己的页被关掉、MCP
     * 换到了旁边那页 → {@link BrowserSession.TabLost}；否则认作自己的页换了网址（登录、下一步），留一句说明。
     */
    private void ensureCurrent() {
        if (!selected) {
            Listed found = find(current, list());
            if (found == null) {
                forget(current);
                throw new BrowserSession.TabLost(BrowserSession.LOST);
            }
            switchTo(current, found);
            return;
        }
        int before = lastListed.size();
        List<Listed> listed = list();
        Listed now = listed.stream().filter(Listed::current).findFirst().orElse(null);
        if (now == null) {
            throw lose();
        }
        if (now.url().equals(current.url)) {
            current.hint = now.index();
            return;
        }
        boolean mineStillThere = listed.stream().anyMatch(t -> t.url().equals(current.url));
        if (calls.othersUrls(owner).contains(now.url()) || (!mineStillThere && listed.size() < before)) {
            throw lose();
        }
        String was = current.url;
        current.url = now.url();
        current.hint = now.index();
        publish();
        notice = "你在用的标签页网址变了（原来 " + was + "，现在 " + now.url() + "），系统认作同一页；"
                + "不是你的页面时用 browser_tabs list 看一下。";
    }

    /** 自己的页被关掉了：去掉记录，这次没有操作。 */
    private RuntimeException lose() {
        forget(current);
        selected = false;
        return new BrowserSession.TabLost(BrowserSession.LOST);
    }

    /**
     * 点击之后：返回里的标签页列表比上次多出来的页里，有这一页打开的弹窗（{@code opener} 是它）就切过去。别人同时开的页
     * 也会多出来，所以一定要核对 opener，不按「多出来一页」猜（真实浏览器上猜过：同时点击时把别人的弹窗收走了）。
     * {@code target=_blank}（Chrome 默认 noopener）的新页 Playwright 也报 opener（CDP 的 openerId），手动检查里核对过。
     */
    private PageSnapshot popup(List<Listed> listed) {
        if (listed.isEmpty()) {
            return null;
        }
        List<String> known = new ArrayList<>(lastListed.stream().map(Listed::url).toList());
        List<Listed> added = new ArrayList<>();
        for (Listed tab : listed) {
            if (!known.remove(tab.url())) {
                added.add(tab);
            }
        }
        lastListed = listed;
        Set<String> others = calls.othersUrls(owner);
        added.removeIf(tab -> tab.current() || others.contains(tab.url()));
        if (added.isEmpty()) {
            return null;
        }
        List<String> mine = calls.use(this, PlaywrightMcpClient::popups);
        Listed opened = added.stream().filter(tab -> mine.contains(tab.url())).reduce((a, b) -> b).orElse(null);
        if (opened == null) {
            return null;
        }
        Tab tab = new Tab(opened.url(), opened.index());
        tabs.add(tab);
        switchTo(tab, opened);
        notice = "这次点击打开了新的标签页（" + opened.url() + "），系统已经切过去，下面是新标签页的快照；"
                + "原来那页还开着，要回去用 browser_tabs。";
        return read();
    }

    /**
     * 在自己的会话里 select 到 {@code found}（序号来自这个会话刚列出的列表），核对切到的网址；别人恰好开关了页、序号挪了
     * 时重新列一次再试一次。然后开焦点模拟。
     */
    private void switchTo(Tab tab, Listed found) {
        Listed target = found;
        for (int attempt = 0; ; attempt++) {
            List<Listed> after = parse(call(BrowserToolPolicy.TABS, Map.of("action", "select", "index", target.index())));
            Listed now = after.stream().filter(Listed::current).findFirst().orElse(null);
            if (now == null || now.url().equals(tab.url) || attempt > 0) {
                break;
            }
            target = find(tab, list());
            if (target == null) {
                forget(tab);
                throw new BrowserSession.TabLost(BrowserSession.LOST);
            }
        }
        tab.hint = target.index();
        current = tab;
        selected = true;
        publish();
        focus();
    }

    /** 开焦点模拟；失败不挡操作（最坏是后台页上点击超时，错误照常交给模型）。 */
    private void focus() {
        try {
            calls.use(this, c -> {
                c.focusEmulation();
                return null;
            });
        } catch (BrowserException.ToolFailed e) {
            // 页面可能还在加载；下次切过来时再开
        }
    }

    /**
     * 点击、输入、选择动作超时（C006-fix-real-run 第 20 条）：先把这页切到前台一次（C011 第 5.1 节：同站点的后台页上
     * 弹出的对话框可能出不来、把点击卡住），再用固定模板检查一次原因（只读），把实情写进错误。点击的目标只是被别的层
     * 盖住时，在同一个 ref 上直接点一次，留说明。调用方已经过了点击守卫。
     */
    private String afterTimeout(String tool, Map<String, Object> args, BrowserException.ToolFailed failed) {
        boolean click = BrowserToolPolicy.CLICK.equals(tool);
        if (!(click || BrowserToolPolicy.TYPE.equals(tool) || BrowserToolPolicy.SELECT_OPTION.equals(tool))
                || !(args.get("target") instanceof String ref) || !PlaywrightMcpClient.isActionTimeout(failed.getMessage())) {
            throw failed;
        }
        try {
            calls.use(this, c -> {
                c.bringToFront();
                return null;
            });
        } catch (BrowserException.ToolFailed e) {
            // 切不过去也照常检查
        }
        PlaywrightMcpClient.Diagnosis diagnosis;
        try {
            diagnosis = calls.use(this, c -> c.diagnose(ref));
        } catch (BrowserException.ToolFailed e) {
            throw failed;
        }
        String timedOut = tool + " 返回错误：动作超时，元素在等待时间内一直不能操作，这次操作没有发生（不是浏览器的安全限制）。"
                + "系统把这一页切到了前台。" + diagnosis.describe();
        if (!click || !diagnosis.coveredButVisible()) {
            throw new BrowserException.ToolFailed(timedOut);
        }
        String result;
        try {
            result = calls.use(this, c -> c.clickThrough(ref));
        } catch (BrowserException.ToolFailed e) {
            throw new BrowserException.ToolFailed(timedOut + "系统又在这个元素上直接点了一次，也失败了：" + e.getMessage());
        }
        notice = "这个元素被别的层盖住，普通点击超时了；系统在同一个元素上直接点了一次（不经过屏幕坐标）。"
                + diagnosis.describe() + "下面是点击之后的快照，核对一下是不是真的生效了（例如多了一张新卡片、弹出层打开了）；"
                + "没有生效时照上面的说明处理，不要自己猜原因。";
        return result;
    }

    /** 自己会话里的标签页列表（不切换）。 */
    private List<Listed> list() {
        List<Listed> listed = parse(call(BrowserToolPolicy.TABS, Map.of("action", "list")));
        lastListed = listed;
        return listed;
    }

    /** 按网址找这一页：网址相同、序号也是上次那个的优先；否则网址相同、不是别人在用的第一个。 */
    private Listed find(Tab tab, List<Listed> listed) {
        if (tab == null) {
            return null;
        }
        List<Listed> same = listed.stream().filter(t -> t.url().equals(tab.url)).toList();
        return same.stream().filter(t -> t.index() == tab.hint).findFirst()
                .orElse(same.isEmpty() ? null : same.get(0));
    }

    private void forget(Tab tab) {
        tabs.remove(tab);
        if (current == tab) {
            current = null;
            selected = false;
        }
        publish();
    }

    private PageSnapshot read() {
        return remember(PageSnapshot.parse(call(BrowserToolPolicy.SNAPSHOT, Map.of())));
    }

    private PageSnapshot remember(PageSnapshot page) {
        if (current != null && page.url() != null && !page.url().isBlank()) {
            current.url = page.url();
            publish();
        }
        return page;
    }

    private void publish() {
        urls = Set.copyOf(tabs.stream().map(t -> t.url).toList());
        currentUrl = current == null ? null : current.url;
    }

    private String call(String tool, Map<String, Object> args) {
        return call(tool, args, BrowserToolPolicy.Context.system());
    }

    private String call(String tool, Map<String, Object> args, BrowserToolPolicy.Context context) {
        return calls.use(this, c -> c.call(tool, args, context));
    }

    static List<Listed> parse(String text) {
        List<Listed> listed = new ArrayList<>();
        Matcher m = TAB_LINE.matcher(text == null ? "" : text);
        while (m.find()) {
            listed.add(new Listed(Integer.parseInt(m.group(1)), m.group(2) != null, m.group(3), m.group(4)));
        }
        return listed;
    }
}
