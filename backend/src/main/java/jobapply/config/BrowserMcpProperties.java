package jobapply.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import jobapply.tools.BrowserLaunch;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code jobapply.browser.mcp.*}：如何启动 Playwright MCP。默认值在 {@code application.yml}。
 */
@ConfigurationProperties("jobapply.browser.mcp")
public record BrowserMcpProperties(String command, List<String> args, String profileDirName, Duration callTimeout) {

    public BrowserLaunch toLaunch() {
        List<String> all = new ArrayList<>(args == null ? List.of() : args);
        if (profileDirName != null && !profileDirName.isBlank()) {
            all.add("--profile-dir-name");
            all.add(profileDirName);
        }
        return new BrowserLaunch(command, all, callTimeout, System.getenv("PATH"), System.getProperty("user.home"));
    }
}
