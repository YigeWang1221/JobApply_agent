package jobapply.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import jobapply.persistence.GlossaryFile.Term;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GlossaryFileTest {

    @TempDir
    Path root;

    @Test
    void putReplacesByChineseAndKeepsOtherRowsAndColumns() {
        TestStorage storage = TestStorage.create(root, false);
        storage.write(storage.layout.baseGlossary(), """
                # 术语对照

                | 中文 | English | 类别 | 说明 | 出处 |
                | --- | --- | --- | --- | --- |
                | 示例大学 | Example University | 学校 | 官方译名 | 学校官网 |
                | 只写了一半 | — | — | — | — |
                """);
        GlossaryFile glossary = new GlossaryFile(storage.layout, storage.clock);

        glossary.put(new Term("计算机科学", "Computer Science", "专业", "", Map.of()));
        glossary.put(new Term("示例大学", "Example Univ.", "学校", "简写", Map.of("出处", "学校官网")));

        assertThat(glossary.list()).extracting(Term::zh).containsExactly("示例大学", "计算机科学");
        assertThat(glossary.find("示例大学").orElseThrow().en()).isEqualTo("Example Univ.");
        assertThat(glossary.find("示例大学").orElseThrow().extra()).containsEntry("出处", "学校官网");
        assertThat(storage.read(storage.layout.baseGlossary())).contains("| 只写了一半 |");
    }
}
