package jobapply.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import jobapply.config.HarnessProperties;
import jobapply.domain.Actor;
import jobapply.domain.AgentRun;
import jobapply.domain.JobPosting;
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
 * {@link ApplicationRunner} 的 Worker 池（C011）：几个申请真的同时跑（时间区间重叠）、多出来的排队并在有空位时开始、
 * 排队时停止不跑、一个出错不影响别的、同一个网站的上限。假模型按公司名认出是哪个申请，卡在门闩上直到测试放行，
 * 然后只回一句话（申请停下等你）或者抛出不可重试的错误（申请出错）。浏览器是假服务端，网址和公司全部虚构。
 */
class ApplicationRunnerPoolTest {

    private static final String PROJECT = "pool-demo";
    private static final Duration WAIT = Duration.ofSeconds(20);

    @Test
    void threeRunOverlappingTheFourthQueuesAStoppedQueuedOneNeverRunsAndAFailureLeavesTheOthers(@TempDir Path dir)
            throws Exception {
        Fixture f = new Fixture(dir, 3, 3);
        try (BrowserSession browser = new BrowserSession(f.fake.launch());
             ApplicationRunner runner = f.runner(browser)) {
            String a = f.job("Alpha示例", "https://jobs.alpha-demo.com/1");
            String b = f.job("Beta示例", "https://jobs.beta-demo.com/2");
            String c = f.job("Gamma示例", "https://jobs.gamma-demo.com/3");
            String d = f.job("Delta示例", "https://jobs.delta-demo.com/4");
            String e = f.job("Epsilon示例", "https://jobs.epsilon-demo.com/5");
            f.model.fail("Alpha示例");

            for (String job : List.of(a, b, c)) {
                assertThat(runner.start(job, PROJECT, f.session).status()).isEqualTo(AgentRun.RUNNING);
            }
            assertThat(runner.start(d, PROJECT, f.session).status()).isEqualTo(AgentRun.QUEUED);
            assertThat(runner.start(e, PROJECT, f.session).status()).isEqualTo(AgentRun.QUEUED);

            // 三个都进了模型调用、谁都还没出来：同时在跑
            f.model.awaitEntered("Alpha示例", "Beta示例", "Gamma示例");
            assertThat(runner.status(d).status()).isEqualTo(AgentRun.QUEUED);

            // 排队时停止：移出队列、记成等你，模型从来没见过它
            ApplicationStatus stopped = runner.stop(e);
            assertThat(stopped.status()).isEqualTo(AgentRun.WAITING_USER);
            assertThat(stopped.message()).isEqualTo(ApplicationRunner.STOPPED_IN_QUEUE);

            // Alpha 出错：空出的位置给排队的 Delta，Beta、Gamma 不受影响
            f.model.release("Alpha示例");
            assertThat(runner.awaitIdle(a, WAIT)).isTrue();
            assertThat(runner.status(a).status()).isEqualTo(AgentRun.FAILED);
            f.model.awaitEntered("Delta示例");
            assertThat(runner.status(d).status()).isEqualTo(AgentRun.RUNNING);
            assertThat(runner.status(b).status()).isEqualTo(AgentRun.RUNNING);
            assertThat(runner.status(c).status()).isEqualTo(AgentRun.RUNNING);

            f.model.release("Beta示例", "Gamma示例", "Delta示例");
            assertThat(runner.awaitIdle(WAIT)).isTrue();
            for (String job : List.of(b, c, d)) {
                assertThat(runner.status(job).status()).isEqualTo(AgentRun.WAITING_USER);
            }
            assertThat(runner.status(e).status()).isEqualTo(AgentRun.WAITING_USER);
            assertThat(f.model.entered).doesNotContainKey("Epsilon示例");

            // 时间区间重叠：最晚开始的早于最早结束的
            long latestStart = List.of("Alpha示例", "Beta示例", "Gamma示例").stream()
                    .mapToLong(f.model.started::get).max().orElseThrow();
            long earliestEnd = List.of("Alpha示例", "Beta示例", "Gamma示例").stream()
                    .mapToLong(f.model.ended::get).min().orElseThrow();
            assertThat(latestStart).isLessThan(earliestEnd);
        }
    }

