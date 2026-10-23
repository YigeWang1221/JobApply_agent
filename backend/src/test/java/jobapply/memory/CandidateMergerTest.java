package jobapply.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jobapply.domain.Fact;
import jobapply.domain.FactClass;
import jobapply.domain.ImportCandidate;
import jobapply.domain.Language;
import jobapply.domain.ProjectCard;
import jobapply.memory.CandidateExtractor.Drafts;
import jobapply.memory.CandidateExtractor.FactDraft;
import jobapply.memory.CandidateExtractor.ProjectDraft;
import org.junit.jupiter.api.Test;

/**
 * 合并和对比是确定性的，不调用模型。数据全部虚构。
 */
class CandidateMergerTest {

    private static final String A = "s-2026-10-22-001";
    private static final String B = "s-2026-10-22-002";

    private static FactDraft fact(String key, String value, String zh, String locator) {
        return new FactDraft(key, value, zh, null, "A", locator, null);
    }

    private static ProjectDraft project(String slug, String titleZh, String titleEn, List<String> aliases,
                                        Language language, String summary, List<String> stack) {
        Map<String, String> sections = new LinkedHashMap<>();
        sections.put("summary", summary);
        sections.put("role", "");
        sections.put("approach", "");
        sections.put("results", "");
        return new ProjectDraft(slug, aliases, titleZh, titleEn, "personal", "2025-01", null, null, stack, List.of(),
                language, sections, List.of("README.md"));
    }

    private static Drafts drafts(String source, List<FactDraft> facts, List<ProjectDraft> projects) {
        return new Drafts(source, facts, projects, Set.of("README.md", "docs/a.md"), 2);
    }

    @Test
    void theSameProjectFromTwoSourcesIsMergedByAlias() {
        Drafts a = drafts(A, List.of(), List.of(project("demo-recsys", "示例推荐服务", null, List.of("DemoRec"),
                Language.ZH, "中文介绍", List.of("Java"))));
        Drafts b = drafts(B, List.of(), List.of(project("demorec-service", null, "Demo Rec", List.of("Demo-Rec"),
                Language.EN, "English summary", List.of("java", "Redis"))));

        List<ImportCandidate> candidates = CandidateMerger.merge(List.of(a, b), List.of(), List.of()).candidates();

        assertThat(candidates).singleElement().satisfies(candidate -> {
            ProjectCard card = candidate.project();
            assertThat(candidate.key()).isEqualTo("projects.demo-recsys");
            assertThat(card.title()).isEqualTo(new ProjectCard.Text("示例推荐服务", "Demo Rec"));
            assertThat(card.aliases()).contains("DemoRec", "demorec-service", "Demo-Rec");
            assertThat(card.stack()).containsExactly("Java", "Redis");
            assertThat(card.sources()).containsExactly(A, B);
            assertThat(card.zh()).extracting(ProjectCard.Section::id)
                    .containsExactly("summary", "role", "approach", "results");
            assertThat(card.en().getFirst().text()).isEqualTo("English summary");
            assertThat(candidate.comparison()).isEqualTo(ImportCandidate.NEW);
        });
    }

    @Test
    void aProjectMatchingAnExistingCardKeepsItsSlug() {
        ProjectCard existing = new ProjectCard("demo", List.of(), new ProjectCard.Text("旧项目", null), null, null,
                null, null, List.of(), List.of(), Map.of(), List.of(), Set.of(), List.of(), List.of(), Map.of());
        Drafts a = drafts(A, List.of(), List.of(project("demo", "新项目", null, List.of(), Language.ZH, "x",
                List.of()), project("other", "另一个项目", null, List.of(), Language.ZH, "y", List.of())));

        List<ImportCandidate> candidates = CandidateMerger.merge(List.of(a), List.of(), List.of(existing))
                .candidates();

        // slug 和已有的卡相同：同一个项目，标题不同
        assertThat(candidates).extracting(ImportCandidate::key).containsExactly("projects.demo", "projects.other");
        assertThat(candidates.getFirst().existing()).containsEntry("zh", "旧项目");
        assertThat(candidates.getFirst().comparison()).isEqualTo(ImportCandidate.DIFFERENT);
    }

