package jobapply.harness.policies;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * C003 的浏览器工具白名单：只能新开标签页打开页面、在页面间导航、取快照、列出标签页。规则写在代码里，不从配置或 Playbook 读取。
 * 不允许切换或关闭标签页：Agent 只操作它自己新开的标签页。
 * 点击、输入、填表、上传、按键、执行脚本等写操作一律拒绝；它们的守卫属于里程碑第 3 段。
 */
public final class BrowserToolPolicy {

    public static final String NAVIGATE = "browser_navigate";
    public static final String SNAPSHOT = "browser_snapshot";
    public static final String TABS = "browser_tabs";

    private static final Set<String> SNAPSHOT_ARGS = Set.of("target", "depth", "boxes");

    private BrowserToolPolicy() {
    }

    /**
     * 不允许时抛出 {@link ToolNotAllowedException}，调用方不得把这次调用发给 MCP 服务端。
     */
    public static void check(String tool, Map<String, Object> args) {
        Map<String, Object> arguments = args == null ? Map.of() : args;
        switch (tool == null ? "" : tool) {
            case NAVIGATE -> checkNavigate(arguments);
            case SNAPSHOT -> checkSnapshot(arguments);
            case TABS -> checkTabs(arguments);
            default -> throw new ToolNotAllowedException("工具 " + tool + " 不在本段白名单内");
        }
    }

    private static void checkNavigate(Map<String, Object> args) {
        onlyKeys(NAVIGATE, args, Set.of("url"));
        requireHttpUrl(NAVIGATE, args);
    }

    private static void checkSnapshot(Map<String, Object> args) {
        onlyKeys(SNAPSHOT, args, SNAPSHOT_ARGS);
    }

    private static void checkTabs(Map<String, Object> args) {
        Object action = args.get("action");
        if ("list".equals(action)) {
            onlyKeys(TABS, args, Set.of("action"));
        } else if ("new".equals(action)) {
            onlyKeys(TABS, args, Set.of("action", "url"));
            requireHttpUrl(TABS + " new", args);
        } else {
            throw new ToolNotAllowedException(TABS + " 只允许 action=list 或 action=new");
        }
    }

    private static void requireHttpUrl(String tool, Map<String, Object> args) {
        if (!(args.get("url") instanceof String url) || !isHttp(url)) {
            throw new ToolNotAllowedException(tool + " 只允许 http / https 地址");
        }
    }

    private static void onlyKeys(String tool, Map<String, Object> args, Set<String> allowed) {
        for (String key : args.keySet()) {
            if (!allowed.contains(key)) {
                throw new ToolNotAllowedException(tool + " 不允许参数 " + key);
            }
        }
    }

    private static boolean isHttp(String url) {
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
