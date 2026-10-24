package jobapply.memory;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import jobapply.domain.Fact;
import jobapply.domain.FactClass;
import jobapply.domain.Language;
import jobapply.harness.context.SkillCatalog;
import jobapply.harness.policies.FieldClassGuard;
import jobapply.harness.policies.ToolNotAllowedException;
import jobapply.persistence.FactsFile;
import jobapply.persistence.MemoryNotes;
import jobapply.persistence.MemoryNotes.Action;
import jobapply.persistence.StorageLayout;

/**
 * 两个 Agent 的 {@code remember} 工具（C014，memory.md「Memory 写入」）：Agent 发现值得留下的东西时直接写进本地
 * memory，不经过用户确认；写了什么由 Agent 在回复里告诉用户，用户不满意就说，Agent 再改。
 * 写法照 Hermes 的 memory / skill_manage 工具：已有的改（replace），新的才加（add），不要的删（remove），
 * 让 memory 不越写越乱（plan/reference/research-agent-design.md「Hermes 的 memory 写法」）。
 * <ul>
 *   <li>{@code fact}：个人事实，按键写进当前 Project 的 {@code overrides.md}：键已有就更新，没有就加；A / B 级；
 *       C 级只能登记占位符 {@code {KEY}}，明文拒绝；</li>
 *   <li>{@code site} / {@code company} / {@code lesson}：{@code playbook/sites/<host>.md}、
 *       {@code companies/<company>.md}、{@code review/lessons.md} 里的一条（{@link MemoryNotes}）；</li>
 *   <li>{@code skill}：skill 正文里唯一的一段换成新的（不整篇重写）。</li>
 * </ul>
 */
public final class MemoryWriter {

    /** C 级占位符：值正好是 {@code {KEY}}，键名是大写字母、数字和下划线（C014 D）。 */
    static final Pattern PLACEHOLDER = Pattern.compile("\\{[A-Z_][A-Z0-9_]*\\}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*");
    private static final Set<String> SOURCES = Set.of("user-confirmed", "site-observed");
    private static final String TELL = "在回复里告诉用户写了什么。";
    private static final Object LOCK = new Object();

    /**
     * 模型给的一条记录（{@code remember} 的参数）。
     *
     * @param kind   {@code fact} / {@code site} / {@code company} / {@code lesson} / {@code skill}
     * @param action {@code add} / {@code replace} / {@code remove}；没写时为 add
     */
    public record Item(String kind, String action, String key, String value, String zh, String en, String factClass,
                       String source, String host, String company, String note, String oldText, String skill,
                       String oldString, String newString) {

        /** 从工具参数里取（空白当没写；{@code new_string} 可以是空的，表示删掉那一段）。 */
        public static Item from(Map<String, Object> args) {
            return new Item(text(args.get("kind")), text(args.get("action")), text(args.get("key")),
                    text(args.get("value")), text(args.get("zh")), text(args.get("en")), text(args.get("class")),
                    text(args.get("source")), text(args.get("host")), text(args.get("company")),
                    text(args.get("note")), text(args.get("old_text")), text(args.get("skill")),
                    args.get("old_string") instanceof String old && !old.isEmpty() ? old : null,
                    args.get("new_string") instanceof String replacement ? replacement : null);
        }

        private static String text(Object value) {
            return value instanceof String text && !text.isBlank() ? text.strip() : null;
        }
    }

    private final StorageLayout layout;
    private final Clock clock;
    private final MemoryNotes notes;

    public MemoryWriter(StorageLayout layout, Clock clock) {
        this.layout = layout;
        this.clock = clock;
        this.notes = new MemoryNotes(layout);
    }

    /**
     * 写一条记录，返回给模型的结果（写到了哪里、现在有什么）。
     *
     * @param defaultHost    没写 {@code host} 时用的站点（申请 Agent 的当前页面）；可以为 null
     * @param defaultCompany 没写 {@code company} 时用的公司（申请 Agent 的这个岗位）；可以为 null
     * @throws IllegalArgumentException 参数不对、没对上要改的条目、超过上限
     * @throws ToolNotAllowedException  C 级明文
     */
    public String remember(String projectId, Item item, String defaultHost, String defaultCompany) {
        Action action = Action.of(item.action());
        String kind = item.kind() == null ? "" : item.kind();
        if (!"fact".equals(kind) && !"skill".equals(kind)) {
            requireNotClassC(kind, item.note());
        }
        return switch (kind) {
            case "fact" -> fact(projectId, action, item);
            case "site" -> {
                String host = hostOf(item.host() != null ? item.host() : defaultHost);
                if (host == null) {
                    throw new IllegalArgumentException("site 要写 host（站点主机名，例如 jobs.example.com）");
                }
                yield notes.site(host, action, item.note(), item.oldText()) + "\n" + TELL;
            }
            case "company" -> {
                String company = item.company() != null ? item.company() : defaultCompany;
                if (company == null || company.isBlank()) {
                    throw new IllegalArgumentException("company 要写公司名");
                }
                yield notes.company(company, action, item.note(), item.oldText()) + "\n" + TELL;
            }
            case "lesson" -> notes.lesson(action, item.note(), item.oldText()) + "\n" + TELL;
            case "skill" -> skill(item);
            default -> throw new IllegalArgumentException("kind 只能是 fact、site、company、lesson 或 skill");
        };
    }

    // ---- 事实：按键更新或新加 ----

