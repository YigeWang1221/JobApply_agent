package jobapply.config;

import java.time.Clock;
import jobapply.memory.AssetAnalyzer;
import jobapply.memory.LeadGroups;
import jobapply.persistence.AssetRegistry;
import jobapply.persistence.EnvFile;
import jobapply.persistence.ModelSettingsFile;
import jobapply.persistence.StorageLayout;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 设置页（模型）和资料页（原件）用到的 Bean。应用启动时不读写 storage，也不创建模型；第一次调用接口时才读设置。
 * 模型不注册成 Bean：{@link ModelRegistry} 在运行时按 {@code models.yaml} 创建，设置保存后热替换。
 */
@Configuration
@EnableConfigurationProperties(ApiProperties.class)
public class SettingsConfiguration {

    @Bean
    EnvFile envFile(StorageLayout layout) {
        return new EnvFile(layout.envFile());
    }

    @Bean
    ModelSettingsFile modelSettingsFile(StorageLayout layout, Clock clock) {
        return new ModelSettingsFile(layout, clock);
    }

    @Bean
    ModelRegistry modelRegistry(ModelSettingsFile settings, EnvFile env, ObjectProvider<ChatGptAccessTokens> tokens) {
        return new ModelRegistry(settings, env, chatGptTokens(tokens));
    }

    @Bean
    ModelSettingsService modelSettingsService(ModelSettingsFile settings, EnvFile env, ModelRegistry registry,
                                              Clock clock, ObjectProvider<ChatGptAccessTokens> tokens,
                                              ChatGptAuth chatGpt) {
        return new ModelSettingsService(settings, env, registry, new ModelCatalogClient(),
                new ConnectionTester(chatGptTokens(tokens)), clock, chatGpt);
    }

    /**
     * ChatGPT 令牌源（C028）在第一次请求时才从容器里取：模型和测试连接都在运行时创建，启动时不依赖它。
     * 容器里没有这个 Bean 时，chatgpt 格式的调用报「需要先登录」，不影响别的格式。
     */
    private static ChatGptAccessTokens chatGptTokens(ObjectProvider<ChatGptAccessTokens> provider) {
        return new ChatGptAccessTokens() {
            @Override
            public String current() {
                return bean().current();
            }

            @Override
            public void invalidate() {
                bean().invalidate();
            }

            private ChatGptAccessTokens bean() {
                ChatGptAccessTokens tokens = provider.getIfAvailable();
                if (tokens == null) {
                    throw new NonTransientAiException("还没有用 ChatGPT 登录，请先在设置页登录");
                }
                return tokens;
            }
        };
    }

    /** 设置页「敏感信息」（C014-D）。 */
    @Bean
    PersonalSettingsService personalSettingsService(StorageLayout layout, Clock clock) {
        return new PersonalSettingsService(layout, clock);
    }

    @Bean
    AssetRegistry assetRegistry(StorageLayout layout, Clock clock) {
        return new AssetRegistry(layout, clock);
    }

    @Bean
    AssetAnalyzer assetAnalyzer(ModelRegistry registry) {
        return new AssetAnalyzer(registry::auxiliaryOrMain);
    }

    /** 候选池公司名 / 地点归并（C023-C）：辅助模型，没有就用主模型。 */
    @Bean
    LeadGroups leadGroups(StorageLayout layout, ModelRegistry registry) {
        return new LeadGroups(layout.leadGroups(), registry::auxiliaryOrMain);
    }
}
