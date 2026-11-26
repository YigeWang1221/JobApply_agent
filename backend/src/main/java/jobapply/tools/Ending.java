package jobapply.tools;

import java.util.List;
import jobapply.domain.AgentRun;

/**
 * 工具（或开始时的系统动作）要结束这一次运行：交给用户。对话类 Mode 只结束运行；申请 Mode 的
 * {@code ApplicationStatePlugin} 按它写出结束时的 {@link AgentRun}。
 */
public sealed interface Ending {

    /** {@code ask_user}：等用户回复（申请 Mode 带问题和上传位置）。 */
    record AskUser(String message, List<AgentRun.Question> questions, List<AgentRun.FileSlot> files)
            implements Ending {

        public AskUser {
            questions = questions == null ? List.of() : List.copyOf(questions);
            files = files == null ? List.of() : List.copyOf(files);
        }

        public static AskUser of(String message) {
            return new AskUser(message, List.of(), List.of());
        }
    }

    /**
     * {@code finish}：停在最终提交前。
     *
     * @param note 对话末尾补的一句现在的状态（C006-fix-final-reply）
     */
    record Finish(String message, boolean submitted, String note) implements Ending {
    }

    /** 浏览器断开：停下等用户说重连。 */
    record Disconnected(String reason) implements Ending {
    }

    /** 系统停下等用户（例如开始时读不到页面）：{@code message} 给用户，也作为结束说明记进对话。 */
    record Waiting(String message) implements Ending {
    }
}
