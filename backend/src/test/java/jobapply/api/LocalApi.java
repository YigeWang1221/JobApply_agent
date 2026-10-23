package jobapply.api;

import static java.nio.charset.StandardCharsets.UTF_8;

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
 * 测试用：向本机端口发 HTTP 请求（和 curl 一样不带 Origin 请求头；需要时由调用方加）。
 */
public final class LocalApi {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    public record Response(int status, String body, String contentType) {

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

    public Response putJson(String path, Object body, String... headers) {
        return send(request(path, headers).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
    }

    public Response patchJson(String path, Object body, String... headers) {
        return send(request(path, headers).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
    }

    /** 写接口一律要求 JSON 的 Content-Type，DELETE 也一样（没有请求体）。 */
    public Response delete(String path, String... headers) {
        return send(request(path, headers).header("Content-Type", "application/json").DELETE());
    }

    /** {@code multipart/form-data}，一个文件字段。 */
    public Response upload(String path, String field, String fileName, byte[] content, String... headers) {
        String boundary = "----jobapply-test-" + System.nanoTime();
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field + "\"; filename=\""
                + fileName + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                .getBytes(UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(UTF_8);
        byte[] body = new byte[head.length + content.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(content, 0, body, head.length, content.length);
        System.arraycopy(tail, 0, body, head.length + content.length, tail.length);
        return send(request(path, headers).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)));
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
        return "/api/jobs/" + jobId + "/application";
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
            return new Response(response.statusCode(), response.body(),
                    response.headers().firstValue("Content-Type").orElse(null));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
