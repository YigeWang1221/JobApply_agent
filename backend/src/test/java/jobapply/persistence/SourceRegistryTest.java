package jobapply.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.NoSuchElementException;
import jobapply.domain.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceRegistryTest {

    @TempDir
    Path root;

    @Test
    void registersOnceScansAndDeletesWithoutReusingKeys() {
        TestStorage storage = TestStorage.create(root);
        SourceRegistry registry = new SourceRegistry(storage.layout, storage.clock);

        SourceRegistry.Registered dir = registry.register(Source.Type.DIR, "/tmp/demo-project", "");
        SourceRegistry.Registered repo = registry.register(Source.Type.GITHUB, "example-owner/example-repo", "abc123");
        SourceRegistry.Registered again = registry.register(Source.Type.DIR, "/tmp/demo-project", "");

        assertThat(dir.source().key()).isEqualTo("s-2026-10-22-001");
        assertThat(repo.source().key()).isEqualTo("s-2026-10-22-002");
        assertThat(again.duplicate()).isTrue();
        assertThat(again.source().key()).isEqualTo("s-2026-10-22-001");

        Source scanned = registry.scanned("s-2026-10-22-001", "files:0123abcd", 37);
        assertThat(scanned.fileCount()).isEqualTo("37");
        assertThat(registry.find("s-2026-10-22-001").orElseThrow().version()).isEqualTo("files:0123abcd");

        registry.delete("s-2026-10-22-002");
        assertThat(registry.status("s-2026-10-22-002")).isEqualTo(SourceRegistry.Status.DELETED);
        assertThat(registry.status("s-2026-10-22-001")).isEqualTo(SourceRegistry.Status.ACTIVE);
        assertThat(registry.status("s-2026-10-22-099")).isEqualTo(SourceRegistry.Status.UNKNOWN);
        assertThat(registry.register(Source.Type.GITHUB, "example-owner/other", "").source().key())
                .isEqualTo("s-2026-10-22-003");
        assertThatThrownBy(() -> registry.delete("s-2026-10-22-002")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void unknownColumnsAndTextArePreserved() {
        TestStorage storage = TestStorage.create(root);
        storage.write(storage.layout.baseSources(), """
                # 来源登记

                我手写的说明。

                | 键 | 类型 | 位置 | 版本 | 登记时间 | 最近扫描 | 读取文件数 | 说明 | 用途 |
                | --- | --- | --- | --- | --- | --- | --- | --- | --- |
                | s-2026-10-01-001 | dir | /tmp/old | — | 2026-10-01T10:00:00-04:00 | — | — | 旧项目 | 课程 |
                """);
        SourceRegistry registry = new SourceRegistry(storage.layout, storage.clock);

        registry.register(Source.Type.DIR, "/tmp/new", "");

        assertThat(registry.find("s-2026-10-01-001").orElseThrow().extra()).containsEntry("用途", "课程");
        String text = storage.read(storage.layout.baseSources());
        assertThat(text).startsWith("# 来源登记").contains("我手写的说明。").contains("| 课程 |").contains("/tmp/new");
    }
}
