package jobapply.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.TreeMap;
import jobapply.domain.Ids;
import jobapply.domain.Timestamps;

/**
 * {@code base/assets.md}：原件索引。只登记用户上传的原件（不登记副本、解析出的文本和日志）。
 * 原件原样保存在 {@code base/originals/<文件名>}；同一个 SHA-256 已经登记过的不重复保存；同名但内容不同时文件名加 -2、-3。
 * 读的时候保留未识别的列（用户以后可能手动加「适用方向」之类的列）和表格前后的内容。
 */
public final class AssetRegistry {

    static final String KEY = "键";
    static final String FILE = "文件名";
    static final String TYPE = "类型";
    static final String SIZE = "大小";
    static final String SHA = "SHA-256";
    static final String UPLOADED_AT = "上传时间";
    static final String DESCRIPTION = "说明";
    private static final List<String> KNOWN_COLUMNS = List.of(KEY, FILE, TYPE, SIZE, SHA, UPLOADED_AT, DESCRIPTION);
    private static final String HEADING = "# 原件索引\n\n";
    static final int MAX_DESCRIPTION = 200;
    static final int MAX_FILE_NAME = 120;

    /**
     * 一条登记。
     *
     * @param extra 未识别的列（列名 → 值）
     */
    public record Asset(String key, String fileName, String type, String size, String sha256, String uploadedAt,
                        String description, Map<String, String> extra) {

        public Asset {
            extra = extra == null ? Map.of() : Map.copyOf(extra);
        }
    }

    /** 登记结果：{@code duplicate} 为 true 时是已有的那一条，没有保存新文件。 */
    public record Registered(Asset asset, boolean duplicate) {
    }

    private final StorageLayout layout;
    private final Clock clock;
    private final StorageGit git;

    public AssetRegistry(StorageLayout layout, Clock clock) {
        this.layout = layout;
        this.clock = clock;
        this.git = new StorageGit(layout, clock);
    }

    public synchronized List<Asset> list() {
        return List.copyOf(table().assets.values());
    }

    public synchronized Optional<Asset> find(String key) {
        return Optional.ofNullable(table().assets.get(key));
    }

    /** 原件在磁盘上的位置。 */
    public Path originalPath(Asset asset) {
        Path path = layout.baseOriginals().resolve(asset.fileName()).normalize();
        if (!path.getParent().equals(layout.baseOriginals().normalize())) {
            throw new IllegalStateException("原件文件名不合法：" + asset.fileName());
        }
        return path;
    }

