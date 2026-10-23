package jobapply.persistence;

import java.nio.file.Files;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * {@code base/conflicts.md}：待核对冲突与空缺。只追加，不改已有的内容（两边都不自动改，由用户以后决定）。
 * 每条是一个 {@code ## 标题} 加几行 {@code - 名称: 值}。文件不存在时按 storage 初始化时的样子新建。
 */
public final class ConflictsFile {

    private final StorageLayout layout;
    private final StorageGit git;

    public ConflictsFile(StorageLayout layout, Clock clock) {
        this.layout = layout;
        this.git = new StorageGit(layout, clock);
    }

    /**
     * @param lines 名称 → 值；值里的换行换成空格
     */
    public synchronized void append(String title, Map<String, String> lines) {
        if (title == null || title.isBlank() || title.contains("\n")) {
            throw new IllegalArgumentException("冲突的标题不能为空，也不能换行");
        }
        String current = Files.exists(layout.baseConflicts()) ? AtomicFiles.read(layout.baseConflicts())
                : FrontmatterFile.render(new FrontmatterFile.Document(Map.of("tags", List.of("jobapply/conflicts")),
                "\n# 待核对冲突与空缺\n"));
        StringBuilder out = new StringBuilder(current.stripTrailing()).append("\n\n## ").append(title.strip())
                .append('\n');
        lines.forEach((name, value) -> out.append("- ").append(name).append(": ")
                .append(value == null || value.isBlank() ? "—" : value.replace('\n', ' ').strip()).append('\n'));
        AtomicFiles.write(layout.baseConflicts(), out.toString());
        git.commit("base：冲突 " + title.strip(), List.of(layout.baseConflicts()));
    }

    public String read() {
        return Files.exists(layout.baseConflicts()) ? AtomicFiles.read(layout.baseConflicts()) : "";
    }
}
