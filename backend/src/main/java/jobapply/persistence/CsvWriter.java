package jobapply.persistence;

import java.util.List;

/**
 * 给 Excel 直接打开的 csv：UTF-8 带 BOM、CRLF、RFC 4180 转义，并防止公式注入。
 */
final class CsvWriter {

    private static final String BOM = "﻿";
    private static final String CRLF = "\r\n";

    private CsvWriter() {
    }

    static String render(List<String> header, List<List<String>> rows) {
        StringBuilder out = new StringBuilder(BOM);
        appendRow(out, header);
        for (List<String> row : rows) {
            appendRow(out, row);
        }
        return out.toString();
    }

    static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String text = startsLikeFormula(value) ? "'" + value : value;
        boolean quote = text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")
                || text.startsWith(" ") || text.endsWith(" ");
        return quote ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
    }

    private static boolean startsLikeFormula(String value) {
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r';
    }

    private static void appendRow(StringBuilder out, List<String> row) {
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(cell(row.get(i)));
        }
        out.append(CRLF);
    }
}
