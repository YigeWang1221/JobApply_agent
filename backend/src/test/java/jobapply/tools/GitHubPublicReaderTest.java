package jobapply.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import jobapply.domain.SourceScan;
import jobapply.tools.FakeGitHubServer.Reply;
import jobapply.tools.GitHubPublicReader.GitHubException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * GitHub 公开仓库的只读读取。只连本机的假服务端，不访问外部网络。
 */
class GitHubPublicReaderTest {

    static final LocalDirectoryScanner.Limits LIMITS = new LocalDirectoryScanner.Limits(400, 1_500_000, 200 * 1024, 40);

    private FakeGitHubServer server;
    private GitHubPublicReader reader;

    @BeforeEach
    void setUp() {
        server = new FakeGitHubServer();
        reader = new GitHubPublicReader(server.uri(), Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void parsesTheAcceptedForms() {
        assertThat(GitHubPublicReader.parse("https://github.com/example-owner/demo"))
                .isEqualTo(new GitHubPublicReader.Ref("example-owner", "demo"));
        assertThat(GitHubPublicReader.parse("github.com/example-owner/demo.git/"))
                .isEqualTo(new GitHubPublicReader.Ref("example-owner", "demo"));
        assertThat(GitHubPublicReader.parse("example-owner/demo"))
                .isEqualTo(new GitHubPublicReader.Ref("example-owner", "demo"));
        assertThat(GitHubPublicReader.parse("https://github.com/example-owner"))
                .isEqualTo(new GitHubPublicReader.Ref("example-owner", null));
        assertThat(GitHubPublicReader.parse("example-owner")).isEqualTo(new GitHubPublicReader.Ref("example-owner", null));
        assertThatThrownBy(() -> GitHubPublicReader.parse("https://gitlab.com/a/b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GitHubPublicReader.parse("a/b/c")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GitHubPublicReader.parse("../etc")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scanPinsTheShaAndAppliesTheSkipRules() {
        SourceScan scan = reader.scan("example-owner", "demo", LIMITS);

        assertThat(scan.version()).isEqualTo(FakeGitHubServer.SHA);
        assertThat(scan.githubCalls()).isEqualTo(3);
        assertThat(scan.files()).extracting(SourceScan.FileEntry::path)
                .containsExactly("README.md", "package.json", "service/pom.xml", "（文件列表）");
        assertThat(scan.skipped()).extracting(SourceScan.Skipped::path)
                .containsExactly(".env", "src/server.pem", "node_modules/");
        assertThat(scan.skipped().getFirst().reason()).isEqualTo(SecretFileRules.SECRET);
        assertThat(scan.chars()).isEqualTo(1200 + 300 + 800 + scan.files().getLast().chars());
        assertThat(GitHubPublicReader.plannedCalls(scan)).isEqualTo(5);
        assertThat(server.received()).allSatisfy(request -> {
            assertThat(request.authorization()).isNull();
            assertThat(request.userAgent()).isNotBlank();
            assertThat(request.apiVersion()).isEqualTo("2022-11-28");
        });
    }

    @Test
    void readsContentLanguagesAndCommitSummary() {
        assertThat(reader.content("example-owner", "demo", FakeGitHubServer.SHA, "README.md"))
                .isEqualTo("# 示例仓库\n虚构的介绍。\n");
        assertThat(reader.languages("example-owner", "demo")).containsEntry("TypeScript", 5000L);
        SourceScan.CommitSummary summary = reader.commitSummary("example-owner", "demo", FakeGitHubServer.SHA,
                Set.of("Demo@Example.com"));
        assertThat(summary.count()).isEqualTo(2);
        assertThat(summary.ownPercent()).isEqualTo(50);
        assertThat(summary.recentTitles()).containsExactly("第二次提交", "初始化");
        assertThat(summary.firstAt()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(reader.commitSummary("example-owner", "demo", FakeGitHubServer.SHA, Set.of()).ownPercent()).isNull();
        // 秘密文件不发请求
        int before = server.received().size();
        assertThatThrownBy(() -> reader.content("example-owner", "demo", FakeGitHubServer.SHA, ".env"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(server.received()).hasSize(before);
    }

    @Test
    void ownerOnlyListsPublicRepositories() {
        assertThat(reader.listRepositories("example-owner")).extracting(GitHubPublicReader.RepoSummary::fullName)
                .containsExactly("example-owner/demo", "example-owner/fork-demo");
    }

    @Test
    void missingPrivateAndEmptyRepositoriesAreReported() {
        assertThatThrownBy(() -> reader.repository("example-owner", "ghost"))
                .isInstanceOfSatisfying(GitHubException.class, e -> {
                    assertThat(e.kind()).isEqualTo(GitHubException.Kind.NOT_FOUND);
                    assertThat(e.getMessage()).contains("不存在或不是公开的");
                });
        server.reply("/repos/example-owner/secret-repo", Reply.json(
                "{\"full_name\": \"example-owner/secret-repo\", \"private\": true, \"default_branch\": \"main\"}"));
        assertThatThrownBy(() -> reader.repository("example-owner", "secret-repo"))
                .isInstanceOfSatisfying(GitHubException.class,
                        e -> assertThat(e.kind()).isEqualTo(GitHubException.Kind.NOT_FOUND));
        server.reply("/repos/example-owner/empty", Reply.json(
                "{\"full_name\": \"example-owner/empty\", \"private\": false, \"default_branch\": \"main\"}"));
        server.reply("/repos/example-owner/empty/commits/main",
                new Reply(409, "{\"message\": \"Git Repository is empty.\"}", Map.of()));
        assertThatThrownBy(() -> reader.repository("example-owner", "empty"))
                .isInstanceOfSatisfying(GitHubException.class, e -> {
                    assertThat(e.kind()).isEqualTo(GitHubException.Kind.EMPTY);
                    assertThat(e.getMessage()).contains("空");
                });
    }

    @Test
    void rateLimitsAreReportedWithResetTimeAndNotRetried() {
        server.reply("/repos/example-owner/demo", new Reply(403, "{\"message\": \"API rate limit exceeded\"}",
                Map.of("x-ratelimit-remaining", "0", "x-ratelimit-reset", "1798761600")));

        assertThatThrownBy(() -> reader.repository("example-owner", "demo"))
                .isInstanceOfSatisfying(GitHubException.class, e -> {
                    assertThat(e.kind()).isEqualTo(GitHubException.Kind.RATE_LIMITED);
                    assertThat(e.getMessage()).contains("额度用完").contains("重置").contains("不会自动重试");
                });
        assertThat(server.received()).hasSize(1);

        server.reply("/repos/example-owner/demo", new Reply(429, "{}", Map.of("retry-after", "60")));
        assertThatThrownBy(() -> reader.repository("example-owner", "demo"))
                .isInstanceOfSatisfying(GitHubException.class,
                        e -> assertThat(e.getMessage()).contains("60 秒后再试"));
        server.reply("/repos/example-owner/demo", new Reply(403, "{\"message\": \"Forbidden\"}", Map.of()));
        assertThatThrownBy(() -> reader.repository("example-owner", "demo"))
                .isInstanceOfSatisfying(GitHubException.class,
                        e -> assertThat(e.kind()).isEqualTo(GitHubException.Kind.UPSTREAM));
    }
}
