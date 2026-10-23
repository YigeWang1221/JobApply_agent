package jobapply.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import jobapply.domain.Claim;
import jobapply.domain.Fact;
import jobapply.domain.FactClass;
import jobapply.domain.ImportCandidate;
import jobapply.domain.ImportRun;
import jobapply.domain.Language;
import jobapply.domain.ProjectCard;
import jobapply.persistence.FactsFile;
import jobapply.persistence.GlossaryFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 确认写入：各文件、冲突、译文未审阅、没有论断也能保存、指标可用于简历和填表、每个决定提交一次。
 * 候选来自 {@link ImportFixture} 的整理（假模型），数据全部虚构。
 */
class FactWriterTest {

    @TempDir
    Path dir;

    private static ImportCandidate candidate(ImportRun run, String key) {
        return ImportRunner.candidates(run).stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
    }

    private static Fact fact(ImportFixture fixture, String key) {
        return FactsFile.read(fixture.storage.layout.baseFacts()).stream().filter(f -> f.key().equals(key))
                .findFirst().orElseThrow();
    }

    @Test
    void acceptWritesTheFactAndDraftsTheMissingLanguageAsUnreviewed() {
        try (ImportFixture fixture = ImportFixture.create(dir, true)) {
            ImportRun run = fixture.reviewing();

            ImportRun after = fixture.runner.decide(run.id(), candidate(run, "contact.name").id(), FactWriter.ACCEPT,
                    null, null);

            Fact name = fact(fixture, "contact.name");
            assertThat(name.zh()).isEqualTo("林虚构");
            assertThat(name.en()).isEqualTo("EN-DRAFT");
            assertThat(name.reviewed()).containsExactly(Language.ZH);
            assertThat(name.claim()).isNull();
            assertThat(name.source()).isEqualTo("source:" + run.sources().getFirst());
            assertThat(name.confirmedAt()).isEqualTo("2026-10-22");
            assertThat(fixture.storage.read(fixture.storage.layout.baseFacts())).contains("# 个人基础事实");
            assertThat(candidate(after, "contact.name").decision().decision()).isEqualTo("accept");

            fixture.storage.project("demo", "live");
            var resolved = new ProfileResolver(fixture.storage.layout, fixture.storage.clock)
                    .resolve("demo", List.of(), Language.EN).get("contact.name");
            assertThat(resolved.value()).isEqualTo("EN-DRAFT");
            assertThat(resolved.unreviewed()).isTrue();
        }
    }

    @Test
    void metricsConfirmedByTheUserAreUsableForResumeAndForms() {
        try (ImportFixture fixture = ImportFixture.create(dir, false)) {
            ImportRun run = fixture.reviewing();
            ImportCandidate metric = candidate(run, "metrics.p99-latency");

            fixture.runner.decide(run.id(), metric.id(), FactWriter.ACCEPT, null, null);

            Claim claim = fixture.ledger.find(metric.claim()).orElseThrow();
            assertThat(claim.status()).isEqualTo(Claim.Status.VERIFIED);
            assertThat(claim.use()).isEqualTo(Claim.Use.FORMS_AND_RESUME);
            assertThat(claim.confirmedAt()).isEqualTo("2026-10-22");
            assertThat(claim.locator()).isEqualTo("README.md#L5");
            assertThat(fact(fixture, "metrics.p99-latency").claim()).isEqualTo(metric.claim());

            fixture.storage.project("demo", "live");
            assertThat(new ProfileResolver(fixture.storage.layout, fixture.storage.clock)
                    .resolve("demo", List.of(), Language.ZH).get("metrics.p99-latency").value())
                    .isEqualTo("P99 延迟从 120ms 降到 45ms");
        }
    }

