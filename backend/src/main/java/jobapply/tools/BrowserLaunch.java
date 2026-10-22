package jobapply.tools;

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
 */
public record BrowserLaunch(String command, List<String> args, Duration callTimeout, String path, String home) {

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
    }
}
