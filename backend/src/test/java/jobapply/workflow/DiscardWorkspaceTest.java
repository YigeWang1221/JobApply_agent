package jobapply.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import jobapply.api.ApplicationController;
import jobapply.domain.Actor;
import jobapply.persistence.JobAnswers;
import jobapply.persistence.TestStorage;
import jobapply.tools.BrowserSession;
import jobapply.tools.FakeBrowser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscardWorkspaceTest {

    @Test
    void discardsWorkspaceOnlyOrMovesJobToTrashWithTrackerRebuilt(@TempDir Path dir) {
        TestStorage storage = TestStorage.create(dir.resolve("storage"));
        storage.project("alpha", "live");
        String session = storage.session("alpha");

        String jobA = storage.ledger().addLead("alpha", session, Actor.USER,
                TestStorage.posting("公司甲", "https://jobs.example.com/1"), null).id();

        storage.clock.set("2026-10-23T10:00:00Z");
        String sessionB = storage.session("alpha");
        String jobB = storage.ledger().addLead("alpha", sessionB, Actor.USER,
                TestStorage.posting("公司乙", "https://jobs.example.com/2"), null).id();

        // 写入虚构运行记录 runs/<job>.json、草稿 fill-report.md、答案、uploads
        storage.write(storage.layout.agentRun(jobA), "{\"job\":\"" + jobA + "\"}");
        storage.write(storage.layout.fillReport("alpha", jobA), "# 填写报告 · 草稿\n\n草稿内容");
        JobAnswers answers = new JobAnswers(storage.layout, storage.clock);
        answers.append("alpha", jobA, session, List.of(new JobAnswers.NewAnswer("毕业院校", "虚构大学")));
        storage.write(storage.layout.uploadsDir(jobA).resolve("resume.pdf"), "虚构简历");
        storage.write(storage.layout.jobAttachmentsDir(jobA).resolve("photo.png"), "虚构照片");

        FakeBrowser fake = FakeBrowser.create(dir.resolve("browser"));
        try (BrowserSession browser = new BrowserSession(fake.launch());
             ApplicationRunner runner = new ApplicationRunner(storage.layout, storage.clock, browser, () -> null, null)) {

            // ---- 模式一：deleteJob: false（只删工作台）----
            ApplicationRunner.Discarded threadDiscarded = runner.discard(jobA, false);
            assertThat(threadDiscarded.id()).isEqualTo(jobA);
            assertThat(threadDiscarded.mode()).isEqualTo("thread");

            // 运行记录、草稿报告、uploads 删除了
            assertThat(storage.layout.agentRun(jobA)).doesNotExist();
            assertThat(storage.layout.fillReport("alpha", jobA)).doesNotExist();
            assertThat(storage.layout.uploadsDir(jobA)).doesNotExist();
            assertThat(runner.run(jobA)).isEmpty();

            // job.md 还在，且 ## 本次答案 已被清理
            assertThat(storage.layout.jobFile("alpha", jobA)).exists();
            assertThat(storage.read(storage.layout.jobFile("alpha", jobA))).doesNotContain("## 本次答案");

            // 台账里这个 Job 还在
            assertThat(storage.read(storage.layout.trackerDir("alpha").resolve("leads.md"))).contains(jobA);
            assertThat(storage.read(storage.layout.jobsIndex())).contains(jobA).contains(jobB);

            // ---- 模式二：deleteJob: true（删 Job 并挪进回收站）----
            // 重新写入一些临时数据与答案，验证删除时一并清理
            storage.write(storage.layout.agentRun(jobA), "{\"job\":\"" + jobA + "\"}");
            storage.write(storage.layout.fillReport("alpha", jobA), "# 填写报告 · 草稿\n");
            answers.append("alpha", jobA, session, List.of(new JobAnswers.NewAnswer("论文指导教师", "张教授")));
            storage.write(storage.layout.uploadsDir(jobA).resolve("resume2.pdf"), "虚构2");

            ApplicationRunner.Discarded jobDiscarded = runner.discard(jobA, true);
            assertThat(jobDiscarded.id()).isEqualTo(jobA);
            assertThat(jobDiscarded.mode()).isEqualTo("job");

            // 运行记录、草稿报告、uploads、以及 Job 目录本身都不在了
            assertThat(storage.layout.agentRun(jobA)).doesNotExist();
            assertThat(storage.layout.fillReport("alpha", jobA)).doesNotExist();
            assertThat(storage.layout.uploadsDir(jobA)).doesNotExist();
            assertThat(storage.layout.jobDir("alpha", jobA)).doesNotExist();

            // Job 目录进入 .trash/
            Path jobTrash = storage.layout.trashEntries().stream()
                    .filter(entry -> entry.getFileName().toString().contains("-job-" + jobA))
                    .findFirst().orElseThrow();
            assertThat(jobTrash.resolve("projects/alpha/jobs/" + jobA + "/job.md")).exists();

            // 台账中不再有该 Job，但保留别的 Job
            assertThat(storage.read(storage.layout.trackerDir("alpha").resolve("leads.md")))
                    .doesNotContain(jobA).contains(jobB);
            assertThat(storage.read(storage.layout.trackerDir("alpha").resolve("leads.csv")))
                    .doesNotContain(jobA).contains(jobB);
            assertThat(storage.read(storage.layout.jobsIndex()))
                    .doesNotContain(jobA).contains(jobB);
            assertThat(storage.read(storage.layout.exportsApplicationsCsv()))
                    .doesNotContain(jobA);

            // 不存在的 Job 抛 NotFound
            assertThatThrownBy(() -> runner.discard("nonexistent-job", false))
                    .isInstanceOf(ApplicationException.NotFound.class);
        }
    }

    @Test
    void controllerDiscardsWorkspaceHandlingEmptyBodyAndDefaults(@TempDir Path dir) {
        TestStorage storage = TestStorage.create(dir.resolve("storage"));
        storage.project("alpha", "live");
        String session = storage.session("alpha");
        String jobId = storage.ledger().addLead("alpha", session, Actor.USER,
                TestStorage.posting("公司甲", "https://jobs.example.com/1"), null).id();

        FakeBrowser fake = FakeBrowser.create(dir.resolve("browser"));
        try (BrowserSession browser = new BrowserSession(fake.launch());
             ApplicationRunner runner = new ApplicationRunner(storage.layout, storage.clock, browser, () -> null, null)) {
            ApplicationController controller = new ApplicationController(runner);

            // body 为 null：默认 deleteJob: false，返回 mode: "thread"
            ApplicationRunner.Discarded d1 = controller.discardWorkspace(jobId, null);
            assertThat(d1.id()).isEqualTo(jobId);
            assertThat(d1.mode()).isEqualTo("thread");
            assertThat(storage.layout.jobFile("alpha", jobId)).exists();

            // body 内 deleteJob 为 null：默认 deleteJob: false
            ApplicationRunner.Discarded d2 = controller.discardWorkspace(jobId, new ApplicationController.DiscardRequest(null));
            assertThat(d2.id()).isEqualTo(jobId);
            assertThat(d2.mode()).isEqualTo("thread");
            assertThat(storage.layout.jobFile("alpha", jobId)).exists();

            // body 内 deleteJob 为 true：mode 为 "job"，Job 移入回收站
            ApplicationRunner.Discarded d3 = controller.discardWorkspace(jobId, new ApplicationController.DiscardRequest(true));
            assertThat(d3.id()).isEqualTo(jobId);
            assertThat(d3.mode()).isEqualTo("job");
            assertThat(storage.layout.jobFile("alpha", jobId)).doesNotExist();
        }
    }
}
