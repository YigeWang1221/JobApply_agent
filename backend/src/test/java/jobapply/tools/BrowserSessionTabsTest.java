package jobapply.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link BrowserSession} 在好几个标签页之间认页（C006-fix-runtime-2 回归修正）：用户在申请那一页里操作、网址变了，
 * 不能把那页当成丢了；用户关掉前面的标签页后别人新开一页，不能删掉另一个使用者的记录。假浏览器的多标签页模式，
 * 测试在两次调用之间改标签页列表 = 用户在 Chrome 里操作。网址全部虚构，不访问网络。
 */
class BrowserSessionTabsTest {

    private static final String USER_TAB = "https://news.example.org/today";
    private static final String JOB = "2026-10-23-001";
    private static final String OTHER = "2026-10-23-002";

    @TempDir
    Path dir;

    @Test
    void theSameTabWithAChangedUrlOnTheSameSiteIsStillThisJobs() {
        String apply = "https://jobs.acme-demo.com/apply/42";
        String other = "https://jobs.acme-demo.com/apply/43";
        FakeBrowser fake = FakeBrowser.create(dir).tabs(0, USER_TAB);

        try (BrowserSession session = new BrowserSession(fake.launch())) {
            session.open(apply, JOB);
            session.open(other, OTHER);

            // 用户在 Job 的标签页（序号 1）里登录、点了下一步：网址变了，还在同一站点
            fake.tabs(1, USER_TAB, "https://jobs.acme-demo.com/apply/42/step-2?login=ok", other);
            assertThat(session.snapshot(JOB).url()).endsWith("/step-2?login=ok");
            assertThat(session.ownsTab(JOB)).isTrue();

            // 跳到了别的站点（停在第三方登录页）：认不出，不读那一页
            fake.tabs(1, USER_TAB, "https://login.idp-demo.net/sso", other);
            int before = fake.toolCalls().size();
            assertThatThrownBy(() -> session.snapshot(JOB)).isInstanceOf(BrowserSession.TabLost.class);
            assertThat(fake.toolCalls().subList(before, fake.toolCalls().size())).containsExactly("browser_tabs");
            assertThat(session.ownsTab(JOB)).isFalse();

            // 用户关掉了 Job 的标签页，另一个 Job 的（同一站点）挪到了序号 1：仍归另一个 Job，不被 Job 认走
            fake.tabs(1, USER_TAB, other);
            assertThat(session.snapshot(OTHER).url()).isEqualTo(other);
            assertThat(session.tabs(JOB)).noneMatch(tab -> tab.url().equals(other));
        }
    }

    @Test
    void openingANewTabKeepsAnotherOwnersRecordAfterTheUserClosedAnEarlierTab() {
        String a = "https://jobs.alpha-demo.com/a";
        String b = "https://jobs.beta-demo.com/b";
        String c = "https://jobs.gamma-demo.com/c";
        FakeBrowser fake = FakeBrowser.create(dir).tabs(0, USER_TAB);

        try (BrowserSession session = new BrowserSession(fake.launch())) {
            session.open(a, "A");
            session.open(b, "B");

            // 用户关掉了最前面的标签页：A 到了序号 0，B 到了序号 1；C 新开的一页是序号 2（B 原来记下的序号）
            fake.tabs(1, a, b);
            assertThat(session.open(c, "C").url()).isEqualTo(c);

            assertThat(session.ownsTab("B")).isTrue();
            assertThat(session.snapshot("B").url()).isEqualTo(b);
            assertThat(session.snapshot("A").url()).isEqualTo(a);
            assertThat(session.snapshot("C").url()).isEqualTo(c);
        }
    }

