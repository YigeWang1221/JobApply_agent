package jobapply.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import jobapply.domain.Ids;

/**
 * {@code base/evidence/<来源键>.md}：一个来源的清单卡（这个来源里有什么、涉及哪些项目和经历）。
 * 由 C004-A-2 的整理生成，可以重新生成；这里只负责读写。frontmatter 记来源键、读取时的版本和生成时间，
 * 未识别的键原样保留；正文是 Markdown，只放出处和简短摘要，不放大段原文。
 */
public final class EvidenceCardFile {

    /**
     * @param extra 未识别的 frontmatter 键
     */
    public record EvidenceCard(String sourceKey, String sourceVersion, String generatedAt, Map<String, Object> extra,
                               String body) {

        public EvidenceCard {
            Ids.requireSourceKey(sourceKey);
            extra = extra == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(extra));
            body = body == null ? "" : body;
        }
    }

    private static final List<String> KNOWN = List.of("source", "source_version", "generated_at");

    private final StorageLayout layout;

    public EvidenceCardFile(StorageLayout layout, Clock clock) {
        this.layout = layout;
    }

    public Optional<EvidenceCard> find(String sourceKey) {
        Path path = layout.evidenceCard(sourceKey);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        FrontmatterFile.Document document = FrontmatterFile.read(path);
        Map<String, Object> fm = document.frontmatter();
        Map<String, Object> extra = new LinkedHashMap<>(fm);
        KNOWN.forEach(extra::remove);
        String key = fm.get("source") == null ? sourceKey : String.valueOf(fm.get("source"));
        if (!key.equals(sourceKey)) {
            throw new StorageFormatException(path.toString(), "frontmatter 的 source 和文件名不一致：" + key);
        }
        return Optional.of(new EvidenceCard(key, string(fm.get("source_version")), string(fm.get("generated_at")),
                extra, document.body()));
    }

    public synchronized void save(EvidenceCard card) {
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("source", card.sourceKey());
        if (card.sourceVersion() != null) {
            fm.put("source_version", card.sourceVersion());
        }
        if (card.generatedAt() != null) {
            fm.put("generated_at", card.generatedAt());
        }
        fm.putAll(card.extra());
        Path path = layout.evidenceCard(card.sourceKey());
        FrontmatterFile.write(path, new FrontmatterFile.Document(fm, card.body()));
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
