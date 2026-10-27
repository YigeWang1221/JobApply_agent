package jobapply.api;

import java.util.List;
import java.util.Map;
import jobapply.domain.AgentRun;
import jobapply.tools.BrowserException;
import jobapply.tools.BrowserSession;
import jobapply.workflow.ApplicationException;
import jobapply.workflow.ApplicationRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 把一个标签页交给 Agent（C006-fix-browser 第 2、3 节）。提示词要求 Agent 只碰自己的标签页和用户明确交给它的；这里是
 * 用户在工作台上明确转交的入口。
 * <ul>
 *   <li>{@code GET /api/browser/tabs}：Chrome 里现在不在任何使用者名下的标签页（序号、标题、网址），<b>只给界面看</b>，不进任何工具结果；</li>
 *   <li>{@code POST /api/jobs/{id}/application/tab} {@code {index, url}}：交给这个申请。再核对一次序号上还是这个网址、
 *       别的使用者没在用；不是时 409，申请已经结束（{@code done}）也是 409。没有申请线程 404，浏览器断开或卡住 503（列表也是）。成功后发 {@code changed}，模型下一次运行（或者正在跑时
 *       下一次浏览器工具的结果）里会看到「用户把标签页 N（标题、网址）交给你了」。</li>
 * </ul>
 */
@RestController
public class BrowserTabsController {

    /** @param url 界面列表里看到的网址（核对标签页没变） */
    public record HandoverRequest(Integer index, String url) {
    }

    public record TabView(int index, String title, String url) {
    }

    private final BrowserSession browser;
    private final ApplicationRunner runner;

    public BrowserTabsController(BrowserSession browser, ApplicationRunner runner) {
        this.browser = browser;
        this.runner = runner;
    }

    @GetMapping("/api/browser/tabs")
    public Map<String, List<TabView>> unowned() {
        return Map.of("tabs", browser.unowned().stream().map(BrowserTabsController::view).toList());
    }

    @PostMapping(path = "/api/jobs/{jobId}/application/tab", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TabView handOver(@PathVariable String jobId, @RequestBody HandoverRequest request) {
        if (request.index() == null || request.index() < 0) {
            throw new IllegalArgumentException("缺少 index（标签页序号）");
        }
        if (request.url() == null || request.url().isBlank()) {
            throw new IllegalArgumentException("缺少 url（列表里看到的网址）");
        }
        // 没有申请线程时 404；用户已经定下了（已提交、已投递、结束了、下线）的申请不再接着做，也不收标签页。
        // 失败、中断的回复后能接着做，照旧能转交
        if (AgentRun.DONE.equals(runner.status(jobId).status())) {
            throw new ApplicationException.Conflict("这个申请已经结束了，不能再把标签页交给它");
        }
        TabView handed = view(browser.handOver(jobId, request.index(), request.url()));
        runner.events().changed(null, jobId);
        return handed;
    }

    @ExceptionHandler(BrowserSession.HandoverRefused.class)
    ResponseEntity<Map<String, String>> refused(BrowserSession.HandoverRefused e) {
        return ApiErrors.error(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(BrowserException.Disconnected.class)
    ResponseEntity<Map<String, String>> disconnected(BrowserException.Disconnected e) {
        // 原因已经在顶部的状态条里；这里只说先做什么
        return ApiErrors.error(HttpStatus.SERVICE_UNAVAILABLE, DISCONNECTED);
    }

    /** 浏览器断开时列表、转交的 503 原话（界面原样显示）。 */
    static final String DISCONNECTED = "浏览器断开了，先点页面顶部的「重新连接」";

    @ExceptionHandler(BrowserException.Stalled.class)
    ResponseEntity<Map<String, String>> stalled(BrowserException.Stalled e) {
        return ApiErrors.error(HttpStatus.SERVICE_UNAVAILABLE, STALLED);
    }

    /** 浏览器卡住时（C011-fix）列表、转交的 503 原话。 */
    static final String STALLED = "浏览器卡住了：先在 Chrome 里把没响应的标签页点一下或关掉，再点页面顶部的「重新连接」";

    private static TabView view(BrowserSession.TabInfo tab) {
        return new TabView(tab.index(), tab.title(), tab.url());
    }
}
