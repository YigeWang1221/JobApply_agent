package jobapply.api;

import java.util.LinkedHashMap;
import java.util.Map;
import jobapply.tools.BrowserSession;
import jobapply.tools.BrowserState;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 浏览器连接状态（C006-fix-browser 第 1 节）：{@code GET /api/browser} 读状态，{@code POST /api/browser/reconnect}
 * 是界面顶部的「重新连接」。状态变化同时作为全局事件 {@code browser} 推送（{@link EventController}）。
 */
@RestController
public class BrowserController {

    private final BrowserSession browser;

    public BrowserController(BrowserSession browser) {
        this.browser = browser;
    }

    @GetMapping("/api/browser")
    public Map<String, Object> state() {
        return view(browser.state());
    }

    /** 连着就不动；否则试一次。连不上时也是 200，状态里写着原因。 */
    @PostMapping("/api/browser/reconnect")
    public Map<String, Object> reconnect() {
        return view(browser.reconnect());
    }

    /** {@code {state, reason, since, generation}}；接口和事件流共用。 */
    static Map<String, Object> view(BrowserState state) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", state.state());
        out.put("reason", state.reason());
        out.put("since", state.since() == null ? null : state.since().toString());
        out.put("generation", state.generation());
        return out;
    }
}
