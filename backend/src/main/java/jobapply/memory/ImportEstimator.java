package jobapply.memory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import jobapply.domain.Fact;
import jobapply.domain.ImportRun;
import jobapply.domain.Language;
import jobapply.domain.Source;
import jobapply.domain.SourceScan;
import jobapply.persistence.FactsFile;
import jobapply.persistence.ImportRunStore;
import jobapply.persistence.SourceRegistry;
import jobapply.persistence.StorageLayout;
import jobapply.tools.GitHubPublicReader;
import jobapply.tools.LocalDirectoryScanner;

/**
 * 导入前的估算：对选中的来源做只读扫描，列出会读的文件、跳过的文件（只有名字和原因）、文字量、预计的模型调用次数
 * 和 GitHub 调用次数，建一条 {@code estimated} 的导入记录，并在 {@code sources.md} 里记下这次扫描。
 * 不调用模型；「开始整理」在 C004-A-2。
 * <p>
 * 模型调用的估算（C004-A-2 的流程）：每个来源的文字按 {@value #CHARS_PER_CALL} 字一批，多于一批时每批先摘要一次；
 * 再加清单卡一次、拆候选一次；多个来源时最后合并一次。没有可读内容的来源不调用。
 */
public final class ImportEstimator {

    static final int CHARS_PER_CALL = 60_000;

    private final StorageLayout layout;
    private final SourceRegistry sources;
    private final ImportRunStore runs;
    private final LocalDirectoryScanner scanner;
    private final GitHubPublicReader github;
    private final LocalDirectoryScanner.Limits limits;

    public ImportEstimator(StorageLayout layout, SourceRegistry sources, ImportRunStore runs,
                           LocalDirectoryScanner scanner, GitHubPublicReader github,
                           LocalDirectoryScanner.Limits limits) {
        this.layout = layout;
        this.sources = sources;
        this.runs = runs;
        this.scanner = scanner;
        this.github = github;
        this.limits = limits;
    }

    public ImportRun estimate(List<String> sourceKeys) {
        if (sourceKeys == null || sourceKeys.isEmpty()) {
            throw new IllegalArgumentException("至少选一个来源");
        }
        List<Source> selected = new ArrayList<>();
        for (String key : new LinkedHashSet<>(sourceKeys)) {
            selected.add(sources.find(key).orElseThrow(() -> new IllegalArgumentException("来源不存在：" + key)));
        }
        Set<String> identities = identities();
        List<ImportRun.SourceEstimate> estimates = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        long chars = 0;
        int modelCalls = 0;
        int used = 0;
        int planned = 0;
        boolean overLimit = false;
        for (Source source : selected) {
            SourceScan scan = scan(source, identities);
            int calls = modelCalls(scan.chars());
            int githubPlanned = source.type() == Source.Type.GITHUB ? GitHubPublicReader.plannedCalls(scan) : 0;
            estimates.add(new ImportRun.SourceEstimate(source.key(), source.type().code(), source.location(), scan,
                    calls, githubPlanned));
            scan.warnings().forEach(warning -> warnings.add(source.key() + "：" + warning));
            if (scan.chars() == 0) {
                warnings.add(source.key() + "：没有可读的内容");
            }
            chars += scan.chars();
            modelCalls += calls;
            used += scan.githubCalls();
            planned += githubPlanned;
            overLimit |= scan.overLimit();
        }
        // 全部扫完再记，避免一个来源失败时前面的来源已经改了 sources.md
        for (ImportRun.SourceEstimate estimate : estimates) {
            int fileCount = (int) estimate.scan().files().stream().filter(file -> !file.kind().equals("tree")).count();
            sources.scanned(estimate.key(), estimate.scan().version(), fileCount);
        }
        if (estimates.stream().filter(estimate -> estimate.modelCalls() > 0).count() > 1) {
            modelCalls++;
        }
        if (overLimit) {
            warnings.add("有来源超过上限（单个来源 " + limits.maxFiles() + " 个文件、" + limits.maxChars()
                    + " 个字符），请缩小目录后再估算");
        }
        return runs.create(selected.stream().map(Source::key).toList(),
                new ImportRun.Estimate(estimates, chars, modelCalls, used, planned, warnings, overLimit));
    }

    /** 一个来源的模型调用次数：多于一批时每批摘要一次，加清单卡、拆候选各一次。 */
    static int modelCalls(long chars) {
        if (chars <= 0) {
            return 0;
        }
        long batches = (chars + CHARS_PER_CALL - 1) / CHARS_PER_CALL;
        return (int) (batches > 1 ? batches : 0) + 2;
    }

    private SourceScan scan(Source source, Set<String> identities) {
        if (source.type() == Source.Type.DIR) {
            Path root = LocalDirectoryScanner.validateRoot(source.location(), layout.root());
            return scanner.scan(root, identities);
        }
        GitHubPublicReader.Ref ref = GitHubPublicReader.parse(source.location());
        return github.scan(ref.owner(), ref.repo(), limits);
    }

    /** 用户确认过的邮箱和名字（{@code contact.email*}、{@code contact.name*}），用来算本人提交占比。 */
    private Set<String> identities() {
        Set<String> identities = new LinkedHashSet<>();
        for (Fact fact : FactsFile.read(layout.baseFacts())) {
            if (fact.unset() || !(fact.key().startsWith("contact.email") || fact.key().startsWith("contact.name"))) {
                continue;
            }
            if (fact.value() != null) {
                identities.add(fact.value());
            }
            for (Language language : Language.values()) {
                if (fact.text(language) != null) {
                    identities.add(fact.text(language));
                }
            }
        }
        return identities;
    }
}
