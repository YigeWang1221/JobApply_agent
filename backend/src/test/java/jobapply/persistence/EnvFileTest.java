package jobapply.persistence;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * .env 写在 {@code @TempDir} 里，值是测试自己生成的假 Key。
 */
class EnvFileTest {

    private static final String KEY = "sk-fake-test-0000-1111-2222-abcd";

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("settings/.env");
    }

    private EnvFile env() {
        return new EnvFile(file(), name -> null);
    }

    @Test
    void writesWithOwnerOnlyPermissions() throws Exception {
        env().set("DEMO_API_KEY", KEY);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file()))).isEqualTo("rw-------");
        assertThat(Files.readString(file(), UTF_8)).isEqualTo("DEMO_API_KEY=" + KEY + "\n");
        try (var leftovers = Files.list(file().getParent())) {
            assertThat(leftovers.map(p -> p.getFileName().toString())).containsExactly(".env");
        }
    }

    @Test
    void updatesAndRemovesVariablesKeepingOtherLines() throws Exception {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "# 用户的注释\nOTHER_API_KEY=keep-me\nDEMO_API_KEY=old\n", UTF_8);
        EnvFile env = env();

        env.set("DEMO_API_KEY", "  " + KEY + "  ");
        assertThat(env.get("DEMO_API_KEY")).contains(KEY);
        assertThat(Files.readString(file(), UTF_8)).isEqualTo("# 用户的注释\nOTHER_API_KEY=keep-me\nDEMO_API_KEY=" + KEY + "\n");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file()))).isEqualTo("rw-------");

        env.remove("DEMO_API_KEY");
        assertThat(env.has("DEMO_API_KEY")).isFalse();
        assertThat(Files.readString(file(), UTF_8)).isEqualTo("# 用户的注释\nOTHER_API_KEY=keep-me\n");
        env.remove("NOT_THERE");
    }

    @Test
    void readsQuotedAndExportedValuesWrittenByHand() throws Exception {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "export A_KEY=\"quoted\"\nB_KEY='single'\n", UTF_8);

        assertThat(env().get("A_KEY")).contains("quoted");
        assertThat(env().get("B_KEY")).contains("single");
    }

    @Test
    void resolvesPlaceholdersFromTheFileThenTheProcessEnvironment() {
        EnvFile env = new EnvFile(file(), Map.of("FROM_SHELL", "shell-value", "DEMO_API_KEY", "shell-loses")::get);
        env.set("DEMO_API_KEY", KEY);

        assertThat(env.resolve("${DEMO_API_KEY}")).isEqualTo(KEY);
        assertThat(env.resolve("${FROM_SHELL}")).isEqualTo("shell-value");
        assertThat(env.resolve("https://${FROM_SHELL}.example.com/v1")).isEqualTo("https://shell-value.example.com/v1");
        assertThat(env.resolve("https://api.example.com")).isEqualTo("https://api.example.com");
        assertThat(env.resolve(null)).isNull();
        assertThat(EnvFile.placeholders("${A}/${B_C}")).containsExactly("A", "B_C");
    }

    @Test
    void missingVariablesReportOnlyTheName() {
        assertThatThrownBy(() -> env().resolve("${NOT_SET_API_KEY}"))
                .isInstanceOf(EnvFile.MissingVariableException.class)
                .hasMessage("变量 NOT_SET_API_KEY 没有设置");
        assertThatThrownBy(() -> env().resolve("${bad-name}")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "a\nb", "a=b", "a#b", "a\rb"})
    void rejectsBadKeysWithoutEchoingThem(String value) {
        assertThatThrownBy(() -> env().set("DEMO_API_KEY", value))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> {
                    if (!value.isBlank()) {
                        assertThat(e.getMessage()).doesNotContain(value);
                    }
                });
        assertThat(file()).doesNotExist();
    }

    @Test
    void rejectsTooLongKeysAndBadNames() {
        assertThatThrownBy(() -> env().set("DEMO_API_KEY", "x".repeat(513))).isInstanceOf(IllegalArgumentException.class);
        env().set("DEMO_API_KEY", "x".repeat(512));
        assertThatThrownBy(() -> env().set("demo", KEY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> env().set("A=B", KEY)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void valuesNeverAppearInToStringOrErrors() {
        EnvFile env = env();
        env.set("DEMO_API_KEY", KEY);

        assertThat(env.toString()).doesNotContain(KEY).contains(".env");
        assertThatThrownBy(() -> env.resolve("${DEMO_API_KEY}${OTHER}"))
                .hasMessageNotContaining(KEY);
    }
}