    @Test
    void theSameSiteTakesAtMostItsLimitAndAnotherSiteBehindItGoesFirst(@TempDir Path dir) throws Exception {
        Fixture f = new Fixture(dir, 3, 2);
        try (BrowserSession browser = new BrowserSession(f.fake.launch());
             ApplicationRunner runner = f.runner(browser)) {
            String a = f.job("Alpha示例", "https://jobs.same-demo.com/1");
            String b = f.job("Beta示例", "https://careers.same-demo.com/2");
            String c = f.job("Gamma示例", "https://jobs.same-demo.com/3");
            String d = f.job("Delta示例", "https://jobs.other-demo.com/4");

            assertThat(runner.start(a, PROJECT, f.session).status()).isEqualTo(AgentRun.RUNNING);
            assertThat(runner.start(b, PROJECT, f.session).status()).isEqualTo(AgentRun.RUNNING);
            assertThat(runner.start(c, PROJECT, f.session).status()).isEqualTo(AgentRun.QUEUED);
            assertThat(runner.start(d, PROJECT, f.session).status()).isEqualTo(AgentRun.RUNNING);
            f.model.awaitEntered("Alpha示例", "Beta示例", "Delta示例");
            assertThat(runner.status(c).status()).isEqualTo(AgentRun.QUEUED);

            f.model.release("Alpha示例");
            f.model.awaitEntered("Gamma示例");
            assertThat(runner.status(c).status()).isEqualTo(AgentRun.RUNNING);

            f.model.release("Beta示例", "Gamma示例", "Delta示例");
            assertThat(runner.awaitIdle(WAIT)).isTrue();
        }
    }

    /** 临时 storage、一个 Project 和 Session、假浏览器、假模型。 */
    private static final class Fixture {

        final TestStorage storage;
        final String session;
        final FakeBrowser fake;
        final GatedModel model = new GatedModel();
        final HarnessProperties limits;

        Fixture(Path dir, int workers, int perSite) {
            storage = TestStorage.create(dir.resolve("storage"));
            storage.project(PROJECT, "live");
            session = storage.session(PROJECT);
            fake = FakeBrowser.create(dir.resolve("browser"));
            limits = new HarnessProperties(null, null, null, null, workers, perSite);
        }

        ApplicationRunner runner(BrowserSession browser) {
            return new ApplicationRunner(storage.layout, storage.clock, browser, () -> model, limits);
        }

        String job(String company, String url) {
            fake.page(url, company + " 招聘", "- heading \"" + company + " 校园招聘\" [level=1] [ref=e1]");
            return storage.ledger().addLead(PROJECT, session, Actor.USER, new JobPosting(company, "2027 校园招聘",
                    "Backend Engineer", "JD-" + company, "campus-fulltime", List.of("上海"), url, null,
                    "self-campus", "user-link"), null).id();
        }
    }

    /**
     * 假模型：按提示词里的公司名认出申请；记下进入、离开的时间；卡在这个公司的门闩上，测试放行后回一句话，
     * 或者（{@link #fail}）抛出不可重试的错误。
     */
    private static final class GatedModel implements ChatModel {

        final Map<String, Long> started = new ConcurrentHashMap<>();
        final Map<String, Long> ended = new ConcurrentHashMap<>();
        final Map<String, CountDownLatch> entered = new ConcurrentHashMap<>();
        private final Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();
        private final Map<String, Boolean> failing = new ConcurrentHashMap<>();

        void fail(String company) {
            failing.put(company, true);
        }

        void release(String... companies) {
            for (String company : companies) {
                gate(company).countDown();
            }
        }

        void awaitEntered(String... companies) throws InterruptedException {
            for (String company : companies) {
                assertThat(latch(company).await(WAIT.toSeconds(), TimeUnit.SECONDS))
                        .as(company + " 进入模型调用").isTrue();
            }
        }

        private CountDownLatch gate(String company) {
            return gates.computeIfAbsent(company, key -> new CountDownLatch(1));
        }

        private CountDownLatch latch(String company) {
            return entered.computeIfAbsent(company, key -> new CountDownLatch(1));
        }

        /** 带工具定义的请求要从这里复制选项（{@code ModelFactory.withTools}）。 */
        @Override
        public ChatOptions getOptions() {
            return OpenAiChatOptions.builder().model("fake-large").build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            String text = prompt.getContents();
            String company = List.of("Alpha示例", "Beta示例", "Gamma示例", "Delta示例", "Epsilon示例").stream()
                    .filter(text::contains).findFirst().orElseThrow();
            started.putIfAbsent(company, System.nanoTime());
            latch(company).countDown();
            try {
                if (!gate(company).await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
                    throw new IllegalArgumentException("测试没有放行 " + company);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalArgumentException("被中断");
            }
            ended.put(company, System.nanoTime());
            if (failing.containsKey(company)) {
                throw new IllegalArgumentException("假模型：" + company + " 出错");
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("页面打开了，等你确认一下。"))));
        }
    }
}
