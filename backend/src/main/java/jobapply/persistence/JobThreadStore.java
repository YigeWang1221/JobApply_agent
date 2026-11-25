package jobapply.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import jobapply.domain.AgentRun;
import jobapply.domain.Job;
import jobapply.domain.Timestamps;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 一个 Job 的运行线程存在哪里（C030 统一 Job 线程）。一个 Job 一条线程，分两段，沿用已有的存储位置（零迁移）：
 * <ul>
 *   <li>投递段：{@code storage/runs/<job-id>.json}，一次申请的运行状态和对话（{@link AgentRun}）；附件在
 *       {@code uploads/<job-id>/attachments/}。</li>
 *   <li>后续段：{@code jobs/<id>/followup/}，{@code transcript.jsonl} 是对话（格式同 Session 的），{@code followup.md}
 *       只记上一次运行的状态和错误；附件在 {@code followup/attachments/}。</li>
 * </ul>
 * 现在在哪一段只看 {@code job.md} 的 {@code phase}（{@link jobapply.domain.JobStatus#applySegment}），这里不另存。
 * 附件两段互为后备（{@link #attachments}），存量 Job 两处的附件都读得到。
 * <p>
 * C019 之前的运行记录只有模型的计划 {@code plan}（一张 {@code {text, status}} 表），没有申请状态 {@code state}：
 * 读的时候把它放进 {@code state.steps}，旧申请照样能接着做。
 * <p>
 * 锁：运行记录每个文件一把锁，进程里所有实例共用；不同 Job 的 Worker 互不等待，JSON 在锁外序列化和解析。后续段的
 * 对话和状态和 {@code job.md} 同一把写锁（{@link StorageLock}）。
 */
public final class JobThreadStore {

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final ConcurrentMap<Path, Object> RUN_LOCKS = new ConcurrentHashMap<>();

    private final StorageLayout layout;
    private final Clock clock;
    private final EventLog eventLog = new EventLog();

    public JobThreadStore(StorageLayout layout, Clock clock) {
        this.layout = layout;
        this.clock = clock;
    }

    /** 后续段上一次运行的状态和错误；没跑过时都是 null。 */
    public record FollowupStatus(String status, String error) {
    }

    // ---- 投递段 ----

    public void saveRun(AgentRun run) {
        Path path = layout.agentRun(run.job());
        String json = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(run) + "\n";
        synchronized (runLock(path)) {
            AtomicFiles.write(path, json);
        }
    }

    public void deleteRun(String jobId) {
        Path path = layout.agentRun(jobId);
        synchronized (runLock(path)) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                throw new UncheckedIOException("删除运行状态失败：" + jobId, e);
            }
        }
    }

    /** 投递段的运行记录；还没开始过申请时为空。 */
    public Optional<AgentRun> findRun(String jobId) {
        Path path = layout.agentRun(jobId);
        String text;
        synchronized (runLock(path)) {
            if (!Files.exists(path)) {
                return Optional.empty();
            }
            text = AtomicFiles.read(path);
        }
        try {
            JsonNode tree = JSON.readTree(text);
            if (tree instanceof ObjectNode run && !run.has("state") && run.get("plan") != null
                    && run.get("plan").isArray()) {
                run.putObject("state").set("steps", run.get("plan"));
            }
            return Optional.of(JSON.treeToValue(tree, AgentRun.class));
        } catch (JacksonException e) {
            throw new StorageFormatException(path.toString(), "运行状态不是合法的 JSON：" + e.getClass().getSimpleName(), e);
        }
    }

    // ---- 后续段 ----

    public void appendFollowup(String projectId, String jobId, AgentRun.Turn turn) {
        synchronized (StorageLock.WRITES) {
            eventLog.append(layout.followupTranscript(projectId, jobId),
                    SessionRepository.line(Timestamps.now(clock), turn));
        }
    }

    /** 后续段的全部对话；还没有后续段时为空。 */
    public List<SessionRepository.TimedTurn> followupTurns(String projectId, String jobId) {
        return SessionRepository.turns(eventLog.read(layout.followupTranscript(projectId, jobId)));
    }

    /** 记下后续段这一次运行的状态和错误（没有错误时去掉）。 */
    public void writeFollowupStatus(String projectId, String jobId, String status, String error) {
        synchronized (StorageLock.WRITES) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("job", jobId);
            fm.put("last_status", status);
            if (error != null) {
                fm.put("last_error", error);
            }
            FrontmatterFile.write(layout.followupStatus(projectId, jobId), new FrontmatterFile.Document(fm, ""));
        }
    }

    public FollowupStatus followupStatus(String projectId, String jobId) {
        Path file = layout.followupStatus(projectId, jobId);
        if (!Files.exists(file)) {
            return new FollowupStatus(null, null);
        }
        Map<String, Object> fm = FrontmatterFile.read(file).frontmatter();
        return new FollowupStatus(fm.get("last_status") == null ? null : String.valueOf(fm.get("last_status")),
                fm.get("last_error") == null ? null : String.valueOf(fm.get("last_error")));
    }

    // ---- 附件 ----

    /** 这个 Job 的附件：按 {@code job.md} 的 phase 选主目录，另一段的目录作后备。 */
    public AttachmentStore attachments(Job job) {
        return attachments(layout, job.project(), job.id(), job.status().applySegment());
    }

    /**
     * 这个 Job 的附件。投递段的主目录是 {@code uploads/<job-id>/attachments/}，后续段的是
     * {@code jobs/<id>/followup/attachments/}；新附件存进当前这段的目录，按编号找和列出时两处都看。
     */
    public static AttachmentStore attachments(StorageLayout layout, String projectId, String jobId,
                                              boolean applySegment) {
        Path apply = layout.jobAttachmentsDir(jobId);
        Path followup = layout.followupAttachmentsDir(projectId, jobId);
        return applySegment ? new AttachmentStore(apply, followup) : new AttachmentStore(followup, apply);
    }

    private static Object runLock(Path path) {
        return RUN_LOCKS.computeIfAbsent(path.toAbsolutePath().normalize(), key -> new Object());
    }
}
