package jobapply.workflow;

import jobapply.config.HarnessProperties;
import jobapply.persistence.StorageLayout;
import jobapply.persistence.ToolCallsFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具调用上限（C018）：申请 Agent 一次运行、对话 Agent 每条消息最多几次工具调用，0 = 不限。用户在设置页自己定，
 * 存进 {@code settings/tool-calls.yaml}；没有这个文件时用 {@link HarnessProperties} 的默认值。
 * <p>
 * 两个 Runner 共用一份。循环每次调模型之前都读当前值，所以改了以后下一次检查就用新值（在跑的也是）。
 */
public final class ToolCallLimits {

    private static final Logger log = LoggerFactory.getLogger(ToolCallLimits.class);

    /** 当前值和 {@code application.yml} 里的默认值；0 = 不限。 */
    public record View(int perRun, int perMessage, int defaultPerRun, int defaultPerMessage) {
    }

    private final ToolCallsFile file;
    private final HarnessProperties defaults;
    private volatile int perRun;
    private volatile int perMessage;

    public ToolCallLimits(StorageLayout layout, HarnessProperties defaults) {
        this.file = new ToolCallsFile(layout);
        this.defaults = defaults == null ? HarnessProperties.defaults() : defaults;
        ToolCallsFile.Limits initial = initial();
        this.perRun = initial.perRun();
        this.perMessage = initial.perMessage();
    }

    /** 申请 Agent 一次运行最多几次工具调用；0 = 不限。 */
    public int perRun() {
        return perRun;
    }

    /** 对话 Agent 每条消息最多几次工具调用；0 = 不限。 */
    public int perMessage() {
        return perMessage;
    }

    public View view() {
        return new View(perRun, perMessage, defaults.toolCallsPerRun(), defaults.toolCallsPerMessage());
    }

    /** 改两个上限并存盘，马上生效。负数抛 {@link IllegalArgumentException}（接口返回 400），不写文件。 */
    public synchronized View set(int perRun, int perMessage) {
        if (perRun < 0 || perMessage < 0) {
            throw new IllegalArgumentException("工具调用上限要是正整数，或者留空 / 0 表示不限");
        }
        file.write(new ToolCallsFile.Limits(perRun, perMessage));
        this.perRun = perRun;
        this.perMessage = perMessage;
        return view();
    }

    /** 有 {@code settings/tool-calls.yaml} 用它，没有或读不了时用配置（读不了时日志警告，界面上看到的是生效的值）。 */
    private ToolCallsFile.Limits initial() {
        ToolCallsFile.Limits fallback =
                new ToolCallsFile.Limits(defaults.toolCallsPerRun(), defaults.toolCallsPerMessage());
        try {
            ToolCallsFile.Limits saved = file.read().orElse(fallback);
            if (saved.perRun() < 0 || saved.perMessage() < 0) {
                log.warn("{} 里的工具调用上限不能是负数，改用配置的值", file.path());
                return fallback;
            }
            return saved;
        } catch (RuntimeException e) {
            log.warn("{} 读不了（{}），改用配置的值", file.path(), e.getMessage());
            return fallback;
        }
    }
}
