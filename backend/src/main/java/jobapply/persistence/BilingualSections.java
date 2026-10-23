package jobapply.persistence;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jobapply.domain.Language;
import jobapply.domain.ProjectCard.Section;

/**
 * 中英双语正文：{@code ## zh}、{@code ## en} 两块，每块下面是 {@code ### 标题 {#id}} 小节（项目卡、画像共用）。
 * 第一个语言块之前的内容是 {@code preamble}；其它 {@code ## …} 块原样放进 {@code trailing}。
 */
final class BilingualSections {

    private static final Pattern SECTION = Pattern.compile("### (.*?)\\s*\\{#([^}]*)}\\s*");

    record Body(String preamble, Map<Language, List<Section>> sections, String trailing) {

        List<Section> get(Language language) {
            return sections.getOrDefault(language, List.of());
        }
    }

    private BilingualSections() {
    }

    static Body parse(String body, String source) {
        StringBuilder preamble = new StringBuilder();
        StringBuilder trailing = new StringBuilder();
        Map<Language, List<Section>> sections = new EnumMap<>(Language.class);
        Language current = null;
        boolean inTrailing = false;
        String id = null;
        String heading = null;
        StringBuilder text = new StringBuilder();
        for (String line : body.replace("\r\n", "\n").split("\n", -1)) {
            if (line.startsWith("## ")) {
                if (id != null) {
                    sections.get(current).add(new Section(id, heading, text.toString().strip()));
                    id = null;
                }
                String name = line.substring(3).strip();
                Language language = name.equals("zh") ? Language.ZH : name.equals("en") ? Language.EN : null;
                if (language != null && !inTrailing) {
                    if (sections.containsKey(language)) {
                        throw new StorageFormatException(source, "## " + name + " 出现了两次");
                    }
                    current = language;
                    sections.put(language, new ArrayList<>());
                    continue;
                }
                inTrailing = true;
            }
            if (inTrailing) {
                trailing.append(line).append('\n');
            } else if (current == null) {
                preamble.append(line).append('\n');
            } else if (line.startsWith("### ")) {
                if (id != null) {
                    sections.get(current).add(new Section(id, heading, text.toString().strip()));
                }
                Matcher matcher = SECTION.matcher(line);
                if (!matcher.matches()) {
                    throw new StorageFormatException(source, "小节标题缺少 {#id}：" + line);
                }
                heading = matcher.group(1).strip();
                id = matcher.group(2).strip();
                text = new StringBuilder();
            } else if (id != null) {
                text.append(line).append('\n');
            } else if (!line.isBlank()) {
                throw new StorageFormatException(source, "## " + current.code() + " 下面的文字要放在 ### 小节里");
            }
        }
        if (id != null) {
            sections.get(current).add(new Section(id, heading, text.toString().strip()));
        }
        try {
            return new Body(preamble.toString(), sections, trailing.toString());
        } catch (IllegalArgumentException e) {
            throw new StorageFormatException(source, e.getMessage(), e);
        }
    }

    static String render(String preamble, List<Section> zh, List<Section> en, String trailing) {
        StringBuilder out = new StringBuilder(preamble == null || preamble.isBlank() ? "\n" : preamble);
        if (!out.toString().endsWith("\n\n")) {
            out.append('\n');
        }
        block(out, Language.ZH, zh);
        block(out, Language.EN, en);
        if (trailing != null && !trailing.isBlank()) {
            out.append(trailing.stripTrailing()).append('\n');
        }
        return out.toString();
    }

    private static void block(StringBuilder out, Language language, List<Section> sections) {
        if (sections.isEmpty()) {
            return;
        }
        out.append("## ").append(language.code()).append("\n\n");
        for (Section section : sections) {
            if (section.heading().contains("\n") || section.heading().contains("{#")) {
                throw new IllegalArgumentException("小节标题不能换行，也不能含 {#：" + section.heading());
            }
            if (section.text().lines().anyMatch(line -> line.startsWith("## ") || line.startsWith("### "))) {
                throw new IllegalArgumentException("小节 " + section.id() + " 的文字里不能有 ## 或 ### 标题");
            }
            out.append("### ").append(section.heading()).append(" {#").append(section.id()).append("}\n");
            if (!section.text().isEmpty()) {
                out.append(section.text()).append('\n');
            }
            out.append('\n');
        }
    }
}
