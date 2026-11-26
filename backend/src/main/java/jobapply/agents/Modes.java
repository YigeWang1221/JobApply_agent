package jobapply.agents;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import jobapply.harness.runtime.ToolRegistry;
import jobapply.persistence.FrontmatterFile;
import jobapply.persistence.StorageLayout;

/**
 * 读 Mode 定义（C030；C030-fix 起可以放在 storage 里）：先找 {@code storage/modes/<name>.md}，没有再用内置的
 * classpath {@code modes/<name>.md}。现在内置的有：
 * <ul>
 *   <li>{@link #SESSION}：Session 对话（调研、候选池、开始申请）；</li>
 *   <li>{@link #APPLY}：申请工作台的投递段（排简历、填表，停在最终提交前）；</li>
 *   <li>{@link #FOLLOWUP}：申请工作台的后续段（笔试面试、复盘、记状态）；</li>
 *   <li>Subagent：{@link #WEB_RESEARCH}（找岗 / 核实，可当场入池）、{@link #REFLECT}（投递后复盘沉淀经验）、
 *       {@link #PORTAL_CHECK}（只读查官网状态）、{@link #RESUME_MATCH}（简历排名与修改建议）、
 *       {@link #INTERVIEW_PREP}（面试准备）。</li>
 * </ul>
 * 不缓存：每次运行开始时读，改了 storage 里的文件下一次运行就生效。文件写错（frontmatter 读不出、工具集不认识、
 * {@code subagents:} 里的 Mode 哪里都没有）时抛出，错误里写清是哪个文件哪一项，不退回内置的；插件名和
 * {@code allow-tools:} 在 {@link BaseAgent} 装配时检查。
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

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]*");

    private Modes() {
    }

    /** 读名字是 {@code name} 的 Mode：storage 里的优先，没有再用内置的。 */
    public static Mode get(StorageLayout layout, String name) {
        String text = read(layout, name);
        if (text == null) {
            throw new IllegalArgumentException("没有这个 Mode：" + name + "（" + layout.modesDir() + " 和内置的 modes/ 里都没有 "
                    + name + ".md）");
        }
        return parse(layout, name, text, source(layout, name));
    }

    /**
     * 一个 Job 线程该用哪个 Mode（给 Slice 4 的统一 Job 线程：{@code job.md} 的 phase 是唯一权威源）：
     * 还在投递段（没有阶段或 prep）→ {@link #APPLY}；投递之后 → {@link #FOLLOWUP}。
     *
     * @param applySegment 这个 Job 是否还在投递段
     */
    public static Mode forJob(StorageLayout layout, boolean applySegment) {
        return get(layout, applySegment ? APPLY : FOLLOWUP);
    }

    private static Path stored(StorageLayout layout, String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Mode 名字只能是小写字母、数字和 -：" + name);
        }
        return layout.modesDir().resolve(name + ".md");
    }

    private static String source(StorageLayout layout, String name) {
        Path file = stored(layout, name);
        return Files.isRegularFile(file) ? file.toString() : "内置 modes/" + name + ".md";
    }

    /** 文件内容；storage 和内置的都没有时为 null。 */
    private static String read(StorageLayout layout, String name) {
        Path file = stored(layout, name);
        try {
            if (Files.isRegularFile(file)) {
                return Files.readString(file);
            }
            try (InputStream in = Modes.class.getClassLoader().getResourceAsStream("modes/" + name + ".md")) {
                return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读不出 Mode 文件 " + source(layout, name), e);
        }
    }

    private static Mode parse(StorageLayout layout, String name, String text, String path) {
        FrontmatterFile.Document doc = FrontmatterFile.parse(text, path);
        Map<String, Object> fm = doc.frontmatter();
        if (fm.isEmpty()) {
            throw new IllegalArgumentException(path + "：开头没有 frontmatter（--- 之间的装配）");
        }
        String limit = String.valueOf(fm.getOrDefault("tool-call-limit", Mode.PER_MESSAGE));
        if (!Mode.PER_RUN.equals(limit) && !Mode.PER_MESSAGE.equals(limit)) {
            throw new IllegalArgumentException(path + " 的 tool-call-limit 只能是 per-run 或 per-message：" + limit);
        }
        for (String toolset : Mode.strings(fm.get("tools"))) {
            if (!ToolRegistry.toolsets().contains(toolset)) {
                throw new IllegalArgumentException(path + " 的 tools 里没有这个工具集：" + toolset + "（有 "
                        + ToolRegistry.toolsets() + "）");
            }
        }
        for (String sub : Mode.strings(fm.get("subagents"))) {
            if (!NAME.matcher(sub).matches() || read(layout, sub) == null) {
                throw new IllegalArgumentException(path + " 的 subagents 里的 Mode 找不到：" + sub);
            }
        }
        return new Mode(String.valueOf(fm.getOrDefault("name", name)), (String) fm.get("description"),
                doc.body().strip(), Mode.strings(fm.get("tools")), Mode.strings(fm.get("allow-tools")),
                Mode.strings(fm.get("plugins")), Mode.strings(fm.get("subagents")), Boolean.TRUE.equals(fm.get("drop-reply-when-stopped")), limit,
                String.valueOf(fm.getOrDefault("limit-note", "")), Mode.SkillScope.of(fm.get("skills")), path);
    }
}
