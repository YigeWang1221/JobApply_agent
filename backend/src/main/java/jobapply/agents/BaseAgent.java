package jobapply.agents;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import jobapply.domain.AgentRun;
import jobapply.domain.SubRun;
import jobapply.harness.plugins.ApplicationStatePlugin;
import jobapply.harness.plugins.ConversationEndingPlugin;
import jobapply.harness.plugins.FollowupContextPlugin;
import jobapply.harness.plugins.JobPagePlugin;
import jobapply.harness.plugins.LimitNotePlugin;
import jobapply.harness.plugins.LoopReminderPlugin;
import jobapply.harness.plugins.OldResultsPlugin;
import jobapply.harness.plugins.PageElisionPlugin;
import jobapply.harness.plugins.Plugin;
import jobapply.harness.plugins.PluginChain;
import jobapply.harness.plugins.ResearchContextPlugin;
import jobapply.harness.runtime.AgentLoopEngine;
import jobapply.harness.runtime.Loop;
import jobapply.harness.runtime.LoopEnd;
import jobapply.harness.runtime.RunControl;
import jobapply.harness.runtime.ToolBox;
import jobapply.harness.runtime.ToolRegistry;
import jobapply.persistence.PlaybookSeeder;
import jobapply.tools.AgentServices;
import jobapply.tools.BrowserFillTools;
import jobapply.tools.ControlTools;
import jobapply.tools.FollowupTools;
import jobapply.tools.ResearchTools;
import jobapply.tools.RunContext;
import jobapply.tools.Subagents;
import jobapply.tools.Workbench;
import org.springframework.ai.chat.model.ChatModel;

/**
 * Base Agent（C030）：按 {@link Mode} 装配一次运行——工具从 {@link ToolRegistry} 按 Mode 的工具集建、插件按 Mode 的
 * {@code plugins:} 组合成 {@link PluginChain}——然后跑唯一的循环 {@link AgentLoopEngine}。Session、投递段、后续段（含
 * 查状态）都从这里进；换 Mode 就换场景。
 * <p>
 * 统一 Job 线程（Slice 4）按 {@code job.md} 的阶段选 Mode（{@link Modes#forJob}）后调 {@link #run}；Subagent（Slice 5，
 * {@link SubagentExecutor}）用自己的 {@link RunContext}（独立的标签页使用者 {@code sub:<id>}）和自己的 Mode 再调一次
 * {@link #run}，就是一个子循环。Mode 写了 {@code subagents:} 时这次运行有 {@code delegate}。
 */
public final class BaseAgent {

    private final ChatModel model;
    private final AgentServices services;

    public BaseAgent(ChatModel model, AgentServices services) {
        this.model = model;
        this.services = services;
    }

    /**
     * 跑一次：到模型或系统结束这次运行。
     *
     * @param history  到现在的对话（最后一条是用户的新消息或系统说明）
     * @param appended 每一条新对话都交给它（对话类 Mode 写进 transcript）；为 null 时不交
     * @throws RuntimeException 主模型出错、重试也不行时（调用方记为出错）
     */
    public LoopEnd run(Mode mode, RunContext ctx, List<AgentRun.Turn> history, RunControl control,
                       Consumer<AgentRun.Turn> appended) {
        new PlaybookSeeder(services.layout(), services.clock()).seed();
        if (services.browser() != null) {
            // 用户发了消息：浏览器断着的话再留一次重连，真要用浏览器时才连（C006-fix-browser 第 1 节）
            services.browser().retryOnNextUse();
        }
        if (!ctx.has(SubagentExecutor.Frame.class)) {
            // 树根：Session 是第 0 层；Job 线程（投递段、后续段）由 Session 开始，算第 1 层
            ctx.put(SubagentExecutor.Frame.class, ctx.job() == null
                    ? new SubagentExecutor.Frame(ctx.session(), 0) : new SubagentExecutor.Frame(ctx.job(), 1));
        }
        if (!mode.subagents().isEmpty()) {
            ctx.put(Subagents.class, new SubagentExecutor(this, services).bind(mode, ctx, control));
        }
        ToolBox tools = ToolRegistry.toolsFor(mode.tools(), mode.allowTools(), ctx);
        PluginChain plugins = plugins(mode, ctx);
        AgentLoopEngine engine = new AgentLoopEngine(model, mode.system(), mode.dropReplyWhenStopped(), tools,
                plugins, control, services.clock(), history, appended);
        ctx.put(Loop.class, engine);
        return engine.run();
    }

