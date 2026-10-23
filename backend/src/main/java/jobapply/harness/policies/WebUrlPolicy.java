package jobapply.harness.policies;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 搜索结果抓取和浏览器打开之前的网址检查（workflow.md「已确认：分层 Web 工具 · 安全检查」），照 Hermes 的做法
 * （用户确认，2026-10-23）：
 * <ul>
 *   <li>只允许 http / https；</li>
 *   <li>带疑似凭证的网址拒绝：查询参数里像密钥、令牌的，和 {@code user:password@host} 这种带账号密码的（不把凭证发给第三方）；</li>
 *   <li>SSRF 防护，默认开：拦截内网、回环、云元数据地址，DNS 解析失败也拦截；</li>
 *   <li>域名黑名单默认关、没有预设，由用户在 {@code jobapply.web.blocklist} 里自己配；子域名也算。</li>
 * </ul>
 * 每次重定向之后要再查一次。不允许时抛出 {@link ToolNotAllowedException}，原因交给模型。
 */
public final class WebUrlPolicy {

    private static final Pattern SECRET = Pattern.compile(
            "(?i)[?&](api[_-]?key|access[_-]?token|token|secret|password|passwd|sig|signature|key)=");

    private WebUrlPolicy() {
    }

    /** 检查通过时返回解析好的网址。不查黑名单。 */
    public static URI check(String url) {
        return check(url, List.of());
    }

    /**
     * @param blocklist 用户配置的域名黑名单（已经按开关取好）；空的时候不拦任何站点
     */
    public static URI check(String url, List<String> blocklist) {
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url.strip());
        } catch (IllegalArgumentException e) {
            throw new ToolNotAllowedException("网址格式不对：" + url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new ToolNotAllowedException("只能打开 http / https 网址");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ToolNotAllowedException("网址没有主机名：" + url);
        }
        if (uri.getRawUserInfo() != null) {
            throw new ToolNotAllowedException("网址里带着用户名或密码（user:password@host），不打开");
        }
        if (SECRET.matcher(uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()).find()) {
            throw new ToolNotAllowedException("网址里像是带着密钥或令牌，不打开");
        }
        String lower = host.toLowerCase(Locale.ROOT);
        for (String blocked : blocklist) {
            String domain = blocked.strip().toLowerCase(Locale.ROOT);
            if (!domain.isEmpty() && (lower.equals(domain) || lower.endsWith("." + domain))) {
                throw new ToolNotAllowedException(host + " 在用户配置的黑名单里（jobapply.web.blocklist），换一个来源");
            }
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new ToolNotAllowedException("解析不了主机名：" + host);
        }
        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                    || address.isAnyLocalAddress() || address.isMulticastAddress()
                    || address.getHostAddress().startsWith("169.254.") || address.getHostAddress().startsWith("fc")
                    || address.getHostAddress().startsWith("fd")) {
                throw new ToolNotAllowedException("不访问内网、本机或云元数据地址：" + host);
            }
        }
        return uri;
    }
}
