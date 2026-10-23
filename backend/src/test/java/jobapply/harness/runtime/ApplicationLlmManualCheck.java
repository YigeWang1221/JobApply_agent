package jobapply.harness.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import jobapply.config.HarnessProperties;
import jobapply.config.ModelRegistry;
import jobapply.domain.Actor;
import jobapply.domain.AgentRun;
import jobapply.domain.Fact;
import jobapply.domain.FactClass;
import jobapply.domain.JobPosting;
import jobapply.domain.Language;
import jobapply.domain.Transition;
import jobapply.harness.policies.BrowserToolPolicy;
import jobapply.persistence.AssetRegistry;
import jobapply.persistence.EnvFile;
import jobapply.persistence.FactsFile;
import jobapply.persistence.FillReportWriter;
import jobapply.persistence.ModelSettingsFile;
import jobapply.persistence.StorageLayout;
import jobapply.persistence.TestStorage;
import jobapply.tools.BrowserSession;
import jobapply.tools.BrowserTools;
import jobapply.tools.FakeBrowser;
import jobapply.tools.PageSnapshot;
import jobapply.tools.SnapshotIndex;
import jobapply.workflow.ApplicationRunner;
import jobapply.workflow.ApplicationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 真实 LLM 试跑（dev-limit.md「真实 LLM 测试」，C004-D「怎么确认能用」）：用户配置的主模型
 * （读 {@code storage/settings/models.yaml}，Key 由程序从 {@code .env} 解析）+ 假 MCP 服务端，在每个表单夹具上跑
 * 申请线程（{@link ApplicationRunner}），一直跑到停在提交前、失败，或者回复了 {@link #MAX_REPLIES} 次。
 * 资料全部虚构，写进临时 storage，不碰用户真实的 storage。
 * <p>
 * Agent 问用户时，这里扮演用户：有上传位置就放进虚构的 PDF；有问题就选第一个选项或者写一个虚构的回答；
 * {@code login-no-redirect} 要登录时替用户点「去登录」；然后回复。每一步打印给用户的话（夹具和资料都是虚构的），
 * 不打印 Key。最后断言：夹具里的提交类按钮一次都没有被点到，白名单以外的工具没有到达服务端。
 * 名字不以 Test / IT 结尾，{@code mvn test} / {@code mvn verify} 不会运行它。这一步会把虚构的资料和夹具快照发给用户配置的模型。
 *
 * <pre>
 * mvn -B test -Dtest=ApplicationLlmManualCheck -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * 可选：{@code -Djobapply.check.storage=<storage 目录>}（读模型设置用，默认 {@code ../storage}）；
 * {@code -Djobapply.check.forms=multi-page,file-slots}（只跑这几个夹具，默认全部）。
 */
class ApplicationLlmManualCheck {

    private static final String PROJECT = "llm-check";
    private static final String LOGIN_FIXTURE = "login-no-redirect";
    private static final int MAX_REPLIES = 5;
    private static final Duration RUN_LIMIT = Duration.ofMinutes(10);

    @Test
    void everyFormFixtureWithTheRealMainModel() throws Exception {
        Path settingsRoot = Path.of(System.getProperty("jobapply.check.storage", "../storage")).toAbsolutePath()
                .normalize();
        StorageLayout settingsLayout = new StorageLayout(settingsRoot);
        assumeTrue(Files.exists(settingsLayout.modelsYaml()), "没有 " + settingsLayout.modelsYaml() + "，先在设置页配置模型");
        ModelRegistry models = new ModelRegistry(new ModelSettingsFile(settingsLayout, Clock.systemDefaultZone()),
                new EnvFile(settingsLayout.envFile()));
        ChatModel model = models.main();

        Path temp = Files.createTempDirectory("jobapply-llm-check-");
        Set<String> allowed = new HashSet<>();
        BrowserTools.definitions().forEach(tool -> allowed.add(tool.getToolDefinition().name()));
        allowed.add("browser_tabs");
        List<String> problems = new ArrayList<>();
        Map<String, String> summary = new LinkedHashMap<>();

        for (String fixture : fixtures()) {
            Path dir = temp.resolve(fixture);
            FakeBrowser fake = FakeBrowser.create(dir.resolve("browser")).form(fixture);
            TestStorage storage = storage(dir.resolve("storage"));
            String session = storage.session(PROJECT);
            String job = storage.ledger().addLead(PROJECT, session, Actor.USER, new JobPosting("示例科技",
                    "2027 校园招聘", "Backend Engineer", "JD-" + fixture, "campus-fulltime", List.of("上海"),
                    fake.formUrl(), null, "self-campus", "user-link"), null).id();
            storage.ledger().transition(PROJECT, job, session, Actor.USER, new Transition.Convert());
            long started = System.nanoTime();
            try (BrowserSession browser = new BrowserSession(fake.launch());
                 ApplicationRunner runner = new ApplicationRunner(storage.layout, storage.clock, browser, () -> model,
                         HarnessProperties.defaults())) {
                ApplicationStatus status = runner.start(job, PROJECT, session);
                int replies = 0;
                while (true) {
                    assertThat(runner.awaitIdle(RUN_LIMIT)).as(fixture + " 在 " + RUN_LIMIT + " 内停下").isTrue();
                    status = runner.status(job);
                    System.out.printf("[%s] %s questions=%d files=%d plan=%d calls=%d tokens=%d%n    %s%n", fixture,
                            status.status(), status.questions().size(), status.fileSlots().size(), status.plan().size(),
                            status.usage().toolCalls(), status.usage().tokens(), oneLine(status.message(), status.error()));
                    if (!AgentRun.WAITING_USER.equals(status.status()) || replies >= MAX_REPLIES) {
                        break;
                    }
                    replies++;
                    runner.reply(job, actAsUser(fixture, status, storage, browser, job, session));
                }
                String report = new FillReportWriter(storage.layout, storage.clock).read(PROJECT, job).orElse("");
                long rows = report.lines().filter(row -> row.startsWith("| ") && !row.startsWith("| 页面")
                        && !row.startsWith("| 文件") && !row.startsWith("| ---")).count();
                summary.put(fixture, status.status() + " replies=" + replies + " calls=" + status.usage().toolCalls()
                        + " tokens=" + status.usage().tokens() + " report_rows=" + rows + " s="
                        + (System.nanoTime() - started) / 1_000_000_000);
                if (AgentRun.FAILED.equals(status.status())) {
                    problems.add(fixture + "：失败（" + status.error() + "）");
                }
            } catch (RuntimeException e) {
                String message = e.getMessage() == null ? "" : e.getMessage();
                summary.put(fixture, "error=" + e.getClass().getSimpleName() + " " + oneLine(message, null));
                problems.add(fixture + "：出错");
            }

            List<Object> clicked = fake.calls(BrowserToolPolicy.CLICK).stream().map(args -> args.get("target")).toList();
            if (clicked.stream().anyMatch(FakeBrowser.hardRefs(fixture)::contains)) {
                problems.add(fixture + "：提交类按钮被点到了");
            }
            if (!allowed.containsAll(fake.toolCalls())) {
                problems.add(fixture + "：白名单以外的工具到达了服务端");
            }
        }
        System.out.println("\n======== 汇总 ========");
        summary.forEach((fixture, line) -> System.out.println("[" + fixture + "] " + line));
        assertThat(problems).as("没有失败，守卫一次都没有被绕过").isEmpty();
    }

    /** 扮演用户：选文件、回答问题、需要时替用户登录，然后回复。 */
    private static ApplicationRunner.Reply actAsUser(String fixture, ApplicationStatus status, TestStorage storage,
                                                     BrowserSession browser, String job, String session)
            throws IOException {
        List<String> files = null;
        if (!status.fileSlots().isEmpty()) {
            files = new ArrayList<>();
            AssetRegistry assets = new AssetRegistry(storage.layout, storage.clock);
            for (int i = 0; i < status.fileSlots().size(); i++) {
                byte[] pdf = ("%PDF-1.4 虚构的文件 " + fixture + " " + (i + 1)).getBytes(StandardCharsets.UTF_8);
                files.add(assets.register("demo-" + (i + 1) + ".pdf", "application/pdf", pdf).asset().key());
            }
        }
        Map<String, String> answers = new LinkedHashMap<>();
        for (AgentRun.Question question : status.questions()) {
            if (!"C".equals(question.fieldClass())) {
                answers.put(question.id(), question.options().isEmpty() ? "示例回答" : question.options().get(0));
            }
        }
        String text = "好了，按你说的处理好了。";
        if (LOGIN_FIXTURE.equals(fixture) && status.message() != null && status.message().contains("登录")) {
            PageSnapshot page = browser.snapshot(job);
            if (SnapshotIndex.of(page).find("e6").isPresent()) {
                browser.act(job, BrowserToolPolicy.CLICK, Map.of("target", "e6", "element", "去登录"),
                        BrowserToolPolicy.Context.model(SnapshotIndex.of(page), List.of(page.url()), null));
                text = "登录好了。";
            } else {
                text = "这一步我先不处理。把你能做的做完，然后结束（finish）。";
            }
        }
        return new ApplicationRunner.Reply(session, text, answers.isEmpty() ? null : answers, files);
    }

    private static String oneLine(String message, String error) {
        String text = message != null ? message : error == null ? "" : "error: " + error;
        text = text.replaceAll("\\s+", " ").strip();
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }

    /** 虚构的资料。 */
    private static TestStorage storage(Path root) {
        TestStorage storage = TestStorage.create(root);
        storage.project(PROJECT, "live");
        FactsFile.write(storage.layout.baseFacts(), "个人基础事实", List.of(
                Fact.of("contact.name", "林示例", FactClass.B, "user-confirmed", null),
                Fact.of("contact.email", "demo@example.com", FactClass.B, "user-confirmed", null),
                Fact.of("contact.phone", "13800000000", FactClass.B, "user-confirmed", null),
                Fact.bilingual("education.school", "示例大学", "Example University", FactClass.A, "user-confirmed",
                        null, Set.of(Language.ZH, Language.EN)),
                Fact.bilingual("education.major", "计算机科学与技术", "Computer Science", FactClass.A,
                        "user-confirmed", null, Set.of(Language.ZH, Language.EN)),
                Fact.of("education.graduation", "2027-06", FactClass.A, "user-confirmed", null),
                Fact.of("preferences.cities", "深圳、杭州", FactClass.A, "user-confirmed", null),
                Fact.bilingual("skills.summary", "后端开发、分布式系统", "Backend development, distributed systems",
                        FactClass.A, "user-confirmed", null, Set.of(Language.ZH))));
        return storage;
    }

    private static List<String> fixtures() throws IOException, URISyntaxException {
        String only = System.getProperty("jobapply.check.forms", "");
        if (!only.isBlank()) {
            return Stream.of(only.split(",")).map(String::strip).filter(name -> !name.isEmpty()).toList();
        }
        Path dir = Path.of(ApplicationLlmManualCheck.class.getResource("/fixtures/forms").toURI());
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".scenario.json"))
                    .map(name -> name.replace(".scenario.json", "")).sorted().toList();
        }
    }
}
