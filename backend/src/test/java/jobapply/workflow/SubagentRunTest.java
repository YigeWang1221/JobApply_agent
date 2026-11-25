package jobapply.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import jobapply.domain.Actor;
import jobapply.domain.AgentRun;
import jobapply.domain.SubRun;
import jobapply.domain.Transition;
import jobapply.persistence.JobRepository;
import jobapply.persistence.SessionRepository;
import jobapply.persistence.SubRunStore;
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
 * Subagent（C030 Slice 5）：后续段把查官网交给 {@code portal-check}，子运行的对话只存进 {@code subruns/<id>.json}，
 * 工作台的对话里只有 {@code delegate} 和它交回的精简结果；子运行记下的建议照样写进 job.md。
 */
class SubagentRunTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Test
    void delegatedRunIsStoredApartFromTheParentConversation(@TempDir Path dir) throws Exception {
        TestStorage storage = TestStorage.create(dir.resolve("storage"));
        storage.project("alpha", "live");
        String session = storage.session("alpha");
        String job = storage.ledger().addLead("alpha", session, Actor.USER,
                TestStorage.posting("公司甲", "https://jobs.example.com/1"), null).id();
        storage.ledger().transition("alpha", job, session, Actor.USER, new Transition.Convert());
        storage.ledger().markSubmitted("alpha", job, session, "用户说投了");

        TreeModel model = new TreeModel();
        FakeBrowser fake = FakeBrowser.create(dir.resolve("browser"));
        try (BrowserSession browser = new BrowserSession(fake.launch());
             JobThreadService threads = new JobThreadService(storage.layout, storage.clock, browser, () -> model,
                     null)) {
            threads.reply(job, new JobThreadService.Reply(null, "去官网看看现在什么状态", null, null, List.of()));
            assertThat(threads.awaitIdle(job, WAIT)).isTrue();

            List<AgentRun.Turn> parent = threads.followupTurns(job).stream().map(SessionRepository.TimedTurn::turn)
                    .toList();
            String parentText = JsonMapper.builder().build().writeValueAsString(parent);
            assertThat(parentText).contains("delegate").contains("子任务 portal-check")
                    .doesNotContain("record_portal_check").doesNotContain("你是查状态的子任务");

            Path dirOfRuns = storage.layout.projectDir("alpha").resolve("subruns");
            List<Path> files;
            try (Stream<Path> list = Files.list(dirOfRuns)) {
                files = list.toList();
            }
            assertThat(files).hasSize(1);
            String id = files.getFirst().getFileName().toString().replace(".json", "");
            SubRun run = new SubRunStore(storage.layout).find("alpha", id).orElseThrow();
            assertThat(run.mode()).isEqualTo("portal-check");
            assertThat(run.depth()).isEqualTo(2);
            assertThat(run.parentRunId()).isEqualTo(job);
            assertThat(run.status()).isEqualTo(SubRun.DONE);
            assertThat(run.turns()).anyMatch(turn -> turn.calls() != null && turn.calls().stream()
                    .anyMatch(call -> "record_portal_check".equals(call.name())));
            assertThat(new JobRepository(storage.layout).load("alpha", job).extra().get("portal_check")).isInstanceOf(Map.class);
        }
    }

    /** 主运行先交给 portal-check，再回一句；子运行记一次官网结果，再交回一句。 */
    private static final class TreeModel implements ChatModel {

        @Override
        public ChatOptions getOptions() {
            return OpenAiChatOptions.builder().model("fake-large").build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();
            boolean sub = messages.getFirst().getText().contains("你是查状态的子任务");
            boolean answered = messages.stream().anyMatch(ToolResponseMessage.class::isInstance);
            AssistantMessage reply;
            if (sub) {
                reply = answered ? new AssistantMessage("found：筛选通过")
                        : call("record_portal_check", Map.of("result", "found", "stage", "筛选通过",
                                "url", "https://jobs.example.com/me"));
            } else {
                reply = answered ? new AssistantMessage("官网显示筛选通过，等你核准。")
                        : call("delegate", Map.of("mode", "portal-check", "task", "查这份申请的官网状态"));
            }
            return new ChatResponse(List.of(new Generation(reply)));
        }

        private static AssistantMessage call(String tool, Map<String, Object> args) {
            return AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                    "call-" + tool, "function", tool, JsonMapper.builder().build().writeValueAsString(args))))
                    .build();
        }
    }
}
