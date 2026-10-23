package jobapply.tools;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 搜索（workflow.md「已确认：分层 Web 工具 · search」，参考 Hermes）。可用的来源：DuckDuckGo（不要 key）、本机 SearXNG
 * （配了地址才有）、Brave（填了 key 才有）。模型可以点名用哪一个（{@link #available()}）；不点名时免费的优先，按顺序试，
 * 被限流、出错或没有结果就换下一个。都不行时返回空结果和每一个的原因，由模型自己换办法（换来源、直接读招聘站或搜索
 * 引擎的结果页）。同一查询缓存 20 分钟。
 */
public final class WebSearch {

    public static final int RESULTS = 5;
    static final Duration CACHE_TTL = Duration.ofMinutes(20);
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/141.0 Safari/537.36";
    /** 一条结果的标题链接。摘要在它和下一条标题之间找（{@link #DDG_SNIPPET}）。 */
    private static final Pattern DDG_TITLE = Pattern.compile(
            "<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern DDG_SNIPPET = Pattern.compile(
            "<(a|div|td)[^>]+class=\"result__snippet\"[^>]*>(.*?)</\\1>", Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Result(String title, String url, String description) {
    }

    /**
     * @param results  找到的结果（最多 {@link #RESULTS} 条）
     * @param provider 用的是哪一级；都失败时为 null
     * @param tried    每一级试过的情况（例如「duckduckgo：被限流」）
     */
    public record Outcome(List<Result> results, String provider, List<String> tried) {
    }

    private record Cached(Outcome outcome, Instant at) {
    }

    private final HttpClient http;
    private final String searxngUrl;
    private final Supplier<Optional<String>> braveKey;
    private final Map<String, Cached> cache = new LinkedHashMap<>();

    /**
     * @param searxngUrl 本机 SearXNG 的地址；为空时跳过这一级
     * @param braveKey   Brave Search 的 key（程序从 {@code storage/settings/.env} 读）；没有时跳过这一级
     */
    public WebSearch(String searxngUrl, Supplier<Optional<String>> braveKey) {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        this.searxngUrl = searxngUrl == null ? "" : searxngUrl.strip();
        this.braveKey = braveKey == null ? Optional::empty : braveKey;
    }

    /** 现在能用的来源名字，按不点名时试的顺序。 */
    public List<String> available() {
        return providers().stream().map(Provider::name).toList();
    }

    public Outcome search(String query) {
        return search(query, null);
    }

    /**
     * @param source 只用这个来源；为空或 {@code auto} 时按顺序试所有可用的来源
     */
    public synchronized Outcome search(String query, String source) {
        String only = source == null || source.isBlank() || source.strip().equalsIgnoreCase("auto") ? null
                : source.strip().toLowerCase(Locale.ROOT);
        String key = (only == null ? "" : only + ":") + query.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        Cached cached = cache.get(key);
        if (cached != null && cached.at().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached.outcome();
        }
        List<String> tried = new ArrayList<>();
        Outcome outcome = new Outcome(List.of(), null, tried);
        List<Provider> chosen = providers().stream().filter(p -> only == null || p.name().equals(only)).toList();
        if (chosen.isEmpty()) {
            tried.add(only + "：没有这个来源，或者没有配置（现在能用：" + String.join("、", available()) + "）");
        }
        for (Provider provider : chosen) {
            try {
                List<Result> results = provider.search(query.strip());
                if (results.isEmpty()) {
                    tried.add(provider.name() + "：没有结果");
                    continue;
                }
                outcome = new Outcome(results.subList(0, Math.min(RESULTS, results.size())), provider.name(), tried);
                break;
            } catch (IOException | RuntimeException e) {
                tried.add(provider.name() + "：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                tried.add(provider.name() + "：被中断");
                break;
            }
        }
        if (!outcome.results().isEmpty()) {
            cache.put(key, new Cached(outcome, Instant.now()));
        }
        return outcome;
    }

    private interface Provider {
        String name();

        List<Result> search(String query) throws IOException, InterruptedException;
    }

    private List<Provider> providers() {
        List<Provider> providers = new ArrayList<>();
        providers.add(new Provider() {
            public String name() {
                return "duckduckgo";
            }

            public List<Result> search(String query) throws IOException, InterruptedException {
                return duckDuckGo(query);
            }
        });
        if (!searxngUrl.isEmpty()) {
            providers.add(new Provider() {
                public String name() {
                    return "searxng";
                }

                public List<Result> search(String query) throws IOException, InterruptedException {
                    return searxng(query);
                }
            });
        }
        braveKey.get().filter(value -> !value.isBlank()).ifPresent(value -> providers.add(new Provider() {
            public String name() {
                return "brave";
            }

            public List<Result> search(String query) throws IOException, InterruptedException {
                return brave(query, value);
            }
        }));
        return providers;
    }

    private List<Result> duckDuckGo(String query) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://html.duckduckgo.com/html/"))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("q=" + encode(query)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        requireOk(response);
        if (response.body().contains("anomaly-modal") || response.body().contains("challenge-form")) {
            throw new IOException("被限流（要求人机验证）");
        }
        List<Result> results = new ArrayList<>();
        String body = response.body();
        Matcher m = DDG_TITLE.matcher(body);
        List<int[]> spans = new ArrayList<>();
        List<String[]> titles = new ArrayList<>();
        while (m.find()) {
            spans.add(new int[] {m.start(), m.end()});
            titles.add(new String[] {m.group(1), m.group(2)});
        }
        for (int i = 0; i < titles.size() && results.size() < RESULTS; i++) {
            String url = ddgTarget(titles.get(i)[0]);
            if (url == null) {
                continue;
            }
            int end = i + 1 < spans.size() ? spans.get(i + 1)[0] : body.length();
            Matcher snippet = DDG_SNIPPET.matcher(body.substring(spans.get(i)[1], end));
            results.add(new Result(text(titles.get(i)[1]), url, snippet.find() ? text(snippet.group(2)) : ""));
        }
        return results;
    }

    private List<Result> searxng(String query) throws IOException, InterruptedException {
        URI uri = URI.create(searxngUrl.replaceAll("/+$", "") + "/search?format=json&q=" + encode(query));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET()
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        requireOk(response);
        List<Result> results = new ArrayList<>();
        for (JsonNode item : JSON.readTree(response.body()).path("results")) {
            results.add(new Result(item.path("title").asString(""), item.path("url").asString(""),
                    item.path("content").asString("")));
        }
        return results;
    }

    private List<Result> brave(String query, String key) throws IOException, InterruptedException {
        URI uri = URI.create("https://api.search.brave.com/res/v1/web/search?count=" + RESULTS + "&q=" + encode(query));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json").header("X-Subscription-Token", key).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        requireOk(response);
        List<Result> results = new ArrayList<>();
        for (JsonNode item : JSON.readTree(response.body()).path("web").path("results")) {
            results.add(new Result(item.path("title").asString(""), item.path("url").asString(""),
                    text(item.path("description").asString(""))));
        }
        return results;
    }

    private static void requireOk(HttpResponse<String> response) throws IOException {
        int status = response.statusCode();
        if (status == 429 || status == 202) {
            throw new IOException("被限流（HTTP " + status + "）");
        }
        if (status / 100 != 2) {
            throw new IOException("HTTP " + status);
        }
    }

    /** DuckDuckGo 的结果链接是 {@code //duckduckgo.com/l/?uddg=<真正的网址>}；广告链接跳过。 */
    private static String ddgTarget(String href) {
        String decoded = href.replace("&amp;", "&");
        int at = decoded.indexOf("uddg=");
        String url = at < 0 ? decoded : URLDecoder.decode(decoded.substring(at + 5).split("&")[0], StandardCharsets.UTF_8);
        if (url.contains("duckduckgo.com/y.js") || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return null;
        }
        return url;
    }

    private static String text(String html) {
        if (html == null) {
            return "";
        }
        return TAG.matcher(html).replaceAll("").replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replaceAll("\\s+", " ").strip();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
