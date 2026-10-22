package jobapply.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import jobapply.domain.Actor;
import jobapply.domain.Evidence;
import jobapply.domain.Job;
import jobapply.domain.JobPosting;
import jobapply.domain.Outcome;
import jobapply.domain.Phase;
import jobapply.domain.PrepState;
import jobapply.domain.Transition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrackerWriterTest {

    private static final String PROJECT = "fall-2026-v1";
    private static final Evidence EVIDENCE = new Evidence("history-record", "已投递（虚构原文）",
            "2026-10-16 09:30", "2026-10-16T09:31:00-04:00", "2026-10-16T09:35:00-04:00");

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
    void applicationsAreGroupedByPhaseAndNumberedById() {
        Job closed = convert(TestStorage.posting("示例公司丙", "JD-1"));
        Job interview = convert(TestStorage.posting("示例公司乙", "JD-2"));
        Job prep = convert(TestStorage.posting("示例公司甲", "JD-3"));
        ledger.addLead(PROJECT, session, Actor.AGENT, TestStorage.posting("示例公司丁", "JD-4"), null);
        move(closed, new Transition.Close(Outcome.NOT_SUBMITTED, "转投别家"));
        for (PrepState state : List.of(PrepState.PREPARING, PrepState.AWAITING_REVIEW, PrepState.USER_SUBMITTING)) {
            move(interview, new Transition.ChangePrepState(state));
        }
        move(interview, new Transition.MarkApplied(EVIDENCE));
        move(interview, new Transition.ChangePhase(Phase.INTERVIEW));

        String md = storage.read(storage.layout.trackerDir(PROJECT).resolve("applications.md"));

        assertThat(md).startsWith("> 生成文件，请勿手改。\n");
        List<String> rows = md.lines()
                .filter(line -> line.startsWith("| ") && !line.startsWith("| #") && !line.startsWith("| ---"))
                .toList();
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).startsWith("| 3 | 示例公司甲 |").contains("| prep · selected |");
        assertThat(rows.get(1)).startsWith("| 2 | 示例公司乙 |").contains("| 10-16 | interview | — |");
        assertThat(rows.get(2)).startsWith("| 1 | 示例公司丙 |").contains("| closed · not_submitted |");
        assertThat(rows.get(1)).contains("[Backend Engineer](../jobs/" + interview.id() + "/job.md)");
        assertThat(md).doesNotContain("示例公司丁");
    }

    @Test
    void leadsPutConvertedLastAndGroupByCompany() {
        ledger.addLead(PROJECT, session, Actor.AGENT, TestStorage.posting("示例公司乙", "JD-1"), null);
        Job converted = convert(TestStorage.posting("示例公司甲", "JD-2"));
        ledger.addLead(PROJECT, session, Actor.AGENT, TestStorage.posting("示例公司乙", "JD-3"), null);
        ledger.addLead(PROJECT, session, Actor.AGENT, TestStorage.posting("示例公司甲", "JD-4"), null);

        String md = storage.read(storage.layout.trackerDir(PROJECT).resolve("leads.md"));
        List<String> companies = md.lines().filter(line -> line.startsWith("| 示例公司"))
                .map(line -> line.split(" \\| ")[0].substring(2) + "/" + line.contains("| converted |"))
                .toList();

        // 公司按 Unicode 码点排序（乙 U+4E59 在 甲 U+7532 之前），结果不依赖 JDK 的本地化规则
        assertThat(companies).containsExactly("示例公司乙/false", "示例公司乙/false", "示例公司甲/false",
                "示例公司甲/true");
        assertThat(md).contains("[" + session + "](../sessions/" + session + "/session.md)");
        assertThat(converted.status().phase()).isEqualTo(Phase.PREP);
    }

    @Test
    void csvIsExcelSafe() {
        JobPosting tricky = new JobPosting("=SUM(1)", null, "Backend, \"Infra\"\n二线", "JD-9", null,
                List.of("上海", "杭州"), null, null, null, null);
        convert(tricky);

        String csv = storage.read(storage.layout.trackerDir(PROJECT).resolve("applications.csv"));
        String md = storage.read(storage.layout.trackerDir(PROJECT).resolve("applications.md"));

        assertThat(csv).startsWith("﻿id,project,company,");
        assertThat(csv.split("\r\n", -1)).hasSize(3);
        assertThat(csv).contains(",'=SUM(1),").contains(",\"Backend, \"\"Infra\"\"\n二线\",")
                .contains(",上海; 杭州,").contains(",prep,selected,");
        assertThat(md).contains("Backend, \"Infra\" 二线").contains("| 上海、杭州 |");
    }

    @Test
    void globalIndexHasProjectColumnAndLeads() {
        storage.project("spring-2027-v2", "live");
        String other = storage.session("spring-2027-v2");
        ledger.addLead(PROJECT, session, Actor.AGENT, TestStorage.posting("示例公司甲", "JD-1"), null);
        Job converted = ledger.addLead("spring-2027-v2", other, Actor.AGENT, TestStorage.posting("示例公司乙", "JD-2"), null);
        ledger.transition("spring-2027-v2", converted.id(), other, Actor.USER, new Transition.Convert());

        String index = storage.read(storage.layout.jobsIndex());
        String exported = storage.read(storage.layout.exportsApplicationsCsv());

        assertThat(index).startsWith("> 生成文件，请勿手改。\n")
                .contains("| Project | 公司 | 岗位 | 状态 | 发现于 | 链接 |")
                .contains("| fall-2026-v1 | 示例公司甲 | [Backend Engineer](projects/fall-2026-v1/jobs/")
                .contains("| spring-2027-v2 | 示例公司乙 |")
                .contains("| prep · selected |")
                .contains("| new |");
        assertThat(exported.split("\r\n", -1)).hasSize(4);
    }

    private Job convert(JobPosting posting) {
        Job job = ledger.addLead(PROJECT, session, Actor.AGENT, posting, null);
        return ledger.transition(PROJECT, job.id(), session, Actor.USER, new Transition.Convert());
    }

    private void move(Job job, Transition transition) {
        ledger.transition(PROJECT, job.id(), session, Actor.USER, transition);
    }
}
