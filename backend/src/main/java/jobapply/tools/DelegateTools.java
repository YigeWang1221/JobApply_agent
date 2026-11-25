package jobapply.tools;

import java.util.List;
import java.util.stream.Collectors;

/**
 * {@code delegate}（C030 Slice 5，deepagents 的 {@code task} 工具）：把一件独立的事交给一个子 Mode 去做，子运行跑完才返回，
 * 只交回精简结果；子运行读的网页、走的步骤不进这次对话。能交给哪些由 Mode 的 {@code subagents:} 定（{@link Subagents}）；
 * 这次运行没有可交的（不在 Mode 里，或者已经是最深一层）时没有这个工具。
 */
public final class DelegateTools {

    public static final String DELEGATE = "delegate";

    private DelegateTools() {
    }

    public static List<Tool> create(RunContext ctx) {
        if (!ctx.has(Subagents.class)) {
            return List.of();
        }
        Subagents subagents = ctx.slot(Subagents.class);
        List<Subagents.Choice> choices = subagents.choices();
        if (choices.isEmpty()) {
            return List.of();
        }
        String modes = choices.stream().map(choice -> "\"" + choice.name() + "\"").collect(Collectors.joining(","));
        String described = choices.stream().map(choice -> "- " + choice.name() + ": " + choice.description())
                .collect(Collectors.joining("\n"));
        return List.of(Tool.of(Tool.definition(DELEGATE,
                "Hand one self-contained task to a sub-agent and wait for its short result. The sub-agent starts "
                        + "fresh: it sees only task (plus its own context for the job), not this conversation, so "
                        + "write in task everything it needs and what to return. Its pages and steps stay out of "
                        + "this conversation; you get back a compact result. It cannot ask the user anything. "
                        + "Sub-agents:\n" + described,
                """
                {"type":"object","properties":{\
                "mode":{"type":"string","enum":[%s]},\
                "task":{"type":"string","description":"what to do, the inputs, and what to report back"},\
                "job":{"type":"string","description":"Job id the task is about; omit in a workspace (its own job)"}},\
                "required":["mode","task"]}""".formatted(modes)),
                input -> {
                    String mode = input.text("mode");
                    String task = input.text("task");
                    if (mode == null || task == null) {
                        return ToolOutcome.failed(input.call(), "失败：delegate 要写 mode 和 task");
                    }
                    return ToolOutcome.done(input.call(), subagents.delegate(mode, task, input.text("job")));
                }));
    }
}
