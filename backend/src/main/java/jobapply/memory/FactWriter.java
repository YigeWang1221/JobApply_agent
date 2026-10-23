package jobapply.memory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import jobapply.domain.Claim;
import jobapply.domain.EvidenceTier;
import jobapply.domain.Fact;
import jobapply.domain.FactClass;
import jobapply.domain.ImportCandidate;
import jobapply.domain.Language;
import jobapply.domain.ProjectCard;
import jobapply.domain.ProjectCard.Section;
import jobapply.domain.ProjectCard.Text;
import jobapply.domain.Timestamps;
import jobapply.persistence.ConflictsFile;
import jobapply.persistence.FactsFile;
import jobapply.persistence.GlossaryFile;
import jobapply.persistence.LedgerFile;
import jobapply.persistence.ProjectCardFile;
import jobapply.persistence.StorageGit;
import jobapply.persistence.StorageLayout;

/**
 * 确认写入：把用户对一个候选的决定写进事实层（{@code facts.md}、{@code projects/<slug>.md}）、台账、术语表或
 * {@code conflicts.md}；开启 git 时每个决定提交一次。
 * <ul>
 *   <li>用户确认即可写入，不要求论断引用、出处或条件（memory.md「来源与使用：用户确认优先」）；填了的论断引用必须存在；</li>
 *   <li>C 级内容不写入（键命中 C 级词表或值里有 C 级的值时拒绝）；</li>
 *   <li>用户这次给出的语言记为已审阅；缺的那种语言由 {@link TranslationDrafter} 起草，标为未审阅。起草失败时照样写入，
 *       返回提示，以后可以在「事实与画像」里补；</li>
 *   <li>两种语言都审阅过的学校、公司、学位、专业名和项目名记进 {@code glossary.md}（起草的译文不进术语表）。</li>
 * </ul>
 * 也负责「事实与画像」页上的修改（改文字、标记已审阅）。
 */
public final class FactWriter {

    public static final String ACCEPT = "accept";
    public static final String EDIT = "edit";
    public static final String REJECT = "reject";
    public static final String CONFLICT = "conflict";
    static final String FACTS_TITLE = "个人基础事实";

    /** 这些字段的中英文是专有名词，两种语言都审阅过时记进术语表。 */
    private static final Set<String> GLOSSARY_FIELDS = Set.of("school", "university", "college", "company",
            "employer", "organization", "degree", "major", "program_name", "department");
    private static final Map<String, String> GLOSSARY_KIND = Map.of("school", "学校", "university", "学校", "college",
            "学院", "company", "公司", "employer", "公司", "organization", "组织", "degree", "学位", "major", "专业",
            "program_name", "专业", "department", "院系");
    private static final Pattern DAY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern MONTH = Pattern.compile("\\d{4}-\\d{2}");

    private final StorageLayout layout;
    private final LedgerFile ledger;
    private final ProjectCardFile cards;
    private final GlossaryFile glossary;
    private final ConflictsFile conflicts;
    private final TranslationDrafter translator;
    private final StorageGit git;
    private final Clock clock;

    public FactWriter(StorageLayout layout, LedgerFile ledger, ProjectCardFile cards, GlossaryFile glossary,
                      ConflictsFile conflicts, TranslationDrafter translator, Clock clock) {
        this.layout = layout;
        this.ledger = ledger;
        this.cards = cards;
        this.glossary = glossary;
        this.conflicts = conflicts;
        this.translator = translator;
        this.git = new StorageGit(layout, clock);
        this.clock = clock;
    }

