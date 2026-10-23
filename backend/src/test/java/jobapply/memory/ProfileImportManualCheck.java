package jobapply.memory;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jobapply.config.ModelRegistry;
import jobapply.domain.ImportCandidate;
import jobapply.domain.ImportRun;
import jobapply.domain.Source;
import jobapply.persistence.ConflictsFile;
import jobapply.persistence.EnvFile;
import jobapply.persistence.EvidenceCardFile;
import jobapply.persistence.GlossaryFile;
import jobapply.persistence.ImportRunStore;
import jobapply.persistence.LedgerFile;
import jobapply.persistence.ModelSettingsFile;
import jobapply.persistence.ProfileSummaryFile;
import jobapply.persistence.ProjectCardFile;
import jobapply.persistence.SourceRegistry;
import jobapply.persistence.StorageLayout;
import jobapply.persistence.TestStorage;
import jobapply.tools.GitHubPublicReader;
import jobapply.tools.LocalDirectoryScanner;
import org.junit.jupiter.api.Test;

/**
 * 真实 LLM 测试（dev-limit.md「真实 LLM 测试」，C004-A-2）：对用户指定的一个本地目录或 GitHub 公开仓库做一次完整的整理（估算 → 清单卡 →
 * 抽取 → 合并），用用户真实的 {@code storage/settings/models.yaml}（Key 由程序从 {@code .env} 解析）。
 * 整理写进一个临时 storage，不碰用户真实的 storage。只打印每一步的耗时、模型调用次数和候选数量，不打印内容。
 * 名字不以 Test / IT 结尾，{@code mvn test} / {@code mvn verify} 不会运行它。这一步会把扫描出的文字发给用户配置的模型。
 *
 * <pre>
 * mvn -B test -Dtest=ProfileImportManualCheck -Dsurefire.failIfNoSpecifiedTests=false -Djobapply.check.dir=&lt;目录&gt;
 * mvn -B test -Dtest=ProfileImportManualCheck -Dsurefire.failIfNoSpecifiedTests=false -Djobapply.check.github=&lt;owner/repo&gt;
 * </pre>
 * 可选：{@code -Djobapply.check.storage=<storage 目录>}（读模型设置用，默认 {@code ../storage}）；
 * {@code -Djobapply.check.confirm=true}：整理完以后确认全部候选（缺的语言由模型起草），再生成一次画像（不写文件），只打印数量和字数。
 */
class ProfileImportManualCheck {

