package jobapply.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code jobapply.web.*}：网址检查里用户自己配的部分（照 Hermes 的 {@code website_blocklist}）。黑名单默认关、没有预设
 * 站点；打开后按域名拦，子域名也算，重定向后再查一次。
 */
@ConfigurationProperties("jobapply.web")
public record WebProperties(Blocklist blocklist) {

    public record Blocklist(boolean enabled, List<String> domains) {

        public Blocklist {
            domains = domains == null ? List.of() : List.copyOf(domains);
        }
    }

    public WebProperties {
        blocklist = blocklist == null ? new Blocklist(false, List.of()) : blocklist;
    }

    /** 生效的黑名单：没打开时为空。 */
    public List<String> activeBlocklist() {
        return blocklist.enabled() ? blocklist.domains() : List.of();
    }
}
