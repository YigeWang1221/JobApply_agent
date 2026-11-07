package jobapply.config;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 请求提供方的模型列表。OpenAI 兼容格式：{@code GET {base}/models}，带 {@code Authorization: Bearer}；
 * ChatGPT 套餐（C028）：{@code GET {base}/models}，带当前登录的 access token，返回 {@code models} 数组。
 * Anthropic 格式：{@code GET {base}/v1/models}，带 {@code x-api-key} 和 {@code anthropic-version}。超时 10 秒。
 * 有的网关没有这个接口，失败时返回原因（HTTP 状态码和对方的错误信息），让用户手填模型名。原因里不带 Key。
 */
public final class ModelCatalogClient {

    static final Duration TIMEOUT = Duration.ofSeconds(10);
    /** 和 anthropic-java SDK 2.52.0 发送的版本一致。 */
    static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int MAX_REASON = 200;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public sealed interface Result {
    }

    /** @param labels slug → 显示名；只有 ChatGPT 的目录带（C028），其它为空。 */
    public record Models(List<String> ids, Map<String, String> labels) implements Result {

        public Models {
            labels = labels == null ? Map.of() : Map.copyOf(labels);
        }

        public Models(List<String> ids) {
            this(ids, Map.of());
        }
    }

    public record Failed(String reason) implements Result {
    }

    private final HttpClient http;
    private final Duration timeout;

    public ModelCatalogClient() {
        this(TIMEOUT);
    }

    ModelCatalogClient(Duration timeout) {
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /**
     * @param apiKey 解析好的 Key；本地模型为 null
     */
    public Result list(String format, String baseUrl, String apiKey) {
        return list(format, baseUrl, apiKey, Map.of());
    }

    /**
     * @param apiKey  解析好的 Key；本地模型为 null
     * @param headers 解析好的自定义请求头（C025）
     */
    public Result list(String format, String baseUrl, String apiKey, Map<String, String> headers) {
        URI uri;
        try {
            String base = baseUrl.strip().replaceAll("/+$", "");
            uri = URI.create("anthropic".equals(format) ? base + "/v1/models?limit=1000" : base + "/models");
        } catch (IllegalArgumentException | NullPointerException e) {
            return new Failed("Base URL 不合法");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout).GET()
                .header("Accept", "application/json");
        try {
            OpenCodeSession.withHeader(uri.getHost(), headers).forEach(request::header);
        } catch (IllegalArgumentException e) {
            return new Failed("自定义请求头不被允许（例如 Host、Connection 这类由客户端自己管的头）");
        }
        if ("anthropic".equals(format)) {
            request.header("anthropic-version", ANTHROPIC_VERSION);
            if (apiKey != null) {
                request.header("x-api-key", apiKey);
            }
        } else if (apiKey != null) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            return new Failed("请求超时（" + timeout.toSeconds() + " 秒）：" + uri.getHost());
        } catch (ConnectException e) {
            return new Failed("连不上 " + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : ""));
        } catch (IOException e) {
            return new Failed(redact(redact("请求失败：" + e.getMessage(), apiKey), headers));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Failed("请求被中断");
        }
        if (response.statusCode() / 100 != 2) {
            return new Failed("HTTP " + response.statusCode() + errorMessage(response.body(), apiKey, headers));
        }
        if ("chatgpt".equals(format)) {
            return chatGptModels(response.body());
        }
        try {
            JsonNode data = JSON.readTree(response.body()).path("data");
            if (!data.isArray()) {
                return new Failed("返回内容里没有 data 列表");
            }
            List<String> ids = new ArrayList<>();
            for (JsonNode model : data) {
                String id = model.path("id").asString("");
                if (!id.isBlank()) {
                    ids.add(id);
                }
            }
            return new Models(ids.stream().distinct().sorted().toList());
        } catch (JacksonException e) {
            return new Failed("返回内容不是合法的 JSON");
        }
    }

    /**
     * ChatGPT 套餐的模型目录（C028，官方 models-and-inference）：{@code models} 数组，只留 {@code visibility == "list"}，
     * 保持服务端的顺序；存和发送用 {@code slug}，页面显示 {@code display_name}。
     */
    private static Result chatGptModels(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode models = root.path("models").isArray() ? root.path("models") : root.path("data");
            if (!models.isArray()) {
                return new Failed("返回内容里没有 models 列表");
            }
            List<String> ids = new ArrayList<>();
            Map<String, String> labels = new LinkedHashMap<>();
            for (JsonNode model : models) {
                if (!"list".equals(model.path("visibility").asString(""))) {
                    continue;
                }
                String slug = model.path("slug").asString("");
                if (slug.isBlank()) {
                    slug = model.path("id").asString("");
                }
                if (slug.isBlank() || ids.contains(slug)) {
                    continue;
                }
                ids.add(slug);
                String label = model.path("display_name").asString("");
                if (!label.isBlank() && !label.equals(slug)) {
                    labels.put(slug, label);
                }
            }
            return new Models(ids, labels);
        } catch (JacksonException e) {
            return new Failed("返回内容不是合法的 JSON");
        }
    }

    /** 先遮住 Key 再截断，免得截断处留下半个 Key。 */
    private static String errorMessage(String body, String apiKey, Map<String, String> headers) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String message;
        try {
            JsonNode error = JSON.readTree(body).path("error");
            message = error.isString() ? error.asString() : error.path("message").asString("");
        } catch (JacksonException e) {
            message = "";
        }
        if (message.isBlank()) {
            message = body;
        }
        message = redact(redact(message, apiKey), headers).replaceAll("\\s+", " ").strip();
        return "：" + (message.length() > MAX_REASON ? message.substring(0, MAX_REASON) + "…" : message);
    }

    /** 自定义请求头的值同样遮住（其中可能有密钥）。 */
    static String redact(String text, Map<String, String> headers) {
        String out = text;
        for (String value : headers.values()) {
            if (value.length() >= 8) {
                out = redact(out, value);
            }
        }
        return out;
    }

    /** 对方的错误信息里偶尔会回显 Key，统一遮住。 */
    static String redact(String text, String apiKey) {
        if (text == null || apiKey == null || apiKey.isBlank()) {
            return text;
        }
        return text.replace(apiKey, "****");
    }
}
