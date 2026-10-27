package jobapply.harness.runtime;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import jobapply.config.ModelFactory;
import jobapply.domain.AgentRun;
import jobapply.domain.Timestamps;
import jobapply.harness.context.Conversation;
import jobapply.tools.PageSnapshot;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

/**
 * 申请 Agent 和对话 Agent 共用的一次运行（C006-fix-runtime-2 第 4 条；deepagents 的主 Agent 和子 Agent 是同一个
 * {@code create_agent}，只差工具和中间件）：停止检查 → 取走用户插话（C010）→ 上限检查 → 调模型 → 模型只回了文字就结束 → 依次执行工具调用
 * （计时、告诉观察的一方、记下最新的页面）→ 结果放回对话 → 下一轮。
 * <p>
 * 每个 Agent 只提供 {@link Agent}：拼给模型的消息、怎么执行一个工具、几种结束时返回什么。没有结果的工具调用在
 * {@link Conversation#messages} 里补「被中断了」，这里不另做。
 *
 * @param <R> 一次运行结束时交给调用方的东西（申请是 {@link AgentRun}，对话是 {@link Void}）
 */
final class AgentLoop<R> {

    static final String SKIPPED = "没有执行：这一次运行已经结束。";
    /** 到了工具调用上限时，给用户的回复末尾加上这句（C018）。 */
    static final String RAISE_LIMIT = "（上限可以在 设置 → 模型 →「工具调用上限」里调大，或者设为不限。）";

    /**
     * 一个 Agent 和循环之间的约定。
     */
    interface Agent<R> {

        /** 这一步发给模型的消息（系统提示词、上下文、对话）。 */
        List<Message> messages(List<AgentRun.Turn> turns, Conversation.LatestPage latest);

        /** 模型回了（每次调模型之后，包括上限时的那次总结）。 */
        default void responded(ChatResponse response) {
        }

        /** 执行一个工具调用。 */
        Step<R> handle(AgentRun.ToolCall call);

        /** 一个工具调用得到了新的页面（在它成为最新页面之前）。 */
        default void sawPage(PageSnapshot page) {
        }

        /** 模型只回了文字（{@code text} 是原文，可能为 null）：这次运行结束。 */
        R answered(String text, String thinking);

        /** 用户要求停止：这次运行结束。 */
        R stopped();

        /** 到了上限：放进对话的那条系统说明（{@code calls} 是这次运行已经用掉的工具调用次数）。 */
        AgentRun.Turn limitTurn(int calls);

        /** 上限时模型的总结（{@code text} 已经去掉首尾空白，没有时是空字符串）：这次运行结束。 */
        R summarized(String text, String thinking);

        /** 一轮的工具都执行完、结果放进对话之后。 */
        default void roundDone() {
        }

        /** 用户插话已经加进对话（C010，在下一次调模型之前）。 */
        default void heard(List<AgentRun.Turn> said) {
        }
    }

    /**
     * 一次工具调用的结果。
     *
     * @param page 调用之后的页面（只有最新的一份会原样发给模型）；没有时为 null
     * @param end  结束这一次运行：在这一轮的结果放进对话、{@link Agent#roundDone} 之后取；不结束时为 null
     */
    record Step<R>(AgentRun.ToolResult result, PageSnapshot page, Supplier<R> end) {

        /** 做成了。状态在产生结果的地方定下，不靠结果的开头去猜（C006-fix）。 */
        static <R> Step<R> done(AgentRun.ToolCall call, String text) {
            return of(call, text, AgentRun.ToolResult.DONE);
        }

        /** 没做成：参数不对、出错、读不到。 */
        static <R> Step<R> failed(AgentRun.ToolCall call, String text) {
            return of(call, text, AgentRun.ToolResult.FAILED);
        }

        /** 被守卫拒绝、或者没有执行。 */
        static <R> Step<R> rejected(AgentRun.ToolCall call, String text) {
            return of(call, text, AgentRun.ToolResult.REJECTED);
        }

        static <R> Step<R> of(AgentRun.ToolCall call, String text, String status) {
            return new Step<>(new AgentRun.ToolResult(call.id(), call.name(), text, status), null, null);
        }

        /** 同一个结果，带上调用之后的页面。 */
        Step<R> withPage(PageSnapshot page) {
            return new Step<>(result, page, end);
        }

        /** 同一个结果，并且结束这一次运行。 */
        Step<R> ending(Supplier<R> end) {
            return new Step<>(result, page, end);
        }
    }

    private final ChatModel model;
    private final List<ToolCallback> tools;
    private final Clock clock;
    private final IntSupplier toolCallLimit;
    private final boolean dropReplyWhenStopped;
    private final BooleanSupplier stop;
    private final LoopObserver observer;
    private final Consumer<AgentRun.Turn> appended;
    private final Supplier<List<AgentRun.Turn>> inbox;
    private final List<AgentRun.Turn> turns;
    private Conversation.LatestPage latestPage;
    private int calls;

