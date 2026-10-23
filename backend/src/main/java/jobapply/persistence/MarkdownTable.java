package jobapply.persistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 正文里有一张 Markdown 表格的文件（台账、来源登记、术语表）。读写往返时保留表格前后的内容和未识别的列；
 * 空单元格写成「—」，读回来是空字符串。表格以表头里含 {@code anchor} 列的第一行为准。
 * 做法同 {@link AssetRegistry}（C004-A）。
 */
final class MarkdownTable {

    private static final String NONE = "—";

    final String before;
    final List<String> columns;
    final List<Map<String, String>> rows;
    final String after;

    private MarkdownTable(String before, List<String> columns, List<Map<String, String>> rows, String after) {
        this.before = before;
        this.columns = columns;
        this.rows = rows;
        this.after = after;
    }

    static MarkdownTable empty(String heading, List<String> known) {
        return new MarkdownTable(heading, new ArrayList<>(known), new ArrayList<>(), "");
    }

    static MarkdownTable parse(String content, String anchor, List<String> known, String heading) {
        List<String> lines = content.replace("\r\n", "\n").lines().toList();
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("|") && cells(lines.get(i)).contains(anchor)) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return new MarkdownTable(content.isBlank() ? heading : content.stripTrailing() + "\n\n",
                    new ArrayList<>(known), new ArrayList<>(), "");
        }
        int end = start;
        while (end < lines.size() && lines.get(end).startsWith("|")) {
            end++;
        }
        List<String> header = cells(lines.get(start));
        List<String> columns = new ArrayList<>(known);
        header.stream().filter(column -> !columns.contains(column)).forEach(columns::add);
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = start + 2; i < end; i++) {
            List<String> values = cells(lines.get(i));
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                String value = c < values.size() ? values.get(c) : "";
                row.put(header.get(c), NONE.equals(value) ? "" : value);
            }
            rows.add(row);
        }
        String before = String.join("\n", lines.subList(0, start));
        String after = String.join("\n", lines.subList(end, lines.size()));
        return new MarkdownTable(before.isEmpty() ? "" : before + "\n", columns, rows,
                after.isBlank() ? "" : "\n" + after + "\n");
    }

    String render() {
        StringBuilder out = new StringBuilder(before);
        out.append("| ").append(String.join(" | ", columns)).append(" |\n");
        out.append("|").append(" --- |".repeat(columns.size())).append("\n");
        for (Map<String, String> row : rows) {
            out.append("|");
            for (String column : columns) {
                String value = row.getOrDefault(column, "");
                requireCell(column, value);
                out.append(' ').append(value == null || value.isEmpty() ? NONE : value).append(" |");
            }
            out.append("\n");
        }
        return out.append(after).toString();
    }

    /** 单元格里不能有换行和竖线（会把表格拆坏）。 */
    static void requireCell(String column, String value) {
        if (value != null && (value.indexOf('|') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)) {
            throw new IllegalArgumentException("「" + column + "」不能含换行或竖线");
        }
    }

    /** 已知列之外的列（列名 → 值）。 */
    static Map<String, String> extra(Map<String, String> row, List<String> known) {
        Map<String, String> extra = new LinkedHashMap<>(row);
        known.forEach(extra::remove);
        return extra;
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
