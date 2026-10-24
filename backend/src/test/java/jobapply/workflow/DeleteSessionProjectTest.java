package jobapply.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import jobapply.domain.Actor;
import jobapply.domain.AgentRun;
import jobapply.domain.JobPosting;
import jobapply.domain.LeadStatus;
import jobapply.domain.Transition;
import jobapply.persistence.TestStorage;
import jobapply.tools.BrowserSession;
import jobapply.tools.FakeBrowser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * 删除 Session / Project（C013）：删错东西是实际损失，所以只验证「挪走的正好是该挪的」。临时 storage，数据全部虚构。
 * <ul>
 *   <li>删 Session 只挪走这一个目录，Job 留在 Project 里，之后还能更新它（事件里记已删除的 Session），编号不复用；</li>
 *   <li>删 Project 只挪走这个 Project 和它的 Job 的 runs / uploads，base、settings、别的 Project 一个字节不变，
 *       全局台账里没有它了；</li>
 *   <li>有申请在跑时删 Project 是 409，什么都不动。</li>
 * </ul>
 */
class DeleteSessionProjectTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Test
    void deletesExactlyTheSessionOrProjectAndRefusesWhileAnApplicationRuns(@TempDir Path dir) {
        TestStorage storage = TestStorage.create(dir.resolve("storage"));
        storage.project("alpha", "live");
        storage.project("beta", "live");
        String keep = storage.session("alpha");
        String gone = storage.session("alpha");
        String betaSession = storage.session("beta");
        String last = storage.session("alpha");
        String alphaJob = storage.ledger().addLead("alpha", gone, Actor.USER,
                posting("Alpha示例", "https://jobs.alpha-demo.com/1"), null).id();
        String betaJob = storage.ledger().addLead("beta", betaSession, Actor.USER,
                posting("Beta示例", "https://jobs.beta-demo.com/2"), null).id();
        storage.write(storage.layout.agentRun(alphaJob), "{}");
        storage.write(storage.layout.uploadsDir(alphaJob).resolve("resume.txt"), "虚构");
        storage.write(storage.layout.agentRun(betaJob), "{}");
        storage.write(storage.layout.workersYaml(), "workers: 2\n");

        FakeBrowser fake = FakeBrowser.create(dir.resolve("browser"));
        fake.page("https://jobs.beta-demo.com/2", "Beta示例 招聘", "- heading \"Beta示例\" [level=1] [ref=e1]");
        GatedModel model = new GatedModel();
        try (BrowserSession browser = new BrowserSession(fake.launch());
             ApplicationRunner applications = new ApplicationRunner(storage.layout, storage.clock, browser,
                     () -> model, null);
             SessionRunner runner = new SessionRunner(storage.layout, storage.clock, browser, () -> model, null, null,
                     applications)) {

            // ---- 删 Session ----
            Map<String, String> before = storage.snapshot();
            assertThat(runner.delete(gone)).isEqualTo(SessionRunner.Deletion.DELETED);
            assertThat(storage.layout.sessionDir("alpha", gone)).doesNotExist();
            assertThat(storage.layout.sessionDir("alpha", keep)).isDirectory();
            assertThat(storage.layout.jobFile("alpha", alphaJob)).exists();
            Path sessionTrash = onlyTrashEntry(storage);
            assertThat(sessionTrash.resolve("projects/alpha/sessions/" + gone + "/session.md")).exists();
            // 只有这个 Session 目录里的文件换了位置，别的一个字节不变
            Map<String, String> after = storage.snapshot();
            before.keySet().removeIf(path -> path.startsWith("projects/alpha/sessions/" + gone + "/"));
            after.keySet().removeIf(path -> path.startsWith(".trash/"));
            assertThat(after).isEqualTo(before);
            // Job 还能更新：事件里记已删除的 Session
            storage.ledger().transition("alpha", alphaJob, gone, Actor.USER,
                    new Transition.MarkLead(LeadStatus.SHORTLISTED, null));
            // 删掉的编号不复用（不然旧 Job 的「发现于」会指到新对话）
            assertThat(runner.delete(last)).isEqualTo(SessionRunner.Deletion.DELETED);
            assertThat(storage.session("alpha")).isNotIn(keep, gone, betaSession, last);

            // ---- 删 Project ----
            Map<String, String> untouched = withoutAlpha(storage.snapshot(), alphaJob);
            runner.deleteProject("alpha");
            assertThat(storage.layout.projectDir("alpha")).doesNotExist();
            assertThat(storage.layout.agentRun(alphaJob)).doesNotExist();
            assertThat(storage.layout.uploadsDir(alphaJob)).doesNotExist();
            Path projectTrash = storage.layout.trashEntries().stream()
                    .filter(entry -> entry.getFileName().toString().contains("-project-alpha")).findFirst().orElseThrow();
            assertThat(projectTrash.resolve("projects/alpha/jobs/" + alphaJob + "/job.md")).exists();
            assertThat(projectTrash.resolve("runs/" + alphaJob + ".json")).exists();
            assertThat(projectTrash.resolve("uploads/" + alphaJob + "/resume.txt")).exists();
            // base、settings、playbook、beta 一个字节不变；全局台账重新生成，没有 alpha 了
            assertThat(withoutAlpha(storage.snapshot(), alphaJob)).isEqualTo(untouched);
            assertThat(storage.read(storage.layout.jobsIndex())).contains(betaJob).doesNotContain(alphaJob);

            // ---- 有申请在跑时删 Project：409，什么都不动 ----
            assertThat(applications.start(betaJob, "beta", betaSession).status()).isEqualTo(AgentRun.RUNNING);
            Map<String, String> running = storage.snapshot();
            assertThatThrownBy(() -> runner.deleteProject("beta"))
                    .isInstanceOf(ApplicationException.Conflict.class).hasMessageContaining(betaJob);
            assertThat(storage.layout.projectFile("beta")).exists();
            model.release();
            assertThat(applications.awaitIdle(WAIT)).isTrue();
            assertThat(running.keySet()).isSubsetOf(storage.snapshot().keySet());
        }
    }

    /** 去掉 alpha 自己的东西、回收站，和随台账重新生成的两个全局文件。 */
    private static Map<String, String> withoutAlpha(Map<String, String> files, String alphaJob) {
        Map<String, String> rest = new TreeMap<>(files);
        rest.keySet().removeIf(path -> path.startsWith("projects/alpha/") || path.startsWith(".trash/")
                || path.equals("runs/" + alphaJob + ".json") || path.startsWith("uploads/" + alphaJob + "/")
                || path.equals("jobs-index.md") || path.equals("exports/applications.csv"));
        return rest;
    }

    private static Path onlyTrashEntry(TestStorage storage) {
        List<Path> entries = storage.layout.trashEntries();
        assertThat(entries).hasSize(1);
        return entries.getFirst();
    }

    private static JobPosting posting(String company, String url) {
        return new JobPosting(company, "2027 校园招聘", "Backend Engineer", "JD-" + company, "campus-fulltime",
                List.of("上海"), url, null, "self-campus", "user-link");
    }

    /** 假模型：卡在门闩上直到测试放行，然后回一句话（申请停下等你）。 */
    private static final class GatedModel implements ChatModel {

        private final CountDownLatch gate = new CountDownLatch(1);

        void release() {
            gate.countDown();
        }

        @Override
        public ChatOptions getOptions() {
            return OpenAiChatOptions.builder().model("fake-large").build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            try {
                if (!gate.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
                    throw new IllegalArgumentException("测试没有放行");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalArgumentException("被中断");
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("页面打开了，等你确认一下。"))));
        }
    }
}
