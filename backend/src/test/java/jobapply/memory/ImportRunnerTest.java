package jobapply.memory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import jobapply.domain.Claim;
import jobapply.domain.ImportCandidate;
import jobapply.domain.ImportRun;
import jobapply.domain.Source;
import jobapply.persistence.EvidenceCardFile;
import jobapply.tools.FakeGitHubServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 整理的后台流程：开始 → 进度 → 候选；JSON 解析失败；中断和恢复；秘密文件不进模型请求。模型是本机假服务端。
 */
class ImportRunnerTest {

    @TempDir
    Path dir;

    @Test
    @SuppressWarnings("unchecked")
    void startRunsInTheBackgroundAndEndsWithCandidates() {
        try (ImportFixture fixture = ImportFixture.create(dir, true)) {
            ImportRun estimated = fixture.estimate();

            ImportRun started = fixture.runner.start(estimated.id());
            assertThat(started.status()).isIn(ImportRun.Status.RUNNING, ImportRun.Status.REVIEWING);
            ImportRun run = fixture.settle(fixture.runner, estimated.id());

            assertThat(run.status()).isEqualTo(ImportRun.Status.REVIEWING);
            assertThat((Map<String, Object>) run.extra().get("progress")).containsEntry("sources_done", 1)
                    .containsEntry("model_calls", 2);
            // 清单卡、抽取各一次
            assertThat(fixture.server.requests()).hasSize(2);
            String extractRequest = fixture.server.requests().get(1).body();
            assertThat(extractRequest).contains("可以提出估算、量化口径、技能定位和措辞的候选写法", "由用户确认采用",
                    "缺少原文数字、出处或测试条件不阻止候选起草", "新增细节作为待确认候选")
                    .doesNotContain("材料里没有的数字不要编", "不要编造经历、数字和技能");
            List<ImportCandidate> candidates = ImportRunner.candidates(run);
            assertThat(candidates).extracting(ImportCandidate::key).containsExactly("contact.name", "contact.email",
                    "skills.backend", "metrics.p99-latency", "projects.demo-recsys");
            assertThat(candidates).allMatch(c -> c.comparison().equals(ImportCandidate.NEW) && c.decision() == null);
            assertThat((List<?>) run.extra().get("warnings")).anyMatch(w -> w.toString().contains("C 级"));

            // 经历、技能、成果、指标和项目在台账里建论断（待确认）；基础信息不建
            assertThat(fixture.ledger.list()).extracting(Claim::category).containsExactly("技能", "指标", "项目");
            assertThat(fixture.ledger.list()).allMatch(c -> c.status() == Claim.Status.NEEDS_VERIFICATION);
            assertThat(candidates.get(0).claim()).isNull();
            assertThat(candidates.get(3).claim()).isEqualTo("c-0002");

            // 清单卡写进 base/evidence，不放原文
            String key = run.sources().getFirst();
            EvidenceCardFile.EvidenceCard card = new EvidenceCardFile(fixture.storage.layout, fixture.storage.clock)
                    .find(key).orElseThrow();
            assertThat(card.body()).contains("示例推荐服务").contains(".env：秘密文件")
                    .contains("出处「not-read.md」不是读过的文件");
            assertThat(card.body()).doesNotContain("单机，1k QPS");
        }
    }

    @Test
    void secretFilesNeverReachTheModel() {
        try (ImportFixture fixture = ImportFixture.create(dir, false)) {
            fixture.reviewing();
            assertThat(fixture.server.requests()).isNotEmpty()
                    .allSatisfy(request -> assertThat(request.body()).doesNotContain(ImportFixture.SECRET));
            assertThat(fixture.server.requests().getFirst().body()).contains("P99").contains("src/Main.java");
            assertThat(fixture.storage.snapshot().values()).noneMatch(text -> text.contains(ImportFixture.SECRET));
        }
    }

