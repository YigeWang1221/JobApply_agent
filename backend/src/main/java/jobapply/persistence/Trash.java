package jobapply.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 删除 Session / Project（C013）：不真删，整个目录挪进 {@code storage/.trash/}。每次删除一个目录
 * {@code <时间>-<session|project>-<id>/}，里面照原来相对 storage 的路径摆放，再写一份 {@code RESTORE.md} 说明怎么挪回去。
 * 只挪 {@code projects/<id>/}（或其中一个 {@code sessions/<id>/}）和这个 Project 的 Job 在 {@code runs/}、{@code uploads/}
 * 下的东西；{@code base/}、{@code settings/}、{@code playbook/}、别的 Project 一律不碰。在 {@link StorageLock} 里做，
 * 不和台账写入交错。调用方负责先确认没有在跑的运行。
 */
public final class Trash {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final StorageLayout layout;
    private final Clock clock;

    public Trash(StorageLayout layout, Clock clock) {
        this.layout = layout;
        this.clock = clock;
    }

    /** 挪走一个 Session 的目录。返回回收站里这次删除的目录。 */
    public Path moveSession(String projectId, String sessionId) {
        synchronized (StorageLock.WRITES) {
            Path source = layout.sessionDir(projectId, sessionId);
            if (!Files.exists(layout.sessionFile(projectId, sessionId))) {
                throw new IllegalArgumentException("Session 不存在：" + projectId + "/" + sessionId);
            }
            Path entry = newEntry("session", sessionId);
            move(source, entry);
            note(entry, "Session " + sessionId + "（Project " + projectId + "）", List.of(layout.relative(source)));
            return entry;
        }
    }

    /** 挪走一个 Job 的目录。返回回收站里这次删除的目录。 */
    public Path moveJob(String projectId, String jobId) {
        synchronized (StorageLock.WRITES) {
            Path source = layout.jobDir(projectId, jobId);
            if (!Files.exists(layout.jobFile(projectId, jobId))) {
                throw new IllegalArgumentException("Job 不存在：" + projectId + "/" + jobId);
            }
            Path entry = newEntry("job", jobId);
            move(source, entry);
            note(entry, "Job " + jobId + "（Project " + projectId + "）", List.of(layout.relative(source)));
            TrackerWriter tracker = new TrackerWriter(layout, clock);
            tracker.writeProject(projectId);
            tracker.writeGlobal();
            return entry;
        }
    }

    /**
     * 挪走整个 Project 的目录，以及它的 Job 的运行记录（{@code runs/<job>.json}）和上传暂存（{@code uploads/<job>/}），
     * 再按剩下的 Project 重新生成全局台账（{@code jobs-index.md}、{@code exports/applications.csv}）。
     */
    public Path moveProject(String projectId) {
        synchronized (StorageLock.WRITES) {
            Path source = layout.projectDir(projectId);
            if (!Files.exists(layout.projectFile(projectId))) {
                throw new IllegalArgumentException("Project 不存在：" + projectId);
            }
            List<String> jobIds = layout.jobIds(projectId);
            Path entry = newEntry("project", projectId);
            List<String> moved = new ArrayList<>();
            move(source, entry);
            moved.add(layout.relative(source));
            for (String jobId : jobIds) {
                for (Path extra : List.of(layout.agentRun(jobId), layout.uploadsDir(jobId))) {
                    if (Files.exists(extra)) {
                        move(extra, entry);
                        moved.add(layout.relative(extra));
                    }
                }
            }
            note(entry, "Project " + projectId, moved);
            new TrackerWriter(layout).writeGlobal();
            return entry;
        }
    }

    private Path newEntry(String kind, String id) {
        String name = LocalDateTime.now(clock).format(STAMP) + "-" + kind + "-" + id;
        Path entry = layout.trashDir().resolve(name);
        for (int n = 2; Files.exists(entry); n++) {
            entry = layout.trashDir().resolve(name + "-" + n);
        }
        try {
            Files.createDirectories(entry);
        } catch (IOException e) {
            throw new UncheckedIOException("建不了回收站目录：" + entry, e);
        }
        return entry;
    }

    /** 把 storage 里的 {@code source} 挪到 {@code entry} 下同样的相对路径。同一个磁盘上是原子的一步。 */
    private void move(Path source, Path entry) {
        Path target = entry.resolve(layout.relative(source)).normalize();
        if (!source.normalize().startsWith(layout.root()) || !target.startsWith(entry)) {
            throw new IllegalArgumentException("路径越界：" + source);
        }
        try {
            Files.createDirectories(target.getParent());
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(source, target);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("挪进回收站失败：" + layout.relative(source), e);
        }
    }

    private static void note(Path entry, String what, List<String> moved) {
        StringBuilder out = new StringBuilder("# 回收站 · ").append(what).append("\n\n")
                .append("这些路径原来在 storage 下，照原样摆在本目录里：\n\n");
        moved.forEach(path -> out.append("- `").append(path).append("`\n"));
        out.append("\n恢复：把本目录里的 `projects/`（以及 `runs/`、`uploads/`，如果有）挪回 storage 下同样的位置，")
                .append("刷新页面就回来了（Project 恢复后，全局台账在下一次写台账时重新生成）。不要了就直接删掉本目录。\n");
        AtomicFiles.write(entry.resolve("RESTORE.md"), out.toString());
    }
}
