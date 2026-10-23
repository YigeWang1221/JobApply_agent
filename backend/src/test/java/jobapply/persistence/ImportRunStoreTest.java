package jobapply.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import jobapply.domain.ImportRun;
import jobapply.domain.SourceScan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportRunStoreTest {

    @TempDir
    Path root;

    @Test
    void createsReadsAndKeepsUnknownFields() {
        TestStorage storage = TestStorage.create(root, true);
        ImportRunStore store = new ImportRunStore(storage.layout, storage.clock);
        SourceScan scan = new SourceScan("files:abcd", List.of(new SourceScan.FileEntry("README.md", "doc", 120)),
                List.of(new SourceScan.Skipped(".env", "秘密文件，按名字跳过，没有打开")), 120,
                new SourceScan.CommitSummary(3, "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z", 67, List.of("修复")),
                List.of(), 0, false);
        ImportRun.Estimate estimate = new ImportRun.Estimate(
                List.of(new ImportRun.SourceEstimate("s-2026-10-22-001", "dir", "/tmp/demo", scan, 2, 0)), 120, 2, 0, 0,
                List.of(), false);

        ImportRun run = store.create(List.of("s-2026-10-22-001"), estimate);
        ImportRun second = store.create(List.of("s-2026-10-22-001"), estimate);

        assertThat(run.id()).isEqualTo("r-2026-10-22-001");
        assertThat(second.id()).isEqualTo("r-2026-10-22-002");
        assertThat(run.status()).isEqualTo(ImportRun.Status.ESTIMATED);
        assertThat(store.require(run.id())).isEqualTo(run);
        assertThat(storage.isClean()).isTrue();

        String json = storage.read(storage.layout.importRun(run.id()));
        assertThat(json).contains("\"status\" : \"estimated\"");
        storage.write(storage.layout.importRun(run.id()), json.replaceFirst("\\{", "{\"progress\": {\"done\": 1},"));
        ImportRun withExtra = store.require(run.id());
        assertThat(withExtra.extra()).containsEntry("progress", Map.of("done", 1));
        store.save(withExtra);
        assertThat(storage.read(storage.layout.importRun(run.id()))).contains("\"progress\"");

        assertThatThrownBy(() -> store.require("r-2026-10-22-099")).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> store.find("../x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listsNewestFirstAndPurgesFinishedRunsAfterThirtyDays() {
        TestStorage storage = TestStorage.create(root, true);
        ImportRunStore store = new ImportRunStore(storage.layout, storage.clock);
        ImportRun done = store.create(List.of("s-2026-10-22-001"), null);
        store.save(new ImportRun(done.id(), ImportRun.Status.DONE, done.createdAt(), done.updatedAt(), done.sources(),
                null, Map.of()));
        ImportRun reviewing = store.create(List.of("s-2026-10-22-001"), null);
        store.save(new ImportRun(reviewing.id(), ImportRun.Status.REVIEWING, reviewing.createdAt(),
                reviewing.updatedAt(), reviewing.sources(), null, Map.of()));

        assertThat(store.list()).extracting(ImportRun::id).containsExactly(reviewing.id(), done.id());
        assertThat(store.purgeDone(Duration.ofDays(30))).isZero();

        storage.clock.advance(Duration.ofDays(31));
        assertThat(store.purgeDone(Duration.ofDays(30))).isEqualTo(1);

        assertThat(store.list()).extracting(ImportRun::id).containsExactly(reviewing.id());
        assertThat(storage.isClean()).isTrue();
    }
}
