package jobapply.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import jobapply.domain.Actor;
import jobapply.domain.AgentRun;
import jobapply.domain.Transition;
import jobapply.persistence.AttachmentStore;
import jobapply.persistence.SessionRepository;
import jobapply.persistence.TestStorage;
import jobapply.tools.BrowserSession;
import jobapply.tools.FakeBrowser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * 统一 Job 线程（C030 Slice 4）：存量的已投递 Job（phase 是 applied，历史附件在 {@code followup/attachments/}）
 * 回复时按 phase 进后续段 Mode，老附件照样读得到；新附件存进这一段的目录。
 */
class JobThreadSegmentTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Test
    void appliedJobContinuesInFollowupModeWithOldAttachments(@TempDir Path dir) {
        TestStorage storage = TestStorage.create(dir.resolve("storage"));
        storage.project("alpha", "live");
        String session = storage.session("alpha");
        String job = storage.ledger().addLead("alpha", session, Actor.USER,
                TestStorage.posting("公司甲", "https://jobs.example.com/1"), null).id();
        storage.ledger().transition("alpha", job, session, Actor.USER, new Transition.Convert());
        storage.ledger().markSubmitted("alpha", job, session, "用户说投了");
        // 历史数据：后续段的附件在 followup/attachments/
        AttachmentStore.Stored old = new AttachmentStore(storage.layout.followupAttachmentsDir("alpha", job))
                .save("笔试通知.txt", "下周三笔试".getBytes(StandardCharsets.UTF_8));

        RecordingModel model = new RecordingModel();
        FakeBrowser fake = FakeBrowser.create(dir.resolve("browser"));
        try (BrowserSession browser = new BrowserSession(fake.launch());
             JobThreadService threads = new JobThreadService(storage.layout, storage.clock, browser, () -> model,
                     null)) {
            AttachmentStore attachments = threads.attachments(job);
            assertThat(attachments.find(old.id())).isPresent();

            threads.reply(job, new JobThreadService.Reply(null, "收到笔试通知了", null, null, List.of(old.id())));
            assertThat(threads.awaitIdle(job, WAIT)).isTrue();

            // 后续段 Mode 的提示词，不是投递段的
            assertThat(model.prompts).isNotEmpty();
            assertThat(model.prompts.getFirst()).contains("已经投递").doesNotContain("简历匹配与修改建议");
            List<AgentRun.Turn> turns = threads.followupTurns(job).stream().map(SessionRepository.TimedTurn::turn)
                    .toList();
            assertThat(turns.getFirst().text()).isEqualTo("收到笔试通知了");
            assertThat(turns.getFirst().attachments()).extracting(AgentRun.Attachment::id).containsExactly(old.id());
            assertThat(turns).anyMatch(turn -> "好的，记下了。".equals(turn.text()));
            assertThat(threads.followupStatus(job).status()).isEqualTo(SessionRunner.Status.IDLE);
            assertThat(threads.run(job)).isEmpty();

            // 新附件存进后续段的目录，投递段的目录也作后备读得到
            AttachmentStore.Stored added = attachments.save("面试安排.txt", "周五面试".getBytes(StandardCharsets.UTF_8));
            assertThat(added.path()).startsWith(storage.layout.followupAttachmentsDir("alpha", job));
            assertThat(threads.attachments(job).list()).extracting(AttachmentStore.Stored::id)
                    .containsExactlyInAnyOrder(old.id(), added.id());
        }
    }

    /** 假模型：记下每次请求的全文，回一句话结束。 */
    private static final class RecordingModel implements ChatModel {

        final List<String> prompts = new CopyOnWriteArrayList<>();

        @Override
        public ChatOptions getOptions() {
            return OpenAiChatOptions.builder().model("fake-large").build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt.getContents());
            return new ChatResponse(List.of(new Generation(new AssistantMessage("好的，记下了。"))));
        }
    }
}
