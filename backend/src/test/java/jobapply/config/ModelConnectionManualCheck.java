package jobapply.config;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import jobapply.persistence.EnvFile;
import jobapply.persistence.ModelSettingsFile;
import jobapply.persistence.StorageLayout;
import org.junit.jupiter.api.Test;

/**
 * 真实 LLM 测试（dev-limit.md「真实 LLM 测试」）：读取用户真实的 {@code storage/settings/models.yaml}，由程序解析
 * {@code .env} 里的 Key，对主模型和辅助模型各做一次「测试连接」。只打印延迟、模型名、是否支持工具调用和用量，
 * 不打印 Key，也不写回 models.yaml。名字不以 Test / IT 结尾，{@code mvn test} / {@code mvn verify} 不会运行它。
 *
 * <pre>
 * mvn -B test -Dtest=ModelConnectionManualCheck -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * 可选：{@code -Djobapply.check.storage=<storage 目录>}（默认 {@code ../storage}，即在 {@code backend/} 下运行时仓库的 storage）。
 */
class ModelConnectionManualCheck {

    @Test
    void testMainAndAuxiliaryAgainstRealProviders() {
        Path root = Path.of(System.getProperty("jobapply.check.storage", "../storage")).toAbsolutePath().normalize();
        StorageLayout layout = new StorageLayout(root);
        assumeTrue(Files.exists(layout.modelsYaml()), "没有 " + layout.modelsYaml() + "，先在设置页配置模型");
        ModelSettingsFile.Settings settings = new ModelSettingsFile(layout, Clock.systemDefaultZone()).read();
        EnvFile env = new EnvFile(layout.envFile());
        ConnectionTester tester = new ConnectionTester();

        for (String usage : ModelSettingsFile.USAGES) {
            Optional<ModelSettingsFile.Assignment> assignment = settings.assignment(usage);
            if (assignment.isEmpty()) {
                System.out.println("[" + usage + "] 没有分配");
                continue;
            }
            ModelFactory.Endpoint endpoint;
            try {
                endpoint = ModelRegistry.endpoint(settings, assignment.get(), env);
            } catch (RuntimeException e) {
                System.out.println("[" + usage + "] 配置有问题：" + e.getMessage());
                continue;
            }
            ConnectionTester.Result result = tester.test(endpoint);
            System.out.println("[" + usage + "] provider=" + assignment.get().provider()
                    + " model=" + result.model() + " ok=" + result.ok() + " tools=" + result.supportsTools()
                    + " latency_ms=" + result.latencyMs() + " tokens=" + result.promptTokens() + "/"
                    + result.completionTokens() + (result.error() == null ? "" : " error=" + result.error()));
        }
    }
}
