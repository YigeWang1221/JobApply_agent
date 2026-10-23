package jobapply.memory;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import jobapply.domain.Fact;
import jobapply.domain.FactClass;
import jobapply.domain.Ids;
import jobapply.domain.Language;
import jobapply.domain.ProjectCard;
import jobapply.domain.ProjectSpec;
import jobapply.persistence.FactsFile;
import jobapply.persistence.ProjectCardFile;
import jobapply.persistence.ProjectRepository;
import jobapply.persistence.StorageLayout;

/**
 * 求 Project 的有效资料。先命中先用：
 * 本 Session 答案 → 本 Project overrides → 父 Project overrides（按 inherits 深度优先，读当前版本）
 * → base facts（按本 Project 的 base_pin 取版本）。{@code unset} 屏蔽更低层的同名条目。
 * <p>
 * base 这一层还包括项目卡（{@code base/projects/<slug>.md}，同样按 base_pin 取版本）推出的 {@code projects.<slug>.*}
 * 字段；{@code facts.md} 里写了同名键时以 {@code facts.md} 为准。
 * 按语言取值：文字字段取对应语言，没有时取另一种并标为未审阅；日期和带单位的数值按语言格式输出（{@link ResolvedFact}）。
 */
public final class ProfileResolver {

    private record Layer(ResolvedFact.Layer kind, String id, List<Fact> facts) {
    }

    /**
     * base 这一层的内容：事实条目，以及项目卡数值对应的条目（按对象，不按键：{@code facts.md} 写了同名键时，
     * 取到的是 {@code facts.md} 那一条，不能再用项目卡的数值去格式化）。
     */
    private record Base(List<Fact> facts, Map<Fact, ProjectCard.CardFact> numbers) {
    }

    private final StorageLayout layout;
    private final ProjectRepository projects;
    private final ProjectCardFile projectCards;

    public ProfileResolver(StorageLayout layout, Clock clock) {
        this.layout = layout;
        this.projects = new ProjectRepository(layout, clock);
        this.projectCards = new ProjectCardFile(layout, clock);
    }

    /** 返回按 key 排序的有效资料（文字字段取中文）。 */
    public Map<String, ResolvedFact> resolve(String projectId, List<Fact> sessionAnswers) {
        return resolve(projectId, sessionAnswers, Language.ZH);
    }

    /** 返回按 key 排序的有效资料，文字字段取 {@code language}（填表时按页面语言）。 */
    public Map<String, ResolvedFact> resolve(String projectId, List<Fact> sessionAnswers, Language language) {
        ProjectSpec own = load(projectId);
        List<String> chain = new ArrayList<>();
        collect(projectId, new ArrayList<>(), new HashSet<>(), chain);

        Base base = base(own);
        List<Layer> layers = new ArrayList<>();
        layers.add(new Layer(ResolvedFact.Layer.SESSION, null, sessionAnswers == null ? List.of() : sessionAnswers));
        for (String id : chain) {
            layers.add(new Layer(ResolvedFact.Layer.PROJECT, id, FactsFile.read(layout.overrides(id))));
        }
        layers.add(new Layer(ResolvedFact.Layer.BASE, own.basePin(), base.facts()));

        Map<String, ResolvedFact> resolved = new TreeMap<>();
        Set<String> seen = new HashSet<>();
        for (Layer layer : layers) {
            for (Fact fact : layer.facts()) {
                if (!seen.add(fact.key()) || fact.unset()) {
                    continue;
                }
                ProjectCard.CardFact number = layer.kind() == ResolvedFact.Layer.BASE ? base.numbers().get(fact) : null;
                resolved.put(fact.key(), resolveOne(fact, layer, language, number));
            }
        }
        return Collections.unmodifiableMap(resolved);
    }

    private static ResolvedFact resolveOne(Fact fact, Layer layer, Language wanted, ProjectCard.CardFact number) {
        if (!fact.isBilingual()) {
            String display = number != null ? ProjectCard.format(number, wanted) : wanted.formatValue(fact.value());
            return new ResolvedFact(fact.key(), fact.value(), display, fact.factClass(), layer.kind(), layer.id(),
                    fact.confirmedAt(), null, false, fact.claim());
        }
        Language used = fact.text(wanted) != null ? wanted : wanted.other();
        String text = fact.text(used);
        boolean unreviewed = used != wanted || !fact.reviewed().contains(used);
        return new ResolvedFact(fact.key(), text, text, fact.factClass(), layer.kind(), layer.id(), fact.confirmedAt(),
                used, unreviewed, fact.claim());
    }

