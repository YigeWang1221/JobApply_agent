package jobapply.harness.plugins;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Predicate;
import jobapply.harness.context.SkillCatalog;
import jobapply.memory.ProfileResolver;
import jobapply.persistence.AttachmentStore;
import jobapply.persistence.FrontmatterFile;
import jobapply.persistence.MemoryNotes;
import jobapply.persistence.StorageLayout;

/**
 * Session Mode 每一步的上下文（原来在 {@code SessionLoop}）：今天、Project、资料画像、偏好、已有的资料键、skill 目录
 * （Mode 的 skill 范围）和经验总结；附件在 Session 目录。
 */
public final class ResearchContextPlugin implements Plugin {

    private final StorageLayout layout;
    private final Clock clock;
    private final String project;
    private final String session;
    private final SkillCatalog catalog;
    private final Predicate<SkillCatalog.Skill> skills;

    public ResearchContextPlugin(StorageLayout layout, Clock clock, String project, String session,
                                 SkillCatalog catalog, Predicate<SkillCatalog.Skill> skills) {
        this.layout = layout;
        this.clock = clock;
        this.project = project;
        this.session = session;
        this.catalog = catalog;
        this.skills = skills;
    }

    @Override
    public void context(ContextView view) {
        view.context(context());
        view.attachments(new AttachmentStore(layout.sessionAttachmentsDir(project, session)));
    }

    private String context() {
        StringBuilder out = new StringBuilder("# 上下文\n\n");
        out.append("- 今天：").append(LocalDate.now(clock)).append('\n');
        out.append("- Project：").append(project).append("（Session ").append(session).append("）\n");
        out.append("\n# 用户画像（profile.md）\n\n").append(body(layout.baseProfile(), "还没有画像。"));
        out.append("\n\n# 偏好（preferences.md）\n\n").append(body(layout.preferences(project), "还没有写偏好。"));
        out.append("\n\n# 已有的资料键（remember fact 之前先看：同一件事用已有的键 replace，不另起新键）\n\n")
                .append(factKeys());
        out.append("\n\n# skill 目录（用 read_skill 读全文）\n\n");
        for (SkillCatalog.Skill skill : catalog.skills()) {
            if (skills.test(skill)) {
                out.append("- ").append(skill.name()).append("：").append(skill.description()).append('\n');
            }
        }
        new MemoryNotes(layout).lessons().ifPresent(notes ->
                out.append("\n\n# 经验总结（lessons.md）\n\n").append(notes));
        return out.toString();
    }

    /** 资料键一行列完（只有键和分级，不带值；C 级的值只会是占位符）。读不出时说原因，不挡住这次对话。 */
    private String factKeys() {
        try {
            List<String> keys = new ProfileResolver(layout, clock).resolve(project, List.of()).values().stream()
                    .map(fact -> fact.key() + "（" + fact.factClass().name() + "）").toList();
            return keys.isEmpty() ? "（还没有）" : String.join("、", keys);
        } catch (RuntimeException e) {
            return "（读不出：" + e.getMessage() + "）";
        }
    }

    /** 一个 Markdown 文件的正文（去掉 frontmatter）；没有文件或是空的时返回 {@code fallback}。 */
    private static String body(Path file, String fallback) {
        if (!Files.exists(file)) {
            return fallback;
        }
        String body = FrontmatterFile.read(file).body().strip();
        return body.isEmpty() ? fallback : body;
    }
}
