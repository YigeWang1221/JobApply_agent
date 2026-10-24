package jobapply.harness.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import jobapply.config.HarnessProperties;
import jobapply.config.PersonalSettingsService;
import jobapply.domain.Actor;
import jobapply.domain.AgentRun;
import jobapply.domain.JobPosting;
import jobapply.persistence.PersonalEnv;
import jobapply.persistence.TestStorage;
import jobapply.tools.BrowserSession;
import jobapply.tools.FakeBrowser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import tools.jackson.databind.json.JsonMapper;

/**
 * C 级占位符（C014-D）：资料里登记 {@code {ID_NUMBER}}、personal.env 里有值时，申请 Agent 填占位符，真实值只出现在发给
 * 假浏览器 MCP 的请求里；模型请求、过程日志、填写报告、对话和 storage 里别的文件都只有占位符。没登记的 C 级明文照旧被拒绝；
 * 登记了但没有值的占位符被拒绝，结果写明「没有值」。页面上出现真实值时（用户在别的框里亲手填了）也换回占位符。
 * storage 在临时目录，值全部虚构。
 */
class PersonalPlaceholderTest {

    private static final String PROJECT = "demo";
    /** 虚构的值。 */
    private static final String REAL = "ZX-ID-48151623";

    @TempDir
    Path dir;

    @Test
    void theRealValueOnlyReachesTheBrowserAndPlaintextClassCIsStillRefused() throws Exception {
        TestStorage storage = TestStorage.create(dir.resolve("storage"));
        storage.project(PROJECT, "live");
        String session = storage.session(PROJECT);
        PersonalSettingsService settings = new PersonalSettingsService(storage.layout, storage.clock);
        settings.set("ID_NUMBER", REAL, null);
        // 登记过、后来清掉了值
        settings.set("WORK_AUTHORIZATION", "虚构的授权", null);
        settings.clear("WORK_AUTHORIZATION");
        assertThat(storage.read(storage.layout.baseFacts())).contains("{ID_NUMBER}").contains("{WORK_AUTHORIZATION}")
                .doesNotContain(REAL);
        assertThat(settings.view().keys()).allSatisfy(row -> assertThat(row.toString()).doesNotContain(REAL));

        FakeBrowser fake = FakeBrowser.create(dir.resolve("browser")).form("c-class");
        // 用户在「姓名」框里亲手填了证件号：快照交出来之前就换回占位符
        fake.userEdit("basic", "e2", REAL);
        String job = storage.ledger().addLead(PROJECT, session, Actor.USER, new JobPosting("示例科技", "2027 校园招聘",
                "Backend Engineer", "JD-900", "campus-fulltime", List.of("上海"), fake.formUrl(), null,
                "self-campus", "user-link"), null).id();

        ScriptedModel model = new ScriptedModel(
                call("browser_fill_form", Map.of("fields", List.of(
                        Map.of("target", "e2", "name", "姓名", "type", "textbox", "value", "林示例", "fact", "none"),
                        Map.of("target", "e4", "name", "身份证号", "type", "textbox", "value", "{ID_NUMBER}",
                                "fact", "sensitive.id_number")))),
                call("browser_fill_form", Map.of("fields", List.of(
                        Map.of("target", "e8", "name", "期望薪资（月薪，元）", "type", "textbox", "value", "30000")))),
                call("browser_select_option", Map.of("target", "e5", "element", "是否已获得工作授权",
                        "values", List.of("{WORK_AUTHORIZATION}"))),
                text("能填的都填了，工作授权请你亲自选。"));

        AgentRun run;
        try (BrowserSession browser = new BrowserSession(fake.launch(), new PersonalEnv(storage.layout)::values)) {
            HarnessLoop loop = new HarnessLoop(model, browser, storage.layout, storage.clock,
                    HarnessProperties.defaults());
            run = loop.run(AgentRun.start(PROJECT, job, session, "示例科技", "Backend Engineer", fake.formUrl(),
                    "开始申请。"), () -> false, ignored -> {
                    });
        }

        // 只有第一次填写发给了浏览器，证件号是真实值；明文薪资和没有值的占位符都没有发
        List<Map<String, Object>> fills = fake.calls("browser_fill_form");
        assertThat(fills).hasSize(1);
        assertThat(fills.getFirst().toString()).contains(REAL).doesNotContain("{ID_NUMBER}");
        assertThat(fake.records().toString()).doesNotContain("30000");
        assertThat(fake.calls("browser_select_option")).isEmpty();

        // 模型看到的只有占位符；拒绝的原因写明没有值
        String prompts = String.join("\n", model.prompts);
        assertThat(prompts).doesNotContain(REAL).contains("{ID_NUMBER}")
                .contains("{WORK_AUTHORIZATION} 在 personal.env 里没有值")
                .contains("「期望薪资（月薪，元）」是 C 级字段");
        assertThat(model.prompts.get(0)).contains("textbox \"姓名\" [ref=e2]: {ID_NUMBER}");

        // 对话、报告、过程日志和 storage 里除了 personal.env 以外的文件都没有真实值
        assertThat(JsonMapper.builder().build().writeValueAsString(run)).doesNotContain(REAL);
        assertThat(storage.read(storage.layout.fillReport(PROJECT, job))).contains("{ID_NUMBER}").doesNotContain(REAL);
        assertThat(storage.read(storage.layout.runLog(PROJECT, job))).doesNotContain(REAL);
        storage.snapshot().forEach((file, content) -> {
            if (!file.equals("settings/personal.env")) {
                assertThat(content).as(file).doesNotContain(REAL);
            }
        });
        assertThat(storage.read(storage.layout.personalEnvFile())).contains("ID_NUMBER=" + REAL);
    }

    private static AssistantMessage call(String tool, Map<String, Object> args) {
        return AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                "call-" + tool + "-" + args.hashCode(), "function", tool,
                JsonMapper.builder().build().writeValueAsString(args)))).build();
    }

    private static AssistantMessage text(String text) {
        return new AssistantMessage(text);
    }

    /** 按顺序回复；记下每次请求里所有消息的文字。 */
    private static final class ScriptedModel implements ChatModel {

        final List<String> prompts = new ArrayList<>();
        private final Deque<AssistantMessage> replies;

        ScriptedModel(AssistantMessage... replies) {
            this.replies = new LinkedList<>(List.of(replies));
        }

        @Override
        public ChatOptions getOptions() {
            return OpenAiChatOptions.builder().model("fake-large").build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            StringBuilder all = new StringBuilder();
            for (Message message : prompt.getInstructions()) {
                all.append(message.getText()).append('\n');
                if (message instanceof ToolResponseMessage tools) {
                    tools.getResponses().forEach(response -> all.append(response.responseData()).append('\n'));
                }
                if (message instanceof AssistantMessage assistant) {
                    assistant.getToolCalls().forEach(toolCall -> all.append(toolCall.arguments()).append('\n'));
                }
            }
            prompts.add(all.toString());
            AssistantMessage reply = replies.isEmpty() ? new AssistantMessage("没有了。") : replies.removeFirst();
            return new ChatResponse(List.of(new Generation(reply)));
        }
    }
}
