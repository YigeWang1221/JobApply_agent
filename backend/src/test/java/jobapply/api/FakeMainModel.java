package jobapply.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import jobapply.config.FakeModelServer;

/**
 * 测试用：像用户在设置页那样，把本机假服务端配置成主模型（加提供方、填 Key、测试工具调用、分配主模型），
 * 之后让它按请求内容回复文字。Key 是测试自己生成的假 Key，写进临时 storage。
 */
final class FakeMainModel {

    private static final String BASE = "/api/settings/models";

    private FakeMainModel() {
    }

    static void configure(LocalApi api, FakeModelServer server) {
        server.toolCalls(true);
        assertThat(api.putJson(BASE + "/providers/fake-gw", Map.of("preset", "custom", "format", "openai",
                "baseUrl", server.openAiBase(), "models", List.of("fake-large"))).status()).isEqualTo(200);
        assertThat(api.putJson(BASE + "/providers/fake-gw/key", Map.of("key", FakeModelServer.OPENAI_KEY)).status())
                .isEqualTo(200);
        assertThat(api.postJson(BASE + "/providers/fake-gw/test", Map.of("model", "fake-large")).status())
                .isEqualTo(200);
        assertThat(api.putJson(BASE + "/assignments",
                Map.of("main", Map.of("provider", "fake-gw", "model", "fake-large"))).status()).isEqualTo(200);
        server.toolCalls(false);
    }
}
