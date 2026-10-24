package jobapply.tools;

import static java.nio.charset.StandardCharsets.UTF_8;

import ch.qos.logback.classic.Level;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * 测试用的 Playwright MCP 服务端：用 MCP Java SDK 走真实的 stdio 协议，返回文本的格式模仿 @playwright/mcp 0.0.83。
 * 行为由场景文件（JSON，见 {@link FakeBrowser}）决定，每次调用前重新读取，测试可以在两次调用之间修改它。
 * 收到的每次调用、调用时客户端声明的 roots，以及启动时看到的环境变量名（不含值）都追加到调用记录里。
 * 用法：{@code java -cp <classpath> jobapply.tools.FakePlaywrightMcpServer <场景文件> <调用记录>}
 * <p>
 * 场景文件里有 {@code form} 键时进入表单模式：页面按表单脚本（{@code fixtures/forms/<name>.scenario.json}）变化，
 * 格式和行为见 {@link FakeFormScript}。没有时按 {@code pages}（网址 → 标题、快照）和 {@code redirects} 返回固定页面。
 */
public final class FakePlaywrightMcpServer {

    static final List<String> TOOLS = List.of(
            "browser_navigate", "browser_snapshot", "browser_tabs", "browser_click", "browser_type",
            "browser_fill_form", "browser_select_option", "browser_file_upload", "browser_press_key",
            "browser_wait_for", "browser_find", "browser_evaluate", "browser_run_code_unsafe");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, Object> ANY_OBJECT = Map.of("type", "object");

    private final Path scenarioFile;
    private final Path callLog;
    private String currentUrl = "about:blank";
    private final FakeFormScript form = new FakeFormScript();

    private FakePlaywrightMcpServer(Path scenarioFile, Path callLog) {
        this.scenarioFile = scenarioFile;
        this.callLog = callLog;
    }

    public static void main(String[] args) throws InterruptedException {
        // stdout 只留给协议；日志改到 stderr
        PrintStream protocol = System.out;
        System.setOut(System.err);
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.WARN);

        FakePlaywrightMcpServer fake = new FakePlaywrightMcpServer(Path.of(args[0]), Path.of(args[1]));
        fake.record(Map.of("event", "start", "pid", ProcessHandle.current().pid(),
                "env", new TreeSet<>(System.getenv().keySet())));

        List<String> omitted = fake.stringList("omitTools");
        var spec = McpServer.sync(new StdioServerTransportProvider(McpJsonDefaults.getMapper(), System.in, protocol))
                .serverInfo("fake-playwright", "0.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build());
        for (String name : TOOLS) {
            if (!omitted.contains(name)) {
                spec.toolCall(McpSchema.Tool.builder(name, ANY_OBJECT).build(),
                        (exchange, request) -> fake.handle(request.name(), request.arguments(), roots(exchange)));
            }
        }
        McpSyncServer server = spec.build();

        // 父进程（测试 JVM）退出后自己退出，不留孤儿进程
        ProcessHandle parent = ProcessHandle.current().parent().orElse(null);
        while (parent == null || parent.isAlive()) {
            Thread.sleep(500);
        }
        server.close();
        Runtime.getRuntime().halt(0);
    }

    private static List<String> roots(io.modelcontextprotocol.server.McpSyncServerExchange exchange) {
        try {
            return exchange.listRoots().roots().stream().map(McpSchema.Root::uri).toList();
        } catch (RuntimeException e) {
            return List.of("error: " + e.getMessage());
        }
    }

