package jobapply.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import jobapply.persistence.StorageLayout;
import jobapply.tools.BrowserLaunch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code jobapply.browser.mcp.*}：如何启动 Playwright MCP。默认值在 {@code application.yml}。
 * 输出目录（{@code --output-dir}）和 root 由 storage 决定：输出写进 {@code storage/cache/playwright-mcp/}，
 * root（workspace）是 {@code storage/uploads/}，所以 MCP 只能读到上传暂存区。
 * {@code --init-page}：把资源里的 {@code browser/no-bring-to-front.cjs} 写到 {@code storage/cache/playwright-init/}
 * 再追加路径（C011-fix 2026-10-24：MCP 切标签页时不再把 Chrome 拉到所有应用前面；不放进输出目录，那里每次连接都清空）。
 * preload：同样把 {@code browser/skip-unresponsive-tabs.cjs} 写到那里，交给 MCP 进程（{@link BrowserLaunch#preload()}，
 * C011-fix 2026-10-25：用户 Chrome 里睡着、卡住的标签页不再拖住连接和每次调用）。写不出来时只记日志，不用它照常启动。
 *
 * @param callTimeout    普通调用的超时（{@link BrowserLaunch#callTimeout()}）
 * @param connectTimeout 建立连接的超时（{@link BrowserLaunch#connectTimeout()}）；不写时同 {@code callTimeout}
 */
@ConfigurationProperties("jobapply.browser.mcp")
public record BrowserMcpProperties(String command, List<String> args, String profileDirName, Duration callTimeout,
                                   Duration connectTimeout) {

    public BrowserLaunch toLaunch(StorageLayout layout) {
        List<String> all = new ArrayList<>(args == null ? List.of() : args);
        if (profileDirName != null && !profileDirName.isBlank()) {
            all.add("--profile-dir-name");
            all.add(profileDirName);
        }
        all.add("--output-dir");
        all.add(layout.mcpOutputDir().toString());
        all.add("--init-page");
        all.add(script(layout, INIT_PAGE).toString());
        return new BrowserLaunch(command, all, callTimeout, System.getenv("PATH"), System.getProperty("user.home"),
                List.of(layout.uploadsDir()), layout.mcpOutputDir(), connectTimeout, preload(layout));
    }

    /** preload 写不出来时不用它（MCP 照原样运行），记一行日志。 */
    private static Path preload(StorageLayout layout) {
        try {
            return script(layout, PRELOAD);
        } catch (RuntimeException e) {
            log.warn("浏览器 MCP 的 preload 没写出来，不用它启动：{}", e.getMessage());
            return null;
        }
    }

    /** 把 {@code browser/<name>} 从资源写到 storage 里（每次启动覆盖一次），返回绝对路径（MCP / Node 用 {@code require} 加载它）。 */
    static Path script(StorageLayout layout, String name) {
        Path file = layout.cacheDir().resolve("playwright-init").resolve(name).toAbsolutePath().normalize();
        try (InputStream in = BrowserMcpProperties.class.getClassLoader().getResourceAsStream("browser/" + name)) {
            if (in == null) {
                throw new IllegalStateException("资源里没有 browser/" + name);
            }
            Files.createDirectories(file.getParent());
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("写浏览器 MCP 的脚本失败：" + file, e);
        }
        return file;
    }

    private static final String INIT_PAGE = "no-bring-to-front.cjs";
    private static final String PRELOAD = "skip-unresponsive-tabs.cjs";
    private static final Logger log = LoggerFactory.getLogger(BrowserMcpProperties.class);
}
