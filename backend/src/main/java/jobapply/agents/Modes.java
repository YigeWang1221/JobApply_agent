package jobapply.agents;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import jobapply.persistence.FrontmatterFile;

/**
 * 读 classpath 里的 Mode 定义（{@code modes/<name>.md}，C030）。现在有：
 * <ul>
 *   <li>{@link #SESSION}：Session 对话（调研、候选池、开始申请）；</li>
 *   <li>{@link #APPLY}：申请工作台的投递段（排简历、填表，停在最终提交前）；</li>
 *   <li>{@link #FOLLOWUP}：申请工作台的后续段（笔试面试、复盘、记状态）；</li>
 *   <li>Subagent：{@link #WEB_RESEARCH}（找岗 / 核实，可当场入池）、{@link #REFLECT}（投递后复盘沉淀经验）、
 *       {@link #PORTAL_CHECK}（只读查官网状态）、{@link #RESUME_MATCH}（简历排名与修改建议）、
 *       {@link #INTERVIEW_PREP}（面试准备）。</li>
 * </ul>
 * 读一次就缓存（文件在 jar 里，不会变）。
 */
public final class Modes {

    public static final String SESSION = "session";
    public static final String APPLY = "apply";
    public static final String FOLLOWUP = "followup";
    /** Subagent 的 Mode（C030 Slice 5，{@link SubagentExecutor}）：父 Mode 的 {@code subagents:} 写它能交给哪几个。 */
    public static final String WEB_RESEARCH = "web-research";
    public static final String REFLECT = "reflect";
    public static final String PORTAL_CHECK = "portal-check";
    public static final String RESUME_MATCH = "resume-match";
    public static final String INTERVIEW_PREP = "interview-prep";

    private static final Map<String, Mode> CACHE = new ConcurrentHashMap<>();

    private Modes() {
    }

    public static Mode get(String name) {
        return CACHE.computeIfAbsent(name, Modes::load);
    }

    /**
     * 一个 Job 线程该用哪个 Mode（给 Slice 4 的统一 Job 线程：{@code job.md} 的 phase 是唯一权威源）：
     * 还在投递段（没有阶段或 prep）→ {@link #APPLY}；投递之后 → {@link #FOLLOWUP}。
     *
     * @param applySegment 这个 Job 是否还在投递段
     */
    public static Mode forJob(boolean applySegment) {
        return get(applySegment ? APPLY : FOLLOWUP);
    }

    private static Mode load(String name) {
        String path = "modes/" + name + ".md";
        String text;
        try (InputStream in = Modes.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("没有这个 Mode：" + name + "（classpath 里没有 " + path + "）");
            }
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读不出 " + path, e);
        }
        FrontmatterFile.Document doc = FrontmatterFile.parse(text, path);
        Map<String, Object> fm = doc.frontmatter();
        String limit = String.valueOf(fm.getOrDefault("tool-call-limit", Mode.PER_MESSAGE));
        if (!Mode.PER_RUN.equals(limit) && !Mode.PER_MESSAGE.equals(limit)) {
            throw new IllegalArgumentException(path + " 的 tool-call-limit 只能是 per-run 或 per-message");
        }
        return new Mode(String.valueOf(fm.getOrDefault("name", name)), (String) fm.get("description"),
                doc.body().strip(), Mode.strings(fm.get("tools")), Mode.strings(fm.get("allow-tools")),
                Mode.strings(fm.get("plugins")), Mode.strings(fm.get("subagents")), Boolean.TRUE.equals(fm.get("drop-reply-when-stopped")), limit,
                String.valueOf(fm.getOrDefault("limit-note", "")), Mode.SkillScope.of(fm.get("skills")));
    }
}
