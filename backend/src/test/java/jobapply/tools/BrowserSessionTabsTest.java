package jobapply.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link BrowserSession} 的标签页记账（C006-fix-browser 第 3 节，C011）：每个使用者自己的会话、正在用哪一页、记在它名下的页、
 * 转交。给模型的列表只列自己的页。假浏览器的多标签页模式，测试在两次调用之间改标签页列表 = 用户在 Chrome 里
 * 操作。网址全部虚构，不访问网络。
 */
class BrowserSessionTabsTest {

    private static final String USER_TAB = "https://news.example.org/today";
    private static final String JOB = "2026-10-23-001";
    private static final String OTHER = "2026-10-23-002";

    @Test
    void theSameTabWithAChangedUrlIsStillThisJobsAndAShiftedTabStaysWithItsOwner(@TempDir Path dir) {
        String apply = "https://jobs.acme-demo.com/apply/42";
        String other = "https://jobs.acme-demo.com/apply/43";
        FakeBrowser fake = FakeBrowser.create(dir).tabs(0, USER_TAB);

        try (BrowserSession session = new BrowserSession(fake.launch())) {
            session.open(apply, JOB);
            session.open(other, OTHER);

            // 用户在 Job 的标签页（序号 1）里登录、点了下一步：网址变了，序号还在
            fake.tabs(1, USER_TAB, "https://jobs.acme-demo.com/apply/42/step-2?login=ok", other);
            assertThat(session.snapshot(JOB).url()).endsWith("/step-2?login=ok");
            assertThat(session.ownsTab(JOB)).isTrue();

            // 用户关掉了 Job 的标签页，另一个 Job 的挪到了序号 1：按网址仍归另一个 Job；Job 认不出，交给模型
            fake.tabs(1, USER_TAB, other);
            assertThat(session.snapshot(OTHER).url()).isEqualTo(other);
            assertThatThrownBy(() -> session.snapshot(JOB)).isInstanceOf(BrowserSession.TabLost.class);
        }
    }

    @Test
    void openingANewTabKeepsAnotherOwnersRecordAfterTheUserClosedAnEarlierTab(@TempDir Path dir) {
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
    void listShowsOnlyThisJobsTabsAndTheUiSeesTheRest(@TempDir Path dir) {
        String apply = "https://jobs.acme-demo.com/apply/42";
        String usersOwn = "https://jobs.acme-demo.com/apply/99";
        FakeBrowser fake = FakeBrowser.create(dir).tabs(0, USER_TAB, usersOwn);

        try (BrowserSession session = new BrowserSession(fake.launch())) {
            // 还没有正在用的页：不碰浏览器，说明交给模型
            assertThatThrownBy(() -> session.snapshot(JOB)).isInstanceOf(BrowserSession.TabLost.class);
            assertThat(fake.toolCalls()).isEmpty();

            session.open(apply, JOB);
            // 给模型的列表只有它自己的页，序号是它自己的编号（C011：别人的页不给模型看）
            assertThat(session.tabs(JOB)).extracting(BrowserSession.TabInfo::url).containsExactly(apply);
            assertThat(session.tabs(JOB)).allMatch(BrowserSession.TabInfo::yours);
            assertThat(session.tabs(JOB)).filteredOn(BrowserSession.TabInfo::inUse)
                    .extracting(BrowserSession.TabInfo::index).containsExactly(0);
            assertThat(session.unowned()).extracting(BrowserSession.TabInfo::url).containsExactly(USER_TAB, usersOwn);
        }
    }

    @Test
    void aHandedOverTabBelongsToTheJobAndCanBeUsed(@TempDir Path dir) {
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

            // Job 已经有正在用的页时再交一页：不偷换它脚下的页，由模型按它自己的编号 select 过去
            assertThat(session.handOver(JOB, 0, USER_TAB).index()).isEqualTo(1);
            assertThat(session.snapshot(JOB).url()).isEqualTo(usersOwn);
            assertThat(session.select(JOB, 1).url()).isEqualTo(USER_TAB);
            assertThat(session.snapshot(JOB).url()).isEqualTo(USER_TAB);
            assertThat(session.tabs(JOB)).filteredOn(BrowserSession.TabInfo::yours).hasSize(2);
            assertThat(session.tabs(OTHER)).noneMatch(BrowserSession.TabInfo::yours);
            // Job 正在用的页不能再交给别人
            assertThatThrownBy(() -> session.handOver(OTHER, 0, USER_TAB))
                    .isInstanceOf(BrowserSession.HandoverRefused.class);
        }
    }

    @Test
    void handoverIsRefusedWhenTheTabChangedOrAnotherOwnerIsUsingIt(@TempDir Path dir) {
        String apply = "https://jobs.acme-demo.com/apply/42";
        String other = "https://jobs.beta-demo.com/apply/7";
        FakeBrowser fake = FakeBrowser.create(dir).tabs(0, USER_TAB);

        try (BrowserSession session = new BrowserSession(fake.launch())) {
            session.open(apply, JOB);
            session.open(other, OTHER);

            assertThatThrownBy(() -> session.handOver(JOB, 2, other)).isInstanceOf(BrowserSession.HandoverRefused.class);
            // 界面上看到之后，用户在那一页换了网址 / 关掉了
            assertThatThrownBy(() -> session.handOver(JOB, 0, "https://news.example.org/yesterday"))
                    .isInstanceOf(BrowserSession.HandoverRefused.class);
            assertThatThrownBy(() -> session.handOver(JOB, 5, USER_TAB)).isInstanceOf(BrowserSession.HandoverRefused.class);

            assertThat(session.takeHandover(JOB)).isNull();
            assertThat(session.snapshot(OTHER).url()).isEqualTo(other);
            assertThat(session.snapshot(JOB).url()).isEqualTo(apply);
            assertThat(session.tabs(JOB)).filteredOn(BrowserSession.TabInfo::yours)
                    .extracting(BrowserSession.TabInfo::url).containsExactly(apply);
        }
    }
}