    @Test
    void importOneDirectoryWithTheRealModels() throws Exception {
        String dir = System.getProperty("jobapply.check.dir");
        String repo = System.getProperty("jobapply.check.github");
        assumeTrue((dir != null && !dir.isBlank()) || (repo != null && !repo.isBlank()),
                "用 -Djobapply.check.dir=<目录> 或 -Djobapply.check.github=<owner/repo> 指定要整理的来源");
        Path settingsRoot = Path.of(System.getProperty("jobapply.check.storage", "../storage")).toAbsolutePath()
                .normalize();
        StorageLayout settingsLayout = new StorageLayout(settingsRoot);
        assumeTrue(Files.exists(settingsLayout.modelsYaml()), "没有 " + settingsLayout.modelsYaml() + "，先在设置页配置模型");
        ModelRegistry models = new ModelRegistry(new ModelSettingsFile(settingsLayout, Clock.systemDefaultZone()),
                new EnvFile(settingsLayout.envFile()));

        Path temp = Files.createTempDirectory("jobapply-import-check-");
        TestStorage storage = TestStorage.create(temp.resolve("storage"));
        StorageLayout layout = storage.layout;
        Clock clock = storage.clock;
        LocalDirectoryScanner.Limits limits = new LocalDirectoryScanner.Limits(400, 1_500_000, 200 * 1024, 40);
        LocalDirectoryScanner scanner = new LocalDirectoryScanner(limits);
        GitHubPublicReader github = new GitHubPublicReader(GitHubPublicReader.DEFAULT_API, Duration.ofSeconds(15));
        SourceRegistry sources = new SourceRegistry(layout, clock);
        ImportRunStore runs = new ImportRunStore(layout, clock);
        LedgerFile ledger = new LedgerFile(layout, clock);
        ProjectCardFile cards = new ProjectCardFile(layout, clock);
        GlossaryFile glossary = new GlossaryFile(layout, clock);
        FactWriter writer = new FactWriter(layout, ledger, cards, glossary, new ConflictsFile(layout, clock),
                new TranslationDrafter(models::main, glossary), clock);
        SourceInventory inventory = new SourceInventory(layout, sources, scanner, github,
                new EvidenceCardFile(layout, clock), models::auxiliaryOrMain, clock);

        long t0 = System.nanoTime();
        String key;
        if (repo != null && !repo.isBlank()) {
            GitHubPublicReader.Ref ref = GitHubPublicReader.parse(repo);
            GitHubPublicReader.RepoInfo info = github.repository(ref.owner(), ref.repo());
            key = sources.register(Source.Type.GITHUB, info.fullName(), info.headSha()).source().key();
        } else {
            key = sources.register(Source.Type.DIR, LocalDirectoryScanner.validateRoot(dir, layout.root()).toString(),
                    "").source().key();
        }
        ImportRun estimated = new ImportEstimator(layout, sources, runs, scanner, github, limits)
                .estimate(List.of(key));
        System.out.println("[估算] " + ms(t0) + " ms，文字 " + estimated.estimate().chars() + " 字，预计模型 "
                + estimated.estimate().modelCalls() + " 次，读 " + estimated.estimate().sources().getFirst().scan()
                .files().size() + " 个文件，跳过 " + estimated.estimate().sources().getFirst().scan().skipped().size()
                + " 个，GitHub 已用 " + github.calls() + " 次、整理时还要 " + estimated.estimate().githubCallsPlanned()
                + " 次");

        try (ImportRunner runner = new ImportRunner(layout, runs, sources, inventory, new CandidateExtractor(models::main),
                writer, ledger, cards, github, models::main, clock)) {
            long t1 = System.nanoTime();
            runner.start(estimated.id());
            Map<String, Long> steps = new LinkedHashMap<>();
            ImportRun run = runner.view(estimated.id());
            while (run.status() == ImportRun.Status.RUNNING || runner.isRunning(run.id())) {
                Object progress = run.extra().get("progress");
                if (progress instanceof Map<?, ?> map) {
                    steps.putIfAbsent(String.valueOf(map.get("message")), ms(t1));
                }
                Thread.sleep(200);
                run = runner.view(estimated.id());
            }
            steps.forEach((step, at) -> System.out.println("[步骤] " + at + " ms 开始：" + step));
            Map<?, ?> progress = (Map<?, ?>) run.extra().get("progress");
            System.out.println("[整理] " + ms(t1) + " ms，状态 " + run.status().code() + "，模型请求 "
                    + (progress == null ? "?" : progress.get("model_calls")) + " 次，候选 "
                    + ImportRunner.candidates(run).size() + " 个（事实 " + ImportRunner.candidates(run).stream()
                    .filter(c -> !c.forProject()).count() + "，项目卡 " + ImportRunner.candidates(run).stream()
                    .filter(ImportCandidate::forProject).count() + "），提示 "
                    + (run.extra().get("warnings") instanceof List<?> list ? list.size() : 0) + " 条，台账 "
                    + ledger.list().size() + " 条，GitHub 共 " + github.calls() + " 次"
                    + (run.extra().get("error") == null ? "" : "，错误：" + run.extra().get("error")));
            if (Boolean.getBoolean("jobapply.check.confirm") && run.status() == ImportRun.Status.REVIEWING) {
                long t2 = System.nanoTime();
                int warnings = 0;
                for (ImportCandidate candidate : ImportRunner.candidates(run)) {
                    if (candidate.decision() == null) {
                        ImportRun after = runner.decide(run.id(), candidate.id(), FactWriter.ACCEPT, null, null);
                        warnings += ImportRunner.candidates(after).stream().filter(c -> c.id().equals(candidate.id()))
                                .findFirst().orElseThrow().decision().warnings().size();
                    }
                }
                System.out.println("[确认全部] " + ms(t2) + " ms，状态 " + runner.view(run.id()).status().code()
                        + "，起草译文的提示 " + warnings + " 条，项目卡 " + cards.list().size() + " 张（两种语言都有的 "
                        + cards.list().stream().filter(c -> !c.zh().isEmpty() && !c.en().isEmpty()).count() + " 张）");
                long t3 = System.nanoTime();
                ProfileSummaryBuilder.Candidate summary = new ProfileSummaryBuilder(layout, cards, ledger,
                        new ProfileSummaryFile(layout, clock), models::main, clock).generate();
                System.out.println("[画像] " + ms(t3) + " ms，zh 各节字数 " + summary.zh().stream()
                        .map(section -> section.id() + "=" + section.text().length()).toList() + "，en 各节字数 "
                        + summary.en().stream().map(section -> section.id() + "=" + section.text().length()).toList()
                        + "，提示 " + summary.warnings().size() + " 条");
            }
        }
        System.out.println("[临时 storage] " + temp + "（检查完可以删除）");
    }

    private static long ms(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