    /**
     * 申请线程的投递段（{@link Modes#APPLY}）：跑到模型或系统结束这次运行，返回更新过的运行记录。开始时系统先确认
     * 标签页是这个岗位的（不是就打开岗位链接），再取一次快照。
     *
     * @param progress 每轮之后调用一次，交给调用方存盘
     */
    public AgentRun apply(AgentRun run, RunControl control, Consumer<AgentRun> progress) {
        RunContext ctx = new RunContext(services, run.project(), run.session(), run.job(), run.job());
        Workbench bench = ctx.put(Workbench.class, new Workbench(services.layout(), services.clock(), run, progress));
        bench.open(ControlTools.catalog(ctx));
        run(Modes.get(Modes.APPLY), ctx, run.conversation(), control, null);
        return ctx.slot(ApplicationStatePlugin.class).result();
    }

    /** Session 的对话（{@link Modes#SESSION}）：每一条新对话交给 {@code appended}。 */
    public void session(String projectId, String sessionId, List<AgentRun.Turn> history, RunControl control,
                        Consumer<AgentRun.Turn> appended) {
        run(Modes.get(Modes.SESSION), new RunContext(services, projectId, sessionId, null, sessionId), history,
                control, appended);
    }

    /**
     * 工作台的后续段（{@link Modes#FOLLOWUP}，含查状态）：浏览器只读，用自己的标签页 {@code followup:<job>}。
     *
     * @param sessionId 台账事件记在哪个 Session 下（申请线程的、或者发现它的）
     */
    public void followup(String projectId, String jobId, String sessionId, List<AgentRun.Turn> history,
                         RunControl control, Consumer<AgentRun.Turn> appended) {
        run(Modes.get(Modes.FOLLOWUP), new RunContext(services, projectId, sessionId, jobId,
                FollowupTools.tabOwner(jobId)), history, control, appended);
    }

    /**
     * 系统直接起一次 Subagent（不经过父运行的 {@code delegate}，例如批量查状态的 {@code portal-check}）：子运行的对话存在
     * {@code subruns/<id>.json}，返回它的记录。
     *
     * @param parentRunId 它属于哪个运行（例如 Job id）
     * @param depth       在树里第几层
     */
    public SubRun subagent(String modeName, String projectId, String sessionId, String jobId, String parentRunId,
                           int depth, String task, RunControl control) {
        return new SubagentExecutor(this, services).run(Modes.get(modeName), projectId, sessionId, jobId, parentRunId,
                depth, task, control);
    }

    /** Mode 里写的插件，按顺序建；没有这个插件时抛出（Mode 写错了）。 */
    private PluginChain plugins(Mode mode, RunContext ctx) {
        List<Plugin> plugins = new ArrayList<>();
        for (String name : mode.plugins()) {
            plugins.add(switch (name) {
                case "application-state" -> ctx.put(ApplicationStatePlugin.class, new ApplicationStatePlugin(
                        ctx.slot(Workbench.class), services.layout(), services.clock(), mode.skills(),
                        mode.limitNote()));
                case "job-page" -> new JobPagePlugin(ctx.slot(Workbench.class), BrowserFillTools.of(ctx));
                case "page-elision" -> new PageElisionPlugin();
                case "loop-reminder" -> new LoopReminderPlugin(services.limits(),
                        ctx.has(Workbench.class) ? ctx.slot(Workbench.class)::log : null);
                case "conversation-ending" -> new ConversationEndingPlugin(mode.limitNote());
                case "limit-note" -> new LimitNotePlugin(mode.limitNote());
                case "old-results" -> new OldResultsPlugin(ResearchTools.OLD_PAGE);
                case "research-context" -> new ResearchContextPlugin(services.layout(), services.clock(),
                        ctx.project(), ctx.session(), ControlTools.catalog(ctx), mode.skills());
                case "followup-context" -> new FollowupContextPlugin(services.layout(), services.clock(),
                        ctx.project(), ctx.job(), ControlTools.catalog(ctx), mode.skills());
                default -> throw new IllegalArgumentException("Mode " + mode.name() + " 里没有这个插件：" + name);
            });
        }
        return new PluginChain(plugins);
    }
}
