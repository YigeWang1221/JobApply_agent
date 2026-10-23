package jobapply.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import jobapply.persistence.EnvFile;
import jobapply.persistence.ModelSettingsFile;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 持有每个用途当前对应的 {@link ChatModel}。第一次取的时候按 {@code models.yaml} 创建；设置保存后调用 {@link #reload()}，
 * 下一次取就用新设置（热替换，不用重启）。C004-B 的 Harness 从这里取主模型。没有配置时明确报错，不猜默认值。
 */
public final class ModelRegistry {

    static final Duration TIMEOUT = Duration.ofSeconds(120);

    /** 这个用途没有分配模型，或分配的提供方 / 模型已经不存在。 */
    public static final class NotConfiguredException extends RuntimeException {

        public NotConfiguredException(String message) {
            super(message);
        }
    }

    private final ModelSettingsFile settings;
    private final EnvFile env;
    private final Map<String, ChatModel> models = new HashMap<>();

    public ModelRegistry(ModelSettingsFile settings, EnvFile env) {
        this.settings = settings;
        this.env = env;
    }

    /** 主模型。没有配置时抛出 {@link NotConfiguredException}。 */
    public ChatModel main() {
        return forUsage(ModelSettingsFile.MAIN)
                .orElseThrow(() -> new NotConfiguredException("还没有配置主模型，先在设置页分配主模型"));
    }

    /** 辅助模型；没有配置时用主模型；都没有时为空。 */
    public Optional<ChatModel> auxiliaryOrMain() {
        Optional<ChatModel> auxiliary = forUsage(ModelSettingsFile.AUXILIARY);
        return auxiliary.isPresent() ? auxiliary : forUsage(ModelSettingsFile.MAIN);
    }

    /**
     * 某个用途的模型；没有分配时为空。
     *
     * @throws NotConfiguredException 分配了，但提供方或模型已经不存在
     * @throws EnvFile.MissingVariableException Key 的变量没有设置
     */
    public synchronized Optional<ChatModel> forUsage(String usage) {
        ChatModel cached = models.get(usage);
        if (cached != null) {
            return Optional.of(cached);
        }
        ModelSettingsFile.Settings current = settings.read();
        Optional<ModelSettingsFile.Assignment> assignment = current.assignment(usage);
        if (assignment.isEmpty()) {
            return Optional.empty();
        }
        ModelFactory.Endpoint endpoint = endpoint(current, assignment.get(), env);
        ChatModel model = ModelFactory.create(endpoint, TIMEOUT);
        models.put(usage, model);
        return Optional.of(model);
    }

    /** 设置变了：丢掉已创建的模型，下一次取的时候重新创建。 */
    public synchronized void reload() {
        models.clear();
    }

    static ModelFactory.Endpoint endpoint(ModelSettingsFile.Settings settings, ModelSettingsFile.Assignment assignment,
                                          EnvFile env) {
        ModelSettingsFile.Provider provider = settings.provider(assignment.provider())
                .orElseThrow(() -> new NotConfiguredException("分配的提供方 " + assignment.provider() + " 不存在"));
        if (!provider.models().contains(assignment.model())) {
            throw new NotConfiguredException("提供方 " + provider.id() + " 没有选中模型 " + assignment.model());
        }
        return endpoint(provider, assignment.model(), env);
    }

    /** 解析占位符，得到可调用的端点。 */
    static ModelFactory.Endpoint endpoint(ModelSettingsFile.Provider provider, String model, EnvFile env) {
        String key = provider.apiKey() == null || provider.apiKey().isBlank() ? null : env.resolve(provider.apiKey());
        return new ModelFactory.Endpoint(provider.format(), env.resolve(provider.baseUrl()), key, model);
    }
}
