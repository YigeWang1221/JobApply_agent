package jobapply.domain;

import java.util.List;

/**
 * 一个来源的只读扫描结果（本地目录或 GitHub 仓库）：会读哪些文件、跳过了哪些（只有名字和原因）、文字量、提交摘要。
 * 不含文件内容。
 *
 * @param version     本地目录是 {@code files:<文件清单哈希>}，GitHub 是 commit SHA
 * @param githubCalls 这次扫描已经用掉的 GitHub 接口调用次数（本地目录是 0）
 * @param overLimit   超过了单个来源的文件数或字符数上限，后面的文件没有读
 */
public record SourceScan(String version, List<FileEntry> files, List<Skipped> skipped, long chars,
                         CommitSummary commits, List<String> warnings, int githubCalls, boolean overLimit) {

    /**
     * @param kind  {@code doc} / {@code manifest} / {@code structured} / {@code source}（源代码只取开头几行）/
     *              {@code tree}（GitHub 的文件列表）
     * @param chars 会读的字符数（GitHub 按文件大小估算）
     */
    public record FileEntry(String path, String kind, long chars) {
    }

    public record Skipped(String path, String reason) {
    }

    /**
     * @param ownPercent 本人提交占比（按用户确认过的邮箱或名字匹配）；没有可匹配的身份时为 null
     */
    public record CommitSummary(int count, String firstAt, String lastAt, Integer ownPercent,
                                List<String> recentTitles) {

        public CommitSummary {
            recentTitles = recentTitles == null ? List.of() : List.copyOf(recentTitles);
        }
    }

    public SourceScan {
        files = files == null ? List.of() : List.copyOf(files);
        skipped = skipped == null ? List.of() : List.copyOf(skipped);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
