package jobapply.tools;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * 如何启动浏览器 MCP 子进程。子进程只拿到 {@code path} 和 {@code home} 两个环境变量。
 *
 * @param command     可执行文件，例如 {@code npx} 或它的绝对路径
 * @param args        参数，例如 {@code -y @playwright/mcp@0.0.83 --cdp-endpoint chrome}
 * @param callTimeout 普通调用的超时：MCP 这么久没回话就算「卡住了」（{@link BrowserException.Stalled}），不算断开。
 *                    要比 MCP 自己的导航超时（{@code --timeout-navigation}）长一点，让导航超时先到
 * @param path        传给子进程的 PATH
 * @param home        传给子进程的 HOME
 * @param roots       MCP 客户端声明的 root；@playwright/mcp 把第一个当作 workspace，文件访问只允许 workspace 和输出目录
 * @param outputDir   MCP 的输出目录（{@code --output-dir}）；建立连接和关闭时清空。null 表示不管理
 * @param connectTimeout 建立连接时的超时（等服务进程监听端口、保活会话的调用）：第一次调用要连 Chrome、等用户点允许，
 *                    要长于 Chrome 等待用户同意连接的时间。null 时同 {@code callTimeout}
 * @param preload     交给 MCP 进程的 Node preload（{@code NODE_OPTIONS=--require <路径>}），null 表示不用：
 *                    让睡着、卡住的标签页不再拖住连接和每次调用（C011-fix 2026-10-25，{@code browser/skip-unresponsive-tabs.cjs}）
 */
public record BrowserLaunch(String command, List<String> args, Duration callTimeout, String path, String home,
                            List<Path> roots, Path outputDir, Duration connectTimeout, Path preload) {

    public BrowserLaunch {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("浏览器 MCP 的启动命令不能为空");
        }
        args = args == null ? List.of() : List.copyOf(args);
        if (callTimeout == null || callTimeout.isNegative() || callTimeout.isZero()) {
            throw new IllegalArgumentException("callTimeout 必须为正");
        }
        path = path == null ? "" : path;
        home = home == null ? "" : home;
        roots = roots == null ? List.of() : roots.stream().map(root -> root.toAbsolutePath().normalize()).toList();
        outputDir = outputDir == null ? null : outputDir.toAbsolutePath().normalize();
        if (connectTimeout == null) {
            connectTimeout = callTimeout;
        } else if (connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("connectTimeout 必须为正");
        }
        preload = preload == null ? null : preload.toAbsolutePath().normalize();
    }

    public BrowserLaunch(String command, List<String> args, Duration callTimeout, String path, String home,
                         List<Path> roots, Path outputDir, Duration connectTimeout) {
        this(command, args, callTimeout, path, home, roots, outputDir, connectTimeout, null);
    }

    public BrowserLaunch(String command, List<String> args, Duration callTimeout, String path, String home,
                         List<Path> roots, Path outputDir) {
        this(command, args, callTimeout, path, home, roots, outputDir, null, null);
    }

    public BrowserLaunch(String command, List<String> args, Duration callTimeout, String path, String home) {
        this(command, args, callTimeout, path, home, List.of(), null, null, null);
    }
}
