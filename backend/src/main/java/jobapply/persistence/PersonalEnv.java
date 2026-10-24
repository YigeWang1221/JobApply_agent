package jobapply.persistence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import jobapply.domain.Placeholder;

/**
 * {@code storage/settings/personal.env}（C014-D）：C 级资料的真实值，一行一个 {@code KEY=值}，和 {@code .env} 同一种格式
 * （{@link EnvFile}），权限 600、原子写入。资料里只写占位符 {@code {KEY}}；程序只在把填表调用发给浏览器 MCP 的那一刻替换，
 * 页面快照回来时再把真实值换回占位符。值不进日志、异常信息和任何返回给页面的内容。
 */
public final class PersonalEnv {

    static final int MAX_VALUE_LENGTH = 512;

    private final EnvFile file;

    public PersonalEnv(StorageLayout layout) {
        // 只看这个文件，不找进程的环境变量
        this.file = new EnvFile(layout.personalEnvFile(), name -> null);
    }

    public Optional<String> value(String key) {
        return Placeholder.isValidKey(key) ? file.get(key).filter(value -> !value.isBlank()) : Optional.empty();
    }

    public boolean has(String key) {
        return value(key).isPresent();
    }

    /** 有值的键名。 */
    public Set<String> names() {
        return values().keySet();
    }

    /** 所有有值的 {@code KEY → 值}。只在内存里用来替换和遮掉。 */
    public Map<String, String> values() {
        Map<String, String> values = new LinkedHashMap<>();
        file.entries().forEach((key, value) -> {
            if (Placeholder.isValidKey(key) && !value.isBlank()) {
                values.put(key, value);
            }
        });
        return values;
    }

    /** 写一个值（同名的行替换，其它行保留）。错误信息里不带值。 */
    public void set(String key, String value) {
        requireKey(key);
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("值不能为空");
        }
        if (trimmed.length() > MAX_VALUE_LENGTH || trimmed.chars().anyMatch(
                c -> c == '\n' || c == '\r' || c == '=' || c == '#' || c == 0)) {
            throw new IllegalArgumentException("值不能超过 " + MAX_VALUE_LENGTH + " 个字符，不能含换行、= 或 #");
        }
        file.set(key, trimmed);
    }

    /** 清掉一个值。没有时什么也不做。 */
    public void remove(String key) {
        requireKey(key);
        file.remove(key);
    }

    @Override
    public String toString() {
        return "PersonalEnv[" + file + "]";
    }

    private static void requireKey(String key) {
        if (!Placeholder.isValidKey(key)) {
            throw new IllegalArgumentException("键名不合法：只允许大写字母、数字和下划线，不以数字开头");
        }
    }
}
