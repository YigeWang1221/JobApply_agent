package jobapply.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import jobapply.persistence.StorageLayout;
import jobapply.tools.BrowserLaunch;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code jobapply.browser.mcp.*}：如何启动 Playwright MCP。默认值在 {@code application.yml}。
 * 输出目录（{@code --output-dir}）和 root 由 storage 决定：输出写进 {@code storage/cache/playwright-mcp/}，
 * root（workspace）是 {@code storage/uploads/}，所以 MCP 只能读到上传暂存区。
 */
@ConfigurationProperties("jobapply.browser.mcp")
public record BrowserMcpProperties(String command, List<String> args, String profileDirName, Duration callTimeout) {

    public BrowserLaunch toLaunch(StorageLayout layout) {
        List<String> all = new ArrayList<>(args == null ? List.of() : args);
        if (profileDirName != null && !profileDirName.isBlank()) {
            all.add("--profile-dir-name");
            all.add(profileDirName);
        }
        all.add("--output-dir");
        all.add(layout.mcpOutputDir().toString());
        return new BrowserLaunch(command, all, callTimeout, System.getenv("PATH"), System.getProperty("user.home"),
                List.of(layout.uploadsDir()), layout.mcpOutputDir());
    }
}
