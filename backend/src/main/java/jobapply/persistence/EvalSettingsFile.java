package jobapply.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * {@code storage/settings/eval.yaml}：设置页「评测」卡片的开关（C025）。没有这个文件时用 {@code application.yml} 的
 * {@code jobapply.eval.enabled}（默认关）。不含密钥。原子写入。
 */
public final class EvalSettingsFile {

    private final Path file;

    public EvalSettingsFile(StorageLayout layout) {
        this.file = layout.evalYaml();
    }

    /** 文件里的值；没有文件时为空。内容不对时抛 {@link StorageFormatException}。 */
    public Optional<Boolean> read() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        Object loaded;
        try {
            loaded = FrontmatterFile.yaml().load(AtomicFiles.read(file));
        } catch (YAMLException e) {
            throw new StorageFormatException(file.toString(), "不是合法的 YAML：" + e.getMessage());
        }
        if (!(loaded instanceof Map<?, ?> map) || !(map.get("enabled") instanceof Boolean enabled)) {
            throw new StorageFormatException(file.toString(), "需要布尔值 enabled");
        }
        return Optional.of(enabled);
    }

    public void write(boolean enabled) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("enabled", enabled);
        AtomicFiles.write(file, "# 评测模式（设置页「评测」写入）：拦截最终提交、每步存脱敏快照。删掉这个文件就回到 application.yml 的值\n"
                + FrontmatterFile.yaml().dump(root));
    }

    public Path path() {
        return file;
    }
}
