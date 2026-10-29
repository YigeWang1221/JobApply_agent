package jobapply.harness.runtime;

import java.util.ArrayList;
import java.util.List;
import jobapply.domain.AgentRun;
import org.springframework.ai.tool.ToolCallback;

/**
 * 工作台两段都有的工具（C022）：投递段（{@link HarnessLoop}）和后续段（{@link FollowupLoop}）的工具清单都经过
 * {@link #with}，执行都先问 {@link #handle}。两段共用的工具只在这一个地方登记。
 * <p>
 * 合龙：{@code search_experience}（跨工作台经验检索，C022 第 2 节，工作包 A）在这里注册——定义加进 {@link #SHARED}，
 * 执行接在 {@link #handle} 里。现在还没有共用工具。
 */
final class WorkbenchTools {

    /** 两段共用的工具定义。合龙时 {@code search_experience} 的定义加在这里。 */
    private static final List<ToolCallback> SHARED = List.of();

    /**
     * 这个工具在哪个工作台、哪一段被调用。
     *
     * @param segment {@code fill}（投递段）或 {@code followup}（后续段）
     */
    record Where(String project, String job, String company, String title, String segment) {

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
     * 执行一个共用工具；不是共用工具时返回 null，由这一段自己处理。合龙时 {@code search_experience} 接在这里
     * （{@code where} 给出工作台 id、公司、岗位和段，可以当默认的过滤条件）。
     */
    static <R> AgentLoop.Step<R> handle(Where where, AgentRun.ToolCall call) {
        return null;
    }
}
