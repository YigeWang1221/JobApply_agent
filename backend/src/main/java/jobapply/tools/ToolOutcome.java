package jobapply.tools;

import java.util.ArrayList;
import java.util.List;
import jobapply.domain.AgentRun;
import jobapply.persistence.RunLog;

/**
 * 一次工具调用的结果，以及循环和插件要知道的事。
 *
 * @param result   放回对话的结果（状态在产生结果的地方定下）
 * @param page     调用之后的页面；没有时为 null（只有最新的一份原样发给模型，见 {@code PageElisionPlugin}）
 * @param ending   结束这一次运行（这一轮的结果放进对话之后）；不结束时为 null
 * @param pageGone 浏览器断开了，不再有最新的页面
 * @param action   一次浏览器操作，给打转提醒比较用（{@code LoopReminderPlugin}）；不是时为 null
 * @param fills    填写的结果，由申请状态插件自动记进申请状态和报告（{@code ApplicationStatePlugin}）
 */
public record ToolOutcome(AgentRun.ToolResult result, PageSnapshot page, Ending ending, boolean pageGone,
                          BrowserAction action, List<FillEvent> fills) {

    public ToolOutcome {
        fills = fills == null ? List.of() : List.copyOf(fills);
    }

    /**
     * 一次浏览器操作（打转提醒：同一元素连续失败、相同操作相同结果、页面一直不变）。
     *
     * @param tool     工具名；为 null 时这不是一次操作，只带来比较的起点 {@code baseline}
     * @param target   操作的元素（ref）；没有时为 null
     * @param action   操作的参数（发给 MCP 的那一份）
     * @param write    是否是写操作
     * @param failed   是否失败（工具报错、参数不对、被拒绝）
     * @param observed 成功时是遮掉 C 级值以后的页面，失败时是错误说明
     * @param baseline 系统在这次操作之前刚取的页面（遮值后），作为比较「有没有变化」的起点；没有时为 null
     * @param repeated 提醒时记进过程日志的那一条；不记时为 null
     */
    public record BrowserAction(String tool, String target, String action, boolean write, boolean failed,
                                String observed, String baseline, RunLog.Entry repeated) {
    }

    public static ToolOutcome done(AgentRun.ToolCall call, String text) {
        return of(call, text, AgentRun.ToolResult.DONE);
    }

    public static ToolOutcome failed(AgentRun.ToolCall call, String text) {
        return of(call, text, AgentRun.ToolResult.FAILED);
    }

    public static ToolOutcome rejected(AgentRun.ToolCall call, String text) {
        return of(call, text, AgentRun.ToolResult.REJECTED);
    }

    public static ToolOutcome of(AgentRun.ToolCall call, String text, String status) {
        return new ToolOutcome(new AgentRun.ToolResult(call.id(), call.name(), text, status), null, null, false, null,
                List.of());
    }

    public ToolOutcome withPage(PageSnapshot after) {
        return new ToolOutcome(result, after, ending, pageGone, action, fills);
    }

    public ToolOutcome ending(Ending end) {
        return new ToolOutcome(result, page, end, pageGone, action, fills);
    }

    public ToolOutcome withoutPage() {
        return new ToolOutcome(result, null, ending, true, action, fills);
    }

    public ToolOutcome observed(BrowserAction browserAction) {
        return new ToolOutcome(result, page, ending, pageGone, browserAction, fills);
    }

    public ToolOutcome filled(List<FillEvent> events) {
        List<FillEvent> all = new ArrayList<>(fills);
        all.addAll(events);
        return new ToolOutcome(result, page, ending, pageGone, action, all);
    }

    /** 同一个结果，文字换成 {@code text}（插件在后面追加提醒时用）。 */
    public ToolOutcome withText(String text) {
        AgentRun.ToolResult r = new AgentRun.ToolResult(result.id(), result.name(), text, result.status(),
                result.startedAt(), result.ms());
        return new ToolOutcome(r, page, ending, pageGone, action, fills);
    }
}
