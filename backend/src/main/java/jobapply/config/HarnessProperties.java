package jobapply.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code jobapply.harness.*}：Agent 循环的兜底上限和重复提醒（agent-runtime.md 7.1）。没写的项用默认值：
 * 一次运行最多 150 次工具调用（到了让模型总结，交给用户决定要不要继续）；同一个元素连续失败 3 次、相同操作得到相同结果
 * 连续 3 次、页面连续 6 次写操作没有变化时，在工具结果里提醒模型（不强制停）。
 * <p>
 * 并发（C011）：同时最多 3 个申请线程在跑（{@code application-workers}），同一个网站上同时最多 2 个
 * （{@code applications-per-site}，按 {@code BrowserToolPolicy.siteOf}），多出来的排队。这两项只是默认值：设置页改过后
 * 存在 {@code storage/settings/workers.yaml}，启动时以它为准（C012）。
 */
@ConfigurationProperties("jobapply.harness")
public record HarnessProperties(Integer toolCallsPerRun, Integer sameElementFailures, Integer sameResultRepeats,
                                Integer unchangedActions, Integer applicationWorkers, Integer applicationsPerSite) {

    public HarnessProperties {
        toolCallsPerRun = positive(toolCallsPerRun, 150, "tool-calls-per-run");
        sameElementFailures = positive(sameElementFailures, 3, "same-element-failures");
        sameResultRepeats = positive(sameResultRepeats, 3, "same-result-repeats");
        unchangedActions = positive(unchangedActions, 6, "unchanged-actions");
        applicationWorkers = positive(applicationWorkers, 3, "application-workers");
        applicationsPerSite = positive(applicationsPerSite, 2, "applications-per-site");
    }

    /** 全部用默认值。 */
    public static HarnessProperties defaults() {
        return new HarnessProperties(null, null, null, null, null, null);
    }

    private static int positive(Integer value, int fallback, String name) {
        int chosen = value == null ? fallback : value;
        if (chosen <= 0) {
            throw new IllegalArgumentException("jobapply.harness." + name + " 必须是正数");
        }
        return chosen;
    }
}
