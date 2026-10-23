package jobapply.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import jobapply.domain.AgentRun;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code storage/runs/<job-id>.json}：一次申请的运行状态（{@link AgentRun}）。是运行状态，不是记忆。
 */
public final class AgentRunStore {

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final StorageLayout layout;

    public AgentRunStore(StorageLayout layout) {
        this.layout = layout;
    }

    public synchronized void save(AgentRun run) {
        AtomicFiles.write(layout.agentRun(run.job()), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(run)
                + "\n");
    }

    public synchronized Optional<AgentRun> find(String jobId) {
        Path path = layout.agentRun(jobId);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readValue(AtomicFiles.read(path), AgentRun.class));
        } catch (JacksonException e) {
            throw new StorageFormatException(path.toString(), "运行状态不是合法的 JSON：" + e.getClass().getSimpleName(), e);
        }
    }
}
