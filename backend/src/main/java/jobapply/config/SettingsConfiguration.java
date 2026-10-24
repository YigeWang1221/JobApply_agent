package jobapply.config;

import java.time.Clock;
import jobapply.memory.AssetAnalyzer;
import jobapply.persistence.AssetRegistry;
import jobapply.persistence.EnvFile;
import jobapply.persistence.ModelSettingsFile;
import jobapply.persistence.StorageLayout;
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
    ModelRegistry modelRegistry(ModelSettingsFile settings, EnvFile env) {
        return new ModelRegistry(settings, env);
    }

    @Bean
    ModelSettingsService modelSettingsService(ModelSettingsFile settings, EnvFile env, ModelRegistry registry,
                                              Clock clock) {
        return new ModelSettingsService(settings, env, registry, new ModelCatalogClient(), new ConnectionTester(),
                clock);
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
}
