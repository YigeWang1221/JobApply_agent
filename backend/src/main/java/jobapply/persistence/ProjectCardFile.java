package jobapply.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import jobapply.domain.Language;
import jobapply.domain.ProjectCard;
import jobapply.domain.ProjectCard.CardFact;
import jobapply.domain.ProjectCard.Link;
import jobapply.domain.ProjectCard.Period;
import jobapply.domain.ProjectCard.Text;

/**
 * {@code base/projects/<slug>.md}：项目卡。frontmatter 是与语言无关的字段，正文是 {@code ## zh} / {@code ## en}
 * 两块小节（格式见 plan/coding/C004-A-1-profile-import.md「项目卡」）。未识别的 frontmatter 键原样保留；
 * 正文里两个语言块以外的内容会被拒绝（项目卡只放这两块）。保存前检查小节对应、{@code {{fact:}}} 和论断引用。
 */
public final class ProjectCardFile {

    private static final List<String> KNOWN = List.of("slug", "aliases", "title", "kind", "period", "team_size",
            "role_claim", "stack", "links", "facts", "sources", "reviewed");

    private final StorageLayout layout;
    private final StorageGit git;

    public ProjectCardFile(StorageLayout layout, Clock clock) {
        this.layout = layout;
        this.git = new StorageGit(layout, clock);
    }

    public List<ProjectCard> list() {
        return layout.projectCardSlugs().stream().map(slug -> find(slug).orElseThrow()).toList();
    }

    public Optional<ProjectCard> find(String slug) {
        Path path = layout.baseProjectCard(slug);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        ProjectCard card = parse(AtomicFiles.read(path), path.toString());
        if (!card.slug().equals(slug)) {
            throw new StorageFormatException(path.toString(), "frontmatter 的 slug 和文件名不一致：" + card.slug());
        }
        return Optional.of(card);
    }

    /**
     * 写入一张卡（新建或替换）。
     *
     * @param claimIds 台账里已有的论断 id，用来检查卡里填了的引用
     */
    public synchronized void save(ProjectCard card, Set<String> claimIds) {
        card.requireKnownClaims(claimIds);
        Path path = layout.baseProjectCard(card.slug());
        AtomicFiles.write(path, render(card));
        git.commit("base：项目卡 " + card.slug(), List.of(path));
    }

    public static ProjectCard parse(String content, String source) {
        FrontmatterFile.Document document = FrontmatterFile.parse(content, source);
        BilingualSections.Body body = BilingualSections.parse(document.body(), source);
        if (!body.preamble().isBlank() || !body.trailing().isBlank()) {
            throw new StorageFormatException(source, "项目卡的正文只能有 ## zh 和 ## en 两块");
        }
        Map<String, Object> fm = document.frontmatter();
        try {
            Map<String, Object> extra = new LinkedHashMap<>(fm);
            KNOWN.forEach(extra::remove);
            return new ProjectCard(
                    string(fm.get("slug")),
                    strings(fm.get("aliases")),
                    text(fm.get("title")),
                    string(fm.get("kind")),
                    period(fm.get("period")),
                    fm.get("team_size") == null ? null : Integer.valueOf(string(fm.get("team_size"))),
                    string(fm.get("role_claim")),
                    strings(fm.get("stack")),
                    links(fm.get("links")),
                    facts(fm.get("facts")),
                    strings(fm.get("sources")),
                    languages(fm.get("reviewed")),
                    body.get(Language.ZH),
                    body.get(Language.EN),
                    extra);
        } catch (IllegalArgumentException | ClassCastException e) {
            throw new StorageFormatException(source, e.getMessage(), e);
        }
    }

    public static String render(ProjectCard card) {
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("slug", card.slug());
        put(fm, "aliases", card.aliases().isEmpty() ? null : card.aliases());
        put(fm, "title", text(card.title()));
        put(fm, "kind", card.kind());
        if (card.period() != null) {
            Map<String, Object> period = new LinkedHashMap<>();
            put(period, "from", card.period().from());
            put(period, "to", card.period().to());
            put(period, "precision", card.period().precision());
            fm.put("period", period);
        }
        put(fm, "team_size", card.teamSize());
        put(fm, "role_claim", card.roleClaim());
        put(fm, "stack", card.stack().isEmpty() ? null : card.stack());
        if (!card.links().isEmpty()) {
            List<Map<String, Object>> links = new ArrayList<>();
            for (Link link : card.links()) {
                Map<String, Object> item = new LinkedHashMap<>();
                put(item, "kind", link.kind());
                put(item, "url", link.url());
                links.add(item);
            }
            fm.put("links", links);
        }
        if (!card.facts().isEmpty()) {
            Map<String, Object> facts = new LinkedHashMap<>();
            card.facts().forEach((name, fact) -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("value", fact.value());
                put(item, "unit", fact.unit());
                put(item, "condition", text(fact.condition()));
                put(item, "claim", fact.claim());
                facts.put(name, item);
            });
            fm.put("facts", facts);
        }
        put(fm, "sources", card.sources().isEmpty() ? null : card.sources());
        put(fm, "reviewed", card.reviewed().isEmpty() ? null : codes(card.reviewed()));
        fm.putAll(card.extra());
        return FrontmatterFile.render(new FrontmatterFile.Document(fm,
                BilingualSections.render(null, card.zh(), card.en(), null)));
    }

    static List<String> codes(Set<Language> languages) {
        return EnumSet.copyOf(languages).stream().map(Language::code).toList();
    }

    static Set<Language> languages(Object value) {
        Set<Language> languages = EnumSet.noneOf(Language.class);
        for (String code : strings(value)) {
            languages.add(Language.of(code));
        }
        return languages;
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> strings(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("应该是列表：" + value);
        }
        return list.stream().map(String::valueOf).toList();
    }

    private static Map<?, ?> map(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("应该是键值对：" + value);
        }
        return map;
    }

    private static Text text(Object value) {
        if (value == null) {
            return null;
        }
        Map<?, ?> map = map(value);
        return new Text(string(map.get("zh")), string(map.get("en")));
    }

    private static Map<String, Object> text(Text text) {
        if (text == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        put(map, "zh", text.zh());
        put(map, "en", text.en());
        return map;
    }

    private static Period period(Object value) {
        if (value == null) {
            return null;
        }
        Map<?, ?> map = map(value);
        return new Period(string(map.get("from")), string(map.get("to")), string(map.get("precision")));
    }

    private static List<Link> links(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("links 应该是列表");
        }
        return list.stream().map(item -> {
            Map<?, ?> map = map(item);
            return new Link(string(map.get("kind")), string(map.get("url")));
        }).toList();
    }

    private static Map<String, CardFact> facts(Object value) {
        Map<String, CardFact> facts = new LinkedHashMap<>();
        if (value == null) {
            return facts;
        }
        map(value).forEach((name, item) -> {
            Map<?, ?> map = map(item);
            facts.put(String.valueOf(name), new CardFact(string(map.get("value")), string(map.get("unit")),
                    text(map.get("condition")), string(map.get("claim"))));
        });
        return facts;
    }
}
