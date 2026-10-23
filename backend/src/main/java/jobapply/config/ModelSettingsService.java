package jobapply.config;

import java.net.URI;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import jobapply.domain.Timestamps;
import jobapply.persistence.EnvFile;
import jobapply.persistence.ModelSettingsFile;
import jobapply.persistence.ModelSettingsFile.Assignment;
import jobapply.persistence.ModelSettingsFile.Provider;
import jobapply.persistence.ModelSettingsFile.Settings;

/**
 * 设置页「模型」背后的操作：把 {@code models.yaml}、{@code .env}、{@link ModelRegistry}、模型列表和测试连接串起来。
 * Key 只经过 {@link #setKey(String, String)} 写进 .env；返回给页面的只有 {@code keySet} 和末四位。
 */
public final class ModelSettingsService {

    /** 页面上看到的提供方：没有 Key 本身。 */
    public record ProviderView(String id, String preset, String format, String baseUrl, String keyVariable,
                               boolean keySet, String keyLast4, List<String> models,
                               List<ModelSettingsFile.TestResult> tested, List<String> usedBy) {
    }

    public record View(List<ProviderView> providers, Map<String, Assignment> assignments,
                       List<ModelPresets.Preset> presets) {
    }

    private final ModelSettingsFile file;
    private final EnvFile env;
    private final ModelRegistry registry;
    private final ModelCatalogClient catalog;
    private final ConnectionTester tester;
    private final Clock clock;

    public ModelSettingsService(ModelSettingsFile file, EnvFile env, ModelRegistry registry, ModelCatalogClient catalog,
                                ConnectionTester tester, Clock clock) {
        this.file = file;
        this.env = env;
        this.registry = registry;
        this.catalog = catalog;
        this.tester = tester;
        this.clock = clock;
    }

    public synchronized View view() {
        Settings settings = file.read();
        List<ProviderView> providers = settings.providers().stream().map(p -> view(settings, p)).toList();
        return new View(providers, settings.assignments(), ModelPresets.ALL);
    }

    /** 新建或修改提供方（不含 Key）。修改时保留已有的 Key 占位符和测试结果。 */
    public synchronized ProviderView upsertProvider(String id, String preset, String format, String baseUrl,
                                                    List<String> models) {
        ModelSettingsFile.requireId(id);
        Optional<ModelPresets.Preset> known = ModelPresets.find(preset == null ? "" : preset);
        if (known.isEmpty()) {
            throw new IllegalArgumentException("未知的预设：" + preset);
        }
        String resolvedFormat = format == null || format.isBlank() ? known.get().format() : format;
        if (resolvedFormat == null || !ModelSettingsFile.FORMATS.contains(resolvedFormat)) {
            throw new IllegalArgumentException("接口格式只能是 openai 或 anthropic");
        }
        String resolvedBase = baseUrl == null || baseUrl.isBlank() ? known.get().baseUrl() : baseUrl.strip();
        if (resolvedBase == null || !(resolvedBase.startsWith("http://") || resolvedBase.startsWith("https://")
                || resolvedBase.startsWith("${"))) {
            throw new IllegalArgumentException("Base URL 必须是 http:// 或 https:// 开头，或者是占位符 ${变量名}");
        }
        List<String> cleanModels = (models == null ? List.<String>of() : models).stream()
                .map(String::strip).filter(m -> !m.isEmpty()).distinct().toList();
        if (cleanModels.stream().anyMatch(m -> m.length() > 200 || m.contains("\n"))) {
            throw new IllegalArgumentException("模型名不合法");
        }
        Settings settings = file.read();
        Optional<Provider> old = settings.provider(id);
        // 接口格式或 Base URL 真的变了（写法差异不算），旧的测试结果对新端点不成立，作废；用途分配不动
        boolean endpointChanged = old.isPresent() && (!old.get().format().equals(resolvedFormat)
                || !sameEndpoint(old.get().baseUrl(), resolvedBase));
        Provider provider = new Provider(id, preset, resolvedFormat, resolvedBase,
                old.map(Provider::apiKey).orElse(null), cleanModels,
                endpointChanged ? List.of() : old.map(p -> p.tested().stream()
                        .filter(t -> cleanModels.contains(t.model())).toList()).orElse(List.of()),
                old.map(Provider::extra).orElse(Map.of()));
        Settings next = settings.withProvider(provider);
        for (String usage : next.usagesOf(id)) {
            String model = next.assignment(usage).orElseThrow().model();
            if (!cleanModels.contains(model)) {
                throw new SettingsException.Conflict("模型 " + model + " 正被用作 " + usage + "，先修改用途分配");
            }
        }
        file.write(next, (old.isPresent() ? "修改" : "添加") + "提供方 " + id);
        registry.reload();
        return view(next, provider);
    }

    /**
     * 两个 Base URL 是不是同一个端点：忽略末尾的 /、协议和主机名的大小写、默认端口（http 80、https 443）。
     * 占位符 {@code ${变量名}} 只比较原文。
     */
    static boolean sameEndpoint(String a, String b) {
        return normalizeEndpoint(a).equals(normalizeEndpoint(b));
    }

    private static String normalizeEndpoint(String url) {
        String text = url.strip().replaceAll("/+$", "");
        if (text.startsWith("${")) {
            return text;
        }
        try {
            URI uri = URI.create(text);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            boolean defaultPort = port == -1 || (scheme.equals("http") && port == 80)
                    || (scheme.equals("https") && port == 443);
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
            return scheme + "://" + host + (defaultPort ? "" : ":" + port) + path + query;
        } catch (IllegalArgumentException e) {
            return text;
        }
    }

