package jobapply.persistence;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 原件全部是测试里现写的虚构内容。
 */
class AssetRegistryTest {

    private static final byte[] RESUME = "虚构简历 2025 版".getBytes(UTF_8);

    @TempDir
    Path root;

    @Test
    void registersSavesTheOriginalAndWritesTheIndex() {
        TestStorage storage = TestStorage.create(root);
        AssetRegistry registry = new AssetRegistry(storage.layout, storage.clock);

        AssetRegistry.Registered registered = registry.register("resume-cn.pdf", "application/pdf", RESUME);

        AssetRegistry.Asset asset = registered.asset();
        assertThat(registered.duplicate()).isFalse();
        assertThat(asset.key()).isEqualTo("a-2026-10-22-001");
        assertThat(asset.fileName()).isEqualTo("resume-cn.pdf");
        assertThat(asset.size()).isEqualTo("21 B");
        assertThat(asset.sha256()).hasSize(64).isEqualTo(AssetRegistry.sha256(RESUME));
        assertThat(asset.uploadedAt()).isEqualTo(jobapply.domain.Timestamps.now(storage.clock));
        assertThat(storage.layout.baseOriginals().resolve("resume-cn.pdf")).hasBinaryContent(RESUME);
        assertThat(storage.read(storage.layout.baseAssets())).startsWith("# 原件索引\n\n| 键 | 文件名 | 类型 | 大小 | SHA-256 | 上传时间 | 说明 |")
                .contains("| a-2026-10-22-001 | resume-cn.pdf | application/pdf | 21 B | " + asset.sha256());
        assertThat(registry.list()).containsExactly(asset);
    }

    @Test
    void sameContentIsNotSavedTwice() {
        TestStorage storage = TestStorage.create(root);
        AssetRegistry registry = new AssetRegistry(storage.layout, storage.clock);
        AssetRegistry.Asset first = registry.register("resume-cn.pdf", "application/pdf", RESUME).asset();

        AssetRegistry.Registered again = registry.register("copy-of-resume.pdf", "application/pdf", RESUME);

        assertThat(again.duplicate()).isTrue();
        assertThat(again.asset()).isEqualTo(first);
        assertThat(registry.list()).hasSize(1);
        assertThat(storage.layout.baseOriginals().resolve("copy-of-resume.pdf")).doesNotExist();
    }

    @Test
    void sameNameDifferentContentGetsASuffix() {
        TestStorage storage = TestStorage.create(root);
        AssetRegistry registry = new AssetRegistry(storage.layout, storage.clock);

        registry.register("resume.pdf", "application/pdf", RESUME);
        AssetRegistry.Asset second = registry.register("resume.pdf", "application/pdf", "第二版".getBytes(UTF_8)).asset();
        AssetRegistry.Asset third = registry.register("resume.pdf", "application/pdf", "第三版".getBytes(UTF_8)).asset();
        AssetRegistry.Asset noExt = registry.register("README", "text/plain", "x".getBytes(UTF_8)).asset();
        AssetRegistry.Asset noExt2 = registry.register("README", "text/plain", "y".getBytes(UTF_8)).asset();

        assertThat(second.fileName()).isEqualTo("resume-2.pdf");
        assertThat(third.fileName()).isEqualTo("resume-3.pdf");
        assertThat(third.key()).isEqualTo("a-2026-10-22-003");
        assertThat(noExt2.fileName()).isEqualTo("README-2");
        assertThat(noExt.fileName()).isEqualTo("README");
    }

    @Test
    void fileNamesCannotEscapeTheOriginalsDirectory() {
        TestStorage storage = TestStorage.create(root);
        AssetRegistry registry = new AssetRegistry(storage.layout, storage.clock);

        AssetRegistry.Asset asset = registry.register("../../etc/pa|ss\nwd.txt", "text/plain", RESUME).asset();

        assertThat(asset.fileName()).isEqualTo("passwd.txt");
        assertThat(registry.originalPath(asset).getParent()).isEqualTo(storage.layout.baseOriginals());
        assertThat(AssetRegistry.safeFileName("C:\\Users\\x\\简历.pdf")).isEqualTo("简历.pdf");
        assertThat(AssetRegistry.safeFileName(".hidden")).isEqualTo("hidden");
        assertThatThrownBy(() -> AssetRegistry.safeFileName("..")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.register("x.pdf", "application/pdf", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(AssetRegistry.safeFileName("a".repeat(200) + ".pdf")).hasSize(120).endsWith(".pdf");
    }

    @Test
    void descriptionsCanBeEditedAndUnknownColumnsAreKept() throws Exception {
        TestStorage storage = TestStorage.create(root);
        Files.createDirectories(storage.layout.baseDir());
        Files.writeString(storage.layout.baseAssets(), """
                # 原件索引

                用户写在表格前面的话。

                | 键 | 文件名 | 类型 | 大小 | SHA-256 | 上传时间 | 说明 | 适用方向 |
                | --- | --- | --- | --- | --- | --- | --- | --- |
                | a-2026-10-21-001 | old.pdf | application/pdf | 3 KB | abc | 2026-10-21T10:00:00+08:00 | — | 后端 |

                表格后面的备注。
                """, UTF_8);
        AssetRegistry registry = new AssetRegistry(storage.layout, storage.clock);

        AssetRegistry.Asset edited = registry.describe("a-2026-10-21-001", "  中文简历，2025 版，2 页 ");
        registry.register("new.pdf", "application/pdf", RESUME);

        assertThat(edited.description()).isEqualTo("中文简历，2025 版，2 页");
        assertThat(edited.extra()).containsEntry("适用方向", "后端");
        String content = storage.read(storage.layout.baseAssets());
        assertThat(content).contains("用户写在表格前面的话。").contains("表格后面的备注。")
                .contains("| 说明 | 适用方向 |")
                .contains("| 中文简历，2025 版，2 页 | 后端 |")
                .contains("| a-2026-10-22-001 | new.pdf |");
        assertThat(registry.list()).extracting(AssetRegistry.Asset::key)
                .containsExactly("a-2026-10-21-001", "a-2026-10-22-001");
    }

    @Test
    void badDescriptionsAndUnknownKeysAreRejected() {
        TestStorage storage = TestStorage.create(root);
        AssetRegistry registry = new AssetRegistry(storage.layout, storage.clock);
        String key = registry.register("r.pdf", "application/pdf", RESUME).asset().key();

        assertThatThrownBy(() -> registry.describe(key, "a|b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.describe(key, "a\nb")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.describe(key, "x".repeat(201))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.describe("../x", "说明")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.describe("a-2026-10-22-999", "说明")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void commitsTheIndexAndTheOriginalWhenGitIsOn() {
        TestStorage storage = TestStorage.create(root);

        new AssetRegistry(storage.layout, storage.clock).register("resume.pdf", "application/pdf", RESUME);
    }

    @Test
    void sizesAreHumanReadable() {
        assertThat(AssetRegistry.humanSize(512)).isEqualTo("512 B");
        assertThat(AssetRegistry.humanSize(182 * 1024)).isEqualTo("182 KB");
        assertThat(AssetRegistry.humanSize(3 * 1024 * 1024 + 300_000)).isEqualTo("3.3 MB");
    }
}
