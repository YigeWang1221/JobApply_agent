package jobapply.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
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
    void listShowsEveryTabAndMarksThisJobsOwn(@TempDir Path dir) {
        String apply = "https://jobs.acme-demo.com/apply/42";
        String usersOwn = "https://jobs.acme-demo.com/apply/99";
        FakeBrowser fake = FakeBrowser.create(dir).tabs(0, USER_TAB, usersOwn);

        try (BrowserSession session = new BrowserSession(fake.launch())) {
            // 还没有正在用的页：不碰浏览器，说明交给模型
            assertThatThrownBy(() -> session.snapshot(JOB)).isInstanceOf(BrowserSession.TabLost.class);
            assertThat(fake.toolCalls()).isEmpty();

            session.open(apply, JOB);
            // 给模型的列表是 Chrome 里所有的页（浏览器里的序号），标出它自己的；用哪一页由提示词约束
            assertThat(session.tabs(JOB)).extracting(BrowserSession.TabInfo::url).containsExactly(USER_TAB, usersOwn, apply);
            assertThat(session.tabs(JOB)).filteredOn(BrowserSession.TabInfo::yours)
                    .extracting(BrowserSession.TabInfo::url).containsExactly(apply);
            assertThat(session.tabs(JOB)).filteredOn(BrowserSession.TabInfo::inUse)
                    .extracting(BrowserSession.TabInfo::index).containsExactly(2);
            assertThat(session.tabs(OTHER)).filteredOn(BrowserSession.TabInfo::others)
                    .extracting(BrowserSession.TabInfo::url).containsExactly(apply);
            // 用户自己开着的页也能切过去，切过去后记在名下
            assertThat(session.select(JOB, 1).url()).isEqualTo(usersOwn);
            assertThat(session.tabs(JOB)).filteredOn(BrowserSession.TabInfo::yours)
                    .extracting(BrowserSession.TabInfo::url).containsExactly(usersOwn, apply);
            assertThat(session.unowned()).extracting(BrowserSession.TabInfo::url).containsExactly(USER_TAB);
        }
    }

    /**
     * 停下时只关自己开的页（C011-fix 2026-10-24）：停在提交前留下填好的那页给用户审阅，用户转交的页、用户自己的页从不关；
     * 彻底结束时关掉自己开的所有页。关错了就是用户的表单或用户自己的页没了。
     */
    @Test
    void stoppingClosesOnlyTabsTheOwnerOpenedAndKeepsTheFormForReview(@TempDir Path dir) {
        String extra = "https://jobs.acme-demo.com/help";
        String apply = "https://jobs.acme-demo.com/apply/42";
        String usersOwn = "https://jobs.acme-demo.com/apply/99";
        FakeBrowser fake = FakeBrowser.create(dir).tabs(0, USER_TAB, usersOwn);

        try (BrowserSession session = new BrowserSession(fake.launch())) {
            session.open(extra, JOB);
            session.open(apply, JOB);
            session.handOver(JOB, 1, usersOwn);

            // 停在提交前：多开的那页关掉，正在用的表单、转交的页、用户自己的页都留着；焦点模拟关掉
            session.leave(JOB, BrowserSession.Leave.KEEP_CURRENT);
            assertThat(FakeBrowser.readJson(fake.scenarioFile).get("tabs")).isEqualTo(List.of(USER_TAB, usersOwn, apply));
            assertThat(fake.calls("browser_run_code_unsafe"))
                    .anySatisfy(args -> assertThat(String.valueOf(args.get("code"))).contains("enabled: false"));

            // 接着做（用户要求修改）：新会话里按网址找回那一页
            assertThat(session.ownsTab(JOB)).isTrue();
            assertThat(session.snapshot(JOB).url()).isEqualTo(apply);

            // 彻底结束（已投递）：自己开的都关掉，转交的、用户自己的不关
            session.release(JOB);
            assertThat(FakeBrowser.readJson(fake.scenarioFile).get("tabs")).isEqualTo(List.of(USER_TAB, usersOwn));
            assertThat(session.ownsTab(JOB)).isFalse();
        }
    }

    @Test
    void unownedListForTheUi(@TempDir Path dir) {
        String apply = "https://jobs.acme-demo.com/apply/42";
        String usersOwn = "https://jobs.acme-demo.com/apply/99";
        FakeBrowser fake = FakeBrowser.create(dir).tabs(0, USER_TAB, usersOwn);

        try (BrowserSession session = new BrowserSession(fake.launch())) {
            session.open(apply, JOB);
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

            // Job 已经有正在用的页时再交一页：不偷换它脚下的页，由模型按浏览器里的序号 select 过去
            assertThat(session.handOver(JOB, 0, USER_TAB).index()).isEqualTo(0);
            assertThat(session.snapshot(JOB).url()).isEqualTo(usersOwn);
            assertThat(session.select(JOB, 0).url()).isEqualTo(USER_TAB);
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
