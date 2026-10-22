package jobapply;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import jobapply.domain.Actor;
import jobapply.domain.Evidence;
import jobapply.domain.Fact;
import jobapply.domain.FactClass;
import jobapply.domain.Job;
import jobapply.domain.JobEvent;
import jobapply.domain.LeadStatus;
import jobapply.domain.Outcome;
import jobapply.domain.Phase;
import jobapply.domain.PrepState;
import jobapply.domain.SessionRecord;
import jobapply.domain.Transition;
import jobapply.memory.ProfileResolver;
import jobapply.memory.ResolvedFact;
import jobapply.persistence.FactsFile;
import jobapply.persistence.FrontmatterFile;
import jobapply.persistence.JobLedger;
import jobapply.persistence.JobRepository;
import jobapply.persistence.SessionRepository;
import jobapply.persistence.TestStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 端到端场景：一个 Project 从调研到一次申请结束，经过两个 Session。数据全部虚构。
 * 只在 {@code mvn verify} 时运行。
 */
class MemoryLedgerScenarioIT {

    private static final String PROJECT = "fall-2026-v1";

    @TempDir
    Path root;

    @Test
    void researchThenApplyThenCloseLeavesConsistentFilesAndHistory() {
        TestStorage storage = TestStorage.create(root, true);
        JobLedger ledger = storage.ledger();
        SessionRepository sessions = storage.sessions();

        // 资料：base + Project overrides
        FactsFile.write(storage.layout.baseFacts(), "个人基础事实", List.of(
                Fact.of("contact.name", "示例用户", FactClass.B, "user-confirmed", "2026-10-01"),
                Fact.of("education.school", "示例大学", FactClass.A, "user-confirmed", "2026-10-01")));
        storage.git().commit("base：录入", List.of(storage.layout.baseFacts()));
        storage.project(PROJECT, "live");
        FactsFile.write(storage.layout.overrides(PROJECT), "overrides", List.of(
                Fact.of("education.school", "示例大学（2026 写法）", FactClass.A, "user-confirmed", "2026-10-20")));
        storage.git().commit("overrides：录入", List.of(storage.layout.overrides(PROJECT)));
        int commitsAfterSetup = storage.commitCount();

        // Session 1：调研
        String research = sessions.create(PROJECT, "调研后端岗位").id();
        sessions.appendTranscript(PROJECT, research, "user", "找一下上海的后端校招岗位。");
        Job alpha = ledger.addLead(PROJECT, research, Actor.AGENT, TestStorage.posting("示例公司甲", "JD-1"), "虚构 JD");
        Job beta = ledger.addLead(PROJECT, research, Actor.AGENT, TestStorage.posting("示例公司乙", "JD-2"), null);
        ledger.addLead(PROJECT, research, Actor.AGENT, TestStorage.posting("示例公司丙", "JD-3"), null);
        ledger.transition(PROJECT, beta.id(), research, Actor.USER, new Transition.MarkLead(LeadStatus.SKIPPED, "方向不符"));
        ledger.transition(PROJECT, alpha.id(), research, Actor.USER, new Transition.MarkLead(LeadStatus.SHORTLISTED, null));
        sessions.appendTranscript(PROJECT, research, "assistant", "找到 3 个候选，你标了 1 个想投。");
        sessions.writeHandoff(PROJECT, research, "调研出 3 个候选，1 个想投，1 个不投。");

        // Session 2：投递，直到结束
        storage.clock.advance(Duration.ofDays(1));
        String apply = sessions.create(PROJECT, "投递示例公司甲").id();
        List<Transition> steps = List.of(
                new Transition.Convert(),
                new Transition.ChangePrepState(PrepState.PREPARING),
                new Transition.ChangePrepState(PrepState.AWAITING_REVIEW),
                new Transition.ChangePrepState(PrepState.USER_SUBMITTING),
                new Transition.MarkApplied(new Evidence("success-page", "投递成功（虚构原文）", null,
                        "2026-10-23T10:05:00-04:00", "2026-10-23T10:06:00-04:00")),
                new Transition.ChangePhase(Phase.INTERVIEW),
                new Transition.Close(Outcome.REJECTED, "一面"));
        for (Transition step : steps) {
            ledger.transition(PROJECT, alpha.id(), apply, Actor.USER, step);
        }

        // 最终状态
        Job finished = new JobRepository(storage.layout).load(PROJECT, alpha.id());
        assertThat(finished.status().phase()).isEqualTo(Phase.CLOSED);
        assertThat(finished.status().outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(finished.status().evidence().kind()).isEqualTo("success-page");
        assertThat(storage.read(storage.layout.jobFile(PROJECT, alpha.id()))).contains("## JD 快照\n\n虚构 JD");

        // 事件：1 条新增 + 1 条标记 + 7 步申请，并且各自属于正确的 Session
        List<JobEvent> events = ledger.events(PROJECT, alpha.id());
        assertThat(events).hasSize(9);
        assertThat(events.subList(0, 2)).allMatch(event -> event.session().equals(research));
        assertThat(events.subList(2, 9)).allMatch(event -> event.session().equals(apply));
        assertThat(events.getLast().to()).isEqualTo("closed:rejected");
        assertThat(events.getLast().reason()).isEqualTo("一面");

        // Session：自动标签、涉及的岗位、对话原文、交接摘要
        SessionRecord researchSession = sessions.load(PROJECT, research);
        SessionRecord applySession = sessions.load(PROJECT, apply);
        assertThat(researchSession.tags()).containsExactly("research");
        assertThat(researchSession.jobs()).hasSize(3);
        assertThat(researchSession.handoff()).isEqualTo("调研出 3 个候选，1 个想投，1 个不投。");
        assertThat(sessions.readTranscript(PROJECT, research)).hasSize(2);
        assertThat(applySession.tags()).containsExactly("apply");
        assertThat(applySession.jobs()).containsExactly(alpha.id());

        // 台账
        Path tracker = storage.layout.trackerDir(PROJECT);
        assertThat(storage.read(tracker.resolve("leads.md"))).contains("方向不符").contains("| converted |");
        assertThat(storage.read(tracker.resolve("applications.md")))
                .contains("| 1 | 示例公司甲 |")
                .contains("| 10-23 | closed · rejected |")
                .doesNotContain("示例公司乙");
        assertThat(storage.read(storage.layout.jobsIndex()))
                .contains("| fall-2026-v1 | 示例公司乙 |")
                .contains("| closed · rejected |");
        assertThat(storage.read(storage.layout.exportsApplicationsCsv()).split("\r\n", -1)).hasSize(5);

        // 有效资料
        Map<String, ResolvedFact> profile = new ProfileResolver(storage.layout, storage.clock).resolve(PROJECT, List.of());
        assertThat(profile.get("education.school").value()).isEqualTo("示例大学（2026 写法）");
        assertThat(profile.get("contact.name").label()).isEqualTo("来自 base（pin live）");

        // 历史：每个写操作一次提交，写过的文件全部已提交
        int writes = 1 + 1 + 3 + 2 + 1 + 1 + 1 + steps.size();
        assertThat(storage.commitCount()).isEqualTo(commitsAfterSetup + writes);
        assertThat(storage.isClean()).isTrue();
        assertThat(FrontmatterFile.read(storage.layout.sessionFile(PROJECT, apply)).frontmatter())
                .containsEntry("created_at", "2026-10-23T10:00:00-04:00");
    }
}
