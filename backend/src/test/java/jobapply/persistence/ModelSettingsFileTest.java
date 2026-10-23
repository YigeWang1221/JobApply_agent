package jobapply.persistence;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import jobapply.persistence.ModelSettingsFile.Assignment;
import jobapply.persistence.ModelSettingsFile.Provider;
import jobapply.persistence.ModelSettingsFile.Settings;
import jobapply.persistence.ModelSettingsFile.TestResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelSettingsFileTest {

    @TempDir
    Path root;

    private static Provider provider(String id) {
        return new Provider(id, "deepseek", "openai", "https://api.example.com/v1", ModelSettingsFile.placeholder(id),
                List.of("fake-small", "fake-large"),
                List.of(new TestResult("fake-large", true, true, 820, "2026-10-22T14:00:00Z", null)), Map.of());
    }

    @Test
    void roundTripsProvidersAndAssignments() {
        TestStorage storage = TestStorage.create(root);
        ModelSettingsFile file = new ModelSettingsFile(storage.layout, storage.clock);
        Settings settings = Settings.empty().withProvider(provider("deepseek-main"))
                .withProvider(new Provider("local", "ollama", "openai", "http://localhost:11434/v1", null,
                        List.of("fake-local"), List.of(), Map.of()))
                .withAssignments(Map.of(ModelSettingsFile.MAIN, new Assignment("deepseek-main", "fake-large")));

        file.write(settings, "测试");
        Settings read = file.read();

        assertThat(read).isEqualTo(settings);
        String yaml = storage.read(storage.layout.modelsYaml());
        assertThat(yaml).contains("api_key: ${DEEPSEEK_MAIN_API_KEY}").contains("cross_check: null")
                .contains("api_key: null");
        assertThat(read.assignment(ModelSettingsFile.AUXILIARY)).isEmpty();
    }

    @Test
    void missingFileIsEmpty() {
        TestStorage storage = TestStorage.create(root);

        assertThat(new ModelSettingsFile(storage.layout, storage.clock).read()).isEqualTo(Settings.empty());
    }

    @Test
    void keepsUnknownFields() throws Exception {
        TestStorage storage = TestStorage.create(root);
        Files.createDirectories(storage.layout.settingsDir());
        Files.writeString(storage.layout.modelsYaml(), """
                version: 2
                providers:
                  - id: gw
                    preset: custom
                    format: anthropic
                    base_url: ${GATEWAY_URL}
                    api_key: ${GW_API_KEY}
                    models: [fake-claude]
                    note: 公司网关
                assignments:
                  main: null
                  embedding: { provider: gw, model: fake-embed }
                """, UTF_8);
        ModelSettingsFile file = new ModelSettingsFile(storage.layout, storage.clock);

        Settings read = file.read();
        file.write(read.withProvider(read.provider("gw").orElseThrow()
                .withTest(new TestResult("fake-claude", true, false, 10, "2026-10-22T14:00:00Z", null))), "测试");
        Settings again = file.read();

        assertThat(again.extra()).containsEntry("version", 2);
        assertThat(again.provider("gw").orElseThrow().extra()).containsEntry("note", "公司网关");
        assertThat(again.provider("gw").orElseThrow().baseUrl()).isEqualTo("${GATEWAY_URL}");
        assertThat(again.assignmentsExtra()).containsKey("embedding");
        assertThat(again.provider("gw").orElseThrow().testOf("fake-claude")).isPresent();
    }

    @Test
    void validatesIds() {
        assertThat(ModelSettingsFile.isValidId("deepseek-main")).isTrue();
        assertThat(ModelSettingsFile.isValidId("a")).isTrue();
        for (String bad : List.of("", "Deep", "a_b", "-a", "a-", "../x", "a b", "x".repeat(41))) {
            assertThat(ModelSettingsFile.isValidId(bad)).as(bad).isFalse();
        }
        assertThat(ModelSettingsFile.envName("deepseek-main")).isEqualTo("DEEPSEEK_MAIN_API_KEY");
        assertThatThrownBy(() -> ModelSettingsFile.envName("../x")).isInstanceOf(IllegalArgumentException.class);

        TestStorage storage = TestStorage.create(root);
        ModelSettingsFile file = new ModelSettingsFile(storage.layout, storage.clock);
        assertThatThrownBy(() -> file.write(Settings.empty().withProvider(provider("Bad_Id")), "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesToDeleteAProviderInUse() {
        Settings settings = Settings.empty().withProvider(provider("deepseek-main"))
                .withAssignments(Map.of(ModelSettingsFile.AUXILIARY, new Assignment("deepseek-main", "fake-small")));

        assertThatThrownBy(() -> settings.withoutProvider("deepseek-main"))
                .isInstanceOf(ModelSettingsFile.InUseException.class)
                .hasMessageContaining("auxiliary");
        assertThat(settings.withAssignments(Map.of()).withoutProvider("deepseek-main").providers()).isEmpty();
    }

    @Test
    void commitsWhenGitIsOnButNeverTheEnvFile() {
        TestStorage storage = TestStorage.create(root);
        new EnvFile(storage.layout.envFile(), name -> null).set("DEEPSEEK_MAIN_API_KEY", "sk-fake-0000-1111-2222");
        ModelSettingsFile file = new ModelSettingsFile(storage.layout, storage.clock);

        file.write(Settings.empty().withProvider(provider("deepseek-main")), "添加提供方");
    }

    @Test
    void malformedYamlIsAFormatError() throws Exception {
        TestStorage storage = TestStorage.create(root);
        Files.createDirectories(storage.layout.settingsDir());
        Files.writeString(storage.layout.modelsYaml(), "providers: [unclosed", UTF_8);

        assertThatThrownBy(() -> new ModelSettingsFile(storage.layout, storage.clock).read())
                .isInstanceOf(StorageFormatException.class);
    }
}
