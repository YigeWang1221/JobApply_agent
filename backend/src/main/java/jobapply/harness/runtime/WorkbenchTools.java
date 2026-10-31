package jobapply.harness.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import jobapply.domain.AgentRun;
import jobapply.domain.IllegalTransitionException;
import jobapply.domain.Job;
import jobapply.domain.Outcome;
import jobapply.memory.ExperienceIndex;
import jobapply.persistence.JobLedger;
import jobapply.tools.ExperienceTools;
import jobapply.tools.FollowupTools;
import org.springframework.ai.tool.ToolCallback;

/**
 * 工作台两段都有的工具（C022）：投递段（{@link HarnessLoop}）和后续段（{@link FollowupLoop}）的工具清单都经过
 * {@link #with}，执行都先问 {@link #handle}。两段共用的工具只在这一个地方登记。
 * 现在是 {@code search_experience}（跨工作台经验检索，C022 第 2 节）和 {@code record_outcome}（投递失败 / 不投了，C024）。
 */
final class WorkbenchTools {

    /** 两段共用的工具定义。 */
    private static final List<ToolCallback> SHARED = List.of(ExperienceTools.definition(), FollowupTools.recordOutcome());

    /**
     * 这个工具在哪个工作台、哪一段被调用。
     *
     * @param session 台账事件记在哪个 Session 下，Session 的系统通知也写给它
     * @param segment {@code fill}（投递段）或 {@code followup}（后续段）
     */
    record Where(String project, String job, String session, String company, String title, String segment) {

        static final String FILL = "fill";
        static final String FOLLOWUP = "followup";
    }

    private WorkbenchTools() {
    }

    /** 一段自己的工具，加上两段共用的。 */
    static List<ToolCallback> with(List<ToolCallback> own) {
        List<ToolCallback> tools = new ArrayList<>(own);
        tools.addAll(SHARED);
        return List.copyOf(tools);
    }

    /**
     * 执行一个共用工具；不是共用工具时返回 null，由这一段自己处理。过滤条件由模型自己给，不拿 {@code where}
     * 默认限定公司：跨公司的经验也要查得到。
     *
     * @param experience 经验检索；为 null 时（测试里手工建的循环）工具回报不可用
     */
    static <R> AgentLoop.Step<R> handle(Where where, AgentRun.ToolCall call, ExperienceIndex experience,
                                        JobLedger ledger, SessionNote note) {
        if (FollowupTools.RECORD_OUTCOME.equals(call.name())) {
            return recordOutcome(where, call, ledger, note);
        }
        if (!ExperienceTools.isExperienceTool(call.name())) {
            return null;
        }
        if (experience == null) {
            return AgentLoop.Step.failed(call, "失败：经验检索没有接上。");
        }
        try {
            return AgentLoop.Step.done(call, experience.searchTool(ToolArgs.parse(call.arguments())));
        } catch (IllegalArgumentException e) {
            return AgentLoop.Step.failed(call, "失败：" + e.getMessage());
        } catch (RuntimeException e) {
            return AgentLoop.Step.failed(call, "失败：经验检索出错：" + e.getMessage());
        }
    }

    /**
     * 用户说了「投不了 / 不投了，因为……」（C024）：和工作台按钮同一个 {@link JobLedger#recordOutcome}，然后给 Session
     * 写一条系统通知。原因必填：没有时不记，让模型先问用户。
     */
    private static <R> AgentLoop.Step<R> recordOutcome(Where where, AgentRun.ToolCall call, JobLedger ledger,
                                                       SessionNote note) {
        Map<String, Object> args;
        try {
            args = ToolArgs.parse(call.arguments());
        } catch (IllegalArgumentException e) {
            return AgentLoop.Step.failed(call, "失败：" + e.getMessage());
        }
        String kind = args.get("kind") == null ? "" : args.get("kind").toString().strip().toLowerCase(Locale.ROOT);
        String reason = args.get("reason") == null ? "" : args.get("reason").toString().strip();
        if (!kind.equals("apply_failed") && !kind.equals("gave_up")) {
            return AgentLoop.Step.failed(call, "失败：kind 只能是 apply_failed 或 gave_up。");
        }
        if (reason.isEmpty()) {
            return AgentLoop.Step.failed(call, "失败：没有原因，没有记。先用 ask_user 问用户为什么，用户说了再记。");
        }
        Outcome outcome = Outcome.of(kind);
        try {
            int events = ledger.events(where.project(), where.job()).size();
            Job job = ledger.recordOutcome(where.project(), where.job(), where.session(), outcome, reason);
            if (ledger.events(where.project(), where.job()).size() == events) {
                return AgentLoop.Step.done(call, "已经记为" + outcome.userLabel() + "了，没有再记一次。");
            }
            note.note(where.project(), where.session(), where.job(), JobLedger.outcomeNote(job));
            return AgentLoop.Step.done(call, "记下了：" + job.status().label() + "，原因「" + reason + "」。Session 里也留了一条"
                    + "通知。不要再动页面；用一句话告诉用户记下了（工作台头部可以撤销），然后结束。");
        } catch (IllegalArgumentException | IllegalTransitionException e) {
            return AgentLoop.Step.failed(call, "失败：" + e.getMessage());
        }
    }
}
