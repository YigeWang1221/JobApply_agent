package jobapply.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import jobapply.domain.Fact;
import jobapply.domain.FactClass;
import jobapply.domain.Language;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FactsFileTest {

    @TempDir
    Path root;

    @Test
    void parsesAllFieldsOfAnEntry() {
        String content = """
                ---
                tags: [jobapply/facts]
                ---

                # 个人基础事实

                ## education.masters.program_name
                - value: 示例大学 计算机科学硕士
                - class: A
                - source: user-confirmed
                - confirmed_at: 2026-10-13
                - valid_from / valid_to: 2025-01 / —
                - precision: year-month
                - notes: 与旧简历写法不一致

                ## contact.email
                - value: someone@example.com
                - class: B
                """;

        List<Fact> facts = FactsFile.parse(content, "facts.md");

        assertThat(facts).hasSize(2);
        Fact program = facts.getFirst();
        assertThat(program.key()).isEqualTo("education.masters.program_name");
        assertThat(program.value()).isEqualTo("示例大学 计算机科学硕士");
        assertThat(program.factClass()).isEqualTo(FactClass.A);
        assertThat(program.confirmedAt()).isEqualTo("2026-10-13");
        assertThat(program.validFrom()).isEqualTo("2025-01");
        assertThat(program.validTo()).isNull();
        assertThat(program.precision()).isEqualTo("year-month");
        assertThat(facts.get(1).factClass()).isEqualTo(FactClass.B);
    }

    @Test
    void writeThenReadRoundTripsIncludingUnset() {
        Path file = root.resolve("overrides.md");
        List<Fact> facts = List.of(
                new Fact("skills.languages", "Java, Go", null, null, FactClass.A, "resume:resume-demo", null, null,
                        "2026-10-13", "2024-09", null, null, null, false),
                Fact.unsetOf("location.current_city"));

        FactsFile.write(file, "overrides", facts);

        assertThat(FactsFile.read(file)).isEqualTo(facts);
        assertThat(FactsFile.read(root.resolve("missing.md"))).isEmpty();
    }

    @Test
    void classCIsRejectedOnReadAndCannotBeWritten() {
        String content = "## identity.document_number\n- value: 不应出现\n- class: C\n";

        assertThatThrownBy(() -> FactsFile.parse(content, "facts.md"))
                .isInstanceOf(StorageFormatException.class)
                .hasMessageContaining("C 级");
        assertThatThrownBy(() -> Fact.of("identity.document_number", "x", FactClass.C, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void classCPlaceholderIsReadAndWritten() {
        Path file = root.resolve("facts.md");
        List<Fact> facts = List.of(Fact.of("sensitive.id_number", "{ID_NUMBER}", FactClass.C, "user-confirmed", null));
        FactsFile.write(file, "facts", facts);

        assertThat(FactsFile.read(file)).isEqualTo(facts);
        assertThatThrownBy(() -> FactsFile.parse("## sensitive.id_number\n- value: {ID_NUMBER} 和明文\n- class: C\n",
                "facts.md")).isInstanceOf(StorageFormatException.class).hasMessageContaining("占位符");
    }

    @Test
    void duplicateKeysAreRejectedBeforeWriting() {
        Path file = root.resolve("overrides.md");
        List<Fact> original = List.of(Fact.of("skills.languages", "Java", FactClass.A, null, null));
        FactsFile.write(file, "overrides", original);

        assertThatThrownBy(() -> FactsFile.write(file, "overrides", List.of(
                Fact.of("skills.languages", "Java", FactClass.A, null, null),
                Fact.of("skills.languages", "Go", FactClass.A, null, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");
        assertThat(FactsFile.read(file)).isEqualTo(original);
    }

    @Test
    void malformedEntriesAreReported() {
        assertThatThrownBy(() -> FactsFile.parse("## a\n- value: 1\n- class: A\n## a\n- value: 2\n- class: A\n", "f.md"))
                .isInstanceOf(StorageFormatException.class).hasMessageContaining("重复");
        assertThatThrownBy(() -> FactsFile.parse("## a\n- value: 1\n- class: A\n- colour: 蓝\n", "f.md"))
                .isInstanceOf(StorageFormatException.class).hasMessageContaining("未知字段");
        assertThatThrownBy(() -> FactsFile.parse("## a\n- value: 1\n", "f.md"))
                .isInstanceOf(StorageFormatException.class).hasMessageContaining("class");
    }

    @Test
    void bilingualFieldsRoundTripAndOldValueOnlyFilesStillRead() {
        Path file = root.resolve("facts.md");
        List<Fact> facts = List.of(
                new Fact("experience.internship-1.role", null, "后端开发实习生", "Backend Engineering Intern",
                        FactClass.A, "user-confirmed", "c-0007", Set.of(Language.ZH), "2026-10-22", "2025-06",
                        "2025-09", "year-month", null, false),
                Fact.bilingual("education.school", "示例大学", null, FactClass.A, "resume:a-2026-10-22-001",
                        "2026-10-22", Set.of(Language.ZH, Language.EN)),
                Fact.of("contact.email", "someone@example.com", FactClass.B, "user-confirmed", "2026-10-22"));

        FactsFile.write(file, "个人基础事实", facts);

        assertThat(FactsFile.read(file)).isEqualTo(facts);
        String text = TestStorage.open(root).read(file);
        assertThat(text).contains("- zh: 后端开发实习生\n- en: Backend Engineering Intern\n")
                .contains("- claim: c-0007\n- reviewed: zh\n")
                .contains("- reviewed: zh, en\n");
        Fact old = FactsFile.parse("## skills.languages\n- value: Java\n- class: A\n", "f.md").getFirst();
        assertThat(old.value()).isEqualTo("Java");
        assertThat(old.isBilingual()).isFalse();
        assertThat(old.reviewed()).isEmpty();
    }

    @Test
    void valueAndLanguageTextCannotBeMixed() {
        assertThatThrownBy(() -> FactsFile.parse("## a\n- value: 1\n- zh: 一\n- class: A\n", "f.md"))
                .isInstanceOf(StorageFormatException.class).hasMessageContaining("二选一");
        assertThatThrownBy(() -> new Fact("a", "1", null, "one", FactClass.A, null, null, null, null, null, null,
                null, null, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("二选一");
        assertThatThrownBy(() -> FactsFile.parse("## a\n- zh: 一\n- class: A\n- reviewed: fr\n", "f.md"))
                .isInstanceOf(StorageFormatException.class).hasMessageContaining("语言");
        assertThatThrownBy(() -> FactsFile.parse("## a\n- zh: 一\n- class: A\n- claim: 7\n", "f.md"))
                .isInstanceOf(StorageFormatException.class).hasMessageContaining("c-NNNN");
    }
}
