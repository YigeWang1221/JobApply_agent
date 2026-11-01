package jobapply.workflow;

import jobapply.observability.EvalOptions;
import jobapply.persistence.EvalSettingsFile;
import jobapply.persistence.StorageLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 评测模式开关（C025 第 5 节）：打开时，在 app 里<b>新开始</b>的申请拦截最终提交、每步存一份脱敏快照，结果在
 * {@code storage/eval/app/<run-id>/}。设置页改了马上生效，存进 {@code settings/eval.yaml}；没有这个文件时用
 * {@code application.yml} 的默认值（关）。运行中切换不影响已在跑的申请。
 * <p>
 * 命令行评测用 {@link #force} 给所有运行指定自己的设置（组名、消融开关），不看设置页的开关，也不改它。
 */
public final class EvalMode {

    private static final Logger log = LoggerFactory.getLogger(EvalMode.class);

    /** 当前值和 {@code application.yml} 里的默认值。 */
    public record View(boolean enabled, boolean defaultEnabled) {
    }

    private final StorageLayout layout;
    private final EvalSettingsFile file;
    private final boolean defaultEnabled;
    private volatile boolean enabled;
    private volatile EvalOptions forced;

    public EvalMode(StorageLayout layout, boolean defaultEnabled) {
        this.layout = layout;
        this.file = new EvalSettingsFile(layout);
        this.defaultEnabled = defaultEnabled;
        boolean initial = defaultEnabled;
        try {
            initial = file.read().orElse(defaultEnabled);
        } catch (RuntimeException e) {
            log.warn("{} 读不了（{}），改用配置的值", file.path(), e.getMessage());
        }
        this.enabled = initial;
    }

    public View view() {
        return new View(enabled, defaultEnabled);
    }

    /** 改开关并存盘，马上生效（只对之后开始的申请）。 */
    public synchronized View set(boolean enabled) {
        file.write(enabled);
        this.enabled = enabled;
        return view();
    }

    /** 命令行评测：之后开始的申请都用这份设置；null 恢复看设置页的开关。 */
    public void force(EvalOptions options) {
        this.forced = options;
    }

    /** 现在开始一次申请时用的评测设置；没开评测时为 null。 */
    EvalOptions forStart() {
        EvalOptions options = forced;
        if (options != null) {
            return options;
        }
        return enabled ? EvalOptions.app(layout) : null;
    }
}
