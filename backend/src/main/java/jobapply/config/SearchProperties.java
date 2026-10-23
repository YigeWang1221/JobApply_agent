package jobapply.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code jobapply.search.*}：对话 Agent 的搜索（C005）。{@code searxng-url} 是本机 SearXNG 的地址，没写时跳过这一级；
 * Brave 的 key 不在这里，程序运行时从 {@code storage/settings/.env} 的 {@link #BRAVE_KEY} 读。
 */
@ConfigurationProperties("jobapply.search")
public record SearchProperties(String searxngUrl) {

    /** Brave Search 的 key 在 {@code .env} 里的变量名。 */
    public static final String BRAVE_KEY = "BRAVE_API_KEY";

    public SearchProperties {
        searxngUrl = searxngUrl == null ? "" : searxngUrl.strip();
    }
}
