package jobapply.workflow.checkpoints;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bsc.langgraph4j.checkpoint.SQLiteSaver;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.state.AgentState;

/**
 * LangGraph4j Checkpoint 存到 {@code storage/checkpoints.sqlite}。表不存在时创建，已有数据保留，
 * 所以应用重启后可以从中断点继续。Checkpoint 只是运行状态，不是记忆。
 */
public final class CheckpointStore {

    private CheckpointStore() {
    }

    public static <S extends AgentState> SQLiteSaver open(Path database, StateSerializer<S> serializer) {
        try {
            Files.createDirectories(database.toAbsolutePath().getParent());
            return SQLiteSaver.builder()
                    .databasePath(database.toAbsolutePath().toString())
                    .stateSerializer(serializer)
                    .createTables(true)
                    .build();
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建 Checkpoint 目录：" + database, e);
        } catch (Exception e) {
            throw new IllegalStateException("无法打开 Checkpoint：" + database, e);
        }
    }
}
