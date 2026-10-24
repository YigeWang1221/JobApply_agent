package jobapply.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Agent 直接改的 memory 文件（C014，memory.md「Memory 写入」）：站点观察 {@code playbook/sites/<host>.md}、
 * 公司记忆 {@code companies/<company>.md}、经验总结 {@code review/lessons.md}，以及 skill 正文的局部修改。
 * <p>
 * 照 Hermes 的 memory 工具（plan/reference/research-agent-design.md「Hermes 的 memory 写法」）：正文里一行
 * {@code - ...} 是一条；{@code add} 加新的（一模一样的不重复加），{@code replace} / {@code remove} 用一小段原文
 * {@code old_text} 找到那一条（找不到、找到几条都报错）；每个文件的条目有总字数上限，满了先合并或删掉旧条目，
 * 不一直往下追加。其它行（标题等）和 frontmatter 不动。
 */
public final class MemoryNotes {

    /** 一个文件里所有条目加起来最多这么多字。 */
    public static final int MAX_CHARS = 4_000;
    /** 几个申请同时在跑（C011），各自的实例改同一个文件：读改写整个过程排队。 */
    private static final Object LOCK = new Object();

    public enum Action {
        ADD, REPLACE, REMOVE;

        public static Action of(String value) {
            if (value == null || value.isBlank()) {
                return ADD;
            }
            for (Action action : values()) {
                if (action.name().equalsIgnoreCase(value.strip())) {
                    return action;
                }
            }
            throw new IllegalArgumentException("action 只能是 add、replace 或 remove");
        }
    }

    private final StorageLayout layout;

    public MemoryNotes(StorageLayout layout) {
        this.layout = layout;
    }

    /** 改一条站点观察（新文件带上 {@link SiteNotes} 的 frontmatter），返回给模型看的结果和现在的条目。 */
    public String site(String host, Action action, String text, String oldText) {
        Map<String, Object> frontmatter = new LinkedHashMap<>();
        frontmatter.put(SiteNotes.HOST, host);
        frontmatter.put(SiteNotes.PLATFORM, "");
        frontmatter.put(SiteNotes.CONFIRMED_BUTTONS, new ArrayList<>());
        return edit(layout.playbookSite(host), frontmatter, "# 观察记录", action, text, oldText);
    }

    /** 改一条公司记忆（流程、在线简历状态、额度提示等）。 */
    public String company(String company, Action action, String text, String oldText) {
        Map<String, Object> frontmatter = new LinkedHashMap<>();
        frontmatter.put("company", company.strip());
        frontmatter.put("tags", List.of("jobapply/company"));
        return edit(layout.companyFile(company), frontmatter, "# " + company.strip(), action, text, oldText);
    }

    /** 改一条可复用的经验。 */
    public String lesson(Action action, String text, String oldText) {
        return edit(layout.reviewLessons(), Map.of("tags", List.of("jobapply/lessons")), "# 经验总结", action, text,
                oldText);
    }

