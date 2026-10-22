package jobapply.tools;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 判断当前页面是不是登录页、SSO 或安全验证页。只看快照里可见的文字和页面 URL，不读 Cookie。
 * 规则按顺序检查，命中一条就停。规则可能误判，所以流程里允许用户 override。
 */
public final class LoginPageDetector {

    public enum Kind {
        /** 验证码、滑块、人机验证。 */
        CHALLENGE,
        /** 登录页或 SSO。 */
        LOGIN,
        NONE
    }

    public record Detection(Kind kind, String rule) {

        public boolean blocksUser() {
            return kind != Kind.NONE;
        }
    }

    private static final List<String> CHALLENGE_WORDS = List.of(
            "验证码", "安全验证", "滑块", "人机验证", "captcha", "verify you are human", "unusual traffic");

    private static final Pattern PASSWORD_BOX = Pattern.compile(
            "textbox \"[^\"\\n]*(密码|password|passcode)[^\"\\n]*\"", Pattern.CASE_INSENSITIVE);

    private static final List<String> LOGIN_URL_WORDS = List.of(
            "login", "signin", "sign-in", "sso", "passport", "oauth", "auth", "cas");

    private static final Pattern LOGIN_ACTION = Pattern.compile(
            "(button|link) \"[^\"\\n]*(登录|登入|sign in|log in|continue with)[^\"\\n]*\"", Pattern.CASE_INSENSITIVE);

    private LoginPageDetector() {
    }

    public static Detection detect(PageSnapshot page) {
        String text = page.visibleText();
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : CHALLENGE_WORDS) {
            if (lower.contains(word)) {
                return new Detection(Kind.CHALLENGE, "页面出现「" + word + "」");
            }
        }
        if (PASSWORD_BOX.matcher(text).find()) {
            return new Detection(Kind.LOGIN, "页面有密码输入框");
        }
        String loginWord = loginWordInUrl(page.url());
        if (loginWord != null && LOGIN_ACTION.matcher(text).find()) {
            return new Detection(Kind.LOGIN, "URL 含「" + loginWord + "」且页面有登录按钮");
        }
        return new Detection(Kind.NONE, "未识别到登录页");
    }

    private static String loginWordInUrl(String url) {
        String hostAndPath;
        try {
            URI uri = new URI(url);
            hostAndPath = (uri.getHost() == null ? "" : uri.getHost()) + (uri.getPath() == null ? "" : uri.getPath());
        } catch (Exception e) {
            hostAndPath = url;
        }
        String lower = hostAndPath.toLowerCase(Locale.ROOT);
        for (String word : LOGIN_URL_WORDS) {
            if (Pattern.compile("(^|[^a-z])" + Pattern.quote(word) + "([^a-z]|$)").matcher(lower).find()) {
                return word;
            }
        }
        return null;
    }
}
