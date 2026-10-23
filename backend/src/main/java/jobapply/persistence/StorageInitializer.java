package jobapply.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 建 storage 骨架。git 是可选项：开启时 {@code git init} 并提交骨架；不开启时不用任何其它方式保留版本。
 * 不提供关闭 git 的方法（关闭等于删除版本历史，由用户自己处理）。重复初始化是幂等的，不覆盖已有文件；
 * 已有的 {@code .gitignore} 缺少某一行时只在末尾追加，其它内容不动。
 */
public final class StorageInitializer {

    static final List<String> GITIGNORE_LINES = List.of(
            "settings/.env", "uploads/", "cache/", "*.sqlite", ".obsidian/", "example.data/");

    private final StorageLayout layout;
    private final StorageGit git;
    private final TrackerWriter tracker;

    public StorageInitializer(StorageLayout layout, Clock clock) {
        this.layout = layout;
        this.git = new StorageGit(layout, clock);
        this.tracker = new TrackerWriter(layout);
    }

    public void initialize(boolean enableGit) {
        if (!enableGit && git.isEnabled()) {
            throw new IllegalStateException("storage 已开启 git；本系统不提供关闭 git 的方法：" + layout.root());
        }
        List<Path> skeleton = writeSkeleton();
        if (enableGit) {
            if (!git.isEnabled()) {
                git.init();
            }
            git.commit("storage：初始化", skeleton);
        }
    }

    /** 为已有的 storage 开启 git，并把当前全部未被忽略的文件提交一次。已开启时什么也不做。 */
    public boolean enableGit() {
        if (git.isEnabled()) {
            return false;
        }
        writeSkeleton();
        git.init();
        return git.commitAll("storage：开启 git");
    }

    private List<Path> writeSkeleton() {
        try {
            Files.createDirectories(layout.baseOriginals());
            Files.createDirectories(layout.projectsDir());
            Files.createDirectories(layout.exportsDir());
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建 storage 目录：" + layout.root(), e);
        }
        writeGitignore();
        AtomicFiles.writeIfAbsent(layout.baseFacts(), FrontmatterFile.render(new FrontmatterFile.Document(
                Map.of("tags", List.of("jobapply/facts")), "\n# 个人基础事实\n")));
        AtomicFiles.writeIfAbsent(layout.baseConflicts(), FrontmatterFile.render(new FrontmatterFile.Document(
                Map.of("tags", List.of("jobapply/conflicts")), "\n# 待核对冲突与空缺\n")));
        List<Path> written = new ArrayList<>(List.of(layout.gitignore(), layout.baseFacts(), layout.baseConflicts()));
        written.addAll(tracker.writeGlobal());
        return written;
    }

    private void writeGitignore() {
        Path file = layout.gitignore();
        if (!Files.exists(file)) {
            AtomicFiles.write(file,
                    "# JobApply storage：以下内容不进入 storage/.git\n" + String.join("\n", GITIGNORE_LINES) + "\n");
            return;
        }
        String content = AtomicFiles.read(file);
        List<String> present = content.lines().map(String::trim).toList();
        List<String> missing = GITIGNORE_LINES.stream().filter(line -> !present.contains(line)).toList();
        if (!missing.isEmpty()) {
            String separator = content.isEmpty() || content.endsWith("\n") ? "" : "\n";
            AtomicFiles.write(file, content + separator + String.join("\n", missing) + "\n");
        }
    }
}