    /**
     * 执行一个决定，返回记进导入记录的决定。
     *
     * @param value {@code edit} 时用户改过的值：事实是 {@code {value}} 或 {@code {zh, en}}；项目卡是
     *              {@code {title: {zh, en}, zh: {小节 id: 文字}, en: {…}}}
     * @param use   论断的用途；不填时按状态取默认值
     */
    public synchronized ImportCandidate.Decision apply(String runId, ImportCandidate candidate, String decision,
                                                       Map<String, Object> value, String use) {
        Claim.Use claimUse = use == null || use.isBlank() ? null : Claim.Use.of(use);
        String today = LocalDate.now(clock).toString();
        List<String> warnings = new ArrayList<>();
        String message = "base：" + label(decision) + " " + candidate.key() + "（导入 " + runId + "）";
        switch (decision == null ? "" : decision) {
            case REJECT -> transaction(message, slugOf(candidate), () -> {
                if (candidate.claim() != null && ledger.find(candidate.claim()).isPresent()) {
                    ledger.remove(candidate.claim());
                }
                return null;
            });
            case CONFLICT -> transaction(message, slugOf(candidate), () -> {
                conflict(runId, candidate);
                updateClaim(candidate.claim(), Claim.Status.DISCREPANCY, claimUse, today);
                return null;
            });
            case ACCEPT, EDIT -> {
                boolean edited = EDIT.equals(decision);
                if (edited && (value == null || value.isEmpty())) {
                    throw new IllegalArgumentException("edit 要带上改过的 value");
                }
                // 先把要写的内容都准备好并检查一遍（C 级、换行、引用、小节对应），通过了再动台账和文件
                boolean claimed = candidate.claim() != null && ledger.find(candidate.claim()).isPresent();
                if (candidate.forProject()) {
                    ProjectCard card = projectToWrite(candidate.project(), edited ? value : Map.of(), warnings);
                    ProjectCard merged = mergeWithExisting(card, claimed ? candidate.claim() : null);
                    ProjectCardFile.render(merged);
                    transaction(message, slugOf(candidate), () -> {
                        updateClaim(candidate.claim(), status(candidate, edited), claimUse, today);
                        cards.save(merged, ledger.ids());
                        glossaryTerm(merged.title(), merged.reviewed(), "项目");
                        return null;
                    });
                } else {
                    Fact fact = factToWrite(candidate, edited ? value : Map.of(), warnings)
                            .withClaim(claimed ? candidate.claim() : null);
                    FactsFile.render(List.of(fact));
                    transaction(message, slugOf(candidate), () -> {
                        updateClaim(candidate.claim(), status(candidate, edited), claimUse, today);
                        writeFact(fact);
                        return null;
                    });
                }
            }
            default -> throw new IllegalArgumentException("decision 只能是 accept、edit、reject、conflict：" + decision);
        }
        return new ImportCandidate.Decision(decision, claimUse == null ? null : claimUse.code(),
                Timestamps.now(clock), warnings);
    }

    /**
     * 「事实与画像」页：修改一条事实的文字或值、标记已审阅。
     *
     * @param reviewed 标为已审阅的语言；为 null 时，改过文字的语言记为已审阅，其它不变
     */
    public synchronized Fact editFact(String key, String newValue, String zh, String en, Set<Language> reviewed) {
        List<Fact> facts = new ArrayList<>(FactsFile.read(layout.baseFacts()));
        Fact current = facts.stream().filter(fact -> fact.key().equals(key) && !fact.unset()).findFirst()
                .orElseThrow(() -> new NoSuchElementException("事实层里没有这一条：" + key));
        String value = current.value();
        String zhText = current.zh();
        String enText = current.en();
        Set<Language> marked = EnumSet.noneOf(Language.class);
        marked.addAll(current.reviewed());
        if (current.value() != null) {
            if (newValue != null) {
                value = blankToNull(newValue);
            }
        } else {
            if (zh != null && !zh.equals(current.zh())) {
                zhText = blankToNull(zh);
                marked.add(Language.ZH);
            }
            if (en != null && !en.equals(current.en())) {
                enText = blankToNull(en);
                marked.add(Language.EN);
            }
        }
        if (reviewed != null) {
            marked = reviewed.isEmpty() ? EnumSet.noneOf(Language.class) : EnumSet.copyOf(reviewed);
        }
        requireNotClassC(key, value, zhText, enText);
        Fact updated = new Fact(key, value, zhText, enText, current.factClass(), current.source(), current.claim(),
                current.value() != null ? Set.of() : marked, current.confirmedAt(), current.validFrom(),
                current.validTo(), current.precision(), current.notes(), false);
        FactsFile.render(List.of(updated));
        transaction("base：修改 " + key, null, () -> {
            writeFact(updated);
            return null;
        });
        return updated;
    }

