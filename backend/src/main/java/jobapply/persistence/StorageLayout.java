package jobapply.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * storage 目录里所有路径的唯一来源。布局见 plan/master/memory.md「storage 目录」。
 */
public record StorageLayout(Path root) {

    public StorageLayout {
        root = root.toAbsolutePath().normalize();
    }

    public Path gitDir() {
        return root.resolve(".git");
    }

    public Path gitignore() {
        return root.resolve(".gitignore");
    }

    public Path baseDir() {
        return root.resolve("base");
    }

    public Path baseFacts() {
        return baseDir().resolve("facts.md");
    }

    public Path baseConflicts() {
        return baseDir().resolve("conflicts.md");
    }

    public Path baseOriginals() {
        return baseDir().resolve("originals");
    }

    public Path projectsDir() {
        return root.resolve("projects");
    }

    public Path projectDir(String projectId) {
        return projectsDir().resolve(projectId);
    }

    public Path projectFile(String projectId) {
        return projectDir(projectId).resolve("project.md");
    }

    public Path overrides(String projectId) {
        return projectDir(projectId).resolve("overrides.md");
    }

    public Path preferences(String projectId) {
        return projectDir(projectId).resolve("preferences.md");
    }

    public Path jobsDir(String projectId) {
        return projectDir(projectId).resolve("jobs");
    }

    public Path jobDir(String projectId, String jobId) {
        return jobsDir(projectId).resolve(jobId);
    }

    public Path jobFile(String projectId, String jobId) {
        return jobDir(projectId, jobId).resolve("job.md");
    }

    public Path jobEvents(String projectId, String jobId) {
        return jobDir(projectId, jobId).resolve("events.jsonl");
    }

    public Path sessionsDir(String projectId) {
        return projectDir(projectId).resolve("sessions");
    }

    public Path sessionDir(String projectId, String sessionId) {
        return sessionsDir(projectId).resolve(sessionId);
    }

    public Path sessionFile(String projectId, String sessionId) {
        return sessionDir(projectId, sessionId).resolve("session.md");
    }

    public Path transcript(String projectId, String sessionId) {
        return sessionDir(projectId, sessionId).resolve("transcript.jsonl");
    }

    public Path trackerDir(String projectId) {
        return projectDir(projectId).resolve("tracker");
    }

    public Path jobsIndex() {
        return root.resolve("jobs-index.md");
    }

    public Path exportsDir() {
        return root.resolve("exports");
    }

    public Path exportsApplicationsCsv() {
        return exportsDir().resolve("applications.csv");
    }

    /** 已有的 Project id，按名字排序。 */
    public List<String> projectIds() {
        return childDirectoryNames(projectsDir());
    }

    /** 相对 storage 根目录、用 / 分隔的路径，供 git 使用。 */
    public String relative(Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    static List<String> childDirectoryNames(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
