package jobapply.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import jobapply.domain.Actor;
import jobapply.domain.Evidence;
import jobapply.domain.IllegalTransitionException;
import jobapply.domain.Job;
import jobapply.domain.JobEvent;
import jobapply.domain.JobPosting;
import jobapply.domain.LeadStatus;
import jobapply.domain.Phase;
import jobapply.domain.PrepState;
import jobapply.domain.Transition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JobLedgerTest {

    private static final String PROJECT = "fall-2026-v1";
    private static final Evidence EVIDENCE = new Evidence("success-page", "投递成功（虚构原文）", null,
            "2026-10-22T10:05:00-04:00", "2026-10-22T10:06:00-04:00");

    private static void editFrontmatter(Path file, Consumer<Map<String, Object>> edit) {
        FrontmatterFile.Document document = FrontmatterFile.read(file);
        edit.accept(document.frontmatter());
        FrontmatterFile.write(file, document);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> evidence(Map<String, Object> fm) {
        return (Map<String, Object>) fm.get("evidence");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> submitted(Map<String, Object> fm) {
        return (Map<String, Object>) evidence(fm).get("submitted");
    }

    @Nested
    class Leads {

        @TempDir
        Path root;

        private TestStorage storage;
        private JobLedger ledger;
        private String session;

        @BeforeEach
        void setUp() {
            storage = TestStorage.create(root);
            storage.project(PROJECT, "live");
            session = storage.session(PROJECT);
            ledger = storage.ledger();
        }

        @Test
        void addingLeadWritesJobEventTrackerAndCommit() {

            Job job = ledger.addLead(PROJECT, session, Actor.AGENT, TestStorage.posting("示例公司甲", "JD-001"),
                    "职位描述（虚构）");

            assertThat(job.id()).isEqualTo("2026-10-22-001");
            Map<String, Object> fm = FrontmatterFile.read(storage.layout.jobFile(PROJECT, job.id())).frontmatter();
            assertThat(fm).containsEntry("lead_status", "new").containsEntry("discovered_in", session)
                    .containsEntry("phase", null).containsEntry("tags", List.of("jobapply/job"));
            assertThat(storage.read(storage.layout.jobFile(PROJECT, job.id()))).contains("## JD 快照\n\n职位描述（虚构）");

            List<JobEvent> events = ledger.events(PROJECT, job.id());
            assertThat(events).singleElement().satisfies(event -> {
                assertThat(event.type()).isEqualTo(JobEvent.Type.LEAD_CREATED);
                assertThat(event.session()).isEqualTo(session);
                assertThat(event.to()).isEqualTo("lead:new");
                assertThat(event.at()).isEqualTo("2026-10-22T10:00:00-04:00");
            });

            Path tracker = storage.layout.trackerDir(PROJECT);
            assertThat(storage.read(tracker.resolve("leads.md")))
                    .contains("| 示例公司甲 | [Backend Engineer](../jobs/2026-10-22-001/job.md) |");
            assertThat(storage.read(tracker.resolve("leads.csv"))).startsWith("﻿id,project,company");
        }

        @Test
        void duplicateLeadIsRejectedWithExistingId() {
            Job first = ledger.addLead(PROJECT, session, Actor.AGENT, TestStorage.posting("示例公司甲", "JD-001"), null);
            JobPosting sameJob = new JobPosting(" 示例公司甲 ", "2027 校园招聘 ", "后端工程师", "jd-001", null, null,
                    null, null, null, null);

            assertThatThrownBy(() -> ledger.addLead(PROJECT, session, Actor.AGENT, sameJob, null))
                    .isInstanceOfSatisfying(DuplicateLeadException.class,
                            e -> assertThat(e.existingJobId()).isEqualTo(first.id()));
            assertThat(new JobRepository(storage.layout).list(PROJECT)).hasSize(1);
        }

        @Test
        void withoutJobIdTheUrlWithoutQueryIsTheKey() {
            JobPosting byUrl = new JobPosting("示例公司乙", null, "SRE", null, null, null,
                    "https://jobs.example.com/sre?from=a#top", null, null, null);
            JobPosting sameUrl = new JobPosting("示例公司乙", null, "SRE", null, null, null,
                    "https://jobs.example.com/sre?from=b", null, null, null);
            ledger.addLead(PROJECT, session, Actor.AGENT, byUrl, null);

            assertThatThrownBy(() -> ledger.addLead(PROJECT, session, Actor.AGENT, sameUrl, null))
                    .isInstanceOf(DuplicateLeadException.class);
        }

        @Test
        void skippingNeedsReasonAndConvertingStartsAtSelected() {
            Job skipped = ledger.addLead(PROJECT, session, Actor.AGENT, TestStorage.posting("示例公司甲", "JD-001"), null);
            Job chosen = ledger.addLead(PROJECT, session, Actor.AGENT, TestStorage.posting("示例公司甲", "JD-002"), null);

            assertThatThrownBy(() -> ledger.transition(PROJECT, skipped.id(), session, Actor.USER,
                    new Transition.MarkLead(LeadStatus.SKIPPED, " ")))
                    .isInstanceOf(IllegalTransitionException.class)
                    .hasMessageContaining("必须写原因");
            ledger.transition(PROJECT, skipped.id(), session, Actor.USER,
                    new Transition.MarkLead(LeadStatus.SKIPPED, "城市不合适"));
            ledger.transition(PROJECT, chosen.id(), session, Actor.USER,
                    new Transition.MarkLead(LeadStatus.SHORTLISTED, null));
            Job converted = ledger.transition(PROJECT, chosen.id(), session, Actor.USER, new Transition.Convert());

            assertThat(converted.status().phase()).isEqualTo(Phase.PREP);
            assertThat(converted.status().prepState()).isEqualTo(PrepState.SELECTED);
            Map<String, Object> fm = FrontmatterFile.read(storage.layout.jobFile(PROJECT, chosen.id())).frontmatter();
            assertThat(fm).containsEntry("lead_status", "converted").containsEntry("phase", "prep")
                    .containsEntry("prep_state", "selected");
            assertThat(storage.read(storage.layout.trackerDir(PROJECT).resolve("leads.md"))).contains("城市不合适");
        }

        @Test
        void jobIdsAreNumberedAcrossProjects() {
            storage.project("spring-2027-v2", "live");
            String otherSession = storage.session("spring-2027-v2");
            ledger.addLead(PROJECT, session, Actor.AGENT, TestStorage.posting("示例公司甲", "JD-001"), null);

            Job second = ledger.addLead("spring-2027-v2", otherSession, Actor.AGENT,
                    TestStorage.posting("示例公司甲", "JD-001"), null);

            assertThat(second.id()).isEqualTo("2026-10-22-002");
            assertThat(storage.read(storage.layout.jobsIndex()))
                    .contains("| fall-2026-v1 | 示例公司甲 |")
                    .contains("| spring-2027-v2 | 示例公司甲 |");
        }

        @Test
        void handEditedKeysSurviveTransitions() {
            Job job = ledger.addLead(PROJECT, session, Actor.AGENT, TestStorage.posting("示例公司甲", "JD-001"), null);
            Path file = storage.layout.jobFile(PROJECT, job.id());
            FrontmatterFile.Document document = FrontmatterFile.read(file);
            Map<String, Object> fm = document.frontmatter();
            fm.put("score", 8.1);
            fm.put("my_note", "手工加的备注");
            FrontmatterFile.write(file, new FrontmatterFile.Document(fm, document.body() + "\n手写的正文\n"));

            ledger.transition(PROJECT, job.id(), session, Actor.USER,
                    new Transition.MarkLead(LeadStatus.SHORTLISTED, null));

            FrontmatterFile.Document after = FrontmatterFile.read(file);
            assertThat(after.frontmatter()).containsEntry("my_note", "手工加的备注").containsEntry("score", 8.1)
                    .containsEntry("lead_status", "shortlisted");
            assertThat(after.body()).endsWith("手写的正文\n");
            assertThat(storage.read(storage.layout.trackerDir(PROJECT).resolve("leads.md"))).contains("| 8.1 |");
        }

        @Test
        void unmodeledNestedEvidenceSurvivesTransitions() {
            Job job = ledger.addLead(PROJECT, session, Actor.AGENT, TestStorage.posting("示例公司甲", "JD-001"), null);
            Path file = storage.layout.jobFile(PROJECT, job.id());
            editFrontmatter(file, fm -> fm.put("evidence",
                    new LinkedHashMap<>(Map.of("qualification", Map.of("text", "学历要求原文（虚构）")))));

            for (Transition step : List.of(new Transition.MarkLead(LeadStatus.SHORTLISTED, null), new Transition.Convert(),
                    new Transition.ChangePrepState(PrepState.PREPARING),
                    new Transition.ChangePrepState(PrepState.AWAITING_REVIEW),
                    new Transition.ChangePrepState(PrepState.USER_SUBMITTING),
                    new Transition.MarkApplied(EVIDENCE))) {
                ledger.transition(PROJECT, job.id(), session, Actor.USER, step);
            }
            editFrontmatter(file, fm -> submitted(fm).put("screenshot", "uploads/success.png"));
            ledger.transition(PROJECT, job.id(), session, Actor.USER, new Transition.ChangePhase(Phase.INTERVIEW));

            Map<String, Object> fm = FrontmatterFile.read(file).frontmatter();
            assertThat(evidence(fm)).containsEntry("qualification", Map.of("text", "学历要求原文（虚构）"));
            assertThat(submitted(fm)).containsEntry("kind", "success-page")
                    .containsEntry("screenshot", "uploads/success.png");
        }

        @Test
        void unknownSessionIsRejected() {
            assertThatThrownBy(() -> ledger.addLead(PROJECT, "s-2026-10-22-999", Actor.AGENT,
                    TestStorage.posting("示例公司甲", "JD-001"), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Session 不存在");
        }
    }

    @Nested
    class InputValidation {

        @TempDir
        Path root;

        private TestStorage storage;
        private JobLedger ledger;
        private String sessionA;
        private String sessionB;
        private Job jobInB;

        @BeforeEach
        void setUp() {
            storage = TestStorage.create(root);
            storage.project("a", "live");
            storage.project("b", "live");
            sessionA = storage.session("a");
            sessionB = storage.session("b");
            ledger = storage.ledger();
            jobInB = ledger.addLead("b", sessionB, Actor.AGENT, TestStorage.posting("示例公司甲", "JD-001"), null);
        }

        @Test
        void pathTraversalIdsAreRejectedAndNothingChanges() {
            Map<String, String> before = storage.snapshot();
            Transition shortlist = new Transition.MarkLead(LeadStatus.SHORTLISTED, null);

            assertThatThrownBy(() -> ledger.transition("a", "../../b/jobs/" + jobInB.id(), sessionA, Actor.USER, shortlist))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("job id");
            assertThatThrownBy(() -> ledger.transition("a", jobInB.id(), "../../b/sessions/" + sessionB, Actor.USER,
                    shortlist)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("session id");
            assertThatThrownBy(() -> ledger.addLead("../b", sessionB, Actor.AGENT,
                    TestStorage.posting("示例公司乙", "JD-002"), null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("project id");

            assertThat(storage.snapshot()).isEqualTo(before);
        }

        @Test
        void missingArgumentsAreRejectedBeforeAnyWrite() {
            Map<String, String> before = storage.snapshot();

            assertThatThrownBy(() -> ledger.addLead("b", sessionB, null, TestStorage.posting("示例公司乙", "JD-002"), null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("actor");
            assertThatThrownBy(() -> ledger.addLead("b", sessionB, Actor.AGENT, null, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("posting");
            assertThatThrownBy(() -> ledger.transition("b", jobInB.id(), sessionB, null, new Transition.Convert()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("actor");
            assertThatThrownBy(() -> ledger.transition("b", jobInB.id(), sessionB, Actor.USER, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("transition");

            assertThat(storage.snapshot()).isEqualTo(before);
        }

        @Test
        void strayDirectoriesAreIgnoredWhenListing() {
            storage.write(storage.layout.jobsDir("b").resolve("notes/readme.md"), "用户自己的笔记");
            storage.write(storage.layout.sessionsDir("b").resolve("draft/readme.md"), "草稿");
            storage.write(storage.layout.projectsDir().resolve("Old Stuff/readme.md"), "旧东西");

            Job next = ledger.addLead("b", sessionB, Actor.AGENT, TestStorage.posting("示例公司乙", "JD-002"), null);

            assertThat(next.id()).isEqualTo("2026-10-22-002");
            assertThat(new JobRepository(storage.layout).list("b")).hasSize(2);
            assertThat(storage.session("b")).isEqualTo("s-2026-10-22-003");
        }
    }

    @Nested
    class Atomicity {

        @TempDir
        Path root;

        @Test
        void rejectedTransitionsChangeNothing() {
            TestStorage storage = TestStorage.create(root);
            storage.project(PROJECT, "live");
            String session = storage.session(PROJECT);
            JobLedger ledger = storage.ledger();
            Job job = ledger.addLead(PROJECT, session, Actor.AGENT, TestStorage.posting("示例公司甲", "JD-001"), null);
            ledger.transition(PROJECT, job.id(), session, Actor.USER, new Transition.Convert());
            ledger.transition(PROJECT, job.id(), session, Actor.AGENT,
                    new Transition.ChangePrepState(PrepState.PREPARING));

            Map<String, String> before = storage.snapshot();

            assertThatThrownBy(() -> ledger.transition(PROJECT, job.id(), session, Actor.AGENT,
                    new Transition.MarkApplied(null))).isInstanceOf(IllegalTransitionException.class);
            assertThatThrownBy(() -> ledger.transition(PROJECT, job.id(), session, Actor.AGENT,
                    new Transition.ChangePrepState(PrepState.USER_SUBMITTING)))
                    .isInstanceOf(IllegalTransitionException.class);
            assertThatThrownBy(() -> ledger.transition(PROJECT, job.id(), session, Actor.AGENT,
                    new Transition.Convert())).isInstanceOf(IllegalTransitionException.class);

            assertThat(storage.snapshot()).isEqualTo(before);
        }
    }

    @Nested
    class WithoutGit {

        @TempDir
        Path root;

        @Test
        void everythingWorksJustNoCommits() {
            TestStorage storage = TestStorage.create(root);
            storage.project(PROJECT, "live");
            String session = storage.session(PROJECT);
            JobLedger ledger = storage.ledger();

            Job job = ledger.addLead(PROJECT, session, Actor.AGENT, TestStorage.posting("示例公司甲", "JD-001"), null);
            ledger.transition(PROJECT, job.id(), session, Actor.USER, new Transition.Convert());
            Job preparing = ledger.transition(PROJECT, job.id(), session, Actor.AGENT,
                    new Transition.ChangePrepState(PrepState.PREPARING));

            assertThat(preparing.status().phase()).isEqualTo(Phase.PREP);
            assertThat(ledger.events(PROJECT, job.id())).hasSize(3);
            assertThat(storage.read(storage.layout.trackerDir(PROJECT).resolve("applications.md")))
                    .contains("prep · preparing");
            assertThat(storage.read(storage.layout.jobsIndex())).contains("示例公司甲");
        }
    }
}
