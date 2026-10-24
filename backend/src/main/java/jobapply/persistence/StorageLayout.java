package jobapply.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import jobapply.domain.Ids;

/**
 * storage 目录里所有路径的唯一来源。布局见 plan/master/memory.md「storage 目录」。
 * 拼进路径的 id 一律先校验格式（{@link Ids}），不合法就报错，所以路径不会越出各自的目录。
 */
public record StorageLayout(Path root) {

    private static final Pattern HOST = Pattern.compile("[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?(:[0-9]{1,5})?");

    public StorageLayout {
        root = root.toAbsolutePath().normalize();
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

    /** 原件索引：只登记用户上传的原件。 */
    public Path baseAssets() {
        return baseDir().resolve("assets.md");
    }

    /** 事实层：项目卡，见 memory.md「资料导入与画像」。 */
    public Path baseProjectCardsDir() {
        return baseDir().resolve("projects");
    }

    public Path baseProjectCard(String slug) {
        return baseProjectCardsDir().resolve(Ids.requireProjectSlug(slug) + ".md");
    }

    /** 画像层。 */
    public Path baseProfile() {
        return baseDir().resolve("profile.md");
    }

    public Path baseGlossary() {
        return baseDir().resolve("glossary.md");
    }

    /** 证据层：论断台账。 */
    public Path baseLedger() {
        return baseDir().resolve("ledger.md");
    }

    /** 证据层：每个来源一张清单卡。 */
    public Path baseEvidenceDir() {
        return baseDir().resolve("evidence");
    }

    public Path evidenceCard(String sourceKey) {
        return baseEvidenceDir().resolve(Ids.requireSourceKey(sourceKey) + ".md");
    }

    /** 来源层：本地目录和 GitHub 公开仓库的登记。 */
    public Path baseSources() {
        return baseDir().resolve("sources.md");
    }

    public Path importRunsDir() {
        return baseDir().resolve("import-runs");
    }

    public Path importRun(String runId) {
        return importRunsDir().resolve(Ids.requireImportRunId(runId) + ".json");
    }

    /** 已有的项目卡 slug，按名字排序。 */
    public List<String> projectCardSlugs() {
        if (!Files.isDirectory(baseProjectCardsDir())) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(baseProjectCardsDir())) {
            return children.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".md"))
                    .map(name -> name.substring(0, name.length() - 3))
                    .filter(Ids::isProjectSlug)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 应用设置，见 ui.md「模型设置」。 */
    public Path settingsDir() {
        return root.resolve("settings");
    }

    public Path modelsYaml() {
        return settingsDir().resolve("models.yaml");
    }

    /** 申请并发数（C012，设置页写入）。 */
    public Path workersYaml() {
        return settingsDir().resolve("workers.yaml");
    }

    /** API key（开发代理不读）。 */
    public Path envFile() {
        return settingsDir().resolve(".env");
    }

    /** 浏览器 MCP 允许读取的上传暂存区（MCP root）。 */
    public Path uploadsDir() {
        return root.resolve("uploads");
    }

    /** 这个 Job 的上传暂存目录：原件的等字节副本，到达关卡 B 或线程被丢弃时清空。 */
    public Path uploadsDir(String jobId) {
        return uploadsDir().resolve(Ids.requireJobId(jobId));
    }

    /** 用户在申请工作台附的文件（C006 附件）：在上传区里，浏览器能上传；关卡 B 时不清空，重新开始申请时清空。 */
    public Path jobAttachmentsDir(String jobId) {
        return uploadsDir(jobId).resolve("attachments");
    }

    public Path playbookDir() {
        return root.resolve("playbook");
    }

    /** skill 根目录：{@code apply-form/}、{@code components/*}、{@code platforms/*}，每个目录一个 {@code SKILL.md}。 */
    public Path playbookSkills() {
        return playbookDir().resolve("skills");
    }

    /** 站点的事实记录（不是 skill）。{@code host} 只能是小写的主机名，不能越出 {@code sites/}。 */
    public Path playbookSite(String host) {
        if (host == null || !HOST.matcher(host).matches() || host.contains("..")) {
            throw new IllegalArgumentException("主机名不合法：" + host);
        }
        return playbookDir().resolve("sites").resolve(host + ".md");
    }

    /** 可以随时删除的缓存。 */
    public Path cacheDir() {
        return root.resolve("cache");
    }

    /** 浏览器 MCP 的输出目录（{@code --output-dir}），每次连接和关闭时清空。 */
    public Path mcpOutputDir() {
        return cacheDir().resolve("playwright-mcp");
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

    /** 填写报告（草稿和定稿是同一个文件，草稿的头部标「草稿」）。 */
    public Path fillReport(String projectId, String jobId) {
        return jobDir(projectId, jobId).resolve("fill-report.md");
    }

    /** 填写过程日志；不记填进去的值和快照全文。 */
    public Path runLog(String projectId, String jobId) {
        return jobDir(projectId, jobId).resolve("run-log.jsonl");
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

    /** 用户在 Session 对话里附的文件（C006 附件）。 */
    public Path sessionAttachmentsDir(String projectId, String sessionId) {
        return sessionDir(projectId, sessionId).resolve("attachments");
    }

    /** 对话 Agent 在这个 Session 里读过的网页（开头部分，{@link SessionPages}）。 */
    public Path sessionPagesDir(String projectId, String sessionId) {
        return sessionDir(projectId, sessionId).resolve("pages");
    }

    public Path transcript(String projectId, String sessionId) {
        return sessionDir(projectId, sessionId).resolve("transcript.jsonl");
    }

    public Path trackerDir(String projectId) {
        return projectDir(projectId).resolve("tracker");
    }

    /** 一次申请的运行状态（对话、计划、给用户的话、用量），中断后接着做。 */
    public Path agentRun(String jobId) {
        return root.resolve("runs").resolve(Ids.requireJobId(jobId) + ".json");
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

    /** 相对 storage 根目录、用 / 分隔的路径。 */
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
