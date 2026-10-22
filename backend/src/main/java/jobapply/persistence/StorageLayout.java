package jobapply.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;
import jobapply.domain.Ids;

/**
 * storage 目录里所有路径的唯一来源。布局见 plan/master/memory.md「storage 目录」。
 * 拼进路径的 id 一律先校验格式（{@link Ids}），不合法就报错，所以路径不会越出各自的目录。
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
        return projectsDir().resolve(Ids.requireProjectId(projectId));
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
        return jobsDir(projectId).resolve(Ids.requireJobId(jobId));
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
        return sessionsDir(projectId).resolve(Ids.requireSessionId(sessionId));
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

    /** LangGraph4j Checkpoint（sqlite-saver）。 */
    public Path checkpointsDb() {
        return root.resolve("checkpoints.sqlite");
    }

    /** 站点登录登记（全局，因为浏览器只有一个）。 */
    public Path browserSites() {
        return root.resolve("browser").resolve("sites.md");
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

    /** 已有的 Project id，按名字排序。名字不合规的目录（用户自己建的等）跳过。 */
    public List<String> projectIds() {
        return childDirectoryNames(projectsDir(), Ids::isProjectId);
    }

    /** 本 Project 下已有的 job id。 */
    public List<String> jobIds(String projectId) {
        return childDirectoryNames(jobsDir(projectId), Ids::isJobId);
    }

    /** 本 Project 下已有的 session id。 */
    public List<String> sessionIds(String projectId) {
        return childDirectoryNames(sessionsDir(projectId), Ids::isSessionId);
    }

    /** 相对 storage 根目录、用 / 分隔的路径，供 git 使用。 */
    public String relative(Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static List<String> childDirectoryNames(Path dir, Predicate<String> accept) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(accept)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
