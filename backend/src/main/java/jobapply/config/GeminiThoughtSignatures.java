package jobapply.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 发往 Gemini OpenAI 兼容端点（{@code generativelanguage.googleapis.com}）的工具调用补上思考签名（C027-fix）。
 * Gemini 3 在每次带工具调用的响应里把签名放在 {@code choices[].message.tool_calls[].extra_content.google.thought_signature}，
 * 下一轮必须原样发回到同一个工具调用上，否则 400「Function call is missing a thought_signature」
 * （ai.google.dev/gemini-api/docs/thought-signatures，「Signatures for OpenAI compatibility」）。
 * Spring AI 只把它放进 {@code AssistantMessage} 的 metadata，而 JobApply 每一轮从自己的记录重建对话，签名就丢了。
 * <p>
 * 做法和 {@link OpenCodeSession} 一样，按 host 挂 OkHttp 拦截器：响应里记下「工具调用 id → 签名」（有上限的内存表）；
 * 请求里每条 assistant 消息的工具调用没有签名时，按 id 补上记下的值。整条消息一个签名都找不到（应用重启过、旧记录里的调用）
 * 时给第一个工具调用补官方文档给的占位值 {@link #DUMMY}（并行调用时 Gemini 本来也只在第一个上给签名）。
 * 对别的 host 什么都不做。只处理非流式响应（JobApply 只用 {@code call()}）。
 */
public final class GeminiThoughtSignatures {

    /** 官方文档 FAQ 给的占位签名，跳过校验。 */
    static final String DUMMY = "skip_thought_signature_validator";
    static final int MAX_ENTRIES = 4096;
    private static final long MAX_PEEK = 32L * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Map<String, String> SIGNATURES = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    static final Interceptor INTERCEPTOR = chain -> {
        Request request = chain.request();
        if (!appliesTo(request)) {
            return chain.proceed(request);
        }
        Response response = chain.proceed(withSignatures(request));
        remember(response);
        return response;
    };

    private GeminiThoughtSignatures() {
    }

    static boolean appliesTo(Request request) {
        String host = request.url().host();
        return host != null && host.toLowerCase(Locale.ROOT).equals("generativelanguage.googleapis.com")
                && request.url().encodedPath().endsWith("/chat/completions");
    }

    private static Request withSignatures(Request request) throws IOException {
        RequestBody body = request.body();
        if (body == null) {
            return request;
        }
        Buffer buffer = new Buffer();
        body.writeTo(buffer);
        byte[] bytes = buffer.readByteArray();
        MediaType type = body.contentType();
        String patched = patch(new String(bytes, StandardCharsets.UTF_8));
        RequestBody rebuilt = patched == null ? RequestBody.create(bytes, type) : RequestBody.create(patched, type);
        return request.newBuilder().method(request.method(), rebuilt).build();
    }

    /** 给请求体里缺签名的工具调用补签名；没有要改的返回 null，原样发。 */
    static String patch(String body) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (RuntimeException e) {
            return null;
        }
        boolean changed = false;
        for (JsonNode message : root.path("messages")) {
            JsonNode calls = message.path("tool_calls");
            if (!"assistant".equals(message.path("role").asString("")) || !calls.isArray() || calls.isEmpty()) {
                continue;
            }
            boolean signed = false;
            for (JsonNode call : calls) {
                if (!signature(call).isEmpty()) {
                    signed = true;
                    continue;
                }
                String known = lookup(call.path("id").asString(""));
                if (known != null && call instanceof ObjectNode node) {
                    put(node, known);
                    signed = true;
                    changed = true;
                }
            }
            if (!signed && calls.get(0) instanceof ObjectNode first) {
                put(first, DUMMY);
                changed = true;
            }
        }
        return changed ? JSON.writeValueAsString(root) : null;
    }

    /** 响应体里每个工具调用的签名按 id 记下；响应体原样留给 SDK 读。 */
    private static void remember(Response response) {
        MediaType type = response.body() == null ? null : response.body().contentType();
        if (!response.isSuccessful() || type == null || !"json".equalsIgnoreCase(type.subtype())) {
            return;
        }
        try {
            record(response.peekBody(MAX_PEEK).string());
        } catch (IOException | RuntimeException e) {
            // 记不下来不影响这次调用；下一轮按占位值发
        }
    }

    static void record(String body) {
        JsonNode root = JSON.readTree(body);
        for (JsonNode choice : root.path("choices")) {
            for (JsonNode call : choice.path("message").path("tool_calls")) {
                String id = call.path("id").asString("");
                String signature = signature(call);
                if (!id.isEmpty() && !signature.isEmpty()) {
                    synchronized (SIGNATURES) {
                        SIGNATURES.put(id, signature);
                    }
                }
            }
        }
    }

    static String lookup(String id) {
        if (id.isEmpty()) {
            return null;
        }
        synchronized (SIGNATURES) {
            return SIGNATURES.get(id);
        }
    }

    private static String signature(JsonNode call) {
        return call.path("extra_content").path("google").path("thought_signature").asString("");
    }

    private static void put(ObjectNode call, String signature) {
        JsonNode extra = call.path("extra_content");
        ObjectNode extraNode = extra instanceof ObjectNode o ? o : call.putObject("extra_content");
        JsonNode google = extraNode.path("google");
        ObjectNode googleNode = google instanceof ObjectNode o ? o : extraNode.putObject("google");
        googleNode.put("thought_signature", signature);
    }
}
