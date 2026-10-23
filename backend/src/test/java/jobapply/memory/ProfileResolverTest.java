package jobapply.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jobapply.domain.Fact;
import jobapply.domain.FactClass;
import jobapply.domain.Language;
import jobapply.domain.ProjectSpec;
import jobapply.persistence.FactsFile;
import jobapply.persistence.FrontmatterFile;
import jobapply.persistence.TestStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileResolverTest {

    @TempDir
    Path root;

    @Test
    void layersApplyInOrderWithSourceLabels() {
        TestStorage storage = TestStorage.create(root, true);
        writeBase(storage, "示例大学", "上海");
        storage.project("backend-2024", "live");
        storage.project("ai-infra-2025", "live", "backend-2024");
        FactsFile.write(storage.layout.overrides("backend-2024"), "overrides", List.of(
                Fact.of("skills.focus", "后端", FactClass.A, "user-confirmed", "2024-05-01"),
                Fact.of("education.school", "父层的写法", FactClass.A, "user-confirmed", "2024-05-01")));
        FactsFile.write(storage.layout.overrides("ai-infra-2025"), "overrides", List.of(
                Fact.of("education.school", "示例大学（2025 写法）", FactClass.A, "user-confirmed", "2026-10-13"),
                Fact.unsetOf("location.current_city")));
        List<Fact> sessionAnswers = List.of(Fact.of("contact.phone_tail", "0000", FactClass.B, "user-confirmed", null));

        Map<String, ResolvedFact> profile = resolver(storage).resolve("ai-infra-2025", sessionAnswers);

        assertThat(profile).containsOnlyKeys("contact.name", "contact.phone_tail", "education.school", "skills.focus");
        assertThat(profile.get("contact.phone_tail").label()).isEqualTo("来自本 Session 答案");
        assertThat(profile.get("education.school").value()).isEqualTo("示例大学（2025 写法）");
        assertThat(profile.get("education.school").label()).isEqualTo("来自 Project ai-infra-2025 / overrides，2026-10-13 确认");
        assertThat(profile.get("skills.focus").label()).isEqualTo("来自 Project backend-2024 / overrides，2024-05-01 确认");
        assertThat(profile.get("contact.name").label()).isEqualTo("来自 base（pin live）");
        assertThat(profile.get("contact.name").factClass()).isEqualTo(FactClass.B);
    }

    @Test
    void pinnedCommitKeepsTheOldBase() {
        TestStorage storage = TestStorage.create(root, true);
        writeBase(storage, "旧学校写法", "上海");
        String v1 = storage.git().head().orElseThrow();
        writeBase(storage, "新学校写法", "北京");
        storage.project("frozen", v1.substring(0, 10));
        storage.project("following", "live");

        assertThat(resolver(storage).resolve("frozen", List.of()).get("education.school").value()).isEqualTo("旧学校写法");
        assertThat(resolver(storage).resolve("frozen", List.of()).get("education.school").label())
                .isEqualTo("来自 base（pin " + v1.substring(0, 10) + "）");
        assertThat(resolver(storage).resolve("following", List.of()).get("education.school").value())
                .isEqualTo("新学校写法");
    }

    @Test
    void pinnedDateReadsTheLastCommitOfThatDay() {
        TestStorage storage = TestStorage.create(root, true);
        storage.clock.set("2026-10-20T15:00:00Z");
        writeBase(storage, "10 月 20 日的写法", "上海");
        storage.clock.set("2026-10-23T15:00:00Z");
        writeBase(storage, "10 月 23 日的写法", "上海");
        storage.project("as-of-21", "2026-10-21");
        storage.project("as-of-01", "2026-10-01");

        assertThat(resolver(storage).resolve("as-of-21", List.of()).get("education.school").value())
                .isEqualTo("10 月 20 日的写法");
        assertThatThrownBy(() -> resolver(storage).resolve("as-of-01", List.of()))
                .isInstanceOf(ProfileResolutionException.class)
                .hasMessageContaining("找不到 base_pin 2026-10-01");
    }

    @Test
    void cyclesAndMissingParentsAreReported() {
        TestStorage storage = TestStorage.create(root, true);
        storage.project("a", "live");
        storage.project("b", "live", "a");
        setInherits(storage, "a", List.of("base", "projects/b"));

        assertThatThrownBy(() -> resolver(storage).resolve("a", List.of()))
                .isInstanceOf(ProfileResolutionException.class)
                .hasMessage("循环继承：a → b → a");

        setInherits(storage, "a", List.of("base", "projects/ghost"));
        assertThatThrownBy(() -> resolver(storage).resolve("b", List.of()))
                .isInstanceOf(ProfileResolutionException.class)
                .hasMessage("父 Project 不存在：ghost");
    }

    @Test
    void inheritsMustContainBaseAndParentsMustExist() {
        TestStorage storage = TestStorage.create(root, true);

        assertThatThrownBy(() -> new ProjectSpec("x", "x", List.of("projects/a"), "live", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须包含 base");
        assertThatThrownBy(() -> storage.project("x", "live", "nobody"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("父 Project 不存在：nobody");
    }

    @Test
    void withoutGitOnlyLivePinsWork() {
        TestStorage storage = TestStorage.create(root, false);
        writeBaseWithoutCommit(storage, "示例大学");

        assertThatThrownBy(() -> storage.project("frozen", "2026-10-21"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有开启 git");
        storage.project("live-one", "live");
        assertThat(resolver(storage).resolve("live-one", List.of()).get("education.school").value()).isEqualTo("示例大学");

        Path projectFile = storage.layout.projectFile("live-one");
        FrontmatterFile.Document document = FrontmatterFile.read(projectFile);
        document.frontmatter().put("base_pin", "2026-10-21");
        FrontmatterFile.write(projectFile, document);
        assertThatThrownBy(() -> resolver(storage).resolve("live-one", List.of()))
                .isInstanceOf(ProfileResolutionException.class)
                .hasMessageContaining("需要开启 git");
    }

    @Test
    void resolvesByLanguageWithUnreviewedMarksAndLocalFormats() {
        TestStorage storage = TestStorage.create(root, true);
        FactsFile.write(storage.layout.baseFacts(), "个人基础事实", List.of(
                Fact.bilingual("experience.internship-1.role", "后端开发实习生", "Backend Engineering Intern",
                        FactClass.A, "user-confirmed", "2026-10-22", Set.of(Language.ZH)),
                Fact.bilingual("education.school", "示例大学", null, FactClass.A, "user-confirmed", "2026-10-22",
                        Set.of(Language.ZH)),
                Fact.of("education.start", "2024-09", FactClass.A, "user-confirmed", "2026-10-22"),
                Fact.of("education.graduated_on", "2026-05-20", FactClass.B, "user-confirmed", "2026-10-22"),
                Fact.of("contact.phone_tail", "0012", FactClass.B, "user-confirmed", "2026-10-22")));
        storage.project("p", "live");

        Map<String, ResolvedFact> zh = resolver(storage).resolve("p", List.of(), Language.ZH);
        Map<String, ResolvedFact> en = resolver(storage).resolve("p", List.of(), Language.EN);

        assertThat(zh.get("experience.internship-1.role").value()).isEqualTo("后端开发实习生");
        assertThat(zh.get("experience.internship-1.role").unreviewed()).isFalse();
        assertThat(zh.get("experience.internship-1.role").label()).isEqualTo("来自 base（pin live）");
        assertThat(en.get("experience.internship-1.role").value()).isEqualTo("Backend Engineering Intern");
        assertThat(en.get("experience.internship-1.role").unreviewed()).isTrue();
        assertThat(en.get("experience.internship-1.role").label()).isEqualTo("来自 base（pin live）；en 文字未审阅");
        // 没有英文时用中文代替，并标为未审阅
        assertThat(en.get("education.school").value()).isEqualTo("示例大学");
        assertThat(en.get("education.school").language()).isEqualTo(Language.ZH);
        assertThat(en.get("education.school").unreviewed()).isTrue();

        assertThat(zh.get("education.start").value()).isEqualTo("2024-09");
        assertThat(zh.get("education.start").display()).isEqualTo("2024年9月");
        assertThat(en.get("education.start").display()).isEqualTo("Sep 2024");
        assertThat(zh.get("education.graduated_on").display()).isEqualTo("2026年5月20日");
        assertThat(en.get("education.graduated_on").display()).isEqualTo("May 20, 2026");
        assertThat(en.get("education.start").language()).isNull();
        // 纯数字不格式化（电话尾号）
        assertThat(en.get("contact.phone_tail").display()).isEqualTo("0012");
    }

    @Test
    void projectCardsJoinTheBaseLayerAndFollowThePin() {
        TestStorage storage = TestStorage.create(root, true);
        writeBase(storage, "示例大学", "上海");
        storage.write(storage.layout.baseProjectCard("demo"), card("12000"));
        storage.git().commit("项目卡", List.of(storage.layout.baseProjectCard("demo")));
        String v1 = storage.git().head().orElseThrow();
        storage.write(storage.layout.baseProjectCard("demo"), card("15000"));
        storage.git().commit("项目卡更新", List.of(storage.layout.baseProjectCard("demo")));
        storage.project("live-one", "live");
        storage.project("frozen", v1);
        FactsFile.write(storage.layout.overrides("live-one"), "overrides", List.of(
                Fact.bilingual("projects.demo.title", "示例项目（本侧重的写法）", null, FactClass.A, "user-confirmed",
                        "2026-10-22", Set.of(Language.ZH))));

        Map<String, ResolvedFact> en = resolver(storage).resolve("live-one", List.of(), Language.EN);
        Map<String, ResolvedFact> zh = resolver(storage).resolve("live-one", List.of(), Language.ZH);

        assertThat(en.get("projects.demo.facts.qps").display()).isEqualTo("15,000 QPS");
        assertThat(en.get("projects.demo.sections.results").value()).isEqualTo("Reached 15,000 QPS.");
        assertThat(en.get("projects.demo.sections.results").unreviewed()).isFalse();
        assertThat(zh.get("projects.demo.sections.results").value()).isEqualTo("QPS 达到 15,000 QPS。");
        assertThat(zh.get("projects.demo.stack").value()).isEqualTo("Java, Redis");
        assertThat(zh.get("projects.demo.period.from").display()).isEqualTo("2025年1月");
        assertThat(zh.get("projects.demo.links.code").value()).isEqualTo("https://github.com/example-owner/demo");
        assertThat(zh.get("projects.demo.title").label()).startsWith("来自 Project live-one / overrides");
        assertThat(resolver(storage).resolve("frozen", List.of(), Language.EN).get("projects.demo.facts.qps").display())
                .isEqualTo("12,000 QPS");
    }

    @Test
    void factsMdOverridingACardNumberAlsoOverridesItsDisplay() {
        TestStorage storage = TestStorage.create(root, true);
        storage.write(storage.layout.baseProjectCard("demo"), card("12000"));
        FactsFile.write(storage.layout.baseFacts(), "个人基础事实", List.of(
                Fact.of("projects.demo.facts.qps", "15000", FactClass.A, "user-confirmed", "2026-10-22")));
        storage.project("p", "live");

        ResolvedFact qps = resolver(storage).resolve("p", List.of(), Language.EN).get("projects.demo.facts.qps");

        assertThat(qps.value()).isEqualTo("15000");
        assertThat(qps.display()).isEqualTo("15000").doesNotContain("12");
    }

    private static String card(String qps) {
        return """
                ---
                slug: demo
                title: {zh: 示例项目, en: Demo project}
                kind: personal
                period: {from: 2025-01, to: 2025-05, precision: year-month}
                stack: [Java, Redis]
                links: [{kind: code, url: https://github.com/example-owner/demo}]
                facts:
                  qps: {value: "%s", unit: QPS}
                reviewed: [zh, en]
                ---

                ## zh

                ### 结果 {#results}
                QPS 达到 {{fact:qps}}。

                ## en

                ### Results {#results}
                Reached {{fact:qps}}.
                """.formatted(qps);
    }

    private static ProfileResolver resolver(TestStorage storage) {
        return new ProfileResolver(storage.layout, storage.clock);
    }

    private static void writeBase(TestStorage storage, String school, String city) {
        FactsFile.write(storage.layout.baseFacts(), "个人基础事实", List.of(
                Fact.of("contact.name", "示例用户", FactClass.B, "user-confirmed", "2026-10-01"),
                Fact.of("education.school", school, FactClass.A, "user-confirmed", "2026-10-01"),
                Fact.of("location.current_city", city, FactClass.B, "user-confirmed", "2026-10-01")));
        storage.git().commit("base 更新", List.of(storage.layout.baseFacts()));
    }

    private static void writeBaseWithoutCommit(TestStorage storage, String school) {
        FactsFile.write(storage.layout.baseFacts(), "个人基础事实",
                List.of(Fact.of("education.school", school, FactClass.A, "user-confirmed", "2026-10-01")));
    }

    private static void setInherits(TestStorage storage, String projectId, List<String> inherits) {
        Path file = storage.layout.projectFile(projectId);
        FrontmatterFile.Document document = FrontmatterFile.read(file);
        document.frontmatter().put("inherits", inherits);
        FrontmatterFile.write(file, document);
    }
}