    /** 公司记忆的正文；没有文件或公司名不能当文件名时为空。 */
    public Optional<String> company(String company) {
        Path file;
        try {
            file = layout.companyFile(company);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        String body = FrontmatterFile.read(file).body().strip();
        return body.isEmpty() ? Optional.empty() : Optional.of(body);
    }

    /**
     * 改 skill 正文里唯一的一段（Hermes {@code skill_manage} 的 {@code patch}）：{@code oldString} 必须正好出现一次；
     * frontmatter 不动。改完的正文不能超过 {@code budget} 字。
     */
    public String patchSkill(Path file, String oldString, String newString, int budget) {
        if (oldString == null || oldString.isEmpty()) {
            throw new IllegalArgumentException("skill 要写 old_string（照 read_skill 读到的正文原样抄一段）");
        }
        synchronized (LOCK) {
            String text = AtomicFiles.read(file);
            String body = FrontmatterFile.parse(text, layout.relative(file)).body();
            String head = text.substring(0, text.length() - body.length());
            int first = body.indexOf(oldString);
            if (first < 0) {
                throw new IllegalArgumentException("正文里没有找到 old_string。先 read_skill 看现在的正文，照原文抄一段");
            }
            if (body.indexOf(oldString, first + 1) >= 0) {
                throw new IllegalArgumentException("old_string 在正文里出现了不止一次，多带一点上下文让它只出现一次");
            }
            String patched = body.substring(0, first) + (newString == null ? "" : newString)
                    + body.substring(first + oldString.length());
            if (patched.length() > budget) {
                throw new IllegalArgumentException("改完正文有 " + patched.length() + " 字，超过 " + budget
                        + " 字。先合并或删掉旧内容，写得短一些");
            }
            AtomicFiles.write(file, head + patched);
            return "已改 " + layout.relative(file) + "（正文 " + patched.length() + " 字）。";
        }
    }

    private String edit(Path file, Map<String, Object> frontmatter, String heading, Action action, String text,
                        String oldText) {
        String entry = text == null ? null : text.strip().replaceAll("\\s*\\R\\s*", " ");
        if (action != Action.REMOVE && (entry == null || entry.isEmpty())) {
            throw new IllegalArgumentException("要记的内容（note）不能为空；要删掉一条用 remove");
        }
        if (action != Action.ADD && (oldText == null || oldText.isBlank())) {
            throw new IllegalArgumentException(action.name().toLowerCase(Locale.ROOT) +" 要写 old_text（那一条里的一小段原文）");
        }
        synchronized (LOCK) {
            Map<String, Object> fm = new LinkedHashMap<>(frontmatter);
            List<String> lines = new ArrayList<>(List.of("", heading, ""));
            if (Files.exists(file)) {
                FrontmatterFile.Document existing = FrontmatterFile.read(file);
                fm = existing.frontmatter();
                lines = new ArrayList<>(List.of(existing.body().stripTrailing().split("\n", -1)));
            }
            String rel = layout.relative(file);
            String done;
            switch (action) {
                case ADD -> {
                    if (entries(lines).contains(entry)) {
                        return "已经有这一条了，没有重复加：" + rel + "\n" + listing(lines);
                    }
                    int last = -1;
                    for (int i = 0; i < lines.size(); i++) {
                        if (lines.get(i).startsWith("- ")) {
                            last = i;
                        }
                    }
                    if (last < 0) {
                        if (!lines.isEmpty() && !lines.getLast().isBlank()) {
                            lines.add("");
                        }
                        lines.add("- " + entry);
                    } else {
                        lines.add(last + 1, "- " + entry);
                    }
                    done = "已加上";
                }
                case REPLACE, REMOVE -> {
                    int index = match(lines, oldText.strip(), rel);
                    if (action == Action.REPLACE) {
                        lines.set(index, "- " + entry);
                        done = "已替换";
                    } else {
                        lines.remove(index);
                        done = "已删掉";
                    }
                }
                default -> throw new IllegalStateException();
            }
            int chars = entries(lines).stream().mapToInt(String::length).sum();
            if (chars > MAX_CHARS) {
                throw new IllegalArgumentException(rel + " 的条目会有 " + chars + " 字，超过 " + MAX_CHARS
                        + " 字，没有写。先合并或删掉旧条目（replace / remove），再加新的。现在的条目：\n"
                        + listing(Files.exists(file)
                        ? List.of(FrontmatterFile.read(file).body().split("\n", -1)) : List.of()));
            }
            FrontmatterFile.write(file, new FrontmatterFile.Document(fm, String.join("\n", lines) + "\n"));
            return done + "：" + rel + "（" + chars + "/" + MAX_CHARS + " 字）。现在的条目：\n" + listing(lines);
        }
    }

    /** 包含 {@code oldText} 的那一条的行号；没有或者不止一条（内容不同）时报错。 */
    private static int match(List<String> lines, String oldText, String rel) {
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("- ") && lines.get(i).contains(oldText)) {
                found.add(i);
            }
        }
        if (found.isEmpty()) {
            throw new IllegalArgumentException(rel + " 里没有包含「" + oldText + "」的条目。现在的条目：\n"
                    + listing(lines));
        }
        if (found.stream().map(lines::get).distinct().count() > 1) {
            throw new IllegalArgumentException("「" + oldText + "」对上了 " + found.size() + " 条，写得更具体一些："
                    + "\n" + String.join("\n", found.stream().map(lines::get).toList()));
        }
        return found.getFirst();
    }

    private static List<String> entries(List<String> lines) {
        return lines.stream().filter(line -> line.startsWith("- ")).map(line -> line.substring(2).strip()).toList();
    }

    private static String listing(List<String> lines) {
        List<String> entries = lines.stream().filter(line -> line.startsWith("- ")).toList();
        return entries.isEmpty() ? "（没有条目）" : String.join("\n", entries);
    }
}
