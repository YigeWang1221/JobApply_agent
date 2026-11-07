package jobapply.config;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 用 ChatGPT 套餐额度调模型（C028）：OpenAI Responses API（{@code POST <baseUrl>/responses}），
 * {@code Authorization: Bearer} 每次请求时从 {@link ChatGptAccessTokens} 取（access token 一小时过期，令牌源负责刷新）。
 * <p>
 * 按 SIWC 文档（developers.openai.com/siwc/token-sharing-open-source，models-and-inference、preview-limitations、
 * errors-and-recovery，2026-11-07 读）：
 * <ul>
 *   <li>每次请求 {@code store:false}、{@code stream:true}；只有 {@code response.completed} 算成功，
 *       {@code response.failed}、{@code response.incomplete}、{@code error} 事件和没收完的流都算失败。</li>
 *   <li>不发 temperature、max_output_tokens、top_p、metadata、user、previous_response_id 等字段（调用方设了也丢掉）；
 *       system 消息合进 {@code instructions}（{@code role:"system"} 项会被拒绝）；每次发完整历史。</li>
 *   <li>工具只用 function；结构化输出走 {@code text.format} json_schema（和 OpenAI 格式一样 name=json_schema、strict=true）。</li>
 * </ul>
 * 推理模型（gpt-5 / o 系列 / codex）带 {@code reasoning.summary=auto}（思考摘要放进 metadata {@code reasoningContent}，
 * {@code ModelCaller.thinking} 读它），思考强度低 / 中 / 高时加 {@code reasoning.effort}；同时要
 * {@code include:["reasoning.encrypted_content"]}：store:false 时服务端不留推理项，带工具调用的多轮里把上一轮的加密推理项
 * 原样发回，模型才能接着上一步的思路（Codex 也是这么做的）。JobApply 每轮从自己的记录重建对话，推理项会丢，
 * 所以和 {@link GeminiThoughtSignatures} 一样记在有上限的内存表里（工具调用 id → 推理项），重建时按 id 补回；
 * 只补给同一个模型，换了模型或应用重启过就不补（只是少了上一步的思路，不报错）。
 * <p>
 * 工具不在这里执行：返回的工具调用原样放进 {@link AssistantMessage}，和 {@code ModelFactory} 里其它格式一样。
 */
public final class ChatGptResponsesChatModel implements ChatModel {

    static final String USAGE_URL = "https://chatgpt.com/settings/usage";
    static final int MAX_REASONING_ENTRIES = 2048;
    private static final int MAX_REASON = 300;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[^\\s\"',]+");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

    /** 工具调用 id → 那一轮的推理项（已去掉 id）和模型名。 */
    private record Reasoning(String model, ArrayNode items) {
    }

