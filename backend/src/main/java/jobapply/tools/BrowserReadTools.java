package jobapply.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import jobapply.domain.AgentRun;
import jobapply.harness.policies.BrowserToolPolicy;
import jobapply.harness.policies.GuardDecision;
import jobapply.harness.policies.SnapshotRedactor;
import jobapply.harness.policies.SubmitGuard;
import jobapply.harness.policies.ToolNotAllowedException;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Agent 在用户 Chrome 里自己的那个只读标签页（BrowserRead；Session 的调研和工作台的后续段共用，原来的 {@code ReadOnlyTab}）：
 * 导航、快照、查找、点击（过 {@link SubmitGuard#readOnly}，申请 / 提交类按钮一律拒绝）、等待、列出和切换标签页。
 * 给哪些工具由 Mode 的工具集定（{@link #RESEARCH}、{@link #FOLLOWUP}）。网址过 {@link WebFetch#checkUrl}；浏览器断开或
 * 没回应时这一次调用失败，原话交给模型，不结束运行。
 */
public final class BrowserReadTools {

    /** 调研时可以用的浏览器工具（导航、标签页另有）。 */
    public static final Set<String> RESEARCH = Set.of(BrowserToolPolicy.SNAPSHOT, BrowserToolPolicy.FIND,
            BrowserToolPolicy.CLICK, BrowserToolPolicy.TYPE, BrowserToolPolicy.PRESS_KEY, BrowserToolPolicy.WAIT_FOR);
    /** 后续段只读查看用的浏览器工具：不给输入、按键、填表和上传。 */
    public static final Set<String> FOLLOWUP = Set.of(BrowserToolPolicy.SNAPSHOT, BrowserToolPolicy.FIND,
            BrowserToolPolicy.CLICK, BrowserToolPolicy.WAIT_FOR);

    static final String TABS_HEADER = "Chrome 里所有的标签页（序号是浏览器里的序号，select 用它）。（你的）= 你开的、点击弹出的、"
            + "用户交给你的；（别的任务在用）= 别的申请或调研正在用，不要碰；其余是用户自己开着的页：\n";

    private final BrowserSession browser;
    private final WebFetch fetch;
    /** 标签页的使用者（Session id，或者后续段的 {@code followup:<job>}）。 */
    private final String owner;
    private PageSnapshot latest;
    /** 每次拿到新的页面时（Session 存一份，之后 add_lead 能取到）。 */
    private Consumer<PageSnapshot> onPage = ignored -> {
    };

    private BrowserReadTools(RunContext ctx) {
        this.browser = ctx.services().browser();
        this.fetch = ctx.services().fetch();
        this.owner = ctx.tabOwner();
    }

    /** 这次运行的只读标签页（同一次运行里只有一份）。 */
    public static BrowserReadTools of(RunContext ctx) {
        return ctx.slot(BrowserReadTools.class, () -> new BrowserReadTools(ctx));
    }

    /**
     * 只读浏览器工具：导航（{@code navigate} 是这个 Mode 的说明）、标签页和 {@code allowed} 里的那几个。
     *
     * @param onPage 每次拿到新的页面时调用；为 null 时不做别的
     */
    public static List<Tool> create(RunContext ctx, ToolDefinition navigate, Set<String> allowed,
                                    Consumer<PageSnapshot> onPage) {
        BrowserReadTools tab = of(ctx);
        if (onPage != null) {
            tab.onPage = onPage;
        }
        List<Tool> tools = new ArrayList<>();
        tools.add(Tool.of(navigate, tab::navigate));
        tools.add(Tool.of(BrowserTools.tabsDefinition(), tab::tabs));
        BrowserTools.definitions().stream().filter(d -> allowed.contains(d.name()))
                .forEach(d -> tools.add(Tool.of(d, tab::handle)));
        return tools;
    }

    /** 系统自己在这个标签页打开了页面（{@code web_fetch} 改用浏览器读时）：之后的操作以它为准。 */
    public void saw(PageSnapshot page) {
        latest = page;
    }

    /** 在自己的标签页打开网址（第一次时新开一个）。 */
    public PageSnapshot open(String url) {
        if (!browser.ownsTab(owner)) {
            return browser.open(url, owner);
        }
        return browser.act(owner, BrowserToolPolicy.NAVIGATE, Map.of("url", url),
                BrowserToolPolicy.Context.model(SnapshotIndex.of(latestOr()), List.of(url), null));
    }

    private PageSnapshot latestOr() {
        return latest != null ? latest : new PageSnapshot("about:blank", "", "", "");
    }

    private ToolOutcome navigate(ToolInput input) {
        AgentRun.ToolCall call = input.call();
        String url = input.text("url");
        if (url == null) {
            return ToolOutcome.failed(call, "失败：browser_navigate 要写 url");
        }
        try {
            fetch.checkUrl(url);
            return page(ToolOutcome.done(call, "完成。系统取了新的快照："), open(url));
        } catch (ToolNotAllowedException e) {
            return ToolOutcome.rejected(call, "被拒绝：" + e.getMessage());
        } catch (BrowserException.Disconnected e) {
            return disconnected(call, e.getMessage());
        } catch (BrowserException.Stalled e) {
            return stalled(call, e.getMessage());
        } catch (BrowserException.ToolFailed | IllegalStateException e) {
            return ToolOutcome.failed(call, "失败：" + e.getMessage());
        }
    }

    /** 执行快照、查找、点击、键入、按键、等待。 */
    private ToolOutcome handle(ToolInput input) {
        AgentRun.ToolCall call = input.call();
        String name = call.name();
        if (!browser.ownsTab(owner) || latest == null) {
            if (browser.ownsTab(owner)) {
                try {
                    latest = browser.snapshot(owner);
                } catch (BrowserException.Disconnected e) {
                    return disconnected(call, e.getMessage());
                } catch (BrowserException.Stalled e) {
                    return stalled(call, e.getMessage());
                } catch (BrowserException.ToolFailed | IllegalStateException e) {
                    return ToolOutcome.failed(call, "失败：" + e.getMessage());
                }
            } else {
                return ToolOutcome.failed(call, "失败：你的标签页还没有打开页面，先用 browser_navigate 打开一个网址。");
            }
        }
        BrowserTools.Call translated;
        try {
            translated = BrowserTools.translate(name, call.arguments(), List.of());
        } catch (IllegalArgumentException e) {
            return ToolOutcome.failed(call, "失败：" + e.getMessage());
        }
        String ref = translated.args().get("target") instanceof String target ? target : null;
        if (BrowserToolPolicy.CLICK.equals(name)) {
            // 只拦硬边界词和判断不了的元素；「下一页」「加载更多」这类软按钮放行（C006-fix-runtime-2）
            if (SubmitGuard.readOnly(latest, ref) instanceof GuardDecision.Reject reject) {
                return ToolOutcome.rejected(call, "被拒绝：" + reject.reason() + "。这里只读，不点申请类按钮。");
            }
        }
        BrowserToolPolicy.Context policy = BrowserToolPolicy.Context.model(SnapshotIndex.of(latest),
                List.of(latest.url()), null);
        try {
            if (BrowserToolPolicy.FIND.equals(name)) {
                String found = browser.find(owner, translated.args(), policy);
                return ToolOutcome.done(call, "找到的内容：\n" + SnapshotRedactor.redact(found == null ? "" : found));
            }
            PageSnapshot after = browser.act(owner, translated.tool(), translated.args(), policy);
            return page(ToolOutcome.done(call, translated.write() ? "完成。系统取了新的快照：" : "完成。"), after);
        } catch (ToolNotAllowedException e) {
            return ToolOutcome.rejected(call, "被拒绝：" + e.getMessage());
        } catch (BrowserException.Disconnected e) {
            return disconnected(call, e.getMessage());
        } catch (BrowserException.Stalled e) {
            return stalled(call, e.getMessage());
        } catch (BrowserException.ToolFailed | IllegalStateException e) {
            return ToolOutcome.failed(call, "失败：" + e.getMessage());
        }
    }

    /**
     * {@code browser_tabs}：{@code list} 列出 Chrome 里所有标签页，{@code select} 切过去并取快照。
     * 用户让看已开着的页面时用。
     */
    private ToolOutcome tabs(ToolInput input) {
        AgentRun.ToolCall call = input.call();
        Object action = input.args().get("action");
        try {
            if ("list".equals(action)) {
                List<BrowserSession.TabInfo> listed = browser.tabs(owner);
                return ToolOutcome.done(call, listed.isEmpty() ? "现在没有标签页。" : TABS_HEADER + tabLines(listed));
            }
            if ("select".equals(action)) {
                if (!(input.args().get("index") instanceof Number number) || number.intValue() < 0) {
                    return ToolOutcome.failed(call, "失败：select 要给 browser_tabs list 列出的序号 index");
                }
                PageSnapshot after = browser.select(owner, number.intValue());
                return page(ToolOutcome.done(call, "已切到标签页 " + number.intValue() + "。系统取了新的快照："), after);
            }
            return ToolOutcome.failed(call, "失败：browser_tabs 的 action 只能是 list 或 select");
        } catch (IllegalArgumentException e) {
            return ToolOutcome.failed(call, "失败：" + e.getMessage() + "。");
        } catch (BrowserException.Disconnected e) {
            return disconnected(call, e.getMessage());
        } catch (BrowserException.Stalled e) {
            return stalled(call, e.getMessage());
        } catch (BrowserException.ToolFailed e) {
            return ToolOutcome.failed(call, "失败：" + e.getMessage());
        }
    }

    private ToolOutcome page(ToolOutcome outcome, PageSnapshot page) {
        latest = page;
        onPage.accept(page);
        return outcome.withPage(page);
    }

    /** 给模型看的标签页列表：一行一个，{@code - 序号: [标题](网址)}，标明正在用的、它自己的和别的任务在用的。 */
    static String tabLines(List<BrowserSession.TabInfo> tabs) {
        StringBuilder out = new StringBuilder();
        for (BrowserSession.TabInfo tab : tabs) {
            out.append("- ").append(tab.index()).append(": ")
                    .append(tab.inUse() ? "（正在用）" : "")
                    .append(tab.yours() ? "（你的）" : "")
                    .append(tab.others() ? "（别的任务在用）" : "")
                    .append('[').append(tab.title()).append("](").append(tab.url()).append(")\n");
        }
        return out.toString().strip();
    }

    /** 浏览器断开：这一次调用失败，不再有最新的页面；不结束运行。 */
    public ToolOutcome disconnected(AgentRun.ToolCall call, String reason) {
        latest = null;
        return ToolOutcome.failed(call, "失败：浏览器连接断开了（" + reason + "）。告诉用户确认 Chrome 开着、远程调试开关"
                + "（chrome://inspect/#remote-debugging）已打开，"
                + "然后点页面顶部的「重新连接」或者回复，就会重新连接；不用浏览器的事可以接着做。").withoutPage();
    }

    /** 浏览器这次没回应（C011-fix）：连接还在，标签页记录不动；只是这一步失败，由模型决定再试、换做法还是问用户。 */
    public static ToolOutcome stalled(AgentRun.ToolCall call, String reason) {
        return ToolOutcome.failed(call, "失败：" + reason + "。这次没有完成；可以再试一次、换个做法或换个来源；一直没有回应时告诉用户"
                + "看一下 Chrome。");
    }
}
