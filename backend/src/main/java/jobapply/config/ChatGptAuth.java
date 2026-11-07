package jobapply.config;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import jobapply.persistence.ChatGptTokenStore;
import jobapply.persistence.ChatGptTokenStore.Registration;
import jobapply.persistence.ChatGptTokenStore.Tokens;
import jobapply.persistence.StorageLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 「用 ChatGPT 登录」（C028，官方 SIWC 开源 / 本机应用流程，preview）：浏览器授权 + 本机回调、换令牌、校验 id_token、
 * 刷新、退出。只有一个 ChatGPT 账号，所以全局只有一份登录。
 * <p>
 * 令牌只在 {@link ChatGptTokenStore} 的 600 文件和这个对象的内存里，不写日志、不返回给页面；异常信息里也不带令牌。
 */
@Component
public class ChatGptAuth implements ChatGptAccessTokens {

    private static final Logger log = LoggerFactory.getLogger(ChatGptAuth.class);

    static final String AUTHORIZE = ChatGptIdToken.ISSUER + "/api/accounts/authorize";
    static final String TOKEN = ChatGptIdToken.ISSUER + "/api/accounts/oauth/token";
    static final String DISCOVERY = ChatGptIdToken.ISSUER + "/.well-known/openid-configuration";
    public static final String RESOURCE = "https://api.openai.com/v1";
    static final String SCOPE = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
    static final String PLAN_SCOPE = "chatgpt.tokens.use.direct";
    static final String FIRST_CLIENT = "dynamic_agent_client";
    static final String AGENT_NAME = "JobApply";
    static final Duration CALLBACK_TIMEOUT = Duration.ofMinutes(5);
    static final long REFRESH_MARGIN_SECONDS = 120;
    static final String RELOGIN = "ChatGPT 登录已失效，请在模型设置里重新登录";
    static final String NOT_SIGNED_IN = "还没有用 ChatGPT 登录，请在模型设置里点 Continue with ChatGPT";
    private static final Set<String> SESSION_GONE = Set.of("invalid_grant", "refresh_token_reused");
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 给页面轮询的状态：state 是 idle / pending / signed_in / error。 */
    public record Status(String state, String email, String error) {
    }

    /** 一次正在等回调的登录。 */
    private static final class Attempt {
        final HttpServer server;
        final String state;
        final String nonce;
        final String verifier;
        final String redirectUri;
        /** 发出去的已签发 client_id；第一次注册时为 null。 */
        final String clientId;
        ScheduledFuture<?> timeout;
        boolean consumed;

        Attempt(HttpServer server, String state, String nonce, String verifier, String redirectUri, String clientId) {
            this.server = server;
            this.state = state;
            this.nonce = nonce;
            this.verifier = verifier;
            this.redirectUri = redirectUri;
            this.clientId = clientId;
        }
    }

    /** 登录没成功，信息给用户看。 */
    private static final class SignInFailed extends Exception {
        SignInFailed(String message) {
            super(message);
        }
    }

    private final ChatGptTokenStore store;
    private final HttpClient http;
    private final Clock clock;
    private final ScheduledExecutorService timer;
    private final Object refreshLock = new Object();

    /** 内存里的当前登录；{@link #loaded} 之前从文件读。 */
    private volatile Tokens cached;
    private volatile boolean loaded;
    private volatile JsonNode discovery;
    private volatile JsonNode jwks;

    // 下面三个由 this 保护
    private Attempt attempt;
    private String lastError;
    /** 退出时吊销失败之类的提示：状态是 idle，但要告诉用户。 */
    private String notice;

