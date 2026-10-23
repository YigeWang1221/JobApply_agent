package jobapply.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import jobapply.persistence.EvidenceCardFile.EvidenceCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvidenceCardFileTest {

    @TempDir
    Path root;

    @Test
    void roundTripsAndKeepsUnknownKeys() {
        TestStorage storage = TestStorage.create(root, true);
        EvidenceCardFile cards = new EvidenceCardFile(storage.layout, storage.clock);
        EvidenceCard card = new EvidenceCard("s-2026-10-22-001", "files:0123abcd", "2026-10-22T10:00:00-04:00",
                Map.of("model", "示例模型"), "\n# 清单卡\n\n- README.md：示例项目的介绍\n");

        cards.save(card);

        assertThat(cards.find("s-2026-10-22-001")).contains(card);
        assertThat(cards.find("s-2026-10-22-002")).isEmpty();
        assertThat(storage.isClean()).isTrue();

        storage.write(storage.layout.evidenceCard("s-2026-10-22-003"), "---\nsource: s-2026-10-22-003\nextra: 1\n---\n正文\n");
        EvidenceCard handWritten = cards.find("s-2026-10-22-003").orElseThrow();
        assertThat(handWritten.extra()).containsEntry("extra", 1);
        assertThat(handWritten.body()).isEqualTo("正文\n");
    }
}
