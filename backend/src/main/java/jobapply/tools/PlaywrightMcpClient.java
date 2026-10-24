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
import tools.jackson.databind.json.JsonMapper;

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

    /** MCP 的 {@code browser_run_code_unsafe}：不在白名单里，只由 {@link #setInputFiles} 用固定模板代发。 */
    static final String RUN_CODE = "browser_run_code_unsafe";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * 把文件直接设到上传位置的文件输入框上（Playwright {@code setInputFiles}：不点击、不等文件选择框，隐藏的
     * {@code input[type=file]} 和被盖住的按钮也行）。从 ref 指的元素往外找：它自己、它里面、label 关联的、
     * 再往上一层层找最近的那个文件输入框。只有 ref 和路径两个参数，都按 JSON 字符串嵌进去。
     */
    private static final String SET_INPUT_FILES = """
            async (page) => {
              const input = await page.locator(%s).evaluateHandle(el => {
                for (let n = el; n; n = n.parentElement) {
                  if (n.matches('input[type=file]')) return n;
                  if (n.tagName === 'LABEL' && n.control && n.control.type === 'file') return n.control;
                  const inside = n.querySelector('input[type=file]');
                  if (inside) return inside;
                }
                return null;
              });
              const element = input.asElement();
              if (!element) throw new Error('这个上传位置附近没有文件输入框（input type=file）');
              await element.setInputFiles(%s);
              return 'files set on the file input';
            }""";

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
        if (BrowserToolPolicy.FILE_UPLOAD.equals(tool) && arguments.get("target") instanceof String ref) {
            return send(RUN_CODE, setInputFiles(ref, arguments.get("paths")), tool);
        }
        return send(tool, arguments, tool);
    }

    /** 带 target 的上传：过了白名单（ref 在最新快照里、路径都在上传目录里）之后，换成固定模板的代码。 */
    static Map<String, Object> setInputFiles(String ref, Object paths) {
        String code = SET_INPUT_FILES.formatted(JSON.writeValueAsString("aria-ref=" + ref), JSON.writeValueAsString(paths));
        return Map.of("code", code);
    }

    /** 发给 MCP；{@code shown} 是模型看到的工具名，写进错误里。 */
    private String send(String tool, Map<String, Object> arguments, String shown) {
        long started = System.nanoTime();
        McpSchema.CallToolResult result;
        try {
            result = client.callTool(new McpSchema.CallToolRequest(tool, arguments));
        } catch (McpError e) {
            throw new BrowserException.ToolFailed(shown + " 调用失败：" + e.getMessage());
        } catch (RuntimeException e) {
            throw new BrowserException.Disconnected(shown + " 调用失败，浏览器连接不可用：" + e.getMessage(), e);
        } finally {
            log.info("浏览器工具 {}，{} ms", tool, (System.nanoTime() - started) / 1_000_000);
        }
        String text = text(result);
        if (Boolean.TRUE.equals(result.isError())) {
            if (isConnectionError(text)) {
                throw new BrowserException.Disconnected(shown + "：浏览器连接不可用：" + firstLine(text));
            }
            throw new BrowserException.ToolFailed(shown + " 返回错误：" + firstLine(text) + hint(shown, text));
        }
        return text;
    }

    @Override
    public void close() {
        closeQuietly(client);
    }

    /**
     * MCP 的错误本身不说原因（超时没有调用日志），模型会自己编「浏览器安全限制」。这里按 0.0.83 的错误文本补一句实情。
     */
    static String hint(String tool, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("does not handle the modal state") && lower.contains("file chooser")) {
            return "。说明：页面上有一个已经打开的文件选择框，别的操作都要等它处理完：用 browser_file_upload（不带 target）"
                    + "把文件交给它，或者不带 files 取消它";
        }
        if (BrowserToolPolicy.FILE_UPLOAD.equals(tool)
                && (lower.contains("related modal state") || lower.contains("no file chooser"))) {
            return "。说明：现在没有打开的文件选择框。用 browser_file_upload 带 target（上传按钮或上传区域的 ref），"
                    + "系统直接把文件设到它的文件输入框上，不需要点击";
        }
        if (lower.contains("timeout") && lower.contains("exceeded")) {
            return "。说明：这是动作超时，元素在等待时间内一直不能操作（被加载遮罩或别的层盖住、还在动画、不可见），"
                    + "这次操作没有发生，不是浏览器的安全限制。等页面加载完（browser_wait_for）再取快照重试，或者换页面上"
                    + "可见的那个元素；上传按钮点不动时用 browser_file_upload 带 target 直接上传";
        }
        return "";
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