    private String fact(String projectId, Action action, Item item) {
        String key = item.key();
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("fact 要写 key（资料键，例如 contact.phone、education.masters.gpa）");
        }
        Path file = layout.overrides(projectId);
        String rel = layout.relative(file);
        if (action == Action.REMOVE) {
            synchronized (LOCK) {
                List<Fact> facts = new ArrayList<>(FactsFile.read(file));
                if (!facts.removeIf(fact -> fact.key().equals(key))) {
                    throw new IllegalArgumentException(rel + " 里没有 " + key + "（base 里的条目不在这里删；"
                            + "值不对就用 replace 写成对的值）");
                }
                FactsFile.replace(file, "overrides · " + projectId, facts);
                return "已删掉 " + rel + " 的 " + key + "。" + TELL;
            }
        }
        if (item.value() != null && (item.zh() != null || item.en() != null)) {
            throw new IllegalArgumentException("value 和 zh / en 只能二选一：与语言无关的值写 value，文字写 zh / en");
        }
        if (item.value() == null && item.zh() == null && item.en() == null) {
            throw new IllegalArgumentException("fact 要写 value，或者 zh / en");
        }
        for (String text : new String[] {item.value(), item.zh(), item.en(), item.note()}) {
            if (text != null && (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0)) {
                throw new IllegalArgumentException("事实的值不能换行，写成一行");
            }
        }
        String source = item.source() == null ? "user-confirmed" : item.source();
        if (!SOURCES.contains(source)) {
            throw new IllegalArgumentException("source 只能是 user-confirmed 或 site-observed");
        }
        FactClass factClass = factClass(key, item);
        Set<Language> reviewed = EnumSet.noneOf(Language.class);
        if (item.zh() != null) {
            reviewed.add(Language.ZH);
        }
        if (item.en() != null) {
            reviewed.add(Language.EN);
        }
        synchronized (LOCK) {
            List<Fact> facts = new ArrayList<>(FactsFile.read(file));
            Fact old = facts.stream().filter(fact -> fact.key().equals(key)).findFirst().orElse(null);
            Fact fact = new Fact(key, item.value(), item.zh(), item.en(), factClass, source, null, reviewed,
                    LocalDate.now(clock).toString(), old == null ? null : old.validFrom(),
                    old == null ? null : old.validTo(), old == null ? null : old.precision(),
                    item.note() != null ? item.note() : old == null ? null : old.notes(), false);
            if (old == null) {
                facts.add(fact);
            } else {
                facts.set(facts.indexOf(old), fact);
            }
            FactsFile.replace(file, "overrides · " + projectId, facts);
            String what = key + "（" + factClass.name() + " 级，" + source + "）";
            return old == null
                    ? "已加上 " + rel + " 的 " + what + "。" + TELL
                    : "已更新 " + rel + " 的 " + what + "，原来是「" + describe(old) + "」。" + TELL;
        }
    }

    /** 占位符一律是 C 级；C 级明文拒绝。 */
    private static FactClass factClass(String key, Item item) {
        if (item.value() != null && PLACEHOLDER.matcher(item.value()).matches()) {
            // 真实值在用户自己的 personal.env 里，系统发给浏览器时才换成真实值
            return FactClass.C;
        }
        if ("C".equals(item.factClass()) || CandidateMerger.isClassC(key, item.value(), item.zh(), item.en())) {
            throw new ToolNotAllowedException(key + " 是 C 级（证件、签证和工作授权、薪资、人口统计、健康、声明），"
                    + "资料里不存明文。改成登记占位符：value 写大写的键名，例如 {ID_NUMBER}，class 写 C；"
                    + "告诉用户把真实值自己写进 storage/settings/personal.env（一行一个 ID_NUMBER=...）");
        }
        if ("B".equals(item.factClass())) {
            return FactClass.B;
        }
        if (item.factClass() == null || "A".equals(item.factClass())) {
            return FactClass.A;
        }
        throw new IllegalArgumentException("class 只能是 A、B 或 C");
    }

    private static String describe(Fact fact) {
        if (fact.unset()) {
            return "unset";
        }
        if (fact.value() != null) {
            return fact.value();
        }
        return (fact.zh() == null ? "" : fact.zh()) + (fact.zh() != null && fact.en() != null ? " / " : "")
                + (fact.en() == null ? "" : fact.en());
    }

    // ---- skill：只改唯一的一段 ----

    private String skill(Item item) {
        if (item.skill() == null) {
            throw new IllegalArgumentException("skill 要写 skill（skill 目录里的名字）");
        }
        SkillCatalog catalog = SkillCatalog.scan(layout);
        SkillCatalog.Skill skill = catalog.find(item.skill()).orElseThrow(() ->
                new IllegalArgumentException("没有这个 skill：" + item.skill() + "。站点特有的经验写 kind site"));
        if (item.newString() != null && FieldClassGuard.withoutClassC(item.newString()).removed() > 0) {
            throw new ToolNotAllowedException("new_string 里有 C 级的值（证件号、薪资等），去掉这些值再写");
        }
        Path file = layout.playbookSkills().resolve(skill.path());
        return notes.patchSkill(file, item.oldString(), item.newString(), SkillCatalog.BODY_BUDGET) + TELL;
    }

    /** 站点、公司、经验是写给以后的 Agent 看的，也不存 C 级的值（证件号、薪资数字等）。 */
    private static void requireNotClassC(String kind, String note) {
        if (note != null && FieldClassGuard.withoutClassC(note).removed() > 0) {
            throw new ToolNotAllowedException(kind + " 的 note 里有 C 级的值（证件号、薪资等），去掉这些值再记");
        }
    }

    /** {@code jobs.example.com}，也接受整个网址；小写。 */
    private static String hostOf(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.strip();
        if (text.contains("://")) {
            try {
                text = URI.create(text).getHost();
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return text == null ? null : text.toLowerCase(Locale.ROOT);
    }
}
