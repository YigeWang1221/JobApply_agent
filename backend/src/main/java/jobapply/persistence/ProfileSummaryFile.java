package jobapply.persistence;

import java.nio.file.Files;
import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import jobapply.domain.Language;
import jobapply.domain.ProjectCard;
import jobapply.domain.ProjectCard.Section;

/**
 * {@code base/profile.md}：画像层，由事实层推出，用户可改。中英双语，固定四节（C004-A-2「画像的小节」）：
 * {@code directions}（方向）、{@code skills}（技能分组）、{@code highlights}（亮点）、{@code positioning}（定位）。
 * 生成画像在 C004-A-2；这里只负责读写和小节对应的检查。{@code user_edited} 记用户手改过的小节
 * （写成 {@code zh.highlights}），重新生成时不覆盖。未识别的 frontmatter 键和两个语言块以外的内容原样保留。
 */
public final class ProfileSummaryFile {

    public static final List<String> SECTIONS = List.of("directions", "skills", "highlights", "positioning");
    private static final List<String> KNOWN = List.of("generated_at", "user_edited", "reviewed");

    /**
     * @param preamble 第一个语言块之前的内容
     * @param trailing 两个语言块以外的 {@code ## …} 块
     * @param extra    未识别的 frontmatter 键
     */
    public record ProfileSummary(String generatedAt, List<String> userEdited, Set<Language> reviewed,
                                 List<Section> zh, List<Section> en, String preamble, String trailing,
                                 Map<String, Object> extra) {

        public ProfileSummary {
            userEdited = userEdited == null ? List.of() : List.copyOf(userEdited);
            reviewed = reviewed == null ? Set.of() : Set.copyOf(reviewed);
            zh = zh == null ? List.of() : List.copyOf(zh);
            en = en == null ? List.of() : List.copyOf(en);
            preamble = preamble == null ? "" : preamble;
            trailing = trailing == null ? "" : trailing;
            extra = extra == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(extra));
            ProjectCard.requireCorresponding(zh, en, "画像");
        }
    }

    private final StorageLayout layout;

    public ProfileSummaryFile(StorageLayout layout, Clock clock) {
        this.layout = layout;
    }

    public Optional<ProfileSummary> read() {
        if (!Files.exists(layout.baseProfile())) {
            return Optional.empty();
        }
        String source = layout.baseProfile().toString();
        FrontmatterFile.Document document = FrontmatterFile.parse(AtomicFiles.read(layout.baseProfile()), source);
        BilingualSections.Body body = BilingualSections.parse(document.body(), source);
        Map<String, Object> fm = document.frontmatter();
        Map<String, Object> extra = new LinkedHashMap<>(fm);
        KNOWN.forEach(extra::remove);
        try {
            List<String> edited = fm.get("user_edited") instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList() : List.of();
            return Optional.of(new ProfileSummary(fm.get("generated_at") == null ? null
                    : String.valueOf(fm.get("generated_at")), edited, ProjectCardFile.languages(fm.get("reviewed")),
                    body.get(Language.ZH), body.get(Language.EN), body.preamble(), body.trailing(), extra));
        } catch (IllegalArgumentException e) {
            throw new StorageFormatException(source, e.getMessage(), e);
        }
    }

    public synchronized void save(ProfileSummary summary) {
        Map<String, Object> fm = new LinkedHashMap<>();
        if (summary.generatedAt() != null) {
            fm.put("generated_at", summary.generatedAt());
        }
        if (!summary.userEdited().isEmpty()) {
            fm.put("user_edited", summary.userEdited());
        }
        if (!summary.reviewed().isEmpty()) {
            fm.put("reviewed", ProjectCardFile.codes(summary.reviewed()));
        }
        fm.putAll(summary.extra());
        AtomicFiles.write(layout.baseProfile(), FrontmatterFile.render(new FrontmatterFile.Document(fm,
                BilingualSections.render(summary.preamble(), summary.zh(), summary.en(), summary.trailing()))));
    }
}
