package jobapply.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code jobapply.eval.*}（C025）：评测模式（拦截最终提交、每步存脱敏快照）的默认值，<b>默认关闭</b>。设置页「评测」改过后
 * 存在 {@code storage/settings/eval.yaml}，以它为准。
 */
@ConfigurationProperties("jobapply.eval")
public record EvalProperties(Boolean enabled) {

    public EvalProperties {
        enabled = enabled != null && enabled;
    }
}
