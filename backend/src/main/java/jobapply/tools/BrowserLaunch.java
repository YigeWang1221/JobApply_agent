package jobapply.tools;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * 如何启动浏览器 MCP 子进程。子进程只拿到 {@code path} 和 {@code home} 两个环境变量。
 *
 * @param command     可执行文件，例如 {@code npx} 或它的绝对路径
 * @param args        参数，例如 {@code -y @playwright/mcp@0.0.83 --extension}
 * @param callTimeout 单次调用的超时，要长于扩展等待用户同意连接的 30 秒
 * @param path        传给子进程的 PATH
 * @param home        传给子进程的 HOME
 * @param roots       MCP 客户端声明的 root；@playwright/mcp 把第一个当作 workspace，文件访问只允许 workspace 和输出目录
 * @param outputDir   MCP 的输出目录（{@code --output-dir}）；建立连接和关闭时清空。null 表示不管理
 */
public record BrowserLaunch(String command, List<String> args, Duration callTimeout, String path, String home,
                            List<Path> roots, Path outputDir) {

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
    }

    public BrowserLaunch(String command, List<String> args, Duration callTimeout, String path, String home) {
        this(command, args, callTimeout, path, home, List.of(), null);
    }
}
