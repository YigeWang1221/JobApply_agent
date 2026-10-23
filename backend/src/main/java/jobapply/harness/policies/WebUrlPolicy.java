package jobapply.harness.policies;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 搜索结果抓取和浏览器打开之前的网址检查（workflow.md「已确认：分层 Web 工具 · 安全检查」）：只允许 http / https；
 * 拦截内网、回环、云元数据地址，DNS 解析失败也拦截；带疑似密钥的网址拒绝；不去条款不允许自动化的站点。
 * 每次重定向之后要再查一次。不允许时抛出 {@link ToolNotAllowedException}，原因交给模型。
 */
public final class WebUrlPolicy {

    /** 不让 Agent 自动访问的站点（有验证码，条款也不允许）：用户在这些地方看到的岗位，自己贴链接。 */
    static final List<String> BLOCKED_HOSTS = List.of("google.com", "linkedin.com", "zhipin.com", "liepin.com",
            "maimai.cn");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)[?&](api[_-]?key|access[_-]?token|token|secret|password|passwd|sig|signature|key)=");

    private WebUrlPolicy() {
    }

    /** 检查通过时返回解析好的网址。 */
    public static URI check(String url) {
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
        String lower = host.toLowerCase(Locale.ROOT);
        for (String blocked : BLOCKED_HOSTS) {
            if (lower.equals(blocked) || lower.endsWith("." + blocked)) {
                throw new ToolNotAllowedException(blocked + " 不允许自动访问；这里看到的岗位请用户自己贴链接");
            }
        }
        if (SECRET.matcher(uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()).find()) {
            throw new ToolNotAllowedException("网址里像是带着密钥或令牌，不打开");
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
