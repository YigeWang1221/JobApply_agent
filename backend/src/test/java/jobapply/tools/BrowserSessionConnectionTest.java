package jobapply.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 浏览器连接属于应用（用户确认，2026-10-23）：一条共享连接给所有 Job 用（C011：每个 Job 自己一个 MCP 会话），不会自己关掉再重新申请。用过的连接断开后下一次
 * 使用自动重连一次；建立失败（包括那一次重连）以后不再自己重试，等用户点「重新连接」或发下一条消息（C006-fix-browser）。
 * 对真实的子进程 {@link FakePlaywrightMcpServer}（本机回环地址上的 Streamable HTTP）；不访问网络，不启动 Chrome，不调用 npx。
 */
class BrowserSessionConnectionTest {

    private static final String FIRST = "2026-10-23-001";
    private static final String SECOND = "2026-10-23-002";

    @TempDir
    Path dir;

    @Test
    void oneSharedConnectionAndOneSessionPerOwner() {
        FakeBrowser fake = FakeBrowser.create(dir).form("multi-page");

        try (BrowserSession session = new BrowserSession(fake.launch(Duration.ofSeconds(3)))) {
            session.open(fake.formUrl(), FIRST);
            session.snapshot(FIRST);
            session.open(fake.formUrl(), SECOND);
            session.snapshot(SECOND);
            session.reconnect();
            session.snapshot(SECOND);
            session.snapshot(FIRST);

            assertThat(fake.starts()).as("两个 Job、一次 reconnect 都只用了一个服务进程").isEqualTo(1);
            assertThat(fake.sessions()).as("保活会话 + 每个 Job 一个会话，用过的会话一直沿用").hasSize(3);
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
                    .hasMessageContaining("还没有重新连上");
            assertThat(fake.toolCalls()).as("等用户重连期间不再碰 MCP").hasSize(callsAfterFailure);
            assertThat(fake.starts()).isEqualTo(1);
            assertThat(session.state().disconnected()).isTrue();
            assertThat(session.isConnected()).isFalse();

            fake.toolError(null);
            session.reconnect();
            session.open(fake.formUrl(), SECOND);

            assertThat(fake.starts()).as("用户重连时才建立第二条连接").isEqualTo(2);
            assertThat(session.state().connected()).isTrue();
        }
    }

    /**
     * C006-fix-browser 第 1 节：用过的连接断开后，下一次使用自动重连一次；那一次也连不上就停下，不再碰 MCP，
     * 直到用户发消息（{@link BrowserSession#retryOnNextUse()}）或点「重新连接」。状态和连接代数跟着变，每次变化都通知。
     */
    @Test
    void aUsedConnectionReconnectsOnceOnTheNextUseAndThenWaitsForTheUser() {
        FakeBrowser fake = FakeBrowser.create(dir).form("multi-page");
        java.util.List<BrowserState> seen = new java.util.concurrent.CopyOnWriteArrayList<>();

        try (BrowserSession session = new BrowserSession(fake.launch(Duration.ofSeconds(3)))) {
            session.onChange(seen::add);
            session.open(fake.formUrl(), FIRST);
            assertThat(session.state().connected()).isTrue();
            assertThat(session.state().generation()).isEqualTo(1);

            fake.exitOnNextCall();
            assertThatThrownBy(() -> session.snapshot(FIRST)).isInstanceOf(BrowserException.Disconnected.class);
            assertThat(session.state().disconnected()).isTrue();
            assertThat(session.state().reason()).isNotBlank();

            // 下一次使用：自动重连成功，第二次连接，标签页要重新开（ref 不跨连接）
            assertThat(session.ownsTab(FIRST)).isFalse();
            session.open(fake.formUrl(), FIRST);
            assertThat(fake.starts()).isEqualTo(2);
            assertThat(session.state().generation()).isEqualTo(2);

            // 又断了，这次自动重连也连不上
            fake.exitOnNextCall();
            assertThatThrownBy(() -> session.snapshot(FIRST)).isInstanceOf(BrowserException.Disconnected.class);
            fake.toolError("Error: async initializeServer: Could not connect to chrome.");
            assertThatThrownBy(() -> session.open(fake.formUrl(), FIRST)).isInstanceOf(BrowserException.Disconnected.class);
            assertThat(fake.starts()).isEqualTo(3);
            int calls = fake.toolCalls().size();

            // 之后不在后台、也不在每次使用时反复重试
            assertThatThrownBy(() -> session.open(fake.formUrl(), SECOND))
                    .isInstanceOf(BrowserException.Disconnected.class);
            assertThat(fake.starts()).isEqualTo(3);
            assertThat(fake.toolCalls()).hasSize(calls);
            assertThat(session.state().disconnected()).isTrue();

            // 用户打开开关、发了下一条消息：再试一次
            fake.toolError(null);
            session.retryOnNextUse();
            session.open(fake.formUrl(), FIRST);
            assertThat(fake.starts()).isEqualTo(4);
            assertThat(session.state().generation()).isEqualTo(3);

            // 连着的时候「重新连接」不动连接
            assertThat(session.reconnect().generation()).isEqualTo(3);
            assertThat(fake.starts()).isEqualTo(4);
        }
        assertThat(seen).extracting(BrowserState::state).containsSubsequence(
                BrowserState.CONNECTING, BrowserState.CONNECTED, BrowserState.DISCONNECTED,
                BrowserState.CONNECTING, BrowserState.CONNECTED, BrowserState.DISCONNECTED,
                BrowserState.CONNECTING, BrowserState.DISCONNECTED,
                BrowserState.CONNECTING, BrowserState.CONNECTED);
    }

    @Test
    void aTimeoutWhileWaitingForTheUsersConsentCountsAsDisconnected() {
        FakeBrowser fake = FakeBrowser.create(dir).form("multi-page")
                .toolError("TimeoutError: async initializeServer: Timeout 30000ms exceeded.");

        try (BrowserSession session = new BrowserSession(fake.launch(Duration.ofSeconds(3)))) {
            assertThatThrownBy(() -> session.open(fake.formUrl(), FIRST))
                    .isInstanceOf(BrowserException.Disconnected.class);
            assertThat(session.state().disconnected()).isTrue();
        }
    }
}
