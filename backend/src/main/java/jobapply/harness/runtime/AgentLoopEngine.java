package jobapply.harness.runtime;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import jobapply.config.ModelFactory;
import jobapply.domain.AgentRun;
import jobapply.domain.Timestamps;
import jobapply.harness.plugins.ContextView;
import jobapply.harness.plugins.PluginChain;
import jobapply.tools.Ending;
import jobapply.tools.PageSnapshot;
import jobapply.tools.ToolInput;
import jobapply.tools.ToolOutcome;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

/**
 * Base Agent 的唯一循环（C030 Slice 3；Hermes 的 while 循环，deepagents 的主 Agent 和子 Agent 是同一个
 * {@code create_agent}，只差工具和中间件）：停止检查 → 取走用户插话（C010）→ 上限检查 → 拼消息（插件的
 * {@code context}）→ 调模型 → 模型只回了文字就结束 → 依次执行工具调用（{@code toolBefore} / 执行 / {@code toolAfter}，
 * 计时、告诉观察的一方）→ 结果放回对话 → 下一轮。
 * <p>
 * 调研、投递、后续段（含查状态）都是这一个循环，差别只在 Mode：系统提示词、工具集、插件组合和两个参数。没有固定的图、
 * 节点和等待点；流程上的事由模型决定。结束时（{@link LoopEnd}）插件按原因写对话和状态（{@link LoopEvent.Finished}）。
 */
public final class AgentLoopEngine implements Loop {

    static final String SKIPPED = "没有执行：这一次运行已经结束。";

    private final ChatModel model;
    private final String system;
    private final boolean dropReplyWhenStopped;
    private final ToolBox tools;
    private final List<ToolCallback> callbacks;
    private final PluginChain plugins;
    private final RunControl control;
    private final Clock clock;
    private final Consumer<AgentRun.Turn> appended;
    private final List<AgentRun.Turn> turns;
    private int calls;
    private boolean started;
    private Ending endedAtStart;

    /**
     * @param system               系统提示词（Mode 的正文）
     * @param dropReplyWhenStopped 调模型回来时用户已经要求停止：true 时丢掉这条回复直接停（申请）；false 时照常记下回复，
     *                             它的工具调用写「没有执行」，下一轮再停（对话）
     * @param history              到现在的对话（复制一份，之后的新对话都加在这份上）
     * @param appended             每一条新对话都交给它（对话类 Mode 写进 transcript）；为 null 时不交
     */
    public AgentLoopEngine(ChatModel model, String system, boolean dropReplyWhenStopped, ToolBox tools,
                           PluginChain plugins, RunControl control, Clock clock, List<AgentRun.Turn> history,
                           Consumer<AgentRun.Turn> appended) {
        this.model = model;
        this.plugins = plugins;
        this.system = plugins.prompt(system);
        this.dropReplyWhenStopped = dropReplyWhenStopped;
        this.tools = new ToolBox(plugins.tools(tools.tools()));
        this.callbacks = this.tools.callbacks();
        this.control = control;
        this.clock = clock;
        this.appended = appended == null ? ignored -> {
        } : appended;
        this.turns = new ArrayList<>(history);
    }