    @Test
    void resumingNeverReadsTheUsersOwnTabThatSlidIntoTheRecordedIndex() {
        String apply = "https://jobs.acme-demo.com/apply/42";
        String mail = "https://mail.private-demo.com/inbox";
        FakeBrowser fake = FakeBrowser.create(dir).tabs(0, USER_TAB);

        try (BrowserSession session = new BrowserSession(fake.launch())) {
            session.open(apply, JOB);

            // 用户关掉了 Job 的标签页，自己的邮箱占了同一个序号（个数没变）
            fake.tabs(1, USER_TAB, mail);
            int before = fake.toolCalls().size();
            assertThatThrownBy(() -> session.snapshot(JOB)).isInstanceOf(BrowserSession.TabLost.class);

            // 不认回、不切换、不取快照，邮箱也不出现在给模型的列表里（C006-fix-browser 第 2 节：连候选都不给）
            assertThat(session.tabs(JOB)).isEmpty();
            assertThatThrownBy(() -> session.select(JOB, 1)).isInstanceOf(IllegalArgumentException.class);
            assertThat(fake.toolCalls().subList(before, fake.toolCalls().size()))
                    .containsOnly("browser_tabs");
            assertThat(fake.calls("browser_tabs")).noneMatch(args -> "select".equals(args.get("action")));
            // 界面上用户能看到它、决定交不交
            assertThat(session.unowned()).extracting(BrowserSession.TabInfo::url).containsExactly(USER_TAB, mail);
        }
    }

    @Test
    void afterALoginDetourTheJobsOwnTabIsListedAgainWhenItComesBackToTheSameSite() {
        String apply = "https://jobs.acme-demo.com/apply/42";
        FakeBrowser fake = FakeBrowser.create(dir).tabs(0, USER_TAB);

        try (BrowserSession session = new BrowserSession(fake.launch())) {
            session.open(apply, JOB);

            // 停在第三方登录页时认不出，也不列给模型；登录完回到同一站点的下一步（个数没变）：还是它自己的页
            fake.tabs(1, USER_TAB, "https://login.idp-demo.net/sso");
            assertThatThrownBy(() -> session.snapshot(JOB)).isInstanceOf(BrowserSession.TabLost.class);
            assertThat(session.tabs(JOB)).isEmpty();
            fake.tabs(0, USER_TAB, "https://jobs.acme-demo.com/apply/42/step-2");
            assertThat(session.tabs(JOB)).extracting(BrowserSession.TabInfo::index).containsExactly(1);
            assertThat(session.select(JOB, 1).url()).endsWith("/step-2");
            assertThat(session.ownsTab(JOB)).isTrue();
        }
    }

