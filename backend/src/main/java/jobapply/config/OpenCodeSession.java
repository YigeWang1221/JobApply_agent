package jobapply.config;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import okhttp3.Interceptor;
import okhttp3.Request;

/**
 * 发往 OpenCode（{@code opencode.ai} 和它的子域名）的模型请求自动带上 {@code x-opencode-session}（C025-fix）：OpenCode Go
 * 没有这个头时返回 400「Request is missing x-opencode-session」。做法照 DSH 插件（beihzb/dsh-opencode-session-header：
 * AsyncLocalStorage 存会话 id + fetch 中间件按 host 补头）：这里用 {@link ScopedValue} 存这次对话的 id，
 * OkHttp 拦截器按 host 补头。
 * <p>
 * 同步调用的拦截器在调模型的线程上跑，SDK 的重试也在这个线程上，所以同一个对话每一轮、每次重试都是同一个值；
 * {@link ScopedValue} 不会传给线程池里别的线程，并行的 Worker 之间不会串。没有对话（测试连接、拉模型列表、导入时的抽取）
 * 用 {@link #DEFAULT}。用户在自定义请求头里写了同名的头时用用户的值。
 */
public final class OpenCodeSession {

    public static final String HEADER = "x-opencode-session";
    static final String DEFAULT = "jobapply-default";

    private static final ScopedValue<String> CONVERSATION = ScopedValue.newInstance();

    /** 发往 OpenCode 的请求没有这个头时补上；对别的 host 什么都不做。 */
    static final Interceptor INTERCEPTOR = chain -> {
        Request request = chain.request();
        if (!appliesTo(request.url().host()) || request.header(HEADER) != null) {
            return chain.proceed(request);
        }
        return chain.proceed(request.newBuilder().header(HEADER, current()).build());
    };

    private OpenCodeSession() {
    }

    /** 在一次对话里跑 {@code task}：其中发往 OpenCode 的模型请求都带 {@code conversationId}。 */
    public static void run(String conversationId, Runnable task) {
        ScopedValue.where(CONVERSATION, conversationId).run(task);
    }

    /** 同 {@link #run}，带返回值。 */
    public static <T> T call(String conversationId, Supplier<T> task) {
        return ScopedValue.where(CONVERSATION, conversationId).call(task::get);
    }

    static String current() {
        return CONVERSATION.isBound() ? CONVERSATION.get() : DEFAULT;
    }

    static boolean appliesTo(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("opencode.ai") || h.endsWith(".opencode.ai");
    }

    /** 不经过 OkHttp 的请求（拉模型列表）：发往 OpenCode 并且用户没写同名头时，返回补上这个头的一份。 */
    static Map<String, String> withHeader(String host, Map<String, String> headers) {
        if (!appliesTo(host) || headers.keySet().stream().anyMatch(HEADER::equalsIgnoreCase)) {
            return headers;
        }
        Map<String, String> added = new LinkedHashMap<>(headers);
        added.put(HEADER, current());
        return added;
    }
}
