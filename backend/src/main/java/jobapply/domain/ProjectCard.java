package jobapply.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 项目卡（{@code base/projects/<slug>.md}）。frontmatter 放与语言无关的字段，正文是两种语言、小节一一对应的描述。
 * 描述里可以直接写数字，也可以用 {@code {{fact:<名字>}}} 引用 {@link #facts()} 里的结构化值；引用、条件、出处、
 * 论断都可选（memory.md「中英双语」，2026-10-22 用户修订）。
 * 只写了一种语言（另一种还没起草）时不检查对应；两种都写了时，小节 id 必须相同、顺序相同。
 *
 * @param extra 未识别的 frontmatter 键，读写时原样保留
 */
public record ProjectCard(
        String slug,
        List<String> aliases,
        Text title,
        String kind,
        Period period,
        Integer teamSize,
        String roleClaim,
        List<String> stack,
        List<Link> links,
        Map<String, CardFact> facts,
        List<String> sources,
        Set<Language> reviewed,
        List<Section> zh,
        List<Section> en,
        Map<String, Object> extra) {

    public static final Set<String> KINDS = Set.of("course", "personal", "internship", "research", "work");
    private static final Pattern FACT_REF = Pattern.compile("\\{\\{fact:([^}]*)}}");
    private static final Pattern FACT_NAME = Pattern.compile("[a-z0-9_]+(-[a-z0-9_]+)*");

    /** 一对文字；可以只有一种语言。 */
    public record Text(String zh, String en) {

        public String get(Language language) {
            return language == Language.ZH ? zh : en;
        }
    }

    public record Period(String from, String to, String precision) {
    }

    public record Link(String kind, String url) {
    }

    /** 结构化数值：值和单位只存一份，条件成对；论断可选。 */
    public record CardFact(String value, String unit, Text condition, String claim) {

        public CardFact {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("项目卡的数值必须有 value");
            }
            if (claim != null && !Ids.isClaimId(claim)) {
                throw new IllegalArgumentException("论断 id 不合法（应为 c-NNNN）：" + claim);
            }
        }
    }

    /** 一个小节：{@code ### 标题 {#id}} 和它下面的文字。 */
    public record Section(String id, String heading, String text) {

        public Section {
            if (id == null || !FACT_NAME.matcher(id).matches()) {
                throw new IllegalArgumentException("小节 id 只允许小写字母、数字、- 和 _：" + id);
            }
            heading = heading == null ? "" : heading;
            text = text == null ? "" : text;
        }
    }

    public ProjectCard {
        Ids.requireProjectSlug(slug);
        if (kind != null && !KINDS.contains(kind)) {
            throw new IllegalArgumentException("项目类型只能是 " + String.join(" / ", KINDS) + "：" + kind);
        }
        if (teamSize != null && teamSize < 1) {
            throw new IllegalArgumentException("团队人数至少是 1：" + teamSize);
        }
        if (roleClaim != null && !Ids.isClaimId(roleClaim)) {
            throw new IllegalArgumentException("论断 id 不合法（应为 c-NNNN）：" + roleClaim);
        }
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        stack = stack == null ? List.of() : List.copyOf(stack);
        links = links == null ? List.of() : List.copyOf(links);
        facts = facts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(facts));
        sources = sources == null ? List.of() : List.copyOf(sources);
        reviewed = reviewed == null ? Set.of() : Set.copyOf(reviewed);
        zh = zh == null ? List.of() : List.copyOf(zh);
        en = en == null ? List.of() : List.copyOf(en);
        extra = extra == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(extra));
        for (String name : facts.keySet()) {
            if (!FACT_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("项目卡数值的名字只允许小写字母、数字、- 和 _：" + name);
            }
        }
        requireCorresponding(zh, en, "项目卡 " + slug);
        List<String> unknown = new ArrayList<>();
        for (Section section : concat(zh, en)) {
            Matcher matcher = FACT_REF.matcher(section.text() + "\n" + section.heading());
            while (matcher.find()) {
                if (!facts.containsKey(matcher.group(1))) {
                    unknown.add(matcher.group(1));
                }
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("项目卡 " + slug + " 引用了不存在的数值：" + String.join("，",
                    unknown.stream().distinct().map(name -> "{{fact:" + name + "}}").toList()));
        }
    }

    /** 两种语言都有小节时，id 必须相同、顺序相同。 */
    public static void requireCorresponding(List<Section> zh, List<Section> en, String what) {
        if (zh.isEmpty() || en.isEmpty()) {
            return;
        }
        List<String> zhIds = zh.stream().map(Section::id).toList();
        List<String> enIds = en.stream().map(Section::id).toList();
        if (!zhIds.equals(enIds)) {
            throw new IllegalArgumentException(what + " 的中英文小节不对应：zh " + zhIds + "，en " + enIds);
        }
    }

    public List<Section> sections(Language language) {
        return language == Language.ZH ? zh : en;
    }

    /** 本卡用到的全部论断引用（角色和各个数值）。 */
    public List<String> claimRefs() {
        List<String> refs = new ArrayList<>();
        if (roleClaim != null) {
            refs.add(roleClaim);
        }
        facts.values().stream().map(CardFact::claim).filter(Objects::nonNull).forEach(refs::add);
        return refs;
    }

    /** 填了的论断引用必须在台账里。 */
    public void requireKnownClaims(Set<String> claimIds) {
        List<String> missing = claimRefs().stream().filter(id -> !claimIds.contains(id)).distinct().toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("项目卡 " + slug + " 引用了台账里没有的论断：" + String.join("，", missing));
        }
    }

    /** 把 {@code {{fact:<名字>}}} 换成按语言格式化的值和单位。 */
    public String render(String text, Language language) {
        Matcher matcher = FACT_REF.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            CardFact fact = facts.get(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(format(fact, language)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** 值加单位；单位是英文单词时中间加空格（{@code 12 ms}），符号和中文单位直接接上（{@code 3.9×}、{@code 3人}）。 */
    public static String format(CardFact fact, Language language) {
        String value = language.formatNumber(fact.value());
        String unit = fact.unit() == null ? "" : fact.unit();
        boolean word = !unit.isEmpty() && ((unit.charAt(0) >= 'a' && unit.charAt(0) <= 'z')
                || (unit.charAt(0) >= 'A' && unit.charAt(0) <= 'Z'));
        return value + (word ? " " : "") + unit;
    }

    private static List<Section> concat(Collection<Section> a, Collection<Section> b) {
        List<Section> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
