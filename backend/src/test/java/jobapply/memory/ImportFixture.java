package jobapply.memory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import jobapply.config.FakeModelServer;
import jobapply.config.ModelFactory;
import jobapply.domain.ImportRun;
import jobapply.domain.Source;
import jobapply.persistence.ConflictsFile;
import jobapply.persistence.EvidenceCardFile;
import jobapply.persistence.GlossaryFile;
import jobapply.persistence.ImportRunStore;
import jobapply.persistence.LedgerFile;
import jobapply.persistence.ProfileSummaryFile;
import jobapply.persistence.ProjectCardFile;
import jobapply.persistence.SourceRegistry;
import jobapply.persistence.TestStorage;
import jobapply.tools.GitHubPublicReader;
import jobapply.tools.LocalDirectoryScanner;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 资料整理的测试装置：临时 storage、{@code @TempDir} 里现造的虚构项目（带一个 {@code .env}）、按提示词回复不同 JSON 的
 * 假模型。数据全部虚构；不访问外部网络（GitHub 指向本机一个没人监听的端口，或者本机的假服务端）。
 */
public final class ImportFixture implements AutoCloseable {

    public static final String SECRET = "SECRET-VALUE-MUST-NOT-REACH-THE-MODEL";
    static final String README = """
            # 示例推荐服务（demo-recsys）

            作者：林虚构，邮箱 lin@example.com
            用 Java 和 Spring Boot 写的推荐服务课程项目。
            压测结果：P99 延迟从 120ms 降到 45ms（单机，1k QPS）。
            """;

    static final String INVENTORY = """
            {"overview":"一个虚构的推荐服务课程项目，Java 和 Spring Boot。",
             "items":[{"name":"示例推荐服务","aliases":["demo-recsys"],"kind":"project"}],
             "claims":[{"category":"基础信息","text":"姓名：林虚构","locator":"README.md"},
               {"category":"指标","text":"P99 延迟从 120ms 降到 45ms","locator":"README.md#L5"},
               {"category":"技能","text":"会写 Go","locator":"not-read.md"}]}""";

    static final String EXTRACT = """
            {"facts":[
              {"key":"contact.name","value":null,"zh":"林虚构","en":null,"class":"B","locator":"README.md","evidence":null},
              {"key":"contact.email","value":"lin@example.com","zh":null,"en":null,"class":"B","locator":"README.md","evidence":null},
              {"key":"skills.backend","value":null,"zh":"Java、Spring Boot","en":null,"class":"A","locator":"not-read.md","evidence":"用 Java 和 Spring Boot 写过推荐服务"},
              {"key":"metrics.p99-latency","value":null,"zh":"P99 延迟从 120ms 降到 45ms","en":null,"class":"A","locator":"README.md#L5","evidence":"压测 P99 延迟从 120ms 降到 45ms"},
              {"key":"contact.visa-status","value":null,"zh":"需要签证担保","en":null,"class":"A","locator":null,"evidence":null}],
             "projects":[{"slug":"demo-recsys","aliases":["示例推荐服务"],"title_zh":"示例推荐服务","title_en":null,
               "kind":"course","period_from":"2025-03","period_to":"2025-06","team_size":3,"stack":["Java","Spring Boot"],
               "links":[],"language":"zh","summary":"课程里的推荐服务。","role":"负责召回和压测。",
               "approach":"缓存热门结果。","results":"P99 延迟降到 45ms。","locators":["README.md"]}]}""";

    static final String TRANSLATE = """
            {"items":[{"id":"text","text":"EN-DRAFT"},{"id":"title","text":"Demo Recommender"},
              {"id":"summary","text":"A course recommender."},{"id":"role","text":"Owned recall and load tests."},
              {"id":"approach","text":"Cached hot results."},{"id":"results","text":"P99 latency down to 45 ms."}]}""";

    static final String PROFILE = """
            {"zh":{"directions":"- 后端开发","skills":"- 后端：Java（projects/demo-recsys）","highlights":"- P99 延迟降到 45ms（projects/demo-recsys）","positioning":"新的定位。"},
             "en":{"directions":"- Backend","skills":"- Backend: Java (projects/demo-recsys)","highlights":"- P99 down to 45 ms (projects/demo-recsys)","positioning":"New positioning."}}""";

