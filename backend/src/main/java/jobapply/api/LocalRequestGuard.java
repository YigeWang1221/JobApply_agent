package jobapply.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 浏览器里的网页也能向 127.0.0.1 发请求。为了不让网页冒充用户发「好了」：
 * 带 {@code Origin} 请求头的请求一律 403（curl 不带这个头；前端在第 7 段再改成允许名单）；
 * 写请求必须是 {@code application/json}，网页就不能用简单表单绕过预检。
 */
@Component
class LocalRequestGuard extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getHeader("Origin") != null) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "不接受来自网页的请求（带 Origin 请求头）");
            return;
        }
        if (isWrite(request.getMethod()) && !isJson(request.getContentType())) {
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

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getOutputStream().write(("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8));
    }
}
