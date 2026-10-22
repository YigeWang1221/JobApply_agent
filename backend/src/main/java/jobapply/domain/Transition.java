package jobapply.domain;

/**
 * 对一个 Job 的一次状态变化请求。是否合法由 {@link ApplicationStateMachine} 判断。
 */
public sealed interface Transition {

    /** 候选池内的标记：想投、不投（必须写原因）、改回新发现。 */
    record MarkLead(LeadStatus to, String reason) implements Transition {
    }

    /** 候选转为申请。 */
    record Convert() implements Transition {
    }

    /** {@code reason} 可选，写进事件，例如登录接力里用户 override 的说明。 */
    record ChangePrepState(PrepState to, String reason) implements Transition {

        public ChangePrepState(PrepState to) {
            this(to, null);
        }
    }

    /** 凭证据进入 {@code phase=applied}。 */
    record MarkApplied(Evidence evidence) implements Transition {
    }

    /** applied 之后的阶段变化：screening / assessment / interview / offer。 */
    record ChangePhase(Phase to) implements Transition {
    }

    record Close(Outcome outcome, String reason) implements Transition {
    }
}
