package jobapply.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageInitializerTest {

    @TempDir
    Path root;

    @Test
    void gitOnWritesSkeletonAndCommitsOnce() {
        TestStorage storage = TestStorage.create(root, true);
        StorageLayout layout = storage.layout;

        assertThat(layout.baseFacts()).exists();
        assertThat(layout.baseConflicts()).exists();
        assertThat(layout.baseOriginals()).isDirectory();
        assertThat(layout.projectsDir()).isDirectory();
        assertThat(layout.jobsIndex()).exists();
        assertThat(layout.exportsApplicationsCsv()).exists();
        assertThat(storage.read(layout.gitignore()).lines())
                .contains("settings/.env", "uploads/", "*.sqlite", ".obsidian/", "example.data/");
        assertThat(layout.gitDir()).isDirectory();
        assertThat(storage.commitCount()).isEqualTo(1);
        assertThat(storage.trackedFiles()).contains(".gitignore", "base/facts.md", "jobs-index.md");

        new StorageInitializer(layout, storage.clock).initialize(true);
        assertThat(storage.commitCount()).isEqualTo(1);
    }

    @Test
    void gitOffWritesSameSkeletonWithoutGitDir() {
        TestStorage storage = TestStorage.create(root, false);

        assertThat(storage.layout.baseFacts()).exists();
        assertThat(storage.layout.jobsIndex()).exists();
        assertThat(storage.layout.gitDir()).doesNotExist();
        assertThat(storage.git().commit("x", java.util.List.of(storage.layout.baseFacts()))).isFalse();
    }

    @Test
    void enablingGitLaterCommitsExistingFilesButNeverExampleData() {
        TestStorage storage = TestStorage.create(root, false);
        storage.write(root.resolve("example.data/x.md"), "旧工作区的数据");
        storage.write(storage.layout.baseOriginals().resolve("resume-demo.txt"), "虚构简历");

        boolean committed = new StorageInitializer(storage.layout, storage.clock).enableGit();

        assertThat(committed).isTrue();
        assertThat(storage.commitCount()).isEqualTo(1);
        assertThat(storage.trackedFiles())
                .contains("base/facts.md", "base/originals/resume-demo.txt")
                .noneMatch(path -> path.startsWith("example.data/"));
        assertThat(new StorageInitializer(storage.layout, storage.clock).enableGit()).isFalse();
    }

    @Test
    void existingFilesAreNotOverwritten() {
        TestStorage storage = TestStorage.create(root, false);
        storage.write(storage.layout.baseFacts(), "# 用户已经写过的内容\n");

        new StorageInitializer(storage.layout, storage.clock).initialize(false);

        assertThat(storage.read(storage.layout.baseFacts())).isEqualTo("# 用户已经写过的内容\n");
    }

    @Test
    void turningGitOffIsNotOffered() {
        TestStorage storage = TestStorage.create(root, true);

        assertThatThrownBy(() -> new StorageInitializer(storage.layout, storage.clock).initialize(false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不提供关闭 git");
        assertThat(Files.isDirectory(storage.layout.gitDir())).isTrue();
    }
}
