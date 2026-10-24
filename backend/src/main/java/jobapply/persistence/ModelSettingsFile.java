package jobapply.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * {@code storage/settings/models.yaml}：模型提供方和用途分配（ui.md「模型设置」）。Key 不在这里，这里只有占位符
 * {@code ${变量名}}，值在 {@code settings/.env}（{@link EnvFile}）。原子写入。
 * 读的时候保留未识别的字段，写回时原样带上。
 */
public final class ModelSettingsFile {

    public static final String MAIN = "main";
    public static final String AUXILIARY = "auxiliary";
    public static final String CROSS_CHECK = "cross_check";
    public static final List<String> USAGES = List.of(MAIN, AUXILIARY, CROSS_CHECK);
    public static final Set<String> FORMATS = Set.of("openai", "anthropic");

    private static final Pattern ID = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?");
    private static final Set<String> PROVIDER_KEYS = Set.of(
            "id", "preset", "format", "base_url", "api_key", "models", "tested");

    /** 最近一次「测试连接」的结果。{@code error} 是失败原因（不含 Key），成功时为 null。 */
    public record TestResult(String model, boolean ok, boolean tools, long latencyMs, String at, String error) {
    }

    /**
     * 一个提供方。
     *
     * @param apiKey 占位符 {@code ${变量名}}；本地模型不需要 Key 时为 null
     * @param extra  未识别的字段
     */
    public record Provider(String id, String preset, String format, String baseUrl, String apiKey,
                           List<String> models, List<TestResult> tested, Map<String, Object> extra) {

        public Provider {
            models = models == null ? List.of() : List.copyOf(models);
            tested = tested == null ? List.of() : List.copyOf(tested);
            extra = extra == null ? Map.of() : Map.copyOf(extra);
        }

        public Optional<TestResult> testOf(String model) {
            return tested.stream().filter(result -> result.model().equals(model)).findFirst();
        }

        /** 同一个模型只留最新一次结果。 */
        public Provider withTest(TestResult result) {
            List<TestResult> next = new ArrayList<>(tested.stream()
                    .filter(old -> !old.model().equals(result.model())).toList());
            next.add(result);
            return new Provider(id, preset, format, baseUrl, apiKey, models, next, extra);
        }
    }

    /**
     * 一个用途分配给哪个提供方的哪个模型。
     *
     * @param showThinking 「显示思考」（C006-agent）。现在不改变请求：Anthropic 的 extended thinking 带工具调用时要把上一轮的
     *                     thinking 块（含签名）原样发回，本项目每一轮从存下的对话重新拼消息，Spring AI 2.0.1 的
     *                     thinking 内容类型不公开，拼不回去；OpenAI 兼容格式的推理字段不用开关，有就显示。
     */
    public record Assignment(String provider, String model, Boolean showThinking) {

        public Assignment {
            showThinking = Boolean.TRUE.equals(showThinking);
        }

        public Assignment(String provider, String model) {
            this(provider, model, false);
        }
    }

    /**
     * @param assignments 用途 → 分配；没有分配的用途不在里面
     * @param extra       未识别的顶层字段
     */
    public record Settings(List<Provider> providers, Map<String, Assignment> assignments,
                           Map<String, Object> assignmentsExtra, Map<String, Object> extra) {

        public Settings {
            providers = providers == null ? List.of() : List.copyOf(providers);
            assignments = assignments == null ? Map.of() : Map.copyOf(assignments);
            assignmentsExtra = assignmentsExtra == null ? Map.of() : Map.copyOf(assignmentsExtra);
            extra = extra == null ? Map.of() : Map.copyOf(extra);
        }

        public static Settings empty() {
            return new Settings(List.of(), Map.of(), Map.of(), Map.of());
        }

        public Optional<Provider> provider(String id) {
            return providers.stream().filter(provider -> provider.id().equals(id)).findFirst();
        }

        public Optional<Assignment> assignment(String usage) {
            return Optional.ofNullable(assignments.get(usage));
        }

        /** 正在使用这个提供方的用途。 */
        public List<String> usagesOf(String providerId) {
            return USAGES.stream()
                    .filter(usage -> assignment(usage).map(a -> a.provider().equals(providerId)).orElse(false))
                    .toList();
        }

        /** 新建或替换同 id 的提供方，顺序不变。 */
        public Settings withProvider(Provider provider) {
            List<Provider> next = new ArrayList<>();
            boolean replaced = false;
            for (Provider old : providers) {
                if (old.id().equals(provider.id())) {
                    next.add(provider);
                    replaced = true;
                } else {
                    next.add(old);
                }
            }
            if (!replaced) {
                next.add(provider);
            }
            return new Settings(next, assignments, assignmentsExtra, extra);
        }

        /**
         * 删掉提供方。
         *
         * @throws InUseException 它正被某个用途使用
         */
        public Settings withoutProvider(String id) {
            List<String> usages = usagesOf(id);
            if (!usages.isEmpty()) {
                throw new InUseException(id, usages);
            }
            return new Settings(providers.stream().filter(p -> !p.id().equals(id)).toList(), assignments,
                    assignmentsExtra, extra);
        }

        public Settings withAssignments(Map<String, Assignment> next) {
            return new Settings(providers, next, assignmentsExtra, extra);
        }
    }

    /** 提供方正被某个用途使用，不能删除。 */
    public static final class InUseException extends RuntimeException {

        public InUseException(String id, List<String> usages) {
            super("提供方 " + id + " 正被用作 " + String.join("、", usages) + "，先修改用途分配再删除");
        }
    }

    private final StorageLayout layout;

    public ModelSettingsFile(StorageLayout layout, Clock clock) {
        this.layout = layout;
    }

