package jobapply.harness.plugins;

import java.util.ArrayList;
import java.util.List;
import jobapply.domain.AgentRun;
import jobapply.harness.context.Conversation;
import jobapply.persistence.AttachmentStore;
import org.springframework.ai.chat.messages.Message;

/**
 * 一次调模型之前要发的东西，插件在 {@code context} 钩子里改：系统提示词、每一步重新生成的上下文、对话视图（存下的对话
 * 不改，只改发给模型的那份）、最新的页面、附件在哪、接在最后一条消息末尾的那段（申请状态）。最后由
 * {@link Conversation#messages} 拼成消息。
 */
public final class ContextView {

    private String system;
    private String context;
    private List<AgentRun.Turn> turns;
    private Conversation.LatestPage latest;
    private AttachmentStore attachments;
    private String tail;

    public ContextView(String system, List<AgentRun.Turn> turns) {
        this.system = system;
        this.turns = new ArrayList<>(turns);
    }

    public String system() {
        return system;
    }

    public void system(String text) {
        system = text;
    }

    public String context() {
        return context;
    }

    public void context(String text) {
        context = text;
    }

    public List<AgentRun.Turn> turns() {
        return turns;
    }

    public void turns(List<AgentRun.Turn> view) {
        turns = new ArrayList<>(view);
    }

    public Conversation.LatestPage latest() {
        return latest;
    }

    public void latest(Conversation.LatestPage page) {
        latest = page;
    }

    public void attachments(AttachmentStore store) {
        attachments = store;
    }

    public void tail(String text) {
        tail = text;
    }

    public List<Message> messages() {
        return Conversation.messages(system, context, turns, latest, attachments, tail);
    }
}
