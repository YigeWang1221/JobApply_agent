package jobapply.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import jobapply.domain.Timestamps;

/**
 * {@code browser/sites.md}：站点登录登记，每个站点 origin 一行。不是生成文件，以后可能有人工补的列，
 * 所以读的时候保留未识别的列和表格前后的内容。账号标识可选、由用户自己填写，原样保存；不写密码。
 */
public final class SiteRegistry {

    static final String SITE = "站点";
    static final String ACCOUNT = "账号标识";
    static final String CONFIRMED_AT = "最近确认登录";
    static final String STATUS = "状态";
    static final String METHOD = "核对方式";
    private static final List<String> KNOWN_COLUMNS = List.of(SITE, ACCOUNT, CONFIRMED_AT, STATUS, METHOD);
    private static final String HEADING = "# 站点登录登记\n\n";

    private final StorageLayout layout;
    private final Clock clock;

    public SiteRegistry(StorageLayout layout, Clock clock) {
        this.layout = layout;
        this.clock = clock;
    }

    /**
     * 记下某个站点已确认登录。同一 origin 更新原有的行，不新增。
     *
     * @param accountHint 用户填写的账号标识，可以为空（写 {@code —}）
     * @param method      {@code matched}（页面上找到了标识）或 {@code user_confirmed}
     */
    public synchronized void recordLogin(String origin, String accountHint, String method) {
        if (origin == null || origin.isBlank() || !isCellSafe(origin)) {
            throw new IllegalArgumentException("站点 origin 不合法：" + origin);
        }
        String hint = accountHint == null || accountHint.isBlank() ? "—" : accountHint.trim();
        if (!isCellSafe(hint)) {
            throw new IllegalArgumentException("账号标识不能含换行或竖线");
        }
        Path file = layout.browserSites();
        Table table = Files.exists(file) ? Table.parse(AtomicFiles.read(file)) : Table.empty();
        Map<String, String> row = table.rows.computeIfAbsent(origin, key -> new LinkedHashMap<>());
        row.put(SITE, origin);
        row.put(ACCOUNT, hint);
        row.put(CONFIRMED_AT, Timestamps.now(clock));
        row.put(STATUS, "logged_in");
        row.put(METHOD, method);
        AtomicFiles.write(file, table.render());
    }

    /** 读出全部行（列名 → 值），按 origin 排序。 */
    public List<Map<String, String>> rows() {
        Path file = layout.browserSites();
        if (!Files.exists(file)) {
            return List.of();
        }
        return List.copyOf(Table.parse(AtomicFiles.read(file)).rows.values());
    }

    private static boolean isCellSafe(String value) {
        return value.indexOf('|') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0;
    }

    /** 表格之前的内容、列、行、表格之后的内容。 */
    private static final class Table {

        private final String before;
        private final List<String> columns;
        private final TreeMap<String, Map<String, String>> rows;
        private final String after;

        private Table(String before, List<String> columns, TreeMap<String, Map<String, String>> rows, String after) {
            this.before = before;
            this.columns = columns;
            this.rows = rows;
            this.after = after;
        }

        static Table empty() {
            return new Table(HEADING, new ArrayList<>(KNOWN_COLUMNS), new TreeMap<>(), "");
        }

        static Table parse(String content) {
            List<String> lines = content.replace("\r\n", "\n").lines().toList();
            int start = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("|") && cells(lines.get(i)).contains(SITE)) {
                    start = i;
                    break;
                }
            }
            if (start < 0) {
                Table table = empty();
                return new Table(content.isBlank() ? HEADING : content.stripTrailing() + "\n\n", table.columns,
                        table.rows, "");
            }
            int end = start;
            while (end < lines.size() && lines.get(end).startsWith("|")) {
                end++;
            }
            List<String> columns = new ArrayList<>(KNOWN_COLUMNS);
            List<String> header = cells(lines.get(start));
            for (String column : header) {
                if (!columns.contains(column)) {
                    columns.add(column);
                }
            }
            TreeMap<String, Map<String, String>> rows = new TreeMap<>();
            for (int i = start + 2; i < end; i++) {
                List<String> values = cells(lines.get(i));
                Map<String, String> row = new LinkedHashMap<>();
                for (int c = 0; c < header.size(); c++) {
                    row.put(header.get(c), c < values.size() ? values.get(c) : "");
                }
                String site = row.getOrDefault(SITE, "");
                if (!site.isEmpty()) {
                    rows.put(site, row);
                }
            }
            String before = String.join("\n", lines.subList(0, start));
            String after = String.join("\n", lines.subList(end, lines.size()));
            return new Table(before.isEmpty() ? "" : before + "\n", columns, rows,
                    after.isBlank() ? "" : "\n" + after + "\n");
        }

        String render() {
            StringBuilder out = new StringBuilder(before);
            out.append("| ").append(String.join(" | ", columns)).append(" |\n");
            out.append("|").append(" --- |".repeat(columns.size())).append("\n");
            for (Map<String, String> row : rows.values()) {
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
