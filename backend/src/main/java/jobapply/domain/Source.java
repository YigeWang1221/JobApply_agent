package jobapply.domain;

import java.util.Map;

/**
 * 来源登记（{@code base/sources.md}）的一行：一个本地目录或一个 GitHub 公开仓库。只登记、只读，不复制进 storage。
 *
 * @param location 本地目录的绝对路径，或者 {@code owner/repo}
 * @param version  本地目录是 {@code files:<文件清单哈希>}，GitHub 是读取时的 commit SHA；还没扫描时为空字符串
 * @param extra    未识别的列（列名 → 值）
 */
public record Source(String key, Type type, String location, String version, String registeredAt,
                     String lastScannedAt, String fileCount, String description, Map<String, String> extra) {

    public enum Type {
        DIR, GITHUB;

        public String code() {
            return name().toLowerCase();
        }

        public static Type of(String code) {
            for (Type type : values()) {
                if (type.code().equals(code == null ? "" : code.strip().toLowerCase())) {
                    return type;
                }
            }
            throw new IllegalArgumentException("来源类型只能是 dir 或 github：" + code);
        }
    }

    public Source {
        Ids.requireSourceKey(key);
        if (type == null || location == null || location.isBlank()) {
            throw new IllegalArgumentException("来源必须有类型和位置");
        }
        version = version == null ? "" : version;
        lastScannedAt = lastScannedAt == null ? "" : lastScannedAt;
        fileCount = fileCount == null ? "" : fileCount;
        description = description == null ? "" : description;
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }

    public Source scanned(String newVersion, String scannedAt, int files) {
        return new Source(key, type, location, newVersion, registeredAt, scannedAt, String.valueOf(files), description,
                extra);
    }
}
