package jobapply.persistence;

import java.nio.file.Files;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import jobapply.domain.SubRun;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Subagent 运行记录（C030 Slice 5）：{@code projects/<project-id>/subruns/<subId>.json}，整份重写。和父运行的对话
 * （Session 的 {@code transcript.jsonl}、Job 线程）分开存，父运行只拿到精简结果。
 */
public final class SubRunStore {

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** {@link SubRun} 除了 {@code turns} 的字段数。 */
    private static final int HEADER_FIELDS = SubRun.class.getRecordComponents().length - 1;

    private final StorageLayout layout;

    public SubRunStore(StorageLayout layout) {
        this.layout = layout;
    }

    public void save(SubRun run) {
        Path path = layout.subrun(run.project(), run.id());
        String json = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(run) + "\n";
        synchronized (this) {
            AtomicFiles.write(path, json);
        }
    }

    /** 一次子运行的记录；没有时为空。 */
    public Optional<SubRun> find(String projectId, String subRunId) {
        Path path = layout.subrun(projectId, subRunId);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readValue(AtomicFiles.read(path), SubRun.class));
        } catch (JacksonException e) {
            throw new StorageFormatException(path.toString(), "Subagent 记录不是合法的 JSON：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 这个 Project 下所有子运行的记录（不带对话），按开始时间排。 */
    public List<SubRun> list(String projectId) {
        Path dir = layout.subrunsDir(projectId);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(path -> path.getFileName().toString().endsWith(".json")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("读不出 Subagent 记录目录", e);
        }
        String suffix = ".json";
        return files.stream()
                .map(path -> path.getFileName().toString())
                .map(name -> header(projectId, name.substring(0, name.length() - suffix.length())))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(run -> Objects.toString(run.startedAt(), "")))
                .toList();
    }

    /**
     * 只读一次子运行记录的头（不带对话）：流式读字段，{@code turns} 跳过不建对象；现在写出的文件里 {@code turns}
     * 在最后，读到它时头已经齐了就不再往下读。
     */
    private Optional<SubRun> header(String projectId, String subRunId) {
        Path path = layout.subrun(projectId, subRunId);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        ObjectNode header = JSON.createObjectNode();
        try (InputStream in = Files.newInputStream(path); JsonParser parser = JSON.createParser(in)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new StorageFormatException(path.toString(), "Subagent 记录不是 JSON 对象", null);
            }
            while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                String name = parser.currentName();
                parser.nextToken();
                if ("turns".equals(name)) {
                    if (header.size() >= HEADER_FIELDS) {
                        break;
                    }
                    parser.skipChildren();
                    continue;
                }
                header.set(name, parser.readValueAsTree());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读不出 Subagent 记录：" + path, e);
        } catch (JacksonException e) {
            throw new StorageFormatException(path.toString(), "Subagent 记录不是合法的 JSON：" + e.getClass().getSimpleName(), e);
        }
        return Optional.of(JSON.treeToValue(header, SubRun.class));
    }
}
