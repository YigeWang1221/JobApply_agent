package jobapply.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import jobapply.domain.Actor;
import jobapply.domain.Fact;
import jobapply.domain.Job;
import jobapply.memory.ProfileResolver;
import jobapply.memory.ResolvedFact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JobAnswersTest {

    private static final String PROJECT = "fall-2026-v1";

    @TempDir
    Path root;

    private TestStorage storage;
    private JobAnswers answers;
    private String session;
    private String jobId;
    private Path jobFile;

    @BeforeEach
    void setUp() {
        storage = TestStorage.create(root);
        storage.project(PROJECT, "live");
        session = storage.session(PROJECT);
        Job job = storage.ledger().addLead(PROJECT, session, Actor.AGENT, TestStorage.posting("示例公司甲", "JD-001"),
                "职位描述（虚构）");
        jobId = job.id();
        jobFile = storage.layout.jobFile(PROJECT, jobId);
        answers = new JobAnswers(storage.layout, storage.clock);
    }

    @Test
    void appendsToSectionWithIdsSessionAndTimeAndKeepsTheRestOfJobMd() {
        String before = storage.read(jobFile);

        List<JobAnswers.Answer> written = answers.append(PROJECT, jobId, session, List.of(
                new JobAnswers.NewAnswer("毕业论文题目", "虚构的论文题目"),
                new JobAnswers.NewAnswer("最早到岗时间", "2027-07")));

        assertThat(written).extracting(JobAnswers.Answer::id).containsExactly("ans-001", "ans-002");
        assertThat(written).allSatisfy(answer -> {
            assertThat(answer.session()).isEqualTo(session);
            assertThat(answer.at()).isEqualTo("2026-10-22T10:00:00-04:00");
        });
        String after = storage.read(jobFile);
        String frontmatter = before.substring(0, before.indexOf("\n---\n") + 5);
        assertThat(after).startsWith(frontmatter);
        assertThat(after).contains("## JD 快照\n\n职位描述（虚构）\n\n## 资格映射\n\n## 本次答案\n\n### ans-001\n"
                + "- 问题：毕业论文题目\n- session：" + session + "\n- 时间：2026-10-22T10:00:00-04:00\n\n> 虚构的论文题目\n\n"
                + "### ans-002\n");
        assertThat(after).endsWith("> 2027-07\n\n## 授权\n\n## 证据\n");
    }

    @Test
    void secondBatchIsAppendedNotOverwritten() {
        answers.append(PROJECT, jobId, session, List.of(new JobAnswers.NewAnswer("毕业论文题目", "虚构题目一")));
        storage.clock.set("2026-10-22T15:00:00Z");

        answers.append(PROJECT, jobId, session, List.of(new JobAnswers.NewAnswer("实习经历补充", "虚构补充")));

        List<JobAnswers.Answer> all = answers.list(PROJECT, jobId);
        assertThat(all).extracting(JobAnswers.Answer::id).containsExactly("ans-001", "ans-002");
        assertThat(all).extracting(JobAnswers.Answer::value).containsExactly("虚构题目一", "虚构补充");
        assertThat(all.get(1).at()).isEqualTo("2026-10-22T11:00:00-04:00");
        assertThat(storage.read(jobFile)).endsWith("> 虚构补充\n\n## 授权\n\n## 证据\n");
    }

    @Test
    void multiLineValueWithHeadingsRoundTrips() {
        String value = "第一行\n## 看起来像标题\n\n### ans-999";
        answers.append(PROJECT, jobId, session, List.of(new JobAnswers.NewAnswer("自我介绍\n（200 字）", value)));

        JobAnswers.Answer answer = answers.list(PROJECT, jobId).getFirst();
        assertThat(answer.id()).isEqualTo("ans-001");
        assertThat(answer.question()).isEqualTo("自我介绍 （200 字）");
        assertThat(answer.value()).isEqualTo(value);
        assertThat(storage.read(jobFile)).contains("## 授权\n\n## 证据\n");
    }

    @Test
    void classCQuestionRejectsTheWholeBatchAndWritesNothing() {
        Map<String, String> before = storage.snapshot();

        assertThatThrownBy(() -> answers.append(PROJECT, jobId, session, List.of(
                new JobAnswers.NewAnswer("毕业论文题目", "虚构题目"),
                new JobAnswers.NewAnswer("身份证号", "110101200001011234"))))
                .isInstanceOf(JobAnswers.ClassCQuestionException.class)
                .hasMessageContaining("身份证号")
                .hasMessageNotContaining("110101200001011234");
        assertThatThrownBy(() -> answers.append(PROJECT, jobId, session, List.of(
                new JobAnswers.NewAnswer("Do you require visa sponsorship?", "No"))))
                .isInstanceOf(JobAnswers.ClassCQuestionException.class);
        assertThatThrownBy(() -> answers.append(PROJECT, jobId, session, List.of(
                new JobAnswers.NewAnswer("期望薪资", "虚构"))))
                .isInstanceOf(JobAnswers.ClassCQuestionException.class);

        assertThat(storage.snapshot()).isEqualTo(before);
    }

    @Test
    void blankValueOrMissingJobIsRejected() {
        assertThatThrownBy(() -> answers.append(PROJECT, jobId, session,
                List.of(new JobAnswers.NewAnswer("毕业论文题目", " "))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> answers.append(PROJECT, "2026-10-22-999", session,
                List.of(new JobAnswers.NewAnswer("毕业论文题目", "虚构"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Job 不存在");
    }

    @Test
    void answersAreTheSessionLayerOfProfileResolver() {
        answers.append(PROJECT, jobId, session, List.of(new JobAnswers.NewAnswer("毕业论文题目", "虚构的论文题目")));

        List<Fact> facts = answers.facts(PROJECT, jobId);
        Map<String, ResolvedFact> resolved = new ProfileResolver(storage.layout, storage.clock)
                .resolve(PROJECT, facts);

        ResolvedFact answer = resolved.get("ans-001");
        assertThat(answer.value()).isEqualTo("虚构的论文题目");
        assertThat(answer.layer()).isEqualTo(ResolvedFact.Layer.SESSION);
        assertThat(answer.label()).isEqualTo("来自本 Session 答案");
    }

    @Test
    void createsTheSectionWhenJobMdHasNone() {
        String content = storage.read(jobFile);
        storage.write(jobFile, content.replace("## 本次答案\n\n", ""));

        answers.append(PROJECT, jobId, session, List.of(new JobAnswers.NewAnswer("毕业论文题目", "虚构题目")));

        assertThat(storage.read(jobFile)).endsWith("## 证据\n\n## 本次答案\n\n### ans-001\n- 问题：毕业论文题目\n"
                + "- session：" + session + "\n- 时间：2026-10-22T10:00:00-04:00\n\n> 虚构题目\n\n");
        assertThat(answers.list(PROJECT, jobId)).hasSize(1);
    }
}
