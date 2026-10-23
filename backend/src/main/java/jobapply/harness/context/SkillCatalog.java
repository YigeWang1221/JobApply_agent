package jobapply.harness.context;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import jobapply.persistence.FrontmatterFile;
import jobapply.persistence.StorageFormatException;
import jobapply.persistence.StorageLayout;

/**
 * {@code storage/playbook/skills/**}{@code /SKILL.md} 的目录（C004-B「skill 体系」）。扫描时只读 frontmatter 的字段，
 * 正文在 {@link #load(String)} 时才读（渐进加载），超过预算就截断并在末尾注明。
 * <p>
 * 格式兼容 agentskills：{@code name}（小写字母、数字、连字符，和所在目录同名）、{@code description}（一句话）、
 * {@code applies_to.url_patterns}、{@code applies_to.page_markers}、{@code version}、{@code observed_count}。
 * <p>
 * 一个文件格式不对，不会让整个目录不可用：它进 {@link #unavailable()}，带上原因，调用方要把它显示出来（不静默丢掉）；
 * 其它 skill 照常可用。名字重复时，按路径排序后的第一个可用，后面的记为不可用。
 * <p>
 * 扫描结果是一次快照；skill 文件改了要重新 {@link #scan}。
 */
public final class SkillCatalog {

    /** 每个 skill 正文的默认预算（字符数），和「每个文件不超过 8,000 个字符」一致。 */
    public static final int BODY_BUDGET = 8_000;
    /** 一直带上的通用 skill。 */
    public static final String APPLY_FORM = "apply-form";
    /** 按招聘系统划分的 skill 所在的目录；只有这里的 skill 会按 {@code url_patterns} 自动带上。 */
    public static final String PLATFORMS = "platforms";

    static final String FILE_NAME = "SKILL.md";
    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    /**
     * 一个可用的 skill。
     *
     * @param path        相对 skill 根目录的路径，例如 {@code components/date-picker/SKILL.md}
     * @param group       所在的第一层目录：{@code apply-form}、{@code components}、{@code platforms} 等
     * @param urlPatterns 网址模式（见 {@link #matchesUrl}）；没有时为空
     * @param pageMarkers 页面特征（例如组件库的 class 前缀），只作提示，不参与自动带上
     * @param version     原样的版本字符串；没有时为空字符串
     */
    public record Skill(String name, String description, String path, String group, List<String> urlPatterns,
                        List<String> pageMarkers, String version, int observedCount) {

        public Skill {
            urlPatterns = List.copyOf(urlPatterns);
            pageMarkers = List.copyOf(pageMarkers);
        }

        public boolean isPlatform() {
            return PLATFORMS.equals(group);
        }
    }

    /** 读不出来的 skill 文件：相对路径和原因。 */
    public record Unavailable(String path, String reason) {
    }

    /**
     * {@code load} 的结果。
     *
     * @param body           交给模型的正文；截断时末尾带上说明
     * @param truncated      是否截断了
     * @param originalLength 原正文的字符数
     */
    public record LoadedSkill(String name, String body, boolean truncated, int originalLength) {
    }

    private final Path root;
    private final List<Skill> skills;
    private final List<Unavailable> unavailable;
    private final int budget;

    private SkillCatalog(Path root, List<Skill> skills, List<Unavailable> unavailable, int budget) {
        this.root = root;
        this.skills = List.copyOf(skills);
        this.unavailable = List.copyOf(unavailable);
        this.budget = budget;
    }

    public static SkillCatalog scan(StorageLayout layout) {
        return scan(layout.playbookSkills(), BODY_BUDGET);
    }