    /**
     * 保存原件并登记，说明先留空（轻量分析之后再用 {@link #describe(String, String)} 写入）。
     *
     * @param originalName 上传时的文件名，只取最后一段
     * @param type         媒体类型（由调用方检测）
     */
    public synchronized Registered register(String originalName, String type, byte[] content) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("文件是空的");
        }
        String sha = sha256(content);
        Table table = table();
        Optional<Asset> existing = table.assets.values().stream().filter(a -> a.sha256().equals(sha)).findFirst();
        if (existing.isPresent()) {
            return new Registered(existing.get(), true);
        }
        String fileName = freeName(safeFileName(originalName));
        Path target = layout.baseOriginals().resolve(fileName);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("保存原件失败：" + fileName, e);
        }
        String key = IdSequence.next("a-" + LocalDate.now(clock) + "-", table.assets.keySet());
        Asset asset = new Asset(key, fileName, cell(type == null || type.isBlank() ? "application/octet-stream" : type),
                humanSize(content.length), sha, Timestamps.now(clock), "", Map.of());
        table.assets.put(key, asset);
        AtomicFiles.write(layout.baseAssets(), table.render());
        git.commit("base：登记原件 " + key, List.of(layout.baseAssets(), target));
        return new Registered(asset, false);
    }

    /** 修改说明（用户手改，或第一次上传时的轻量分析）。 */
    public synchronized Asset describe(String key, String description) {
        if (!Ids.isAssetKey(key)) {
            throw new IllegalArgumentException("原件键不合法（应为 a-YYYY-MM-DD-NNN）");
        }
        String text = description == null ? "" : description.strip();
        if (text.length() > MAX_DESCRIPTION || text.indexOf('|') >= 0 || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("说明不能超过 " + MAX_DESCRIPTION + " 个字，不能含换行或竖线");
        }
        Table table = table();
        Asset old = table.assets.get(key);
        if (old == null) {
            throw new NoSuchElementException("原件不存在：" + key);
        }
        Asset next = new Asset(old.key(), old.fileName(), old.type(), old.size(), old.sha256(), old.uploadedAt(), text,
                old.extra());
        table.assets.put(key, next);
        AtomicFiles.write(layout.baseAssets(), table.render());
        git.commit("base：修改原件说明 " + key, List.of(layout.baseAssets()));
        return next;
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return (bytes + 1023) / 1024 + " KB";
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** 只取最后一段；去掉控制字符和表格、路径里有特殊含义的字符。 */
    static String safeFileName(String originalName) {
        String name = originalName == null ? "" : originalName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        StringBuilder out = new StringBuilder();
        name.codePoints().forEach(c -> {
            if (!Character.isISOControl(c) && c != '|' && c != ':' && c != '*' && c != '?' && c != '"' && c != '<'
                    && c != '>') {
                out.appendCodePoint(c);
            }
        });
        String cleaned = out.toString().strip();
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.isBlank()) {
            throw new IllegalArgumentException("文件名不合法");
        }
        if (cleaned.length() > MAX_FILE_NAME) {
            int dot = cleaned.lastIndexOf('.');
            String ext = dot > 0 && cleaned.length() - dot <= 10 ? cleaned.substring(dot) : "";
            cleaned = cleaned.substring(0, MAX_FILE_NAME - ext.length()) + ext;
        }
        return cleaned;
    }

    /** 同名文件已存在时加 -2、-3（在扩展名之前）。 */
    private String freeName(String name) {
        if (!Files.exists(layout.baseOriginals().resolve(name))) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int n = 2; ; n++) {
            String candidate = stem + "-" + n + ext;
            if (!Files.exists(layout.baseOriginals().resolve(candidate))) {
                return candidate;
            }
        }
    }

    private static String cell(String value) {
        return value.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').strip();
    }

    private Table table() {
        Path file = layout.baseAssets();
        return Files.exists(file) ? Table.parse(AtomicFiles.read(file)) : Table.empty();
    }

    /** 表格之前的内容、列、行（按键排序）、表格之后的内容。 */
    private static final class Table {

        private final String before;
        private final List<String> columns;
        private final TreeMap<String, Asset> assets;
        private final String after;

        private Table(String before, List<String> columns, TreeMap<String, Asset> assets, String after) {
            this.before = before;
            this.columns = columns;
            this.assets = assets;
            this.after = after;
        }

        static Table empty() {
            return new Table(HEADING, new ArrayList<>(KNOWN_COLUMNS), new TreeMap<>(), "");
        }

        static Table parse(String content) {
            List<String> lines = content.replace("\r\n", "\n").lines().toList();
            int start = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("|") && cells(lines.get(i)).contains(KEY)) {
                    start = i;
                    break;
                }
            }
            if (start < 0) {
                Table table = empty();
                return new Table(content.isBlank() ? HEADING : content.stripTrailing() + "\n\n", table.columns,
                        table.assets, "");
            }
            int end = start;
            while (end < lines.size() && lines.get(end).startsWith("|")) {
                end++;
            }
            List<String> header = cells(lines.get(start));
            List<String> columns = new ArrayList<>(KNOWN_COLUMNS);
            header.stream().filter(column -> !columns.contains(column)).forEach(columns::add);
            TreeMap<String, Asset> assets = new TreeMap<>();
            for (int i = start + 2; i < end; i++) {
                List<String> values = cells(lines.get(i));
                Map<String, String> row = new LinkedHashMap<>();
                for (int c = 0; c < header.size(); c++) {
                    String value = c < values.size() ? values.get(c) : "";
                    row.put(header.get(c), "—".equals(value) ? "" : value);
                }
                String key = row.getOrDefault(KEY, "");
                if (!Ids.isAssetKey(key)) {
                    continue;
                }
                Map<String, String> extra = new LinkedHashMap<>(row);
                KNOWN_COLUMNS.forEach(extra::remove);
                assets.put(key, new Asset(key, row.getOrDefault(FILE, ""), row.getOrDefault(TYPE, ""),
                        row.getOrDefault(SIZE, ""), row.getOrDefault(SHA, ""), row.getOrDefault(UPLOADED_AT, ""),
                        row.getOrDefault(DESCRIPTION, ""), extra));
            }
            String before = String.join("\n", lines.subList(0, start));
            String after = String.join("\n", lines.subList(end, lines.size()));
            return new Table(before.isEmpty() ? "" : before + "\n", columns, assets,
                    after.isBlank() ? "" : "\n" + after + "\n");
        }

        String render() {
            StringBuilder out = new StringBuilder(before);
            out.append("| ").append(String.join(" | ", columns)).append(" |\n");
            out.append("|").append(" --- |".repeat(columns.size())).append("\n");
            for (Asset asset : assets.values()) {
                Map<String, String> row = new LinkedHashMap<>(asset.extra());
                row.put(KEY, asset.key());
                row.put(FILE, asset.fileName());
                row.put(TYPE, asset.type());
                row.put(SIZE, asset.size());
                row.put(SHA, asset.sha256());
                row.put(UPLOADED_AT, asset.uploadedAt());
                row.put(DESCRIPTION, asset.description());
                out.append("|");
                for (String column : columns) {
                    String value = row.getOrDefault(column, "");
                    out.append(' ').append(value.isEmpty() ? "—" : value).append(" |");
                }
                out.append("\n");
            }
            return out.append(after).toString();
        }

        private static List<String> cells(String line) {
            String trimmed = line.trim();
            if (trimmed.startsWith("|")) {
                trimmed = trimmed.substring(1);
            }
            if (trimmed.endsWith("|")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            List<String> cells = new ArrayList<>();
            for (String cell : trimmed.split("\\|", -1)) {
                cells.add(cell.trim());
            }
            return cells;
        }
    }
}
