package jobapply.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jobapply.domain.AgentRun;
import jobapply.domain.Timestamps;

/**
 * 工作台「后续」段的对话（C022）：{@code jobs/<id>/followup/}。和投递段的运行记录分开，界面上接在它后面。
 * 对话一行一条，格式和 Session 的 {@code transcript.jsonl} 一样；{@code followup.md} 只记上一次运行的状态和错误。
 */
public final class FollowupRepository {

    private final StorageLayout layout;
    private final Clock clock;
    private final EventLog eventLog = new EventLog();

    public FollowupRepository(StorageLayout layout, Clock clock) {
        this.layout = layout;
        this.clock = clock;
    }

    public void appendTurn(String projectId, String jobId, AgentRun.Turn turn) {
        synchronized (StorageLock.WRITES) {
            eventLog.append(layout.followupTranscript(projectId, jobId),
                    SessionRepository.line(Timestamps.now(clock), turn));
        }
    }

    /** 全部对话；还没有后续段时为空。 */
    public List<SessionRepository.TimedTurn> readTurns(String projectId, String jobId) {
        return SessionRepository.turns(eventLog.read(layout.followupTranscript(projectId, jobId)));
    }

    /** 记下这一次运行的状态和错误（没有错误时去掉）。 */
    public void writeStatus(String projectId, String jobId, String status, String error) {
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

    /** 上一次运行的状态和错误；没跑过时都是 null。 */
    public Status status(String projectId, String jobId) {
        Path file = layout.followupStatus(projectId, jobId);
        if (!Files.exists(file)) {
            return new Status(null, null);
        }
        Map<String, Object> fm = FrontmatterFile.read(file).frontmatter();
        return new Status(fm.get("last_status") == null ? null : String.valueOf(fm.get("last_status")),
                fm.get("last_error") == null ? null : String.valueOf(fm.get("last_error")));
    }

    public record Status(String status, String error) {
    }
}
