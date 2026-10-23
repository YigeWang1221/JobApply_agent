package jobapply.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import jobapply.domain.Actor;
import jobapply.domain.Job;
import jobapply.domain.SessionRecord;
import jobapply.domain.Transition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionRepositoryTest {

    private static final String PROJECT = "fall-2026-v1";

    @TempDir
    Path root;

    private TestStorage storage;
    private SessionRepository sessions;

    @BeforeEach
    void setUp() {
        storage = TestStorage.create(root);
        storage.project(PROJECT, "live");
        sessions = storage.sessions();
    }

    @Test
    void sessionIdsAreNumberedAcrossProjects() {
        storage.project("spring-2027-v2", "live");

        SessionRecord first = sessions.create(PROJECT, "调研");
        SessionRecord second = sessions.create("spring-2027-v2", "投递");

        assertThat(first.id()).isEqualTo("s-2026-10-22-001");
        assertThat(second.id()).isEqualTo("s-2026-10-22-002");
        assertThat(FrontmatterFile.read(storage.layout.sessionFile(PROJECT, first.id())).frontmatter())
                .containsEntry("tags", List.of("jobapply/session"))
                .containsEntry("created_at", "2026-10-22T10:00:00-04:00");
    }

    @Test
    void tagsAndJobsAreDerivedFromEvents() {
        String research = sessions.create(PROJECT, "调研").id();
        String apply = sessions.create(PROJECT, "投递").id();
        JobLedger ledger = storage.ledger();
        Job first = ledger.addLead(PROJECT, research, Actor.AGENT, TestStorage.posting("示例公司甲", "JD-1"), null);
        Job second = ledger.addLead(PROJECT, research, Actor.AGENT, TestStorage.posting("示例公司乙", "JD-2"), null);

        ledger.transition(PROJECT, second.id(), apply, Actor.USER, new Transition.Convert());

        SessionRecord researchSession = sessions.load(PROJECT, research);
        SessionRecord applySession = sessions.load(PROJECT, apply);
        assertThat(researchSession.tags()).containsExactly("research");
        assertThat(researchSession.jobs()).containsExactly(first.id(), second.id());
        assertThat(applySession.tags()).containsExactly("apply");
        assertThat(applySession.jobs()).containsExactly(second.id());
        assertThat(FrontmatterFile.read(storage.layout.sessionFile(PROJECT, apply)).frontmatter())
                .containsEntry("tags", List.of("jobapply/session", "apply"));
    }

    @Test
    void transcriptIsAppendedLineByLine() {
        String id = sessions.create(PROJECT, "对话").id();

        sessions.appendTranscript(PROJECT, id, "user", "下一家投什么？");
        storage.clock.set("2026-10-22T14:01:00Z");
        sessions.appendTranscript(PROJECT, id, "assistant", "候选池里有 2 个新岗位。");

        List<Map<String, Object>> lines = sessions.readTranscript(PROJECT, id);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).containsEntry("role", "user").containsEntry("text", "下一家投什么？");
        assertThat(lines.get(1)).containsEntry("at", "2026-10-22T10:01:00-04:00");
        assertThat(storage.read(storage.layout.transcript(PROJECT, id)).lines()).hasSize(2);
    }

    @Test
    void handoffSummaryReplacesThePreviousOne() {
        String id = sessions.create(PROJECT, "对话").id();

        sessions.writeHandoff(PROJECT, id, "本次调研出 3 个候选。");
        sessions.writeHandoff(PROJECT, id, "本次调研出 12 个候选，投了 2 个，另有 1 个停在关卡 B。");

        assertThat(sessions.load(PROJECT, id).handoff()).isEqualTo("本次调研出 12 个候选，投了 2 个，另有 1 个停在关卡 B。");
        assertThat(storage.read(storage.layout.sessionFile(PROJECT, id)))
                .contains("# 对话\n\n## 交接摘要\n\n本次调研出 12 个候选")
                .doesNotContain("本次调研出 3 个候选");
    }
}
