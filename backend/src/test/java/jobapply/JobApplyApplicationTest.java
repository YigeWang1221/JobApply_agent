package jobapply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import jobapply.persistence.StorageLayout;
import jobapply.tools.BrowserSession;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.spring.ai.serializer.std.SpringAIStateSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 用默认配置（application.yml）启动真实的 Web 服务器，和在 backend/ 下正常启动一样。
 * 启动时不连接 Chrome，也不读写 storage。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JobApplyApplicationTest {

    @Autowired
    StorageLayout storage;

    @Autowired
    BrowserSession browser;

    @Test
    void contextStartsWithoutModelCredentials() {
        assertNotNull(StateGraph.class);
        assertNotNull(SpringAIStateSerializer.class);
        assertNotNull(ChatClient.class);
    }

    @Test
    void defaultStorageRootIsResolvedAgainstTheWorkingDirectoryAndNothingConnects() {
        assertThat(storage.root()).isEqualTo(Path.of("../storage").toAbsolutePath().normalize());
        assertThat(browser.isConnected()).isFalse();
    }
}
