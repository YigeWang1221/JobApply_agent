package jobapply.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * {@code storage/settings/workers.yaml}：设置页改过的申请并发数（C012）。没有这个文件时用 {@code application.yml} 的
 * {@code jobapply.harness.application-workers} / {@code applications-per-site}。不含密钥。原子写入。
 */
public final class WorkerSettingsFile {

    /** 同时跑几个申请、同一个网站最多几个。 */
    public record Limits(int workers, int perSite) {
    }

    private final Path file;

    public WorkerSettingsFile(StorageLayout layout) {
        this.file = layout.workersYaml();
    }

    /** 文件里的值；没有文件时为空。内容不对时抛 {@link StorageFormatException}。 */
    public Optional<Limits> read() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        Object loaded;
        try {
            loaded = FrontmatterFile.yaml().load(AtomicFiles.read(file));
        } catch (YAMLException e) {
            throw new StorageFormatException(file.toString(), "不是合法的 YAML：" + e.getMessage());
        }
        if (!(loaded instanceof Map<?, ?> map)
                || !(map.get("application_workers") instanceof Integer workers)
                || !(map.get("applications_per_site") instanceof Integer perSite)) {
            throw new StorageFormatException(file.toString(), "需要整数 application_workers 和 applications_per_site");
        }
        return Optional.of(new Limits(workers, perSite));
    }

    public void write(Limits limits) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("application_workers", limits.workers());
        root.put("applications_per_site", limits.perSite());
        AtomicFiles.write(file, "# 申请并发数（设置页「同时申请」写入）。删掉这个文件就回到 application.yml 的值\n"
                + FrontmatterFile.yaml().dump(root));
    }

    public Path path() {
        return file;
    }
}
