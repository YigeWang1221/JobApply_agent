package jobapply.tools;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import jobapply.domain.SourceScan;
import jobapply.domain.Timestamps;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 只读 GitHub 公开仓库（REST API，不带 token，未认证额度每小时 60 次）。只访问 GitHub 的接口，不做网页抓取。
 * 读取固定到当时默认分支的 commit SHA；文件列表套用 {@link SecretFileRules}。返回 403 / 429 时报出原因和重置时间，
 * 不自动重试。接口地址可以配置（测试指向本机的假服务端）。
 * 接口与返回结构按 docs.github.com/en/rest（API 版本 2022-11-28）。
 */
public final class GitHubPublicReader {

    public static final URI DEFAULT_API = URI.create("https://api.github.com");
    static final String API_VERSION = "2022-11-28";
    static final long MAX_FILE_BYTES = LocalDirectoryScanner.TEXT_MAX_BYTES;
    static final int COMMITS_PER_PAGE = 30;

    /** GitHub 的用户名和组织名：字母、数字和 -，不以 - 开头。 */
    private static final Pattern OWNER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,38}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,100}");
    private static final Pattern URL = Pattern.compile(
            "(?:https?://)?(?:www\\.)?github\\.com/([^/?#]+)(?:/([^/?#]+))?/?(?:[?#].*)?");
    private static final Pattern README = Pattern.compile("(?i)readme(\\.(md|markdown|txt|rst))?");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 用户给的仓库；只有 owner 时 {@code repo} 为 null。 */
    public record Ref(String owner, String repo) {
    }

    public record RepoSummary(String fullName, String description, boolean fork, boolean archived, String pushedAt) {
    }

    /** 仓库信息，{@code headSha} 是默认分支当时的 commit。 */
    public record RepoInfo(String owner, String repo, String defaultBranch, boolean fork, String description,
                           List<String> topics, String headSha) {

        public String fullName() {
            return owner + "/" + repo;
        }
    }

    /** 读取失败。{@code kind} 决定接口返回的状态码。 */
    public static final class GitHubException extends RuntimeException {

        public enum Kind {
            NOT_FOUND, EMPTY, RATE_LIMITED, UPSTREAM
        }

        private final Kind kind;

        public GitHubException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }

    private final URI api;
    private final Duration timeout;
    private final HttpClient http;
    private int calls;

    public GitHubPublicReader(URI api, Duration timeout) {
        this.api = api;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** 这个实例到现在为止调用了几次接口。 */
    public synchronized int calls() {
        return calls;
    }

    /** 接受 {@code https://github.com/<owner>/<repo>}、{@code <owner>/<repo>}、只有 owner。 */
    public static Ref parse(String input) {
        String text = input == null ? "" : input.strip();
        String owner;
        String repo;
        Matcher url = URL.matcher(text);
        if (url.matches()) {
            owner = url.group(1);
            repo = url.group(2);
        } else if (!text.contains(":") && !text.contains("\\")) {
            String[] parts = text.replaceAll("/+$", "").split("/", -1);
            if (parts.length > 2) {
                throw new IllegalArgumentException("GitHub 地址要写成 owner/repo：" + input);
            }
            owner = parts[0];
            repo = parts.length == 2 ? parts[1] : null;
        } else {
            throw new IllegalArgumentException("只支持 GitHub 仓库地址或 owner/repo：" + input);
        }
        if (repo != null && repo.toLowerCase(Locale.ROOT).endsWith(".git")) {
            repo = repo.substring(0, repo.length() - 4);
        }
        if (!OWNER.matcher(owner).matches()
                || (repo != null && (!NAME.matcher(repo).matches() || repo.equals(".") || repo.equals("..")))) {
            throw new IllegalArgumentException("GitHub 的 owner / 仓库名不合法：" + input);
        }
        return new Ref(owner, repo);
    }

    /** owner 的公开仓库（最近推送的在前，最多 100 个）。1 次调用。 */
    public List<RepoSummary> listRepositories(String owner) {
        JsonNode list = get("/users/" + owner + "/repos?type=owner&sort=pushed&per_page=100", "用户或组织不存在：" + owner);
        List<RepoSummary> repos = new ArrayList<>();
        for (JsonNode repo : list) {
            if (repo.path("private").asBoolean(false)) {
                continue;
            }
            repos.add(new RepoSummary(repo.path("full_name").asString(""), repo.path("description").asString(""),
                    repo.path("fork").asBoolean(false), repo.path("archived").asBoolean(false),
                    repo.path("pushed_at").asString("")));
        }
        return repos;
    }

    /** 仓库信息和默认分支当时的 commit SHA。不是公开的、不存在或者是空仓库时报错。2 次调用。 */
    public RepoInfo repository(String owner, String repo) {
        JsonNode info = get("/repos/" + owner + "/" + repo, notFound(owner, repo));
        if (info.path("private").asBoolean(false) || !"public".equals(info.path("visibility").asString("public"))) {
            throw new GitHubException(GitHubException.Kind.NOT_FOUND, notFound(owner, repo));
        }
        String branch = info.path("default_branch").asString("");
        if (branch.isEmpty()) {
            throw new GitHubException(GitHubException.Kind.EMPTY, "仓库是空的：" + owner + "/" + repo);
        }
        JsonNode commit = get("/repos/" + owner + "/" + repo + "/commits/" + path(branch), notFound(owner, repo));
        String sha = commit.path("sha").asString("");
        if (sha.isEmpty()) {
            throw new GitHubException(GitHubException.Kind.UPSTREAM, "GitHub 没有返回 commit SHA");
        }
        List<String> topics = new ArrayList<>();
        info.path("topics").forEach(topic -> topics.add(topic.asString("")));
        String[] fullName = info.path("full_name").asString(owner + "/" + repo).split("/", 2);
        return new RepoInfo(fullName[0], fullName.length > 1 ? fullName[1] : repo, branch,
                info.path("fork").asBoolean(false), info.path("description").asString(""), topics, sha);
    }

    /**
     * 估算用的扫描：仓库信息、固定 SHA、读文件列表（3 次调用），按文件大小估算 README 和依赖清单的字符数。
     * 不读文件内容。源代码只算文件列表本身。
     */
    public SourceScan scan(String owner, String repo, LocalDirectoryScanner.Limits limits) {
        int before = calls();
        RepoInfo info = repository(owner, repo);
        JsonNode tree = get("/repos/" + owner + "/" + repo + "/git/trees/" + info.headSha() + "?recursive=1",
                notFound(owner, repo));
        List<SourceScan.FileEntry> files = new ArrayList<>();
        List<SourceScan.Skipped> skipped = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (tree.path("truncated").asBoolean(false)) {
            warnings.add("仓库文件太多，GitHub 只返回了一部分文件列表");
        }
        long chars = 0;
        long listing = 0;
        boolean filesWarned = false;
        for (JsonNode entry : tree.path("tree")) {
            String path = entry.path("path").asString("");
            String type = entry.path("type").asString("");
            int slash = path.lastIndexOf('/');
            String name = path.substring(slash + 1);
            String parent = slash < 0 ? "" : path.substring(0, slash);
            if (!parent.isEmpty() && SecretFileRules.skippedPath(parent + "/x").isPresent()) {
                continue;
            }
            if (type.equals("tree")) {
                SecretFileRules.skippedDirectory(name).ifPresent(reason -> skipped.add(
                        new SourceScan.Skipped(path + "/", reason)));
                continue;
            }
            if (!type.equals("blob")) {
                continue;
            }
            if (SecretFileRules.isSecret(name)) {
                skipped.add(new SourceScan.Skipped(path, SecretFileRules.SECRET));
                continue;
            }
            listing += path.length() + 1;
            boolean readme = parent.isEmpty() && README.matcher(name).matches();
            boolean manifest = "manifest".equals(LocalDirectoryScanner.kind(name));
            if (!readme && !manifest) {
                continue;
            }
            long size = entry.path("size").asLong(0);
            if (size > MAX_FILE_BYTES) {
                skipped.add(new SourceScan.Skipped(path, "文件太大"));
            } else if (files.size() >= limits.maxFiles() || chars + size > limits.maxChars()) {
                if (!filesWarned) {
                    warnings.add("超过单个来源的上限，后面的 README / 依赖清单不会读");
                    filesWarned = true;
                }
                skipped.add(new SourceScan.Skipped(path, "超过上限"));
            } else {
                files.add(new SourceScan.FileEntry(path, readme ? "doc" : "manifest", size));
                chars += size;
            }
        }
        files.add(new SourceScan.FileEntry("（文件列表）", "tree", listing));
        chars += listing;
        return new SourceScan(info.headSha(), files, skipped, chars, null, warnings, calls() - before, filesWarned);
    }

    /** 开始整理以后读这个来源还要几次调用：语言、每个 README / 依赖清单一次、提交摘要。 */
    public static int plannedCalls(SourceScan scan) {
        int contents = (int) scan.files().stream().filter(file -> !file.kind().equals("tree")).count();
        return 1 + contents + 1;
    }

    /** 各语言的字节数（{@code /languages}）。1 次调用。 */
    public Map<String, Long> languages(String owner, String repo) {
        Map<String, Long> languages = new LinkedHashMap<>();
        get("/repos/" + owner + "/" + repo + "/languages", notFound(owner, repo)).properties()
                .forEach(entry -> languages.put(entry.getKey(), entry.getValue().asLong(0)));
        return languages;
    }

    /** 按 SHA 读一个文件的文字（{@code /contents/{path}?ref=}）。1 次调用。 */
    public String content(String owner, String repo, String sha, String path) {
        if (SecretFileRules.skippedPath(path).isPresent()) {
            throw new IllegalArgumentException("这个文件按规则跳过，不读取：" + path);
        }
        JsonNode file = get("/repos/" + owner + "/" + repo + "/contents/" + path(path) + "?ref=" + sha,
                "文件不存在：" + path);
        if (!"base64".equals(file.path("encoding").asString(""))) {
            throw new GitHubException(GitHubException.Kind.UPSTREAM, "GitHub 没有返回文件内容：" + path);
        }
        return new String(Base64.getMimeDecoder().decode(file.path("content").asString("")), UTF_8);
    }

    /** 最近 {@value #COMMITS_PER_PAGE} 个提交的摘要。1 次调用。 */
    public SourceScan.CommitSummary commitSummary(String owner, String repo, String sha, Set<String> identities) {
        Set<String> me = identities.stream().map(id -> id.strip().toLowerCase(Locale.ROOT))
                .filter(id -> !id.isEmpty()).collect(Collectors.toSet());
        JsonNode commits = get("/repos/" + owner + "/" + repo + "/commits?sha=" + sha + "&per_page=" + COMMITS_PER_PAGE,
                notFound(owner, repo));
        int count = 0;
        int own = 0;
        String first = null;
        String last = null;
        List<String> titles = new ArrayList<>();
        for (JsonNode commit : commits) {
            JsonNode author = commit.path("commit").path("author");
            String when = author.path("date").asString(null);
            if (last == null) {
                last = when;
            }
            first = when;
            count++;
            if (me.contains(author.path("email").asString("").toLowerCase(Locale.ROOT))
                    || me.contains(author.path("name").asString("").toLowerCase(Locale.ROOT))
                    || me.contains(commit.path("author").path("login").asString("").toLowerCase(Locale.ROOT))) {
                own++;
            }
            if (titles.size() < LocalDirectoryScanner.RECENT_TITLES) {
                String title = commit.path("commit").path("message").asString("").lines().findFirst().orElse("");
                titles.add(title.length() > 80 ? title.substring(0, 80) + "…" : title.strip());
            }
        }
        Integer percent = me.isEmpty() || count == 0 ? null : Math.round(own * 100f / count);
        return new SourceScan.CommitSummary(count, first, last, percent, titles);
    }

    private JsonNode get(String pathAndQuery, String notFoundMessage) {
        URI uri = URI.create(api.toString().replaceAll("/+$", "") + pathAndQuery);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).GET()
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", API_VERSION)
                .header("User-Agent", "JobApply-local")
                .build();
        HttpResponse<String> response;
        synchronized (this) {
            calls++;
        }
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new GitHubException(GitHubException.Kind.UPSTREAM, "GitHub 请求超时（" + timeout.toSeconds() + " 秒）");
        } catch (IOException e) {
            throw new GitHubException(GitHubException.Kind.UPSTREAM, "连不上 GitHub：" + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitHubException(GitHubException.Kind.UPSTREAM, "GitHub 请求被中断");
        }
        int status = response.statusCode();
        if (status == 200) {
            try {
                return JSON.readTree(response.body());
            } catch (JacksonException e) {
                throw new GitHubException(GitHubException.Kind.UPSTREAM, "GitHub 返回的不是 JSON");
            }
        }
        Optional<String> remaining = response.headers().firstValue("x-ratelimit-remaining");
        Optional<String> retryAfter = response.headers().firstValue("retry-after");
        if ((status == 403 || status == 429) && (remaining.filter("0"::equals).isPresent() || retryAfter.isPresent())) {
            throw new GitHubException(GitHubException.Kind.RATE_LIMITED, rateLimitMessage(response));
        }
        throw switch (status) {
            case 404 -> new GitHubException(GitHubException.Kind.NOT_FOUND, notFoundMessage);
            case 409 -> new GitHubException(GitHubException.Kind.EMPTY, "仓库是空的（GitHub：" + message(response) + "）");
            case 301, 302, 307 -> new GitHubException(GitHubException.Kind.UPSTREAM, "仓库已经改名或移动，请用新的地址");
            default -> new GitHubException(GitHubException.Kind.UPSTREAM,
                    "GitHub 返回 " + status + "：" + message(response));
        };
    }

    private static String rateLimitMessage(HttpResponse<String> response) {
        Optional<String> reset = response.headers().firstValue("x-ratelimit-reset");
        Optional<String> retryAfter = response.headers().firstValue("retry-after");
        String when;
        try {
            when = reset.map(value -> "，" + Timestamps.format(OffsetDateTime.ofInstant(
                            Instant.ofEpochSecond(Long.parseLong(value)), ZoneId.systemDefault())) + " 重置")
                    .orElseGet(() -> retryAfter.map(value -> "，" + value + " 秒后再试").orElse(""));
        } catch (NumberFormatException e) {
            when = "";
        }
        return "GitHub 接口额度用完了（未认证每小时 60 次）" + when + "；不会自动重试";
    }

    /** GitHub 错误返回里的 {@code message}（GitHub 自己的说明，不是文件内容）。 */
    private static String message(HttpResponse<String> response) {
        try {
            String message = JSON.readTree(response.body()).path("message").asString("");
            return message.length() > 200 ? message.substring(0, 200) : message;
        } catch (JacksonException e) {
            return "（没有说明）";
        }
    }

    private static String notFound(String owner, String repo) {
        return "仓库不存在或不是公开的：" + owner + "/" + repo;
    }

    /** 路径按段编码，保留 /。 */
    private static String path(String value) {
        List<String> parts = new ArrayList<>();
        for (String part : value.split("/", -1)) {
            parts.add(URLEncoder.encode(part, UTF_8).replace("+", "%20"));
        }
        return String.join("/", parts);
    }
}
