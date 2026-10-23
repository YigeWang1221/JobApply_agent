package jobapply.persistence;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 原件全部是测试里现写的虚构内容。
 */
class UploadAreaTest {

    private static final String JOB = "2026-10-23-001";
    private static final byte[] RESUME = "虚构简历 PDF 内容".getBytes(UTF_8);
    private static final byte[] PORTFOLIO = "虚构作品集".getBytes(UTF_8);

    @TempDir
    Path root;

    @Test
    void copiesTheOriginalByteForByteIntoTheJobsUploadsDir() {
        TestStorage storage = TestStorage.create(root);
        AssetRegistry assets = new AssetRegistry(storage.layout, storage.clock);
        String resume = assets.register("resume-demo.pdf", "application/pdf", RESUME).asset().key();
        String portfolio = assets.register("portfolio-demo.pdf", "application/pdf", PORTFOLIO).asset().key();
        UploadArea uploads = new UploadArea(storage.layout, assets);

        List<Path> staged = uploads.stage(JOB, List.of(portfolio, resume));

        assertThat(staged).containsExactly(storage.layout.uploadsDir(JOB).resolve("portfolio-demo.pdf"),
                storage.layout.uploadsDir(JOB).resolve("resume-demo.pdf"));
        assertThat(staged.get(0)).hasBinaryContent(PORTFOLIO);
        assertThat(staged.get(1)).hasBinaryContent(RESUME);
        assertThat(storage.layout.baseOriginals().resolve("resume-demo.pdf")).hasBinaryContent(RESUME);
        assertThat(uploads.dir(JOB)).isEqualTo(storage.layout.uploadsDir(JOB));
    }

    @Test
    void refusesACopyWhoseShaDoesNotMatchTheRegistry() throws Exception {
        TestStorage storage = TestStorage.create(root);
        AssetRegistry assets = new AssetRegistry(storage.layout, storage.clock);
        String resume = assets.register("resume-demo.pdf", "application/pdf", RESUME).asset().key();
        Files.writeString(storage.layout.baseOriginals().resolve("resume-demo.pdf"), "被改过的虚构内容", UTF_8);
        UploadArea uploads = new UploadArea(storage.layout, assets);

        assertThatThrownBy(() -> uploads.stage(JOB, resume))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SHA-256");
        assertThat(storage.layout.uploadsDir(JOB).resolve("resume-demo.pdf")).doesNotExist();
    }

    @Test
    void unknownOrMissingOriginalsAreErrors() throws Exception {
        TestStorage storage = TestStorage.create(root);
        AssetRegistry assets = new AssetRegistry(storage.layout, storage.clock);
        String resume = assets.register("resume-demo.pdf", "application/pdf", RESUME).asset().key();
        UploadArea uploads = new UploadArea(storage.layout, assets);

        assertThatThrownBy(() -> uploads.stage(JOB, "a-2026-10-22-999")).isInstanceOf(NoSuchElementException.class);
        Files.delete(storage.layout.baseOriginals().resolve("resume-demo.pdf"));
        assertThatThrownBy(() -> uploads.stage(JOB, resume)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> uploads.stage("../escape", resume)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clearEmptiesOnlyThisJobsDir() throws Exception {
        TestStorage storage = TestStorage.create(root);
        AssetRegistry assets = new AssetRegistry(storage.layout, storage.clock);
        String resume = assets.register("resume-demo.pdf", "application/pdf", RESUME).asset().key();
        UploadArea uploads = new UploadArea(storage.layout, assets);
        uploads.stage(JOB, resume);
        Path other = uploads.stage("2026-10-23-002", resume);
        Files.createDirectories(uploads.dir(JOB).resolve("nested"));
        Files.writeString(uploads.dir(JOB).resolve("nested/left.txt"), "x", UTF_8);

        uploads.clear(JOB);
        uploads.clear("2026-10-23-003"); // 不存在时什么都不做

        assertThat(uploads.dir(JOB)).isEmptyDirectory();
        assertThat(other).hasBinaryContent(RESUME);
        assertThat(storage.layout.baseOriginals().resolve("resume-demo.pdf")).hasBinaryContent(RESUME);
    }
}
