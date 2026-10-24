package jobapply.domain;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * C 级资料的占位符 {@code {KEY}}（C014-D）：资料里只存占位符，真实值在用户自己维护的 {@code storage/settings/personal.env}。
 * 键名和 {@code .env} 的变量名一样：大写字母、数字、下划线，不以数字开头。
 */
public final class Placeholder {

    private static final Pattern KEY = Pattern.compile("[A-Z_][A-Z0-9_]{0,127}");
    private static final Pattern EXACT = Pattern.compile("\\{(" + KEY.pattern() + ")}");

    private Placeholder() {
    }

    public static boolean isValidKey(String key) {
        return key != null && KEY.matcher(key).matches();
    }

    /** 值正好是一个占位符（首尾空格不算）时返回键名。 */
    public static Optional<String> keyOf(String value) {
        if (value == null) {
            return Optional.empty();
        }
        Matcher matcher = EXACT.matcher(value.strip());
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    public static boolean is(String value) {
        return keyOf(value).isPresent();
    }

    public static String of(String key) {
        if (!isValidKey(key)) {
            throw new IllegalArgumentException("键名不合法：只允许大写字母、数字和下划线，不以数字开头");
        }
        return "{" + key + "}";
    }
}