    /** 深度优先收集本 Project 与全部祖先 Project；出现环时报出整条环。 */
    private void collect(String id, List<String> path, Set<String> visited, List<String> order) {
        if (path.contains(id)) {
            List<String> cycle = new ArrayList<>(path.subList(path.indexOf(id), path.size()));
            cycle.add(id);
            throw new ProfileResolutionException("循环继承：" + String.join(" → ", cycle));
        }
        if (!visited.add(id)) {
            return;
        }
        ProjectSpec spec = load(id);
        order.add(id);
        path.add(id);
        for (String parent : spec.parentIds()) {
            collect(parent, path, visited, order);
        }
        path.removeLast();
    }

    private ProjectSpec load(String id) {
        if (!projects.exists(id)) {
            throw new ProfileResolutionException("父 Project 不存在：" + id);
        }
        return projects.load(id);
    }

    private Base base(ProjectSpec spec) {
        String pin = spec.basePin();
        List<Fact> facts;
        List<ProjectCard> cards = new ArrayList<>();
        if (spec.pinsLive()) {
            facts = new ArrayList<>(FactsFile.read(layout.baseFacts()));
            cards.addAll(projectCards.list());
        } else {
            throw new ProfileResolutionException("Project " + spec.id() + " 的 base_pin 是 " + pin
                    + "：storage 不做版本控制，base_pin 只能是 live");
        }
        Map<Fact, ProjectCard.CardFact> numbers = new IdentityHashMap<>();
        for (ProjectCard card : cards) {
            facts.addAll(cardFacts(card, numbers));
        }
        return new Base(facts, numbers);
    }

    /** 项目卡推出的 {@code projects.<slug>.*} 字段（A 级，来源是项目卡）。 */
    private static List<Fact> cardFacts(ProjectCard card, Map<Fact, ProjectCard.CardFact> numbers) {
        String prefix = "projects." + card.slug() + ".";
        String source = "project-card:" + card.slug();
        List<Fact> facts = new ArrayList<>();
        if (card.title() != null && (card.title().zh() != null || card.title().en() != null)) {
            facts.add(Fact.bilingual(prefix + "title", card.title().zh(), card.title().en(), FactClass.A, source, null,
                    card.reviewed()));
        }
        addValue(facts, prefix + "kind", card.kind(), source);
        if (card.period() != null) {
            addValue(facts, prefix + "period.from", card.period().from(), source);
            addValue(facts, prefix + "period.to", card.period().to(), source);
        }
        addValue(facts, prefix + "team_size", card.teamSize() == null ? null : card.teamSize().toString(), source);
        addValue(facts, prefix + "stack", card.stack().isEmpty() ? null : String.join(", ", card.stack()), source);
        Set<String> linkKinds = new HashSet<>();
        for (ProjectCard.Link link : card.links()) {
            if (link.kind() != null && linkKinds.add(link.kind())) {
                addValue(facts, prefix + "links." + link.kind(), link.url(), source);
            }
        }
        card.facts().forEach((name, number) -> {
            Fact fact = Fact.of(prefix + "facts." + name, number.value(), FactClass.A, source, null);
            numbers.put(fact, number);
            facts.add(fact);
        });
        Map<String, String> en = new HashMap<>();
        card.en().forEach(section -> en.put(section.id(), card.render(section.text(), Language.EN)));
        Set<String> ids = new LinkedHashSet<>();
        card.zh().forEach(section -> ids.add(section.id()));
        card.en().forEach(section -> ids.add(section.id()));
        Map<String, String> zh = new HashMap<>();
        card.zh().forEach(section -> zh.put(section.id(), card.render(section.text(), Language.ZH)));
        for (String id : ids) {
            facts.add(Fact.bilingual(prefix + "sections." + id, zh.get(id), en.get(id), FactClass.A, source, null,
                    card.reviewed()));
        }
        return facts;
    }

    private static void addValue(List<Fact> facts, String key, String value, String source) {
        if (value != null && !value.isBlank()) {
            facts.add(Fact.of(key, value, FactClass.A, source, null));
        }
    }
}
