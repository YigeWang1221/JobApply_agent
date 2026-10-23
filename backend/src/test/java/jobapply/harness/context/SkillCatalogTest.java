package jobapply.harness.context;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jobapply.harness.context.SkillCatalog.LoadedSkill;
import jobapply.harness.context.SkillCatalog.Skill;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillCatalogTest {

    @TempDir
    Path root;

    @Test
    void parsesFrontmatterFields() throws IOException {
        write("components/date-picker/SKILL.md", """
                ---
                name: date-picker
                description: |
                  日期控件的经验，
                  分两行写的描述
                applies_to:
                  page_markers:
                    - ant-picker
                version: 2
                observed_count: 3
                ---
                正文
                """);

        Skill skill = SkillCatalog.scan(root, 8_000).find("date-picker").orElseThrow();

        assertThat(skill.description()).isEqualTo("日期控件的经验， 分两行写的描述");
        assertThat(skill.path()).isEqualTo("components/date-picker/SKILL.md");
        assertThat(skill.group()).isEqualTo("components");
        assertThat(skill.pageMarkers()).containsExactly("ant-picker");
        assertThat(skill.urlPatterns()).isEmpty();
        assertThat(skill.version()).isEqualTo("2");
        assertThat(skill.observedCount()).isEqualTo(3);
        assertThat(skill.isPlatform()).isFalse();
    }

    @Test
    void autoIncludesApplyFormAndMatchingPlatformsOnly() throws IOException {
        skill("apply-form", "apply-form", List.of());
        skill("components/date-picker", "date-picker", List.of("*"));
        skill("platforms/demo-ats", "demo-ats", List.of("*.demo-ats.example/*"));
        skill("platforms/other-ats", "other-ats", List.of("jobs.other.example/apply/*"));
        SkillCatalog catalog = SkillCatalog.scan(root, 8_000);

        assertThat(names(catalog.autoInclude("https://campus.demo-ats.example/position/1")))
                .containsExactly("apply-form", "demo-ats");
        assertThat(names(catalog.autoInclude("HTTPS://JOBS.OTHER.EXAMPLE/apply/42")))
                .containsExactly("apply-form", "other-ats");
        assertThat(names(catalog.autoInclude("https://jobs.other.example/list")))
                .containsExactly("apply-form");
        // components 即使模式命中也不自动带上
        assertThat(names(catalog.autoInclude("https://anything.example/"))).containsExactly("apply-form");
        assertThat(names(catalog.skills()))
                .containsExactly("apply-form", "date-picker", "demo-ats", "other-ats");
    }

    @Test
    void urlPatternTreatsOnlyStarAsWildcard() {
        assertThat(SkillCatalog.matchesUrl("*.demo.example/*", "https://a.demo.example/x?y=1")).isTrue();
        assertThat(SkillCatalog.matchesUrl("*.demo.example/*", "https://a.demoXexample/x")).isFalse();
        assertThat(SkillCatalog.matchesUrl("https://jobs.demo.example/*", "http://jobs.demo.example/1")).isTrue();
        assertThat(SkillCatalog.matchesUrl("jobs.demo.example/*", "https://evil.example/?jobs.demo.example/"))
                .isFalse();
    }

    @Test
    void loadReturnsBodyWithoutFrontmatter() throws IOException {
        skill("apply-form", "apply-form", List.of());

        LoadedSkill loaded = SkillCatalog.scan(root, 8_000).load("apply-form");

        assertThat(loaded.body()).isEqualTo("# apply-form\n\n经验正文。");
        assertThat(loaded.truncated()).isFalse();
    }

    @Test
    void loadTruncatesOverBudgetAndSaysSo() throws IOException {
        write("components/long/SKILL.md", "---\nname: long\ndescription: 很长\n---\n" + "经".repeat(120) + "\n");

        LoadedSkill loaded = SkillCatalog.scan(root, 100).load("long");

        assertThat(loaded.truncated()).isTrue();
        assertThat(loaded.originalLength()).isEqualTo(120);
        assertThat(loaded.body()).startsWith("经".repeat(100) + "\n\n（skill 正文共 120 个字符")
                .contains("已截断").doesNotContain("经".repeat(101));
    }

    @Test
    void malformedSkillIsReportedWithoutBreakingOthers() throws IOException {
        skill("apply-form", "apply-form", List.of());
        write("components/broken/SKILL.md", "---\nname: [unclosed\n---\n正文\n");
        write("components/nodesc/SKILL.md", "---\nname: nodesc\n---\n正文\n");
        write("components/wrong-dir/SKILL.md", "---\nname: other-name\ndescription: x\n---\n");
        write("components/dup/SKILL.md", "---\nname: dup\ndescription: x\n---\n");
        write("platforms/dup/SKILL.md", "---\nname: dup\ndescription: y\n---\n");

        SkillCatalog catalog = SkillCatalog.scan(root, 8_000);

        assertThat(names(catalog.skills())).containsExactly("apply-form", "dup");
        assertThat(catalog.unavailable()).extracting(SkillCatalog.Unavailable::path).containsExactly(
                "components/broken/SKILL.md", "components/nodesc/SKILL.md", "components/wrong-dir/SKILL.md",
                "platforms/dup/SKILL.md");
        assertThat(catalog.unavailable().get(1).reason()).contains("缺少 description");
        assertThatThrownBy(() -> catalog.load("nodesc")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skill 不可用：nodesc");
        assertThatThrownBy(() -> catalog.load("missing")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有这个 skill");
    }

    @Test
    void missingDirectoryIsAnEmptyCatalog() {
        SkillCatalog catalog = SkillCatalog.scan(root.resolve("nope"), 8_000);

        assertThat(catalog.skills()).isEmpty();
        assertThat(catalog.autoInclude("https://jobs.example.com/")).isEmpty();
    }

    private void skill(String dir, String name, List<String> urlPatterns) throws IOException {
        String patterns = urlPatterns.isEmpty() ? " []" : "\n" + String.join("\n",
                urlPatterns.stream().map(p -> "    - \"" + p + "\"").toList());
        write(dir + "/SKILL.md", "---\nname: " + name + "\ndescription: " + name + " 的描述\napplies_to:\n"
                + "  url_patterns:" + patterns + "\nversion: 1\nobserved_count: 0\n---\n\n# " + name
                + "\n\n经验正文。\n");
    }

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, UTF_8);
    }

    private static List<String> names(List<Skill> skills) {
        return skills.stream().map(Skill::name).toList();
    }
}
