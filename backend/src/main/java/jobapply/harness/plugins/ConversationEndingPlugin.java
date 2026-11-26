package jobapply.harness.plugins;

import java.util.List;
import jobapply.domain.AgentRun;
import jobapply.harness.runtime.Loop;
import jobapply.harness.runtime.LoopEnd;
import jobapply.harness.runtime.LoopEvent;

/**
 * 对话类 Mode（Session、后续段）怎么结束：模型的文字就是给用户的回复；停下时回一句「已按你的要求停下」；到了上限时
 * 先加一条系统说明（Mode 里的 {@code limit-note}），模型的总结后面补「回复继续」。{@code ask_user} 只结束运行，
 * 它的 message 已经在模型的工具调用里。
 */
public final class ConversationEndingPlugin implements Plugin {

    public static final String STOPPED = "已按你的要求停下。回复后接着做。";
    /** 到了工具调用上限时，给用户的回复末尾加上这句（C018）。 */
    public static final String RAISE_LIMIT = "（上限可以在 设置 → 模型 →「工具调用上限」里调大，或者设为不限。）";

    private final String limitNote;

    /** @param limitNote 到了上限时的系统说明，{@code {calls}} 换成已经用掉的次数 */
    public ConversationEndingPlugin(String limitNote) {
        this.limitNote = limitNote;
    }

    @Override
    public void on(LoopEvent event, Loop loop) {
        switch (event) {
            case LoopEvent.LimitReached limit -> loop.add(AgentRun.Turn.note(limitText(limitNote, limit.calls())));
            case LoopEvent.Finished finished -> finished(finished.end(), loop);
            default -> {
            }
        }
    }

    private static void finished(LoopEnd end, Loop loop) {
        switch (end) {
            case LoopEnd.Answered answered -> loop.reply(AgentRun.Turn.assistant(
                    answered.text() == null || answered.text().isBlank() ? "（没有想好怎么回答，请再说一遍要做什么。）"
                            : answered.text().strip(), List.of(), answered.thinking()));
            case LoopEnd.Stopped stopped -> loop.add(AgentRun.Turn.assistant(STOPPED, List.of()));
            case LoopEnd.Summarized summary -> loop.reply(AgentRun.Turn.assistant(
                    (summary.text().isEmpty() ? "这一次做了很多步，到了上限。" : summary.text())
                            + "\n\n回复「继续」我接着做。" + RAISE_LIMIT, List.of(), summary.thinking()));
            case LoopEnd.Ended ended -> {
            }
        }
    }

    /** Mode 里的上限说明：{@code {calls}} 换成次数。 */
    static String limitText(String template, int calls) {
        return template.replace("{calls}", String.valueOf(calls));
    }
}
