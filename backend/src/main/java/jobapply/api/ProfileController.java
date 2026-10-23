package jobapply.api;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import jobapply.domain.EvidenceTier;
import jobapply.domain.Fact;
import jobapply.domain.Language;
import jobapply.domain.ProjectCard;
import jobapply.domain.ProjectCard.Section;
import jobapply.memory.FactWriter;
import jobapply.memory.ProfileSummaryBuilder;
import jobapply.persistence.FactsFile;
import jobapply.persistence.ProfileSummaryFile;
import jobapply.persistence.ProjectCardFile;
import jobapply.persistence.SourceRegistry;
import jobapply.persistence.StorageLayout;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「事实与画像」：事实层按组浏览，项目卡中英对照修改、标记已审阅，生成和确认画像（C004-A-2）。
 * 语言在接口里一律写成 {@code zh} / {@code en}。
 */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    /**
     * @param reviewed 标为已审阅的语言；不写时，改过文字的语言记为已审阅
     */
    public record FactEdit(String value, String zh, String en, List<String> reviewed) {
    }

    /**
     * @param zh 小节 id → 文字；没写的小节不变
     */
    public record ProjectEdit(Map<String, String> title, Map<String, String> zh, Map<String, String> en,
                              List<String> reviewed) {
    }

    /**
     * @param userEdited 用户手改过的小节（{@code zh.highlights}）
     */
    public record SummaryConfirm(Map<String, String> zh, Map<String, String> en, List<String> userEdited,
                                 List<String> reviewed) {
    }

    private final StorageLayout layout;
    private final ProjectCardFile cards;
    private final ProfileSummaryFile summaries;
    private final SourceRegistry sources;
    private final FactWriter writer;
    private final ProfileSummaryBuilder builder;

    public ProfileController(StorageLayout layout, ProjectCardFile cards, ProfileSummaryFile summaries,
                             SourceRegistry sources, FactWriter writer, ProfileSummaryBuilder builder) {
        this.layout = layout;
        this.cards = cards;
        this.summaries = summaries;
        this.sources = sources;
        this.writer = writer;
        this.builder = builder;
    }

    /** 事实层（按组）、项目卡、画像。 */
    @GetMapping
    public Map<String, Object> profile() {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (EvidenceTier tier : EvidenceTier.values()) {
            groups.put(tier.name().toLowerCase(Locale.ROOT), new ArrayList<>());
        }
        for (Fact fact : FactsFile.read(layout.baseFacts())) {
            if (!fact.unset()) {
                groups.get(EvidenceTier.of(fact.key()).name().toLowerCase(Locale.ROOT)).add(fact(fact));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("groups", groups);
        result.put("projects", cards.list().stream().map(ProfileController::card).toList());
        result.put("summary", summaries.read().map(ProfileController::summary).orElse(null));
        return result;
    }

    @PutMapping(path = "/facts/{key}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> editFact(@PathVariable String key, @RequestBody FactEdit edit) {
        return fact(writer.editFact(key, edit.value(), edit.zh(), edit.en(), languages(edit.reviewed())));
    }

    @PutMapping(path = "/projects/{slug}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> editProject(@PathVariable String slug, @RequestBody ProjectEdit edit) {
        Map<Language, Map<String, String>> sections = new EnumMap<>(Language.class);
        if (edit.zh() != null) {
            sections.put(Language.ZH, edit.zh());
        }
        if (edit.en() != null) {
            sections.put(Language.EN, edit.en());
        }
        ProjectCard.Text title = edit.title() == null ? null
                : new ProjectCard.Text(edit.title().get("zh"), edit.title().get("en"));
        return card(writer.editProject(slug, title, sections, languages(edit.reviewed())));
    }

    /** 生成画像候选（不写文件）。 */
    @PostMapping(path = "/summary", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> generate() {
        ProfileSummaryBuilder.Candidate candidate = builder.generate();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("current", candidate.current() == null ? null : summary(candidate.current()));
        result.put("zh", sections(candidate.zh()));
        result.put("en", sections(candidate.en()));
        result.put("suggestions", candidate.suggestions());
        result.put("userEdited", candidate.userEdited());
        result.put("warnings", candidate.warnings());
        return result;
    }

    /** 确认画像，替换 {@code profile.md}。 */
    @PutMapping(path = "/summary", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> confirm(@RequestBody SummaryConfirm body) {
        Set<Language> reviewed = languages(body.reviewed());
        return summary(builder.confirm(body.zh(), body.en(), body.userEdited(),
                reviewed == null ? Set.of() : reviewed));
    }

    private Map<String, Object> fact(Fact fact) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("key", fact.key());
        view.put("value", fact.value());
        view.put("zh", fact.zh());
        view.put("en", fact.en());
        view.put("class", fact.factClass().name());
        view.put("source", fact.source());
        view.put("sourceDeleted", fact.source() != null && fact.source().startsWith("source:")
                && sources.status(fact.source().substring(7)) == SourceRegistry.Status.DELETED);
        view.put("claim", fact.claim());
        view.put("reviewed", codes(fact.reviewed()));
        view.put("confirmedAt", fact.confirmedAt());
        return view;
    }

    private static Map<String, Object> card(ProjectCard card) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("slug", card.slug());
        view.put("title", card.title());
        view.put("aliases", card.aliases());
        view.put("kind", card.kind());
        view.put("period", card.period());
        view.put("teamSize", card.teamSize());
        view.put("stack", card.stack());
        view.put("links", card.links());
        view.put("sources", card.sources());
        view.put("roleClaim", card.roleClaim());
        view.put("reviewed", codes(card.reviewed()));
        view.put("zh", sections(card.zh()));
        view.put("en", sections(card.en()));
        return view;
    }

    private static Map<String, Object> summary(ProfileSummaryFile.ProfileSummary summary) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("generatedAt", summary.generatedAt());
        view.put("userEdited", summary.userEdited());
        view.put("reviewed", codes(summary.reviewed()));
        view.put("zh", sections(summary.zh()));
        view.put("en", sections(summary.en()));
        return view;
    }

    private static List<Map<String, String>> sections(List<Section> sections) {
        return sections.stream().map(section -> Map.of("id", section.id(), "heading", section.heading(),
                "text", section.text())).toList();
    }

    private static List<String> codes(Set<Language> languages) {
        return languages.isEmpty() ? List.of() : EnumSet.copyOf(languages).stream().map(Language::code).toList();
    }

    /** 接口里的语言列表；null 表示没写。 */
    private static Set<Language> languages(List<String> codes) {
        if (codes == null) {
            return null;
        }
        Set<Language> languages = EnumSet.noneOf(Language.class);
        codes.forEach(code -> languages.add(Language.of(code)));
        return languages;
    }
}
