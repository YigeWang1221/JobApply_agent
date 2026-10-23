package jobapply.harness.runtime;

import java.util.Objects;
import java.util.Optional;
import jobapply.config.HarnessProperties;

/**
 * 重复操作的提醒（agent-runtime.md 7.1）：不看总量，看是否在重复。命中时返回一句话，附在工具结果里告诉模型
 * 「这是第 N 次」，由模型换办法或问用户；不强制停。
 * <ul>
 *   <li>同一个元素连续失败 N 次（默认 3）；</li>
 *   <li>相同操作得到相同结果连续 N 次（默认 3）；</li>
 *   <li>页面连续 N 次写操作没有变化（默认 6）。</li>
 * </ul>
 * 比较用的结果必须是遮掉 C 级值以后的快照（{@link jobapply.harness.policies.SnapshotRedactor}）。
 * 状态只在一次运行的内存里，用户回复后重新计数。
 */
public final class LoopDetector {

    /**
     * 一次浏览器操作。
     *
     * @param tool    工具名
     * @param target  操作的元素（ref）；没有时为 null
     * @param action  操作的参数（发给 MCP 的那一份），用来判断是不是相同的操作
     * @param write   是否是写操作
     * @param failed  是否失败（工具报错、参数不对、被白名单拒绝）
     * @param result  结果：成功时是遮掉 C 级值以后的页面，失败时是错误说明
     */
    public record Action(String tool, String target, String action, boolean write, boolean failed, String result) {
    }

    private final HarnessProperties limits;
    private String failingElement;
    private int failures;
    private String lastSignature;
    private int repeats;
    private String lastPage;
    private int unchanged;

    public LoopDetector(HarnessProperties limits) {
        this.limits = limits;
    }

    /** 记下一次操作，返回给模型的提醒；没有在重复时为空。 */
    public Optional<String> record(Action action) {
        String element = action.target() == null ? action.tool() : action.target();
        if (action.failed()) {
            failures = element.equals(failingElement) ? failures + 1 : 1;
            failingElement = element;
        } else if (element.equals(failingElement)) {
            failingElement = null;
            failures = 0;
        }

        String signature = action.tool() + "\u0000" + action.action() + "\u0000" + action.failed() + "\u0000"
                + action.result();
        repeats = signature.equals(lastSignature) ? repeats + 1 : 1;
        lastSignature = signature;

        if (action.write()) {
            boolean changed = !action.failed() && lastPage != null && !Objects.equals(lastPage, action.result());
            unchanged = changed ? 0 : unchanged + 1;
        }
        if (!action.failed()) {
            if (lastPage != null && !Objects.equals(lastPage, action.result())) {
                unchanged = 0;
            }
            lastPage = action.result();
        }

        if (failures >= limits.sameElementFailures()) {
            return Optional.of("提醒：同一个元素已经连续失败 " + failures + " 次。换一个办法（先读相关的 skill、看错误提示、"
                    + "换操作方式），还不行就用 ask_user 请用户帮忙。");
        }
        if (repeats >= limits.sameResultRepeats()) {
            return Optional.of("提醒：同样的操作已经连续 " + repeats + " 次得到同样的结果，再做一次也不会变。换一个办法，"
                    + "或者用 ask_user。");
        }
        if (unchanged >= limits.unchangedActions()) {
            return Optional.of("提醒：已经连续 " + unchanged + " 次写操作页面都没有变化。先确认操作的是不是对的元素，"
                    + "或者用 ask_user。");
        }
        return Optional.empty();
    }

    /** 记下系统自己取到的页面（一轮开始时的快照），作为比较「有没有变化」的起点。 */
    public void startAt(String page) {
        lastPage = page;
    }
}
