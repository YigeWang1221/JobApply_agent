package jobapply.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import jobapply.persistence.SiteNotes.ButtonConfirmation;
import jobapply.persistence.SiteNotes.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SiteNotesTest {

    private static final String HOST = "jobs.example.com";
    private static final String JOB = "2026-10-22-001";

    @TempDir
    Path root;

    @Test
    void firstRecordCreatesFileWithFrontmatterAndCommits() {
        TestStorage storage = TestStorage.create(root);
        SiteNotes notes = new SiteNotes(storage.layout, storage.clock);

        notes.recordButton(HOST, "下一步", Verdict.SAFE, JOB);

        assertThat(storage.read(storage.layout.playbookSite(HOST))).isEqualTo("""
                ---
                host: jobs.example.com
                platform: ''
                confirmed_buttons:
                  - name: 下一步
                    verdict: safe
                    at: 2026-10-22T10:00:00-04:00
                    session: 2026-10-22-001
                ---

                # 观察记录
                """);
        assertThat(notes.confirmedSafe(HOST)).containsExactly("下一步");
    }

    @Test
    void recordsAreAppendOnlyAndLatestVerdictWins() {
        TestStorage storage = TestStorage.create(root);
        SiteNotes notes = new SiteNotes(storage.layout, storage.clock);
        notes.recordButton(HOST, "保存", Verdict.SAFE, JOB);
        notes.recordButton(HOST, "下一步", Verdict.SAFE, JOB);
        storage.clock.advance(Duration.ofHours(1));

        notes.recordButton(HOST, " 保存 ", Verdict.UNSAFE, "s-2026-10-22-002");

        SiteNotes.Site site = notes.read(HOST).orElseThrow();
        assertThat(site.confirmedButtons()).extracting(ButtonConfirmation::name, ButtonConfirmation::verdict)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("保存", Verdict.SAFE),
                        org.assertj.core.groups.Tuple.tuple("下一步", Verdict.SAFE),
                        org.assertj.core.groups.Tuple.tuple("保存", Verdict.UNSAFE));
        assertThat(site.confirmedButtons().get(2).at()).isEqualTo("2026-10-22T11:00:00-04:00");
        assertThat(site.confirmedButtons().get(2).session()).isEqualTo("s-2026-10-22-002");
        assertThat(site.confirmedSafe()).containsExactly("下一步");
    }

    @Test
    void platformGuessAndObservationsKeepConfirmations() {
        TestStorage storage = TestStorage.create(root);
        SiteNotes notes = new SiteNotes(storage.layout, storage.clock);
        notes.recordButton(HOST, "Next", Verdict.SAFE, JOB);

        notes.notePlatform(HOST, "自建站（猜测）");
        notes.appendObservation(HOST, "进度条有 4 步，\n第 2 步是教育经历");
        notes.appendObservation(HOST, "附件栏一条只能挂一个文件");

        SiteNotes.Site site = notes.read(HOST).orElseThrow();
        assertThat(site.platform()).isEqualTo("自建站（猜测）");
        assertThat(site.confirmedButtons()).hasSize(1);
        assertThat(site.notes()).isEqualTo("""

                # 观察记录

                - 2026-10-22T10:00:00-04:00 进度条有 4 步， 第 2 步是教育经历
                - 2026-10-22T10:00:00-04:00 附件栏一条只能挂一个文件
                """);
    }

    @Test
    void refusesToRecordHardBoundaryButtonAsSafe() {
        TestStorage storage = TestStorage.create(root);
        SiteNotes notes = new SiteNotes(storage.layout, storage.clock);

        assertThatThrownBy(() -> notes.recordButton(HOST, "保存并提交", Verdict.SAFE, JOB))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("硬边界");
        assertThatThrownBy(() -> notes.recordButton(HOST, "Apply now", Verdict.SAFE, JOB))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(notes.read(HOST)).isEmpty();

        notes.recordButton(HOST, "提交", Verdict.UNSAFE, JOB);
        assertThat(notes.confirmedSafe(HOST)).isEmpty();
    }

    @Test
    void handEditedSafeRecordCannotReleaseHardButton() {
        TestStorage storage = TestStorage.create(root);
        storage.write(storage.layout.playbookSite(HOST), """
                ---
                host: jobs.example.com
                platform: ''
                confirmed_buttons:
                  - name: 提交
                    verdict: safe
                    at: '2026-10-22T10:00:00-04:00'
                    session: 2026-10-22-001
                  - name: 继续
                    verdict: safe
                    at: '2026-10-22T10:00:00-04:00'
                    session: 2026-10-22-001
                ---
                """);
        SiteNotes notes = new SiteNotes(storage.layout, storage.clock);

        Set<String> safe = notes.confirmedSafe(HOST);

        assertThat(safe).containsExactly("继续");
    }

    @Test
    void rejectsBadInputAndMalformedFiles() {
        TestStorage storage = TestStorage.create(root);
        SiteNotes notes = new SiteNotes(storage.layout, storage.clock);

        assertThatThrownBy(() -> notes.recordButton(HOST, "下一\n步", Verdict.SAFE, JOB))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> notes.recordButton(HOST, "下一步", Verdict.SAFE, "not-an-id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> notes.recordButton("../etc", "下一步", Verdict.SAFE, JOB))
                .isInstanceOf(IllegalArgumentException.class);

        storage.write(storage.layout.playbookSite(HOST), """
                ---
                host: jobs.example.com
                confirmed_buttons:
                  - name: 下一步
                    verdict: maybe
                ---
                """);
        assertThatThrownBy(() -> notes.confirmedSafe(HOST)).isInstanceOf(StorageFormatException.class)
                .hasMessageContaining("verdict");
        assertThatThrownBy(() -> notes.recordButton(HOST, "保存", Verdict.SAFE, JOB))
                .isInstanceOf(StorageFormatException.class);
    }
}
