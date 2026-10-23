package jobapply.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jobapply.domain.Language;
import jobapply.domain.ProjectCard;
import jobapply.domain.ProjectCard.CardFact;
import jobapply.domain.ProjectCard.Section;
import jobapply.domain.ProjectCard.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 项目卡：小节对应、可选引用。项目和数字都是虚构的。
 */
class ProjectCardFileTest {

    static final String CARD = """
            ---
            slug: distributed-training
            aliases: [训练平台, trainer]
            title:
              zh: 分布式训练平台
              en: Distributed Training Platform
            kind: course
            period: {from: 2025-01, to: 2025-05, precision: year-month}
            team_size: 3
            role_claim: c-0012
            stack: [PyTorch, Slurm]
            links: [{kind: code, url: https://github.com/example-owner/example-repo}]
            facts:
              speedup: {value: "3.9", unit: "×", condition: {zh: 8 卡对比单卡, en: 8 GPUs vs. 1}, claim: c-0015}
            sources: [s-2026-10-22-001, a-2026-10-22-002]
            reviewed: [zh]
            my_note: 自己加的键
            ---

            ## zh

            ### 一句话 {#summary}
            课程项目：多机训练调度。

            ### 结果 {#results}
            吞吐提升 {{fact:speedup}}。

            ## en

            ### Summary {#summary}
            Course project: multi-node training scheduler.

            ### Results {#results}
            Throughput improved {{fact:speedup}}.
            """;

    @TempDir
    Path root;

    @Test
    void parsesRendersAndRoundTrips() {
        ProjectCard card = ProjectCardFile.parse(CARD, "card.md");

        assertThat(card.title()).isEqualTo(new Text("分布式训练平台", "Distributed Training Platform"));
        assertThat(card.period().from()).isEqualTo("2025-01");
        assertThat(card.teamSize()).isEqualTo(3);
        assertThat(card.facts().get("speedup").value()).isEqualTo("3.9");
        assertThat(card.reviewed()).containsExactly(Language.ZH);
        assertThat(card.extra()).containsEntry("my_note", "自己加的键");
        assertThat(card.zh()).extracting(Section::id).containsExactly("summary", "results");
        assertThat(card.render(card.zh().get(1).text(), Language.ZH)).isEqualTo("吞吐提升 3.9×。");
        assertThat(ProjectCardFile.parse(ProjectCardFile.render(card), "again.md")).isEqualTo(card);

        TestStorage storage = TestStorage.create(root, true);
        ProjectCardFile cards = new ProjectCardFile(storage.layout, storage.clock);
        cards.save(card, Set.of("c-0012", "c-0015"));
        assertThat(cards.list()).containsExactly(card);
        assertThat(storage.isClean()).isTrue();
    }

    @Test
    void sectionsMustCorrespondWhenBothLanguagesArePresent() {
        String swapped = CARD.replace("### Summary {#summary}", "### Summary {#overview}");

        assertThatThrownBy(() -> ProjectCardFile.parse(swapped, "card.md"))
                .isInstanceOf(StorageFormatException.class).hasMessageContaining("中英文小节不对应");
        // 只写了一种语言（另一种还没起草）时不检查
        ProjectCard zhOnly = card(List.of(new Section("summary", "一句话", "只有中文")), List.of(), Map.of());
        assertThat(zhOnly.en()).isEmpty();
        assertThatThrownBy(() -> card(List.of(new Section("summary", "一句话", "a"), new Section("role", "职责", "b")),
                List.of(new Section("role", "Role", "b"), new Section("summary", "Summary", "a")), Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不对应");
    }

    @Test
    void numbersMayBeWrittenDirectlyAndReferencesAreOptional() {
        ProjectCard direct = card(List.of(new Section("results", "结果", "接口延迟降低 40%，QPS 达到 12000。")),
                List.of(new Section("results", "Results", "Cut latency by 40% and reached 12,000 QPS.")), Map.of());
        // 条件、出处、论断都可以不填
        ProjectCard bare = card(List.of(new Section("results", "结果", "提速 {{fact:speedup}}")), List.of(),
                Map.of("speedup", new CardFact("3.9", "×", null, null)));

        TestStorage storage = TestStorage.create(root, false);
        ProjectCardFile cards = new ProjectCardFile(storage.layout, storage.clock);
        cards.save(direct, Set.of());
        assertThat(cards.find("demo").orElseThrow()).isEqualTo(direct);
        cards.save(bare, Set.of());
        assertThat(cards.find("demo").orElseThrow()).isEqualTo(bare);
    }

    @Test
    void referencesToMissingNumbersOrClaimsAreRejected() {
        assertThatThrownBy(() -> card(List.of(new Section("results", "结果", "提速 {{fact:speedup}}")), List.of(),
                Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("{{fact:speedup}}");

        ProjectCard card = ProjectCardFile.parse(CARD, "card.md");
        TestStorage storage = TestStorage.create(root, false);
        assertThatThrownBy(() -> new ProjectCardFile(storage.layout, storage.clock).save(card, Set.of("c-0012")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("c-0015");
    }

    @Test
    void userConfirmedPersonalContributionOnTeamProjectsCanBeSaved() {
        ProjectCard team = new ProjectCard("demo", null, new Text("团队项目", "Team project"), "work", null, 5, null,
                null, null, null, null, Set.of(Language.ZH, Language.EN),
                List.of(new Section("role", "我的职责", "主导调度模块设计，独立完成任务队列。")),
                List.of(new Section("role", "My role", "Led the scheduler design and solely built the job queue.")),
                null);

        TestStorage storage = TestStorage.create(root, false);
        ProjectCardFile cards = new ProjectCardFile(storage.layout, storage.clock);
        cards.save(team, Set.of());
        assertThat(cards.find("demo").orElseThrow().en().getFirst().text()).contains("solely built");
    }

    @Test
    void bodyOutsideTheTwoLanguagesAndSlugMismatchAreFormatErrors() {
        assertThatThrownBy(() -> ProjectCardFile.parse(CARD + "\n## notes\n随手记\n", "card.md"))
                .isInstanceOf(StorageFormatException.class).hasMessageContaining("只能有");
        assertThatThrownBy(() -> ProjectCardFile.parse(CARD.replace("### 一句话 {#summary}", "### 一句话"), "card.md"))
                .isInstanceOf(StorageFormatException.class).hasMessageContaining("{#id}");

        TestStorage storage = TestStorage.create(root, false);
        storage.write(storage.layout.baseProjectCard("other"), CARD);
        assertThatThrownBy(() -> new ProjectCardFile(storage.layout, storage.clock).find("other"))
                .isInstanceOf(StorageFormatException.class).hasMessageContaining("slug");
    }

    private static ProjectCard card(List<Section> zh, List<Section> en, Map<String, CardFact> facts) {
        return new ProjectCard("demo", null, new Text("示例项目", "Demo project"), "personal", null, null, null, null,
                null, facts, null, null, zh, en, null);
    }
}
