package jobapply.harness.policies;

/**
 * 守卫对一次写操作的判断。
 * <ul>
 *   <li>{@link Allow}：可以发给 MCP。</li>
 *   <li>{@link Reject}：hardline，不发；原因里写明「不要重试」，交给模型。</li>
 *   <li>{@link AskUser}：拿不准会不会提交的按钮，先问用户。</li>
 *   <li>{@link Handoff}：这个字段由用户亲自在浏览器里填（C 级），不发。</li>
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
     * 「下一步」「继续」「保存」这类按钮拿不准会不会直接提交：不发，请模型先用 {@code ask_user} 问用户；
     * 用户回复之后，模型再点同一个按钮就放行。
     *
     * @param button 按钮名字（快照里的原文）
     */
    record AskUser(String button, String reason) implements GuardDecision {
    }

    record Handoff(String reason) implements GuardDecision {
    }

    static GuardDecision allow() {
        return new Allow();
    }
}
