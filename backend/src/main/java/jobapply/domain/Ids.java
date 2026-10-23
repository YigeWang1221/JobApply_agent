package jobapply.domain;

import java.util.regex.Pattern;

/**
 * project / job / session id 与原件键的格式。id 会拼进文件路径，所以只允许这些格式，防止 {@code ../} 之类越出目录。
 */
public final class Ids {

    private static final Pattern PROJECT = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final Pattern JOB = Pattern.compile("\\d{4}-\\d{2}-\\d{2}-\\d{3,}");
    private static final Pattern SESSION = Pattern.compile("s-\\d{4}-\\d{2}-\\d{2}-\\d{3,}");
    private static final Pattern ASSET = Pattern.compile("a-\\d{4}-\\d{2}-\\d{2}-\\d{3,}");

    private Ids() {
    }

    public static boolean isProjectId(String value) {
        return value != null && PROJECT.matcher(value).matches();
    }

    public static boolean isJobId(String value) {
        return value != null && JOB.matcher(value).matches();
    }

    public static boolean isSessionId(String value) {
        return value != null && SESSION.matcher(value).matches();
    }

    /** {@code base/assets.md} 的键：{@code a-YYYY-MM-DD-NNN}。 */
    public static boolean isAssetKey(String value) {
        return value != null && ASSET.matcher(value).matches();
    }

    public static String requireProjectId(String value) {
        if (!isProjectId(value)) {
            throw new IllegalArgumentException("非法的 project id（只允许小写字母、数字和 -）：" + value);
        }
        return value;
    }

    public static String requireJobId(String value) {
        if (!isJobId(value)) {
            throw new IllegalArgumentException("非法的 job id（应为 YYYY-MM-DD-NNN）：" + value);
        }
        return value;
    }

    public static String requireSessionId(String value) {
        if (!isSessionId(value)) {
            throw new IllegalArgumentException("非法的 session id（应为 s-YYYY-MM-DD-NNN）：" + value);
        }
        return value;
    }
}