    @Test
    void contentWithoutEvidenceIsSavedAndEditsAreOwnerAttested() {
        try (ImportFixture fixture = ImportFixture.create(dir, false)) {
            ImportRun run = fixture.reviewing();
            ImportCandidate skills = candidate(run, "skills.backend");
            assertThat(skills.origins()).allMatch(origin -> origin.locator() == null);

            fixture.runner.decide(run.id(), skills.id(), FactWriter.EDIT,
                    Map.of("zh", "Java、Spring Boot、Redis", "en", "Java, Spring Boot, Redis"), "interview_only");

            Fact fact = fact(fixture, "skills.backend");
            assertThat(fact.zh()).isEqualTo("Java、Spring Boot、Redis");
            assertThat(fact.en()).isEqualTo("Java, Spring Boot, Redis");
            assertThat(fact.reviewed()).containsExactlyInAnyOrder(Language.ZH, Language.EN);
            Claim claim = fixture.ledger.find(skills.claim()).orElseThrow();
            assertThat(claim.status()).isEqualTo(Claim.Status.OWNER_ATTESTED);
            assertThat(claim.use()).isEqualTo(Claim.Use.INTERVIEW_ONLY);
            // 两种语言都给了：不调用模型起草
            assertThat(fixture.server.requests()).noneMatch(r -> r.body().contains("翻译成另一种语言"));
        }
    }

    @Test
    void userConfirmedContentWithoutAClaimCanBeSaved() {
        try (ImportFixture fixture = ImportFixture.create(dir, false)) {
            ImportRun run = fixture.reviewing();
            ImportCandidate metric = candidate(run, "metrics.p99-latency");
            fixture.ledger.remove(metric.claim());

            fixture.runner.decide(run.id(), metric.id(), FactWriter.ACCEPT, null, null);

            assertThat(fact(fixture, "metrics.p99-latency").claim()).isNull();
        }
    }

    @Test
    void conflictGoesToConflictsMdAndLeavesTheFactLayerAlone() {
        try (ImportFixture fixture = ImportFixture.create(dir, true)) {
            ImportRun run = fixture.reviewing();
            ImportCandidate metric = candidate(run, "metrics.p99-latency");
            String facts = fixture.storage.read(fixture.storage.layout.baseFacts());

            fixture.runner.decide(run.id(), metric.id(), FactWriter.CONFLICT, null, null);

            assertThat(fixture.storage.read(fixture.storage.layout.baseFacts())).isEqualTo(facts);
            assertThat(fixture.conflicts.read()).contains("## metrics.p99-latency（2026-10-22）")
                    .contains("- 新材料: zh：P99 延迟从 120ms 降到 45ms").contains(run.id() + "，" + metric.id());
            assertThat(fixture.ledger.find(metric.claim()).orElseThrow().status())
                    .isEqualTo(Claim.Status.DISCREPANCY);
        }
    }

    @Test
    void rejectRemovesTheDraftClaim() {
        try (ImportFixture fixture = ImportFixture.create(dir, false)) {
            ImportRun run = fixture.reviewing();
            ImportCandidate skills = candidate(run, "skills.backend");

            fixture.runner.decide(run.id(), skills.id(), FactWriter.REJECT, null, null);

            assertThat(fixture.ledger.find(skills.claim())).isEmpty();
            assertThat(FactsFile.read(fixture.storage.layout.baseFacts())).isEmpty();
        }
    }

    @Test
    void acceptingAProjectWritesTheCardWithADraftedTranslation() {
        try (ImportFixture fixture = ImportFixture.create(dir, false)) {
            ImportRun run = fixture.reviewing();
            ImportCandidate project = candidate(run, "projects.demo-recsys");

            fixture.runner.decide(run.id(), project.id(), FactWriter.ACCEPT, null, "forms_and_resume");

            ProjectCard card = fixture.cards.find("demo-recsys").orElseThrow();
            assertThat(card.title()).isEqualTo(new ProjectCard.Text("示例推荐服务", "Demo Recommender"));
            assertThat(card.reviewed()).containsExactly(Language.ZH);
            assertThat(card.zh().get(1).text()).isEqualTo("负责召回和压测。");
            assertThat(card.en()).extracting(ProjectCard.Section::heading)
                    .containsExactly("Summary", "My role", "Approach", "Results");
            assertThat(card.en().get(1).text()).isEqualTo("Owned recall and load tests.");
            assertThat(card.roleClaim()).isEqualTo(project.claim());
            assertThat(card.period()).isEqualTo(new ProjectCard.Period("2025-03", "2025-06", "year-month"));
            assertThat(card.teamSize()).isEqualTo(3);
            // 起草的译文不进术语表
            assertThat(fixture.glossary.list()).isEmpty();
        }
    }

