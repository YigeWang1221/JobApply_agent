package jobapply.agents;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import jobapply.config.OpenCodeSession;
import jobapply.domain.AgentRun;
import jobapply.domain.SubRun;
import jobapply.domain.Timestamps;
import jobapply.harness.runtime.LoopEnd;
import jobapply.harness.runtime.LoopObserver;
import jobapply.harness.runtime.RunControl;
import jobapply.persistence.SubRunStore;
import jobapply.tools.AgentServices;
import jobapply.tools.RunContext;
import jobapply.tools.Subagents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Depth=2/3 单向 Subagent 树（C030 Slice 5，deepagents 的 {@code task}：子 Agent 就是同一个循环换一个 Mode）。
 * 父运行用 {@code delegate} 把一件事交给子 Mode：子运行用自己的 {@link RunContext}（标签页使用者 {@code sub:<subRunId>}），
 * 跑完才返回，父运行只拿到精简结果；子运行的对话、步骤和结果存在 {@code projects/<p>/subruns/<subRunId>.json}
 * （{@link SubRunStore}），不进父运行的对话。
 * <p>
 * 单向：只能交给父 Mode 的 {@code subagents:} 里写的子 Mode，子 Mode 再往下也一样；Session 是第 0 层、Job 线程第 1 层，
 * 最深到 {@link #MAX_DEPTH}，到了这一层就没有 {@code delegate}。子运行不能问用户，停止跟着父运行。用了浏览器的子运行
 * 结束时（正常、出错都一样）关掉自己开的所有标签页。
 */
public final class SubagentExecutor {

    /** 最深一层（Session 0 → Job 线程 / Subagent 1 → Subagent 2）。 */
    public static final int MAX_DEPTH = 2;
    /** 针对一个 Job 的子 Mode：没有 Job 时不起子运行，直接告诉模型要写 {@code job}。 */
    static final Set<String> NEEDS_JOB = Set.of(Modes.RESUME_MATCH, Modes.PORTAL_CHECK, Modes.INTERVIEW_PREP,
            Modes.REFLECT);
    /** 交回父运行的结果最多这么多字。 */
    static final int MAX_RESULT = 3_000;

    /** 一次运行在树里的位置（放在 {@link RunContext} 里）。 */
    public record Frame(String runId, int depth) {
    }

    private static final Logger log = LoggerFactory.getLogger(SubagentExecutor.class);

    private final BaseAgent agent;
    private final AgentServices services;
    private final SubRunStore store;

    SubagentExecutor(BaseAgent agent, AgentServices services) {
        this.agent = agent;
        this.services = services;
        this.store = new SubRunStore(services.layout());
    }

    /** 子运行的标签页使用者：和 Session、Job 线程、别的子运行都分开。 */
    public static String tabOwner(String subRunId) {
        return "sub:" + subRunId;
    }

    /**
     * 父运行能用的 {@code delegate}：只交给 {@code parentMode} 的 {@code subagents:}，父运行已经在最深一层时没有可交的。
     */
    Subagents bind(Mode parentMode, RunContext parent, RunControl control) {
        Frame frame = parent.slot(Frame.class);
        List<String> allowed = frame.depth() >= MAX_DEPTH ? List.of() : parentMode.subagents();
        return new Subagents() {
            @Override
            public List<Choice> choices() {
                return allowed.stream()
                        .map(name -> new Choice(name, Modes.get(services.layout(), name).description())).toList();
            }

            @Override
            public String delegate(String mode, String task, String job) {
                if (!allowed.contains(mode)) {
                    throw new IllegalArgumentException("这里不能交给 " + mode + "，只能交给 " + allowed);
                }
                String target = job != null && !job.isBlank() ? job : parent.job();
                if (target == null && NEEDS_JOB.contains(mode)) {
                    throw new IllegalArgumentException("交给 " + mode + " 要写 job（这件事针对的 Job id），请带上 job 再调一次");
                }
                SubRun run = run(Modes.get(services.layout(), mode), parent.project(), parent.session(), target, frame.runId(),
                        frame.depth() + 1, task, new RunControl(control.stop(), null, LoopObserver.NONE,
                                control.toolCallLimit()));
                return forParent(run);
            }
        };
    }

    /**
     * 跑一次子运行：存 {@code subruns/<id>.json}（开始、每一条对话、结束），通知 {@link AgentServices#subruns()}。
     * 模型出错不抛出，记成 {@link SubRun#FAILED}，原因放在结果里。
     *
     * @param parentRunId 父运行（Session id、Job id 或上一层子运行的 id）
     * @param depth       这次子运行在第几层
     * @param control     停止跟着父运行；收件箱不用（子运行不收用户插话）
     */
    public SubRun run(Mode mode, String project, String session, String job, String parentRunId, int depth,
                      String task, RunControl control) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("已经到了最深一层（" + MAX_DEPTH + "），不能再往下交");
        }
        String id = "sub-" + UUID.randomUUID().toString().substring(0, 13);
        String owner = tabOwner(id);
        RunContext ctx = new RunContext(services, project, session, job, owner);
        ctx.put(Frame.class, new Frame(id, depth));
        AgentRun.Turn brief = AgentRun.Turn.user(task, List.of());
        List<AgentRun.Turn> turns = Collections.synchronizedList(new ArrayList<>(List.of(brief)));
        SubRun started = new SubRun(id, mode.name(), depth, parentRunId, project, session, job, task, SubRun.RUNNING,
                Timestamps.now(services.clock()), null, null, turns);
        save(started, true);
        String status;
        String result;
        try {
            LoopEnd end = OpenCodeSession.call("jobapply-" + id, () -> agent.run(mode, ctx, List.of(brief), control,
                    turn -> {
                        turns.add(turn);
                        save(started.withTurns(List.copyOf(turns)), false);
                    }));
            switch (end) {
                case LoopEnd.Answered answered -> {
                    status = SubRun.DONE;
                    result = answered.text() == null ? "" : answered.text().strip();
                }
                case LoopEnd.Summarized summary -> {
                    status = SubRun.DONE;
                    result = summary.text();
                }
                case LoopEnd.Stopped stopped -> {
                    status = SubRun.STOPPED;
                    result = "按用户的要求停下了。";
                }
                case LoopEnd.Ended ended -> {
                    status = SubRun.DONE;
                    result = String.valueOf(ended.ending());
                }
            }
            if (!result.isEmpty() && (end instanceof LoopEnd.Answered || end instanceof LoopEnd.Summarized)) {
                turns.add(AgentRun.Turn.assistant(result, List.of()));
            }
        } catch (RuntimeException e) {
            status = SubRun.FAILED;
            result = "出错：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            log.warn("Subagent {}（{}）出错：{}", id, mode.name(), result, e);
        } finally {
            if (services.browser() != null) {
                // 自己开的页一律关掉，使用者也不再留（sub:<id> 不会再用）
                services.browser().release(owner);
            }
        }
        SubRun ended = started.ended(status, result, Timestamps.now(services.clock()), List.copyOf(turns));
        save(ended, true);
        return ended;
    }

    /** 交回父运行的那一小段。 */
    static String forParent(SubRun run) {
        String result = run.result() == null || run.result().isBlank() ? "（没有要交回的内容）" : run.result().strip();
        if (result.length() > MAX_RESULT) {
            result = result.substring(0, MAX_RESULT) + "…";
        }
        String state = switch (run.status()) {
            case SubRun.DONE -> "做完了";
            case SubRun.STOPPED -> "停下了";
            default -> "出错了";
        };
        return "子任务 " + run.mode() + "（" + run.id() + "）" + state + "：\n" + result;
    }

    private void save(SubRun run, boolean notify) {
        try {
            store.save(run);
        } catch (RuntimeException e) {
            log.warn("Subagent {} 的记录没有写进去：{}", run.id(), e.getMessage());
        }
        if (notify) {
            try {
                services.subruns().changed(run);
            } catch (RuntimeException e) {
                log.warn("Subagent {} 的事件没有发出去：{}", run.id(), e.getMessage());
            }
        }
    }
}