    /** 扫描一个 skill 根目录。目录不存在时得到空目录。 */
    public static SkillCatalog scan(Path root, int budget) {
        if (budget <= 0) {
            throw new IllegalArgumentException("正文预算必须是正数：" + budget);
        }
        Path normalized = root.toAbsolutePath().normalize();
        List<Skill> skills = new ArrayList<>();
        List<Unavailable> unavailable = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Path file : skillFiles(normalized)) {
            String relative = relative(normalized, file);
            try {
                Skill skill = parse(relative, readText(file));
                if (!names.add(skill.name())) {
                    unavailable.add(new Unavailable(relative, "名字和另一个 skill 重复：" + skill.name()));
                    continue;
                }
                skills.add(skill);
            } catch (StorageFormatException | UncheckedIOException e) {
                unavailable.add(new Unavailable(relative, e.getMessage()));
            }
        }
        return new SkillCatalog(normalized, skills, unavailable, budget);
    }

    /** 所有可用的 skill，按路径排序。上下文里只放它们的名字和描述。 */
    public List<Skill> skills() {
        return skills;
    }

    public List<Unavailable> unavailable() {
        return unavailable;
    }

    public Optional<Skill> find(String name) {
        return skills.stream().filter(skill -> skill.name().equals(name)).findFirst();
    }

    /**
     * 这个网址要自动带上正文的 skill：{@code apply-form}（可用时）一直在第一个，其后是 {@code url_patterns} 命中的
     * {@code platforms/*}。其它 skill 由模型用 {@code load_skill} 读。
     */
    public List<Skill> autoInclude(String url) {
        List<Skill> included = new ArrayList<>();
        find(APPLY_FORM).ifPresent(included::add);
        for (Skill skill : skills) {
            if (skill.isPlatform() && skill.urlPatterns().stream().anyMatch(pattern -> matchesUrl(pattern, url))) {
                included.add(skill);
            }
        }
        return included;
    }

    /**
     * 读一个 skill 的正文（不含 frontmatter）。超过预算时只保留前面的部分，并在末尾注明截断了多少。
     *
     * @throws IllegalArgumentException 没有这个名字的可用 skill（不可用的会说明原因）
     * @throws StorageFormatException   扫描之后文件被改坏了
     */
    public LoadedSkill load(String name) {
        Skill skill = find(name).orElseThrow(() -> new IllegalArgumentException(unknown(name)));
        String text = readText(root.resolve(skill.path()));
        String body = FrontmatterFile.parse(text, skill.path()).body().strip();
        int length = body.length();
        if (length <= budget) {
            return new LoadedSkill(skill.name(), body, false, length);
        }
        int cut = budget;
        if (Character.isHighSurrogate(body.charAt(cut - 1))) {
            cut--;
        }
        String note = "\n\n（skill 正文共 " + length + " 个字符，超过预算 " + budget + "，已截断：只显示前 " + cut
                + " 个字符。）";
        return new LoadedSkill(skill.name(), body.substring(0, cut) + note, true, length);
    }

    /**
     * 网址模式：不区分大小写，和去掉协议（{@code https://}）后的整个网址比较；{@code *} 匹配任意字符（包括 {@code /}），
     * 其它字符按字面。例如 {@code *.example-ats.com/*}。
     */
    static boolean matchesUrl(String pattern, String url) {
        if (pattern == null || pattern.isBlank() || url == null) {
            return false;
        }
        String target = url.trim().toLowerCase(Locale.ROOT).replaceFirst("^[a-z][a-z0-9+.-]*://", "");
        StringBuilder regex = new StringBuilder();
        for (String part : pattern.trim().toLowerCase(Locale.ROOT).replaceFirst("^[a-z][a-z0-9+.-]*://", "")
                .split("\\*", -1)) {
            if (!regex.isEmpty()) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(part));
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL).matcher(target).matches();
    }

    private String unknown(String name) {
        for (Unavailable item : unavailable) {
            if (item.path().endsWith("/" + name + "/" + FILE_NAME) || item.path().equals(name + "/" + FILE_NAME)) {
                return "skill 不可用：" + name + "（" + item.reason() + "）";
            }
        }
        return "没有这个 skill：" + name;
    }

    static Skill parse(String relative, String text) {
        FrontmatterFile.Document document = FrontmatterFile.parse(text, relative);
        Map<String, Object> fm = document.frontmatter();
        if (fm.isEmpty()) {
            throw new StorageFormatException(relative, "缺少 frontmatter");
        }
        String name = requiredString(fm, "name", relative);
        if (!NAME.matcher(name).matches()) {
            throw new StorageFormatException(relative, "name 只能是小写字母、数字和连字符：" + name);
        }
        String[] segments = relative.split("/");
        if (segments.length < 2 || !segments[segments.length - 2].equals(name)) {
            throw new StorageFormatException(relative, "name 要和所在目录同名：" + name);
        }
        String description = requiredString(fm, "description", relative).replaceAll("\\s+", " ");
        List<String> urlPatterns = List.of();
        List<String> pageMarkers = List.of();
        Object appliesTo = fm.get("applies_to");
        if (appliesTo instanceof Map<?, ?> map) {
            urlPatterns = stringList(map.get("url_patterns"), "applies_to.url_patterns", relative);
            pageMarkers = stringList(map.get("page_markers"), "applies_to.page_markers", relative);
        } else if (appliesTo != null) {
            throw new StorageFormatException(relative, "applies_to 必须是键值对");
        }
        Object version = fm.get("version");
        if (version != null && !(version instanceof String || version instanceof Number)) {
            throw new StorageFormatException(relative, "version 必须是字符串或数字");
        }
        Object observed = fm.get("observed_count");
        int observedCount = 0;
        if (observed instanceof Integer count && count >= 0) {
            observedCount = count;
        } else if (observed != null) {
            throw new StorageFormatException(relative, "observed_count 必须是非负整数");
        }
        String group = segments.length > 2 ? segments[0] : name;
        return new Skill(name, description, relative, group, urlPatterns, pageMarkers,
                version == null ? "" : version.toString(), observedCount);
    }

    private static String requiredString(Map<String, Object> fm, String key, String source) {
        if (!(fm.get(key) instanceof String value) || value.isBlank()) {
            throw new StorageFormatException(source, "缺少 " + key);
        }
        return value.strip();
    }

    private static List<String> stringList(Object value, String key, String source) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new StorageFormatException(source, key + " 必须是列表");
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new StorageFormatException(source, key + " 的每一项都必须是非空字符串");
            }
            out.add(text.strip());
        }
        return out;
    }

    private static List<Path> skillFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.getFileName().toString().equals(FILE_NAME))
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> relative(root, path)))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("无法列出 skill 目录：" + root, e);
        }
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static String readText(Path file) {
        try {
            return Files.readString(file, UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取失败：" + file, e);
        }
    }
}
