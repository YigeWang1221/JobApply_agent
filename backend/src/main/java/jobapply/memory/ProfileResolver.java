package jobapply.memory;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import jobapply.domain.Fact;
import jobapply.domain.ProjectSpec;
import jobapply.persistence.FactsFile;
import jobapply.persistence.ProjectRepository;
import jobapply.persistence.StorageGit;
import jobapply.persistence.StorageLayout;

/**
 * 求 Project 的有效资料。先命中先用：
 * 本 Session 答案 → 本 Project overrides → 父 Project overrides（按 inherits 深度优先，读当前版本）
 * → base facts（按本 Project 的 base_pin 取版本）。{@code unset} 屏蔽更低层的同名条目。
 */
public final class ProfileResolver {

    private record Layer(ResolvedFact.Layer kind, String id, List<Fact> facts) {
    }

    private final StorageLayout layout;
    private final ProjectRepository projects;
    private final StorageGit git;

    public ProfileResolver(StorageLayout layout, Clock clock) {
        this.layout = layout;
        this.projects = new ProjectRepository(layout, clock);
        this.git = new StorageGit(layout, clock);
    }

    /** 返回按 key 排序的有效资料。 */
    public Map<String, ResolvedFact> resolve(String projectId, List<Fact> sessionAnswers) {
        ProjectSpec own = load(projectId);
        List<String> chain = new ArrayList<>();
        collect(projectId, new ArrayList<>(), new HashSet<>(), chain);

        List<Layer> layers = new ArrayList<>();
        layers.add(new Layer(ResolvedFact.Layer.SESSION, null, sessionAnswers == null ? List.of() : sessionAnswers));
        for (String id : chain) {
            layers.add(new Layer(ResolvedFact.Layer.PROJECT, id, FactsFile.read(layout.overrides(id))));
        }
        layers.add(new Layer(ResolvedFact.Layer.BASE, own.basePin(), baseFacts(own)));

        Map<String, ResolvedFact> resolved = new TreeMap<>();
        Set<String> seen = new HashSet<>();
        for (Layer layer : layers) {
            for (Fact fact : layer.facts()) {
                if (!seen.add(fact.key()) || fact.unset()) {
                    continue;
                }
                resolved.put(fact.key(), new ResolvedFact(fact.key(), fact.value(), fact.factClass(), layer.kind(),
                        layer.id(), fact.confirmedAt()));
            }
        }
        return Collections.unmodifiableMap(resolved);
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

    private List<Fact> baseFacts(ProjectSpec spec) {
        String pin = spec.basePin();
        if (spec.pinsLive()) {
            return FactsFile.read(layout.baseFacts());
        }
        if (!git.isEnabled()) {
            throw new ProfileResolutionException(
                    "Project " + spec.id() + " 的 base_pin 是 " + pin + "，需要开启 git 才能读取历史版本");
        }
        String path = layout.relative(layout.baseFacts());
        Optional<String> content = spec.pinsDate()
                ? git.readAtEndOfDay(LocalDate.parse(pin), path)
                : git.readAtCommit(pin, path);
        String source = "base/facts.md@" + pin;
        return FactsFile.parse(content.orElseThrow(() -> new ProfileResolutionException(
                "找不到 base_pin " + pin + " 对应的 base/facts.md")), source);
    }
}
