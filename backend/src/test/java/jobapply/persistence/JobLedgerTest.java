package jobapply.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import jobapply.domain.Actor;
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

    @Nested
    class Leads {

        @TempDir
        Path root;

        private TestStorage storage;
        private JobLedger ledger;
        private String session;

        @BeforeEach
        void setUp() {
            storage = TestStorage.create(root, true);
            storage.project(PROJECT, "live");
            session = storage.session(PROJECT);
            ledger = storage.ledger();
        }

        @Test
        void addingLeadWritesJobEventTrackerAndCommit() {
            int commitsBefore = storage.commitCount();

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
            assertThat(storage.commitCount()).isEqualTo(commitsBefore + 1);
            assertThat(storage.headMessage()).startsWith("job 2026-10-22-001：新增候选 示例公司甲");
            assertThat(storage.trackedFiles()).contains(
                    "projects/fall-2026-v1/jobs/2026-10-22-001/job.md",
                    "projects/fall-2026-v1/jobs/2026-10-22-001/events.jsonl",
                    "projects/fall-2026-v1/tracker/leads.md",
                    "jobs-index.md");
        }

        @Test
        void duplicateLeadIsRejectedWithExistingId() {
            Job first = ledger.addLead(PROJECT, session, Actor.AGENT, TestStorage.posting("示例公司甲", "JD-001"), null);
            JobPosting sameJob = new JobPosting(" 示例公司甲 ", "2027 校园招聘 ", "后端工程师", "jd-001", null, null,
                    null, null, null, null);
            int commitsBefore = storage.commitCount();

            assertThatThrownBy(() -> ledger.addLead(PROJECT, session, Actor.AGENT, sameJob, null))
                    .isInstanceOfSatisfying(DuplicateLeadException.class,
                            e -> assertThat(e.existingJobId()).isEqualTo(first.id()));
            assertThat(new JobRepository(storage.layout).list(PROJECT)).hasSize(1);
            assertThat(storage.commitCount()).isEqualTo(commitsBefore);
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
            assertThat(storage.headMessage()).startsWith("job 2026-10-22-002：lead:shortlisted → prep:selected");
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
        void unknownSessionIsRejected() {
            assertThatThrownBy(() -> ledger.addLead(PROJECT, "s-2026-10-22-999", Actor.AGENT,
                    TestStorage.posting("示例公司甲", "JD-001"), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Session 不存在");
        }
    }

    @Nested
    class Atomicity {

        @TempDir
        Path root;

        @Test
        void rejectedTransitionsChangeNothing() {
            TestStorage storage = TestStorage.create(root, true);
            storage.project(PROJECT, "live");
            String session = storage.session(PROJECT);
            JobLedger ledger = storage.ledger();
            Job job = ledger.addLead(PROJECT, session, Actor.AGENT, TestStorage.posting("示例公司甲", "JD-001"), null);
            ledger.transition(PROJECT, job.id(), session, Actor.USER, new Transition.Convert());
            ledger.transition(PROJECT, job.id(), session, Actor.AGENT,
                    new Transition.ChangePrepState(PrepState.PREPARING));

            Map<String, String> before = storage.snapshot();
            String head = storage.git().head().orElseThrow();

            assertThatThrownBy(() -> ledger.transition(PROJECT, job.id(), session, Actor.AGENT,
                    new Transition.MarkApplied(null))).isInstanceOf(IllegalTransitionException.class);
            assertThatThrownBy(() -> ledger.transition(PROJECT, job.id(), session, Actor.AGENT,
                    new Transition.ChangePrepState(PrepState.USER_SUBMITTING)))
                    .isInstanceOf(IllegalTransitionException.class);
            assertThatThrownBy(() -> ledger.transition(PROJECT, job.id(), session, Actor.AGENT,
                    new Transition.Convert())).isInstanceOf(IllegalTransitionException.class);

            assertThat(storage.snapshot()).isEqualTo(before);
            assertThat(storage.git().head()).contains(head);
        }
    }

    @Nested
    class WithoutGit {

        @TempDir
        Path root;

        @Test
        void everythingWorksJustNoCommits() {
            TestStorage storage = TestStorage.create(root, false);
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
            assertThat(storage.layout.gitDir()).doesNotExist();
        }
    }
}
