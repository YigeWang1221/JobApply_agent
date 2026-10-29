package jobapply.harness.runtime;

import static jobapply.harness.runtime.ToolArgs.parse;
import static jobapply.harness.runtime.ToolArgs.text;

import java.util.List;
import java.util.Map;
import jobapply.domain.AgentRun;
import jobapply.harness.policies.BrowserToolPolicy;
import jobapply.harness.policies.GuardDecision;
import jobapply.harness.policies.SnapshotRedactor;
import jobapply.harness.policies.SubmitGuard;
import jobapply.harness.policies.ToolNotAllowedException;
import jobapply.harness.runtime.AgentLoop.Step;
import jobapply.tools.BrowserException;
import jobapply.tools.BrowserSession;
import jobapply.tools.BrowserTools;
import jobapply.tools.PageSnapshot;
import jobapply.tools.SnapshotIndex;
import jobapply.tools.WebFetch;

/**
 * Agent 在用户 Chrome 里自己的那个只读标签页（C022 从 {@link SessionLoop} 抽出来，Session 的调研和工作台的后续段共用）：
 * 导航、快照、查找、点击（过 {@link SubmitGuard#readOnly}，申请 / 提交类按钮一律拒绝）、等待、列出和切换标签页。
 * 给哪些工具由各自的工具清单定，这里只执行。网址过 {@link WebFetch#checkUrl}；浏览器断开或没回应时这一次调用失败，
 * 原话交给模型，不结束运行。
 */
final class ReadOnlyTab {

    private final BrowserSession browser;
    private final WebFetch fetch;
    /** 标签页的使用者（Session id，或者后续段的 {@code followup:<job>}）。 */
    private final String owner;
    /** 浏览器断开时：循环不再有最新的页面。 */
    private final Runnable forget;
    private PageSnapshot latest;

    ReadOnlyTab(BrowserSession browser, WebFetch fetch, String owner, Runnable forget) {
        this.browser = browser;
        this.fetch = fetch;
        this.owner = owner;
        this.forget = forget;
    }

    /** 一个工具调用得到了新的页面。 */
    void saw(PageSnapshot page) {
        latest = page;
    }

    /** 在自己的标签页打开网址（第一次时新开一个）。 */
    PageSnapshot open(String url) {
        if (!browser.ownsTab(owner)) {
            return browser.open(url, owner);
        }
        return browser.act(owner, BrowserToolPolicy.NAVIGATE, Map.of("url", url),
                BrowserToolPolicy.Context.model(SnapshotIndex.of(latestOr()), List.of(url), null));
    }

    private PageSnapshot latestOr() {
        return latest != null ? latest : new PageSnapshot("about:blank", "", "", "");
    }

    /** 执行一个浏览器工具（调用方已经确认它在自己的工具清单里）。 */
    <R> Step<R> handle(AgentRun.ToolCall call) {
        String name = call.name();
        if (BrowserToolPolicy.TABS.equals(name)) {
            return tabs(call);
        }
        if (BrowserToolPolicy.NAVIGATE.equals(name)) {
            String url;
            try {
                url = text(parse(call.arguments()).get("url"));
            } catch (IllegalArgumentException e) {
                return Step.failed(call, "失败：" + e.getMessage());
            }
            if (url == null) {
                return Step.failed(call, "失败：browser_navigate 要写 url");
            }
            try {
                fetch.checkUrl(url);
                PageSnapshot page = open(url);
                return Step.<R>done(call, "完成。系统取了新的快照：").withPage(page);
            } catch (ToolNotAllowedException e) {
                return Step.rejected(call, "被拒绝：" + e.getMessage());
            } catch (BrowserException.Disconnected e) {
                return disconnected(call, e.getMessage());
            } catch (BrowserException.Stalled e) {
                return stalled(call, e.getMessage());
            } catch (BrowserException.ToolFailed | IllegalStateException e) {
                return Step.failed(call, "失败：" + e.getMessage());
            }
        }
        if (!browser.ownsTab(owner) || latest == null) {
            if (browser.ownsTab(owner)) {
                try {
                    latest = browser.snapshot(owner);
                } catch (BrowserException.Disconnected e) {
                    return disconnected(call, e.getMessage());
                } catch (BrowserException.Stalled e) {
                    return stalled(call, e.getMessage());
                } catch (BrowserException.ToolFailed | IllegalStateException e) {
                    return Step.failed(call, "失败：" + e.getMessage());
                }
            } else {
                return Step.failed(call, "失败：你的标签页还没有打开页面，先用 browser_navigate 打开一个网址。");
            }
        }
        BrowserTools.Call translated;
        try {
            translated = BrowserTools.translate(name, call.arguments(), List.of());
        } catch (IllegalArgumentException e) {
            return Step.failed(call, "失败：" + e.getMessage());
        }
        String ref = translated.args().get("target") instanceof String target ? target : null;
        if (BrowserToolPolicy.CLICK.equals(name)) {
            // 只拦硬边界词和判断不了的元素；「下一页」「加载更多」这类软按钮放行（C006-fix-runtime-2）
            if (SubmitGuard.readOnly(latest, ref) instanceof GuardDecision.Reject reject) {
                return Step.rejected(call, "被拒绝：" + reject.reason() + "。这里只读，不点申请类按钮。");
            }
        }
        BrowserToolPolicy.Context policy = BrowserToolPolicy.Context.model(SnapshotIndex.of(latest),
                List.of(latest.url()), null);
        try {
            if (BrowserToolPolicy.FIND.equals(name)) {
                String found = browser.find(owner, translated.args(), policy);
                return Step.done(call, "找到的内容：\n" + SnapshotRedactor.redact(found == null ? "" : found));
            }
            PageSnapshot after = browser.act(owner, translated.tool(), translated.args(), policy);
            return Step.<R>done(call, translated.write() ? "完成。系统取了新的快照：" : "完成。").withPage(after);
        } catch (ToolNotAllowedException e) {
            return Step.rejected(call, "被拒绝：" + e.getMessage());
        } catch (BrowserException.Disconnected e) {
            return disconnected(call, e.getMessage());
        } catch (BrowserException.Stalled e) {
            return stalled(call, e.getMessage());
        } catch (BrowserException.ToolFailed | IllegalStateException e) {
            return Step.failed(call, "失败：" + e.getMessage());
        }
    }

