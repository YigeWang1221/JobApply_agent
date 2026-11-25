package jobapply.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SiteNotesTest {

    private static final String HOST = "jobs.example.com";

    @TempDir
    Path root;

    @Test
    void platformGuessAndObservationsWriteAndReadSite() {
        TestStorage storage = TestStorage.create(root);
        SiteNotes notes = new SiteNotes(storage.layout, storage.clock);

        notes.notePlatform(HOST, "自建站（猜测）");
        notes.appendObservation(HOST, "进度条有 4 步，\n第 2 步是教育经历");
        notes.appendObservation(HOST, "附件栏一条只能挂一个文件");

        SiteNotes.Site site = notes.read(HOST).orElseThrow();
        assertThat(site.host()).isEqualTo(HOST);
        assertThat(site.platform()).isEqualTo("自建站（猜测）");
        assertThat(site.notes()).isEqualTo("""

                # 观察记录

                - 2026-10-22T10:00:00-04:00 进度条有 4 步， 第 2 步是教育经历
                - 2026-10-22T10:00:00-04:00 附件栏一条只能挂一个文件
                """);
    }

    @Test
    void legacySiteFileWithConfirmedButtonsLoadsCleanly() {
        TestStorage storage = TestStorage.create(root);
        storage.write(storage.layout.playbookSite(HOST), """
                ---
                host: jobs.example.com
                platform: 某招聘平台
                confirmed_buttons:
                  - name: 下一步
                    verdict: safe
                    at: '2026-10-22T10:00:00-04:00'
                    session: 2026-10-22-001
                ---

                # 观察记录

                - 2026-10-22T10:00:00-04:00 旧记录
                """);
        SiteNotes notes = new SiteNotes(storage.layout, storage.clock);

        SiteNotes.Site site = notes.read(HOST).orElseThrow();
        assertThat(site.host()).isEqualTo(HOST);
        assertThat(site.platform()).isEqualTo("某招聘平台");
        assertThat(site.notes()).contains("旧记录");
    }
}
