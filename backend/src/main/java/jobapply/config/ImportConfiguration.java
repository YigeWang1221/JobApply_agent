package jobapply.config;

import java.time.Clock;
import jobapply.memory.ImportEstimator;
import jobapply.persistence.ImportRunStore;
import jobapply.persistence.SourceRegistry;
import jobapply.persistence.StorageLayout;
import jobapply.tools.GitHubPublicReader;
import jobapply.tools.LocalDirectoryScanner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 资料导入（来源登记、扫描、估算）用到的 Bean。应用启动时不读写 storage，不访问 GitHub。
 */
@Configuration
@EnableConfigurationProperties(ImportProperties.class)
public class ImportConfiguration {

    @Bean
    SourceRegistry sourceRegistry(StorageLayout layout, Clock clock) {
        return new SourceRegistry(layout, clock);
    }

    @Bean
    ImportRunStore importRunStore(StorageLayout layout, Clock clock) {
        return new ImportRunStore(layout, clock);
    }

    @Bean
    GitHubPublicReader gitHubPublicReader(ImportProperties properties) {
        return new GitHubPublicReader(properties.githubUri(), properties.githubTimeout());
    }

    @Bean
    ImportEstimator importEstimator(StorageLayout layout, SourceRegistry sources, ImportRunStore runs,
                                    GitHubPublicReader github, ImportProperties properties) {
        return new ImportEstimator(layout, sources, runs, new LocalDirectoryScanner(properties.limits()), github,
                properties.limits());
    }
}
