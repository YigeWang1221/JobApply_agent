package jobapply.harness.runtime;

import java.util.ArrayList;
import java.util.List;
import jobapply.domain.AgentRun;
import jobapply.memory.ExperienceIndex;
import jobapply.tools.ExperienceTools;
import org.springframework.ai.tool.ToolCallback;

/**
 * 工作台两段都有的工具（C022）：投递段（{@link HarnessLoop}）和后续段（{@link FollowupLoop}）的工具清单都经过
 * {@link #with}，执行都先问 {@link #handle}。两段共用的工具只在这一个地方登记。
 * 现在只有 {@code search_experience}（跨工作台经验检索，C022 第 2 节）。
 */
final class WorkbenchTools {

    /** 两段共用的工具定义。 */
    private static final List<ToolCallback> SHARED = List.of(ExperienceTools.definition());

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
     * 执行一个共用工具；不是共用工具时返回 null，由这一段自己处理。过滤条件由模型自己给，不拿 {@code where}
     * 默认限定公司：跨公司的经验也要查得到。
     *
     * @param experience 经验检索；为 null 时（测试里手工建的循环）工具回报不可用
     */
    static <R> AgentLoop.Step<R> handle(Where where, AgentRun.ToolCall call, ExperienceIndex experience) {
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
}
