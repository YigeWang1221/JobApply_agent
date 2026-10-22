package jobapply.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import jobapply.domain.Fact;
import jobapply.domain.FactClass;
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