    /**
     * 「事实与画像」页：修改项目卡的标题和小节文字、标记已审阅。小节 id 不变；改过文字的语言记为已审阅。
     *
     * @param sections 语言 → (小节 id → 文字)；没给的小节不变
     * @param reviewed 标为已审阅的语言；为 null 时，改过文字的语言记为已审阅，其它不变
     */
    public synchronized ProjectCard editProject(String slug, Text title, Map<Language, Map<String, String>> sections,
                                                Set<Language> reviewed) {
        ProjectCard current = cards.find(slug).orElseThrow(() -> new NoSuchElementException("没有这张项目卡：" + slug));
        Set<Language> marked = EnumSet.noneOf(Language.class);
        marked.addAll(current.reviewed());
        Text newTitle = current.title();
        if (title != null) {
            String zh = title.zh() == null ? (current.title() == null ? null : current.title().zh()) : title.zh();
            String en = title.en() == null ? (current.title() == null ? null : current.title().en()) : title.en();
            newTitle = new Text(blankToNull(zh), blankToNull(en));
            requireNotClassC("projects", newTitle.zh(), newTitle.en());
        }
        Map<Language, List<Section>> next = new LinkedHashMap<>();
        for (Language language : Language.values()) {
            Map<String, String> changes = sections == null ? null : sections.get(language);
            List<Section> list = new ArrayList<>(current.sections(language));
            if (changes != null && !changes.isEmpty()) {
                if (list.isEmpty()) {
                    list.addAll(emptySections(language, current.sections(language.other())));
                }
                for (int i = 0; i < list.size(); i++) {
                    Section section = list.get(i);
                    if (changes.containsKey(section.id())) {
                        String text = changes.get(section.id()) == null ? "" : changes.get(section.id()).strip();
                        requireNotClassC("projects", text);
                        if (!text.equals(section.text())) {
                            marked.add(language);
                        }
                        list.set(i, new Section(section.id(), section.heading(), text));
                    }
                }
            }
            next.put(language, list);
        }
        if (reviewed != null) {
            marked = reviewed.isEmpty() ? EnumSet.noneOf(Language.class) : EnumSet.copyOf(reviewed);
        }
        ProjectCard updated = new ProjectCard(current.slug(), current.aliases(), newTitle, current.kind(),
                current.period(), current.teamSize(), current.roleClaim(), current.stack(), current.links(),
                current.facts(), current.sources(), marked, next.get(Language.ZH), next.get(Language.EN),
                current.extra());
        ProjectCardFile.render(updated);
        updated.requireKnownClaims(ledger.ids());
        transaction("base：修改项目卡 " + slug, slug, () -> {
            cards.save(updated, ledger.ids());
            glossaryTerm(updated.title(), updated.reviewed(), "项目");
            return null;
        });
        return updated;
    }

    /** 另一种语言还没有小节时，按这种语言的小节 id 建空的小节（两种语言的小节要一一对应）。 */
    private static List<Section> emptySections(Language language, List<Section> other) {
        if (other.isEmpty()) {
            return CandidateMerger.sections(language, Map.of("summary", ""));
        }
        Map<String, String> headings = language == Language.ZH ? CandidateExtractor.SECTION_ZH
                : CandidateExtractor.SECTION_EN;
        return other.stream().map(section -> new Section(section.id(),
                headings.getOrDefault(section.id(), section.heading()), "")).toList();
    }

    // ---------------------------------------------------------------- 事实

