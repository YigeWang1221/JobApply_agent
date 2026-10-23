package jobapply.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 浏览器连接属于应用（用户确认，2026-10-23）：一条连接给所有 Job 用，不会自己关掉再重新申请；连接失败或断开以后
 * 不自动重试（每次重试 Chrome 都会再弹一次确认），等用户发 {@code reconnected}。
 * 对真实的 stdio 子进程 {@link FakePlaywrightMcpServer}；不访问网络，不启动 Chrome，不调用 npx。
 */
class BrowserSessionConnectionTest {

    private static final String FIRST = "2026-10-23-001";
    private static final String SECOND = "2026-10-23-002";

    @TempDir
    Path dir;

    @Test
    void oneConnectionServesEveryJobInTurn() {
        FakeBrowser fake = FakeBrowser.create(dir).form("multi-page");

        try (BrowserSession session = new BrowserSession(fake.launch(Duration.ofSeconds(3)))) {
            session.open(fake.formUrl(), FIRST);
            session.snapshot(FIRST);
            session.open(fake.formUrl(), SECOND);
            session.snapshot(SECOND);
            session.reconnect();
            session.snapshot(SECOND);

            assertThat(fake.starts()).as("两个 Job、一次 reconnected 都只用了一条连接").isEqualTo(1);
        }
    }

    @Test
    void aFailedConnectionIsNotRetriedUntilTheUserReconnects() {
        FakeBrowser fake = FakeBrowser.create(dir).form("multi-page")
                .toolError("Error: async initializeServer: Could not connect to chrome.");

        try (BrowserSession session = new BrowserSession(fake.launch(Duration.ofSeconds(3)))) {
            assertThatThrownBy(() -> session.open(fake.formUrl(), FIRST))
                    .isInstanceOf(BrowserException.Disconnected.class);
            int callsAfterFailure = fake.toolCalls().size();

            assertThatThrownBy(() -> session.open(fake.formUrl(), SECOND))
                    .isInstanceOf(BrowserException.Disconnected.class)
                    .hasMessageContaining("reconnected");
            assertThat(fake.toolCalls()).as("等用户重连期间不再碰 MCP").hasSize(callsAfterFailure);
            assertThat(fake.starts()).isEqualTo(1);
            assertThat(session.needsUser()).isTrue();
            assertThat(session.isConnected()).isFalse();

            fake.toolError(null);
            session.reconnect();
            session.open(fake.formUrl(), SECOND);

            assertThat(fake.starts()).as("用户重连时才建立第二条连接").isEqualTo(2);
            assertThat(session.needsUser()).isFalse();
        }
    }

    @Test
    void aTimeoutWhileWaitingForTheUsersConsentCountsAsDisconnected() {
        FakeBrowser fake = FakeBrowser.create(dir).form("multi-page")
                .toolError("TimeoutError: async initializeServer: Timeout 30000ms exceeded.");

        try (BrowserSession session = new BrowserSession(fake.launch(Duration.ofSeconds(3)))) {
            assertThatThrownBy(() -> session.open(fake.formUrl(), FIRST))
                    .isInstanceOf(BrowserException.Disconnected.class);
            assertThat(session.needsUser()).isTrue();
        }
    }
}