    /** 跑到某个结束条件。主模型出错、重试也不行时抛出（调用方记为失败）。 */
    public LoopEnd run() {
        plugins.on(new LoopEvent.Started(), this);
        started = true;
        if (endedAtStart != null) {
            return finish(new LoopEnd.Ended(endedAtStart));
        }
        while (true) {
            if (control.stop().getAsBoolean()) {
                return finish(new LoopEnd.Stopped());
            }
            heard();
            int limit = control.toolCallLimit().getAsInt();
            if (limit > 0 && calls >= limit) {
                return limitReached();
            }
            ChatResponse response = call();
            if (dropReplyWhenStopped && control.stop().getAsBoolean()) {
                return finish(new LoopEnd.Stopped());
            }
            AssistantMessage message = ModelCaller.message(response);
            String thinking = ModelCaller.thinking(response);
            String text = message == null ? null : message.getText();
            if (message == null || !message.hasToolCalls()) {
                return finish(new LoopEnd.Answered(text, thinking));
            }
            List<AgentRun.ToolCall> toolCalls = message.getToolCalls().stream()
                    .map(call -> new AgentRun.ToolCall(call.id(), call.name(), call.arguments())).toList();
            reply(AgentRun.Turn.assistant(text, toolCalls, thinking));
            List<AgentRun.ToolResult> results = new ArrayList<>();
            Ending end = null;
            for (AgentRun.ToolCall call : toolCalls) {
                String startedAt = Timestamps.now(clock);
                if (end != null || control.stop().getAsBoolean()) {
                    AgentRun.ToolResult skipped = new AgentRun.ToolResult(call.id(), call.name(), SKIPPED,
                            AgentRun.ToolResult.REJECTED).timed(startedAt, 0);
                    results.add(skipped);
                    control.observer().done(call, skipped);
                    continue;
                }
                calls++;
                control.observer().tool(call);
                long begin = System.nanoTime();
                ToolOutcome outcome = plugins.toolBefore(call, this);
                if (outcome == null) {
                    outcome = tools.run(new ToolInput(call));
                }
                outcome = plugins.toolAfter(call, outcome, this);
                AgentRun.ToolResult result = outcome.result().timed(startedAt, (System.nanoTime() - begin) / 1_000_000);
                results.add(result);
                control.observer().done(call, result);
                end = outcome.ending();
            }
            add(AgentRun.Turn.tool(results));
            plugins.on(new LoopEvent.RoundDone(), this);
            if (end != null) {
                return finish(new LoopEnd.Ended(end));
            }
        }
    }

    /**
     * 用户插话（C010）：上一轮的工具都执行完、结果放进对话之后，下一次调模型之前，作为用户消息加进对话。正在执行的工具
     * 不打断（浏览器的写操作不会做一半）；先查停止再取，「打断并发送」的那句话留给下一次运行。
     */
    private void heard() {
        List<AgentRun.Turn> said = control.inbox().get();
        if (said.isEmpty()) {
            return;
        }
        said.forEach(this::add);
        plugins.on(new LoopEvent.Heard(said), this);
        control.observer().heard(said);
    }

    /** 到了上限：插件加一条系统说明，让模型总结做到哪了（不再执行工具），交给用户决定要不要继续。 */
    private LoopEnd limitReached() {
        plugins.on(new LoopEvent.LimitReached(calls), this);
        ChatResponse response = call();
        AssistantMessage message = ModelCaller.message(response);
        String text = message == null || message.getText() == null ? "" : message.getText().strip();
        return finish(new LoopEnd.Summarized(text, ModelCaller.thinking(response)));
    }

    /** 调主模型（{@link ModelCaller}：暂时出错时等一会儿再试）。 */
    private ChatResponse call() {
        control.observer().thinking();
        ContextView view = new ContextView(system, turns);
        plugins.context(view);
        ChatResponse response = ModelCaller.call(model,
                new Prompt(view.messages(), ModelFactory.withTools(model, callbacks)), control.stop());
        plugins.response(response);
        return response;
    }

    private LoopEnd finish(LoopEnd end) {
        plugins.on(new LoopEvent.Finished(end), this);
        return end;
    }

    // ---- Loop ----

    @Override
    public List<AgentRun.Turn> turns() {
        return Collections.unmodifiableList(turns);
    }

    @Override
    public void add(AgentRun.Turn turn) {
        turns.add(turn);
        appended.accept(turn);
    }

    @Override
    public void reply(AgentRun.Turn turn) {
        add(turn);
        control.observer().replied(turn);
    }

    @Override
    public void showPage(PageSnapshot page) {
        plugins.on(new LoopEvent.PageShown(page, turns.size() - 1), this);
    }

    @Override
    public int calls() {
        return calls;
    }

    @Override
    public void end(Ending ending) {
        if (started) {
            throw new IllegalStateException("只能在循环开始时结束：运行中由工具的结果结束");
        }
        if (endedAtStart == null) {
            endedAtStart = ending;
        }
    }
}
