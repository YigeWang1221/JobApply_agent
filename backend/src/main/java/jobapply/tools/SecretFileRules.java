package jobapply.tools;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 扫描来源时的跳过规则，只看名字，不打开文件（C004-A-1「来源登记与扫描规则」）。本地目录和 GitHub 共用。
 * 名字匹配忽略大小写。
 */
public final class SecretFileRules {

    public static final String SECRET = "秘密文件，按名字跳过，没有打开";
    public static final String GENERATED = "生成目录或依赖目录，整个跳过";
    public static final String GIT = "git 内部数据，只读取提交摘要";

    private static final Set<String> SECRET_NAMES = Set.of(".env", ".npmrc", ".pypirc", ".netrc");
    // .env 结尾的也算（例如 C014-D 的 personal.env）
    private static final Set<String> SECRET_SUFFIXES = Set.of(".env", ".pem", ".key", ".p12", ".pfx", ".keystore", ".jks");
    private static final Set<String> SECRET_PREFIXES = Set.of(".env.", "id_rsa", "id_ed25519");
    private static final Set<String> SECRET_PARTS = Set.of("credentials", "secret");
    private static final Set<String> SKIPPED_DIRS = Set.of("node_modules", "dist", "build", "target", ".venv", "venv",
            "__pycache__", ".next", ".astro", ".wrangler", ".idea", ".vscode");

    private SecretFileRules() {
    }

    /** 文件名（最后一段）是不是秘密文件。 */
    public static boolean isSecret(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        return SECRET_NAMES.contains(name)
                || SECRET_SUFFIXES.stream().anyMatch(name::endsWith)
                || SECRET_PREFIXES.stream().anyMatch(name::startsWith)
                || SECRET_PARTS.stream().anyMatch(name::contains);
    }

    /** 目录名（最后一段）要整个跳过时返回原因。秘密名字的目录（例如 {@code secrets/}）也整个跳过。 */
    public static Optional<String> skippedDirectory(String dirName) {
        String name = dirName.toLowerCase(Locale.ROOT);
        if (name.equals(".git")) {
            return Optional.of(GIT);
        }
        if (SKIPPED_DIRS.contains(name)) {
            return Optional.of(GENERATED);
        }
        if (isSecret(name)) {
            return Optional.of(SECRET);
        }
        return Optional.empty();
    }

    /** 路径（用 / 分隔）里任何一段命中规则时返回原因：先看目录，再看文件名。GitHub 的文件列表用它。 */
    public static Optional<String> skippedPath(String path) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            Optional<String> reason = skippedDirectory(parts[i]);
            if (reason.isPresent()) {
                return reason;
            }
        }
        return isSecret(parts[parts.length - 1]) ? Optional.of(SECRET) : Optional.empty();
    }
}