    @Test
    void theUsersTabsAreNeverListedOrReadEvenOnTheSameSiteUntilHandedOver() {
        String apply = "https://jobs.acme-demo.com/apply/42";
        String usersOwn = "https://jobs.acme-demo.com/apply/99";
        FakeBrowser fake = FakeBrowser.create(dir).tabs(0, USER_TAB, usersOwn);

        try (BrowserSession session = new BrowserSession(fake.launch())) {
            // 还没新开、没人转交：不能读任何一页
            int before = fake.toolCalls().size();
            assertThatThrownBy(() -> session.snapshot(JOB)).isInstanceOf(BrowserSession.TabLost.class);
            assertThat(session.tabs(JOB)).isEmpty();
            assertThat(fake.toolCalls().subList(before, fake.toolCalls().size())).containsOnly("browser_tabs");

            session.open(apply, JOB);
            // 同一站点上用户自己开的那页也不列、不能选
            assertThat(session.tabs(JOB)).extracting(BrowserSession.TabInfo::url).containsExactly(apply);
            assertThatThrownBy(() -> session.select(JOB, 1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> session.select(JOB, 0)).isInstanceOf(IllegalArgumentException.class);
            assertThat(fake.calls("browser_tabs")).noneMatch(args -> "select".equals(args.get("action")));
            assertThat(fake.calls("browser_snapshot")).hasSize(1); // 只有新开那页的
            assertThat(session.unowned()).extracting(BrowserSession.TabInfo::url).containsExactly(USER_TAB, usersOwn);
        }
    }

    @Test
    void aHandedOverTabBelongsToTheJobAndCanBeUsed() {
        String apply = "https://jobs.acme-demo.com/apply/42";
        String usersOwn = "https://jobs.acme-demo.com/apply/42/step-3";
        FakeBrowser fake = FakeBrowser.create(dir).tabs(0, USER_TAB, usersOwn);

        try (BrowserSession session = new BrowserSession(fake.launch())) {
            // 应用重启后：Job 没有标签页，用户把自己开着的第 1 页交给它
            BrowserSession.TabInfo handed = session.handOver(JOB, 1, usersOwn);
            assertThat(handed.url()).isEqualTo(usersOwn);
            assertThat(session.ownsTab(JOB)).isTrue();
            assertThat(session.takeHandover(JOB)).isEqualTo(handed);
            assertThat(session.takeHandover(JOB)).isNull();
            assertThat(session.unowned()).extracting(BrowserSession.TabInfo::url).containsExactly(USER_TAB);

            assertThat(session.snapshot(JOB).url()).isEqualTo(usersOwn);
            assertThat(session.tabs(JOB)).extracting(BrowserSession.TabInfo::url).containsExactly(usersOwn);

            // Job 已经有正在用的页时再交一页：不偷换它脚下的页，记成它的别的页，由模型 select 过去
            assertThat(session.handOver(JOB, 0, USER_TAB).index()).isZero();
            assertThat(session.snapshot(JOB).url()).isEqualTo(usersOwn);
            assertThat(session.select(JOB, 0).url()).isEqualTo(USER_TAB);
            assertThat(session.snapshot(JOB).url()).isEqualTo(USER_TAB);
            assertThat(session.tabs(JOB)).hasSize(2);
            // 另一个使用者看不到交给 Job 的页，也不能被交走
            assertThat(session.tabs(OTHER)).isEmpty();
            assertThatThrownBy(() -> session.handOver(OTHER, 1, usersOwn))
                    .isInstanceOf(BrowserSession.HandoverRefused.class);
        }
    }

    @Test
    void handoverIsRefusedWhenTheTabChangedOrAlreadyHasAnOwner() {
        String apply = "https://jobs.acme-demo.com/apply/42";
        String other = "https://jobs.beta-demo.com/apply/7";
        FakeBrowser fake = FakeBrowser.create(dir).tabs(0, USER_TAB);

        try (BrowserSession session = new BrowserSession(fake.launch())) {
            session.open(apply, JOB);
            session.open(other, OTHER);

            // 别的申请的页、它自己的页：都有主人
            assertThatThrownBy(() -> session.handOver(JOB, 2, other)).isInstanceOf(BrowserSession.HandoverRefused.class);
            assertThatThrownBy(() -> session.handOver(JOB, 1, apply)).isInstanceOf(BrowserSession.HandoverRefused.class);
            // 界面上看到之后，用户在那一页换了网址 / 关掉了
            assertThatThrownBy(() -> session.handOver(JOB, 0, "https://news.example.org/yesterday"))
                    .isInstanceOf(BrowserSession.HandoverRefused.class);
            assertThatThrownBy(() -> session.handOver(JOB, 5, USER_TAB)).isInstanceOf(BrowserSession.HandoverRefused.class);

            assertThat(session.takeHandover(JOB)).isNull();
            assertThat(session.snapshot(OTHER).url()).isEqualTo(other);
            assertThat(session.snapshot(JOB).url()).isEqualTo(apply);
            assertThat(session.tabs(JOB)).extracting(BrowserSession.TabInfo::url).containsExactly(apply);
        }
    }

    @Test
    void closingATabNeverHandsAnotherApplicationsFormOnTheSameSiteToThisJob() {
        String a = "https://boards.ats-demo.io/acme/jobs/1";
        String b = "https://boards.ats-demo.io/beta/jobs/2";
        FakeBrowser fake = FakeBrowser.create(dir).tabs(0, USER_TAB, "https://news.example.org/other");

        try (BrowserSession session = new BrowserSession(fake.launch())) {
            session.open(a, "A");
            session.open(b, "B");

            // 用户关掉了 A 的标签页（序号 2），B 挪到了 2，B 那页的网址也变了（在里面登录了）
            fake.tabs(2, USER_TAB, "https://news.example.org/other", "https://boards.ats-demo.io/beta/jobs/2?login=ok");
            int before = fake.toolCalls().size();
            assertThatThrownBy(() -> session.snapshot("A")).isInstanceOf(BrowserSession.TabLost.class);

            // 下一次列表个数没变，也不能再把挪过来的 B 的页认成 A 的，也不列给 A
            assertThatThrownBy(() -> session.snapshot("A")).isInstanceOf(BrowserSession.TabLost.class);
            assertThat(session.tabs("A")).isEmpty();
            assertThat(session.ownsTab("A")).isFalse();
            assertThat(fake.toolCalls().subList(before, fake.toolCalls().size())).containsOnly("browser_tabs");
        }
    }
}
