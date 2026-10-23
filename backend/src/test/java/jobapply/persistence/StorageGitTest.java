package jobapply.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.eclipse.jgit.api.Git;
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
    void filesTheUserStagedStayOutOfOurCommit() throws Exception {
        TestStorage storage = TestStorage.create(root, true);
        Path manual = root.resolve("manual.md");
        Path ours = root.resolve("projects/demo/ours.md");
        storage.write(manual, "用户自己暂存的");
        try (Git git = Git.open(root.toFile())) {
            git.add().addFilepattern("manual.md").call();
        }
        int before = storage.commitCount();

        assertThat(storage.git().commit("没有本次路径的变化", List.of(storage.layout.baseFacts()))).isFalse();
        assertThat(storage.commitCount()).isEqualTo(before);

        storage.write(ours, "ours");
        assertThat(storage.git().commit("只提交 ours", List.of(ours))).isTrue();

        assertThat(storage.trackedFiles()).contains("projects/demo/ours.md").doesNotContain("manual.md");
        try (Git git = Git.open(root.toFile())) {
            assertThat(git.status().call().getAdded()).containsExactly("manual.md");
        }
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

    @Test
    void inOneCommitMergesTheCommitsOfSeveralWriters() {
        TestStorage storage = TestStorage.create(root, true);
        int before = storage.commitCount();
        Path a = root.resolve("base/a.md");
        Path b = root.resolve("base/b.md");

        String result = storage.git().inOneCommit("base：一次决定", () -> {
            storage.write(a, "a\n");
            assertThat(storage.git().commit("a", List.of(a))).isFalse();
            storage.write(b, "b\n");
            new StorageGit(storage.layout, storage.clock).commit("b", List.of(b));
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(storage.commitCount()).isEqualTo(before + 1);
        assertThat(storage.headMessage()).isEqualTo("base：一次决定");
        assertThat(storage.trackedFiles()).contains("base/a.md", "base/b.md");
        assertThat(storage.isClean()).isTrue();
        assertThatThrownBy(() -> storage.git().inOneCommit("外层", () -> storage.git().inOneCommit("内层", () -> 1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aDeletedFileIsCommittedAsADeletion() throws Exception {
        TestStorage storage = TestStorage.create(root, true);
        Path file = root.resolve("base/import-runs/x.json");
        storage.write(file, "{}\n");
        storage.git().commit("加", List.of(file));
        Files.delete(file);

        assertThat(storage.git().commit("删", List.of(file))).isTrue();

        assertThat(storage.trackedFiles()).doesNotContain("base/import-runs/x.json");
        assertThat(storage.isClean()).isTrue();
    }
}
