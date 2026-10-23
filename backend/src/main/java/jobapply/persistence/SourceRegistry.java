package jobapply.persistence;

import java.nio.file.Files;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import jobapply.domain.Ids;
import jobapply.domain.Source;
import jobapply.domain.Timestamps;

/**
 * {@code base/sources.md}：本地目录和 GitHub 公开仓库的登记（上传的原件在 {@code assets.md}）。只登记，不复制。
 * 同一类型、同一位置已经登记过时返回已有的那一条。删除登记以后键不复用，并记在 frontmatter 的 {@code deleted} 里，
 * 事实层里指向它的出处可以显示为「来源已删除」。读的时候保留未识别的列和表格前后的内容。
 */
public final class SourceRegistry {

    static final String KEY = "键";
    static final String TYPE = "类型";
    static final String LOCATION = "位置";
    static final String VERSION = "版本";
    static final String REGISTERED_AT = "登记时间";
    static final String SCANNED_AT = "最近扫描";
    static final String FILES = "读取文件数";
    static final String DESCRIPTION = "说明";
    private static final List<String> KNOWN = List.of(KEY, TYPE, LOCATION, VERSION, REGISTERED_AT, SCANNED_AT, FILES,
            DESCRIPTION);
    private static final String HEADING = "\n# 来源登记\n\n";
    private static final String DELETED = "deleted";

    public enum Status {
        ACTIVE, DELETED, UNKNOWN
    }

    /** 登记结果：{@code duplicate} 为 true 时是已有的那一条。 */
    public record Registered(Source source, boolean duplicate) {
    }

    private final StorageLayout layout;
    private final Clock clock;
    private final StorageGit git;

    public SourceRegistry(StorageLayout layout, Clock clock) {
        this.layout = layout;
        this.clock = clock;
        this.git = new StorageGit(layout, clock);
    }

    public synchronized List<Source> list() {
        return List.copyOf(load().sources);
    }

    public synchronized Optional<Source> find(String key) {
        return load().sources.stream().filter(source -> source.key().equals(key)).findFirst();
    }

    /** 一个来源键现在的状态：还在登记里、已删除、从来没有过。 */
    public synchronized Status status(String key) {
        Registry registry = load();
        if (registry.sources.stream().anyMatch(source -> source.key().equals(key))) {
            return Status.ACTIVE;
        }
        return registry.deleted.contains(key) ? Status.DELETED : Status.UNKNOWN;
    }

    /**
     * @param location 已经由调用方规范化（目录是真实的绝对路径，GitHub 是 {@code owner/repo}）
     * @param version  登记时读到的版本，没有就传空字符串
     */
    public synchronized Registered register(Source.Type type, String location, String version) {
        MarkdownTable.requireCell(LOCATION, location);
        Registry registry = load();
        Optional<Source> existing = registry.sources.stream()
                .filter(source -> source.type() == type && source.location().equals(location)).findFirst();
        if (existing.isPresent()) {
            return new Registered(existing.get(), true);
        }
        Set<String> used = new LinkedHashSet<>(registry.deleted);
        registry.sources.forEach(source -> used.add(source.key()));
        String key = IdSequence.next("s-" + LocalDate.now(clock) + "-", used);
        Source source = new Source(key, type, location, version, Timestamps.now(clock), "", "", "", Map.of());
        registry.sources.add(source);
        save(registry, "base：登记来源 " + key);
        return new Registered(source, false);
    }

    /** 记下一次扫描：版本、时间、读取的文件数。 */
    public synchronized Source scanned(String key, String version, int fileCount) {
        Registry registry = load();
        int index = indexOf(registry, key);
        Source next = registry.sources.get(index).scanned(version, Timestamps.now(clock), fileCount);
        registry.sources.set(index, next);
        save(registry, "base：扫描来源 " + key);
        return next;
    }

    /** 删除登记。不碰目录或仓库里的任何东西。 */
    public synchronized void delete(String key) {
        Ids.requireSourceKey(key);
        Registry registry = load();
        registry.sources.remove(indexOf(registry, key));
        registry.deleted.add(key);
        save(registry, "base：删除来源 " + key);
    }

    private static int indexOf(Registry registry, String key) {
        for (int i = 0; i < registry.sources.size(); i++) {
            if (registry.sources.get(i).key().equals(key)) {
                return i;
            }
        }
        throw new NoSuchElementException("来源不存在：" + key);
    }

    private static final class Registry {
        Map<String, Object> frontmatter;
        MarkdownTable table;
        List<Source> sources;
        Set<String> deleted;
    }

    private Registry load() {
        Registry registry = new Registry();
        String file = layout.baseSources().toString();
        if (Files.exists(layout.baseSources())) {
            FrontmatterFile.Document document = FrontmatterFile.parse(AtomicFiles.read(layout.baseSources()), file);
            registry.frontmatter = document.frontmatter();
            registry.table = MarkdownTable.parse(document.body(), KEY, KNOWN, HEADING);
        } else {
            registry.frontmatter = new LinkedHashMap<>();
            registry.table = MarkdownTable.empty(HEADING, KNOWN);
        }
        registry.deleted = new LinkedHashSet<>();
        if (registry.frontmatter.get(DELETED) instanceof List<?> list) {
            list.forEach(item -> registry.deleted.add(String.valueOf(item)));
        }
        registry.sources = new ArrayList<>();
        for (Map<String, String> row : registry.table.rows) {
            String key = row.getOrDefault(KEY, "");
            if (!Ids.isSourceKey(key)) {
                continue;
            }
            try {
                registry.sources.add(new Source(key, Source.Type.of(row.getOrDefault(TYPE, "")),
                        row.getOrDefault(LOCATION, ""), row.getOrDefault(VERSION, ""),
                        row.getOrDefault(REGISTERED_AT, ""), row.getOrDefault(SCANNED_AT, ""),
                        row.getOrDefault(FILES, ""), row.getOrDefault(DESCRIPTION, ""),
                        MarkdownTable.extra(row, KNOWN)));
            } catch (IllegalArgumentException e) {
                throw new StorageFormatException(file, key + "：" + e.getMessage(), e);
            }
        }
        return registry;
    }

    private void save(Registry registry, String message) {
        registry.table.rows.clear();
        for (Source source : registry.sources) {
            Map<String, String> row = new LinkedHashMap<>(source.extra());
            row.put(KEY, source.key());
            row.put(TYPE, source.type().code());
            row.put(LOCATION, source.location());
            row.put(VERSION, source.version());
            row.put(REGISTERED_AT, source.registeredAt());
            row.put(SCANNED_AT, source.lastScannedAt());
            row.put(FILES, source.fileCount());
            row.put(DESCRIPTION, source.description());
            registry.table.rows.add(row);
        }
        Map<String, Object> frontmatter = new LinkedHashMap<>(registry.frontmatter);
        if (!registry.deleted.isEmpty()) {
            frontmatter.put(DELETED, List.copyOf(registry.deleted));
        }
        String body = registry.table.render();
        AtomicFiles.write(layout.baseSources(), frontmatter.isEmpty() ? body.stripLeading()
                : FrontmatterFile.render(new FrontmatterFile.Document(frontmatter, body)));
        git.commit(message, List.of(layout.baseSources()));
    }
}
