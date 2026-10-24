package jobapply.harness.context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import jobapply.agents.ApplyAgent;
import jobapply.domain.AgentRun;
import jobapply.persistence.AttachmentStore;
import jobapply.domain.Claim;
import jobapply.domain.FactClass;
import jobapply.memory.ResolvedFact;
import jobapply.persistence.JobAnswers;
import org.springframework.ai.chat.messages.Message;

/**
 * Agent 循环每一步交给模型的消息（agent-runtime.md 7.3）：
 * <ol>
 *   <li>系统提示词（{@link ApplyAgent#systemPrompt()}）；</li>
 *   <li>资料和上下文：有效资料（中英两种写法都给，由模型按页面选）、本次答案、可上传的文件编号、skill 目录、
 *       这个站点的记录、计划；</li>
 *   <li>对话（{@link AgentRun#conversation()}），由 {@link Conversation} 拼成消息。</li>
 * </ol>
 */
public final class FillContext {

    /**
     * 每一步的上下文内容。
     *
     * @param profileZh 已经按 {@link #sendable} 选过的有效资料（文字取中文）
     * @param profileEn 同上（文字取英文）
     * @param answers   本次答案（Session 层）
     * @param files     可上传的文件，编号从 1 开始（只有文件名）
     * @param catalog   所有 skill 的名字和描述
     * @param suggested 这个站点建议先读的 skill 名字
     * @param siteNotes 这个站点的记录；没有时为 null
     * @param plan      模型的计划
     */
    public record Inputs(Map<String, ResolvedFact> profileZh, Map<String, ResolvedFact> profileEn,
                         List<JobAnswers.Answer> answers, List<String> files, List<SkillCatalog.Skill> catalog,
                         List<String> suggested, String siteNotes, List<AgentRun.PlanItem> plan) {

        public Inputs {
            profileZh = profileZh == null ? Map.of() : profileZh;
            profileEn = profileEn == null ? Map.of() : profileEn;
            answers = answers == null ? List.of() : List.copyOf(answers);
            files = files == null ? List.of() : List.copyOf(files);
            catalog = catalog == null ? List.of() : List.copyOf(catalog);
            suggested = suggested == null ? List.of() : List.copyOf(suggested);
            plan = plan == null ? List.of() : List.copyOf(plan);
        }
    }

    private FillContext() {
    }

    /**
     * 这一条资料能不能发给模型、能不能用来填表（C004-B「关键规则 · 资料与语言」）：没有关联论断的；
     * 关联论断的用途是 {@code forms_and_resume}、状态不是 {@code discrepancy} 的。{@code interview_only}、
     * {@code none}（包括 {@code needs_verification} 的默认用途）、{@code discrepancy} 和台账里找不到的论断都不发。
     * C 级本来存不进资料层，这里再挡一次。
     */
    public static boolean sendable(ResolvedFact fact, Function<String, Optional<Claim>> claims) {
        if (fact == null || fact.factClass() == FactClass.C) {
            return false;
        }
        if (fact.claim() == null) {
            return true;
        }
        Optional<Claim> claim = claims.apply(fact.claim());
        return claim.isPresent() && claim.get().use() == Claim.Use.FORMS_AND_RESUME
                && claim.get().status() != Claim.Status.DISCREPANCY;
    }

    /** 选出能发给模型的有效资料，保持原来的顺序。 */
    public static Map<String, ResolvedFact> sendable(Map<String, ResolvedFact> resolved,
                                                     Function<String, Optional<Claim>> claims) {
        Map<String, ResolvedFact> kept = new LinkedHashMap<>();
        resolved.forEach((key, fact) -> {
            if (sendable(fact, claims)) {
                kept.put(key, fact);
            }
        });
        return kept;
    }

    /** 组装这一步的全部消息。 */
    public static List<Message> messages(Inputs inputs, List<AgentRun.Turn> turns, Conversation.LatestPage latest) {
        return messages(inputs, turns, latest, null);
    }

