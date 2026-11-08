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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import jobapply.domain.Ids;
import jobapply.domain.Job;
import jobapply.domain.Timestamps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code base/assets.md}：原件索引。只登记用户上传的原件（不登记副本、解析出的文本和日志）。
 * 原件原样保存在 {@code base/originals/<文件名>}；同一个 SHA-256 已经登记过的不重复保存；同名但内容不同时文件名加 -2、-3。
 * 读的时候保留未识别的列（用户以后可能手动加「适用方向」之类的列）和表格前后的内容。
 * <p>
 * C004-A-fix：每条有「分类」（{@link #RESUME 简历} / {@link #GENERAL 通用资料}）和「Project」。简历属于一个 Project
 * （空 = 未归属，Agent 看不到）；通用资料没有 Project，所有 Project 都看得到（{@link #visibleTo(String)}）。
 * 同一个文件在另一个 Project 再传一次就是那个 Project 自己的一条（去重按 SHA-256 和 Project 都相同）。
 * 没有这两列的旧文件照常读，启动时 {@link #upgrade()} 补上分类和归属。
 */
public final class AssetRegistry {

    static final String KEY = "键";
    static final String FILE = "文件名";
    static final String TYPE = "类型";
    static final String SIZE = "大小";
    static final String SHA = "SHA-256";
    static final String UPLOADED_AT = "上传时间";
    static final String DESCRIPTION = "说明";
    static final String CATEGORY = "分类";
    static final String PROJECT = "Project";
    /** 分类：简历（归属一个 Project）。 */
    public static final String RESUME = "简历";
    /** 分类：通用资料（不归属 Project，所有 Project 都看得到）。 */
    public static final String GENERAL = "通用资料";
    private static final List<String> BASE_COLUMNS = List.of(KEY, FILE, TYPE, SIZE, SHA, UPLOADED_AT, DESCRIPTION);
    /** 后加的两列排在已有列（包括用户自己加的）后面，旧文件的列顺序不变。 */
    private static final List<String> ADDED_COLUMNS = List.of(CATEGORY, PROJECT);
    private static final List<String> KNOWN_COLUMNS = List.of(KEY, FILE, TYPE, SIZE, SHA, UPLOADED_AT, DESCRIPTION,
            CATEGORY, PROJECT);
    /** 升级时按文件名 / 说明判断是不是简历。 */
    private static final Pattern RESUME_WORDS = Pattern.compile(
            "简历|履历|resume|résumé|curriculum[ _-]?vitae|(?<![a-z])cv(?![a-z])", Pattern.CASE_INSENSITIVE);
    private static final Logger LOG = LoggerFactory.getLogger(AssetRegistry.class);
    private static final String HEADING = "# 原件索引\n\n";
    static final int MAX_DESCRIPTION = 200;
    static final int MAX_FILE_NAME = 120;

    /**
     * 一条登记。
     *
     * @param category 简历 / 通用资料；旧数据升级前为空
     * @param project  简历归属的 Project；通用资料、未归属的简历为空
     * @param extra    未识别的列（列名 → 值）
     */
    public record Asset(String key, String fileName, String type, String size, String sha256, String uploadedAt,
                        String description, String category, String project, Map<String, String> extra) {

        public Asset {
            category = category == null ? "" : category;
            project = project == null ? "" : project;
            extra = extra == null ? Map.of() : Map.copyOf(extra);
        }

        public boolean resume() {
            return RESUME.equals(category);
        }

        /** 这个 Project 的 Agent 能不能看到：本 Project 的简历，或通用资料（分类为空的旧行也算通用资料）。 */
        public boolean visibleTo(String projectId) {
            return resume() ? !project.isEmpty() && project.equals(projectId) : true;
        }

        Asset with(String newDescription, String newCategory, String newProject) {
            return new Asset(key, fileName, type, size, sha256, uploadedAt, newDescription, newCategory, newProject,
                    extra);
        }
    }

    /** 登记结果：{@code duplicate} 为 true 时是已有的那一条，没有保存新文件。 */
    public record Registered(Asset asset, boolean duplicate) {
    }

    private final StorageLayout layout;
    private final Clock clock;

    public AssetRegistry(StorageLayout layout, Clock clock) {
        this.layout = layout;
        this.clock = clock;
    }

    public synchronized List<Asset> list() {
        return List.copyOf(table().assets.values());
    }

    public synchronized Optional<Asset> find(String key) {
        return Optional.ofNullable(table().assets.get(key));
    }

    /** 这个 Project 的 Agent 能看到的：本 Project 的简历 + 全部通用资料。 */
    public synchronized List<Asset> visibleTo(String projectId) {
        return table().assets.values().stream().filter(a -> a.visibleTo(projectId)).toList();
    }

    /** 这个 Project 能看到的一条；不存在或属于别的 Project 时为空。 */
    public Optional<Asset> findVisible(String projectId, String key) {
        return find(key).filter(a -> a.visibleTo(projectId));
    }

    /** 原件在磁盘上的位置。 */
    public Path originalPath(Asset asset) {
        Path path = layout.baseOriginals().resolve(asset.fileName()).normalize();
        if (!path.getParent().equals(layout.baseOriginals().normalize())) {
            throw new IllegalStateException("原件文件名不合法：" + asset.fileName());
        }
        return path;
    }

    /** 登记成通用资料（测试和旧调用方用）。 */
    public Registered register(String originalName, String type, byte[] content) {
        return register(originalName, type, content, GENERAL, "");
    }

    /**
     * 保存原件并登记，说明先留空（轻量分析之后再用 {@link #describe(String, String)} 写入）。
     *
     * @param originalName 上传时的文件名，只取最后一段
     * @param type         媒体类型（由调用方检测）
     * @param category     简历 / 通用资料；空时按文件名判断（{@link #guessCategory(String, String)}）
     * @param project      简历归属的 Project（可以为空 = 未归属）；通用资料忽略
     */
    public synchronized Registered register(String originalName, String type, byte[] content, String category,
                                            String project) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("文件是空的");
        }
        String kind = category == null || category.isBlank() ? guessCategory(originalName, "") : category(category);
        String owner = RESUME.equals(kind) ? project(project) : "";
        String sha = sha256(content);
        Table table = table();
        Optional<Asset> existing = table.assets.values().stream().filter(a -> a.sha256().equals(sha)
                && a.project().equals(owner)).findFirst();
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
                humanSize(content.length), sha, Timestamps.now(clock), "", kind, owner, Map.of());
        table.assets.put(key, asset);
        AtomicFiles.write(layout.baseAssets(), table.render());
        return new Registered(asset, false);
    }

    /** 修改说明（用户手改，或第一次上传时的轻量分析）。 */
    public Asset describe(String key, String description) {
        return update(key, description, null, null);
    }

    /**
     * 改说明、分类、归属（资料页）。参数为 null 的不改。改成通用资料时清掉 Project；
     * Project 写空字符串是「未归属」。
     */
    public synchronized Asset update(String key, String description, String category, String project) {
        if (!Ids.isAssetKey(key)) {
            throw new IllegalArgumentException("原件键不合法（应为 a-YYYY-MM-DD-NNN）");
        }
        Table table = table();
        Asset old = table.assets.get(key);
        if (old == null) {
            throw new NoSuchElementException("原件不存在：" + key);
        }
        String text = old.description();
        if (description != null) {
            text = description.strip();
            if (text.length() > MAX_DESCRIPTION || text.indexOf('|') >= 0 || text.indexOf('\n') >= 0
                    || text.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("说明不能超过 " + MAX_DESCRIPTION + " 个字，不能含换行或竖线");
            }
        }
        String kind = category == null ? old.category() : category(category);
        String owner = project == null ? old.project() : project(project);
        if (!RESUME.equals(kind)) {
            if (project != null && !owner.isEmpty()) {
                throw new IllegalArgumentException("通用资料不归属 Project；要归属请先把分类改成简历");
            }
            owner = "";
        }
        Asset next = old.with(text, kind, owner);
        table.assets.put(key, next);
        AtomicFiles.write(layout.baseAssets(), table.render());
        return next;
    }

    /**
     * 删除一条（C004-A-fix）：原件文件挪进回收站（{@link Trash#moveAsset}），再从 {@code base/assets.md} 删掉这一行。
     * 返回回收站里这次删除的目录。
     */
    public synchronized Path remove(String key, Trash trash) {
        if (!Ids.isAssetKey(key)) {
            throw new IllegalArgumentException("原件键不合法（应为 a-YYYY-MM-DD-NNN）");
        }
        Table table = table();
        Asset asset = table.assets.get(key);
        if (asset == null) {
            throw new NoSuchElementException("原件不存在：" + key);
        }
        Path entry = trash.moveAsset(asset, originalPath(asset), rowText(table, asset));
        table.assets.remove(key);
        AtomicFiles.write(layout.baseAssets(), table.render());
        return entry;
    }

    /**
     * 旧数据升级（C004-A-fix，启动时跑一次，幂等）：「分类」为空的行自动分类——被某个 Job 选为 {@code resume_choice}、
     * 或文件名 / 说明像简历的是简历，其余是通用资料；简历被恰好一个 Project 的 Job 用过（{@code resume_choice} 或
     * {@code materials}）就归那个 Project，否则留空（未归属）。已经有分类的行不动。返回改了几行。
     */
    public synchronized int upgrade() {
        Path file = layout.baseAssets();
        if (!Files.exists(file)) {
            return 0;
        }
        Table table = table();
        if (table.assets.values().stream().noneMatch(a -> a.category().isEmpty())) {
            return 0;
        }
        Map<String, Set<String>> chosen = new LinkedHashMap<>();
        Map<String, Set<String>> used = new LinkedHashMap<>();
        JobRepository jobs = new JobRepository(layout);
        for (String projectId : layout.projectIds()) {
            for (String jobId : layout.jobIds(projectId)) {
                Job job;
                try {
                    if (!jobs.exists(projectId, jobId)) {
                        continue;
                    }
                    job = jobs.load(projectId, jobId);
                } catch (RuntimeException e) {
                    LOG.warn("升级原件分类时读不了 {}/{}：{}", projectId, jobId, e.getMessage());
                    continue;
                }
                if (job.extra().get("resume_choice") instanceof Map<?, ?> choice
                        && choice.get("asset") instanceof String key) {
                    chosen.computeIfAbsent(key, k -> new TreeSet<>()).add(projectId);
                    used.computeIfAbsent(key, k -> new TreeSet<>()).add(projectId);
                }
                if (job.extra().get(JobMaterials.KEY) instanceof List<?> materials) {
                    for (Object item : materials) {
                        if (item instanceof Map<?, ?> m && m.get("asset") instanceof String key) {
                            used.computeIfAbsent(key, k -> new TreeSet<>()).add(projectId);
                        }
                    }
                }
            }
        }
        int changed = 0;
        for (Asset asset : List.copyOf(table.assets.values())) {
            if (!asset.category().isEmpty()) {
                continue;
            }
            String kind = chosen.containsKey(asset.key()) ? RESUME : guessCategory(asset.fileName(), asset.description());
            String owner = asset.project();
            if (RESUME.equals(kind) && owner.isEmpty()) {
                Set<String> projects = used.getOrDefault(asset.key(), Set.of());
                owner = projects.size() == 1 ? projects.iterator().next() : "";
            }
            table.assets.put(asset.key(), asset.with(asset.description(), kind, RESUME.equals(kind) ? owner : ""));
            changed++;
        }
        AtomicFiles.write(file, table.render());
        return changed;
    }

    /** 按文件名和说明猜分类：含 简历 / 履历 / resume / CV 的是简历，其余是通用资料。 */
    public static String guessCategory(String fileName, String description) {
        String text = (fileName == null ? "" : fileName) + " " + (description == null ? "" : description);
        return RESUME_WORDS.matcher(text).find() ? RESUME : GENERAL;
    }

    private static String category(String value) {
        String v = value.strip();
        if (!RESUME.equals(v) && !GENERAL.equals(v)) {
            throw new IllegalArgumentException("分类只能是「" + RESUME + "」或「" + GENERAL + "」");
        }
        return v;
    }

    private static String project(String value) {
        String v = value == null ? "" : value.strip();
        if (!v.isEmpty() && !Ids.isProjectId(v)) {
            throw new IllegalArgumentException("Project 不合法：" + v);
        }
        return v;
    }

    /** 这一行在表格里的样子（写进回收站的 RESTORE.md）。 */
    private static String rowText(Table table, Asset asset) {
        Table one = new Table("", table.columns, new TreeMap<>(Map.of(asset.key(), asset)), "");
        return one.render().strip();
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
            List<String> columns = new ArrayList<>(BASE_COLUMNS);
            header.stream().filter(column -> !columns.contains(column)).forEach(columns::add);
            ADDED_COLUMNS.stream().filter(column -> !columns.contains(column)).forEach(columns::add);
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
                        row.getOrDefault(DESCRIPTION, ""), row.getOrDefault(CATEGORY, ""),
                        row.getOrDefault(PROJECT, ""), extra));
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
                row.put(CATEGORY, asset.category());
                row.put(PROJECT, asset.project());
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
