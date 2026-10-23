package jobapply.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import jobapply.harness.context.SkillCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlaybookSeederTest {

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(\\+?\\d[\\d -]{9,}\\d)(?!\\d)");
    private static final Pattern CN_ID = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");

    @TempDir
    Path root;

    @Test
    void copiesAllBundledSkillsAndCommitsOnce() {
        TestStorage storage = TestStorage.create(root);

        List<Path> written = new PlaybookSeeder(storage.layout, storage.clock).seed();

        assertThat(written).hasSize(PlaybookSeeder.BUNDLED.size());
        for (String relative : PlaybookSeeder.BUNDLED) {
            assertThat(storage.read(storage.layout.playbookSkills().resolve(relative)))
                    .isEqualTo(PlaybookSeeder.bundled(relative));
        }
    }

    @Test
    void neverOverwritesExistingFilesAndFillsOnlyGaps() {
        TestStorage storage = TestStorage.create(root);
        Path edited = storage.layout.playbookSkills().resolve("apply-form/SKILL.md");
        storage.write(edited, "---\nname: apply-form\ndescription: 用户改过\n---\n改过的正文\n");
        PlaybookSeeder seeder = new PlaybookSeeder(storage.layout, storage.clock);

        List<Path> first = seeder.seed();
        List<Path> second = seeder.seed();

        assertThat(first).hasSize(PlaybookSeeder.BUNDLED.size() - 1).doesNotContain(edited);
        assertThat(storage.read(edited)).contains("改过的正文");
        assertThat(second).isEmpty();
    }

    @Test
    void bundledListMatchesResourceDirectory() throws IOException, URISyntaxException {
        Path resources = Path.of(Objects.requireNonNull(
                PlaybookSeeder.class.getClassLoader().getResource(PlaybookSeeder.RESOURCE_ROOT)).toURI());
        try (Stream<Path> walk = Files.walk(resources)) {
            List<String> found = walk.filter(Files::isRegularFile)
                    .map(path -> resources.relativize(path).toString().replace('\\', '/'))
                    .sorted().toList();
            assertThat(found).containsExactlyInAnyOrderElementsOf(PlaybookSeeder.BUNDLED);
        }
    }

    @Test
    void everyBundledSkillParsesFitsBudgetAndHasNoPersonalData() {
        TestStorage storage = TestStorage.create(root);
        new PlaybookSeeder(storage.layout, storage.clock).seed();

        SkillCatalog catalog = SkillCatalog.scan(storage.layout);

        assertThat(catalog.unavailable()).isEmpty();
        assertThat(catalog.skills()).hasSize(PlaybookSeeder.BUNDLED.size());
        assertThat(catalog.skills()).noneMatch(SkillCatalog.Skill::isPlatform);
        assertThat(catalog.autoInclude("https://jobs.example.com/apply")).extracting(SkillCatalog.Skill::name)
                .containsExactly(SkillCatalog.APPLY_FORM);
        for (String relative : PlaybookSeeder.BUNDLED) {
            String content = PlaybookSeeder.bundled(relative);
            assertThat(content.length()).as(relative).isLessThanOrEqualTo(SkillCatalog.BODY_BUDGET);
            assertThat(EMAIL.matcher(content).find()).as(relative + " 不含邮箱").isFalse();
            assertThat(PHONE.matcher(content).find()).as(relative + " 不含电话号码").isFalse();
            assertThat(CN_ID.matcher(content).find()).as(relative + " 不含证件号").isFalse();
        }
        for (SkillCatalog.Skill skill : catalog.skills()) {
            assertThat(catalog.load(skill.name()).truncated()).as(skill.name()).isFalse();
        }
    }
}
