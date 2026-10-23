package jobapply.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import jobapply.config.FakeModelServer;
import jobapply.config.ModelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 测试资源（{@code src/test/resources/assets/}）是仓库里虚构的小文件。模型是本地的假服务端。
 */
class AssetAnalyzerTest {

    @TempDir
    Path dir;

    private static Path resource(String name) {
        try {
            return Path.of(AssetAnalyzerTest.class.getResource("/assets/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ChatModel model(FakeModelServer server) {
        return ModelFactory.create(new ModelFactory.Endpoint("openai", server.openAiBase(), FakeModelServer.OPENAI_KEY,
                "fake-small"), Duration.ofSeconds(5));
    }

    @Test
    void readsPdfDocxAndTextAndAsksTheModelForOneLine() throws Exception {
        try (FakeModelServer server = FakeModelServer.start().toolCalls(false).reply("「英文简历，2025 版，2 页」\n多余的第二行")) {
            AssetAnalyzer analyzer = new AssetAnalyzer(() -> Optional.of(model(server)));

            AssetAnalyzer.Analysis pdf = analyzer.analyze(resource("resume-demo.pdf"), "application/pdf");
            assertThat(pdf).isEqualTo(new AssetAnalyzer.Analysis("英文简历，2025 版，2 页", null));
            assertThat(server.last().body()).contains("Demo Resume 2025").contains("页数：2");

            analyzer.analyze(resource("resume-demo.docx"),
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            assertThat(server.last().body()).contains("虚构简历 2025 版");

            analyzer.analyze(resource("notes-demo.txt"), "text/plain");
            assertThat(server.last().body()).contains("作品集链接");
        }
    }

    @Test
    void thePromptDoesNotInviteAGuessedYear() {
        // 示例里有具体年份时，模型会照着补一个年份（C004-A 手动检查发现）
        assertThat(AssetAnalyzer.SYSTEM_PROMPT).doesNotContainPattern("(19|20)\\d{2}");
        assertThat(AssetAnalyzer.SYSTEM_PROMPT).contains("不要从毕业时间、经历时间、项目时间等日期去推断版本或年份");
    }

    @Test
    void theSystemPromptIsWhatIsSent() throws Exception {
        try (FakeModelServer server = FakeModelServer.start().toolCalls(false).reply("中文简历，1 页")) {
            new AssetAnalyzer(() -> Optional.of(model(server))).analyze(resource("notes-demo.txt"), "text/plain");

            assertThat(server.last().body()).contains("拿不准就不写").doesNotContain("2025 版");
        }
    }

    @Test
    void onlyTheFirst4000CharactersAreSent() throws Exception {
        Path big = dir.resolve("long.txt");
        Files.writeString(big, "甲".repeat(4_000) + "乙".repeat(2_000));
        try (FakeModelServer server = FakeModelServer.start().toolCalls(false).reply("长文本")) {
            new AssetAnalyzer(() -> Optional.of(model(server))).analyze(big, "text/plain");

            assertThat(server.last().body()).contains("甲".repeat(100)).doesNotContain("乙");
        }
    }

    @Test
    void skipsWithoutAModel() {
        AssetAnalyzer analyzer = new AssetAnalyzer(Optional::empty);

        AssetAnalyzer.Analysis analysis = analyzer.analyze(resource("resume-demo.pdf"), "application/pdf");

        assertThat(analysis.description()).isEmpty();
        assertThat(analysis.note()).contains("还没有配置模型");
    }

    @Test
    void scannedPdfsAreLabelledWithoutCallingTheModel() {
        try (FakeModelServer server = FakeModelServer.start()) {
            AssetAnalyzer analyzer = new AssetAnalyzer(() -> Optional.of(model(server)));

            assertThat(analyzer.analyze(resource("scanned-demo.pdf"), "application/pdf").description())
                    .isEqualTo("没有文字层，可能是扫描件");
            assertThat(server.requests()).isEmpty();
        }
    }

    @Test
    void modelFailuresAndUnreadableFilesAreNotesNotErrors() throws Exception {
        Path broken = dir.resolve("broken.pdf");
        Files.write(broken, new byte[] {'%', 'P', 'D', 'F', '-', '1', '.', '7', 0, 1, 2});
        try (FakeModelServer server = FakeModelServer.start().fail(500, "{\"error\":{\"message\":\"boom\"}}")) {
            AssetAnalyzer analyzer = new AssetAnalyzer(() -> Optional.of(model(server)));

            assertThat(analyzer.analyze(resource("notes-demo.txt"), "text/plain").note()).contains("模型调用失败");
            assertThat(analyzer.analyze(broken, "application/pdf").note()).isNotBlank();
        }
        AssetAnalyzer throwing = new AssetAnalyzer(() -> {
            throw new IllegalStateException("变量 X_API_KEY 没有设置");
        });
        assertThat(throwing.analyze(resource("notes-demo.txt"), "text/plain").note()).contains("X_API_KEY");
    }

    @Test
    void detectsTypesAndCleansAnswers() throws Exception {
        AssetAnalyzer analyzer = new AssetAnalyzer(Optional::empty);

        assertThat(analyzer.detectType(Files.readAllBytes(resource("resume-demo.pdf")), "resume-demo.pdf"))
                .isEqualTo("application/pdf");
        assertThat(analyzer.detectType(Files.readAllBytes(resource("resume-demo.docx")), "resume-demo.docx"))
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(AssetAnalyzer.clean("\"a|b\"")).isEqualTo("a b");
        assertThat(AssetAnalyzer.clean("字".repeat(80))).hasSize(60);
        assertThat(AssetAnalyzer.clean(null)).isEmpty();
    }
}