    private Fact factToWrite(ImportCandidate candidate, Map<String, Object> edit, List<String> warnings) {
        String value = candidate.value();
        String zh = candidate.zh();
        String en = candidate.en();
        if (!edit.isEmpty()) {
            value = blankToNull(string(edit.get("value")));
            zh = value == null ? blankToNull(string(edit.get("zh"))) : null;
            en = value == null ? blankToNull(string(edit.get("en"))) : null;
            if (value == null && zh == null && en == null) {
                throw new IllegalArgumentException("改过的值不能为空");
            }
        }
        requireNotClassC(candidate.key(), value, zh, en);
        Set<Language> reviewed = EnumSet.noneOf(Language.class);
        if (value == null) {
            if (zh != null) {
                reviewed.add(Language.ZH);
            }
            if (en != null) {
                reviewed.add(Language.EN);
            }
            if (zh == null || en == null) {
                Language from = zh != null ? Language.ZH : Language.EN;
                String draft = translateOne(from == Language.ZH ? zh : en, from, warnings);
                if (draft != null) {
                    zh = zh != null ? zh : draft;
                    en = en != null ? en : draft;
                }
            }
        }
        Fact current = FactsFile.read(layout.baseFacts()).stream()
                .filter(fact -> fact.key().equals(candidate.key()) && !fact.unset()).findFirst().orElse(null);
        String precision = current != null && current.precision() != null ? current.precision()
                : value == null ? null : DAY.matcher(value).matches() ? "day"
                : MONTH.matcher(value).matches() ? "year-month" : null;
        String source = candidate.origins().isEmpty() ? "user-confirmed"
                : "source:" + candidate.origins().getFirst().source();
        return new Fact(candidate.key(), value, zh, en,
                "B".equals(candidate.factClass()) ? FactClass.B : FactClass.A, source, null, reviewed,
                LocalDate.now(clock).toString(), current == null ? null : current.validFrom(),
                current == null ? null : current.validTo(), precision, current == null ? null : current.notes(),
                false);
    }

    private void writeFact(Fact fact) {
        List<Fact> facts = new ArrayList<>(FactsFile.read(layout.baseFacts()));
        if (fact.claim() != null) {
            EvidenceTier.requireKnownClaims(List.of(fact), ledger.ids());
        }
        int index = -1;
        for (int i = 0; i < facts.size(); i++) {
            if (facts.get(i).key().equals(fact.key())) {
                index = i;
            }
        }
        if (index < 0) {
            facts.add(fact);
        } else {
            facts.set(index, fact);
        }
        FactsFile.replace(layout.baseFacts(), FACTS_TITLE, facts);
        git.commit("base：事实 " + fact.key(), List.of(layout.baseFacts()));
        String field = fact.key().substring(fact.key().lastIndexOf('.') + 1);
        if (GLOSSARY_FIELDS.contains(field)) {
            glossaryTerm(new Text(fact.zh(), fact.en()), fact.reviewed(), GLOSSARY_KIND.get(field));
        }
    }

    // ---------------------------------------------------------------- 项目卡

    /** 用户确认的项目卡：套上改过的标题和小节，缺的那种语言起草。 */
    private ProjectCard projectToWrite(ProjectCard card, Map<String, Object> edit, List<String> warnings) {
        Text title = card.title();
        Map<Language, List<Section>> sections = new LinkedHashMap<>();
        sections.put(Language.ZH, card.zh());
        sections.put(Language.EN, card.en());
        if (!edit.isEmpty()) {
            if (edit.get("title") instanceof Map<?, ?> map) {
                title = new Text(blankToNull(string(map.get("zh"))), blankToNull(string(map.get("en"))));
            }
            for (Language language : Language.values()) {
                if (edit.get(language.code()) instanceof Map<?, ?> map) {
                    Map<String, String> texts = new LinkedHashMap<>();
                    map.forEach((id, text) -> {
                        String clean = CandidateMerger.sectionText(string(text));
                        if (!clean.isEmpty()) {
                            texts.put(String.valueOf(id), clean);
                        }
                    });
                    sections.put(language, CandidateMerger.sections(language, texts));
                }
            }
        }
        requireNotClassC("projects", title == null ? null : title.zh(), title == null ? null : title.en());
        for (Language language : Language.values()) {
            for (Section section : sections.get(language)) {
                requireNotClassC("projects", section.text());
            }
        }
        Set<Language> reviewed = EnumSet.noneOf(Language.class);
        for (Language language : Language.values()) {
            if (!sections.get(language).isEmpty()) {
                reviewed.add(language);
            }
        }
        if (reviewed.isEmpty()) {
            throw new IllegalArgumentException("项目卡至少要有一种语言的描述");
        }
        if (reviewed.size() == 1) {
            Language from = reviewed.iterator().next();
            Map<String, String> texts = new LinkedHashMap<>();
            String fromTitle = title == null ? null : title.get(from);
            String toTitle = title == null ? null : title.get(from.other());
            if (fromTitle != null && toTitle == null) {
                texts.put("title", fromTitle);
            }
            sections.get(from).forEach(section -> texts.put(section.id(), section.text()));
            try {
                Map<String, String> drafted = translator.draft(texts, from);
                List<Section> other = new ArrayList<>();
                Map<String, String> headings = from.other() == Language.ZH ? CandidateExtractor.SECTION_ZH
                        : CandidateExtractor.SECTION_EN;
                for (Section section : sections.get(from)) {
                    other.add(new Section(section.id(), headings.getOrDefault(section.id(), section.heading()),
                            drafted.getOrDefault(section.id(), "")));
                }
                sections.put(from.other(), other);
                if (drafted.containsKey("title")) {
                    title = from == Language.ZH ? new Text(fromTitle, drafted.get("title"))
                            : new Text(drafted.get("title"), fromTitle);
                }
            } catch (StructuredCall.StepFailed e) {
                warnings.add(untranslated(e));
            }
        }
        return new ProjectCard(card.slug(), card.aliases(), title, card.kind(), card.period(), card.teamSize(),
                card.roleClaim(), card.stack(), card.links(), card.facts(), card.sources(), reviewed,
                sections.get(Language.ZH), sections.get(Language.EN), card.extra());
    }

