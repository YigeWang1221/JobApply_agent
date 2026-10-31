package jobapply.domain;

import static jobapply.domain.PrepState.AWAITING_REVIEW;
import static jobapply.domain.PrepState.FAILED;
import static jobapply.domain.PrepState.HANDOFF_USER;
import static jobapply.domain.PrepState.NEEDS_INPUT;
import static jobapply.domain.PrepState.NEEDS_LOGIN;
import static jobapply.domain.PrepState.PAUSED;
import static jobapply.domain.PrepState.PREPARING;
import static jobapply.domain.PrepState.SELECTED;
import static jobapply.domain.PrepState.UNCONFIRMED;
import static jobapply.domain.PrepState.USER_SUBMITTING;
import static jobapply.domain.PrepState.VERIFICATION_PENDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ApplicationStateMachineTest {

    /** plan/coding/01-foundation/C002-memory-ledger.md「状态机」表的独立复述。 */
    private static final Map<PrepState, Set<PrepState>> EXPECTED = new EnumMap<>(PrepState.class);

    static {
        EXPECTED.put(SELECTED, EnumSet.of(PREPARING, PAUSED));
        EXPECTED.put(PREPARING, EnumSet.of(NEEDS_INPUT, NEEDS_LOGIN, HANDOFF_USER, FAILED, AWAITING_REVIEW, PAUSED));
        EXPECTED.put(NEEDS_INPUT, EnumSet.of(PREPARING, PAUSED));
        EXPECTED.put(NEEDS_LOGIN, EnumSet.of(PREPARING, PAUSED));
        EXPECTED.put(HANDOFF_USER, EnumSet.of(PREPARING, PAUSED));
        EXPECTED.put(FAILED, EnumSet.of(PREPARING, PAUSED));
        EXPECTED.put(AWAITING_REVIEW, EnumSet.of(USER_SUBMITTING, PREPARING, PAUSED));
        EXPECTED.put(USER_SUBMITTING, EnumSet.of(VERIFICATION_PENDING, UNCONFIRMED));
        EXPECTED.put(VERIFICATION_PENDING, EnumSet.of(UNCONFIRMED));
        EXPECTED.put(UNCONFIRMED, EnumSet.noneOf(PrepState.class));
        EXPECTED.put(PAUSED, EnumSet.of(SELECTED, PREPARING));
    }

    private static final Evidence EVIDENCE = new Evidence("success-page", "投递成功（虚构原文）",
            "2026-10-22 10:05", "2026-10-22T10:05:00-04:00", "2026-10-22T10:06:00-04:00");

    static Stream<Arguments> everyPrepPair() {
        List<Arguments> pairs = new ArrayList<>();
        for (PrepState from : PrepState.values()) {
            for (PrepState to : PrepState.values()) {
                pairs.add(Arguments.of(from, to, EXPECTED.get(from).contains(to)));
            }
        }
        return pairs.stream();
    }

    @ParameterizedTest(name = "{0} → {1}: {2}")
    @MethodSource("everyPrepPair")
    void prepStateEdgesMatchThePlan(PrepState from, PrepState to, boolean allowed) {
        JobStatus current = prep(from);
        Transition change = new Transition.ChangePrepState(to);

        if (allowed) {
            assertThat(ApplicationStateMachine.next(current, change, Actor.AGENT).prepState()).isEqualTo(to);
        } else {
            assertThatThrownBy(() -> ApplicationStateMachine.next(current, change, Actor.AGENT))
                    .isInstanceOf(IllegalTransitionException.class);
        }
    }

    @Test
    void leadEdges() {
        JobStatus fresh = JobStatus.newLead();
        JobStatus shortlisted = next(fresh, new Transition.MarkLead(LeadStatus.SHORTLISTED, null));
        assertThat(next(shortlisted, new Transition.MarkLead(LeadStatus.NEW, null)).leadStatus())
                .isEqualTo(LeadStatus.NEW);
        JobStatus skipped = next(shortlisted, new Transition.MarkLead(LeadStatus.SKIPPED, "方向不符"));
        assertThat(skipped.skipReason()).isEqualTo("方向不符");

        rejects(fresh, new Transition.MarkLead(LeadStatus.SKIPPED, null));
        rejects(skipped, new Transition.MarkLead(LeadStatus.NEW, null));
        rejects(skipped, new Transition.Convert());
        rejects(fresh, new Transition.MarkLead(LeadStatus.CONVERTED, null));
        rejects(fresh, new Transition.ChangePrepState(PREPARING));
        rejects(next(fresh, new Transition.Convert()), new Transition.Convert());
    }

    @Test
    void appliedNeedsCompleteEvidenceFromSubmittingStates() {
        for (PrepState from : List.of(USER_SUBMITTING, VERIFICATION_PENDING, UNCONFIRMED)) {
            JobStatus applied = next(prep(from), new Transition.MarkApplied(EVIDENCE));
            assertThat(applied.phase()).isEqualTo(Phase.APPLIED);
            assertThat(applied.prepState()).isNull();
            assertThat(applied.evidence()).isEqualTo(EVIDENCE);
        }
        rejects(prep(USER_SUBMITTING), new Transition.MarkApplied(null));
        rejects(prep(USER_SUBMITTING), new Transition.MarkApplied(
                new Evidence("user-said-so", "我投了", null, "2026-10-22T10:05:00-04:00", "2026-10-22T10:06:00-04:00")));
        rejects(prep(USER_SUBMITTING), new Transition.MarkApplied(
                new Evidence("history-record", " ", null, "2026-10-22T10:05:00-04:00", "2026-10-22T10:06:00-04:00")));
        rejects(prep(USER_SUBMITTING), new Transition.MarkApplied(
                new Evidence("history-record", "已投递", null, null, "2026-10-22T10:06:00-04:00")));
        rejects(prep(AWAITING_REVIEW), new Transition.MarkApplied(EVIDENCE));
        rejects(prep(USER_SUBMITTING), new Transition.ChangePhase(Phase.APPLIED));
    }

    @Test
    void phasesAfterAppliedMoveFreelyButNeverBack() {
        JobStatus applied = next(prep(USER_SUBMITTING), new Transition.MarkApplied(EVIDENCE));
        JobStatus interview = next(applied, new Transition.ChangePhase(Phase.INTERVIEW));
        JobStatus assessment = next(interview, new Transition.ChangePhase(Phase.ASSESSMENT));

        assertThat(assessment.phase()).isEqualTo(Phase.ASSESSMENT);
        assertThat(assessment.evidence()).isEqualTo(EVIDENCE);
        rejects(interview, new Transition.ChangePhase(Phase.INTERVIEW));
        rejects(interview, new Transition.ChangePhase(Phase.PREP));
        rejects(interview, new Transition.ChangePhase(Phase.APPLIED));
        rejects(interview, new Transition.ChangePhase(Phase.CLOSED));
        rejects(prep(PREPARING), new Transition.ChangePhase(Phase.SCREENING));
    }

    @Test
    void outcomesDependOnPhase() {
        JobStatus applied = next(prep(USER_SUBMITTING), new Transition.MarkApplied(EVIDENCE));
        JobStatus offer = next(applied, new Transition.ChangePhase(Phase.OFFER));
        JobStatus interview = next(applied, new Transition.ChangePhase(Phase.INTERVIEW));

        JobStatus notSubmitted = next(prep(PREPARING), new Transition.Close(Outcome.NOT_SUBMITTED, "转投别家"));
        assertThat(notSubmitted.phase()).isEqualTo(Phase.CLOSED);
        assertThat(notSubmitted.prepState()).isNull();
        assertThat(notSubmitted.outcome()).isEqualTo(Outcome.NOT_SUBMITTED);
        assertThat(next(offer, new Transition.Close(Outcome.ACCEPTED, null)).outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(next(interview, new Transition.Close(Outcome.REJECTED, "二面")).outcome())
                .isEqualTo(Outcome.REJECTED);
        assertThat(ApplicationStateMachine.next(applied, new Transition.Close(Outcome.GHOSTED, null), Actor.USER)
                .outcome()).isEqualTo(Outcome.GHOSTED);

        rejects(applied, new Transition.Close(Outcome.NOT_SUBMITTED, null));
        rejects(interview, new Transition.Close(Outcome.DECLINED, null));
        rejects(prep(PREPARING), new Transition.Close(Outcome.REJECTED, null));
        rejects(JobStatus.newLead(), new Transition.Close(Outcome.NOT_SUBMITTED, null));
        rejects(applied, new Transition.Close(null, null));
        assertThatThrownBy(() -> ApplicationStateMachine.next(applied, new Transition.Close(Outcome.GHOSTED, null),
                Actor.SYSTEM)).isInstanceOf(IllegalTransitionException.class).hasMessageContaining("用户");
    }

    @Test
    void closedIsTerminal() {
        JobStatus closed = next(prep(PREPARING), new Transition.Close(Outcome.NOT_SUBMITTED, null));

        rejects(closed, new Transition.ChangePrepState(PREPARING));
        rejects(closed, new Transition.ChangePhase(Phase.INTERVIEW));
        rejects(closed, new Transition.Close(Outcome.WITHDRAWN, null));
    }

    @Test
    void inconsistentStatusCannotBeConstructed() {
        assertThatThrownBy(() -> new JobStatus(LeadStatus.CONVERTED, null, Phase.APPLIED, PREPARING, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobStatus(LeadStatus.CONVERTED, null, Phase.INTERVIEW, null, Outcome.REJECTED, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobStatus(LeadStatus.NEW, null, Phase.PREP, SELECTED, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobStatus(LeadStatus.SKIPPED, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static JobStatus prep(PrepState state) {
        return new JobStatus(LeadStatus.CONVERTED, null, Phase.PREP, state, null, null);
    }

    private static JobStatus next(JobStatus current, Transition transition) {
        return ApplicationStateMachine.next(current, transition, Actor.USER);
    }

    private static void rejects(JobStatus current, Transition transition) {
        assertThatThrownBy(() -> ApplicationStateMachine.next(current, transition, Actor.USER))
                .isInstanceOf(IllegalTransitionException.class);
    }
}