    private synchronized McpSchema.CallToolResult handle(String tool, Map<String, Object> arguments,
                                                         List<String> roots) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        record(Map.of("event", "call", "tool", tool, "args", args, "roots", roots));
        Map<String, Object> scenario = scenario();
        if (Boolean.TRUE.equals(scenario.get("exitOnNextCall"))) {
            Map<String, Object> next = new LinkedHashMap<>(scenario);
            next.put("exitOnNextCall", false);
            write(next);
            Runtime.getRuntime().halt(3);
        }
        if (scenario.get("callDelayMillis") instanceof Number delay && delay.longValue() > 0) {
            try {
                Thread.sleep(delay.longValue());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (scenario.get("toolError") instanceof String error && !error.isEmpty()) {
            return McpSchema.CallToolResult.builder().addTextContent("### Error\n" + error).isError(true).build();
        }
        if ("browser_find".equals(tool) && scenario.get("findText") instanceof String raw) {
            return text(raw);
        }
        if (scenario.get("form") instanceof Map<?, ?>) {
            return form.handle(tool, args, scenario);
        }
        if (scenario.get("tabs") instanceof List<?>) {
            return withTabs(tool, args, scenario);
        }
        return switch (tool) {
            case "browser_navigate" -> {
                String url = String.valueOf(args.get("url"));
                Object redirect = map(scenario.get("redirects")).get(url);
                currentUrl = redirect == null ? url : redirect.toString();
                yield text("### Ran Playwright code\n```js\nawait page.goto('" + url + "');\n```\n" + page(scenario));
            }
            case "browser_snapshot" -> text(page(scenario));
            case "browser_tabs" -> {
                if ("new".equals(args.get("action"))) {
                    String url = String.valueOf(args.get("url"));
                    Object redirect = map(scenario.get("redirects")).get(url);
                    currentUrl = redirect == null ? url : redirect.toString();
                }
                yield text("### Open tabs\n- 0: (current) [" + title(scenario) + "](" + currentUrl + ")\n"
                        + page(scenario));
            }
            default -> text("### Result\nok");
        };
    }

    /**
     * 多标签页模式（场景文件有 {@code tabs}：各标签页的网址，{@code currentTab}：当前序号，见 {@link FakeBrowser#tabs}）。
     * {@code browser_tabs new / select} 和 {@code browser_navigate} 改动后写回场景文件；测试在两次调用之间改这两个键，
     * 就是用户在 Chrome 里关标签页、在标签页里换了网址。
     */
    private McpSchema.CallToolResult withTabs(String tool, Map<String, Object> args, Map<String, Object> scenario) {
        List<String> urls = new java.util.ArrayList<>(((List<?>) scenario.get("tabs")).stream()
                .map(String::valueOf).toList());
        int current = scenario.get("currentTab") instanceof Number n ? n.intValue() : 0;
        boolean changed = false;
        if ("browser_tabs".equals(tool) && "new".equals(args.get("action"))) {
            String url = String.valueOf(args.get("url"));
            Object redirect = map(scenario.get("redirects")).get(url);
            urls.add(redirect == null ? url : redirect.toString());
            current = urls.size() - 1;
            changed = true;
        } else if ("browser_tabs".equals(tool) && "select".equals(args.get("action"))
                && args.get("index") instanceof Number index) {
            current = index.intValue();
            changed = true;
        } else if ("browser_navigate".equals(tool)) {
            urls.set(current, String.valueOf(args.get("url")));
            changed = true;
        }
        if (changed) {
            Map<String, Object> next = new LinkedHashMap<>(scenario);
            next.put("tabs", urls);
            next.put("currentTab", current);
            write(next);
        }
        currentUrl = urls.isEmpty() ? "about:blank" : urls.get(current);
        if ("browser_tabs".equals(tool)) {
            StringBuilder out = new StringBuilder("### Open tabs\n");
            for (int i = 0; i < urls.size(); i++) {
                Object title = map(map(scenario.get("pages")).get(urls.get(i))).get("title");
                out.append("- ").append(i).append(':').append(i == current ? " (current)" : "").append(" [")
                        .append(title == null ? "" : title).append("](").append(urls.get(i)).append(")\n");
            }
            return text(out + page(scenario));
        }
        if ("browser_snapshot".equals(tool) || "browser_navigate".equals(tool)) {
            return text(page(scenario));
        }
        return text("### Result\nok");
    }

    private String page(Map<String, Object> scenario) {
        Object snapshot = map(map(scenario.get("pages")).get(currentUrl)).get("snapshot");
        return "### Page\n- Page URL: " + currentUrl + "\n- Page Title: " + title(scenario) + "\n"
                + "### Snapshot\n```yaml\n" + (snapshot == null ? "- generic [ref=e1]" : snapshot) + "\n```";
    }

    private String title(Map<String, Object> scenario) {
        Object title = map(map(scenario.get("pages")).get(currentUrl)).get("title");
        return title == null ? "" : title.toString();
    }

    private static McpSchema.CallToolResult text(String text) {
        return McpSchema.CallToolResult.builder().addTextContent(text).build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private List<String> stringList(String key) {
        Object value = scenario().get(key);
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    private Map<String, Object> scenario() {
        return FakeBrowser.readJson(scenarioFile);
    }

    private void write(Map<String, Object> scenario) {
        FakeBrowser.writeJson(scenarioFile, scenario);
    }

    private void record(Map<String, Object> entry) {
        try {
            Files.writeString(callLog, JSON.writeValueAsString(entry) + "\n", UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
