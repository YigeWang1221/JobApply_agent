package jobapply.memory;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import jobapply.domain.Claim;
import jobapply.domain.Fact;
import jobapply.domain.ImportCandidate;
import jobapply.domain.ImportRun;
import jobapply.domain.ProjectCard;
import jobapply.domain.Source;
import jobapply.domain.Timestamps;
import jobapply.persistence.FactsFile;
import jobapply.persistence.ImportRunStore;
import jobapply.persistence.LedgerFile;
import jobapply.persistence.ProjectCardFile;
import jobapply.persistence.SourceRegistry;
import jobapply.persistence.StorageFormatException;
import jobapply.persistence.StorageLayout;
import jobapply.tools.GitHubPublicReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 一次导入的整理（C004-A-2）：确认估算后在后台执行，状态 {@code estimated} → {@code running} → {@code reviewing}
 * → {@code done}，任何一步失败为 {@code failed}（页面显示原因，可以重新开始）。
 * <ol>
 *   <li>每个来源写一张清单卡（{@link SourceInventory}）；</li>
 *   <li>按来源抽取候选（{@link CandidateExtractor}），前面来源抽到的项目交给后面的来源，让同一个项目沿用同一个 slug；</li>
 *   <li>合并并和事实层对比（{@link CandidateMerger}）；经历、技能、成果、指标和项目在台账里各建一条论断，状态
 *       {@code needs_verification}；</li>
 *   <li>等用户逐条决定（{@link FactWriter}）；全部处理完是 {@code done}，保留 {@value #KEEP_DAYS} 天后清理。</li>
 * </ol>
 * 进度、候选、提示、错误和决定都写在导入记录里（{@code progress}、{@code candidates}、{@code warnings}、{@code error}），
 * 关掉页面或重启应用以后可以接着确认。应用在整理途中关闭时，下次读取记为 {@code failed}（「整理被中断」）。
 * 记录里不放原文，只放候选和出处定位。
 */
public final class ImportRunner implements AutoCloseable {

    static final int KEEP_DAYS = 30;
    static final String INTERRUPTED = "整理被中断（应用关闭或重启），可以重新开始";

    /** 导入记录现在的状态不允许这个操作（接口返回 409）。 */
    public static final class StateException extends RuntimeException {

        public StateException(String message) {
            super(message);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ImportRunner.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final Map<String, String> CATEGORY = Map.of("experience", "经历", "skills", "技能",
            "outcomes", "成果", "metrics", "指标", "projects", "项目");

    private final StorageLayout layout;
    private final ImportRunStore runs;
    private final SourceRegistry sources;
    private final SourceInventory inventory;
    private final CandidateExtractor extractor;
    private final FactWriter writer;
    private final LedgerFile ledger;
    private final ProjectCardFile cards;
    private final GitHubPublicReader github;
    private final Supplier<ChatModel> mainModel;
    private final Clock clock;
    private final ExecutorService executor;
    private final Set<String> active = Collections.synchronizedSet(new HashSet<>());

    public ImportRunner(StorageLayout layout, ImportRunStore runs, SourceRegistry sources, SourceInventory inventory,
                        CandidateExtractor extractor, FactWriter writer, LedgerFile ledger, ProjectCardFile cards,
                        GitHubPublicReader github, Supplier<ChatModel> mainModel, Clock clock) {
        this.layout = layout;
        this.runs = runs;
        this.sources = sources;
        this.inventory = inventory;
        this.extractor = extractor;
        this.writer = writer;
        this.ledger = ledger;
        this.cards = cards;
        this.github = github;
        this.mainModel = mainModel;
        this.clock = clock;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "jobapply-import");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 确认估算，开始整理（后台执行）。只有 {@code estimated} 或 {@code failed} 的记录可以开始；有来源超过上限时不能开始。
     * 主模型没有配置时直接报错（不先改状态）。
     */
    public synchronized ImportRun start(String id) {
        ImportRun run = view(id);
        if (run.status() != ImportRun.Status.ESTIMATED && run.status() != ImportRun.Status.FAILED) {
            throw new StateException("这次导入的状态是 " + run.status().code() + "，不能开始整理");
        }
        if (run.estimate() == null) {
            throw new StateException("这次导入还没有估算");
        }
        if (run.estimate().overLimit()) {
            throw new StateException("有来源超过上限，请缩小目录后重新估算");
        }
        mainModel.get();
        runs.purgeDone(Duration.ofDays(KEEP_DAYS));
        Map<String, Object> extra = new LinkedHashMap<>(run.extra());
        extra.remove("error");
        extra.remove("candidates");
        extra.remove("warnings");
        extra.put("started_at", Timestamps.now(clock));
        extra.put("progress", progress("准备", 0, run.sources().size(), 0, 0));
        ImportRun running = with(run, ImportRun.Status.RUNNING, extra);
        runs.save(running);
        active.add(id);
        executor.submit(() -> execute(id));
        return runs.require(id);
    }

    /** 读一条导入记录；记录是 {@code running} 但这个进程里没有在跑时，记为中断（失败）。 */
    public ImportRun view(String id) {
        ImportRun run = runs.require(id);
        if (run.status() == ImportRun.Status.RUNNING && !active.contains(id)) {
            synchronized (this) {
                run = runs.require(id);
                if (run.status() == ImportRun.Status.RUNNING && !active.contains(id)) {
                    Map<String, Object> extra = new LinkedHashMap<>(run.extra());
                    extra.put("error", INTERRUPTED);
                    run = with(run, ImportRun.Status.FAILED, extra);
                    runs.save(run);
                }
            }
        }
        return run;
    }

    /** 这个进程里是不是还在整理这条记录（状态写进文件以后、提交 git 之前也算在跑）。 */
    boolean isRunning(String id) {
        return active.contains(id);
    }

    public List<ImportRun> list() {
        return runs.list().stream().map(run -> view(run.id())).toList();
    }

    /**
     * 用户对一个候选的决定（{@link FactWriter}）。只有 {@code reviewing} 的记录可以决定；已经决定过的候选不能再改。
     * 全部候选都有决定时记录变成 {@code done}。
     */
    public synchronized ImportRun decide(String id, String candidateId, String decision, Map<String, Object> value,
                                         String use) {
        ImportRun run = view(id);
        if (run.status() != ImportRun.Status.REVIEWING) {
            throw new StateException("这次导入的状态是 " + run.status().code() + "，不能再确认候选");
        }
        List<ImportCandidate> candidates = candidates(run);
        int index = -1;
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).id().equals(candidateId)) {
                index = i;
            }
        }
        if (index < 0) {
            throw new NoSuchElementException("这次导入里没有这个候选：" + candidateId);
        }
        ImportCandidate candidate = candidates.get(index);
        if (candidate.decision() != null) {
            throw new StateException("这个候选已经处理过（" + candidate.decision().decision() + "）");
        }
        ImportCandidate.Decision result = writer.apply(id, candidate, decision, value, use);
        candidates.set(index, candidate.withDecision(result));
        Map<String, Object> extra = new LinkedHashMap<>(run.extra());
        extra.put("candidates", toMaps(candidates));
        boolean finished = candidates.stream().allMatch(c -> c.decision() != null);
        if (finished) {
            extra.put("finished_at", Timestamps.now(clock));
        }
        ImportRun next = with(run, finished ? ImportRun.Status.DONE : ImportRun.Status.REVIEWING, extra);
        runs.save(next);
        return runs.require(id);
    }

    /** 导入记录里的候选。 */
    public static List<ImportCandidate> candidates(ImportRun run) {
        Object list = run.extra().get("candidates");
        if (!(list instanceof List<?> items)) {
            return new ArrayList<>();
        }
        List<ImportCandidate> candidates = new ArrayList<>();
        for (Object item : items) {
            candidates.add(JSON.convertValue(item, ImportCandidate.class));
        }
        return candidates;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    /**
     * 取模型。取的时候出错（没有配置、Key 没设）转成这一步的失败，信息给页面看。
     */
    static <T> Optional<T> model(Supplier<Optional<T>> supplier, String step) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            throw new StructuredCall.StepFailed(step + "：模型不可用（" + e.getMessage() + "）");
        }
    }

    // ---------------------------------------------------------------- 后台

    private void execute(String id) {
        int[] modelCalls = {0};
        int githubBefore = github.calls();
        try {
            ImportRun run = runs.require(id);
            Set<String> identities = ImportEstimator.identities(layout);
            List<String> warnings = new ArrayList<>();
            List<SourceInventory.Inventory> inventories = new ArrayList<>();
            int done = 0;
            for (String key : run.sources()) {
                Source source = sources.find(key)
                        .orElseThrow(() -> new StructuredCall.StepFailed("来源 " + key + " 已经删除登记，请重新估算"));
                ImportRun.SourceEstimate estimate = run.estimate().sources().stream()
                        .filter(s -> s.key().equals(key)).findFirst().orElse(null);
                int sourcesDone = done;
                SourceInventory.Inventory card = inventory.build(source, estimate, identities, message ->
                        progress(id, message, sourcesDone, run.sources().size(), modelCalls[0],
                                github.calls() - githubBefore));
                modelCalls[0] += card.modelCalls();
                card.warnings().forEach(warning -> warnings.add(key + "：" + warning));
                inventories.add(card);
                done++;
                progress(id, "清单卡完成 " + key, done, run.sources().size(), modelCalls[0],
                        github.calls() - githubBefore);
            }
            List<Fact> facts = FactsFile.read(layout.baseFacts());
            List<ProjectCard> existingCards = cards.list();
            List<Map<String, Object>> knownProjects = new ArrayList<>();
            existingCards.forEach(card -> knownProjects.add(project(card.slug(), card.aliases(), card.title())));
            List<CandidateExtractor.Drafts> drafts = new ArrayList<>();
            for (SourceInventory.Inventory card : inventories) {
                progress(id, "抽取候选 " + card.sourceKey(), done, run.sources().size(), modelCalls[0],
                        github.calls() - githubBefore);
                CandidateExtractor.Drafts extracted = extractor.extract(card, facts, List.copyOf(knownProjects));
                modelCalls[0] += extracted.modelCalls();
                extracted.projects().forEach(p -> knownProjects.add(project(p.slug(), p.aliases(),
                        new ProjectCard.Text(p.titleZh(), p.titleEn()))));
                drafts.add(extracted);
            }
            progress(id, "合并和对比", done, run.sources().size(), modelCalls[0], github.calls() - githubBefore);
            CandidateMerger.Result merged = CandidateMerger.merge(drafts, facts, existingCards);
            warnings.addAll(merged.warnings());
            List<ImportCandidate> candidates = withClaims(merged.candidates());

            ImportRun latest = runs.require(id);
            Map<String, Object> extra = new LinkedHashMap<>(latest.extra());
            extra.put("candidates", toMaps(candidates));
            extra.put("warnings", warnings);
            extra.put("progress", progress("等你确认", done, run.sources().size(), modelCalls[0],
                    github.calls() - githubBefore));
            boolean finished = candidates.stream().allMatch(c -> c.decision() != null);
            if (finished) {
                extra.put("finished_at", Timestamps.now(clock));
            }
            runs.save(with(latest, finished ? ImportRun.Status.DONE : ImportRun.Status.REVIEWING, extra));
            log.info("导入 {} 整理完成：候选 {} 个，模型请求 {} 次", id, candidates.size(), modelCalls[0]);
        } catch (RuntimeException e) {
            // 这几类异常的信息只有原因、路径或仓库名，没有文件内容，可以给页面看
            String message = e instanceof StructuredCall.StepFailed || e instanceof GitHubPublicReader.GitHubException
                    || e instanceof StorageFormatException || e instanceof IllegalArgumentException ? e.getMessage()
                    : "整理出错（" + e.getClass().getSimpleName() + "）";
            log.warn("导入 {} 整理失败：{}", id, e.getClass().getSimpleName());
            try {
                ImportRun latest = runs.require(id);
                Map<String, Object> extra = new LinkedHashMap<>(latest.extra());
                extra.put("error", message);
                Object progress = extra.get("progress");
                if (progress instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    map.forEach((k, v) -> copy.put(String.valueOf(k), v));
                    copy.put("model_calls", modelCalls[0]);
                    copy.put("github_calls", github.calls() - githubBefore);
                    extra.put("progress", copy);
                }
                runs.save(with(latest, ImportRun.Status.FAILED, extra));
            } catch (RuntimeException saveError) {
                log.error("导入 {} 的失败状态没有写进记录", id, saveError);
            }
        } finally {
            active.remove(id);
        }
    }

    /** 经历、技能、成果、指标和项目候选（「相同」的除外）在台账里各建一条论断，状态 needs_verification。 */
    private List<ImportCandidate> withClaims(List<ImportCandidate> candidates) {
        List<Integer> indexes = new ArrayList<>();
        List<Claim> drafts = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            ImportCandidate candidate = candidates.get(i);
            String category = CATEGORY.get(candidate.group());
            if (category == null || ImportCandidate.SAME.equals(candidate.comparison())) {
                continue;
            }
            String text = candidate.evidence() != null ? candidate.evidence()
                    : candidate.forProject() ? title(candidate.project()) + " 项目"
                    : candidate.zh() != null ? candidate.zh() : candidate.en() != null ? candidate.en()
                    : candidate.key() + "：" + candidate.value();
            ImportCandidate.Origin origin = candidate.origins().stream().filter(o -> o.locator() != null).findFirst()
                    .orElse(candidate.origins().isEmpty() ? null : candidate.origins().getFirst());
            drafts.add(Claim.draft(category, cell(text), origin == null ? null : origin.source(),
                    origin == null || origin.locator() == null ? null : cell(origin.locator()),
                    Claim.Status.NEEDS_VERIFICATION));
            indexes.add(i);
        }
        List<ImportCandidate> result = new ArrayList<>(candidates);
        List<Claim> added = ledger.addAll(drafts);
        for (int i = 0; i < added.size(); i++) {
            int index = indexes.get(i);
            result.set(index, result.get(index).withClaim(added.get(i).id()));
        }
        return result;
    }

    private void progress(String id, String message, int done, int total, int calls, int githubCalls) {
        synchronized (this) {
            ImportRun run = runs.require(id);
            Map<String, Object> extra = new LinkedHashMap<>(run.extra());
            extra.put("progress", progress(message, done, total, calls, githubCalls));
            runs.save(with(run, run.status(), extra));
        }
    }

    private static Map<String, Object> progress(String message, int done, int total, int calls, int githubCalls) {
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("message", message);
        progress.put("sources_done", done);
        progress.put("sources_total", total);
        progress.put("model_calls", calls);
        progress.put("github_calls", githubCalls);
        return progress;
    }

    private static Map<String, Object> project(String slug, List<String> aliases, ProjectCard.Text title) {
        Map<String, Object> project = new LinkedHashMap<>();
        project.put("slug", slug);
        project.put("aliases", aliases);
        project.put("title_zh", title == null ? null : title.zh());
        project.put("title_en", title == null ? null : title.en());
        return project;
    }

    private static String title(ProjectCard card) {
        if (card.title() == null) {
            return card.slug();
        }
        return card.title().zh() != null ? card.title().zh() : card.title().en() != null ? card.title().en()
                : card.slug();
    }

    /** 台账的单元格不能有竖线和换行。 */
    private static String cell(String text) {
        return text.replace('|', '／').replace('\n', ' ').replace('\r', ' ').strip();
    }

    private static List<Map<String, Object>> toMaps(List<ImportCandidate> candidates) {
        return candidates.stream().map(candidate -> JSON.convertValue(candidate, MAP)).toList();
    }

    private static ImportRun with(ImportRun run, ImportRun.Status status, Map<String, Object> extra) {
        return new ImportRun(run.id(), status, run.createdAt(), run.updatedAt(), run.sources(), run.estimate(),
                extra);
    }
}
