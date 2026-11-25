package jobapply.harness.plugins;

import jobapply.domain.AgentRun;
import jobapply.harness.policies.SubmitGuard;
import jobapply.harness.runtime.Loop;
import jobapply.harness.runtime.LoopEvent;
import jobapply.tools.BrowserException;
import jobapply.tools.BrowserFillTools;
import jobapply.tools.Ending;
import jobapply.tools.PageSnapshot;
import jobapply.tools.Workbench;

/**
 * 申请 Mode 开始时的页面：系统先确认标签页是这个岗位的（不是就打开岗位链接），取一次快照，作为最新页面放进对话。
 * 工作台刚启动、还没选定简历时（C021）先不打开 Chrome，等模型第一次用浏览器工具时再开（{@link BrowserFillTools}）。
 * 浏览器断开时直接停下等用户；没回应时告诉模型由它再开；读不到页面时停下请用户看一下。
 */
public final class JobPagePlugin implements Plugin {

    private final Workbench bench;
    private final BrowserFillTools browser;

    public JobPagePlugin(Workbench bench, BrowserFillTools browser) {
        this.bench = bench;
        this.browser = browser;
    }

    @Override
    public void on(LoopEvent event, Loop loop) {
        if (!(event instanceof LoopEvent.Started) || browser.deferred()) {
            return;
        }
        PageSnapshot latest;
        try {
            latest = browser.start();
        } catch (BrowserException.Disconnected e) {
            loop.end(new Ending.Disconnected(e.getMessage()));
            return;
        } catch (BrowserException.Stalled e) {
            // 浏览器这次没回应：不停下，告诉模型，由它再打开或换做法（C011-fix）
            latest = null;
            loop.add(AgentRun.Turn.page("系统打开岗位页时浏览器没有回应（" + e.getMessage() + "）。用 browser_navigate 再打开一次"
                    + "岗位链接 " + bench.run().url() + "；一直没有回应时用 ask_user 请用户看一下 Chrome。"));
        } catch (BrowserException.ToolFailed e) {
            loop.end(new Ending.Waiting("读不到当前页面（" + e.getMessage() + "）。确认 Chrome 里这个页面开着，然后回复，我再试一次。"));
            return;
        }
        bench.setLatest(latest);
        if (latest != null) {
            loop.showPage(latest);
            SubmitGuard.pageWarning(latest).ifPresent(warning -> loop.add(AgentRun.Turn.user("系统提醒：" + warning)));
        }
    }
}
