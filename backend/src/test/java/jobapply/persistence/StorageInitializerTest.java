package jobapply.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageInitializerTest {

    @TempDir
    Path root;

    @Test
    void writesTheSkeletonWithoutAnyVersionControl() {
        TestStorage storage = TestStorage.create(root);
        StorageLayout layout = storage.layout;

        assertThat(layout.baseFacts()).exists();
        assertThat(layout.baseConflicts()).exists();
        assertThat(layout.baseOriginals()).isDirectory();
        assertThat(layout.projectsDir()).isDirectory();
        assertThat(layout.jobsIndex()).exists();
        assertThat(layout.exportsApplicationsCsv()).exists();
        assertThat(root.resolve(".git")).doesNotExist();
        assertThat(root.resolve(".gitignore")).doesNotExist();
    }

    @Test
    void existingFilesAreNotOverwritten() {
        TestStorage storage = TestStorage.create(root);
        storage.write(storage.layout.baseFacts(), "# 用户已经写过的内容\n");

        new StorageInitializer(storage.layout, storage.clock).initialize();

        assertThat(storage.read(storage.layout.baseFacts())).isEqualTo("# 用户已经写过的内容\n");
    }
}
