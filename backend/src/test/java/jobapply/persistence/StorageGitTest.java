package jobapply.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageGitTest {

    @TempDir
    Path root;

    @Test
    void commitsOnlyTheGivenPaths() {
        TestStorage storage = TestStorage.create(root, true);
        Path ours = root.resolve("projects/demo/a.md");
        Path handEdited = root.resolve("projects/demo/b.md");
        storage.write(ours, "a");
        storage.write(handEdited, "b");

        boolean committed = storage.git().commit("只提交 a", List.of(ours));

        assertThat(committed).isTrue();
        assertThat(storage.trackedFiles()).contains("projects/demo/a.md").doesNotContain("projects/demo/b.md");
        assertThat(storage.git().commit("没有变化", List.of(ours))).isFalse();
        assertThat(storage.commitCount()).isEqualTo(2);
    }

    @Test
    void readsOldVersionsByCommitAndByDate() {
        TestStorage storage = TestStorage.create(root, true);
        String facts = storage.layout.relative(storage.layout.baseFacts());
        storage.clock.set("2026-10-20T15:00:00Z");
        storage.write(storage.layout.baseFacts(), "v1\n");
        storage.git().commit("base v1", List.of(storage.layout.baseFacts()));
        String v1 = storage.git().head().orElseThrow();
        storage.clock.set("2026-10-23T15:00:00Z");
        storage.write(storage.layout.baseFacts(), "v2\n");
        storage.git().commit("base v2", List.of(storage.layout.baseFacts()));

        assertThat(storage.git().readAtCommit(v1, facts)).contains("v1\n");
        assertThat(storage.git().readAtCommit(v1.substring(0, 8), facts)).contains("v1\n");
        assertThat(storage.git().readAtEndOfDay(LocalDate.parse("2026-10-21"), facts)).contains("v1\n");
        assertThat(storage.git().readAtEndOfDay(LocalDate.parse("2026-10-23"), facts)).contains("v2\n");
        assertThat(storage.git().readAtEndOfDay(LocalDate.parse("2026-10-01"), facts)).isEmpty();
        assertThat(storage.git().readAtCommit("0000000", facts)).isEmpty();
    }
}
