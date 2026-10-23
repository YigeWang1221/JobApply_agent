package jobapply.tools;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import jobapply.harness.policies.BrowserToolPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一条到 Playwright MCP 子进程的 stdio 连接。每次调用先过 {@link BrowserToolPolicy}，被拒绝的调用不会发出。
 * 子进程用 {@code env -i PATH=… HOME=…} 启动：MCP SDK 的 stdio 传输默认会传 HOME、LOGNAME、PATH、SHELL、TERM、USER，
 * 这里只留 PATH 和 HOME。日志只记工具名和耗时，不记返回内容（快照里可能有个人信息）。
 */
public final class PlaywrightMcpClient implements AutoCloseable {

    static final List<String> REQUIRED_TOOLS = List.of(
            BrowserToolPolicy.NAVIGATE, BrowserToolPolicy.SNAPSHOT, BrowserToolPolicy.TABS, BrowserToolPolicy.CLICK,
            BrowserToolPolicy.TYPE, BrowserToolPolicy.FILL_FORM, BrowserToolPolicy.SELECT_OPTION,
            BrowserToolPolicy.FILE_UPLOAD, BrowserToolPolicy.PRESS_KEY, BrowserToolPolicy.WAIT_FOR,
            BrowserToolPolicy.FIND);

    /**
     * 连接失败或连接已断开时，@playwright/mcp 返回的错误里会出现的片段（0.0.83 源码）。
     * {@code initializeServer}：远程调试模式下连不上 Chrome（「Could not connect to chrome」，或用户 30 秒内没有同意时的
     * 「Timeout 30000ms exceeded」），2026-10-23 冒烟时观察到。
     */
    private static final List<String> CONNECTION_ERRORS = List.of(
            "did not connect within", "extension connection", "has been closed", "browser has disconnected",
            "target closed", "not connected", "could not connect to chrome", "initializeserver");

    private static final Logger log = LoggerFactory.getLogger(PlaywrightMcpClient.class);

    private final McpSyncClient client;

    private PlaywrightMcpClient(McpSyncClient client) {
        this.client = client;
    }

    /**
     * 启动子进程并完成 MCP 初始化（声明 {@link BrowserLaunch#roots()}），检查必需的工具都在。失败时子进程已关闭。
     */
    public static PlaywrightMcpClient start(BrowserLaunch launch) {
        List<String> args = new ArrayList<>();
        args.add("-i");
        args.add("PATH=" + launch.path());
        args.add("HOME=" + launch.home());
        args.add(launch.command());
        args.addAll(launch.args());
        log.info("启动浏览器 MCP：{} {}", launch.command(), String.join(" ", launch.args()));
        StdioClientTransport transport = new StdioClientTransport(
                ServerParameters.builder("/usr/bin/env").args(args).build(), McpJsonDefaults.getMapper());
        transport.setStdErrorHandler(line -> log.debug("浏览器 MCP stderr：{}", line));
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(launch.callTimeout())
                .initializationTimeout(launch.callTimeout())
                .clientInfo(new McpSchema.Implementation("jobapply", "0.0.1"))
                .roots(launch.roots().stream()
                        .map(root -> new McpSchema.Root(root.toUri().toString(), root.getFileName().toString()))
                        .toList())
                .build();
        try {
            client.initialize();
            Set<String> tools = client.listTools().tools().stream()
                    .map(McpSchema.Tool::name)
                    .collect(Collectors.toSet());
            List<String> missing = REQUIRED_TOOLS.stream().filter(name -> !tools.contains(name)).toList();
            if (!missing.isEmpty()) {
                throw new BrowserException.ToolFailed("浏览器 MCP 缺少工具：" + String.join(", ", missing));
            }
        } catch (BrowserException.ToolFailed e) {
            closeQuietly(client);
            throw e;
        } catch (RuntimeException e) {
            closeQuietly(client);
            throw new BrowserException.Disconnected("浏览器 MCP 启动失败：" + e.getMessage(), e);
        }
        return new PlaywrightMcpClient(client);
    }

    /**
     * 系统自己的调用，返回文本内容。不在白名单内时抛出 {@link jobapply.harness.policies.ToolNotAllowedException}。
     */
    public String call(String tool, Map<String, Object> args) {
        return call(tool, args, BrowserToolPolicy.Context.system());
    }

    /**
     * 按给定的上下文（系统或模型）检查白名单，再调用。点击和填写的守卫由调用方先判断，这里只管白名单。
     */
    public String call(String tool, Map<String, Object> args, BrowserToolPolicy.Context context) {
        Map<String, Object> arguments = args == null ? Map.of() : args;
        BrowserToolPolicy.check(tool, arguments, context);
        long started = System.nanoTime();
        McpSchema.CallToolResult result;
        try {
            result = client.callTool(new McpSchema.CallToolRequest(tool, arguments));
        } catch (McpError e) {
            throw new BrowserException.ToolFailed(tool + " 调用失败：" + e.getMessage());
        } catch (RuntimeException e) {
            throw new BrowserException.Disconnected(tool + " 调用失败，浏览器连接不可用：" + e.getMessage(), e);
        } finally {
            log.info("浏览器工具 {}，{} ms", tool, (System.nanoTime() - started) / 1_000_000);
        }
        String text = text(result);
        if (Boolean.TRUE.equals(result.isError())) {
            if (isConnectionError(text)) {
                throw new BrowserException.Disconnected(tool + "：浏览器连接不可用：" + firstLine(text));
            }
            throw new BrowserException.ToolFailed(tool + " 返回错误：" + firstLine(text));
        }
        return text;
    }

    @Override
    public void close() {
        closeQuietly(client);
    }

    static boolean isConnectionError(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return CONNECTION_ERRORS.stream().anyMatch(lower::contains);
    }

    private static String text(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(content -> ((McpSchema.TextContent) content).text())
                .collect(Collectors.joining("\n"));
    }

    private static String firstLine(String text) {
        return text.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("###"))
                .findFirst()
                .orElse("（无说明）");
    }

    private static void closeQuietly(McpSyncClient client) {
        try {
            if (!client.closeGracefully()) {
                client.close();
            }
        } catch (RuntimeException e) {
            log.warn("关闭浏览器 MCP 时出错：{}", e.getMessage());
        }
    }
}
