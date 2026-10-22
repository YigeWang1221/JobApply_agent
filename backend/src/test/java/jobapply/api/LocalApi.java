package jobapply.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 测试用：向本机端口发 HTTP 请求（和 curl 一样不带 Origin 请求头）。
 */
public final class LocalApi {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    public record Response(int status, String body) {

        public Map<String, Object> json() {
            return JSON.readValue(body, MAP);
        }

        public Object get(String key) {
            return json().get(key);
        }
    }

    private final HttpClient client = HttpClient.newHttpClient();
    private final String base;

    public LocalApi(int port) {
        this.base = "http://127.0.0.1:" + port;
    }

    public Response get(String path, String... headers) {
        return send(request(path, headers).GET());
    }

    public Response postJson(String path, Map<String, Object> body) {
        return post(path, "application/json", JSON.writeValueAsString(body));
    }

    public Response post(String path, String contentType, String body, String... headers) {
        HttpRequest.Builder builder = request(path, headers).POST(HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        return send(builder);
    }

    /** 轮询登录接力的状态，直到它不再是 running。 */
    public Map<String, Object> settle(String jobId) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            Response response = get(path(jobId));
            if (response.status() == 200 && !"running".equals(response.get("status"))) {
                return response.json();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("登录接力 30 秒内没有停下：" + jobId);
    }

    public static String path(String jobId) {
        return "/api/jobs/" + jobId + "/login-handoff";
    }

    private HttpRequest.Builder request(String path, String... headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return builder;
    }

    private Response send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