    /** 按系统提示词里的一句话判断是哪一步。 */
    public static String answer(String body) {
        if (body.contains("写这个来源的清单卡")) {
            return INVENTORY;
        }
        if (body.contains("整理成求职资料的候选条目")) {
            return EXTRACT;
        }
        if (body.contains("翻译成另一种语言")) {
            return TRANSLATE;
        }
        if (body.contains("写一份个人画像")) {
            return PROFILE;
        }
        return null;
    }

    final TestStorage storage;
    final FakeModelServer server;
    final Path project;
    final SourceRegistry sources;
    final ImportRunStore runs;
    final LedgerFile ledger;
    final ProjectCardFile cards;
    final GlossaryFile glossary;
    final ConflictsFile conflicts;
    final ProfileSummaryFile summaries;
    final LocalDirectoryScanner scanner;
    final GitHubPublicReader github;
    final ImportEstimator estimator;
    final FactWriter writer;
    final ImportRunner runner;

    private ImportFixture(Path dir, boolean git, URI githubApi) {
        storage = TestStorage.create(dir.resolve("storage"), git);
        server = FakeModelServer.start().toolCalls(false).respond(ImportFixture::answer);
        project = dir.resolve("demo-project");
        write(project.resolve("README.md"), README);
        write(project.resolve(".env"), "TOKEN=" + SECRET);
        write(project.resolve("src/Main.java"), "class Main {\n}\n");
        var layout = storage.layout;
        var clock = storage.clock;
        sources = new SourceRegistry(layout, clock);
        runs = new ImportRunStore(layout, clock);
        ledger = new LedgerFile(layout, clock);
        cards = new ProjectCardFile(layout, clock);
        glossary = new GlossaryFile(layout, clock);
        conflicts = new ConflictsFile(layout, clock);
        summaries = new ProfileSummaryFile(layout, clock);
        LocalDirectoryScanner.Limits limits = new LocalDirectoryScanner.Limits(400, 1_500_000, 200 * 1024, 40);
        scanner = new LocalDirectoryScanner(limits);
        github = new GitHubPublicReader(githubApi, Duration.ofSeconds(2));
        estimator = new ImportEstimator(layout, sources, runs, scanner, github, limits);
        writer = new FactWriter(layout, ledger, cards, glossary, conflicts, new TranslationDrafter(this::model,
                glossary), clock);
        runner = newRunner();
    }

    static ImportFixture create(Path dir, boolean git) {
        return new ImportFixture(dir, git, URI.create("http://127.0.0.1:9"));
    }

    /** GitHub 指向本机的假服务端。 */
    static ImportFixture create(Path dir, URI githubApi) {
        return new ImportFixture(dir, false, githubApi);
    }

    /** 一个新的 ImportRunner（相当于重启应用以后）。 */
    ImportRunner newRunner() {
        var layout = storage.layout;
        var clock = storage.clock;
        SourceInventory inventory = new SourceInventory(layout, sources, scanner, github,
                new EvidenceCardFile(layout, clock), () -> Optional.of(model()), clock);
        return new ImportRunner(layout, runs, sources, inventory, new CandidateExtractor(this::model), writer, ledger,
                cards, github, this::model, clock);
    }

    ChatModel model() {
        return ModelFactory.create(new ModelFactory.Endpoint("openai", server.openAiBase(), FakeModelServer.OPENAI_KEY,
                "fake-large"), Duration.ofSeconds(5));
    }

    ProfileSummaryBuilder summaryBuilder() {
        return new ProfileSummaryBuilder(storage.layout, cards, ledger, summaries, this::model, storage.clock);
    }

    /** 登记虚构项目并估算。 */
    ImportRun estimate() {
        String key = sources.register(Source.Type.DIR, realPath(project), "").source().key();
        return estimator.estimate(List.of(key));
    }

    /** 等整理停下来（不再是 running，后台也已经做完最后一次提交）。 */
    ImportRun settle(ImportRunner runner, String id) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            ImportRun run = runner.view(id);
            if (run.status() != ImportRun.Status.RUNNING && !runner.isRunning(id)) {
                return run;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("整理 30 秒内没有停下：" + id);
    }

    /** 整理到 reviewing。 */
    ImportRun reviewing() {
        ImportRun run = estimate();
        runner.start(run.id());
        return settle(runner, run.id());
    }

    @Override
    public void close() {
        runner.close();
        server.close();
    }

    static String realPath(Path path) {
        try {
            return path.toRealPath().toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
