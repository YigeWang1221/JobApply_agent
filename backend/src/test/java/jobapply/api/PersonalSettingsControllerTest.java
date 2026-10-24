package jobapply.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 设置页「敏感信息」的 HTTP 接口（C014-D）：值写进临时 storage 的 personal.env（权限 600），base 资料里自动登记占位符；
 * 每个响应体都检查过不含值。值全部虚构。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PersonalSettingsControllerTest {

    private static final String BASE = "/api/settings/personal";
    private static final String VALUE = "ZX-PASS-90210";

    @TempDir
    static Path root;

    @LocalServerPort
    int port;

    private final List<String> bodies = new ArrayList<>();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jobapply.storage.root", () -> root.resolve("storage").toString());
    }

    @AfterEach
    void noResponseEverContainsTheValue() {
        assertThat(bodies).isNotEmpty().allSatisfy(body -> assertThat(body).doesNotContain(VALUE));
    }

    private LocalApi.Response record(LocalApi.Response response) {
        bodies.add(response.body());
        return response;
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveShowsOnlySetRegistersThePlaceholderAndClearKeepsIt() throws Exception {
        LocalApi api = new LocalApi(port);

        LocalApi.Response before = record(api.get(BASE));
        assertThat(before.status()).isEqualTo(200);
        List<Map<String, Object>> keys = (List<Map<String, Object>>) before.get("keys");
        assertThat(keys).extracting(row -> row.get("key")).contains("ID_NUMBER", "PASSPORT_NUMBER", "EXPECTED_SALARY");
        assertThat(keys).allSatisfy(row -> assertThat(row).containsEntry("set", false).doesNotContainKey("value"));

        LocalApi.Response saved = record(api.putJson(BASE + "/PASSPORT_NUMBER", Map.of("value", " " + VALUE + " ")));
        assertThat(saved.status()).isEqualTo(200);
        assertThat(saved.json()).containsEntry("key", "PASSPORT_NUMBER").containsEntry("set", true);

        Path env = root.resolve("storage/settings/personal.env");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(env))).isEqualTo("rw-------");
        assertThat(Files.readString(env)).isEqualTo("PASSPORT_NUMBER=" + VALUE + "\n");
        String facts = Files.readString(root.resolve("storage/base/facts.md"));
        assertThat(facts).contains("## sensitive.passport_number").contains("- value: {PASSPORT_NUMBER}")
                .contains("- class: C").doesNotContain(VALUE);

        // 自己加的键，带名字
        LocalApi.Response custom = record(api.putJson(BASE + "/HUKOU_CITY", Map.of("value", "虚构城市",
                "label", "户口所在地")));
        assertThat(custom.status()).isEqualTo(200);
        assertThat(custom.json()).containsEntry("preset", false).containsEntry("zh", "户口所在地");

        LocalApi.Response view = record(api.get(BASE));
        assertThat((List<Map<String, Object>>) view.get("keys")).filteredOn(row -> "PASSPORT_NUMBER".equals(row.get("key")))
                .singleElement().satisfies(row -> assertThat(row).containsEntry("set", true));

        // 键名不合法、值为空：400
        assertThat(record(api.putJson(BASE + "/bad-key", Map.of("value", "x"))).status()).isEqualTo(400);
        assertThat(record(api.putJson(BASE + "/ID_NUMBER", Map.of("value", "  "))).status()).isEqualTo(400);

        LocalApi.Response cleared = record(api.delete(BASE + "/PASSPORT_NUMBER"));
        assertThat(cleared.status()).isEqualTo(200);
        assertThat(cleared.json()).containsEntry("set", false);
        assertThat(Files.readString(env)).doesNotContain("PASSPORT_NUMBER");
        assertThat(Files.readString(root.resolve("storage/base/facts.md"))).contains("{PASSPORT_NUMBER}");
    }
}
