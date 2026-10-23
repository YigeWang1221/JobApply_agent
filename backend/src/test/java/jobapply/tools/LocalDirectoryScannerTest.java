package jobapply.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import jobapply.domain.SourceScan;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 本地目录的只读扫描。目录是在 @TempDir 里现造的虚构项目；秘密文件设成不可读，扫描仍然成功，证明它们没有被打开。
 */
class LocalDirectoryScannerTest {

    static final LocalDirectoryScanner.Limits LIMITS = new LocalDirectoryScanner.Limits(400, 1_500_000, 200 * 1024, 40);

    @TempDir
    Path temp;

    private final List<Path> locked = new ArrayList<>();

    @AfterEach
    void unlock() throws IOException {
        for (Path path : locked) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(Files.isDirectory(path)
                    ? "rwx------" : "rw-------"));
        }
    }

    @Test
    void secretsAreSkippedByNameAndNeverOpened() throws Exception {
        Path project = project();
        for (String secret : List.of(".env", ".env.local", "id_rsa", "deploy/server.pem", "config/credentials.json",
                "config/app-secret.yaml")) {
            lock(write(project, secret, "不应被读取的虚构内容"));
        }
        Path secretsDir = Files.createDirectories(project.resolve("secrets"));
        write(project, "secrets/token.txt", "不应被读取");
        lock(secretsDir);

        SourceScan scan = new LocalDirectoryScanner(LIMITS).scan(project, Set.of());

        assertThat(scan.skipped()).filteredOn(s -> s.reason().equals(SecretFileRules.SECRET))
                .extracting(SourceScan.Skipped::path)
                .containsExactlyInAnyOrder(".env", ".env.local", "id_rsa", "deploy/server.pem",
                        "config/credentials.json", "config/app-secret.yaml", "secrets/");
        assertThat(scan.files()).extracting(SourceScan.FileEntry::path)
                .noneMatch(path -> path.contains("env") || path.contains("secret") || path.contains("id_rsa"));
    }

    @Test
    void readsDocsManifestsStructuredAndSourceHeads() throws Exception {
        Path project = project();
        String source = IntStream.rangeClosed(1, 100).mapToObj(i -> "line" + i).collect(Collectors.joining("\n"));
        write(project, "src/Main.java", source);
        write(project, "data/big.json", "x".repeat(200 * 1024 + 1));
        write(project, "logo.png", "不是图片，只是名字");
        Files.createDirectories(project.resolve("docs"));
        Files.copy(getClass().getResourceAsStream("/assets/resume-demo.pdf"), project.resolve("docs/demo.pdf"));

        SourceScan scan = new LocalDirectoryScanner(LIMITS).scan(project, Set.of());

        assertThat(scan.files()).extracting(SourceScan.FileEntry::path, SourceScan.FileEntry::kind).contains(
                tuple("README.md", "doc"),
                tuple("package.json", "manifest"),
                tuple("requirements-dev.txt", "manifest"),
                tuple("config/app.yaml", "structured"),
                tuple("src/Main.java", "source"),
                tuple("docs/demo.pdf", "doc"));
        long head = IntStream.rangeClosed(1, 40).mapToLong(i -> ("line" + i).length() + 1).sum();
        assertThat(file(scan, "src/Main.java").chars()).isEqualTo(head);
        assertThat(file(scan, "docs/demo.pdf").chars()).isPositive();
        assertThat(skipReason(scan, "data/big.json")).contains("200KB");
        assertThat(skipReason(scan, "logo.png")).contains("不在读取范围");
        assertThat(scan.chars()).isEqualTo(scan.files().stream().mapToLong(SourceScan.FileEntry::chars).sum());
        assertThat(scan.version()).startsWith("files:");
        assertThat(new LocalDirectoryScanner(LIMITS).scan(project, Set.of()).version()).isEqualTo(scan.version());
        write(project, "README.md", "# 改过的介绍\n");
        assertThat(new LocalDirectoryScanner(LIMITS).scan(project, Set.of()).version()).isNotEqualTo(scan.version());
        assertThat(scan.overLimit()).isFalse();
    }

    @Test
    void generatedDirsGitignoreAndSymlinksAreSkipped() throws Exception {
        Path project = project();
        write(project, "node_modules/pkg/package.json", "{}");
        write(project, "target/classes/App.java", "class App {}");
        write(project, ".gitignore", "logs/\n*.tmp.md\n");
        write(project, "logs/today.md", "日志");
        write(project, "notes.tmp.md", "临时");
        write(project, "sub/.gitignore", "private.md\n");
        write(project, "sub/private.md", "子目录忽略");
        write(project, "sub/public.md", "子目录保留");
        Path outside = Files.createDirectories(temp.resolve("outside"));
        write(outside, "leak.md", "目录外的文件");
        Files.createSymbolicLink(project.resolve("link-dir"), outside);
        Files.createSymbolicLink(project.resolve("link.md"), outside.resolve("leak.md"));

        SourceScan scan = new LocalDirectoryScanner(LIMITS).scan(project, Set.of());

        assertThat(skipReason(scan, "node_modules/")).isEqualTo(SecretFileRules.GENERATED);
        assertThat(skipReason(scan, "target/")).isEqualTo(SecretFileRules.GENERATED);
        assertThat(skipReason(scan, "logs/")).contains(".gitignore");
        assertThat(skipReason(scan, "notes.tmp.md")).contains(".gitignore");
        assertThat(skipReason(scan, "sub/private.md")).contains(".gitignore");
        assertThat(skipReason(scan, "link-dir")).contains("符号链接");
        assertThat(skipReason(scan, "link.md")).contains("符号链接");
        assertThat(scan.files()).extracting(SourceScan.FileEntry::path).contains("sub/public.md")
                .noneMatch(path -> path.contains("leak") || path.startsWith("node_modules"));
    }

    @Test
    void limitsAreReported() throws Exception {
        Path project = project();
        SourceScan scan = new LocalDirectoryScanner(new LocalDirectoryScanner.Limits(3, 1_500_000, 200 * 1024, 40))
                .scan(project, Set.of());

        assertThat(scan.files()).hasSize(3);
        assertThat(scan.overLimit()).isTrue();
        assertThat(scan.warnings()).anyMatch(w -> w.contains("3 个文件"));
        assertThat(scan.skipped()).anyMatch(s -> s.reason().equals("超过文件数上限"));

        SourceScan chars = new LocalDirectoryScanner(new LocalDirectoryScanner.Limits(400, 10, 200 * 1024, 40))
                .scan(project, Set.of());
        assertThat(chars.overLimit()).isTrue();
        assertThat(chars.warnings()).anyMatch(w -> w.contains("10 个字符"));
    }

    @Test
    void theFileThatCrossesTheCharLimitIsReportedAndNotRead() throws Exception {
        Path project = Files.createDirectories(temp.resolve("one-file"));
        write(project, "notes.md", "字".repeat(101));

        SourceScan scan = new LocalDirectoryScanner(new LocalDirectoryScanner.Limits(400, 100, 200 * 1024, 40))
                .scan(project, Set.of());

        assertThat(scan.overLimit()).isTrue();
        assertThat(scan.warnings()).anyMatch(w -> w.contains("100 个字符"));
        assertThat(scan.files()).isEmpty();
        assertThat(scan.chars()).isZero();
        assertThat(skipReason(scan, "notes.md")).isEqualTo("超过字符数上限");

        write(project, "notes.md", "字".repeat(100));
        SourceScan exact = new LocalDirectoryScanner(new LocalDirectoryScanner.Limits(400, 100, 200 * 1024, 40))
                .scan(project, Set.of());
        assertThat(exact.overLimit()).isFalse();
        assertThat(exact.chars()).isEqualTo(100);
    }

    @Test
    void commitSummaryIsReadOnlyFromGit() throws Exception {
        Path project = project();
        try (Git git = Git.init().setDirectory(project.toFile()).setInitialBranch("main").call()) {
            commit(git, "初始化", "Demo", "demo@example.com", "2026-01-01T00:00:00Z");
            commit(git, "加功能\n\n正文", "Other", "other@example.com", "2026-02-01T00:00:00Z");
            commit(git, "修复", "Demo", "demo@example.com", "2026-03-01T00:00:00Z");
        }

        SourceScan scan = new LocalDirectoryScanner(LIMITS).scan(project, Set.of("DEMO@example.com"));

        assertThat(skipReason(scan, ".git/")).isEqualTo(SecretFileRules.GIT);
        assertThat(scan.commits().count()).isEqualTo(3);
        assertThat(scan.commits().ownPercent()).isEqualTo(67);
        assertThat(scan.commits().recentTitles()).containsExactly("修复", "加功能", "初始化");
        assertThat(scan.commits().firstAt()).startsWith("2026-01-01");
        assertThat(scan.commits().lastAt()).startsWith("2026-03-01");
        assertThat(new LocalDirectoryScanner(LIMITS).scan(project, Set.of()).commits().ownPercent()).isNull();

        Path empty = Files.createDirectories(temp.resolve("empty"));
        Git.init().setDirectory(empty.toFile()).call().close();
        assertThat(new LocalDirectoryScanner(LIMITS).scan(empty, Set.of()).commits().count()).isZero();
    }

    @Test
    void rootMustBeAbsoluteReadableAndAwayFromStorage() throws Exception {
        Path storage = Files.createDirectories(temp.resolve("home/storage"));
        Path project = project();

        assertThat(LocalDirectoryScanner.validateRoot(project.toString(), storage)).isEqualTo(project.toRealPath());
        assertThatThrownBy(() -> LocalDirectoryScanner.validateRoot("relative/dir", storage))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("绝对路径");
        assertThatThrownBy(() -> LocalDirectoryScanner.validateRoot(temp.resolve("missing").toString(), storage))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不存在");
        for (Path bad : List.of(storage, storage.getParent(), Files.createDirectories(storage.resolve("base")))) {
            assertThatThrownBy(() -> LocalDirectoryScanner.validateRoot(bad.toString(), storage))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("storage");
        }
    }

    /** 虚构的小项目。 */
    private Path project() throws IOException {
        Path project = Files.createDirectories(temp.resolve("demo-project"));
        write(project, "README.md", "# 示例项目\n虚构的介绍。\n");
        write(project, "package.json", "{\"name\": \"demo\", \"dependencies\": {\"react\": \"19.0.0\"}}");
        write(project, "requirements-dev.txt", "pytest\n");
        write(project, "config/app.yaml", "port: 8080\n");
        return project;
    }

    private static Path write(Path root, String relative, String content) throws IOException {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

    private void lock(Path path) throws IOException {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("---------"));
        locked.add(path);
    }

    private static SourceScan.FileEntry file(SourceScan scan, String path) {
        return scan.files().stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow();
    }

    private static String skipReason(SourceScan scan, String path) {
        return scan.skipped().stream().filter(s -> s.path().equals(path)).findFirst()
                .map(SourceScan.Skipped::reason).orElseThrow(() -> new AssertionError("没有跳过：" + path));
    }

    private static void commit(Git git, String message, String name, String email, String at) throws Exception {
        PersonIdent ident = new PersonIdent(name, email, Instant.parse(at), ZoneOffset.UTC);
        git.commit().setAllowEmpty(true).setMessage(message).setAuthor(ident).setCommitter(ident).setSign(false)
                .call();
    }
}