    @Test
    void factsAreComparedWithTheFactLayer() {
        List<Fact> existing = List.of(
                Fact.bilingual("contact.name", "林虚构", "Lin Xugou", FactClass.B, "user-confirmed", "2026-10-01",
                        Set.of(Language.ZH, Language.EN)),
                Fact.of("contact.email", "old@example.com", FactClass.B, "user-confirmed", "2026-10-01"));
        Drafts a = drafts(A, List.of(fact("contact.name", null, "林虚构", "README.md"),
                fact("contact.email", "new@example.com", null, "README.md"),
                fact("skills.backend", null, "Java", null)), List.of());

        List<ImportCandidate> candidates = CandidateMerger.merge(List.of(a), existing, List.of()).candidates();

        assertThat(candidates).extracting(ImportCandidate::comparison)
                .containsExactly(ImportCandidate.SAME, ImportCandidate.DIFFERENT, ImportCandidate.NEW);
        assertThat(candidates.get(0).decision().decision()).isEqualTo("same");
        assertThat(candidates.get(1).existing()).containsEntry("value", "old@example.com");
        assertThat(candidates.get(1).decision()).isNull();
        assertThat(candidates).extracting(ImportCandidate::group).containsExactly("basic", "basic", "skills");
    }

    @Test
    void sameKeyFromTwoSourcesKeepsTheOtherWritingAsAnAlternative() {
        Drafts a = drafts(A, List.of(fact("education.demo-u.major", null, "计算机科学", "README.md")), List.of());
        Drafts b = drafts(B, List.of(fact("education.demo-u.major", null, "计算机科学与技术", "docs/a.md"),
                fact("education.demo-u.school", null, "示例大学", null)), List.of());
        Drafts c = drafts(B, List.of(fact("education.demo-u.major", null, "计算机科学", "docs/a.md")), List.of());

        List<ImportCandidate> candidates = CandidateMerger.merge(List.of(a, b, c), List.of(), List.of()).candidates();

        ImportCandidate major = candidates.getFirst();
        assertThat(major.zh()).isEqualTo("计算机科学");
        assertThat(major.alternatives()).containsExactly(new ImportCandidate.Alternative(B, null, "计算机科学与技术",
                null));
        assertThat(major.origins()).extracting(ImportCandidate.Origin::source).containsExactly(A, B);
        assertThat(candidates).hasSize(2);
    }

    @Test
    void classCCandidatesAreDropped() {
        Drafts a = drafts(A, List.of(
                fact("contact.visa-status", null, "H1B", null),
                fact("contact.expected-salary", "30k", null, null),
                fact("contact.id-number", null, "身份证号：110101199001011234", null),
                fact("experience.demo.summary", null, "护照号 E12345678 已过期", null),
                fact("projects.health-app.title", null, "健康管理平台", null),
                fact("experience.demo-health.role", null, "健康管理平台后端开发", null)), List.of());

        CandidateMerger.Result result = CandidateMerger.merge(List.of(a), List.of(), List.of());

        // 普通文字里出现「健康」不算 C 级；projects.* 的事实不收（项目写成项目卡）
        assertThat(result.candidates()).extracting(ImportCandidate::key).containsExactly("experience.demo-health.role");
        assertThat(result.warnings()).anyMatch(w -> w.contains("丢弃了 4 条 C 级内容"));
    }