    /**
     * {@code browser_tabs}：{@code list} 列出 Chrome 里所有标签页，{@code select} 切过去并取快照。
     * 用户让看已开着的页面时用。
     */
    private <R> Step<R> tabs(AgentRun.ToolCall call) {
        Map<String, Object> args;
        try {
            args = parse(call.arguments());
        } catch (IllegalArgumentException e) {
            return Step.failed(call, "失败：" + e.getMessage());
        }
        Object action = args.get("action");
        try {
            if ("list".equals(action)) {
                List<BrowserSession.TabInfo> listed = browser.tabs(owner);
                return Step.done(call, listed.isEmpty() ? "现在没有标签页。"
                        : "Chrome 里所有的标签页（序号是浏览器里的序号，select 用它）。（你的）= 你开的、点击弹出的、"
                        + "用户交给你的；（别的任务在用）= 别的申请或调研正在用，不要碰；其余是用户自己开着的页：\n"
                        + tabLines(listed));
            }
            if ("select".equals(action)) {
                if (!(args.get("index") instanceof Number number) || number.intValue() < 0) {
                    return Step.failed(call, "失败：select 要给 browser_tabs list 列出的序号 index");
                }
                PageSnapshot after = browser.select(owner, number.intValue());
                return Step.<R>done(call, "已切到标签页 " + number.intValue() + "。系统取了新的快照：")
                        .withPage(after);
            }
            return Step.failed(call, "失败：browser_tabs 的 action 只能是 list 或 select");
        } catch (IllegalArgumentException e) {
            return Step.failed(call, "失败：" + e.getMessage() + "。");
        } catch (BrowserException.Disconnected e) {
            return disconnected(call, e.getMessage());
        } catch (BrowserException.Stalled e) {
            return stalled(call, e.getMessage());
        } catch (BrowserException.ToolFailed e) {
            return Step.failed(call, "失败：" + e.getMessage());
        }
    }

    private static String tabLines(List<BrowserSession.TabInfo> tabs) {
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

    <R> Step<R> disconnected(AgentRun.ToolCall call, String reason) {
        latest = null;
        forget.run();
        return Step.failed(call, "失败：浏览器连接断开了（" + reason + "）。告诉用户确认 Chrome 开着、远程调试开关"
                + "（chrome://inspect/#remote-debugging）已打开，"
                + "然后点页面顶部的「重新连接」或者回复，就会重新连接；不用浏览器的事可以接着做。");
    }

    /** 浏览器这次没回应（C011-fix）：连接还在，标签页记录不动；只是这一步失败，由模型决定再试、换做法还是问用户。 */
    static <R> Step<R> stalled(AgentRun.ToolCall call, String reason) {
        return Step.failed(call, "失败：" + reason + "。这次没有完成；可以再试一次、换个做法或换个来源；一直没有回应时告诉用户"
                + "看一下 Chrome。");
    }
}
