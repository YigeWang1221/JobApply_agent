package jobapply.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import jobapply.domain.EvidenceTier;
import jobapply.domain.Fact;
import jobapply.domain.ImportCandidate;
import jobapply.domain.ImportCandidate.Alternative;
import jobapply.domain.ImportCandidate.Origin;
import jobapply.domain.Language;
import jobapply.domain.ProjectCard;
import jobapply.domain.ProjectCard.Section;
import jobapply.harness.policies.FieldClassGuard;

/**
 * 合并多个来源抽出来的草稿，并和已有的事实层对比（确定性的，不调用模型）：
 * <ul>
 *   <li>事实按键合并；同一个键在别的来源里写法不同时记为 {@code alternatives}，由用户选；</li>
 *   <li>项目按别名合并：两份草稿的 slug、别名、中英文标题有一个相同（忽略大小写、空白和标点）就是同一个项目；
 *       和已有的项目卡按同样的规则对上时沿用它的 slug；</li>
 *   <li>和事实层对比，标为新增 / 不同 / 相同（memory.md「新建 Project」的规则）；「相同」的候选自动记为沿用，不用确认；</li>
 *   <li>C 级：字段名命中 C 级词表，或者值里有 C 级的值（证件号码、「签证：…」这类），整条丢弃；</li>
 *   <li>出处不在这个来源读过的文件里时，去掉出处并提示核对，内容保留为候选；没有出处的内容照样保留。</li>
 * </ul>
 */
public final class CandidateMerger {

    private static final Pattern KEY = Pattern.compile("[a-z0-9_-]+(\\.[a-z0-9_-]+)+");
    private static final Pattern FACT_REF = Pattern.compile("\\{\\{fact:([^}]*)}}");
    private static final Pattern YEAR_MONTH = Pattern.compile("\\d{4}-\\d{2}");
    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");

    /**
     * @param warnings 给页面看的汇总提示（丢弃了几条 C 级内容、几条键不合法等）
     */
    public record Result(List<ImportCandidate> candidates, List<String> warnings) {
    }

    private CandidateMerger() {
    }

    public static Result merge(List<CandidateExtractor.Drafts> drafts, List<Fact> existingFacts,
                               List<ProjectCard> existingCards) {
        List<String> warnings = new ArrayList<>();
        List<ImportCandidate> candidates = new ArrayList<>(facts(drafts, existingFacts, warnings));
        candidates.addAll(projects(drafts, existingCards));
        return new Result(candidates, warnings);
    }

    // ---------------------------------------------------------------- 事实

    private static final class FactBuilder {
        String key;
        String value;
        String zh;
        String en;
        String factClass;
        String evidence;
        final Set<Origin> origins = new LinkedHashSet<>();
        final List<Alternative> alternatives = new ArrayList<>();
        final Set<String> notes = new LinkedHashSet<>();
    }

