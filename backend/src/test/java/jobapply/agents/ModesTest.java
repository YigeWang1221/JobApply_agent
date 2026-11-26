package jobapply.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import jobapply.persistence.StorageLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** C030-fix：storage/modes 里的同名文件覆盖内置的；新加的子 Mode 能被 subagents: 找到；写错时报出文件名。 */
class ModesTest {

    @TempDir
    Path dir;

    @Test
    void storageFileOverridesBuiltInAndNewSubModeResolves() throws Exception {
        StorageLayout layout = new StorageLayout(dir);
        assertThat(Modes.get(layout, Modes.SESSION).source()).isEqualTo("内置 modes/session.md");

        Files.createDirectories(layout.modesDir());
        Files.writeString(layout.modesDir().resolve("company-notes.md"), """
                ---
                description: 新的子任务
                tools: [research]
                allow-tools: [web_search]
                ---
                只查公司资料。
                """);
        Files.writeString(layout.modesDir().resolve("session.md"), """
                ---
                description: 改过的 Session
                tools: [research, delegate]
                subagents: [web-research, company-notes]
                ---
                新的提示词。
                """);

        Mode session = Modes.get(layout, Modes.SESSION);
        assertThat(session.system()).isEqualTo("新的提示词。");
        assertThat(session.subagents()).containsExactly("web-research", "company-notes");
        assertThat(Modes.get(layout, "company-notes").description()).isEqualTo("新的子任务");

        Files.writeString(layout.modesDir().resolve("session.md"), """
                ---
                tools: [research]
                subagents: [nowhere]
                ---
                x
                """);
        assertThatThrownBy(() -> Modes.get(layout, Modes.SESSION))
                .hasMessageContaining(layout.modesDir().resolve("session.md").toString())
                .hasMessageContaining("nowhere");
    }
}