    public static boolean isValidId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    public static String requireId(String id) {
        if (!isValidId(id)) {
            throw new IllegalArgumentException("提供方 id 只允许小写字母、数字和 -，不超过 40 个字符，首尾不能是 -");
        }
        return id;
    }

    /** 由提供方 id 生成 .env 里的变量名：大写，{@code -} 换成 {@code _}，加上 {@code _API_KEY}。 */
    public static String envName(String providerId) {
        return requireId(providerId).toUpperCase(Locale.ROOT).replace('-', '_') + "_API_KEY";
    }

    public static String placeholder(String providerId) {
        return "${" + envName(providerId) + "}";
    }

    public synchronized Settings read() {
        Path file = layout.modelsYaml();
        if (!Files.exists(file)) {
            return Settings.empty();
        }
        Object loaded;
        try {
            loaded = FrontmatterFile.yaml().load(AtomicFiles.read(file));
        } catch (YAMLException e) {
            throw new StorageFormatException(file.toString(), "不是合法的 YAML：" + e.getMessage());
        }
        if (loaded == null) {
            return Settings.empty();
        }
        Map<String, Object> root = map(file, loaded, "顶层");
        List<Provider> providers = new ArrayList<>();
        for (Object item : list(file, root.get("providers"), "providers")) {
            providers.add(provider(file, map(file, item, "providers 的每一项")));
        }
        Map<String, Assignment> assignments = new LinkedHashMap<>();
        Map<String, Object> assignmentsExtra = new LinkedHashMap<>();
        if (root.get("assignments") != null) {
            for (Map.Entry<String, Object> entry : map(file, root.get("assignments"), "assignments").entrySet()) {
                if (!USAGES.contains(entry.getKey())) {
                    assignmentsExtra.put(entry.getKey(), entry.getValue());
                } else if (entry.getValue() != null) {
                    Map<String, Object> value = map(file, entry.getValue(), "assignments." + entry.getKey());
                    assignments.put(entry.getKey(), new Assignment(string(value.get("provider")),
                            string(value.get("model")), Boolean.TRUE.equals(value.get("show_thinking"))));
                }
            }
        }
        Map<String, Object> extra = new LinkedHashMap<>(root);
        extra.remove("providers");
        extra.remove("assignments");
        return new Settings(providers, assignments, assignmentsExtra, extra);
    }

    public synchronized void write(Settings settings, String message) {
        for (Provider provider : settings.providers()) {
            requireId(provider.id());
            if (!FORMATS.contains(provider.format())) {
                throw new IllegalArgumentException("接口格式只能是 openai 或 anthropic");
            }
        }
        Map<String, Object> root = new LinkedHashMap<>();
        List<Object> providers = new ArrayList<>();
        for (Provider provider : settings.providers()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", provider.id());
            out.put("preset", provider.preset());
            out.put("format", provider.format());
            out.put("base_url", provider.baseUrl());
            out.put("api_key", provider.apiKey());
            out.put("models", new ArrayList<>(provider.models()));
            List<Object> tested = new ArrayList<>();
            for (TestResult result : provider.tested()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("model", result.model());
                entry.put("ok", result.ok());
                entry.put("tools", result.tools());
                entry.put("latency_ms", result.latencyMs());
                entry.put("at", result.at());
                if (result.error() != null) {
                    entry.put("error", result.error());
                }
                tested.add(entry);
            }
            out.put("tested", tested);
            out.putAll(provider.extra());
            providers.add(out);
        }
        root.put("providers", providers);
        Map<String, Object> assignments = new LinkedHashMap<>();
        for (String usage : USAGES) {
            assignments.put(usage, settings.assignment(usage).map(a -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("provider", a.provider());
                value.put("model", a.model());
                if (Boolean.TRUE.equals(a.showThinking())) {
                    value.put("show_thinking", true);
                }
                return (Object) value;
            }).orElse(null));
        }
        assignments.putAll(settings.assignmentsExtra());
        root.put("assignments", assignments);
        root.putAll(settings.extra());
        Path file = layout.modelsYaml();
        AtomicFiles.write(file, "# 模型设置。Key 不在这里：api_key 是占位符，值在 settings/.env\n"
                + FrontmatterFile.yaml().dump(root));
    }

    private static Provider provider(Path file, Map<String, Object> map) {
        String id = string(map.get("id"));
        if (!isValidId(id)) {
            throw new StorageFormatException(file.toString(), "提供方 id 不合法：" + id);
        }
        List<String> models = list(file, map.get("models"), "models").stream().map(String::valueOf).toList();
        List<TestResult> tested = new ArrayList<>();
        for (Object item : list(file, map.get("tested"), "tested")) {
            Map<String, Object> entry = map(file, item, "tested 的每一项");
            tested.add(new TestResult(string(entry.get("model")), Boolean.TRUE.equals(entry.get("ok")),
                    Boolean.TRUE.equals(entry.get("tools")),
                    entry.get("latency_ms") instanceof Number n ? n.longValue() : 0L,
                    string(entry.get("at")), string(entry.get("error"))));
        }
        Map<String, Object> extra = new LinkedHashMap<>(map);
        extra.keySet().removeAll(PROVIDER_KEYS);
        return new Provider(id, string(map.get("preset")), string(map.get("format")), string(map.get("base_url")),
                string(map.get("api_key")), models, tested, extra);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Path file, Object value, String what) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new StorageFormatException(file.toString(), what + " 必须是映射");
        }
        for (Object key : map.keySet()) {
            if (!(key instanceof String)) {
                throw new StorageFormatException(file.toString(), what + " 的键必须是字符串：" + key);
            }
        }
        return (Map<String, Object>) map;
    }

    private static List<?> list(Path file, Object value, String what) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new StorageFormatException(file.toString(), what + " 必须是列表");
        }
        return list;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
