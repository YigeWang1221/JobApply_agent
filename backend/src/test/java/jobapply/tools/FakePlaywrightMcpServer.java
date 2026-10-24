package jobapply.tools;

import static java.nio.charset.StandardCharsets.UTF_8;

import ch.qos.logback.classic.Level;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
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
 * 测试用的 Playwright MCP 服务端：用 MCP Java SDK 走真实的 Streamable HTTP 协议（Tomcat 只听 127.0.0.1，和 --port --host 127.0.0.1 一样），每个会话自己的当前标签页，返回文本的格式模仿 @playwright/mcp 0.0.83。
 * 行为由场景文件（JSON，见 {@link FakeBrowser}）决定，每次调用前重新读取，测试可以在两次调用之间修改它。
 * 收到的每次调用、调用时客户端声明的 roots，以及启动时看到的环境变量名（不含值）都追加到调用记录里。
 * 用法：{@code java -cp <classpath> jobapply.tools.FakePlaywrightMcpServer <场景文件> <调用记录> --port <端口>}
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

    public static void main(String[] args) throws Exception {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.WARN);

        FakePlaywrightMcpServer fake = new FakePlaywrightMcpServer(Path.of(args[0]), Path.of(args[1]));
        int port = -1;
        for (int i = 2; i + 1 < args.length; i++) {
            if ("--port".equals(args[i])) {
                port = Integer.parseInt(args[i + 1]);
            }
        }
        fake.record(Map.of("event", "start", "pid", ProcessHandle.current().pid(),
                "env", new TreeSet<>(System.getenv().keySet()), "args", List.of(args)));

        // 和 @playwright/mcp --port --host 127.0.0.1 一样：Streamable HTTP，只听本机回环地址，每个会话各自的当前标签页
        HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(McpJsonDefaults.getMapper()).mcpEndpoint("/mcp").build();
        List<String> omitted = fake.stringList("omitTools");
        var spec = McpServer.sync(transport)
                .serverInfo("fake-playwright", "0.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build());
        for (String name : TOOLS) {
            if (!omitted.contains(name)) {
                spec.toolCall(McpSchema.Tool.builder(name, ANY_OBJECT).build(),
                        (exchange, request) -> fake.handle(exchange.sessionId(), request.name(), request.arguments(),
                                roots(exchange)));
            }
        }
        McpSyncServer server = spec.build();
        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(Files.createTempDirectory("fake-mcp-tomcat").toString());
        Connector connector = new Connector();
        connector.setPort(port);
        connector.setProperty("address", "127.0.0.1");
        tomcat.setConnector(connector);
        Context context = tomcat.addContext("", null);
        Tomcat.addServlet(context, "mcp", transport).setAsyncSupported(true);
        context.addServletMappingDecoded("/mcp", "mcp");
        tomcat.start();

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

    /**
     * 一次工具调用。等待（{@code callDelayMillis}）不在锁里，不同会话的调用可以同时进行；改页面状态在锁里。
     * 调用开始时记一条 {@code call}，结束时记一条 {@code done}（会话、开始 / 结束时间（微秒）、调用时这个会话的当前网址）。
     */
    private McpSchema.CallToolResult handle(String session, String tool, Map<String, Object> arguments,
                                            List<String> roots) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        long started = micros();
        record(Map.of("event", "call", "tool", tool, "args", args, "roots", roots, "session", session));
        String[] url = {""};
        McpSchema.CallToolResult result = handle(session, tool, args, url);
        record(Map.of("event", "done", "tool", tool, "session", session, "url", url[0], "started", started,
                "ended", micros()));
        return result;
    }

    private static long micros() {
        java.time.Instant now = java.time.Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000;
    }

    private McpSchema.CallToolResult handle(String session, String tool, Map<String, Object> args, String[] url) {
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
        synchronized (this) {
            McpSchema.CallToolResult result = dispatch(session, tool, args);
            url[0] = currentUrl;
            return result;
        }
    }

    private McpSchema.CallToolResult dispatch(String session, String tool, Map<String, Object> args) {
        // 场景文件在锁里重新读一次：别的会话刚写回的标签页变化要看得到
        Map<String, Object> scenario = scenario();
        if (scenario.get("form") instanceof Map<?, ?>) {
            return form.handle(tool, args, scenario);
        }
        if (scenario.get("tabs") instanceof List<?>) {
            return withTabs(session, tool, args, scenario);
        }
        // 单页模式也按会话记当前页（C011 合并后的竞争问题）：几个会话同时 browser_tabs new 时各自看到自己开的页，
        // 不会读到别人刚开的页。新会话从最近一次调用时的当前页开始（只有一个使用者的测试和以前一样）。
        currentUrl = singleUrls.getOrDefault(session, currentUrl);
        McpSchema.CallToolResult result = single(tool, args, scenario);
        singleUrls.put(session, currentUrl);
        return result;
    }

    /** 单页模式（没有 {@code tabs}）：会话 → 它的当前网址。 */
    private final Map<String, String> singleUrls = new java.util.HashMap<>();

    private McpSchema.CallToolResult single(String tool, Map<String, Object> args, Map<String, Object> scenario) {
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
            case "browser_run_code_unsafe" -> text(String.valueOf(args.get("code")).contains("opener()")
                    ? "### Result\n[]" : "### Result\nok");
            default -> text("### Result\nok");
        };
    }

    /** 一个会话的当前标签页：序号和那时的网址。 */
    private record Current(int index, String url) {
    }

    /** 会话 → 它的当前标签页（和 @playwright/mcp 一样，每个会话一个）。 */
    private final Map<String, Current> currents = new java.util.HashMap<>();

    /**
     * 这个会话现在的当前页：序号和网址都对得上就是它；网址在别的序号上（前面的页被关掉、序号挪了）就跟过去；都不在时
     * 留在原来的序号上（网址变了 = 同一页换了网址；原来的页被关掉 = MCP 换到旁边那一页）。新会话的当前页是第一页。
     */
    private static int resolve(Current current, List<String> urls) {
        if (urls.isEmpty()) {
            return -1;
        }
        if (current == null) {
            return 0;
        }
        if (current.index() < urls.size() && urls.get(current.index()).equals(current.url())) {
            return current.index();
        }
        int moved = urls.indexOf(current.url());
        return moved >= 0 ? moved : Math.min(current.index(), urls.size() - 1);
    }

    /**
     * 多标签页模式（场景文件有 {@code tabs}：浏览器里各标签页的网址，见 {@link FakeBrowser#tabs}）。标签页是共享的，
     * 每个会话有自己的当前页（{@link #currents}）。{@code browser_tabs new} 和 {@code browser_navigate} 改动后写回
     * 场景文件；测试在两次调用之间改 {@code tabs}，就是用户在 Chrome 里关标签页、在标签页里换了网址。
     */
    private McpSchema.CallToolResult withTabs(String session, String tool, Map<String, Object> args,
                                              Map<String, Object> scenario) {
        List<String> urls = new java.util.ArrayList<>(((List<?>) scenario.get("tabs")).stream()
                .map(String::valueOf).toList());
        int current = resolve(currents.get(session), urls);
        boolean changed = false;
        if ("browser_tabs".equals(tool) && "new".equals(args.get("action"))) {
            String url = String.valueOf(args.get("url"));
            Object redirect = map(scenario.get("redirects")).get(url);
            urls.add(redirect == null ? url : redirect.toString());
            current = urls.size() - 1;
            changed = true;
        } else if ("browser_tabs".equals(tool) && "select".equals(args.get("action"))
                && args.get("index") instanceof Number index) {
            if (index.intValue() < 0 || index.intValue() >= urls.size()) {
                return McpSchema.CallToolResult.builder().addTextContent("### Error\nError: Tab " + index + " not found")
                        .isError(true).build();
            }
            current = index.intValue();
        } else if ("browser_navigate".equals(tool) && current >= 0) {
            urls.set(current, String.valueOf(args.get("url")));
            changed = true;
        }
        if (changed) {
            Map<String, Object> next = new LinkedHashMap<>(scenario);
            next.put("tabs", urls);
            write(next);
        }
        currentUrl = current < 0 ? "about:blank" : urls.get(current);
        if (current >= 0) {
            currents.put(session, new Current(current, currentUrl));
        }
        if ("browser_run_code_unsafe".equals(tool) && String.valueOf(args.get("code")).contains("opener()")) {
            return text("### Result\n[]");
        }
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

    private synchronized void record(Map<String, Object> entry) {
        try {
            Files.writeString(callLog, JSON.writeValueAsString(entry) + "\n", UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