    @Test
    void invalidJsonIsRetriedOnceThenTheRunFails() {
        try (ImportFixture fixture = ImportFixture.create(dir, false)) {
            fixture.server.respond(body -> "不是 JSON");
            ImportRun run = fixture.estimate();
            fixture.runner.start(run.id());

            ImportRun failed = fixture.settle(fixture.runner, run.id());

            assertThat(failed.status()).isEqualTo(ImportRun.Status.FAILED);
            assertThat((String) failed.extra().get("error")).contains("JSON 解析失败").contains("重试一次");
            assertThat(fixture.server.requests()).hasSize(2);

            // 失败以后可以重新开始
            fixture.server.respond(ImportFixture::answer);
            fixture.runner.start(run.id());
            assertThat(fixture.settle(fixture.runner, run.id()).status()).isEqualTo(ImportRun.Status.REVIEWING);
        }
    }

    @Test
    void aSecondTryAfterOneBadAnswerSucceeds() {
        try (ImportFixture fixture = ImportFixture.create(dir, false)) {
            int[] count = {0};
            fixture.server.respond(body -> count[0]++ == 0 ? "```json\n{ 半截" : ImportFixture.answer(body));
            ImportRun run = fixture.estimate();
            fixture.runner.start(run.id());

            assertThat(fixture.settle(fixture.runner, run.id()).status()).isEqualTo(ImportRun.Status.REVIEWING);
            assertThat(fixture.server.requests()).hasSize(3);
        }
    }

    @Test
    void modelErrorsFailTheRunWithAReason() {
        try (ImportFixture fixture = ImportFixture.create(dir, false)) {
            fixture.server.fail(500, "{\"error\":{\"message\":\"boom\"}}");
            ImportRun run = fixture.estimate();
            fixture.runner.start(run.id());

            ImportRun failed = fixture.settle(fixture.runner, run.id());
            assertThat(failed.status()).isEqualTo(ImportRun.Status.FAILED);
            assertThat((String) failed.extra().get("error")).contains("清单卡").contains("模型调用失败");
        }
    }

    @Test
    void reviewContinuesAfterRestartAndAnInterruptedRunIsMarkedFailed() {
        try (ImportFixture fixture = ImportFixture.create(dir, false)) {
            ImportRun run = fixture.reviewing();
            fixture.runner.close();

            // 重启以后：候选和决定都在导入记录里，可以接着确认
            ImportRunner restarted = fixture.newRunner();
            assertThat(ImportRunner.candidates(restarted.view(run.id()))).hasSize(5);
            ImportRun decided = restarted.decide(run.id(), "f-001", FactWriter.ACCEPT, null, null);
            assertThat(ImportRunner.candidates(decided).getFirst().decision().decision()).isEqualTo("accept");
            restarted.close();

            // 记录停在 running、但没有进程在跑：读的时候记为中断
            ImportRun estimated = fixture.estimate();
            fixture.runs.save(new ImportRun(estimated.id(), ImportRun.Status.RUNNING, estimated.createdAt(),
                    estimated.updatedAt(), estimated.sources(), estimated.estimate(), Map.of()));
            ImportRun interrupted = fixture.newRunner().view(estimated.id());
            assertThat(interrupted.status()).isEqualTo(ImportRun.Status.FAILED);
            assertThat(interrupted.extra().get("error")).isEqualTo(ImportRunner.INTERRUPTED);
        }
    }

    @Test
    void allDecisionsMakeTheRunDone() {
        try (ImportFixture fixture = ImportFixture.create(dir, false)) {
            ImportRun run = fixture.reviewing();
            for (ImportCandidate candidate : ImportRunner.candidates(run)) {
                fixture.runner.decide(run.id(), candidate.id(), FactWriter.REJECT, null, null);
            }
            ImportRun done = fixture.runner.view(run.id());
            assertThat(done.status()).isEqualTo(ImportRun.Status.DONE);
            assertThat(done.extra()).containsKey("finished_at");
            assertThat(fixture.ledger.list()).isEmpty();
            assertThatThrownBy(() -> fixture.runner.decide(run.id(), "f-001", FactWriter.ACCEPT, null, null))
                    .isInstanceOf(ImportRunner.StateException.class);
        }
    }

