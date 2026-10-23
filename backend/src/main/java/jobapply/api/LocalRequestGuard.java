package jobapply.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import jobapply.config.ApiProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 浏览器里的网页也能向 127.0.0.1 发请求。为了不让别的网页冒充用户（例如发「好了」、改模型设置）：
 * 带 {@code Origin} 请求头的请求只放行 {@code jobapply.api.allowed-origins} 里的本机前端地址，其它一律 403；
 * 不带的（curl）照常放行。写请求必须是 {@code application/json}，网页就不能用简单表单绕过预检；
 * 唯一的例外是上传原件（{@code POST /api/assets}），允许 {@code multipart/form-data}。
 */
@Component
class LocalRequestGuard extends OncePerRequestFilter {

    static final String UPLOAD_PATH = "/api/assets";

    private final Set<String> allowedOrigins;

    LocalRequestGuard(ApiProperties properties) {
        this.allowedOrigins = Set.copyOf(properties.allowedOrigins());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (origin != null && !allowedOrigins.contains(origin)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "不接受来自这个网页的请求（Origin 不在允许名单里）");
            return;
        }
        if (isWrite(request.getMethod()) && !isJson(request.getContentType()) && !isUpload(request)) {
            reject(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "写接口只接受 Content-Type: application/json");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isWrite(String method) {
        return !("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method));
    }

    private static boolean isJson(String contentType) {
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith(MediaType.APPLICATION_JSON_VALUE);
    }

    private static boolean isUpload(HttpServletRequest request) {
        String contentType = request.getContentType();
        return "POST".equals(request.getMethod()) && UPLOAD_PATH.equals(request.getRequestURI())
                && contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith(MediaType.MULTIPART_FORM_DATA_VALUE);
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getOutputStream().write(("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8));
    }
}
