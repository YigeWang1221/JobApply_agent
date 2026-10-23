package jobapply.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import jobapply.config.FakeModelServer;
import jobapply.memory.ImportFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 「事实与画像」的接口。事实层直接写进临时 storage（虚构数据）；模型是本机假服务端。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProfileControllerTest {

    static final String PRIVATE_NOTE = "PRIVATE-FACT-TEXT-NOT-IN-ERRORS";

    @TempDir
    static Path root;

    static final FakeModelServer MODEL = FakeModelServer.start().toolCalls(false).respond(ImportFixture::answer);

    @LocalServerPort
    int port;

    private LocalApi api;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jobapply.storage.root", () -> root.resolve("storage").toString());
    }

    @AfterAll
    static void stop() {
        MODEL.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        api = new LocalApi(port);
        Path base = Files.createDirectories(root.resolve("storage/base/projects"));
        Files.deleteIfExists(base.resolve("../profile.md"));
        Files.writeString(base.resolve("../facts.md"), """
                ---
                tags: [jobapply/facts]
                ---

                # 个人基础事实

                ## contact.name
                - zh: 林虚构
                - class: B
                - source: user-confirmed
                - reviewed: zh
                - confirmed_at: 2026-10-01

                ## skills.backend
                - zh: Java、%s
                - en: Java
                - class: A
                - source: user-confirmed
                - reviewed: zh, en
                - confirmed_at: 2026-10-01

                ## experience.demo-co.start
                - value: 2025-06
                - class: A
                - source: user-confirmed
                - confirmed_at: 2026-10-01
                """.formatted(PRIVATE_NOTE));
        Files.writeString(base.resolve("demo-recsys.md"), """
                ---
                slug: demo-recsys
                title: {zh: 示例推荐服务}
                stack: [Java]
                reviewed: [zh]
                ---

                ## zh

                ### 一句话 {#summary}
                课程里的推荐服务。

                ### 我的职责 {#role}
                负责召回。
                """);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    void browseFactsAndProjects() {
        LocalApi.Response profile = api.get("/api/profile");

        assertThat(profile.status()).isEqualTo(200);
        Map<?, ?> groups = (Map<?, ?>) profile.get("groups");
        assertThat((List<?>) groups.get("basic")).singleElement().satisfies(fact -> assertThat(map(fact))
                .containsEntry("key", "contact.name").containsEntry("zh", "林虚构").containsEntry("class", "B")
                .containsEntry("reviewed", List.of("zh")).containsEntry("sourceDeleted", false));
        assertThat((List<?>) groups.get("skills")).hasSize(1);
        assertThat((List<?>) groups.get("experience")).hasSize(1);
        assertThat((List<?>) profile.get("projects")).singleElement().satisfies(card -> assertThat(map(card))
                .containsEntry("slug", "demo-recsys").containsEntry("reviewed", List.of("zh")));
        assertThat(profile.get("summary")).isNull();
    }

    @Test
    void editAFactAndAProjectCard() {
        LocalApi.Response fact = api.putJson("/api/profile/facts/contact.name", Map.of("en", "Lin Xugou"));
        assertThat(fact.status()).isEqualTo(200);
        assertThat(fact.json()).containsEntry("en", "Lin Xugou").containsEntry("reviewed", List.of("zh", "en"));

        LocalApi.Response unmarked = api.putJson("/api/profile/facts/contact.name", Map.of("reviewed", List.of("zh")));
        assertThat(unmarked.json()).containsEntry("reviewed", List.of("zh"));

        LocalApi.Response card = api.putJson("/api/profile/projects/demo-recsys",
                Map.of("title", Map.of("en", "Demo Recommender"), "en", Map.of("summary", "A course recommender.")));
        assertThat(card.status()).isEqualTo(200);
        assertThat(card.json()).containsEntry("reviewed", List.of("zh", "en"));
        // 英文小节按中文已有的小节建（两种语言要一一对应）
        assertThat((List<?>) card.get("en")).hasSize(2);

        assertThat(api.putJson("/api/profile/facts/contact.unknown", Map.of("zh", "x")).status()).isEqualTo(404);
        assertThat(api.putJson("/api/profile/projects/no-such-card", Map.of()).status()).isEqualTo(404);
        assertThat(api.putJson("/api/profile/projects/Bad_Slug", Map.of()).status()).isEqualTo(400);
        LocalApi.Response classC = api.putJson("/api/profile/facts/skills.backend", Map.of("zh", "签证：H1B"));
        assertThat(classC.status()).isEqualTo(400);
        assertThat(classC.body()).doesNotContain(PRIVATE_NOTE);
        assertThat(api.putJson("/api/profile/facts/contact.name", Map.of("en", "x"), "Origin",
                "https://evil.example.com").status()).isEqualTo(403);
        assertThat(api.post("/api/profile/summary", "text/plain", "x").status()).isEqualTo(415);
    }

    @Test
    void generateAndConfirmTheSummary() {
        LocalApi.Response noModel = api.postJson("/api/profile/summary", Map.of());
        assertThat(noModel.status()).isEqualTo(502);
        assertThat((String) noModel.get("error")).contains("模型不可用");

        FakeMainModel.configure(api, MODEL);
        MODEL.respond(body -> "不是 JSON");
        LocalApi.Response bad = api.postJson("/api/profile/summary", Map.of());
        assertThat(bad.status()).isEqualTo(502);
        assertThat(bad.body()).contains("JSON 解析失败").doesNotContain(PRIVATE_NOTE);
        MODEL.respond(ImportFixture::answer);

        LocalApi.Response candidate = api.postJson("/api/profile/summary", Map.of());
        assertThat(candidate.status()).isEqualTo(200);
        assertThat((List<?>) candidate.get("zh")).hasSize(4);
        assertThat(candidate.get("current")).isNull();
        assertThat(api.get("/api/profile").get("summary")).isNull();

        Map<String, String> zh = Map.of("directions", "- 后端", "skills", "- Java", "highlights", "- 一条",
                "positioning", "我写的定位。");
        Map<String, String> en = Map.of("directions", "- Backend", "skills", "- Java", "highlights", "- One",
                "positioning", "Mine.");
        LocalApi.Response saved = api.putJson("/api/profile/summary", Map.of("zh", zh, "en", en,
                "userEdited", List.of("zh.positioning"), "reviewed", List.of("zh", "en")));
        assertThat(saved.status()).isEqualTo(200);
        assertThat(saved.json()).containsEntry("userEdited", List.of("zh.positioning"))
                .containsEntry("reviewed", List.of("zh", "en"));
        assertThat(map(api.get("/api/profile").get("summary"))).containsEntry("generatedAt",
                saved.get("generatedAt"));

        assertThat(api.putJson("/api/profile/summary", Map.of("zh", Map.of("extra", "x"), "en", en)).status())
                .isEqualTo(400);
    }
}