    /** @param attachments 用户附的文件存在哪里（C006 附件）；为 null 时图片不发 */
    public static List<Message> messages(Inputs inputs, List<AgentRun.Turn> turns, Conversation.LatestPage latest,
                                         AttachmentStore attachments) {
        return Conversation.messages(ApplyAgent.systemPrompt(), context(inputs), turns, latest, attachments);
    }

    private static String context(Inputs inputs) {
        StringBuilder out = new StringBuilder();
        out.append("# 有效资料\n\n");
        out.append("资料键可以写进字段的 fact（可选）。有两种写法时按页面语言选；标「未审阅」的写法是起草的，")
                .append("用之前看一眼是否通顺。\n\n");
        Set<String> keys = new LinkedHashSet<>(inputs.profileZh().keySet());
        keys.addAll(inputs.profileEn().keySet());
        int listed = 0;
        for (String key : keys) {
            ResolvedFact zh = inputs.profileZh().get(key);
            ResolvedFact en = inputs.profileEn().get(key);
            ResolvedFact any = zh != null ? zh : en;
            if (any.layer() == ResolvedFact.Layer.SESSION) {
                continue;
            }
            listed++;
            out.append("- ").append(key).append("（").append(any.factClass().name()).append("）：");
            String zhText = text(zh);
            String enText = text(en);
            if (zh == null || en == null || zhText.equals(enText)) {
                out.append(block(zh != null ? zh : en));
            } else {
                out.append("\n  - 中文：").append(block(zh)).append("\n  - English：").append(block(en));
            }
            out.append('\n');
        }
        if (listed == 0) {
            out.append("- （没有可用的资料）\n");
        }

        out.append("\n# 本次答案（用户在这次申请里的回答，fact 写答案编号）\n\n");
        if (inputs.answers().isEmpty()) {
            out.append("- （还没有）\n");
        }
        for (JobAnswers.Answer answer : inputs.answers()) {
            out.append("- ").append(answer.id()).append("（问题：").append(answer.question().strip()).append("）：")
                    .append(answer.value().strip()).append('\n');
        }

        out.append("\n# 可上传的文件（browser_file_upload 只写编号）\n\n");
        if (inputs.files().isEmpty()) {
            out.append("- （还没有；需要上传时用 ask_user 的 files 请用户选）\n");
        }
        for (int i = 0; i < inputs.files().size(); i++) {
            out.append("- 文件 ").append(i + 1).append("：").append(inputs.files().get(i)).append('\n');
        }

        out.append("\n# skill 目录（用 read_skill 读全文）\n\n");
        for (SkillCatalog.Skill skill : inputs.catalog()) {
            out.append("- ").append(skill.name()).append("：").append(skill.description()).append('\n');
        }
        if (!inputs.suggested().isEmpty()) {
            out.append("\n这个站点建议先读：").append(String.join("、", inputs.suggested())).append('\n');
        }

        if (inputs.siteNotes() != null && !inputs.siteNotes().isBlank()) {
            out.append("\n# 这个站点以前的记录\n\n").append(inputs.siteNotes().strip()).append('\n');
        }

        out.append("\n# 你的计划\n\n");
        if (inputs.plan().isEmpty()) {
            out.append("（还没有，用 update_plan 写下）\n");
        }
        for (AgentRun.PlanItem item : inputs.plan()) {
            out.append("- [").append(item.status()).append("] ").append(item.text()).append('\n');
        }
        return out.toString();
    }

    private static String text(ResolvedFact fact) {
        if (fact == null) {
            return "";
        }
        return (fact.display() == null ? fact.value() : fact.display()).strip();
    }

    /** 一条资料的写法：多行文字原样保留（缩进），不压成一行。 */
    private static String block(ResolvedFact fact) {
        String text = text(fact).replace("\n", "\n    ");
        return fact.unreviewed() ? text + "（未审阅）" : text;
    }
}
