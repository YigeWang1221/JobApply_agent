package jobapply.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 两种格式都对本地的假服务端完成一次对话。不访问外部网络，不调用真实模型。
 */
class ModelFactoryTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void openAiFormatSendsBearerKeyToBaseUrlPlusChatCompletions() {
        try (FakeModelServer server = FakeModelServer.start().toolCalls(false)) {
            ChatModel model = ModelFactory.create(new ModelFactory.Endpoint("openai", server.openAiBase(),
                    FakeModelServer.OPENAI_KEY, "fake-small"), TIMEOUT);

            ChatResponse response = model.call(new Prompt("hi"));

            assertThat(response.getResult().getOutput().getText()).isEqualTo("OK");
            assertThat(server.last().path()).isEqualTo("/v1/chat/completions");
            assertThat(server.last().authorization()).isEqualTo("Bearer " + FakeModelServer.OPENAI_KEY);
            assertThat(server.last().body()).contains("\"model\":\"fake-small\"");
        }
    }

    @Test
    void openAiFormatWithoutKeySendsNoAuthorization() {
        try (FakeModelServer server = FakeModelServer.start().toolCalls(false)) {
            ModelFactory.create(new ModelFactory.Endpoint("openai", server.openAiBase(), null, "fake-local"), TIMEOUT)
                    .call(new Prompt("hi"));

            assertThat(server.last().authorization()).isNull();
        }
    }

    @Test
    void anthropicFormatSendsApiKeyToBaseUrlPlusV1Messages() {
        try (FakeModelServer server = FakeModelServer.start().toolCalls(false)) {
            ChatModel model = ModelFactory.create(new ModelFactory.Endpoint("anthropic", server.anthropicBase(),
                    FakeModelServer.ANTHROPIC_KEY, "fake-claude"), TIMEOUT);

            ChatResponse response = model.call(new Prompt("hi"));

            assertThat(response.getResult().getOutput().getText()).isEqualTo("OK");
            assertThat(server.last().path()).isEqualTo("/v1/messages");
            assertThat(server.last().apiKey()).isEqualTo(FakeModelServer.ANTHROPIC_KEY);
            assertThat(server.last().body()).contains("\"model\":\"fake-claude\"");
        }
    }

    @Test
    void toolCallsComeBackUnexecutedInBothFormats() {
        try (FakeModelServer server = FakeModelServer.start().toolCalls(true)) {
            for (ModelFactory.Endpoint endpoint : List.of(
                    new ModelFactory.Endpoint("openai", server.openAiBase(), FakeModelServer.OPENAI_KEY, "fake-large"),
                    new ModelFactory.Endpoint("anthropic", server.anthropicBase(), FakeModelServer.ANTHROPIC_KEY,
                            "fake-claude"))) {
                ChatModel model = ModelFactory.create(endpoint, TIMEOUT);
                Prompt prompt = new Prompt("call it",
                        ModelFactory.withTools(endpoint, List.of(new ConnectionTester.ProbeTool())));

                // ProbeTool.call() 会抛异常；这里没有抛，说明工具没有被执行
                ChatResponse response = model.call(prompt);

                assertThat(response.getResult().getOutput().getToolCalls())
                        .as(endpoint.format())
                        .singleElement()
                        .satisfies(call -> assertThat(call.name()).isEqualTo(ConnectionTester.PROBE_TOOL));
                assertThat(server.last().body()).contains(ConnectionTester.PROBE_TOOL);
                // 带工具的单次选项不能把模型名换成 SDK 的默认值（gpt-5-mini / claude-haiku-4-5）
                assertThat(server.last().body()).as(endpoint.format())
                        .contains("\"model\":\"" + endpoint.model() + "\"")
                        .doesNotContain("gpt-5-mini").doesNotContain("claude-haiku");
            }
            assertThat(server.requests()).hasSize(2);
        }
    }

    @Test
    void endpointsAreValidatedAndNeverPrintTheKey() {
        ModelFactory.Endpoint endpoint = new ModelFactory.Endpoint("openai", "https://api.example.com/v1",
                FakeModelServer.OPENAI_KEY, "fake-small");

        assertThat(endpoint.toString()).doesNotContain(FakeModelServer.OPENAI_KEY).contains("已设置");
        assertThatThrownBy(() -> new ModelFactory.Endpoint("gemini", "https://x", "k", "m"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelFactory.Endpoint("openai", " ", "k", "m"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelFactory.Endpoint("openai", "https://x", "k", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelFactory.Endpoint("anthropic", "https://x", null, "m"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new ModelFactory.Endpoint("openai", "http://localhost:11434/v1", null, "m"))
                .doesNotThrowAnyException();
    }

    @Test
    void withSchemaKeepsTheModelNameAndAsksForJsonInBothFormats() {
        String schema = "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\"}},"
                + "\"required\":[\"a\"],\"additionalProperties\":false}";
        try (FakeModelServer server = FakeModelServer.start().toolCalls(false).respond(body -> "{\"a\":1}")) {
            ChatModel openAi = ModelFactory.create(new ModelFactory.Endpoint("openai", server.openAiBase(),
                    FakeModelServer.OPENAI_KEY, "fake-small"), TIMEOUT);
            String text = openAi.call(new Prompt("hi", ModelFactory.withSchema(openAi, schema, 16_000))).getResult()
                    .getOutput().getText();
            assertThat(text).isEqualTo("{\"a\":1}");
            assertThat(server.last().body()).contains("\"model\":\"fake-small\"")
                    .contains("\"response_format\"").contains("\"json_schema\"").contains("\"strict\":true")
                    .doesNotContain("max_tokens");

            ChatModel anthropic = ModelFactory.create(new ModelFactory.Endpoint("anthropic", server.anthropicBase(),
                    FakeModelServer.ANTHROPIC_KEY, "fake-claude"), TIMEOUT);
            anthropic.call(new Prompt("hi", ModelFactory.withSchema(anthropic, schema, 16_000)));
            assertThat(server.last().body()).contains("\"model\":\"fake-claude\"")
                    .contains("\"output_config\"").contains("\"json_schema\"").contains("\"max_tokens\":16000");
        }
    }
}
