package jobapply.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.JsonNode;

/**
 * C028 Responses 模型：请求体映射、SSE 解析和 401 刷新重试。本地 HttpServer，不访问外部网络，令牌是假的。
 */
class ChatGptResponsesChatModelTest {

    private static final String SSE_TOOL_CALL = """
            event: response.output_item.done
            data: {"type":"response.output_item.done","item":{"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"想一想"}],"encrypted_content":"ENC"}}

            event: response.output_item.done
            data: {"type":"response.output_item.done","item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"jobapply_connection_probe","arguments":"{\\"ok\\":true}"}}

            event: response.completed
            data: {"type":"response.completed","response":{"id":"resp_1","model":"gpt-5.1","output":[],"usage":{"input_tokens":12,"output_tokens":5,"total_tokens":17}}}

            """;

    /** 假令牌源：第一次给 old，invalidate 之后给 new。 */
    static final class FakeTokens implements ChatGptAccessTokens {
        final AtomicInteger invalidated = new AtomicInteger();

        @Override
        public String current() {
            return invalidated.get() == 0 ? "tok-old-123456" : "tok-new-123456";
        }

        @Override
        public void invalidate() {
            invalidated.incrementAndGet();
        }
    }

    private static ChatGptResponsesChatModel model(String base, String thinking, FakeTokens tokens) {
        return (ChatGptResponsesChatModel) ModelFactory.create(new ModelFactory.Endpoint("chatgpt", base, null,
                "gpt-5.1", null, thinking).withChatGptTokens(tokens), Duration.ofSeconds(5));
    }

    @Test
    void requestBodyFollowsSiwcRules() {
        ChatGptResponsesChatModel model = model("http://127.0.0.1:1/v1", "high", new FakeTokens());
        Media image = Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(new byte[] {1, 2, 3}).build();
        Prompt prompt = new Prompt(List.of(
                new SystemMessage("sys one"), new SystemMessage("sys two"),
                UserMessage.builder().text("look").media(List.of(image)).build(),
                AssistantMessage.builder().content("calling").toolCalls(List.of(
                        new AssistantMessage.ToolCall("call_9", "function", "probe", "{\"ok\":true}"))).build(),
                ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse("call_9", "probe", "done"))).build()),
                ChatGptResponsesOptions.builder().model("gpt-5.1").temperature(0.2).maxTokens(100)
                        .toolCallbacks(List.of(new ConnectionTester.ProbeTool())).build());

        JsonNode body = model.requestBody(prompt);

        assertThat(body.path("store").asBoolean(true)).isFalse();
        assertThat(body.path("stream").asBoolean(false)).isTrue();
        assertThat(body.path("instructions").asString()).isEqualTo("sys one\n\nsys two");
        assertThat(body.toString()).doesNotContain("\"system\"").doesNotContain("temperature")
                .doesNotContain("max_output_tokens").doesNotContain("previous_response_id");
        JsonNode input = body.path("input");
        assertThat(input.get(0).path("content").get(1).path("image_url").asString())
                .isEqualTo("data:image/png;base64,AQID");
        assertThat(input.get(1).path("role").asString()).isEqualTo("assistant");
        assertThat(input.get(2).path("type").asString()).isEqualTo("function_call");
        assertThat(input.get(2).path("call_id").asString()).isEqualTo("call_9");
        assertThat(input.get(3).path("type").asString()).isEqualTo("function_call_output");
        assertThat(body.path("tools").get(0).path("type").asString()).isEqualTo("function");
        assertThat(body.path("tools").get(0).path("strict").asBoolean(true)).isFalse();
        assertThat(body.path("reasoning").path("effort").asString()).isEqualTo("high");
        assertThat(body.path("include").get(0).asString()).isEqualTo("reasoning.encrypted_content");
    }

    @Test
    void parsesToolCallReasoningAndUsageThenReplaysReasoningByCallId() {
        ChatGptResponsesChatModel model = model("http://127.0.0.1:1/v1", null, new FakeTokens());
        ChatResponse response = model.parse(SSE_TOOL_CALL, "tok", "gpt-5.1");

        AssistantMessage output = response.getResult().getOutput();
        assertThat(output.getToolCalls()).extracting(AssistantMessage.ToolCall::id).containsExactly("call_1");
        assertThat(output.getMetadata().get("reasoningContent")).isEqualTo("想一想");
        assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(12);

        JsonNode body = model.requestBody(new Prompt(List.of(new UserMessage("go"), AssistantMessage.builder()
                .content("").toolCalls(output.getToolCalls()).build())));
        assertThat(body.path("input").get(1).path("type").asString()).isEqualTo("reasoning");
        assertThat(body.path("input").get(1).has("id")).isFalse();
        assertThat(body.path("reasoning").has("effort")).isFalse();
    }

    @Test
    void failedEventWithUsageLimitIsNonTransient() {
        ChatGptResponsesChatModel model = model("http://127.0.0.1:1/v1", null, new FakeTokens());
        String sse = """
                data: {"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded","message":"limit"}}}

                """;
        assertThatThrownBy(() -> model.parse(sse, "tok", "gpt-5.1")).isInstanceOf(NonTransientAiException.class)
                .hasMessageContaining("额度已用完");
    }

    @Test
    void unauthorizedInvalidatesTokenAndRetriesOnce() throws IOException {
        List<String> auth = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            auth.add(authorization);
            byte[] out = (authorization.endsWith("old-123456") ? "{\"detail\":\"expired\"}" : SSE_TOOL_CALL)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(authorization.endsWith("old-123456") ? 401 : 200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            FakeTokens tokens = new FakeTokens();
            ChatModel model = model("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", null, tokens);
            ChatResponse response = model.call(new Prompt("hi"));
            assertThat(response.getResult().getOutput().getToolCalls()).hasSize(1);
            assertThat(tokens.invalidated.get()).isEqualTo(1);
            assertThat(auth).containsExactly("Bearer tok-old-123456", "Bearer tok-new-123456");
        } finally {
            server.stop(0);
        }
    }
}
