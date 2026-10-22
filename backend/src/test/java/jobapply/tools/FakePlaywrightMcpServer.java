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
 * 收到的每次调用和启动时看到的环境变量名（不含值）都追加到调用记录里。
 * 用法：{@code java -cp <classpath> jobapply.tools.FakePlaywrightMcpServer <场景文件> <调用记录>}
 */
public final class FakePlaywrightMcpServer {

    static final List<String> TOOLS = List.of(
            "browser_navigate", "browser_snapshot", "browser_tabs", "browser_click", "browser_type",
            "browser_fill_form", "browser_evaluate", "browser_run_code_unsafe", "browser_file_upload");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, Object> ANY_OBJECT = Map.of("type", "object");

    private final Path scenarioFile;
    private final Path callLog;
    private String currentUrl = "about:blank";

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
                        (exchange, request) -> fake.handle(request.name(), request.arguments()));
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

    private synchronized McpSchema.CallToolResult handle(String tool, Map<String, Object> arguments) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        record(Map.of("event", "call", "tool", tool, "args", args));
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