    @Test
    void aFailedTranslationStillSavesAndWarns() {
        try (ImportFixture fixture = ImportFixture.create(dir, false)) {
            ImportRun run = fixture.reviewing();
            fixture.server.respond(body -> body.contains("翻译成另一种语言") ? "不是 JSON" : ImportFixture.answer(body));

            ImportRun after = fixture.runner.decide(run.id(), candidate(run, "contact.name").id(), FactWriter.ACCEPT,
                    null, null);

            Fact name = fact(fixture, "contact.name");
            assertThat(name.zh()).isEqualTo("林虚构");
            assertThat(name.en()).isNull();
            assertThat(candidate(after, "contact.name").decision().warnings()).singleElement().asString()
                    .contains("没有起草成功").contains("JSON 解析失败");
        }
    }

    @Test
    void classCValuesAreNotWritten() {
        try (ImportFixture fixture = ImportFixture.create(dir, false)) {
            ImportRun run = fixture.reviewing();
            String id = candidate(run, "skills.backend").id();
            assertThatThrownBy(() -> fixture.runner.decide(run.id(), id, FactWriter.EDIT,
                    Map.of("zh", "签证：H1B"), null)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("C 级");
            assertThatThrownBy(() -> fixture.runner.decide(run.id(), id, "approve", null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(FactsFile.read(fixture.storage.layout.baseFacts())).isEmpty();
        }
    }

    @Test
    void editsFromTheProfilePageMarkReviewedAndFillTheGlossary() {
        try (ImportFixture fixture = ImportFixture.create(dir, true)) {
            fixture.storage.write(fixture.storage.layout.baseFacts(), "---\ntags: [jobapply/facts]\n---\n\n# 个人基础事实\n\n"
                    + FactsFile.render(List.of(Fact.bilingual("education.demo-u.school", "示例大学", "Demo Univ",
                    FactClass.A, "user-confirmed", "2026-10-01", Set.of(Language.ZH)))));

            Fact fact = fixture.writer.editFact("education.demo-u.school", null, null, "Demo University", null);

            assertThat(fact.en()).isEqualTo("Demo University");
            assertThat(fact.reviewed()).containsExactlyInAnyOrder(Language.ZH, Language.EN);
            assertThat(fixture.glossary.list()).containsExactly(new GlossaryFile.Term("示例大学", "Demo University", "学校",
                    "", Map.of()));
            assertThat(fixture.writer.editFact("education.demo-u.school", null, null, null, Set.of()).reviewed())
                    .isEmpty();
            assertThatThrownBy(() -> fixture.writer.editFact("contact.none", null, "x", null, null))
                    .isInstanceOf(NoSuchElementException.class);
        }
    }

    @Test
    void projectEditsFromTheProfilePage() {
        try (ImportFixture fixture = ImportFixture.create(dir, false)) {
            ImportRun run = fixture.reviewing();
            fixture.runner.decide(run.id(), candidate(run, "projects.demo-recsys").id(), FactWriter.ACCEPT, null, null);

            ProjectCard card = fixture.writer.editProject("demo-recsys", new ProjectCard.Text(null, "Demo Rec Service"),
                    Map.of(Language.EN, Map.of("role", "Owned recall, ranking and load tests.")), null);

            assertThat(card.title()).isEqualTo(new ProjectCard.Text("示例推荐服务", "Demo Rec Service"));
            assertThat(card.en().get(1).text()).isEqualTo("Owned recall, ranking and load tests.");
            assertThat(card.reviewed()).containsExactlyInAnyOrder(Language.ZH, Language.EN);
            assertThat(fixture.cards.find("demo-recsys").orElseThrow()).isEqualTo(card);
            assertThat(fixture.glossary.find("示例推荐服务")).isPresent();
        }
    }

    @Test
    void editingAProjectCardDirectlyAlsoRefusesClassC() {
        try (ImportFixture fixture = ImportFixture.create(dir, true)) {
            ImportRun run = fixture.reviewing();
            fixture.runner.decide(run.id(), candidate(run, "projects.demo-recsys").id(), FactWriter.ACCEPT, null, null);
            String before = fixture.storage.read(fixture.storage.layout.baseProjectCard("demo-recsys"));

            assertThatThrownBy(() -> fixture.writer.editProject("demo-recsys", null,
                    Map.of(Language.ZH, Map.of("results", "期望薪资：30k")), null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("C 级");
            assertThatThrownBy(() -> fixture.writer.editProject("demo-recsys",
                    new ProjectCard.Text("护照号 E12345678", null), null, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("C 级");

            assertThat(fixture.storage.read(fixture.storage.layout.baseProjectCard("demo-recsys"))).isEqualTo(before);
        }
    }

    @Test
    void aFailedConfirmationLeavesTheLedgerAndTheFilesUnchanged() {
        try (ImportFixture fixture = ImportFixture.create(dir, true)) {
            ImportRun run = fixture.reviewing();
            ImportCandidate skills = candidate(run, "skills.backend");
            String facts = fixture.storage.read(fixture.storage.layout.baseFacts());
            String ledger = fixture.storage.read(fixture.storage.layout.baseLedger());

            assertThatThrownBy(() -> fixture.runner.decide(run.id(), skills.id(), FactWriter.EDIT,
                    Map.of("zh", "第一行\n第二行", "en", "x"), null)).isInstanceOf(IllegalArgumentException.class);

            assertThat(fixture.ledger.find(skills.claim()).orElseThrow().status())
                    .isEqualTo(Claim.Status.NEEDS_VERIFICATION);
            assertThat(fixture.storage.read(fixture.storage.layout.baseFacts())).isEqualTo(facts);
            assertThat(fixture.storage.read(fixture.storage.layout.baseLedger())).isEqualTo(ledger);
            assertThat(candidate(fixture.runner.view(run.id()), "skills.backend").decision()).isNull();
        }
    }

    @Test
    void aWriteThatFailsHalfwayIsRolledBack() throws Exception {
        try (ImportFixture fixture = ImportFixture.create(dir, true)) {
            ImportRun run = fixture.reviewing();
            ImportCandidate project = candidate(run, "projects.demo-recsys");
            String ledger = fixture.storage.read(fixture.storage.layout.baseLedger());
            Path cardsDir = Files.createDirectories(fixture.storage.layout.baseProjectCardsDir());
            Files.setPosixFilePermissions(cardsDir, PosixFilePermissions.fromString("r-xr-xr-x"));
            try {
                // 台账先改、项目卡写不进去：台账要恢复，工作区不留修改
                assertThatThrownBy(() -> fixture.runner.decide(run.id(), project.id(), FactWriter.ACCEPT, null,
                        null)).isInstanceOf(RuntimeException.class);
            } finally {
                Files.setPosixFilePermissions(cardsDir, PosixFilePermissions.fromString("rwxr-xr-x"));
            }

            assertThat(fixture.storage.read(fixture.storage.layout.baseLedger())).isEqualTo(ledger);
            assertThat(fixture.ledger.find(project.claim()).orElseThrow().status())
                    .isEqualTo(Claim.Status.NEEDS_VERIFICATION);
            // 恢复以后可以再确认
            fixture.runner.decide(run.id(), project.id(), FactWriter.ACCEPT, null, null);
            assertThat(fixture.cards.find("demo-recsys")).isPresent();
        }
    }
}

