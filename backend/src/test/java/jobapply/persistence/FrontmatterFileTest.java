package jobapply.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FrontmatterFileTest {

    @TempDir
    Path root;

    private static final String HAND_WRITTEN = """
            ---
            zeta: 1
            id: 2026-10-22-001
            last_checked_at: 2026-10-22T10:00:00-04:00
            confirmed_at: 2026-10-13
            custom:
              nested: 值
            alpha: [上海, 北京]
            ---

            # 正文

            正文里也可以有 --- 分隔线，原样保留。
            """;

    @Test
    void parsesTimesAsStringsKeepingOffset() {
        FrontmatterFile.Document document = FrontmatterFile.parse(HAND_WRITTEN, "test");

        assertThat(document.frontmatter().get("last_checked_at")).isEqualTo("2026-10-22T10:00:00-04:00");
        assertThat(document.frontmatter().get("confirmed_at")).isEqualTo("2026-10-13");
        assertThat(document.frontmatter().get("id")).isEqualTo("2026-10-22-001");
        assertThat(document.frontmatter().get("zeta")).isEqualTo(1);
    }

    @Test
    void roundTripKeepsBodyKeyOrderAndUnknownKeys() {
        Path file = root.resolve("job.md");
        FrontmatterFile.Document original = FrontmatterFile.parse(HAND_WRITTEN, "test");

        FrontmatterFile.write(file, original);
        FrontmatterFile.Document reread = FrontmatterFile.read(file);

        assertThat(reread.body()).isEqualTo(original.body());
        assertThat(reread.frontmatter().keySet())
                .containsExactly("zeta", "id", "last_checked_at", "confirmed_at", "custom", "alpha");
        assertThat(reread.frontmatter()).isEqualTo(original.frontmatter());
        assertThat(reread.frontmatter().get("custom")).isEqualTo(Map.of("nested", "值"));
        assertThat(FrontmatterFile.render(reread)).isEqualTo(FrontmatterFile.render(original));
    }

    @Test
    void fileWithoutFrontmatterIsAllBody() {
        FrontmatterFile.Document document = FrontmatterFile.parse("# 只有正文\n", "test");

        assertThat(document.frontmatter()).isEmpty();
        assertThat(document.body()).isEqualTo("# 只有正文\n");
    }

    @Test
    void missingClosingFenceIsReportedWithPath() throws Exception {
        Path file = root.resolve("broken.md");
        Files.writeString(file, "---\nid: x\n# 没有结束\n");

        assertThatThrownBy(() -> FrontmatterFile.read(file))
                .isInstanceOf(StorageFormatException.class)
                .hasMessageContaining(file.toString())
                .hasMessageContaining("缺少结束的 ---");
    }
}
