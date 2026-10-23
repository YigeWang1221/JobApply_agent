package jobapply.config;

import java.time.Clock;
import jobapply.memory.CandidateExtractor;
import jobapply.memory.FactWriter;
import jobapply.memory.ImportEstimator;
import jobapply.memory.ImportRunner;
import jobapply.memory.ProfileSummaryBuilder;
import jobapply.memory.SourceInventory;
import jobapply.memory.TranslationDrafter;
import jobapply.persistence.ConflictsFile;
import jobapply.persistence.EvidenceCardFile;
import jobapply.persistence.GlossaryFile;
import jobapply.persistence.ImportRunStore;
import jobapply.persistence.LedgerFile;
import jobapply.persistence.ProfileSummaryFile;
import jobapply.persistence.ProjectCardFile;
import jobapply.persistence.SourceRegistry;
import jobapply.persistence.StorageLayout;
import jobapply.tools.GitHubPublicReader;
import jobapply.tools.LocalDirectoryScanner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 资料导入（来源登记、扫描、估算；整理、确认、画像）用到的 Bean。应用启动时不读写 storage，不访问 GitHub，不创建模型：
 * 模型在每次用到时从 {@link ModelRegistry} 取（设置页保存后热替换）。
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
    LocalDirectoryScanner localDirectoryScanner(ImportProperties properties) {
        return new LocalDirectoryScanner(properties.limits());
    }

    @Bean
    ImportEstimator importEstimator(StorageLayout layout, SourceRegistry sources, ImportRunStore runs,
                                    LocalDirectoryScanner scanner, GitHubPublicReader github,
                                    ImportProperties properties) {
        return new ImportEstimator(layout, sources, runs, scanner, github, properties.limits());
    }

    @Bean
    LedgerFile ledgerFile(StorageLayout layout, Clock clock) {
        return new LedgerFile(layout, clock);
    }

    @Bean
    ProjectCardFile projectCardFile(StorageLayout layout, Clock clock) {
        return new ProjectCardFile(layout, clock);
    }

    @Bean
    GlossaryFile glossaryFile(StorageLayout layout, Clock clock) {
        return new GlossaryFile(layout, clock);
    }

    @Bean
    ConflictsFile conflictsFile(StorageLayout layout, Clock clock) {
        return new ConflictsFile(layout, clock);
    }

    @Bean
    ProfileSummaryFile profileSummaryFile(StorageLayout layout, Clock clock) {
        return new ProfileSummaryFile(layout, clock);
    }

    @Bean
    FactWriter factWriter(StorageLayout layout, LedgerFile ledger, ProjectCardFile cards, GlossaryFile glossary,
                          ConflictsFile conflicts, ModelRegistry models, Clock clock) {
        return new FactWriter(layout, ledger, cards, glossary, conflicts,
                new TranslationDrafter(models::main, glossary), clock);
    }

    @Bean
    ImportRunner importRunner(StorageLayout layout, ImportRunStore runs, SourceRegistry sources,
                              LocalDirectoryScanner scanner, GitHubPublicReader github, FactWriter writer,
                              LedgerFile ledger, ProjectCardFile cards, ModelRegistry models, Clock clock) {
        SourceInventory inventory = new SourceInventory(layout, sources, scanner, github,
                new EvidenceCardFile(layout, clock), models::auxiliaryOrMain, clock);
        return new ImportRunner(layout, runs, sources, inventory, new CandidateExtractor(models::main), writer, ledger,
                cards, github, models::main, clock);
    }

    @Bean
    ProfileSummaryBuilder profileSummaryBuilder(StorageLayout layout, ProjectCardFile cards, LedgerFile ledger,
                                                ProfileSummaryFile file, ModelRegistry models, Clock clock) {
        return new ProfileSummaryBuilder(layout, cards, ledger, file, models::main, clock);
    }
}
