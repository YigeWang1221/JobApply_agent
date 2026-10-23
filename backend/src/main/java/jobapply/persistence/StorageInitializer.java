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
 * 建 storage 骨架。storage 不做任何版本控制（用户确认，2026-10-23）：没有 git，也没有 {@code .gitignore}。
 * 重复初始化是幂等的，不覆盖已有文件。
 */
public final class StorageInitializer {

    private final StorageLayout layout;
    private final TrackerWriter tracker;

    public StorageInitializer(StorageLayout layout, Clock clock) {
        this.layout = layout;
        this.tracker = new TrackerWriter(layout);
    }

    public void initialize() {
        try {
            Files.createDirectories(layout.baseOriginals());
            Files.createDirectories(layout.projectsDir());
            Files.createDirectories(layout.exportsDir());
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建 storage 目录：" + layout.root(), e);
        }
        AtomicFiles.writeIfAbsent(layout.baseFacts(), FrontmatterFile.render(new FrontmatterFile.Document(
                Map.of("tags", List.of("jobapply/facts")), "\n# 个人基础事实\n")));
        AtomicFiles.writeIfAbsent(layout.baseConflicts(), FrontmatterFile.render(new FrontmatterFile.Document(
                Map.of("tags", List.of("jobapply/conflicts")), "\n# 待核对冲突与空缺\n")));
        tracker.writeGlobal();
    }
}