    /**
     * @param toolCallLimit        这一次运行最多执行多少次工具调用，到了让模型总结；0 或负数 = 不限。每次调模型之前都读一次，
     *                             所以设置页改了以后在跑的运行下一步就用新值（C018）
     * @param dropReplyWhenStopped 调模型回来时用户已经要求停止：true 时丢掉这条回复直接停（申请 Agent）；false 时
     *                             照常记下回复，它的工具调用写「没有执行」，下一轮再停（对话 Agent）
     * @param history              到现在的对话（复制一份，之后的新对话都加在这份上）
     * @param appended             每一条新对话都交给它（对话 Agent 写进 transcript）
     * @param inbox                取走运行中用户插的话（C010）；没有时返回空
     */
    AgentLoop(ChatModel model, List<ToolCallback> tools, Clock clock, IntSupplier toolCallLimit,
              boolean dropReplyWhenStopped, BooleanSupplier stop, LoopObserver observer, List<AgentRun.Turn> history,
              Consumer<AgentRun.Turn> appended, Supplier<List<AgentRun.Turn>> inbox) {
        this.model = model;
        this.tools = tools;
        this.clock = clock;
        this.toolCallLimit = toolCallLimit;
        this.dropReplyWhenStopped = dropReplyWhenStopped;
        this.stop = stop == null ? () -> false : stop;
        this.observer = observer == null ? LoopObserver.NONE : observer;
        this.appended = appended == null ? ignored -> {
        } : appended;
        this.inbox = inbox == null ? List::of : inbox;
        this.turns = new ArrayList<>(history);
    }

    /** 跑到 {@code agent} 的某个结束条件。主模型出错、重试也不行时抛出（调用方记为失败）。 */
    R run(Agent<R> agent) {
        while (true) {
            if (stop.getAsBoolean()) {
                return agent.stopped();
            }
            heard(agent);
            int limit = toolCallLimit.getAsInt();
            if (limit > 0 && calls >= limit) {
                return limitReached(agent);
            }
            ChatResponse response = call(agent);
            if (dropReplyWhenStopped && stop.getAsBoolean()) {
                return agent.stopped();
            }
            AssistantMessage message = ModelCaller.message(response);
            String thinking = ModelCaller.thinking(response);
            String text = message == null ? null : message.getText();
            if (message == null || !message.hasToolCalls()) {
                return agent.answered(text, thinking);
            }
            List<AgentRun.ToolCall> toolCalls = message.getToolCalls().stream()
                    .map(call -> new AgentRun.ToolCall(call.id(), call.name(), call.arguments())).toList();
            reply(AgentRun.Turn.assistant(text, toolCalls, thinking));
            int toolTurn = turns.size();
            List<AgentRun.ToolResult> results = new ArrayList<>();
            Supplier<R> end = null;
            for (AgentRun.ToolCall call : toolCalls) {
                String startedAt = Timestamps.now(clock);
                if (end != null || stop.getAsBoolean()) {
                    AgentRun.ToolResult skipped = new AgentRun.ToolResult(call.id(), call.name(), SKIPPED,
                            AgentRun.ToolResult.REJECTED).timed(startedAt, 0);
                    results.add(skipped);
                    observer.done(call, skipped);
                    continue;
                }
                calls++;
                observer.tool(call);
                long started = System.nanoTime();
                Step<R> step = agent.handle(call);
                AgentRun.ToolResult result = step.result().timed(startedAt, (System.nanoTime() - started) / 1_000_000);
                results.add(result);
                observer.done(call, result);
                if (step.page() != null) {
                    agent.sawPage(step.page());
                    // 弹出层视图时留住之前那份整页（C016 实跑后修正）
                    latestPage = Conversation.LatestPage.next(latestPage, step.page(), toolTurn, call.id());
                }
                end = step.end();
            }
            add(AgentRun.Turn.tool(results));
            agent.roundDone();
            if (end != null) {
                return end.get();
            }
        }
    }

    /**
     * 用户插话（C010）：上一轮的工具都执行完、结果放进对话之后，下一次调模型之前，作为用户消息加进对话。正在执行的工具
     * 不打断（浏览器的写操作不会做一半）；先查停止再取，「打断并发送」的那句话留给下一次运行。
     */
    private void heard(Agent<R> agent) {
        List<AgentRun.Turn> said = inbox.get();
        if (said.isEmpty()) {
            return;
        }
        said.forEach(this::add);
        agent.heard(said);
        observer.heard(said);
    }

    /** 到了上限：让模型总结做到哪了（不再执行工具），交给用户决定要不要继续。 */
    private R limitReached(Agent<R> agent) {
        add(agent.limitTurn(calls));
        ChatResponse response = call(agent);
        AssistantMessage message = ModelCaller.message(response);
        String text = message == null || message.getText() == null ? "" : message.getText().strip();
        return agent.summarized(text, ModelCaller.thinking(response));
    }

    /** 调主模型（{@link ModelCaller}：暂时出错时等一会儿再试）。 */
    private ChatResponse call(Agent<R> agent) {
        observer.thinking();
        ChatResponse response = ModelCaller.call(model,
                new Prompt(agent.messages(turns, latestPage), ModelFactory.withTools(model, tools)), stop);
        agent.responded(response);
        return response;
    }

    /** 加一条对话。 */
    void add(AgentRun.Turn turn) {
        turns.add(turn);
        appended.accept(turn);
    }

    /** 模型回的一条：记下，再告诉观察的一方。 */
    void reply(AgentRun.Turn turn) {
        add(turn);
        observer.replied(turn);
    }

    /** 到现在的对话（同一份，不是复制）。 */
    List<AgentRun.Turn> turns() {
        return turns;
    }

    /** 系统自己取的页面：它是最新的一条对话（{@code page} 条）。 */
    void showPage(PageSnapshot page) {
        latestPage = new Conversation.LatestPage(page, turns.size() - 1, null);
    }

    /** 不再有最新的页面（浏览器断开了）。 */
    void forgetPage() {
        latestPage = null;
    }

    /** 这一次运行已经执行的工具调用次数。 */
    int calls() {
        return calls;
    }
}
