package jobapply.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import jobapply.domain.Claim;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LedgerFileTest {

    @TempDir
    Path root;

    @Test
    void idsAreSequentialAndNeverReused() {
        TestStorage storage = TestStorage.create(root);
        LedgerFile ledger = new LedgerFile(storage.layout, storage.clock);

        Claim first = ledger.add(Claim.draft("经历", "实习期间负责订单服务的接口开发", "s-2026-10-22-001", "README.md#L12",
                Claim.Status.OWNER_ATTESTED));
        Claim second = ledger.add(Claim.draft("指标", "接口延迟降低 40%", "", "", Claim.Status.VERIFIED));
        ledger.remove(second.id());
        Claim third = ledger.add(Claim.draft("技能", "熟悉 Go", "", "", Claim.Status.NEEDS_VERIFICATION));

        assertThat(first.id()).isEqualTo("c-0001");
        assertThat(second.id()).isEqualTo("c-0002");
        assertThat(third.id()).isEqualTo("c-0003");
        assertThat(ledger.ids()).containsExactly("c-0001", "c-0003");
        assertThatThrownBy(() -> ledger.remove("c-0002")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void defaultUses() {
        assertThat(Claim.defaultUse(Claim.Status.VERIFIED)).isEqualTo(Claim.Use.FORMS_AND_RESUME);
        assertThat(Claim.defaultUse(Claim.Status.PARTIAL)).isEqualTo(Claim.Use.FORMS_AND_RESUME);
        // 本人确认的指标默认可以写简历、填表
        assertThat(Claim.defaultUse(Claim.Status.OWNER_ATTESTED)).isEqualTo(Claim.Use.FORMS_AND_RESUME);
        // 差异不自动禁用，由用户选择
        assertThat(Claim.defaultUse(Claim.Status.DISCREPANCY)).isEqualTo(Claim.Use.FORMS_AND_RESUME);
        assertThat(Claim.defaultUse(Claim.Status.NEEDS_VERIFICATION)).isEqualTo(Claim.Use.NONE);

        TestStorage storage = TestStorage.create(root);
        LedgerFile ledger = new LedgerFile(storage.layout, storage.clock);
        ledger.add(Claim.draft("指标", "QPS 12000", "", "", Claim.Status.OWNER_ATTESTED));
        Claim read = ledger.find("c-0001").orElseThrow();
        assertThat(read.use()).isEqualTo(Claim.Use.FORMS_AND_RESUME);
        assertThat(read.locator()).isEmpty();
        assertThat(storage.read(storage.layout.baseLedger()))
                .contains("| c-0001 | 指标 | QPS 12000 | — | — | — | owner_attested | forms_and_resume | — |");

        ledger.update(new Claim("c-0001", "指标", "QPS 12000", "", "", "压测 4 核", Claim.Status.OWNER_ATTESTED,
                Claim.Use.INTERVIEW_ONLY, "2026-10-22", null));
        assertThat(ledger.find("c-0001").orElseThrow().use()).isEqualTo(Claim.Use.INTERVIEW_ONLY);
    }

    @Test
    void unknownColumnsAndSurroundingTextArePreserved() {
        TestStorage storage = TestStorage.create(root);
        storage.write(storage.layout.baseLedger(), """
                ---
                last_claim: c-0009
                ---
                # 论断台账

                手写的说明。

                | id | 类别 | 论断 | 来源 | 出处 | 值与条件 | 状态 | 用途 | 确认时间 | 备注 |
                | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
                | c-0004 | 经历 | 负责接口开发 | — | — | — | partial | — | — | 我自己加的列 |

                ## 之后再看
                """);
        LedgerFile ledger = new LedgerFile(storage.layout, storage.clock);

        Claim added = ledger.add(Claim.draft("技能", "熟悉 Go", "", "", Claim.Status.OWNER_ATTESTED));

        assertThat(added.id()).isEqualTo("c-0010");
        assertThat(ledger.find("c-0004").orElseThrow().extra()).containsEntry("备注", "我自己加的列");
        assertThat(ledger.find("c-0004").orElseThrow().use()).isEqualTo(Claim.Use.FORMS_AND_RESUME);
        String text = storage.read(storage.layout.baseLedger());
        assertThat(text).contains("手写的说明。").contains("## 之后再看").contains("| 我自己加的列 |")
                .contains("last_claim: c-0010");
        assertThatThrownBy(() -> ledger.add(Claim.draft("技能", "a | b", "", "", Claim.Status.VERIFIED)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("竖线");
    }

    @Test
    void badStatusIsAFormatError() {
        TestStorage storage = TestStorage.create(root);
        storage.write(storage.layout.baseLedger(), """
                | id | 类别 | 论断 | 来源 | 出处 | 值与条件 | 状态 | 用途 | 确认时间 |
                | --- | --- | --- | --- | --- | --- | --- | --- | --- |
                | c-0001 | 经历 | 负责接口开发 | — | — | — | maybe | — | — |
                """);

        assertThatThrownBy(() -> new LedgerFile(storage.layout, storage.clock).list())
                .isInstanceOf(StorageFormatException.class).hasMessageContaining("c-0001");
    }

    @Test
    void addAllNumbersInOrderAndWritesOnce() {
        TestStorage storage = TestStorage.create(root);
        LedgerFile ledger = new LedgerFile(storage.layout, storage.clock);
        ledger.add(Claim.draft("技能", "会写 Java", null, null, Claim.Status.OWNER_ATTESTED));

        var added = ledger.addAll(List.of(
                Claim.draft("指标", "P99 45ms", "s-2026-10-22-001", "README.md#L5", Claim.Status.NEEDS_VERIFICATION),
                Claim.draft("项目", "示例项目", null, null, Claim.Status.NEEDS_VERIFICATION)));

        assertThat(added).extracting(Claim::id).containsExactly("c-0002", "c-0003");
        assertThat(ledger.list()).hasSize(3);
        assertThat(ledger.addAll(List.of())).isEmpty();
    }
}
