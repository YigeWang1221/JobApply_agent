package jobapply.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import jobapply.domain.Fact;
import jobapply.domain.FactClass;
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
                new Fact("skills.languages", "Java, Go", FactClass.A, "resume:resume-demo", "2026-10-13",
                        "2024-09", null, null, null, false),
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
}
