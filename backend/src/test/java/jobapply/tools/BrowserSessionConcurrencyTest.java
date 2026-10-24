package jobapply.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import jobapply.harness.policies.BrowserToolPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * C011 方案 A：两个使用者（两个申请线程）在同一条共享连接上<b>同时</b>操作浏览器，各自的会话、各自的标签页。证明两点：
 * 调用的时间区间真的重叠（不是一个等另一个），每次调用都落在自己的页上。假服务端在每次调用里等 300 毫秒，按会话记下开始 /
 * 结束时间和那一刻会话的当前网址。网址全部虚构，不访问网络。
 */
class BrowserSessionConcurrencyTest {

    private static final String USER_TAB = "https://news.example.org/today";
    private static final String ALPHA = "https://jobs.alpha-demo.com/apply/1";
    private static final String BETA = "https://jobs.beta-demo.com/apply/2";
    private static final String FORM = """
            - heading "%s" [level=1] [ref=e1]
            - textbox "姓名" [ref=e2]""";

    @Test
    void twoOwnersActAtTheSameTimeEachOnItsOwnPage(@TempDir Path dir) throws Exception {
        FakeBrowser fake = FakeBrowser.create(dir)
                .page(ALPHA, "Alpha 申请", FORM.formatted("Alpha"))
                .page(BETA, "Beta 申请", FORM.formatted("Beta"))
                .tabs(0, USER_TAB)
                // 每次调用等 300 毫秒（在开页之前设：场景文件整体写回，开页之后再改会盖掉服务端记下的标签页）
                .callDelay(Duration.ofMillis(300));

        try (BrowserSession session = new BrowserSession(fake.launch(Duration.ofSeconds(10)));
             ExecutorService pool = Executors.newFixedThreadPool(2)) {
            PageSnapshot alpha = session.open(ALPHA, "A");
            PageSnapshot beta = session.open(BETA, "B");

            CyclicBarrier start = new CyclicBarrier(2);
            Future<List<PageSnapshot>> a = pool.submit(() -> work(session, start, "A", alpha, "示例甲"));
            Future<List<PageSnapshot>> b = pool.submit(() -> work(session, start, "B", beta, "示例乙"));
            List<PageSnapshot> seenByA = a.get();
            List<PageSnapshot> seenByB = b.get();

            assertThat(seenByA).extracting(PageSnapshot::url).containsOnly(ALPHA);
            assertThat(seenByB).extracting(PageSnapshot::url).containsOnly(BETA);
        }

        List<FakeBrowser.Done> typed = fake.done().stream().filter(d -> "browser_type".equals(d.tool())).toList();
        assertThat(typed).extracting(FakeBrowser.Done::url).containsExactlyInAnyOrder(ALPHA, BETA);
        FakeBrowser.Done first = typed.get(0);
        FakeBrowser.Done second = typed.get(1);
        System.out.printf("两次填写（微秒）：%s %d–%d；%s %d–%d%n", first.url(), first.started(), first.ended(),
                second.url(), second.started(), second.ended());
        assertThat(first.session()).isNotEqualTo(second.session());
        assertThat(Math.max(first.started(), second.started()))
                .as("两次填写的时间区间重叠：一个还没做完另一个已经开始")
                .isLessThan(Math.min(first.ended(), second.ended()));
        for (FakeBrowser.Done mine : typed) {
            // 这个会话从开页起的每一次调用都落在它自己的页上（没有切到别人的页、也没有 select 过）
            assertThat(fake.done()).filteredOn(d -> d.session().equals(mine.session()))
                    .extracting(FakeBrowser.Done::url).containsOnly(mine.url());
        }
        assertThat(fake.calls("browser_tabs")).noneMatch(args -> "select".equals(args.get("action")));
    }

    /** 一个使用者：和另一个同时开始，填一个字段、取一次快照。 */
    private static List<PageSnapshot> work(BrowserSession session, CyclicBarrier start, String owner,
                                           PageSnapshot page, String name) throws Exception {
        start.await();
        BrowserToolPolicy.Context context = BrowserToolPolicy.Context.model(SnapshotIndex.of(page),
                List.of(page.url()), null);
        PageSnapshot typed = session.act(owner, BrowserToolPolicy.TYPE,
                Map.of("target", "e2", "element", "姓名", "text", name), context);
        PageSnapshot again = session.snapshot(owner);
        return List.of(typed, again);
    }
}