    /** 和已有的同名项目卡合并：候选没写的字段沿用已有的，别名、技术栈、链接、来源取并集。 */
    private ProjectCard mergeWithExisting(ProjectCard card, String roleClaim) {
        Optional<ProjectCard> found = cards.find(card.slug());
        if (found.isEmpty()) {
            return new ProjectCard(card.slug(), card.aliases(), card.title(), card.kind(), card.period(),
                    card.teamSize(), roleClaim, card.stack(), card.links(), card.facts(), card.sources(),
                    card.reviewed(), card.zh(), card.en(), card.extra());
        }
        ProjectCard old = found.get();
        Text title = card.title() == null ? old.title() : new Text(
                card.title().zh() != null ? card.title().zh() : old.title() == null ? null : old.title().zh(),
                card.title().en() != null ? card.title().en() : old.title() == null ? null : old.title().en());
        Map<String, ProjectCard.Link> links = new LinkedHashMap<>();
        old.links().forEach(link -> links.put(link.url(), link));
        card.links().forEach(link -> links.putIfAbsent(link.url(), link));
        Map<String, Object> extra = new LinkedHashMap<>(old.extra());
        extra.putAll(card.extra());
        boolean keepFacts = card.facts().isEmpty();
        return new ProjectCard(card.slug(), union(old.aliases(), card.aliases()), title,
                card.kind() != null ? card.kind() : old.kind(), card.period() != null ? card.period() : old.period(),
                card.teamSize() != null ? card.teamSize() : old.teamSize(),
                roleClaim != null ? roleClaim : old.roleClaim(), union(old.stack(), card.stack()),
                List.copyOf(links.values()), keepFacts ? old.facts() : card.facts(), union(old.sources(), card.sources()),
                card.reviewed(), card.zh(), card.en(), extra);
    }

    // ---------------------------------------------------------------- 台账、术语、冲突

    /** 整理时建的论断：确认时有出处的记为 verified，没有出处或者改过的记为本人确认（owner_attested）。 */
    private static Claim.Status status(ImportCandidate candidate, boolean edited) {
        boolean located = candidate.origins().stream().anyMatch(origin -> origin.locator() != null);
        return !edited && located ? Claim.Status.VERIFIED : Claim.Status.OWNER_ATTESTED;
    }

    /** 更新论断的状态、用途和确认时间；论断不存在（例如已经从台账删掉）时返回 null。 */
    private Claim.Status updateClaim(String id, Claim.Status status, Claim.Use use, String today) {
        if (id == null) {
            return null;
        }
        Optional<Claim> claim = ledger.find(id);
        if (claim.isEmpty()) {
            return null;
        }
        Claim old = claim.get();
        ledger.update(new Claim(old.id(), old.category(), old.text(), old.source(), old.locator(),
                old.valueAndCondition(), status, use == null ? Claim.defaultUse(status) : use, today, old.extra()));
        return status;
    }

