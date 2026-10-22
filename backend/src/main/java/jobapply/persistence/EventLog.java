package jobapply.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jobapply.domain.Actor;
import jobapply.domain.Evidence;
import jobapply.domain.JobEvent;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 追加式 JSONL：{@code events.jsonl} 与 {@code transcript.jsonl}。只追加，不改写已有行。
 */
public final class EventLog {

    private static final TypeReference<LinkedHashMap<String, Object>> LINE = new TypeReference<>() {
    };

    private final JsonMapper mapper = JsonMapper.builder().build();

    public void append(Path file, Map<String, Object> record) {
        AtomicFiles.appendLine(file, mapper.writeValueAsString(record));
    }

    public List<Map<String, Object>> read(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<Map<String, Object>> records = new ArrayList<>();
        String[] lines = AtomicFiles.read(file).split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            try {
                records.add(mapper.readValue(lines[i], LINE));
            } catch (JacksonException e) {
                throw new StorageFormatException(file + " 第 " + (i + 1) + " 行", "不是合法的 JSON", e);
            }
        }
        return records;
    }

    public void appendJobEvent(Path file, JobEvent event) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("at", event.at());
        record.put("session", event.session());
        record.put("actor", event.actor().value());
        record.put("type", event.type().value());
        record.put("from", event.from());
        record.put("to", event.to());
        if (event.reason() != null) {
            record.put("reason", event.reason());
        }
        if (event.evidence() != null) {
            record.put("evidence", JobRepository.evidenceToMap(event.evidence()));
        }
        append(file, record);
    }

    public List<JobEvent> readJobEvents(Path file) {
        List<JobEvent> events = new ArrayList<>();
        for (Map<String, Object> record : read(file)) {
            try {
                Evidence evidence = JobRepository.evidenceFromMap(record.get("evidence"));
                events.add(new JobEvent(
                        string(record, "at"),
                        string(record, "session"),
                        Actor.of(string(record, "actor")),
                        JobEvent.Type.of(string(record, "type")),
                        string(record, "from"),
                        string(record, "to"),
                        string(record, "reason"),
                        evidence));
            } catch (RuntimeException e) {
                throw new StorageFormatException(file.toString(), "事件格式不对：" + record, e);
            }
        }
        return events;
    }

    private static String string(Map<String, Object> record, String key) {
        Object value = record.get(key);
        return value == null ? null : value.toString();
    }
}
