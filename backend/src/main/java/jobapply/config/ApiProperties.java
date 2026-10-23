package jobapply.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code jobapply.api.*}：本地 HTTP 接口。带 {@code Origin} 请求头的请求只放行这里列出的本机前端地址
 * （默认 {@code http://127.0.0.1:5173}、{@code http://localhost:5173}，在 {@code application.yml}）。
 */
@ConfigurationProperties("jobapply.api")
public record ApiProperties(List<String> allowedOrigins) {

    public ApiProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
