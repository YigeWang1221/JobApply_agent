package jobapply.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jobapply.domain.Fact;
import jobapply.domain.FactClass;
import jobapply.domain.Language;

/**
 * {@code facts.md} / {@code overrides.md} 的事实条目：{@code ## <key>} 下面跟 {@code - 字段: 值}。
 * 格式见 plan/master/memory.md「事实条目格式」。C 级条目读写都会报错。
 * 文字字段成对写成 {@code - zh:} / {@code - en:}，与 {@code - value:} 二选一；只有 {@code value} 的旧文件照常读。
 */
public final class FactsFile {

    private static final String VALID = "valid_from / valid_to";
    private static final String NONE = "—";
    private static final Set<String> FIELDS = Set.of(
            "value", "zh", "en", "class", "source", "claim", "reviewed", "confirmed_at", VALID, "precision", "notes",
            "unset");

    private FactsFile() {
    }

    /** 文件不存在时返回空列表。 */
    public static List<Fact> read(Path path) {
        return Files.exists(path) ? parse(AtomicFiles.read(path), path.toString()) : List.of();
    }

    public static List<Fact> parse(String content, String source) {
        String body = FrontmatterFile.parse(content, source).body();
        List<Fact> facts = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        String key = null;
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : body.split("\n", -1)) {
            if (line.startsWith("## ")) {
                if (key != null) {
                    facts.add(toFact(key, fields, source));
                }
                key = line.substring(3).trim();
                if (!keys.add(key)) {
                    throw new StorageFormatException(source, "事实条目重复：" + key);
                }
                fields = new LinkedHashMap<>();
            } else if (key != null && line.startsWith("- ")) {
                int colon = line.indexOf(": ");
                String name = colon < 0 ? line.substring(2).trim() : line.substring(2, colon).trim();
                if (!FIELDS.contains(name)) {
                    throw new StorageFormatException(source, key + " 有未知字段：" + name);
                }
                fields.put(name, colon < 0 ? "" : line.substring(colon + 2).trim());
            }
        }
        if (key != null) {
            facts.add(toFact(key, fields, source));
        }
        return facts;
    }

    public static void write(Path path, String title, List<Fact> facts) {
        AtomicFiles.write(path, FrontmatterFile.render(new FrontmatterFile.Document(
                Map.of("tags", List.of("jobapply/facts")), "\n# " + title + "\n\n" + render(facts))));
    }

    public static String render(List<Fact> facts) {
        Set<String> keys = new HashSet<>();
        for (Fact fact : facts) {
            if (!keys.add(fact.key())) {
                throw new IllegalArgumentException("事实条目重复：" + fact.key());
            }
        }
        StringBuilder out = new StringBuilder();
        for (Fact fact : facts) {
            out.append("## ").append(fact.key()).append('\n');
            if (fact.unset()) {
                out.append("- unset: true\n");
            } else {
                field(out, "value", fact.value());
                field(out, "zh", fact.zh());
                field(out, "en", fact.en());
                field(out, "class", fact.factClass().name());
            }
            field(out, "source", fact.source());
            field(out, "claim", fact.claim());
            if (!fact.reviewed().isEmpty()) {
                field(out, "reviewed", String.join(", ", Arrays.stream(Language.values())
                        .filter(fact.reviewed()::contains).map(Language::code).toList()));
            }
            field(out, "confirmed_at", fact.confirmedAt());
            if (fact.validFrom() != null || fact.validTo() != null) {
                field(out, VALID, orNone(fact.validFrom()) + " / " + orNone(fact.validTo()));
            }
            field(out, "precision", fact.precision());
            field(out, "notes", fact.notes());
            out.append('\n');
        }
        return out.toString();
    }

    private static Fact toFact(String key, Map<String, String> fields, String source) {
        String classValue = fields.get("class");
        if ("C".equals(classValue)) {
            throw new StorageFormatException(source, "C 级字段不允许出现在文件里：" + key);
        }
        String validFrom = null;
        String validTo = null;
        String valid = fields.get(VALID);
        if (valid != null) {
            String[] parts = valid.split("/", 2);
            validFrom = noneToNull(parts[0].trim());
            validTo = parts.length > 1 ? noneToNull(parts[1].trim()) : null;
        }
        try {
            return new Fact(
                    key,
                    fields.get("value"),
                    fields.get("zh"),
                    fields.get("en"),
                    classValue == null ? null : FactClass.valueOf(classValue),
                    fields.get("source"),
                    fields.get("claim"),
                    languages(fields.get("reviewed")),
                    fields.get("confirmed_at"),
                    validFrom,
                    validTo,
                    fields.get("precision"),
                    fields.get("notes"),
                    "true".equals(fields.get("unset")));
        } catch (IllegalArgumentException e) {
            throw new StorageFormatException(source, e.getMessage(), e);
        }
    }

    private static Set<Language> languages(String value) {
        Set<Language> languages = EnumSet.noneOf(Language.class);
        if (value == null || value.isBlank() || value.equals(NONE)) {
            return languages;
        }
        for (String code : value.split(",")) {
            languages.add(Language.of(code));
        }
        return languages;
    }

    private static void field(StringBuilder out, String name, String value) {
        if (value == null) {
            return;
        }
        if (value.contains("\n")) {
            throw new IllegalArgumentException("事实条目的字段不能换行：" + name);
        }
        out.append("- ").append(name).append(": ").append(value).append('\n');
    }

    private static String orNone(String value) {
        return value == null ? NONE : value;
    }

    private static String noneToNull(String value) {
        return value.isEmpty() || value.equals(NONE) ? null : value;
    }
}