    private static List<ImportCandidate> facts(List<CandidateExtractor.Drafts> drafts, List<Fact> existingFacts,
                                               List<String> warnings) {
        Map<String, FactBuilder> byKey = new LinkedHashMap<>();
        int invalid = 0;
        int classC = 0;
        for (CandidateExtractor.Drafts source : drafts) {
            for (CandidateExtractor.FactDraft draft : source.facts()) {
                String key = draft.key() == null ? "" : draft.key().strip().toLowerCase(Locale.ROOT);
                String value = draft.value();
                String zh = value == null ? draft.zh() : null;
                String en = value == null ? draft.en() : null;
                if (!KEY.matcher(key).matches() || key.startsWith("projects.")
                        || (value == null && zh == null && en == null) || hasLineBreak(value, zh, en)) {
                    invalid++;
                    continue;
                }
                if (isClassC(key, value, zh, en)) {
                    classC++;
                    continue;
                }
                String locator = draft.locator();
                String note = null;
                if (locator != null && !source.readPaths().contains(SourceInventory.pathOf(locator))) {
                    note = SourceInventory.invalidLocator(locator);
                    locator = null;
                }
                FactBuilder fact = byKey.get(key);
                if (fact == null) {
                    fact = new FactBuilder();
                    fact.key = key;
                    fact.value = value;
                    fact.zh = zh;
                    fact.en = en;
                    fact.factClass = "B".equals(draft.factClass()) ? "B" : "A";
                    byKey.put(key, fact);
                } else if (!sameValue(fact.value, fact.zh, fact.en, value, zh, en)) {
                    fact.alternatives.add(new Alternative(source.sourceKey(), value, zh, en));
                }
                if (fact.evidence == null) {
                    fact.evidence = draft.evidence();
                }
                fact.origins.add(new Origin(source.sourceKey(), locator));
                if (note != null) {
                    fact.notes.add(note);
                }
            }
        }
        if (classC > 0) {
            warnings.add("丢弃了 " + classC + " 条 C 级内容（证件、签证、薪资等不提取、不写入）");
        }
        if (invalid > 0) {
            warnings.add("有 " + invalid + " 条事实的键或值不合法，没有列出");
        }
        Map<String, Fact> existing = new LinkedHashMap<>();
        existingFacts.stream().filter(fact -> !fact.unset()).forEach(fact -> existing.put(fact.key(), fact));
        List<ImportCandidate> candidates = new ArrayList<>();
        int n = 0;
        for (FactBuilder fact : byKey.values()) {
            Fact current = existing.get(fact.key);
            // 别的来源有不同写法时不算相同，要让用户看到并选择
            String comparison = current == null ? ImportCandidate.NEW
                    : fact.alternatives.isEmpty()
                    && sameValue(current.value(), current.zh(), current.en(), fact.value, fact.zh, fact.en)
                    ? ImportCandidate.SAME : ImportCandidate.DIFFERENT;
            Map<String, String> before = null;
            if (current != null && comparison.equals(ImportCandidate.DIFFERENT)) {
                before = new LinkedHashMap<>();
                before.put("value", current.value());
                before.put("zh", current.zh());
                before.put("en", current.en());
            }
            candidates.add(new ImportCandidate(String.format("f-%03d", ++n), ImportCandidate.FACT,
                    group(fact.key), fact.key, fact.value, fact.zh, fact.en, fact.factClass, null, fact.evidence,
                    List.copyOf(fact.origins), null, comparison, before, fact.alternatives,
                    List.copyOf(fact.notes), sameDecision(comparison)));
        }
        return candidates;
    }

    /**
     * 两种写法是不是同一个值：与语言无关的值直接比较；文字按两边都有的语言比较（至少要有一种语言两边都有）。
     */
    static boolean sameValue(String value1, String zh1, String en1, String value2, String zh2, String en2) {
        if (value1 != null || value2 != null) {
            return value1 != null && value2 != null && normalize(value1).equals(normalize(value2));
        }
        boolean compared = false;
        if (zh1 != null && zh2 != null) {
            if (!normalize(zh1).equals(normalize(zh2))) {
                return false;
            }
            compared = true;
        }
        if (en1 != null && en2 != null) {
            if (!normalize(en1).equals(normalize(en2))) {
                return false;
            }
            compared = true;
        }
        return compared;
    }

