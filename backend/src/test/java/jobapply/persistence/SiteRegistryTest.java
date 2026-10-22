package jobapply.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SiteRegistryTest {

    @TempDir
    Path root;

    @Test
    void addsARowPerOriginSortedAndCommits() {
        TestStorage storage = TestStorage.create(root, true);
        SiteRegistry sites = new SiteRegistry(storage.layout, storage.clock);

        sites.recordLogin("https://jobs.example.com", "user-1001", "matched");
        sites.recordLogin("https://careers.example.cn", "", "user_confirmed");

        assertThat(storage.read(storage.layout.browserSites())).isEqualTo("""
                # 站点登录登记

                | 站点 | 账号标识 | 最近确认登录 | 状态 | 核对方式 |
                | --- | --- | --- | --- | --- |
                | https://careers.example.cn | — | 2026-10-22T10:00:00-04:00 | logged_in | user_confirmed |
                | https://jobs.example.com | user-1001 | 2026-10-22T10:00:00-04:00 | logged_in | matched |
                """);
        assertThat(storage.headMessage()).isEqualTo("browser：确认登录 https://careers.example.cn");
        assertThat(storage.isClean()).isTrue();
    }

    @Test
    void sameOriginUpdatesInsteadOfAdding() {
        TestStorage storage = TestStorage.create(root, false);
        SiteRegistry sites = new SiteRegistry(storage.layout, storage.clock);
        sites.recordLogin("https://jobs.example.com", "user-1001", "matched");
        storage.clock.advance(Duration.ofHours(2));

        sites.recordLogin("https://jobs.example.com", "user-2002", "user_confirmed");

        List<Map<String, String>> rows = sites.rows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("账号标识", "user-2002")
                .containsEntry("最近确认登录", "2026-10-22T12:00:00-04:00")
                .containsEntry("核对方式", "user_confirmed");
    }

    @Test
    void keepsUnknownColumnsAndSurroundingText() {
        TestStorage storage = TestStorage.create(root, false);
        storage.write(storage.layout.browserSites(), """
                # 站点登录登记

                手工备注：第二个账号只用来投实习。

                | 站点 | 账号标识 | 备注 | 最近确认登录 | 状态 | 核对方式 |
                | --- | --- | --- | --- | --- | --- |
                | https://jobs.example.com | old | 主账号 | 2026-10-01T09:00:00-04:00 | logged_in | matched |

                更多说明。
                """);
        SiteRegistry sites = new SiteRegistry(storage.layout, storage.clock);

        sites.recordLogin("https://jobs.example.com", "user-1001", "matched");

        String content = storage.read(storage.layout.browserSites());
        assertThat(content).contains("手工备注：第二个账号只用来投实习。").contains("更多说明。");
        assertThat(content).contains("| 站点 | 账号标识 | 最近确认登录 | 状态 | 核对方式 | 备注 |");
        assertThat(sites.rows().get(0)).containsEntry("备注", "主账号").containsEntry("账号标识", "user-1001");
    }

    @Test
    void rejectsCellsThatWouldBreakTheTable() {
        TestStorage storage = TestStorage.create(root, false);
        SiteRegistry sites = new SiteRegistry(storage.layout, storage.clock);

        assertThatThrownBy(() -> sites.recordLogin("https://jobs.example.com", "a|b", "matched"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sites.recordLogin("https://jobs.example.com", "a\nb", "matched"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sites.recordLogin(" ", "a", "matched")).isInstanceOf(IllegalArgumentException.class);
        assertThat(storage.layout.browserSites()).doesNotExist();
    }
}