    private void glossaryTerm(Text title, Set<Language> reviewed, String kind) {
        if (title == null || title.zh() == null || title.en() == null || !reviewed.containsAll(Set.of(Language.ZH,
                Language.EN)) || title.zh().contains("|") || title.en().contains("|")) {
            return;
        }
        Optional<GlossaryFile.Term> existing = glossary.find(title.zh());
        if (existing.isPresent() && existing.get().en().equals(title.en())) {
            return;
        }
        glossary.put(new GlossaryFile.Term(title.zh(), title.en(), kind, "", Map.of()));
    }

    private void conflict(String runId, ImportCandidate candidate) {
        Map<String, String> lines = new LinkedHashMap<>();
        if (candidate.existing() != null) {
            lines.put("事实层现在", describe(candidate.existing().get("value"), candidate.existing().get("zh"),
                    candidate.existing().get("en")));
        }
        lines.put("新材料", describe(candidate.value(), candidate.zh(), candidate.en()));
        candidate.alternatives().forEach(alternative -> lines.put("另一种写法（" + alternative.source() + "）",
                describe(alternative.value(), alternative.zh(), alternative.en())));
        List<String> origins = candidate.origins().stream()
                .map(origin -> origin.source() + (origin.locator() == null ? "" : " " + origin.locator())).toList();
        lines.put("出处", String.join("；", origins));
        lines.put("导入", runId + "，" + candidate.id());
        conflicts.append(candidate.key() + "（" + LocalDate.now(clock) + "）", lines);
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 一个决定里的所有写入：合并成一次 git 提交；中途失败时把这几个文件恢复成写之前的样子，再把错误交给调用方，
     * 不留下一半的修改（台账已改、事实没写这种）。
     */
    private void transaction(String message, String slug, Supplier<Void> work) {
        List<Path> paths = new ArrayList<>(List.of(layout.baseFacts(), layout.baseLedger(), layout.baseGlossary(),
                layout.baseConflicts()));
        if (slug != null) {
            paths.add(layout.baseProjectCard(slug));
        }
        Map<Path, byte[]> before = new LinkedHashMap<>();
        for (Path path : paths) {
            before.put(path, read(path));
        }
        try {
            git.inOneCommit(message, work);
        } catch (RuntimeException e) {
            before.forEach(FactWriter::restore);
            throw e;
        }
    }

    private static String slugOf(ImportCandidate candidate) {
        return candidate.forProject() ? candidate.project().slug() : null;
    }

    private static byte[] read(Path path) {
        try {
            return Files.exists(path) ? Files.readAllBytes(path) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void restore(Path path, byte[] content) {
        try {
            if (content == null) {
                Files.deleteIfExists(path);
            } else {
                Files.write(path, content);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("恢复失败：" + path, e);
        }
    }

    private String translateOne(String text, Language from, List<String> warnings) {
        try {
            return translator.draft(Map.of("text", text), from).get("text");
        } catch (StructuredCall.StepFailed e) {
            warnings.add(untranslated(e));
            return null;
        }
    }

    private static String untranslated(StructuredCall.StepFailed e) {
        return "另一种语言没有起草成功（" + e.getMessage() + "），已经按现有的语言写入；可以在「事实与画像」里补";
    }

    private static void requireNotClassC(String key, String... values) {
        if (CandidateMerger.isClassC(key, values)) {
            throw new IllegalArgumentException("C 级内容（证件、签证、薪资等）不写入：" + key);
        }
    }

    private static String describe(String value, String zh, String en) {
        if (value != null) {
            return value;
        }
        List<String> parts = new ArrayList<>();
        if (zh != null) {
            parts.add("zh：" + zh);
        }
        if (en != null) {
            parts.add("en：" + en);
        }
        return String.join("；", parts);
    }

    private static List<String> union(List<String> a, List<String> b) {
        Set<String> all = new LinkedHashSet<>(a);
        all.addAll(b);
        return List.copyOf(all);
    }

    private static String label(String decision) {
        return switch (decision == null ? "" : decision) {
            case REJECT -> "拒绝";
            case CONFLICT -> "记为冲突";
            case EDIT -> "修改后确认";
            default -> "确认";
        };
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
