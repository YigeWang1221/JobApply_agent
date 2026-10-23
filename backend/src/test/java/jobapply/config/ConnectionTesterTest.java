package jobapply.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ConnectionTesterTest {

    @Test
    void modelsThatCallTheProbeSupportTools() {
        try (FakeModelServer server = FakeModelServer.start().toolCalls(true)) {
            ConnectionTester.Result openai = new ConnectionTester().test(new ModelFactory.Endpoint("openai",
                    server.openAiBase(), FakeModelServer.OPENAI_KEY, "fake-large"));
            ConnectionTester.Result anthropic = new ConnectionTester().test(new ModelFactory.Endpoint("anthropic",
                    server.anthropicBase(), FakeModelServer.ANTHROPIC_KEY, "fake-claude"));

            assertThat(openai).extracting(ConnectionTester.Result::ok, ConnectionTester.Result::supportsTools,
                    ConnectionTester.Result::model, ConnectionTester.Result::promptTokens,
                    ConnectionTester.Result::completionTokens).containsExactly(true, true, "fake-large-2026", 42, 7);
            assertThat(openai.latencyMs()).isGreaterThanOrEqualTo(0);
            assertThat(anthropic).extracting(ConnectionTester.Result::ok, ConnectionTester.Result::supportsTools,
                    ConnectionTester.Result::model, ConnectionTester.Result::promptTokens)
                    .containsExactly(true, true, "fake-claude-2026", 50);
            // 测的是选中的模型，不是 SDK 的默认模型（C004-A 手动检查发现的缺陷）
            assertThat(server.requests()).extracting(FakeModelServer.Request::body).satisfiesExactly(
                    body -> assertThat(body).contains("\"model\":\"fake-large\""),
                    body -> assertThat(body).contains("\"model\":\"fake-claude\""));
        }
    }

    @Test
    void modelsThatAnswerWithTextDoNotSupportTools() {
        try (FakeModelServer server = FakeModelServer.start().toolCalls(false)) {
            ConnectionTester.Result result = new ConnectionTester().test(new ModelFactory.Endpoint("openai",
                    server.openAiBase(), FakeModelServer.OPENAI_KEY, "fake-small"));

            assertThat(result.ok()).isTrue();
            assertThat(result.supportsTools()).isFalse();
            assertThat(result.error()).isNull();
        }
    }

    @Test
    void latencyIsMeasured() {
        try (FakeModelServer server = FakeModelServer.start().delay(200)) {
            ConnectionTester.Result result = new ConnectionTester().test(new ModelFactory.Endpoint("openai",
                    server.openAiBase(), FakeModelServer.OPENAI_KEY, "fake-large"));

            assertThat(result.latencyMs()).isGreaterThanOrEqualTo(200);
        }
    }

    @Test
    void failuresAreResultsWithoutTheKey() {
        try (FakeModelServer server = FakeModelServer.start().fail(401,
                "{\"error\":{\"message\":\"bad key " + FakeModelServer.OPENAI_KEY + "\",\"type\":\"invalid_request_error\"}}")) {
            ConnectionTester.Result result = new ConnectionTester(Duration.ofSeconds(5)).test(new ModelFactory.Endpoint(
                    "openai", server.openAiBase(), FakeModelServer.OPENAI_KEY, "fake-large"));

            assertThat(result.ok()).isFalse();
            assertThat(result.supportsTools()).isFalse();
            assertThat(result.error()).isNotBlank().doesNotContain(FakeModelServer.OPENAI_KEY);
            assertThat(server.requests()).hasSize(1);
        }
    }
}
