package jobapply.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import jobapply.harness.policies.WebUrlPolicy;

/**
 * 后端直接读一个网页（workflow.md「已确认：分层 Web 工具 · fetch」，C005）：每次请求和每次重定向之前都过
 * {@link WebUrlPolicy}；同一个主机两次请求至少隔 {@link #HOST_INTERVAL}；HTML 去掉脚本、样式和标签转成文字，
 * JSON（Greenhouse / Lever / Ashby 的公开接口）原样返回。太长时只给开头和结尾，全文存到 {@code storage/cache/web/}。
 * 内容太少（多半是要在浏览器里渲染的单页应用）时由调用方改用浏览器读。
 */
public final class WebFetch {

    /** 少于这么多字时认为没读到正文。 */
    public static final int THIN = 500;
    /** 超过这么多字时只给开头和结尾。 */
    public static final int LIMIT = 15_000;
    static final Duration HOST_INTERVAL = Duration.ofSeconds(2);
    private static final int MAX_REDIRECTS = 5;
    private static final int HEAD = 11_000;
    private static final int TAIL = 3_500;
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/141.0 Safari/537.36";
    private static final Pattern DROP = Pattern.compile("(?is)<(script|style|noscript|svg|head)\\b.*?</\\1>");
    private static final Pattern BLOCK = Pattern.compile("(?i)<(br|/p|/div|/li|/h[1-6]|/tr|/section|/article)[^>]*>");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    /**
     * @param url       最后读到的网址（重定向之后）
     * @param text      交给模型的文字（太长时已经截断）
     * @param length    全文的字数
     * @param savedTo   全文存在哪里（相对 storage）；没有截断时为 null
     */
    public record Page(String url, String text, int length, String savedTo) {

        public boolean thin() {
            return length < THIN;
        }
    }

    private final HttpClient http;
    private final Path cacheDir;
    private final String cacheLabel;
    private final Map<String, Long> lastRequest = new HashMap<>();
    private final List<String> blocklist;

    /**
     * @param cacheDir   全文存放的目录（{@code storage/cache/web}）
     * @param cacheLabel 给模型看的目录名（相对 storage）
     * @param blocklist  用户配置的域名黑名单；没有时为空
     */
    public WebFetch(Path cacheDir, String cacheLabel, List<String> blocklist) {
        this.blocklist = blocklist == null ? List.of() : List.copyOf(blocklist);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.cacheDir = cacheDir;
        this.cacheLabel = cacheLabel;
    }

    /**
     * 读一个网页。网址不允许时抛出 {@link jobapply.harness.policies.ToolNotAllowedException}，网络出错或返回非 2xx 时
     * 抛出 {@link IOException}。
     */
    /** 网址检查（{@link WebUrlPolicy}，带上用户的黑名单）；浏览器打开之前也用它。 */
    public URI checkUrl(String url) {
        return WebUrlPolicy.check(url, blocklist);
    }

    public Page fetch(String url) throws IOException, InterruptedException {
        URI target = checkUrl(url);
        HttpResponse<String> response = null;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            pace(target.getHost());
            response = http.send(HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(20))
                            .header("User-Agent", USER_AGENT)
                            .header("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
                            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status / 100 != 3) {
                break;
            }
            String location = response.headers().firstValue("Location").orElse(null);
            if (location == null) {
                break;
            }
            target = checkUrl(target.resolve(location).toString());
            response = null;
        }
        if (response == null) {
            throw new IOException("重定向超过 " + MAX_REDIRECTS + " 次");
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + response.statusCode());
        }
        String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        String body = response.body() == null ? "" : response.body();
        String text = type.contains("json") ? body.strip() : type.contains("html") || body.contains("<html")
                ? text(body) : body.strip();
        return page(target.toString(), text);
    }

    /** 把读到的全文（也可以是浏览器快照）变成交给模型的结果：太长时截断并存下全文。 */
    public Page page(String url, String text) {
        if (text.length() <= LIMIT) {
            return new Page(url, text, text.length(), null);
        }
        String name = sha256(url) + ".txt";
        try {
            Files.createDirectories(cacheDir);
            Files.writeString(cacheDir.resolve(name), text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("存不下网页全文：" + name, e);
        }
        String shown = text.substring(0, HEAD) + "\n\n……（中间省略 " + (text.length() - HEAD - TAIL) + " 字）……\n\n"
                + text.substring(text.length() - TAIL);
        return new Page(url, shown, text.length(), cacheLabel + "/" + name);
    }

    /** 同一个主机两次请求之间至少隔 {@link #HOST_INTERVAL}。 */
    private void pace(String host) throws InterruptedException {
        long wait;
        synchronized (lastRequest) {
            long now = System.currentTimeMillis();
            long next = lastRequest.getOrDefault(host, 0L) + HOST_INTERVAL.toMillis();
            wait = Math.max(0, next - now);
            lastRequest.put(host, now + wait);
        }
        if (wait > 0) {
            Thread.sleep(wait);
        }
    }

    static String text(String html) {
        String stripped = DROP.matcher(html).replaceAll(" ");
        stripped = BLOCK.matcher(stripped).replaceAll("\n");
        stripped = TAG.matcher(stripped).replaceAll(" ");
        stripped = stripped.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&#x27;", "'").replace("&lt;", "<").replace("&gt;", ">");
        StringBuilder out = new StringBuilder();
        for (String line : stripped.split("\n")) {
            String clean = line.replaceAll("[ \\t\\x0B\\f\\r]+", " ").strip();
            if (!clean.isEmpty()) {
                out.append(clean).append('\n');
            }
        }
        return out.toString().strip();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
