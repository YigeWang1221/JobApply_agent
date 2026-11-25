package jobapply.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import jobapply.domain.AgentRun;
import jobapply.domain.IllegalTransitionException;
import jobapply.domain.Job;
import jobapply.domain.Outcome;
import jobapply.persistence.JobLedger;
import jobapply.persistence.JobRepository;

/**
 * 工作台的工具（C022）：两段都有的 {@code search_experience}（跨工作台经验检索）和 {@code record_outcome}（投递失败 /
 * 不投了，C024），以及投递段的 {@code mark_submitted}（用户说已经在 Chrome 里提交了）。
 */
public final class WorkbenchTools {

    private final RunContext ctx;
    private final JobLedger ledger;

    private WorkbenchTools(RunContext ctx) {
        this.ctx = ctx;
        this.ledger = ctx.services().ledger();
    }

    /** 投递段：{@code mark_submitted} + 两段共用的。 */
    public static List<Tool> fill(RunContext ctx) {
        WorkbenchTools tools = new WorkbenchTools(ctx);
        List<Tool> out = new ArrayList<>();
        out.add(Tool.of(FollowupTools.SUBMITTED, tools::markSubmitted));
        out.addAll(shared(ctx, tools));
        return out;
    }

    /** 后续段：两段共用的。 */
    public static List<Tool> followup(RunContext ctx) {
        return shared(ctx, new WorkbenchTools(ctx));
    }

    private static List<Tool> shared(RunContext ctx, WorkbenchTools tools) {
        return List.of(ExperienceTools.create(ctx), Tool.of(FollowupTools.OUTCOME, tools::recordOutcome));
    }

    /**
     * 用户说了「投不了 / 不投了，因为……」（C024）：和工作台按钮同一个 {@link JobLedger#recordOutcome}，然后给 Session
     * 写一条系统通知。原因必填：没有时不记，让模型先问用户。
     */
    private ToolOutcome recordOutcome(ToolInput input) {
        AgentRun.ToolCall call = input.call();
        Object kindArg = input.args().get("kind");
        Object reasonArg = input.args().get("reason");
        String kind = kindArg == null ? "" : kindArg.toString().strip().toLowerCase(Locale.ROOT);
        String reason = reasonArg == null ? "" : reasonArg.toString().strip();
        if (!kind.equals("apply_failed") && !kind.equals("gave_up")) {
            return ToolOutcome.failed(call, "失败：kind 只能是 apply_failed 或 gave_up。");
        }
        if (reason.isEmpty()) {
            return ToolOutcome.failed(call, "失败：没有原因，没有记。先用 ask_user 问用户为什么，用户说了再记。");
        }
        Outcome outcome = Outcome.of(kind);
        try {
            int events = ledger.events(ctx.project(), ctx.job()).size();
            Job job = ledger.recordOutcome(ctx.project(), ctx.job(), ctx.session(), outcome, reason);
            if (ledger.events(ctx.project(), ctx.job()).size() == events) {
                return ToolOutcome.done(call, "已经记为" + outcome.userLabel() + "了，没有再记一次。");
            }
            ctx.services().sessionNote().note(ctx.project(), ctx.session(), ctx.job(), JobLedger.outcomeNote(job));
            return ToolOutcome.done(call, "记下了：" + job.status().label() + "，原因「" + reason + "」。Session 里也留了一条"
                    + "通知。不要再动页面；用一句话告诉用户记下了（工作台头部可以撤销），然后结束。");
        } catch (IllegalArgumentException | IllegalTransitionException e) {
            return ToolOutcome.failed(call, "失败：" + e.getMessage());
        }
    }

    /**
     * 用户在工作台里说已经在 Chrome 里提交了（C022）：记为已投递，用户的话就是证据。不结束这次运行：模型回一句话就停下；
     * 用户定下了，结束时不会改回去，工作台接着进入后续段。
     */
    private ToolOutcome markSubmitted(ToolInput input) {
        AgentRun.ToolCall call = input.call();
        String note = input.text("note");
        JobRepository jobs = new JobRepository(ctx.services().layout());
        Job current = jobs.load(ctx.project(), ctx.job());
        if (JobLedger.alreadyApplied(current.status())) {
            return ToolOutcome.done(call, "已经记为已投递了（" + current.status().label() + "），投递时间 "
                    + current.extraString("applied_at") + "。没有再记一次。");
        }
        try {
            Job job = ledger.markSubmitted(ctx.project(), ctx.job(), ctx.session(), note);
            return ToolOutcome.done(call, "记为已投递（" + job.status().label() + "），" + job.extraString("next_check_at")
                    + " 提醒用户去看状态。不要再动页面；用一句话告诉用户记下了，之后的笔试、面试在这个工作台的后续段里聊。");
        } catch (IllegalArgumentException | IllegalTransitionException e) {
            return ToolOutcome.failed(call, "失败：" + e.getMessage());
        }
    }
}