    @Test
    void startIsRefusedInTheWrongState() {
        try (ImportFixture fixture = ImportFixture.create(dir, false)) {
            ImportRun run = fixture.reviewing();
            assertThatThrownBy(() -> fixture.runner.start(run.id())).isInstanceOf(ImportRunner.StateException.class);
            assertThatThrownBy(() -> fixture.runner.decide(run.id(), "f-999", FactWriter.ACCEPT, null, null))
                    .isInstanceOf(NoSuchElementException.class);
            fixture.runner.decide(run.id(), "f-001", FactWriter.ACCEPT, null, null);
            assertThatThrownBy(() -> fixture.runner.decide(run.id(), "f-001", FactWriter.REJECT, null, null))
                    .isInstanceOf(ImportRunner.StateException.class).hasMessageContaining("已经处理过");
            assertThat(Files.exists(fixture.storage.layout.importRun(run.id()))).isTrue();
        }
    }

    @Test
    void secondImportComparesWithTheFactLayer() {
        try (ImportFixture fixture = ImportFixture.create(dir, false)) {
            ImportRun first = fixture.reviewing();
            for (ImportCandidate candidate : ImportRunner.candidates(first)) {
                fixture.runner.decide(first.id(), candidate.id(), FactWriter.ACCEPT, null, null);
            }
            ImportFixture.write(fixture.project.resolve("NOTES.md"), "新加的笔记");

            ImportRun second = fixture.estimate();
            fixture.runner.start(second.id());
            List<ImportCandidate> candidates = ImportRunner.candidates(fixture.settle(fixture.runner, second.id()));

            assertThat(candidates).filteredOn(c -> c.key().equals("contact.email")).singleElement()
                    .satisfies(c -> {
                        assertThat(c.comparison()).isEqualTo(ImportCandidate.SAME);
                        assertThat(c.decision().decision()).isEqualTo("same");
                        assertThat(c.claim()).isNull();
                    });
            // 第一次确认时译文由模型起草（项目卡多了英文），这次材料只有中文：中文相同，算相同
            assertThat(candidates).filteredOn(c -> c.key().equals("projects.demo-recsys")).singleElement()
                    .extracting(ImportCandidate::comparison).isEqualTo(ImportCandidate.SAME);
        }
    }

    @Test
    void aGitHubSourceIsReadAtTheEstimatedShaWithThePlannedNumberOfCalls() {
        try (FakeGitHubServer github = new FakeGitHubServer();
             ImportFixture fixture = ImportFixture.create(dir, github.uri())) {
            for (String path : List.of("package.json", "service/pom.xml")) {
                github.reply("/repos/example-owner/demo/contents/" + path + "?ref=" + FakeGitHubServer.SHA,
                        FakeGitHubServer.Reply.json("{\"encoding\": \"base64\", \"content\": \""
                                + Base64.getEncoder().encodeToString(("虚构的 " + path).getBytes(UTF_8)) + "\"}"));
            }
            String key = fixture.sources.register(Source.Type.GITHUB, "example-owner/demo", FakeGitHubServer.SHA)
                    .source().key();
            ImportRun estimated = fixture.estimator.estimate(List.of(key));
            int planned = estimated.estimate().githubCallsPlanned();
            int before = github.received().size();

            fixture.runner.start(estimated.id());
            ImportRun run = fixture.settle(fixture.runner, estimated.id());

            assertThat(run.status()).isEqualTo(ImportRun.Status.REVIEWING);
            assertThat(github.received().size() - before).isEqualTo(planned);
            assertThat(((Map<?, ?>) run.extra().get("progress")).get("github_calls")).isEqualTo(planned);
            assertThat(github.received()).allMatch(received -> received.authorization() == null);
            String inventoryRequest = fixture.server.requests().getFirst().body();
            assertThat(inventoryRequest).contains("虚构的介绍").contains("虚构的 service/pom.xml")
                    .contains("TypeScript").contains("第二次提交");
        }
    }
}
