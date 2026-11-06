package jobapply.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * {@code storage/settings/checks.yaml}：多少天查看一次投递状态（C026，全局，用户在设置页或 Dashboard 输入）。
 * Project 的 {@code preferences.md} 里 {@code check_every_days} 有值时它优先。没有文件时用
 * {@link JobLedger#DEFAULT_CHECK_DAYS}。不含密钥。原子写入。
 */
public final class CheckSettingsFile {

    public static final int MIN_DAYS = 1;
    public static final int MAX_DAYS = 90;
    static final String KEY = "check_every_days";

    private final Path file;

    public CheckSettingsFile(StorageLayout layout) {
        this.file = layout.checksYaml();
    }

    /** 文件里的天数；没有文件、内容不对时为空（内容不对时调用方用默认值）。 */
    public Optional<Integer> read() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            Object loaded = FrontmatterFile.yaml().load(AtomicFiles.read(file));
            if (loaded instanceof Map<?, ?> map && map.get(KEY) instanceof Integer days && valid(days)) {
                return Optional.of(days);
            }
        } catch (YAMLException e) {
            // 写坏了就当没有
        }
        return Optional.empty();
    }

    public void write(int days) {
        require(days);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put(KEY, days);
        AtomicFiles.write(file, "# 多少天查看一次投递状态（设置页「运行」或 Dashboard 写入）。Project 的 preferences.md 可以单独设\n"
                + FrontmatterFile.yaml().dump(root));
    }

    public static boolean valid(int days) {
        return days >= MIN_DAYS && days <= MAX_DAYS;
    }

    public static void require(int days) {
        if (!valid(days)) {
            throw new IllegalArgumentException("查看间隔要在 " + MIN_DAYS + " 到 " + MAX_DAYS + " 天之间");
        }
    }
}
