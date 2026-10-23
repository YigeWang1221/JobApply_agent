package jobapply.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import jobapply.persistence.EnvFile;
import jobapply.persistence.ModelSettingsFile;
import jobapply.persistence.ModelSettingsFile.Assignment;
import jobapply.persistence.ModelSettingsFile.Provider;
import jobapply.persistence.ModelSettingsFile.Settings;
import jobapply.persistence.TestStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

class ModelRegistryTest {

    @TempDir
    Path root;

    @Test
    void reportsClearlyWhenNothingIsConfigured() {
        TestStorage storage = TestStorage.create(root, false);
        ModelRegistry registry = new ModelRegistry(new ModelSettingsFile(storage.layout, storage.clock),
                new EnvFile(storage.layout.envFile(), name -> null));

        assertThatThrownBy(registry::main)
                .isInstanceOf(ModelRegistry.NotConfiguredException.class)
                .hasMessageContaining("还没有配置主模型");
        assertThat(registry.auxiliaryOrMain()).isEmpty();
    }

    @Test
    void servesTheAssignedModelAndHotSwapsAfterReload() {
        TestStorage storage = TestStorage.create(root, false);
        ModelSettingsFile file = new ModelSettingsFile(storage.layout, storage.clock);
        EnvFile env = new EnvFile(storage.layout.envFile(), name -> null);
        ModelRegistry registry = new ModelRegistry(file, env);
        try (FakeModelServer first = FakeModelServer.start().toolCalls(false);
             FakeModelServer second = FakeModelServer.start().toolCalls(false)) {
            env.set("ONE_API_KEY", FakeModelServer.OPENAI_KEY);
            file.write(Settings.empty()
                    .withProvider(new Provider("one", "custom", "openai", first.openAiBase(), "${ONE_API_KEY}",
                            List.of("fake-small"), List.of(), Map.of()))
                    .withAssignments(Map.of(ModelSettingsFile.MAIN, new Assignment("one", "fake-small"))), "x");

            ChatModel main = registry.main();
            main.call(new Prompt("hi"));
            assertThat(first.requests()).hasSize(1);
            assertThat(registry.main()).isSameAs(main);
            assertThat(registry.auxiliaryOrMain()).containsSame(main);

            Settings moved = file.read().withProvider(new Provider("one", "custom", "openai", second.openAiBase(),
                    "${ONE_API_KEY}", List.of("fake-small"), List.of(), Map.of()));
            file.write(moved, "x");
            registry.reload();
            registry.main().call(new Prompt("hi"));

            assertThat(registry.main()).isNotSameAs(main);
            assertThat(first.requests()).hasSize(1);
            assertThat(second.requests()).hasSize(1);
            assertThat(second.last().authorization()).isEqualTo("Bearer " + FakeModelServer.OPENAI_KEY);
        }
    }

    @Test
    void staleAssignmentsAndMissingKeysAreErrorsNamingOnlyTheVariable() {
        TestStorage storage = TestStorage.create(root, false);
        ModelSettingsFile file = new ModelSettingsFile(storage.layout, storage.clock);
        ModelRegistry registry = new ModelRegistry(file, new EnvFile(storage.layout.envFile(), name -> null));
        file.write(Settings.empty()
                .withProvider(new Provider("one", "custom", "openai", "http://127.0.0.1:9/v1", "${ONE_API_KEY}",
                        List.of("fake-small"), List.of(), Map.of()))
                .withAssignments(Map.of(ModelSettingsFile.MAIN, new Assignment("one", "fake-small"),
                        ModelSettingsFile.AUXILIARY, new Assignment("one", "gone"))), "x");

        assertThatThrownBy(registry::main).isInstanceOf(EnvFile.MissingVariableException.class)
                .hasMessage("变量 ONE_API_KEY 没有设置");
        assertThatThrownBy(() -> registry.forUsage(ModelSettingsFile.AUXILIARY))
                .isInstanceOf(ModelRegistry.NotConfiguredException.class)
                .hasMessageContaining("gone");
    }
}
