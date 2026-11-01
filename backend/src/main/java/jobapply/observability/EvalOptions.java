package jobapply.observability;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import jobapply.persistence.StorageLayout;

/**
 * 一次评测运行的设置（C025）：结果存到哪、两个在申请循环里生效的消融开关、提交拦截认哪些请求。为 null 就是没开评测，
 * 主流程不装拦截、不写 {@code storage/eval/}、提示词和工具不变。另外两个消融开关不在这里：超时检查在
 * {@code BrowserSession#diagnoseTimeouts}，经验检索在 {@code ExperienceIndex#keywordOnly}，由命令行评测直接设。
 *
 * @param group         组名（{@code app} 是设置页打开评测模式后在 app 里开始的申请）
 * @param dir           这一组的结果目录 {@code storage/eval/<组名>/}，每次运行一个子目录
 * @param fullSnapshots 消融 1：历史消息里的快照不省掉
 * @param skills        消融 2a 关闭时为 false：不给 {@code read_skill}，提示词和上下文里的 skill 引导一起去掉
 * @param submitPattern 提交请求的特征（正则，忽略大小写，对网址路径和请求体）；为空时用 {@link #GENERIC_SUBMIT}
 * @param params        记进 {@code meta.json} 的参数（开关、题卡、storage 位置等）
 * @param cleanStart    这次起点是不是空表；不知道时为 null
 */
public record EvalOptions(String group, Path dir, boolean fullSnapshots, boolean skills, String submitPattern,
                          Map<String, Object> params, Boolean cleanStart) {

    /** 没有站点题卡时的通用规则：路径或请求体里有这些词就当成提交（可能误拦普通保存，拦下的都记下来给用户核对）。 */
    public static final String GENERIC_SUBMIT = "submit|apply|deliver|投递";

    public EvalOptions {
        submitPattern = submitPattern == null || submitPattern.isBlank() ? GENERIC_SUBMIT : submitPattern;
        params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    /** 设置页打开评测模式后、在 app 里开始的申请：只拦截和存快照，不做消融。 */
    public static EvalOptions app(StorageLayout layout) {
        return new EvalOptions("app", layout.evalDir().resolve("app"), false, true, null,
                Map.of("source", "app"), null);
    }
}