    /**
     * 字段名（键的最后一段，例如 {@code contact.visa-status} 的 {@code visa-status}）命中 C 级词表，或者值里有 C 级的值。
     * 键中间的公司名、项目名不算（{@code experience.demo-health.role} 不是 C 级）。
     */
    static boolean isClassC(String key, String... values) {
        String field = key.substring(key.lastIndexOf('.') + 1);
        if (FieldClassGuard.isClassC(field.replace('_', ' ').replace('-', ' '))) {
            return true;
        }
        for (String value : values) {
            if (value != null && FieldClassGuard.withoutClassC(value).removed() > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLineBreak(String... values) {
        for (String value : values) {
            if (value != null && (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 项目

    private static final class ProjectGroup {
        final List<CandidateExtractor.ProjectDraft> drafts = new ArrayList<>();
        final List<String> sources = new ArrayList<>();
        final Set<String> names = new LinkedHashSet<>();
        final Set<Origin> origins = new LinkedHashSet<>();
        final Set<String> notes = new LinkedHashSet<>();
    }

    private static List<ImportCandidate> projects(List<CandidateExtractor.Drafts> drafts,
                                                  List<ProjectCard> existingCards) {
        List<ProjectGroup> groups = new ArrayList<>();
        for (CandidateExtractor.Drafts source : drafts) {
            for (CandidateExtractor.ProjectDraft draft : source.projects()) {
                Set<String> names = names(draft.slug(), draft.titleZh(), draft.titleEn(), draft.aliases());
                if (names.isEmpty()) {
                    continue;
                }
                ProjectGroup group = groups.stream().filter(g -> intersects(g.names, names)).findFirst()
                        .orElseGet(() -> {
                            ProjectGroup created = new ProjectGroup();
                            groups.add(created);
                            return created;
                        });
                group.drafts.add(draft);
                group.names.addAll(names);
                if (!group.sources.contains(source.sourceKey())) {
                    group.sources.add(source.sourceKey());
                }
                boolean any = false;
                for (String locator : draft.locators()) {
                    if (source.readPaths().contains(SourceInventory.pathOf(locator))) {
                        group.origins.add(new Origin(source.sourceKey(), locator));
                        any = true;
                    } else {
                        group.notes.add(SourceInventory.invalidLocator(locator));
                    }
                }
                if (!any) {
                    group.origins.add(new Origin(source.sourceKey(), null));
                }
            }
        }
        List<ImportCandidate> candidates = new ArrayList<>();
        Set<String> usedSlugs = new LinkedHashSet<>();
        existingCards.forEach(card -> usedSlugs.add(card.slug()));
        Set<String> takenThisRun = new LinkedHashSet<>();
        int n = 0;
        for (ProjectGroup group : groups) {
            ProjectCard existing = existingCards.stream()
                    .filter(card -> intersects(group.names, names(card.slug(), card.title() == null ? null
                            : card.title().zh(), card.title() == null ? null : card.title().en(), card.aliases())))
                    .filter(card -> !takenThisRun.contains(card.slug()))
                    .findFirst().orElse(null);
            String slug;
            if (existing != null) {
                slug = existing.slug();
            } else {
                slug = unique(slug(group.drafts.getFirst()), usedSlugs);
            }
            usedSlugs.add(slug);
            takenThisRun.add(slug);
            ProjectCard card = card(slug, group);
            String comparison = existing == null ? ImportCandidate.NEW
                    : sameCard(existing, card) ? ImportCandidate.SAME : ImportCandidate.DIFFERENT;
            Map<String, String> before = null;
            if (existing != null && comparison.equals(ImportCandidate.DIFFERENT)) {
                before = new LinkedHashMap<>();
                before.put("zh", existing.title() == null ? null : existing.title().zh());
                before.put("en", existing.title() == null ? null : existing.title().en());
            }
            candidates.add(new ImportCandidate(String.format("p-%03d", ++n), ImportCandidate.PROJECT,
                    group("projects." + slug), "projects." + slug, null,
                    card.title() == null ? null : card.title().zh(), card.title() == null ? null : card.title().en(),
                    null, card, null, List.copyOf(group.origins), null, comparison, before, List.of(),
                    List.copyOf(group.notes), sameDecision(comparison)));
        }
        return candidates;
    }

    private static ProjectCard card(String slug, ProjectGroup group) {
        String titleZh = null;
        String titleEn = null;
        String kind = null;
        String from = null;
        String to = null;
        Integer teamSize = null;
        Set<String> stack = new LinkedHashSet<>();
        Set<String> stackSeen = new LinkedHashSet<>();
        Map<String, ProjectCard.Link> links = new LinkedHashMap<>();
        Set<String> aliases = new LinkedHashSet<>();
        Map<Language, Map<String, String>> sections = new LinkedHashMap<>();
        for (CandidateExtractor.ProjectDraft draft : group.drafts) {
            titleZh = titleZh != null ? titleZh : oneLine(draft.titleZh());
            titleEn = titleEn != null ? titleEn : oneLine(draft.titleEn());
            if (kind == null && draft.kind() != null && ProjectCard.KINDS.contains(draft.kind())) {
                kind = draft.kind();
            }
            from = from != null ? from : yearMonth(draft.periodFrom());
            to = to != null ? to : yearMonth(draft.periodTo());
            teamSize = teamSize != null ? teamSize : draft.teamSize();
            for (String item : draft.stack()) {
                if (stackSeen.add(item.toLowerCase(Locale.ROOT))) {
                    stack.add(oneLine(item));
                }
            }
            draft.links().forEach(link -> links.putIfAbsent(link.url(), link));
            if (draft.slug() != null) {
                aliases.add(oneLine(draft.slug()));
            }
            draft.aliases().forEach(alias -> aliases.add(oneLine(alias)));
            Map<String, String> texts = sections.computeIfAbsent(draft.language(), language -> new LinkedHashMap<>());
            draft.sections().forEach((id, text) -> {
                String clean = sectionText(text);
                if (!clean.isEmpty()) {
                    texts.putIfAbsent(id, clean);
                }
            });
        }
        aliases.remove(slug);
        aliases.remove(titleZh);
        aliases.remove(titleEn);
        List<Section> zh = sections(Language.ZH, sections.get(Language.ZH));
        List<Section> en = sections(Language.EN, sections.get(Language.EN));
        ProjectCard.Period period = from == null && to == null ? null
                : new ProjectCard.Period(from, to, "year-month");
        return new ProjectCard(slug, List.copyOf(aliases),
                titleZh == null && titleEn == null ? null : new ProjectCard.Text(titleZh, titleEn), kind, period,
                teamSize, null, List.copyOf(stack), List.copyOf(links.values()), Map.of(), List.copyOf(group.sources),
                Set.of(), zh, en, Map.of());
    }

    /** 某种语言的四个小节；这种语言一段文字都没有时为空（确认时再起草）。 */
    static List<Section> sections(Language language, Map<String, String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        Map<String, String> headings = language == Language.ZH ? CandidateExtractor.SECTION_ZH
                : CandidateExtractor.SECTION_EN;
        return CandidateExtractor.SECTION_IDS.stream()
                .map(id -> new Section(id, headings.get(id), texts.getOrDefault(id, ""))).toList();
    }

    /**
     * 已有的卡和候选一样：候选写了的标题、小节、类型、起止时间、团队人数都和已有的相同，技术栈和链接没有新增。
     * 有任何一处不同都要用户确认，不自动沿用。
     */
    private static boolean sameCard(ProjectCard existing, ProjectCard candidate) {
        if ((candidate.kind() != null && !candidate.kind().equals(existing.kind()))
                || (candidate.teamSize() != null && !candidate.teamSize().equals(existing.teamSize()))) {
            return false;
        }
        if (candidate.period() != null) {
            ProjectCard.Period old = existing.period();
            if ((candidate.period().from() != null && (old == null || !candidate.period().from().equals(old.from())))
                    || (candidate.period().to() != null && (old == null || !candidate.period().to().equals(old.to())))) {
                return false;
            }
        }
        Set<String> stack = new LinkedHashSet<>();
        existing.stack().forEach(item -> stack.add(item.toLowerCase(Locale.ROOT)));
        if (candidate.stack().stream().anyMatch(item -> !stack.contains(item.toLowerCase(Locale.ROOT)))) {
            return false;
        }
        Set<String> links = new LinkedHashSet<>();
        existing.links().forEach(link -> links.add(link.url()));
        if (candidate.links().stream().anyMatch(link -> !links.contains(link.url()))) {
            return false;
        }
        if (candidate.title() != null) {
            String zh = existing.title() == null ? null : existing.title().zh();
            String en = existing.title() == null ? null : existing.title().en();
            if ((candidate.title().zh() != null && !candidate.title().zh().equals(zh))
                    || (candidate.title().en() != null && !candidate.title().en().equals(en))) {
                return false;
            }
        }
        for (Language language : Language.values()) {
            List<Section> mine = candidate.sections(language);
            if (mine.isEmpty()) {
                continue;
            }
            List<Section> theirs = existing.sections(language);
            for (Section section : mine) {
                String text = theirs.stream().filter(s -> s.id().equals(section.id())).map(Section::text)
                        .findFirst().orElse(null);
                if (!normalize(section.text()).equals(text == null ? null : normalize(text))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 用来判断是不是同一个项目的名字：小写，去掉空白和标点。 */
    static Set<String> names(String slug, String titleZh, String titleEn, List<String> aliases) {
        Set<String> names = new LinkedHashSet<>();
        List<String> all = new ArrayList<>();
        all.add(slug);
        all.add(titleZh);
        all.add(titleEn);
        all.addAll(aliases);
        for (String name : all) {
            if (name == null) {
                continue;
            }
            String key = name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
            if (!key.isEmpty()) {
                names.add(key);
            }
        }
        return names;
    }

    private static boolean intersects(Set<String> a, Set<String> b) {
        return a.stream().anyMatch(b::contains);
    }

    static String slug(CandidateExtractor.ProjectDraft draft) {
        for (String candidate : new String[] {draft.slug(), draft.titleEn()}) {
            if (candidate == null) {
                continue;
            }
            String slug = NON_SLUG.matcher(candidate.toLowerCase(Locale.ROOT)).replaceAll("-")
                    .replaceAll("^-+|-+$", "");
            if (!slug.isEmpty()) {
                return slug.length() > 60 ? slug.substring(0, 60).replaceAll("-+$", "") : slug;
            }
        }
        return "project";
    }

    private static String unique(String slug, Set<String> used) {
        String result = slug;
        for (int i = 2; used.contains(result); i++) {
            result = slug + "-" + i;
        }
        return result;
    }

    private static String yearMonth(String value) {
        if (value == null) {
            return null;
        }
        String text = value.strip();
        return text.length() >= 7 && YEAR_MONTH.matcher(text.substring(0, 7)).matches() ? text.substring(0, 7) : null;
    }

    /** 小节文字：去掉模型写的 {@code {{fact:}}} 引用（项目卡里还没有对应的数值）和 Markdown 标题。 */
    static String sectionText(String text) {
        if (text == null) {
            return "";
        }
        String clean = FACT_REF.matcher(text).replaceAll("$1");
        return clean.lines().map(line -> line.startsWith("#") ? line.replaceFirst("^#+\\s*", "") : line)
                .reduce((a, b) -> a + "\n" + b).orElse("").replace("{#", "{ #").strip();
    }

    private static String oneLine(String text) {
        return text == null ? null : text.replace('\n', ' ').replace('\r', ' ').strip();
    }

    private static String normalize(String text) {
        return text.strip().replaceAll("\\s+", " ");
    }

    private static String group(String key) {
        return EvidenceTier.of(key).name().toLowerCase(Locale.ROOT);
    }

    private static ImportCandidate.Decision sameDecision(String comparison) {
        return Objects.equals(comparison, ImportCandidate.SAME)
                ? new ImportCandidate.Decision("same", null, null, List.of("和事实层相同，沿用")) : null;
    }
}
