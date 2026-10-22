package jobapply.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Playwright MCP 返回文本的解析结果。返回文本是 Markdown，分成 {@code ### Page}、{@code ### Snapshot}、
 * {@code ### Modal state} 等小节（@playwright/mcp 0.0.83）。只依赖小节标题，缺少 Page 小节时报错，不猜。
 * 快照里可能有个人信息，只在内存里使用，不写入 Checkpoint 和日志。
 */
public record PageSnapshot(String url, String title, String snapshot, String modalState) {

    public static PageSnapshot parse(String text) {
        Map<String, String> sections = sections(text == null ? "" : text);
        String page = sections.get("Page");
        if (page == null) {
            throw new BrowserException.ToolFailed("返回内容里没有 Page 小节，无法确认当前页面");
        }
        String url = null;
        String title = "";
        for (String line : page.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- Page URL:")) {
                url = trimmed.substring("- Page URL:".length()).trim();
            } else if (trimmed.startsWith("- Page Title:")) {
                title = trimmed.substring("- Page Title:".length()).trim();
            }
        }
        if (url == null || url.isEmpty()) {
            throw new BrowserException.ToolFailed("Page 小节里没有 Page URL");
        }
        return new PageSnapshot(url, title, stripFence(sections.getOrDefault("Snapshot", "")),
                sections.getOrDefault("Modal state", "").trim());
    }

    /** 页面上可见的全部文字（标题 + 快照），供识别与核对使用。 */
    public String visibleText() {
        return title + "\n" + snapshot;
    }

    private static Map<String, String> sections(String text) {
        Map<String, String> sections = new LinkedHashMap<>();
        String current = null;
        List<String> lines = new ArrayList<>();
        for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
            if (line.startsWith("### ")) {
                if (current != null) {
                    sections.put(current, String.join("\n", lines));
                }
                current = line.substring(4).trim();
                lines = new ArrayList<>();
            } else if (current != null) {
                lines.add(line);
            }
        }
        if (current != null) {
            sections.put(current, String.join("\n", lines));
        }
        return sections;
    }

    private static String stripFence(String body) {
        List<String> kept = new ArrayList<>();
        for (String line : body.split("\n", -1)) {
            if (!line.trim().startsWith("```")) {
                kept.add(line);
            }
        }
        return String.join("\n", kept).trim();
    }
}
