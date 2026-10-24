package jobapply.persistence;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code storage/settings/.env}：API key 的唯一落盘位置（ui.md「密钥存放」）。权限 600，原子写入。
 * 一行一个 {@code 名字=值}；保留注释和其它行。值不进日志、不进异常信息、不进 {@link #toString()}。
 * {@code ${名字}} 占位符先在本文件里找，再找进程的环境变量（用户自己 {@code export} 的）；都没有时只报名字。
 */
public final class EnvFile {

    private static final Pattern NAME = Pattern.compile("[A-Z_][A-Z0-9_]{0,127}");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]*)}");
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");
    static final int MAX_VALUE_LENGTH = 512;

    /** 占位符引用的变量在 .env 和环境变量里都没有。信息里只有变量名。 */
    public static final class MissingVariableException extends RuntimeException {

        public MissingVariableException(String name) {
            super("变量 " + name + " 没有设置");
        }
    }

    private final Path file;
    private final Function<String, String> processEnv;

    public EnvFile(Path file) {
        this(file, System::getenv);
    }

    public EnvFile(Path file, Function<String, String> processEnv) {
        this.file = file;
        this.processEnv = processEnv;
    }

    public static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** Key 的检查：去掉首尾空格后不能为空，不超过 512 个字符，不能含换行、{@code =}、{@code #}。返回去掉空格后的值。 */
    public static String checkValue(String value) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Key 不能为空");
        }
        if (trimmed.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("Key 不能超过 " + MAX_VALUE_LENGTH + " 个字符");
        }
        if (trimmed.chars().anyMatch(c -> c == '\n' || c == '\r' || c == '=' || c == '#' || c == 0)) {
            throw new IllegalArgumentException("Key 不能含换行、= 或 #");
        }
        return trimmed;
    }

    /** 只看本文件。 */
    public synchronized Optional<String> get(String name) {
        requireName(name);
        for (String line : lines()) {
            Optional<String> value = valueOf(line, name);
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    /** 本文件里所有合法的 {@code 名字=值}（同名取第一行），按出现顺序。值只在内存里用，不进日志。 */
    public synchronized Map<String, String> entries() {
        Map<String, String> entries = new LinkedHashMap<>();
        for (String line : lines()) {
            String trimmed = line.strip();
            if (trimmed.startsWith("export ")) {
                trimmed = trimmed.substring("export ".length()).strip();
            }
            int equals = trimmed.indexOf('=');
            String name = equals < 0 ? null : trimmed.substring(0, equals).strip();
            if (isValidName(name) && !entries.containsKey(name)) {
                valueOf(line, name).ifPresent(value -> entries.put(name, value));
            }
        }
        return entries;
    }

    public boolean has(String name) {
        return get(name).isPresent();
    }

    public synchronized void set(String name, String value) {
        requireName(name);
        String checked = checkValue(value);
        List<String> lines = new ArrayList<>();
        boolean replaced = false;
        for (String line : lines()) {
            if (valueOf(line, name).isPresent()) {
                if (!replaced) {
                    lines.add(name + "=" + checked);
                    replaced = true;
                }
            } else {
                lines.add(line);
            }
        }
        if (!replaced) {
            lines.add(name + "=" + checked);
        }
        write(lines);
    }

    /** 删掉这个变量的所有行。没有时什么也不做。 */
    public synchronized void remove(String name) {
        requireName(name);
        List<String> lines = lines();
        List<String> kept = lines.stream().filter(line -> valueOf(line, name).isEmpty()).toList();
        if (kept.size() != lines.size()) {
            write(kept);
        }
    }

    /**
     * 把文本里的 {@code ${名字}} 换成变量的值。没有占位符时原样返回。
     *
     * @throws MissingVariableException 变量在本文件和进程环境变量里都没有
     */
    public String resolve(String template) {
        if (template == null) {
            return null;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!isValidName(name)) {
                throw new IllegalArgumentException("占位符 ${" + name + "} 的变量名不合法");
            }
            String value = get(name).orElseGet(() -> processEnv.apply(name));
            if (value == null || value.isBlank()) {
                throw new MissingVariableException(name);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** 文本里引用的变量名。 */
    public static List<String> placeholders(String template) {
        if (template == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    @Override
    public String toString() {
        return "EnvFile[" + file + "]";
    }

    private static void requireName(String name) {
        if (!isValidName(name)) {
            throw new IllegalArgumentException("变量名不合法：只允许大写字母、数字和下划线");
        }
    }

    private static Optional<String> valueOf(String line, String name) {
        String trimmed = line.strip();
        if (trimmed.startsWith("export ")) {
            trimmed = trimmed.substring("export ".length()).strip();
        }
        if (!trimmed.startsWith(name + "=")) {
            return Optional.empty();
        }
        String value = trimmed.substring(name.length() + 1).strip();
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
        }
        return Optional.of(value);
    }

    private List<String> lines() {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return Files.readAllLines(file, UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取失败：" + file, e);
        }
    }

    private void write(List<String> lines) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "." + file.getFileName(), ".tmp",
                    PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            try {
                Files.writeString(temp, lines.isEmpty() ? "" : String.join("\n", lines) + "\n", UTF_8);
                Files.setPosixFilePermissions(temp, OWNER_ONLY);
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("写入失败：" + file, e);
        }
    }
}
