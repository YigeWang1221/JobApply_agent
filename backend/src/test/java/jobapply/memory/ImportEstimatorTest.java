package jobapply.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import jobapply.domain.Fact;
import jobapply.domain.FactClass;
import jobapply.domain.ImportRun;
import jobapply.domain.Source;
import jobapply.persistence.FactsFile;
import jobapply.persistence.ImportRunStore;
import jobapply.persistence.SourceRegistry;
import jobapply.persistence.TestStorage;
import jobapply.tools.FakeGitHubServer;
import jobapply.tools.GitHubPublicReader;
import jobapply.tools.LocalDirectoryScanner;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 估算：不调用模型。GitHub 连本机的假服务端；本地目录是 @TempDir 里的虚构项目。
 */
class ImportEstimatorTest {

    @TempDir
    Path temp;

    @Test
    void modelCallsFollowTheBatchingRule() {
        assertThat(ImportEstimator.modelCalls(0)).isZero();
        assertThat(ImportEstimator.modelCalls(1)).isEqualTo(2);
        assertThat(ImportEstimator.modelCalls(ImportEstimator.CHARS_PER_CALL)).isEqualTo(2);
        assertThat(ImportEstimator.modelCalls(ImportEstimator.CHARS_PER_CALL + 1)).isEqualTo(4);
        assertThat(ImportEstimator.modelCalls(ImportEstimator.CHARS_PER_CALL * 3L)).isEqualTo(5);
    }

    @Test
    void estimatesDirectoryAndGitHubSourcesAndRecordsTheScan() throws Exception {
        TestStorage storage = TestStorage.create(temp.resolve("storage"), true);
        FactsFile.write(storage.layout.baseFacts(), "个人基础事实",
                List.of(Fact.of("contact.email", "demo@example.com", FactClass.B, "user-confirmed", "2026-10-22")));
        storage.git().commit("base 更新", List.of(storage.layout.baseFacts()));
        Path project = Files.createDirectories(temp.resolve("demo-project"));
        Files.writeString(project.resolve("README.md"), "# 示例\n" + "文字".repeat(100));
        Files.writeString(project.resolve(".env"), "不读");
        try (Git git = Git.init().setDirectory(project.toFile()).call()) {
            git.commit().setAllowEmpty(true).setMessage("初始化").setAuthor("Demo", "demo@example.com")
                    .setCommitter("Demo", "demo@example.com").setSign(false).call();
        }
        SourceRegistry sources = new SourceRegistry(storage.layout, storage.clock);
        String dir = sources.register(Source.Type.DIR, project.toRealPath().toString(), "").source().key();
        String repo = sources.register(Source.Type.GITHUB, "example-owner/demo", "").source().key();

        try (FakeGitHubServer server = new FakeGitHubServer()) {
            ImportEstimator estimator = estimator(storage, server, new LocalDirectoryScanner.Limits(400, 1_500_000,
                    200 * 1024, 40));

            ImportRun run = estimator.estimate(List.of(dir, repo, dir));

            assertThat(run.status()).isEqualTo(ImportRun.Status.ESTIMATED);
            assertThat(run.sources()).containsExactly(dir, repo);
            ImportRun.Estimate estimate = run.estimate();
            ImportRun.SourceEstimate local = estimate.sources().getFirst();
            assertThat(local.scan().files()).extracting(f -> f.path()).containsExactly("README.md");
            assertThat(local.scan().commits().ownPercent()).isEqualTo(100);
            assertThat(local.modelCalls()).isEqualTo(2);
            assertThat(local.githubCallsPlanned()).isZero();
            ImportRun.SourceEstimate remote = estimate.sources().get(1);
            assertThat(remote.scan().version()).isEqualTo(FakeGitHubServer.SHA);
            assertThat(estimate.githubCallsUsed()).isEqualTo(3);
            assertThat(estimate.githubCallsPlanned()).isEqualTo(5);
            assertThat(estimate.modelCalls()).isEqualTo(2 + 2 + 1);
            assertThat(estimate.chars()).isEqualTo(local.scan().chars() + remote.scan().chars());
            assertThat(estimate.overLimit()).isFalse();
            assertThat(new ImportRunStore(storage.layout, storage.clock).require(run.id())).isEqualTo(run);

            Source scanned = sources.find(dir).orElseThrow();
            assertThat(scanned.version()).isEqualTo(local.scan().version());
            assertThat(scanned.fileCount()).isEqualTo("1");
            assertThat(scanned.lastScannedAt()).isEqualTo("2026-10-22T10:00:00-04:00");
            assertThat(sources.find(repo).orElseThrow().fileCount()).isEqualTo("3");
            assertThat(storage.isClean()).isTrue();
        }
    }

    @Test
    void overLimitAndBadSelectionsAreReported() throws Exception {
        TestStorage storage = TestStorage.create(temp.resolve("storage"), false);
        Path project = Files.createDirectories(temp.resolve("demo-project"));
        for (int i = 0; i < 5; i++) {
            Files.writeString(project.resolve("note-" + i + ".md"), "虚构笔记 " + i);
        }
        SourceRegistry sources = new SourceRegistry(storage.layout, storage.clock);
        String dir = sources.register(Source.Type.DIR, project.toRealPath().toString(), "").source().key();

        try (FakeGitHubServer server = new FakeGitHubServer()) {
            ImportEstimator estimator = estimator(storage, server, new LocalDirectoryScanner.Limits(2, 1_500_000,
                    200 * 1024, 40));
            ImportRun run = estimator.estimate(List.of(dir));
            assertThat(run.estimate().overLimit()).isTrue();
            assertThat(run.estimate().warnings()).anyMatch(w -> w.contains("缩小目录"));

            assertThatThrownBy(() -> estimator.estimate(List.of())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> estimator.estimate(List.of("s-2026-10-22-099")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("来源不存在");
            assertThat(server.received()).isEmpty();
        }
    }

    private static ImportEstimator estimator(TestStorage storage, FakeGitHubServer server,
                                             LocalDirectoryScanner.Limits limits) {
        return new ImportEstimator(storage.layout, new SourceRegistry(storage.layout, storage.clock),
                new ImportRunStore(storage.layout, storage.clock), new LocalDirectoryScanner(limits),
                new GitHubPublicReader(server.uri(), Duration.ofSeconds(5)), limits);
    }
}
