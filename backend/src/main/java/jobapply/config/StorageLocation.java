package jobapply.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * storage 根目录从哪来（C017 第 3 节）。启动时按顺序取：命令行 {@code --jobapply.storage.root} / 环境变量
 * {@code JOBAPPLY_STORAGE_ROOT} → storage 外面的 {@code app.yaml}（{@code storageRoot}）→ {@code application.yml} 默认。
 * {@code jobapply.app.require-storage-choice} 为 true（macapp）且前两项都没有时，用 App Support 下的默认位置并报
 * {@code needsChoice}，等用户在界面上选；启动时不建、不写这个文件夹。换位置只写 {@code app.yaml}，重启后才生效。
 */
public record StorageLocation(Path root, String source, boolean needsChoice, Path defaultRoot, Path appConfig) {

    public static final String OVERRIDE = "override";
    public static final String APP_CONFIG = "app-config";
    public static final String DEFAULT = "default";

    static StorageLocation resolve(ConfigurableEnvironment env, StorageProperties properties) {
        Path supportDir = Path.of(System.getProperty("user.home"), "Library", "Application Support", "JobApply");
        String configured = System.getenv("JOBAPPLY_APP_CONFIG");
        Path appConfig = configured == null || configured.isBlank()
                ? supportDir.resolve("app.yaml") : Path.of(configured).toAbsolutePath().normalize();
        Path defaultRoot = supportDir.resolve("storage");
        if (overridden(env)) {
            return new StorageLocation(properties.rootPath(), OVERRIDE, false, defaultRoot, appConfig);
        }
        Path fromFile = readAppConfig(appConfig);
        if (fromFile != null) {
            return new StorageLocation(fromFile, APP_CONFIG, false, defaultRoot, appConfig);
        }
        if (env.getProperty("jobapply.app.require-storage-choice", Boolean.class, false)) {
            return new StorageLocation(defaultRoot, DEFAULT, true, defaultRoot, appConfig);
        }
        return new StorageLocation(properties.rootPath(), DEFAULT, false, defaultRoot, appConfig);
    }

    /** 把选好的根目录写进 {@code app.yaml}（会建上级目录）。不碰 storage 本身。 */
    public void save(Path newRoot) {
        try {
            Files.createDirectories(appConfig.getParent());
            String body = "# JobApply 的数据位置（设置页「数据位置」写入），重启后生效\n"
                    + new Yaml(blockStyle()).dump(Map.of("storageRoot", newRoot.toString()));
            Path temp = Files.createTempFile(appConfig.getParent(), "app", ".yaml.tmp");
            Files.writeString(temp, body);
            Files.move(temp, appConfig, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("写不了 " + appConfig + "：" + e.getMessage(), e);
        }
    }

    private static DumperOptions blockStyle() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return options;
    }

    /** 根目录是命令行、环境变量（或测试）给的，不是 application*.yml 里的默认值。 */
    private static boolean overridden(ConfigurableEnvironment env) {
        for (PropertySource<?> source : env.getPropertySources()) {
            // configurationProperties 是 Spring 把其它来源包在一起的视图，跳过它看真正的来源
            if (!"configurationProperties".equals(source.getName())
                    && source.containsProperty("jobapply.storage.root")) {
                // application*.yml 的来源名是「Config resource ...」；命令行、环境变量、测试里给的都算指定
                return !source.getName().startsWith("Config resource");
            }
        }
        return System.getenv("JOBAPPLY_STORAGE_ROOT") != null;
    }

    /** app.yaml 里的 storageRoot；没有文件时为 null。内容不对时报错，不悄悄换成默认位置。 */
    private static Path readAppConfig(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Object loaded;
        try {
            loaded = new Yaml().load(Files.readString(file));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("读不了 " + file + "：" + e.getMessage(), e);
        }
        if (!(loaded instanceof Map<?, ?> map) || !(map.get("storageRoot") instanceof String root)
                || !Path.of(root).isAbsolute()) {
            throw new IllegalStateException(file + " 需要一项绝对路径 storageRoot");
        }
        return Path.of(root).normalize();
    }

    /** 只读检查一个候选位置：不建、不写任何东西。 */
    public record Check(String path, boolean ok, String problem, String kind, int projects) {
    }

    public static Check check(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Check(raw, false, "需要一个路径", null, 0);
        }
        Path path = Path.of(raw.trim());
        if (!path.isAbsolute()) {
            return new Check(raw, false, "需要绝对路径", null, 0);
        }
        path = path.normalize();
        String shown = path.toString();
        if (!Files.exists(path)) {
            Path parent = path.getParent();
            while (parent != null && !Files.exists(parent)) {
                parent = parent.getParent();
            }
            if (parent == null || !Files.isDirectory(parent) || !Files.isWritable(parent)) {
                return new Check(shown, false, "文件夹不存在，也建不了（上级目录不可写）", null, 0);
            }
            return new Check(shown, true, null, "empty", 0);
        }
        if (!Files.isDirectory(path)) {
            return new Check(shown, false, "这不是文件夹", null, 0);
        }
        if (!Files.isWritable(path)) {
            return new Check(shown, false, "这个文件夹不可写", null, 0);
        }
        int projects = countProjects(path);
        if (Files.isDirectory(path.resolve("projects")) || Files.isDirectory(path.resolve("settings"))) {
            return new Check(shown, true, null, "jobapply", projects);
        }
        try (Stream<Path> entries = Files.list(path)) {
            boolean empty = entries.noneMatch(p -> !p.getFileName().toString().equals(".DS_Store"));
            return new Check(shown, true, null, empty ? "empty" : "other", 0);
        } catch (IOException e) {
            return new Check(shown, false, "读不了这个文件夹：" + e.getMessage(), null, 0);
        }
    }

    public static int countProjects(Path root) {
        Path dir = root.resolve("projects");
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return (int) entries.filter(Files::isDirectory).count();
        } catch (IOException e) {
            return 0;
        }
    }
}