    @Test
    void contentWithoutALocatorIsKeptAndAnUnreadFileLocatorIsRemoved() {
        Drafts a = drafts(A, List.of(fact("skills.backend", null, "Java", null),
                fact("metrics.p99", null, "P99 45ms", "secret/notes.md#L3")), List.of(
                new ProjectDraft("demo", List.of(), "示例", null, null, null, null, null, List.of(), List.of(),
                        Language.ZH, Map.of("summary", "x"), List.of("README.md", "not-read.md"))));

        List<ImportCandidate> candidates = CandidateMerger.merge(List.of(a), List.of(), List.of()).candidates();

        assertThat(candidates.get(0).origins()).containsExactly(new ImportCandidate.Origin(A, null));
        assertThat(candidates.get(0).notes()).isEmpty();
        assertThat(candidates.get(1).origins()).containsExactly(new ImportCandidate.Origin(A, null));
        assertThat(candidates.get(1).notes()).singleElement().asString().contains("secret/notes.md#L3")
                .contains("请核对");
        assertThat(candidates.get(1).zh()).isEqualTo("P99 45ms");
        assertThat(candidates.get(2).origins()).containsExactly(new ImportCandidate.Origin(A, "README.md"));
        assertThat(candidates.get(2).notes()).singleElement().asString().contains("not-read.md");
    }

    @Test
    void invalidKeysAndValuesAreNotListed() {
        Drafts a = drafts(A, List.of(fact("Contact Name", null, "x", null), fact("name", null, "x", null),
                fact("contact.city", null, null, null), fact("contact.note", null, "两行\n文字", null),
                fact("Contact.City", null, "上海", null)), List.of());

        CandidateMerger.Result result = CandidateMerger.merge(List.of(a), List.of(), List.of());

        assertThat(result.candidates()).extracting(ImportCandidate::key).containsExactly("contact.city");
        assertThat(result.warnings()).anyMatch(w -> w.contains("4 条事实的键或值不合法"));
    }

    @Test
    void sectionTextLosesHeadingsAndFactReferences() {
        assertThat(CandidateMerger.sectionText("## 标题\n延迟 {{fact:p99}} 毫秒")).isEqualTo("标题\n延迟 p99 毫秒");
    }

    @Test
    void aProjectWithTheSameTextButNewStackOrPeriodIsDifferent() {
        ProjectDraft draft = project("demo", "示例", null, List.of(), Language.ZH, "中文介绍", List.of("Java"));
        ProjectCard existing = CandidateMerger.merge(List.of(drafts(A, List.of(), List.of(draft))), List.of(),
                List.of()).candidates().getFirst().project();
        assertThat(CandidateMerger.merge(List.of(drafts(A, List.of(), List.of(draft))), List.of(), List.of(existing))
                .candidates().getFirst().comparison()).isEqualTo(ImportCandidate.SAME);

        ProjectDraft moreStack = project("demo", "示例", null, List.of(), Language.ZH, "中文介绍",
                List.of("Java", "Redis"));
        ProjectDraft laterEnd = new ProjectDraft("demo", List.of(), "示例", null, "personal", "2025-01", "2025-09",
                null, List.of("Java"), List.of(), Language.ZH, Map.of("summary", "中文介绍"), List.of("README.md"));

        for (ProjectDraft changed : List.of(moreStack, laterEnd)) {
            ImportCandidate candidate = CandidateMerger.merge(List.of(drafts(A, List.of(), List.of(changed))),
                    List.of(), List.of(existing)).candidates().getFirst();
            assertThat(candidate.comparison()).isEqualTo(ImportCandidate.DIFFERENT);
            assertThat(candidate.decision()).isNull();
        }
    }

    @Test
    void aFactMatchingTheFactLayerButContradictedByAnotherSourceIsDifferent() {
        List<Fact> existing = List.of(Fact.of("contact.email", "lin@example.com", FactClass.B, "user-confirmed",
                "2026-10-01"));
        Drafts a = drafts(A, List.of(fact("contact.email", "lin@example.com", null, null)), List.of());
        Drafts b = drafts(B, List.of(fact("contact.email", "lin@other.example.com", null, null)), List.of());

        ImportCandidate candidate = CandidateMerger.merge(List.of(a, b), existing, List.of()).candidates().getFirst();

        assertThat(candidate.comparison()).isEqualTo(ImportCandidate.DIFFERENT);
        assertThat(candidate.decision()).isNull();
        assertThat(candidate.alternatives()).extracting(ImportCandidate.Alternative::value)
                .containsExactly("lin@other.example.com");
        assertThat(candidate.existing()).containsEntry("value", "lin@example.com");
    }
}

