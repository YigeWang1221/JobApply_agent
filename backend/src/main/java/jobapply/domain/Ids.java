package jobapply.domain;

import java.util.regex.Pattern;

/**
 * project / job / session id、原件键、来源键、导入记录 id、论断 id、项目 slug 的格式。id 会拼进文件路径，所以只允许这些格式，防止 {@code ../} 之类越出目录。
 */
public final class Ids {

    private static final Pattern PROJECT = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final Pattern JOB = Pattern.compile("\\d{4}-\\d{2}-\\d{2}-\\d{3,}");
    private static final Pattern SESSION = Pattern.compile("s-\\d{4}-\\d{2}-\\d{2}-\\d{3,}");
    private static final Pattern ASSET = Pattern.compile("a-\\d{4}-\\d{2}-\\d{2}-\\d{3,}");
    private static final Pattern SOURCE = SESSION;
    private static final Pattern IMPORT_RUN = Pattern.compile("r-\\d{4}-\\d{2}-\\d{2}-\\d{3,}");
    private static final Pattern CLAIM = Pattern.compile("c-\\d{4,}");

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

    /** {@code base/sources.md} 的键：{@code s-YYYY-MM-DD-NNN}（和 session id 同一种写法，但不在同一个命名空间）。 */
    public static boolean isSourceKey(String value) {
        return value != null && SOURCE.matcher(value).matches();
    }

    /** 导入记录 {@code base/import-runs/<id>.json}：{@code r-YYYY-MM-DD-NNN}。 */
    public static boolean isImportRunId(String value) {
        return value != null && IMPORT_RUN.matcher(value).matches();
    }

    /** 论断台账的 id：{@code c-NNNN}，全局连续编号。 */
    public static boolean isClaimId(String value) {
        return value != null && CLAIM.matcher(value).matches();
    }

    /** 项目卡 {@code base/projects/<slug>.md}：小写字母、数字和 -（与 project id 相同）。 */
    public static boolean isProjectSlug(String value) {
        return isProjectId(value);
    }

    public static String requireSourceKey(String value) {
        if (!isSourceKey(value)) {
            throw new IllegalArgumentException("非法的来源键（应为 s-YYYY-MM-DD-NNN）：" + value);
        }
        return value;
    }

    public static String requireImportRunId(String value) {
        if (!isImportRunId(value)) {
            throw new IllegalArgumentException("非法的导入记录 id（应为 r-YYYY-MM-DD-NNN）：" + value);
        }
        return value;
    }

    public static String requireProjectSlug(String value) {
        if (!isProjectSlug(value)) {
            throw new IllegalArgumentException("非法的项目 slug（只允许小写字母、数字和 -）：" + value);
        }
        return value;
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