    /** 删除提供方，同时删掉 .env 里对应的那一行。正被某个用途使用时拒绝。 */
    public synchronized void deleteProvider(String id) {
        Settings settings = file.read();
        Provider provider = require(settings, id);
        Settings next;
        try {
            next = settings.withoutProvider(id);
        } catch (ModelSettingsFile.InUseException e) {
            throw new SettingsException.Conflict(e.getMessage());
        }
        file.write(next, "删除提供方 " + id);
        for (String name : EnvFile.placeholders(provider.apiKey())) {
            if (EnvFile.isValidName(name)) {
                env.remove(name);
            }
        }
        registry.reload();
    }

    /** 把 Key 写进 .env，yaml 里只写占位符。返回末四位。 */
    public synchronized String setKey(String id, String key) {
        Settings settings = file.read();
        Provider provider = require(settings, id);
        String variable = ModelSettingsFile.envName(id);
        String checked = EnvFile.checkValue(key);
        env.set(variable, checked);
        String placeholder = ModelSettingsFile.placeholder(id);
        if (!placeholder.equals(provider.apiKey())) {
            file.write(settings.withProvider(new Provider(provider.id(), provider.preset(), provider.format(),
                    provider.baseUrl(), placeholder, provider.models(), provider.tested(), provider.extra())),
                    "提供方 " + id + " 的 Key 改为占位符 " + placeholder);
        }
        registry.reload();
        return last4(checked);
    }

    /** 模型 id 列表；拉不到时抛出 {@link SettingsException.Upstream}，带原因。 */
    public List<String> catalog(String id) {
        Provider provider = require(file.read(), id);
        String key = resolvedKey(provider);
        ModelCatalogClient.Result result = catalog.list(provider.format(), env.resolve(provider.baseUrl()), key);
        return switch (result) {
            case ModelCatalogClient.Models models -> models.ids();
            case ModelCatalogClient.Failed failed -> throw new SettingsException.Upstream(
                    "拉不到模型列表：" + failed.reason() + "。可以手填模型名");
        };
    }

    /** 测试连接，结果记进 models.yaml。 */
    public ConnectionTester.Result test(String id, String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("缺少 model");
        }
        Provider provider = require(file.read(), id);
        if (!provider.models().contains(model)) {
            throw new IllegalArgumentException("先选中模型 " + model + "，再测试");
        }
        ConnectionTester.Result result = tester.test(ModelRegistry.endpoint(provider, model, env));
        synchronized (this) {
            Settings latest = file.read();
            latest.provider(id).ifPresent(current -> file.write(latest.withProvider(current.withTest(
                    new ModelSettingsFile.TestResult(model, result.ok(), result.supportsTools(), result.latencyMs(),
                            Timestamps.now(clock), result.error()))), "测试连接 " + id + " / " + model));
        }
        return result;
    }

    /** 分配用途。主模型必须通过了工具调用测试。保存后立即生效（热替换）。 */
    public synchronized Map<String, Assignment> assign(Assignment main, Assignment auxiliary, Assignment crossCheck) {
        Settings settings = file.read();
        Map<String, Assignment> next = new LinkedHashMap<>();
        put(settings, next, ModelSettingsFile.MAIN, main);
        put(settings, next, ModelSettingsFile.AUXILIARY, auxiliary);
        put(settings, next, ModelSettingsFile.CROSS_CHECK, crossCheck);
        if (main != null) {
            Provider provider = settings.provider(main.provider()).orElseThrow();
            boolean passed = provider.testOf(main.model())
                    .map(t -> t.ok() && t.tools()).orElse(false);
            if (!passed) {
                throw new SettingsException.Conflict("模型 " + main.model()
                        + " 没有通过工具调用测试，不能用作主模型；先点「测试」");
            }
        }
        file.write(settings.withAssignments(next), "用途分配");
        registry.reload();
        return next;
    }

    private static void put(Settings settings, Map<String, Assignment> next, String usage, Assignment assignment) {
        if (assignment == null) {
            return;
        }
        Provider provider = settings.provider(assignment.provider())
                .orElseThrow(() -> new IllegalArgumentException("提供方 " + assignment.provider() + " 不存在"));
        if (!provider.models().contains(assignment.model())) {
            throw new IllegalArgumentException("提供方 " + provider.id() + " 没有选中模型 " + assignment.model());
        }
        next.put(usage, assignment);
    }

    private ProviderView view(Settings settings, Provider provider) {
        String key = null;
        try {
            key = resolvedKey(provider);
        } catch (EnvFile.MissingVariableException | IllegalArgumentException e) {
            // 页面上显示「未设置」
        }
        List<String> variables = EnvFile.placeholders(provider.apiKey());
        return new ProviderView(provider.id(), provider.preset(), provider.format(), provider.baseUrl(),
                variables.isEmpty() ? ModelSettingsFile.envName(provider.id()) : variables.getFirst(),
                key != null, key == null ? null : last4(key), provider.models(), provider.tested(),
                settings.usagesOf(provider.id()));
    }

    private String resolvedKey(Provider provider) {
        return provider.apiKey() == null || provider.apiKey().isBlank() ? null : env.resolve(provider.apiKey());
    }

    private static Provider require(Settings settings, String id) {
        ModelSettingsFile.requireId(id);
        return settings.provider(id).orElseThrow(() -> new SettingsException.NotFound("提供方不存在：" + id));
    }

    /** 末四位；Key 太短时不显示，免得露出大半。 */
    static String last4(String key) {
        return key.length() < 12 ? "****" : key.substring(key.length() - 4);
    }
}
