package jobapply.tools;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试用：本机 127.0.0.1 随机端口上的假 GitHub REST 接口。返回结构按 docs.github.com/en/rest（2022-11-28）写，
 * 仓库和内容全部虚构。默认有一个公开仓库 {@code example-owner/demo}；可以按路径覆盖返回。记录每个请求的路径和请求头。
 */
public final class FakeGitHubServer implements AutoCloseable {

    public static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    /** 一条预设的返回。 */
    public record Reply(int status, String body, Map<String, String> headers) {

        public static Reply json(String body) {
            return new Reply(200, body, Map.of());
        }
    }

    /** 收到的请求：路径加查询，和 Authorization 请求头（应当一直是 null）。 */
    public record Received(String pathAndQuery, String authorization, String userAgent, String apiVersion) {
    }

    private final HttpServer server;
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();

    public FakeGitHubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.start();
        defaults();
    }

    public URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public void reply(String pathAndQuery, Reply reply) {
        replies.put(pathAndQuery, reply);
    }

    public List<Received> received() {
        return new ArrayList<>(received);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void defaults() {
        reply("/repos/example-owner/demo", Reply.json("""
                {"full_name": "example-owner/demo", "private": false, "visibility": "public", "fork": false,
                 "default_branch": "main", "description": "示例仓库", "topics": ["demo", "java"], "size": 12}"""));
        reply("/repos/example-owner/demo/commits/main", Reply.json("""
                {"sha": "%s", "commit": {"message": "最近一次提交", "author": {"name": "Demo", "email": "demo@example.com",
                 "date": "2026-02-01T00:00:00Z"}}}""".formatted(SHA)));
        reply("/repos/example-owner/demo/git/trees/" + SHA + "?recursive=1", Reply.json("""
                {"sha": "%s", "truncated": false, "tree": [
                  {"path": "README.md", "type": "blob", "size": 1200},
                  {"path": ".env", "type": "blob", "size": 40},
                  {"path": "package.json", "type": "blob", "size": 300},
                  {"path": "src", "type": "tree"},
                  {"path": "src/main.ts", "type": "blob", "size": 5000},
                  {"path": "src/server.pem", "type": "blob", "size": 900},
                  {"path": "node_modules", "type": "tree"},
                  {"path": "node_modules/x/package.json", "type": "blob", "size": 100},
                  {"path": "docs/README.md", "type": "blob", "size": 700},
                  {"path": "service/pom.xml", "type": "blob", "size": 800}
                ]}""".formatted(SHA)));
        reply("/repos/example-owner/demo/languages", Reply.json("{\"TypeScript\": 5000, \"Java\": 800}"));
        reply("/repos/example-owner/demo/contents/README.md?ref=" + SHA, Reply.json(
                "{\"name\": \"README.md\", \"encoding\": \"base64\", \"content\": \""
                        + Base64.getMimeEncoder().encodeToString("# 示例仓库\n虚构的介绍。\n".getBytes(UTF_8))
                        .replace("\r\n", "\\n") + "\"}"));
        reply("/repos/example-owner/demo/commits?sha=" + SHA + "&per_page=30", Reply.json("""
                [{"sha": "b", "commit": {"message": "第二次提交\\n\\n正文", "author": {"name": "Demo",
                  "email": "demo@example.com", "date": "2026-02-01T00:00:00Z"}}, "author": {"login": "example-owner"}},
                 {"sha": "a", "commit": {"message": "初始化", "author": {"name": "Other", "email": "other@example.com",
                  "date": "2026-01-01T00:00:00Z"}}, "author": null}]"""));
        reply("/users/example-owner/repos?type=owner&sort=pushed&per_page=100", Reply.json("""
                [{"full_name": "example-owner/demo", "private": false, "fork": false, "archived": false,
                  "description": "示例仓库", "pushed_at": "2026-02-01T00:00:00Z"},
                 {"full_name": "example-owner/fork-demo", "private": false, "fork": true, "archived": true,
                  "description": null, "pushed_at": "2025-02-01T00:00:00Z"}]"""));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String pathAndQuery = exchange.getRequestURI().getRawPath()
                + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
        received.add(new Received(pathAndQuery, exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("User-Agent"),
                exchange.getRequestHeaders().getFirst("X-GitHub-Api-Version")));
        Reply reply = replies.getOrDefault(pathAndQuery, new Reply(404, "{\"message\": \"Not Found\"}", Map.of()));
        byte[] body = reply.body().getBytes(UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        exchange.sendResponseHeaders(reply.status(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
