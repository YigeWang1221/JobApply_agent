package jobapply.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelCatalogClientTest {

    @Test
    void listsModelsInBothFormats() {
        try (FakeModelServer server = FakeModelServer.start().models(List.of("fake-b", "fake-a", "fake-a"))) {
            ModelCatalogClient client = new ModelCatalogClient();

            assertThat(client.list("openai", server.openAiBase() + "/", FakeModelServer.OPENAI_KEY))
                    .isEqualTo(new ModelCatalogClient.Models(List.of("fake-a", "fake-b")));
            assertThat(server.last().path()).isEqualTo("/v1/models");
            assertThat(server.last().authorization()).isEqualTo("Bearer " + FakeModelServer.OPENAI_KEY);
            assertThat(server.last().apiKey()).isNull();

            assertThat(client.list("anthropic", server.anthropicBase(), FakeModelServer.ANTHROPIC_KEY))
                    .isEqualTo(new ModelCatalogClient.Models(List.of("fake-a", "fake-b")));
            assertThat(server.last().path()).isEqualTo("/v1/models");
            assertThat(server.last().apiKey()).isEqualTo(FakeModelServer.ANTHROPIC_KEY);
            assertThat(server.last().anthropicVersion()).isEqualTo("2023-06-01");
            assertThat(server.last().authorization()).isNull();

            client.list("openai", server.openAiBase(), null);
            assertThat(server.last().authorization()).isNull();
        }
    }

    @Test
    void unauthorizedReturnsTheReasonWithoutTheKey() {
        try (FakeModelServer server = FakeModelServer.start().fail(401,
                "{\"error\":{\"message\":\"Incorrect API key provided: " + FakeModelServer.OPENAI_KEY + "\"}}")) {
            ModelCatalogClient.Result result = new ModelCatalogClient().list("openai", server.openAiBase(),
                    FakeModelServer.OPENAI_KEY);

            assertThat(result).isInstanceOf(ModelCatalogClient.Failed.class);
            String reason = ((ModelCatalogClient.Failed) result).reason();
            assertThat(reason).startsWith("HTTP 401").contains("Incorrect API key").contains("****")
                    .doesNotContain(FakeModelServer.OPENAI_KEY);
        }
    }

    @Test
    void gatewaysWithoutTheEndpointReturn404Reason() {
        try (FakeModelServer server = FakeModelServer.start()) {
            ModelCatalogClient.Result result = new ModelCatalogClient().list("openai",
                    server.anthropicBase() + "/no-such-gateway", FakeModelServer.OPENAI_KEY);

            assertThat(((ModelCatalogClient.Failed) result).reason()).startsWith("HTTP 404");
        }
    }

    @Test
    void timeoutsAndRefusedConnectionsAreReasons() {
        try (FakeModelServer server = FakeModelServer.start().delay(3_000)) {
            ModelCatalogClient.Result result = new ModelCatalogClient(Duration.ofMillis(300)).list("openai",
                    server.openAiBase(), FakeModelServer.OPENAI_KEY);

            assertThat(((ModelCatalogClient.Failed) result).reason()).contains("超时")
                    .doesNotContain(FakeModelServer.OPENAI_KEY);
        }
        ModelCatalogClient.Result refused = new ModelCatalogClient().list("openai", "http://127.0.0.1:9/v1", null);
        assertThat(((ModelCatalogClient.Failed) refused).reason()).contains("连不上");
        assertThat(new ModelCatalogClient().list("openai", "not a url", null))
                .isEqualTo(new ModelCatalogClient.Failed("Base URL 不合法"));
    }
}
