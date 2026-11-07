package jobapply.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** C028：令牌文件是 600、toString 不带令牌、退出后注册信息保留。 */
class ChatGptTokenStoreTest {

    @Test
    void tokensAreOwnerOnlyAndRedacted(@TempDir Path dir) throws Exception {
        ChatGptTokenStore store = new ChatGptTokenStore(new StorageLayout(dir));
        ChatGptTokenStore.Tokens tokens = new ChatGptTokenStore.Tokens("oaiapp_x", "fake-access-value",
                "fake-refresh-value", 123, "openid chatgpt.tokens.use.direct", "someone@example.com", "sub-1");
        store.write(tokens);

        Path file = new StorageLayout(dir).chatGptAuthFile();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        assertThat(store.read()).contains(tokens);
        assertThat(tokens.toString()).doesNotContain("fake-access-value", "fake-refresh-value", "someone@");

        String hostId = store.registration().hostId();
        store.saveClientId("oaiapp_x");
        store.delete();
        assertThat(Files.exists(file)).isFalse();
        assertThat(store.read()).isEmpty();
        assertThat(store.registration()).isEqualTo(new ChatGptTokenStore.Registration("oaiapp_x", hostId));
        assertThat(hostId).startsWith("urn:uuid:");
    }
}
