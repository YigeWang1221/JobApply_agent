package jobapply.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import jobapply.harness.policies.BrowserToolPolicy;
import jobapply.harness.policies.FieldIntent;
import jobapply.harness.policies.SubmitGuard;
import jobapply.harness.policies.ToolNotAllowedException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class BrowserToolsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path UPLOADS = Path.of("/tmp/jobapply-test/storage/uploads/2026-10-23-001");
    private static final SnapshotIndex LATEST = SnapshotIndex.parse("""
            - textbox "姓名" [ref=e3]
            - textbox "邮箱" [ref=e4]
            - combobox "学历" [ref=e13]
            - checkbox "同意隐私政策" [ref=e15]
            - button "下一步" [ref=e9]""");
    private static final BrowserToolPolicy.Context MODEL =
            BrowserToolPolicy.Context.model(LATEST, List.of("https://careers.example.com/apply/1"), UPLOADS);

    @Test
    void definesExactlyTheModelWhitelistAndNeverExecutes() {
        List<ToolCallback> tools = BrowserTools.definitions();

        assertThat(tools).extracting(tool -> tool.getToolDefinition().name()).containsExactlyInAnyOrder(
                BrowserToolPolicy.SNAPSHOT, BrowserToolPolicy.FIND, BrowserToolPolicy.NAVIGATE,
                BrowserToolPolicy.CLICK, BrowserToolPolicy.TYPE, BrowserToolPolicy.FILL_FORM,
                BrowserToolPolicy.SELECT_OPTION, BrowserToolPolicy.SET_DATE, BrowserToolPolicy.FILE_UPLOAD,
                BrowserToolPolicy.PRESS_KEY, BrowserToolPolicy.WAIT_FOR);
        for (ToolCallback tool : tools) {
            JsonNode schema = JSON.readTree(tool.getToolDefinition().inputSchema());
            assertThat(schema.get("type").asString()).isEqualTo("object");
            assertThatThrownBy(() -> tool.call("{}")).isInstanceOf(IllegalStateException.class);
        }
        assertThat(BrowserTools.isBrowserTool("browser_handle_dialog")).isFalse();
        assertThat(BrowserTools.isBrowserTool("browser_tabs")).isFalse();
    }

    @Test
    void factAndConfidenceAreOptionalAndFileUploadOnlyTakesNumbers() {
        Map<String, JsonNode> schemas = schemas();

        assertThat(schemas.get(BrowserToolPolicy.TYPE).get("required").toString())
                .doesNotContain("\"fact\"").doesNotContain("\"confidence\"");
        assertThat(schemas.get(BrowserToolPolicy.TYPE).get("properties").has("fact")).isTrue();
        assertThat(schemas.get(BrowserToolPolicy.FILL_FORM).at("/properties/fields/items/properties").has("language"))
                .isTrue();
        assertThat(schemas.get(BrowserToolPolicy.CLICK).get("properties").has(SubmitGuard.ClickIntent.PURPOSE))
                .isTrue();
        JsonNode upload = schemas.get(BrowserToolPolicy.FILE_UPLOAD).get("properties");
        assertThat(upload.has("files")).isTrue();
        assertThat(upload.has("paths")).isFalse();
    }

    @Test
    void fillFormFieldsLoseTheExtraKeysBeforeTheWhitelist() {
        String args = """
                {"fields":[
                  {"target":"e3","name":"姓名","type":"textbox","value":"示例姓名","fact":"base.name","confidence":"high"},
                  {"target":"e4","name":"Email","type":"textbox","value":"demo@example.com","fact":"base.email",
                   "confidence":"medium","language":"en"}]}""";

        BrowserTools.Call call = BrowserTools.translate(BrowserToolPolicy.FILL_FORM, args, List.of());

        assertThat(call.tool()).isEqualTo(BrowserToolPolicy.FILL_FORM);
        assertThat(call.write()).isTrue();
        assertThat(call.fields()).containsExactly(
                new FieldIntent("e3", "姓名", "base.name", "high", null),
                new FieldIntent("e4", "Email", "base.email", "medium", "en"));
        assertThat(call.args().toString()).doesNotContain("fact").doesNotContain("confidence")
                .doesNotContain("language");
        assertThatCode(() -> BrowserToolPolicy.check(call.tool(), call.args(), MODEL)).doesNotThrowAnyException();
        // 没有去掉时白名单会拒绝
        assertThatThrownBy(() -> BrowserToolPolicy.check(BrowserToolPolicy.FILL_FORM,
                JSON.readValue(args, Map.class), MODEL)).isInstanceOf(ToolNotAllowedException.class);
    }

    @Test
    void typeAndSelectCarryOneFieldIntent() {
        BrowserTools.Call type = BrowserTools.translate(BrowserToolPolicy.TYPE,
                "{\"target\":\"e3\",\"element\":\"姓名\",\"text\":\"示例姓名\",\"fact\":\"none\",\"confidence\":\"low\"}",
                List.of());
        BrowserTools.Call select = BrowserTools.translate(BrowserToolPolicy.SELECT_OPTION,
                "{\"target\":\"e13\",\"element\":\"学历\",\"values\":[\"硕士\"],\"fact\":\"q-1\",\"confidence\":\"high\"}",
                List.of());

        assertThat(type.fields()).singleElement().satisfies(field -> assertThat(field.inferred()).isTrue());
        assertThat(type.args()).containsOnlyKeys("target", "element", "text");
        assertThat(select.fields()).singleElement().satisfies(field -> assertThat(field.fact()).isEqualTo("q-1"));
        assertThat(select.args()).containsOnlyKeys("target", "element", "values");
        assertThatCode(() -> BrowserToolPolicy.check(type.tool(), type.args(), MODEL)).doesNotThrowAnyException();
        assertThatCode(() -> BrowserToolPolicy.check(select.tool(), select.args(), MODEL)).doesNotThrowAnyException();
    }

    @Test
    void fieldsWithoutFactOrConfidenceAreAcceptedAndBadArgumentsAreRefused() {
        BrowserTools.Call call = BrowserTools.translate(BrowserToolPolicy.TYPE, "{\"target\":\"e3\",\"text\":\"x\"}",
                List.of());

        assertThat(call.fields()).singleElement().satisfies(field -> assertThat(field.inferred()).isTrue());
        assertThatThrownBy(() -> BrowserTools.translate(BrowserToolPolicy.TYPE,
                "{\"target\":\"e3\",\"text\":\"x\",\"language\":\"fr\"}", List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("language");
        assertThatThrownBy(() -> BrowserTools.translate(BrowserToolPolicy.TYPE, "not json", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BrowserTools.translate("browser_evaluate", "{}", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clickIntentIsTakenOut() {
        BrowserTools.Call call = BrowserTools.translate(BrowserToolPolicy.CLICK, """
                {"target":"e9","element":"下一步","purpose":"next_step","final_step_suspected":false,
                 "evidence":"第 1/3 步"}""", List.of());

        assertThat(call.click()).isEqualTo(new SubmitGuard.ClickIntent("next_step", false, "第 1/3 步"));
        assertThat(call.args()).containsOnlyKeys("target", "element");
        assertThat(call.fields()).isEmpty();
    }

    @Test
    void fileNumbersBecomePathsInsideTheUploadsDir() {
        List<Path> files = List.of(UPLOADS.resolve("resume-demo.pdf"), UPLOADS.resolve("portfolio-demo.pdf"));

        BrowserTools.Call call = BrowserTools.translate(BrowserToolPolicy.FILE_UPLOAD, "{\"files\":[2]}", files);

        assertThat(call.fileNumbers()).containsExactly(2);
        assertThat(call.args()).containsOnlyKeys("paths");
        assertThat(call.args().get("paths")).isEqualTo(List.of(files.get(1).toString()));
        assertThatCode(() -> BrowserToolPolicy.check(call.tool(), call.args(), MODEL)).doesNotThrowAnyException();

        BrowserTools.Call cancel = BrowserTools.translate(BrowserToolPolicy.FILE_UPLOAD, "{}", files);
        assertThat(cancel.args()).isEmpty();
        assertThatThrownBy(() -> BrowserTools.translate(BrowserToolPolicy.FILE_UPLOAD, "{\"files\":[3]}", files))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1–2");
        assertThatThrownBy(() -> BrowserTools.translate(BrowserToolPolicy.FILE_UPLOAD, "{\"files\":[0]}", files))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BrowserTools.translate(BrowserToolPolicy.FILE_UPLOAD,
                "{\"files\":[\"/etc/passwd\"]}", files)).isInstanceOf(IllegalArgumentException.class);
        // 模型自己带的 paths 不会被换掉，照样交给白名单拒绝
        BrowserTools.Call sneaky = BrowserTools.translate(BrowserToolPolicy.FILE_UPLOAD,
                "{\"paths\":[\"/etc/passwd\"]}", files);
        assertThatThrownBy(() -> BrowserToolPolicy.check(sneaky.tool(), sneaky.args(), MODEL))
                .isInstanceOf(ToolNotAllowedException.class);
    }

    @Test
    void readOnlyCallsPassThrough() {
        BrowserTools.Call snapshot = BrowserTools.translate(BrowserToolPolicy.SNAPSHOT, "", List.of());
        BrowserTools.Call find = BrowserTools.translate(BrowserToolPolicy.FIND, "{\"text\":\"下一步\"}", List.of());

        assertThat(snapshot.args()).isEmpty();
        assertThat(snapshot.write()).isFalse();
        assertThat(find.args()).containsEntry("text", "下一步");
        assertThat(find.write()).isFalse();
    }

    private static Map<String, JsonNode> schemas() {
        Map<String, JsonNode> schemas = new java.util.LinkedHashMap<>();
        for (ToolCallback tool : BrowserTools.definitions()) {
            schemas.put(tool.getToolDefinition().name(), JSON.readTree(tool.getToolDefinition().inputSchema()));
        }
        return schemas;
    }
}
