package jobapply.config;

import java.net.URI;
import java.io.UncheckedIOException;
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

    /** 页面上看到的提供方：没有 Key 本身，密钥请求头也没有值。 */
    public record ProviderView(String id, String preset, String format, String baseUrl, String keyVariable,
                               boolean keySet, String keyLast4, List<String> models,
                               List<ModelSettingsFile.TestResult> tested, List<String> usedBy,
                               List<HeaderView> headers, Account account) {
    }

    /** ChatGPT 套餐提供方的登录状态（C028）；别的提供方为 null。不带令牌。 */
    public record Account(boolean signedIn, String email) {
    }

    /** 模型列表：{@code labels} 是 slug → 显示名，只有 ChatGPT 的目录带（C028）。 */
    public record Catalog(List<String> models, Map<String, String> labels) {
    }

    /**
     * 一个自定义请求头（C025）。
     *
     * @param value  明文值或用户自己写的占位符；密钥请求头为 null
     * @param secret 值存在 .env 里（yaml 里是 {@code ${<提供方>_HEADER_<名字>}}）
     * @param set    密钥请求头的值在 .env 里有没有
     */
    public record HeaderView(String name, String value, boolean secret, boolean set) {
    }

    /**
     * 页面发来的请求头。
     *
     * @param value  新的值；密钥请求头留空表示沿用已有的值
     * @param secret 值写进 .env，yaml 里只写占位符
     */
    public record HeaderInput(String name, String value, boolean secret) {
    }

    public record View(List<ProviderView> providers, Map<String, Assignment> assignments,
                       List<ModelPresets.Preset> presets) {
    }

    private static final String CHATGPT = ModelPresets.CHATGPT;

    private final ModelSettingsFile file;
    private final EnvFile env;
    private final ModelRegistry registry;
    private final ModelCatalogClient catalog;
    private final ConnectionTester tester;
    private final Clock clock;
    private final ChatGptAuth chatGpt;

    public ModelSettingsService(ModelSettingsFile file, EnvFile env, ModelRegistry registry, ModelCatalogClient catalog,
                                ConnectionTester tester, Clock clock, ChatGptAuth chatGpt) {
        this.file = file;
        this.env = env;
        this.registry = registry;
        this.catalog = catalog;
        this.tester = tester;
        this.clock = clock;
        this.chatGpt = chatGpt;
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
        boolean chatGptPreset = ModelPresets.CHATGPT.equals(known.get().id());
        // ChatGPT 套餐只能从它的预设来，接口格式和 Base URL 固定（C028）
        String resolvedFormat = chatGptPreset ? CHATGPT
                : format == null || format.isBlank() ? known.get().format() : format;
        if (resolvedFormat == null || !ModelSettingsFile.FORMATS.contains(resolvedFormat)) {
            throw new IllegalArgumentException("接口格式只能是 openai、anthropic 或 chatgpt");
        }
        if (!chatGptPreset && CHATGPT.equals(resolvedFormat)) {
            throw new IllegalArgumentException("chatgpt 格式只能用「ChatGPT 套餐」预设添加");
        }
        String resolvedBase = chatGptPreset ? known.get().baseUrl()
                : baseUrl == null || baseUrl.isBlank() ? known.get().baseUrl() : baseUrl.strip();
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
        if (old.isPresent() && CHATGPT.equals(old.get().format()) != CHATGPT.equals(resolvedFormat)) {
            throw new SettingsException.Conflict("ChatGPT 套餐提供方不能和别的接口格式互换；删掉后重新添加");
        }
        if (CHATGPT.equals(resolvedFormat)) {
            settings.providers().stream().filter(p -> CHATGPT.equals(p.format()) && !p.id().equals(id)).findFirst()
                    .ifPresent(p -> {
                        throw new SettingsException.Conflict("只能有一个 ChatGPT 套餐提供方（已有 " + p.id() + "）");
                    });
        }
        // 接口格式或 Base URL 真的变了（写法差异不算），旧的测试结果对新端点不成立，作废；用途分配不动
        boolean endpointChanged = old.isPresent() && (!old.get().format().equals(resolvedFormat)
                || !sameEndpoint(old.get().baseUrl(), resolvedBase));
        Provider provider = new Provider(id, preset, resolvedFormat, resolvedBase,
                old.map(Provider::apiKey).orElse(null), cleanModels,
                endpointChanged ? List.of() : old.map(p -> p.tested().stream()
                        .filter(t -> cleanModels.contains(t.model())).toList()).orElse(List.of()),
                old.map(Provider::headers).orElse(Map.of()), old.map(Provider::extra).orElse(Map.of()));
        // 去掉的模型正被某个用途使用时，清掉那条分配，由用户重新分配（C025-fix）
        Settings next = settings.withProvider(provider).withoutAssignmentsOutside(id, cleanModels);
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

    /**
     * 删除提供方，同时删掉 .env 里对应的那一行；指向它的用途分配一起清掉（C025-fix）。
     * ChatGPT 套餐提供方先退出登录、删掉令牌文件（C028）。
     */
    public synchronized void deleteProvider(String id) {
        Settings settings = file.read();
        Provider provider = require(settings, id);
        if (CHATGPT.equals(provider.format())) {
            chatGpt.signOut();
        }
        Settings next = settings.withoutProvider(id);
        file.write(next, "删除提供方 " + id);
        for (String name : EnvFile.placeholders(provider.apiKey())) {
            if (EnvFile.isValidName(name)) {
                env.remove(name);
            }
        }
        provider.headers().keySet().forEach(header -> removeSecretHeader(provider, header));
        registry.reload();
    }

    /**
     * 整张替换自定义请求头（C025）。密钥请求头的值写进 .env，yaml 里只写占位符；去掉的密钥请求头，.env 里那一行一起删。
     * 错误信息里不带值。
     */
    public synchronized ProviderView setHeaders(String id, List<HeaderInput> inputs) {
        Settings settings = file.read();
        Provider provider = require(settings, id);
        Map<String, String> next = new LinkedHashMap<>();
        Map<String, String> secrets = new LinkedHashMap<>();
        for (HeaderInput input : inputs == null ? List.<HeaderInput>of() : inputs) {
            String name = input.name() == null ? "" : input.name().strip();
            if (!ModelSettingsFile.isValidHeaderName(name)) {
                throw new IllegalArgumentException("请求头名字只允许字母、数字和 -，不超过 64 个字符：" + name);
            }
            if (next.keySet().stream().anyMatch(name::equalsIgnoreCase)) {
                throw new IllegalArgumentException("请求头 " + name + " 重复了");
            }
            String value = input.value() == null ? "" : input.value().strip();
            if (input.secret()) {
                String variable = ModelSettingsFile.headerEnvName(id, name);
                if (value.isEmpty()) {
                    if (!secretPlaceholder(id, name).equals(provider.headers().get(name)) || !env.has(variable)) {
                        throw new IllegalArgumentException("密钥请求头 " + name + " 还没有值");
                    }
                } else {
                    ModelSettingsFile.requireHeaderValue(name, value);
                    secrets.put(variable, value);
                }
                next.put(name, secretPlaceholder(id, name));
            } else {
                ModelSettingsFile.requireHeaderValue(name, value);
                for (String variable : EnvFile.placeholders(value)) {
                    if (!EnvFile.isValidName(variable)) {
                        throw new IllegalArgumentException("请求头 " + name + " 里的占位符变量名不合法");
                    }
                }
                next.put(name, value);
            }
        }
        secrets.forEach(env::set);
        Provider updated = new Provider(provider.id(), provider.preset(), provider.format(), provider.baseUrl(),
                provider.apiKey(), provider.models(), provider.tested(), next, provider.extra());
        Settings written = settings.withProvider(updated);
        file.write(written, "提供方 " + id + " 的自定义请求头");
        provider.headers().keySet().stream().filter(header -> !secretPlaceholder(id, header).equals(next.get(header)))
                .forEach(header -> removeSecretHeader(provider, header));
        registry.reload();
        return view(written, updated);
    }

    private static String secretPlaceholder(String id, String header) {
        return "${" + ModelSettingsFile.headerEnvName(id, header) + "}";
    }

    /** 这个请求头原来是密钥请求头时，删掉 .env 里的那一行。 */
    private void removeSecretHeader(Provider provider, String header) {
        if (secretPlaceholder(provider.id(), header).equals(provider.headers().get(header))) {
            env.remove(ModelSettingsFile.headerEnvName(provider.id(), header));
        }
    }

    /** 把 Key 写进 .env，yaml 里只写占位符。返回末四位。 */
    public synchronized String setKey(String id, String key) {
        Settings settings = file.read();
        Provider provider = require(settings, id);
        if (CHATGPT.equals(provider.format())) {
            throw new IllegalArgumentException("ChatGPT 套餐不用 Key，点 Continue with ChatGPT 登录");
        }
        String variable = ModelSettingsFile.envName(id);
        String checked = EnvFile.checkValue(key);
        env.set(variable, checked);
        String placeholder = ModelSettingsFile.placeholder(id);
        if (!placeholder.equals(provider.apiKey())) {
            file.write(settings.withProvider(new Provider(provider.id(), provider.preset(), provider.format(),
                    provider.baseUrl(), placeholder, provider.models(), provider.tested(), provider.headers(),
                    provider.extra())),
                    "提供方 " + id + " 的 Key 改为占位符 " + placeholder);
        }
        registry.reload();
        return last4(checked);
    }

    /** 模型 id 列表；拉不到时抛出 {@link SettingsException.Upstream}，带原因。ChatGPT 用当前登录的令牌（C028）。 */
    public Catalog catalog(String id) {
        Provider provider = require(file.read(), id);
        String key;
        if (CHATGPT.equals(provider.format())) {
            try {
                key = chatGpt.current();
            } catch (NonTransientAiException e) {
                throw new SettingsException.Conflict(e.getMessage());
            } catch (IllegalStateException e) {
                throw new SettingsException.Upstream(e.getMessage());
            }
        } else {
            key = resolvedKey(provider);
        }
        ModelCatalogClient.Result result = catalog.list(provider.format(), env.resolve(provider.baseUrl()), key,
                ModelRegistry.headers(provider, env));
        return switch (result) {
            case ModelCatalogClient.Models models -> new Catalog(models.ids(), models.labels());
            case ModelCatalogClient.Failed failed -> throw new SettingsException.Upstream(
                    "拉不到模型列表：" + failed.reason() + "。可以手填模型名");
        };
    }

    /** 开始「用 ChatGPT 登录」，返回授权 URL（前端在新窗口打开）。 */
    public String chatGptSignIn(String id) {
        requireChatGpt(id);
        try {
            return chatGpt.startSignIn();
        } catch (UncheckedIOException e) {
            throw new SettingsException.Upstream(e.getMessage());
        }
    }

    public ChatGptAuth.Status chatGptStatus(String id) {
        requireChatGpt(id);
        return chatGpt.status();
    }

    /** 退出 ChatGPT：吊销（尽力而为）、删令牌文件；吊销失败的提示放在状态的 error 里。 */
    public ProviderView chatGptSignOut(String id) {
        requireChatGpt(id);
        chatGpt.signOut();
        Settings settings = file.read();
        return view(settings, require(settings, id));
    }

    private void requireChatGpt(String id) {
        Provider provider = require(file.read(), id);
        if (!CHATGPT.equals(provider.format())) {
            throw new IllegalArgumentException("提供方 " + id + " 不是 ChatGPT 套餐");
        }
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

    /**
     * 分配用途。新分配的主模型必须通过了工具调用测试；主模型没变或清空时不查（C025-fix：清别的用途不被旧测试结果挡住）。
     * 保存后立即生效（热替换）。
     */
    public synchronized Map<String, Assignment> assign(Assignment main, Assignment auxiliary, Assignment crossCheck) {
        Settings settings = file.read();
        Map<String, Assignment> next = new LinkedHashMap<>();
        put(settings, next, ModelSettingsFile.MAIN, main);
        put(settings, next, ModelSettingsFile.AUXILIARY, auxiliary);
        put(settings, next, ModelSettingsFile.CROSS_CHECK, crossCheck);
        boolean mainChanged = main != null && settings.assignment(ModelSettingsFile.MAIN)
                .map(old -> !old.provider().equals(main.provider()) || !old.model().equals(main.model()))
                .orElse(true);
        if (mainChanged) {
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
        if (assignment.thinking() != null && !ModelSettingsFile.THINKING.contains(assignment.thinking())) {
            throw new IllegalArgumentException("思考强度只能是 off、low、medium、high、max 或不填");
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
                settings.usagesOf(provider.id()), headerViews(provider),
                CHATGPT.equals(provider.format())
                        ? new Account(chatGpt.signedIn(), chatGpt.email().orElse(null)) : null);
    }

    private List<HeaderView> headerViews(Provider provider) {
        return provider.headers().entrySet().stream().map(entry -> {
            boolean secret = secretPlaceholder(provider.id(), entry.getKey()).equals(entry.getValue());
            return secret
                    ? new HeaderView(entry.getKey(), null, true,
                            env.has(ModelSettingsFile.headerEnvName(provider.id(), entry.getKey())))
                    : new HeaderView(entry.getKey(), entry.getValue(), false, true);
        }).toList();
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