    public ChatGptAuth(StorageLayout layout) {
        this.store = new ChatGptTokenStore(layout);
        this.http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.clock = Clock.systemUTC();
        this.timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "chatgpt-signin");
            thread.setDaemon(true);
            return thread;
        });
    }

    // ---------------------------------------------------------------- 登录

    /** 开始一次登录：起本机回调监听，返回授权 URL。之前没完成的那次作废。 */
    public synchronized String startSignIn() {
        cancelPending();
        lastError = null;
        notice = null;
        Registration registration = store.registration();
        String verifier = random(48);
        String state = random(24);
        String nonce = random(24);
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException("启动本机登录回调监听失败：" + e.getMessage(), e);
        }
        String redirectUri = "http://127.0.0.1:" + server.getAddress().getPort() + "/auth/callback";
        Attempt next = new Attempt(server, state, nonce, verifier, redirectUri, registration.clientId());
        server.createContext("/auth/callback", exchange -> handleCallback(next, exchange));
        server.start();
        next.timeout = timer.schedule(() -> timedOut(next), CALLBACK_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        attempt = next;

        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", registration.clientId() == null ? FIRST_CLIENT : registration.clientId());
        if (registration.clientId() == null) {
            params.put("agent_name_hint", AGENT_NAME);
        }
        params.put("ext_agent_host_id", registration.hostId());
        params.put("redirect_uri", redirectUri);
        params.put("scope", SCOPE);
        params.put("resource", RESOURCE);
        params.put("state", state);
        params.put("nonce", nonce);
        params.put("code_challenge", challenge(verifier));
        params.put("code_challenge_method", "S256");
        log.info("ChatGPT 登录：等待浏览器回调（127.0.0.1:{}，{} 分钟超时）", server.getAddress().getPort(),
                CALLBACK_TIMEOUT.toMinutes());
        return AUTHORIZE + "?" + form(params);
    }

    /** 锁顺序只能是 refreshLock → this，所以先在锁外读令牌。 */
    public Status status() {
        Optional<Tokens> tokens = tokens();
        synchronized (this) {
            return status(tokens);
        }
    }

    private Status status(Optional<Tokens> tokens) {
        if (attempt != null) {
            return new Status("pending", null, null);
        }
        String email = tokens.map(Tokens::email).orElse(null);
        if (lastError != null) {
            return new Status("error", email, lastError);
        }
        if (tokens.isPresent()) {
            return new Status("signed_in", email, null);
        }
        return new Status("idle", null, notice);
    }

    public boolean signedIn() {
        return tokens().isPresent();
    }

    public Optional<String> email() {
        return tokens().map(Tokens::email);
    }

    /**
     * 退出：先吊销 refresh token（尽力而为），再删令牌文件。吊销失败也删本地，并在状态的 error 里提示用户
     * 可以到 ChatGPT 设置里断开 JobApply。注册信息（client_id、host id）保留，下次登录复用。
     */
    public void signOut() {
        synchronized (this) {
            cancelPending();
            lastError = null;
            notice = null;
        }
        String warning = null;
        synchronized (refreshLock) {
            Optional<Tokens> tokens = tokens();
            if (tokens.isPresent()) {
                try {
                    revoke(tokens.get());
                } catch (IOException | RuntimeException e) {
                    warning = "已在本机退出，但吊销 ChatGPT 授权失败（" + e.getClass().getSimpleName()
                            + "）；可以在 ChatGPT 的设置里断开 JobApply";
                    log.warn("ChatGPT 退出：吊销失败（{}），本地令牌照常删除", e.getClass().getSimpleName());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    warning = "已在本机退出，吊销 ChatGPT 授权被中断；可以在 ChatGPT 的设置里断开 JobApply";
                }
            }
            store.delete();
            cached = null;
            loaded = true;
        }
        synchronized (this) {
            notice = warning;
        }
    }

    private void handleCallback(Attempt current, HttpExchange exchange) {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod())) {
                page(exchange, 405, "只接受 GET");
                return;
            }
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            synchronized (this) {
                if (attempt != current) {
                    page(exchange, 410, "这次登录已经过期，请回到 JobApply 重新点 Continue with ChatGPT");
                    return;
                }
                // state 不对的回调直接忽略，不让它打断正在等的登录
                if (!current.state.equals(query.get("state"))) {
                    page(exchange, 400, "登录回调的 state 不对，已忽略");
                    return;
                }
                if (current.consumed) {
                    page(exchange, 409, "这次登录已经处理过了");
                    return;
                }
                current.consumed = true;
            }
            String error = null;
            try {
                complete(current, query);
            } catch (SignInFailed e) {
                error = e.getMessage();
            } catch (RuntimeException e) {
                error = "登录失败：" + e.getClass().getSimpleName();
                log.warn("ChatGPT 登录：处理回调出错（{}）", e.getClass().getSimpleName());
            }
            finish(current, error);
            page(exchange, error == null ? 200 : 400,
                    error == null ? "登录成功，可以关闭这个页面回到 JobApply" : error);
        } catch (IOException e) {
            log.warn("ChatGPT 登录：回调页面写回失败（{}）", e.getClass().getSimpleName());
        }
    }

    /** 处理回调：核对 client_id、换令牌、校验 id_token 和 scope、存令牌。 */
    private void complete(Attempt current, Map<String, String> query) throws SignInFailed {
        String error = query.get("error");
        if (error != null) {
            if ("access_denied".equals(error)) {
                throw new SignInFailed("已取消授权");
            }
            String description = query.getOrDefault("error_description", "");
            throw new SignInFailed("授权失败：" + error + (description.isBlank() ? "" : "（" + clip(description) + "）"));
        }
        String code = query.get("code");
        if (code == null || code.isBlank()) {
            throw new SignInFailed("回调里没有授权码，请重新登录");
        }
        String returned = query.get("client_id");
        String clientId;
        if (current.clientId == null) {
            if (returned == null || returned.isBlank()) {
                throw new SignInFailed("注册没有完成：回调里没有签发的 client_id，请重新登录");
            }
            store.saveClientId(returned);
            clientId = returned;
        } else {
            if (returned != null && !returned.equals(current.clientId)) {
                throw new SignInFailed("回调里的 client_id 和本机登记的不一致，已拒绝这次登录");
            }
            clientId = current.clientId;
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("client_id", clientId);
        form.put("code", code);
        form.put("code_verifier", current.verifier);
        form.put("redirect_uri", current.redirectUri);
        form.put("resource", RESOURCE);
        HttpResponse<String> response;
        try {
            response = post(TOKEN, form);
        } catch (IOException e) {
            throw new SignInFailed("连不上 auth.openai.com，换令牌失败");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SignInFailed("换令牌被中断");
        }
        JsonNode body = parse(response.body());
        if (response.statusCode() / 100 != 2) {
            String errorCode = errorCode(body);
            if ("invalid_grant".equals(errorCode)) {
                throw new SignInFailed("授权码已失效，请重新登录");
            }
            throw new SignInFailed("换令牌失败：HTTP " + response.statusCode()
                    + (errorCode == null ? "" : " " + errorCode));
        }
        String accessToken = text(body, "access_token");
        String refreshToken = text(body, "refresh_token");
        if (accessToken == null || refreshToken == null) {
            throw new SignInFailed("换令牌的返回里缺少 access_token 或 refresh_token");
        }
        String scope = Optional.ofNullable(text(body, "scope")).orElse(query.get("scope"));
        if (scope == null || !Set.of(scope.split("\\s+")).contains(PLAN_SCOPE)) {
            throw new SignInFailed("这次授权没有包含 ChatGPT 套餐额度（chatgpt.tokens.use.direct），请重新登录并同意授权");
        }
        ChatGptIdToken.Claims claims = verifyIdToken(text(body, "id_token"), clientId, current.nonce);
        long expiresIn = body.path("expires_in").asLong(3600);
        Tokens tokens = new Tokens(clientId, accessToken, refreshToken, clock.instant().getEpochSecond() + expiresIn,
                scope, claims.email(), claims.subject());
        synchronized (refreshLock) {
            store.write(tokens);
            cached = tokens;
            loaded = true;
        }
        log.info("ChatGPT 登录成功");
    }

    private ChatGptIdToken.Claims verifyIdToken(String idToken, String clientId, String nonce) throws SignInFailed {
        try {
            try {
                return ChatGptIdToken.verify(idToken, jwks(false), clientId, nonce, clock.instant());
            } catch (ChatGptIdToken.UnknownKey e) {
                // 密钥轮换了：重新取一次 JWKS
                return ChatGptIdToken.verify(idToken, jwks(true), clientId, nonce, clock.instant());
            }
        } catch (ChatGptIdToken.Invalid | ChatGptIdToken.UnknownKey e) {
            throw new SignInFailed("id_token 校验没通过：" + e.getMessage());
        } catch (IOException e) {
            throw new SignInFailed("取 OpenAI 的签名公钥失败");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SignInFailed("取签名公钥被中断");
        }
    }

    private synchronized void finish(Attempt current, String error) {
        if (attempt != current) {
            return;
        }
        attempt = null;
        lastError = error;
        stop(current);
        if (error != null) {
            log.info("ChatGPT 登录没有完成：{}", error);
        }
    }

    private synchronized void timedOut(Attempt current) {
        if (attempt != current || current.consumed) {
            return;
        }
        attempt = null;
        lastError = CALLBACK_TIMEOUT.toMinutes() + " 分钟内没有完成登录，已停止等待；可以重新点 Continue with ChatGPT";
        stop(current);
    }

    private void cancelPending() {
        if (attempt != null) {
            stop(attempt);
            attempt = null;
        }
    }

    /** 关监听放到别的线程：回调线程自己还要把页面写回去。 */
    private void stop(Attempt current) {
        if (current.timeout != null) {
            current.timeout.cancel(false);
        }
        timer.schedule(() -> current.server.stop(1), 200, TimeUnit.MILLISECONDS);
    }

    // ---------------------------------------------------------------- 令牌

    @Override
    public String current() {
        Tokens tokens = tokens().orElseThrow(() -> new NonTransientAiException(NOT_SIGNED_IN));
        if (fresh(tokens)) {
            return tokens.accessToken();
        }
        synchronized (refreshLock) {
            tokens = tokens().orElseThrow(() -> new NonTransientAiException(NOT_SIGNED_IN));
            if (fresh(tokens)) {
                return tokens.accessToken();
            }
            return refresh(tokens).accessToken();
        }
    }

    @Override
    public void invalidate() {
        synchronized (refreshLock) {
            tokens().ifPresent(t -> cached = new Tokens(t.clientId(), t.accessToken(), t.refreshToken(), 0,
                    t.scope(), t.email(), t.subject()));
        }
    }

    private boolean fresh(Tokens tokens) {
        return tokens.accessToken() != null
                && tokens.expiresAt() - clock.instant().getEpochSecond() > REFRESH_MARGIN_SECONDS;
    }

    /** 调用方持有 refreshLock。refresh token 每次都换新，新的一份原子写回。 */
    private Tokens refresh(Tokens old) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("client_id", old.clientId());
        form.put("refresh_token", old.refreshToken());
        form.put("resource", RESOURCE);
        HttpResponse<String> response;
        try {
            response = post(TOKEN, form);
        } catch (IOException e) {
            // 网络问题：让调用方按普通错误重试
            throw new IllegalStateException("刷新 ChatGPT 令牌时连不上 auth.openai.com（" + e.getClass().getSimpleName()
                    + "）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("刷新 ChatGPT 令牌被中断");
        }
        JsonNode body = parse(response.body());
        int status = response.statusCode();
        if (status / 100 != 2) {
            String code = errorCode(body);
            if (code != null && SESSION_GONE.contains(code)) {
                store.delete();
                cached = null;
                loaded = true;
                synchronized (this) {
                    lastError = RELOGIN;
                }
                log.warn("ChatGPT 刷新令牌被拒（{}），已清掉本地登录", code);
                throw new NonTransientAiException(RELOGIN);
            }
            if (status >= 500 || status == 429) {
                throw new IllegalStateException("刷新 ChatGPT 令牌失败：HTTP " + status);
            }
            throw new NonTransientAiException("刷新 ChatGPT 令牌失败：HTTP " + status + (code == null ? "" : " " + code)
                    + "；可以在模型设置里重新登录");
        }
        String accessToken = text(body, "access_token");
        if (accessToken == null) {
            throw new IllegalStateException("刷新 ChatGPT 令牌的返回里没有 access_token");
        }
        Tokens next = new Tokens(old.clientId(), accessToken,
                Optional.ofNullable(text(body, "refresh_token")).orElse(old.refreshToken()),
                clock.instant().getEpochSecond() + body.path("expires_in").asLong(3600),
                Optional.ofNullable(text(body, "scope")).orElse(old.scope()), old.email(), old.subject());
        store.write(next);
        cached = next;
        loaded = true;
        return next;
    }

    private Optional<Tokens> tokens() {
        if (!loaded) {
            synchronized (refreshLock) {
                if (!loaded) {
                    cached = store.read().orElse(null);
                    loaded = true;
                }
            }
        }
        return Optional.ofNullable(cached);
    }

    private void revoke(Tokens tokens) throws IOException, InterruptedException {
        String endpoint = text(discovery(), "revocation_endpoint");
        if (endpoint == null) {
            throw new IOException("openid-configuration 里没有 revocation_endpoint");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("token", tokens.refreshToken());
        form.put("token_type_hint", "refresh_token");
        form.put("client_id", tokens.clientId());
        HttpResponse<String> response = post(endpoint, form);
        if (response.statusCode() / 100 != 2) {
            throw new IOException("吊销返回 HTTP " + response.statusCode());
        }
    }

    // ---------------------------------------------------------------- HTTP

    private JsonNode discovery() throws IOException, InterruptedException {
        JsonNode current = discovery;
        if (current == null) {
            current = getJson(DISCOVERY);
            discovery = current;
        }
        return current;
    }

    private JsonNode jwks(boolean reload) throws IOException, InterruptedException {
        JsonNode current = jwks;
        if (current == null || reload) {
            String uri = text(discovery(), "jwks_uri");
            if (uri == null) {
                throw new IOException("openid-configuration 里没有 jwks_uri");
            }
            current = getJson(uri);
            jwks = current;
        }
        return current;
    }

    private JsonNode getJson(String uri) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(uri)).timeout(HTTP_TIMEOUT)
                .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException(URI.create(uri).getHost() + " 返回 HTTP " + response.statusCode());
        }
        JsonNode node = parse(response.body());
        if (!node.isObject()) {
            throw new IOException(URI.create(uri).getHost() + " 返回的不是 JSON");
        }
        return node;
    }

    private HttpResponse<String> post(String uri, Map<String, String> form) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(uri)).timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form(form))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? JSON.createObjectNode() : JSON.readTree(body);
        } catch (JacksonException e) {
            return JSON.createObjectNode();
        }
    }

    /** OAuth 的 {@code error} 可能是字符串，也可能是带 {@code code} 的对象。 */
    private static String errorCode(JsonNode body) {
        JsonNode error = body.path("error");
        if (error.isString()) {
            return error.asString();
        }
        String code = text(error, "code");
        return code != null ? code : text(error, "type");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }

    private static String form(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), UTF_8) + "=" + URLEncoder.encode(e.getValue(), UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), UTF_8);
            out.putIfAbsent(key, value);
        }
        return out;
    }

    private static void page(HttpExchange exchange, int status, String message) throws IOException {
        String html = "<!doctype html><html lang=\"zh\"><head><meta charset=\"utf-8\"><title>JobApply</title>"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head>"
                + "<body style=\"font-family:system-ui,sans-serif;margin:3rem 1rem;text-align:center\"><p>"
                + escape(message) + "</p></body></html>";
        byte[] bytes = html.getBytes(UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String clip(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
    }

    private static String random(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    private static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
