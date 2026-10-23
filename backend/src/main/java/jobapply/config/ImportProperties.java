package jobapply.config;

import java.net.URI;
import java.time.Duration;
import jobapply.tools.GitHubPublicReader;
import jobapply.tools.LocalDirectoryScanner;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code jobapply.import.*}：资料导入的扫描上限和 GitHub 接口地址（C004-A-1「已确认」第 4 项）。
 * 没写的项用默认值：单个来源最多 400 个文件、150 万字符；yaml / json 单个不超过 200KB；源代码只读前 40 行；
 * GitHub 接口 {@code https://api.github.com}，超时 15 秒。
 */
@ConfigurationProperties("jobapply.import")
public record ImportProperties(Integer maxFiles, Long maxChars, Long maxStructuredBytes, Integer sourceHeadLines,
                               String githubApi, Duration githubTimeout) {

    public ImportProperties {
        maxFiles = maxFiles == null ? 400 : maxFiles;
        maxChars = maxChars == null ? 1_500_000L : maxChars;
        maxStructuredBytes = maxStructuredBytes == null ? 200L * 1024 : maxStructuredBytes;
        sourceHeadLines = sourceHeadLines == null ? 40 : sourceHeadLines;
        githubApi = githubApi == null || githubApi.isBlank() ? GitHubPublicReader.DEFAULT_API.toString() : githubApi;
        githubTimeout = githubTimeout == null ? Duration.ofSeconds(15) : githubTimeout;
    }

    public LocalDirectoryScanner.Limits limits() {
        return new LocalDirectoryScanner.Limits(maxFiles, maxChars, maxStructuredBytes, sourceHeadLines);
    }

    public URI githubUri() {
        return URI.create(githubApi);
    }
}