    private static final Map<String, Reasoning> REASONING = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Reasoning> eldest) {
            return size() > MAX_REASONING_ENTRIES;
        }
    };

    private final URI responsesUri;
    private final String model;
    private final String thinking;
    private final ChatGptAccessTokens tokens;
    private final Duration timeout;
    private final Map<String, String> headers;
    private final ChatGptResponsesOptions defaults;

    ChatGptResponsesChatModel(String baseUrl, String model, String thinking, Map<String, String> headers,
                              ChatGptAccessTokens tokens, Duration timeout) {
        if (tokens == null) {
            throw new IllegalArgumentException("ChatGPT 套餐需要先登录（没有令牌源）");
        }
        String base = baseUrl.strip();
        this.responsesUri = URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
                + "/responses");
        this.model = model;
        this.thinking = thinking;
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.tokens = tokens;
        this.timeout = timeout;
        this.defaults = ChatGptResponsesOptions.builder().model(model).build();
    }

    /** 名字看起来是推理模型（会接受 {@code reasoning} 字段）：gpt-5 系列、o1 / o3 / o4、codex。 */
    public static boolean reasoningModel(String model) {
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
        return m.startsWith("gpt-5") || m.matches("^o\\d.*") || m.contains("codex");
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return defaults;
    }

    @Override
    public ChatOptions getOptions() {
        return defaults;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String requestModel = requestModel(prompt);
        String body = requestBody(prompt).toString();
        String token = tokens.current();
        HttpResponse<String> response = send(body, token);
        if (response.statusCode() == 401) {
            tokens.invalidate();
            token = tokens.current();
            response = send(body, token);
            if (response.statusCode() == 401) {
                throw new NonTransientAiException("ChatGPT 登录已失效，请在设置页重新登录（"
                        + reason(response.body(), token) + "）");
            }
        }
        if (response.statusCode() / 100 != 2) {
            throw httpError(response.statusCode(), response.body(), token);
        }
        return parse(response.body(), token, requestModel);
    }

    /** 单次请求选项里的模型名；没写时用创建时的模型名。 */
    private String requestModel(Prompt prompt) {
        ChatOptions options = prompt.getOptions();
        return options != null && options.getModel() != null && !options.getModel().isBlank()
                ? options.getModel() : model;
    }

    // ---------- 请求 ----------

    ObjectNode requestBody(Prompt prompt) {
        ChatOptions options = prompt.getOptions();
        String requestModel = requestModel(prompt);
        ObjectNode body = JSON.createObjectNode();
        body.put("model", requestModel);
        List<String> instructions = new ArrayList<>();
        ArrayNode input = body.putArray("input");
        for (Message message : prompt.getInstructions()) {
            switch (message) {
                case SystemMessage system -> {
                    if (system.getText() != null && !system.getText().isBlank()) {
                        instructions.add(system.getText());
                    }
                }
                case UserMessage user -> input.add(userItem(user));
                case AssistantMessage assistant -> assistantItems(assistant, requestModel, input);
                case ToolResponseMessage tool -> tool.getResponses().forEach(r -> {
                    ObjectNode item = input.addObject();
                    item.put("type", "function_call_output");
                    item.put("call_id", r.id());
                    item.put("output", r.responseData() == null ? "" : r.responseData());
                });
                default -> {
                    // 其它消息类型 JobApply 不用
                }
            }
        }
        if (!instructions.isEmpty()) {
            body.put("instructions", String.join("\n\n", instructions));
        }
        body.put("store", false);
        body.put("stream", true);
        List<ToolCallback> callbacks = options instanceof ToolCallingChatOptions tooling
                && tooling.getToolCallbacks() != null ? tooling.getToolCallbacks() : List.of();
        if (!callbacks.isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolCallback callback : callbacks) {
                ToolDefinition definition = callback.getToolDefinition();
                ObjectNode tool = tools.addObject();
                tool.put("type", "function");
                tool.put("name", definition.name());
                tool.put("description", definition.description() == null ? "" : definition.description());
                tool.set("parameters", readJson(definition.inputSchema(), "工具 " + definition.name() + " 的参数定义"));
                tool.put("strict", false);
            }
        }
        if (options instanceof StructuredOutputChatOptions structured && structured.getOutputSchema() != null
                && !structured.getOutputSchema().isBlank()) {
            ObjectNode format = body.putObject("text").putObject("format");
            format.put("type", "json_schema");
            format.put("name", "json_schema");
            format.set("schema", readJson(structured.getOutputSchema(), "JSON Schema"));
            format.put("strict", true);
        }
        if (reasoningModel(requestModel)) {
            ObjectNode reasoning = body.putObject("reasoning");
            if (thinking != null && List.of("low", "medium", "high").contains(thinking)) {
                reasoning.put("effort", thinking);
            }
            reasoning.put("summary", "auto");
            body.putArray("include").add("reasoning.encrypted_content");
        }
        return body;
    }

    private static ObjectNode userItem(UserMessage user) {
        ObjectNode item = JSON.createObjectNode();
        item.put("type", "message");
        item.put("role", "user");
        ArrayNode content = item.putArray("content");
        String text = user.getText();
        if (text != null && !text.isEmpty()) {
            content.addObject().put("type", "input_text").put("text", text);
        }
        for (Media media : user.getMedia()) {
            content.addObject().put("type", "input_image").put("image_url", imageUrl(media));
        }
        if (content.isEmpty()) {
            content.addObject().put("type", "input_text").put("text", "");
        }
        return item;
    }

    private static String imageUrl(Media media) {
        Object data = media.getData();
        if (data instanceof byte[] bytes) {
            return "data:" + media.getMimeType() + ";base64," + Base64.getEncoder().encodeToString(bytes);
        }
        if (data instanceof String url) {
            return url;
        }
        throw new IllegalArgumentException("不支持的图片数据：" + (data == null ? "空" : data.getClass().getSimpleName()));
    }

    private static void assistantItems(AssistantMessage assistant, String requestModel, ArrayNode input) {
        List<AssistantMessage.ToolCall> calls = assistant.getToolCalls();
        Reasoning reasoning = null;
        synchronized (REASONING) {
            for (AssistantMessage.ToolCall call : calls) {
                Reasoning found = REASONING.get(call.id());
                if (found != null) {
                    reasoning = found;
                    break;
                }
            }
        }
        if (reasoning != null && reasoning.model().equals(requestModel)) {
            reasoning.items().forEach(item -> input.add(item.deepCopy()));
        }
        String text = assistant.getText();
        if (text != null && !text.isBlank()) {
            ObjectNode item = input.addObject();
            item.put("type", "message");
            item.put("role", "assistant");
            item.putArray("content").addObject().put("type", "output_text").put("text", text);
        }
        for (AssistantMessage.ToolCall call : calls) {
            ObjectNode item = input.addObject();
            item.put("type", "function_call");
            item.put("call_id", call.id());
            item.put("name", call.name());
            item.put("arguments", call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments());
        }
    }

    private static JsonNode readJson(String text, String what) {
        try {
            return JSON.readTree(text);
        } catch (JacksonException e) {
            throw new IllegalArgumentException(what + " 不是合法的 JSON", e);
        }
    }

    private HttpResponse<String> send(String body, String token) {
        HttpRequest.Builder request = HttpRequest.newBuilder(responsesUri)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(request::header);
        request.header("Authorization", "Bearer " + token);
        CompletableFuture<HttpResponse<String>> future = HTTP.sendAsync(request.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            // 整个回复（含完整的 SSE 流）都在超时内收完；stream:true 是路由要求，这里收完再解析
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RuntimeException("ChatGPT 请求超时（" + timeout.toSeconds() + " 秒）");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new RuntimeException("ChatGPT 请求被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            String message = cause instanceof IOException && cause.getMessage() != null ? cause.getMessage()
                    : cause.getClass().getSimpleName();
            throw new RuntimeException("ChatGPT 请求失败：" + redact(message, token));
        }
    }

    // ---------- 响应 ----------

    ChatResponse parse(String sse, String token, String requestModel) {
        StringBuilder deltas = new StringBuilder();
        StringBuilder summaryDeltas = new StringBuilder();
        List<JsonNode> items = new ArrayList<>();
        JsonNode completed = null;
        for (JsonNode event : events(sse)) {
            String type = event.path("type").asString("");
            switch (type) {
                case "response.output_text.delta" -> deltas.append(event.path("delta").asString(""));
                case "response.reasoning_summary_text.delta" ->
                        summaryDeltas.append(event.path("delta").asString(""));
                case "response.reasoning_summary_part.done" -> summaryDeltas.append("\n\n");
                case "response.output_item.done" -> items.add(event.path("item"));
                case "response.completed" -> completed = event.path("response");
                case "response.failed" -> {
                    JsonNode error = event.path("response").path("error");
                    throw fail(0, error.path("code").asString(""), error.path("message").asString(""),
                            error.path("param").asString(""), token);
                }
                case "response.incomplete" -> throw new NonTransientAiException("ChatGPT 回复不完整（"
                        + event.path("response").path("incomplete_details").path("reason").asString("未知原因")
                        + "）");
                case "error" -> {
                    JsonNode error = event.has("error") ? event.path("error") : event;
                    throw fail(0, error.path("code").asString(""), error.path("message").asString(""),
                            error.path("param").asString(""), token);
                }
                default -> {
                    // 其它进度事件不用
                }
            }
        }
        if (completed == null) {
            throw new RuntimeException("ChatGPT 的回复流在完成（response.completed）前断开了");
        }
        if (items.isEmpty() && completed.path("output").isArray()) {
            completed.path("output").forEach(items::add);
        }
        StringBuilder text = new StringBuilder();
        StringBuilder summary = new StringBuilder();
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        ArrayNode reasoningItems = JSON.createArrayNode();
        for (JsonNode item : items) {
            switch (item.path("type").asString("")) {
                case "message" -> item.path("content").forEach(part -> {
                    if ("output_text".equals(part.path("type").asString(""))) {
                        text.append(part.path("text").asString(""));
                    }
                });
                case "function_call" -> calls.add(new AssistantMessage.ToolCall(item.path("call_id").asString(""),
                        "function", item.path("name").asString(""), item.path("arguments").asString("{}")));
                case "reasoning" -> {
                    item.path("summary").forEach(part -> {
                        String piece = part.path("text").asString("");
                        if (!piece.isBlank()) {
                            summary.append(summary.isEmpty() ? "" : "\n\n").append(piece.strip());
                        }
                    });
                    ObjectNode copy = ((ObjectNode) item).deepCopy();
                    copy.remove("id");
                    copy.remove("status");
                    reasoningItems.add(copy);
                }
                default -> {
                    // 其它输出项（JobApply 不用 hosted 工具）
                }
            }
        }
        String answer = text.isEmpty() ? deltas.toString() : text.toString();
        String reasoningText = summary.isEmpty() ? summaryDeltas.toString().strip() : summary.toString();
        String responseModel = completed.path("model").asString(requestModel);
        if (!calls.isEmpty() && !reasoningItems.isEmpty()) {
            Reasoning remembered = new Reasoning(requestModel, reasoningItems);
            synchronized (REASONING) {
                calls.forEach(call -> REASONING.put(call.id(), remembered));
            }
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("reasoningContent", reasoningText);
        if (!completed.path("id").asString("").isEmpty()) {
            properties.put("id", completed.path("id").asString(""));
        }
        AssistantMessage message = AssistantMessage.builder().content(answer).properties(properties)
                .toolCalls(calls).build();
        String finish = calls.isEmpty() ? "STOP" : "TOOL_CALLS";
        Generation generation = new Generation(message, ChatGenerationMetadata.builder().finishReason(finish).build());
        JsonNode usage = completed.path("usage");
        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder()
                .id(completed.path("id").asString(""))
                .model(responseModel);
        if (usage.isObject()) {
            metadata.usage(new DefaultUsage(intOrNull(usage, "input_tokens"), intOrNull(usage, "output_tokens"),
                    intOrNull(usage, "total_tokens"), JSON.convertValue(usage, Map.class)));
        }
        return new ChatResponse(List.of(generation), metadata.build());
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? null : value.asInt();
    }

    /** SSE：空行分隔事件，{@code data:} 行拼起来是 JSON；{@code [DONE]} 和解析不了的行跳过。 */
    static List<JsonNode> events(String sse) {
        List<JsonNode> events = new ArrayList<>();
        StringBuilder data = new StringBuilder();
        for (String line : (sse + "\n\n").split("\\r?\\n", -1)) {
            if (line.isEmpty()) {
                if (!data.isEmpty()) {
                    String payload = data.toString().strip();
                    data.setLength(0);
                    if (!payload.equals("[DONE]")) {
                        try {
                            JsonNode node = JSON.readTree(payload);
                            if (node != null && node.isObject()) {
                                events.add(node);
                            }
                        } catch (JacksonException ignored) {
                            // 不是 JSON 的 data 行（心跳等）
                        }
                    }
                }
            } else if (line.startsWith("data:")) {
                data.append(data.isEmpty() ? "" : "\n").append(line.substring(5).stripLeading());
            }
        }
        return events;
    }

    // ---------- 错误 ----------

    private RuntimeException httpError(int status, String body, String token) {
        JsonNode json = null;
        try {
            json = body == null || body.isBlank() ? null : JSON.readTree(body);
        } catch (JacksonException ignored) {
            // 不是 JSON 的错误页
        }
        JsonNode error = json == null ? null : json.path("error");
        if (error != null && error.isObject()) {
            return fail(status, error.path("code").asString(""), error.path("message").asString(""),
                    error.path("param").asString(""), token);
        }
        String detail = json != null && json.path("detail").isString() ? json.path("detail").asString() : body;
        return fail(status, "", detail, "", token);
    }

    /** 错误码 / HTTP 状态 → 异常；不重试的转成 {@link NonTransientAiException}，消息是中文说明。 */
    static RuntimeException fail(int status, String code, String message, String param, String token) {
        String detail = clip(redact(message == null ? "" : message, token));
        String suffix = detail.isBlank() ? "" : "（" + detail + "）";
        return switch (code == null ? "" : code) {
            case "subscription_sharing_usage_limit_exceeded" -> new NonTransientAiException(
                    "ChatGPT 套餐额度已用完，请稍后再试，或在 " + USAGE_URL + " 查看用量");
            case "subscription_sharing_user_not_eligible" -> new NonTransientAiException(
                    "这个 ChatGPT 账号的套餐不能在 JobApply 里使用，请换一个账号或换别的模型提供方" + suffix);
            case "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" ->
                    new NonTransientAiException("ChatGPT 套餐额度暂时不可用，请稍后再试" + suffix);
            case "subscription_sharing_invalid_user" -> new NonTransientAiException(
                    "ChatGPT 登录已失效，请在设置页重新登录" + suffix);
            case "subscription_sharing_unsupported_capability" -> new NonTransientAiException(
                    "ChatGPT 套餐不支持这次请求里的" + (param == null || param.isBlank() ? "某个参数" : " " + param)
                            + suffix);
            default -> {
                String text = "ChatGPT 请求失败" + (status > 0 ? "：HTTP " + status : "")
                        + (code == null || code.isBlank() ? "" : " " + code) + suffix;
                boolean retryable = status == 0 ? code == null || code.isBlank() || code.contains("server")
                        : status == 408 || status == 409 || status == 429 || status >= 500;
                yield retryable && (code == null || !code.startsWith("chatpass_"))
                        ? new RuntimeException(text) : new NonTransientAiException(text);
            }
        };
    }

    private String reason(String body, String token) {
        return clip(redact(body == null ? "" : body, token)).replaceAll("\\s+", " ").strip();
    }

    static String redact(String text, String token) {
        String out = text;
        if (token != null && token.length() >= 8) {
            out = out.replace(token, "***");
        }
        out = BEARER.matcher(out).replaceAll("Bearer ***");
        return JWT.matcher(out).replaceAll("***");
    }

    private static String clip(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() > MAX_REASON ? flat.substring(0, MAX_REASON) + "…" : flat;
    }
}
