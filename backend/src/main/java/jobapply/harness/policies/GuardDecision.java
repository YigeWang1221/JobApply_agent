package jobapply.harness.policies;

/**
 * 守卫对一次写操作的判断。
 * <ul>
 *   <li>{@link Allow}：可以发给 MCP。</li>
 *   <li>{@link Reject}：hardline，不发；原因里写明「不要重试」，交给模型。</li>
 *   <li>{@link NeedsConfirm}：软边界，先问用户。</li>
 *   <li>{@link Handoff}：停止写操作，由用户亲自在浏览器里操作。</li>
 * </ul>
 */
public sealed interface GuardDecision {

    record Allow() implements GuardDecision {
    }

    record Reject(String reason) implements GuardDecision {

        public Reject {
            reason = reason.endsWith("不要重试。") ? reason : reason + "。不要重试。";
        }
    }

    /**
     * @param button             按钮名字（快照里的原文）
     * @param purpose            模型给的用途；没给时为 null
     * @param finalStepSuspected 模型是否认为像是最后一步；没给时为 null
     * @param evidence           模型给的依据；没给时为 null
     */
    record NeedsConfirm(String button, String purpose, Boolean finalStepSuspected, String evidence)
            implements GuardDecision {
    }

    record Handoff(String reason) implements GuardDecision {
    }

    static GuardDecision allow() {
        return new Allow();
    }
}
