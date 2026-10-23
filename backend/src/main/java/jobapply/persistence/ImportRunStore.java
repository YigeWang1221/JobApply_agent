package jobapply.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Stream;
import jobapply.domain.Ids;
import jobapply.domain.ImportRun;
import jobapply.domain.Timestamps;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code base/import-runs/<id>.json}：导入记录。id 是 {@code r-YYYY-MM-DD-NNN}。不认识的字段原样保留
 * （C004-A-2 会往里加进度和候选）。JSON 的形状由 {@link #toMap(ImportRun)} 决定，接口也用它返回。
 */
public final class ImportRunStore {

    private static final List<String> KNOWN = List.of("id", "status", "created_at", "updated_at", "sources",
            "estimate");
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
    };
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final StorageLayout layout;
    private final Clock clock;
    private final StorageGit git;

    public ImportRunStore(StorageLayout layout, Clock clock) {
        this.layout = layout;
        this.clock = clock;
        this.git = new StorageGit(layout, clock);
    }

    /** 建一条 {@code estimated} 的记录。 */
    public synchronized ImportRun create(List<String> sources, ImportRun.Estimate estimate) {
        String id = IdSequence.next("r-" + LocalDate.now(clock) + "-", ids());
        String now = Timestamps.now(clock);
        ImportRun run = new ImportRun(id, ImportRun.Status.ESTIMATED, now, now, sources, estimate, Map.of());
        write(run);
        return run;
    }

    public synchronized void save(ImportRun run) {
        write(new ImportRun(run.id(), run.status(), run.createdAt(), Timestamps.now(clock), run.sources(),
                run.estimate(), run.extra()));
    }

    public Optional<ImportRun> find(String id) {
        if (!Ids.isImportRunId(id)) {
            throw new IllegalArgumentException("非法的导入记录 id（应为 r-YYYY-MM-DD-NNN）：" + id);
        }
        Path path = layout.importRun(id);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(fromMap(JSON.readValue(AtomicFiles.read(path), MAP)));
        } catch (JacksonException | IllegalArgumentException e) {
            throw new StorageFormatException(path.toString(), "导入记录不是合法的 JSON：" + e.getClass().getSimpleName(), e);
        }
    }

    public ImportRun require(String id) {
        return find(id).orElseThrow(() -> new NoSuchElementException("导入记录不存在：" + id));
    }

    public static Map<String, Object> toMap(ImportRun run) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", run.id());
        map.put("status", run.status().code());
        map.put("created_at", run.createdAt());
        map.put("updated_at", run.updatedAt());
        map.put("sources", run.sources());
        map.put("estimate", run.estimate() == null ? null : JSON.convertValue(run.estimate(), MAP));
        run.extra().forEach(map::putIfAbsent);
        return map;
    }

    @SuppressWarnings("unchecked")
    static ImportRun fromMap(Map<String, Object> map) {
        Map<String, Object> extra = new LinkedHashMap<>(map);
        KNOWN.forEach(extra::remove);
        Object estimate = map.get("estimate");
        return new ImportRun(
                String.valueOf(map.get("id")),
                ImportRun.Status.of(String.valueOf(map.get("status"))),
                (String) map.get("created_at"),
                (String) map.get("updated_at"),
                map.get("sources") == null ? List.of() : (List<String>) map.get("sources"),
                estimate == null ? null : JSON.convertValue(estimate, ImportRun.Estimate.class),
                extra);
    }

    private void write(ImportRun run) {
        Path path = layout.importRun(run.id());
        AtomicFiles.write(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(toMap(run)) + "\n");
        git.commit("base：导入记录 " + run.id() + "（" + run.status().code() + "）", List.of(path));
    }

    private List<String> ids() {
        if (!Files.isDirectory(layout.importRunsDir())) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(layout.importRunsDir())) {
            List<String> ids = new ArrayList<>();
            children.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .map(name -> name.substring(0, name.length() - 5))
                    .forEach(ids::add);
            return ids;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
