package jobapply.tools;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jobapply.domain.SourceScan;
import jobapply.domain.Timestamps;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.ignore.IgnoreNode;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/**
 * 本地目录的只读扫描（C004-A-1「来源登记与扫描规则」）。不跟随符号链接，不越出登记的目录；秘密文件和生成目录
 * 只按名字跳过、不打开（{@link SecretFileRules}）；{@code .gitignore} 忽略的路径跳过。读取范围：文档（md、txt、pdf、
 * docx）、依赖清单、结构化内容（yaml、json，单个不超过上限）；源代码只取开头几行。{@code .git/} 不扫描，
 * 用 JGit 只读读取提交摘要。结果里只有文件名、字符数和原因，不含文件内容。
 */
public final class LocalDirectoryScanner {

    /**
     * @param maxFiles           单个来源最多读取的文件数
     * @param maxChars           单个来源最多读取的字符数
     * @param maxStructuredBytes 单个 yaml / json 的上限
     * @param sourceHeadLines    源代码只读开头这么多行
     */
    public record Limits(int maxFiles, long maxChars, long maxStructuredBytes, int sourceHeadLines) {
    }

    static final long TEXT_MAX_BYTES = 2L * 1024 * 1024;
    static final long DOC_MAX_BYTES = 20L * 1024 * 1024;
    static final int MAX_SKIPPED_LISTED = 300;
    static final int RECENT_TITLES = 5;
    static final int MAX_COMMITS = 50_000;

    private static final Set<String> DOCS = Set.of("md", "markdown", "txt");
    private static final Set<String> BINARY_DOCS = Set.of("pdf", "docx");
    private static final Set<String> STRUCTURED = Set.of("yaml", "yml", "json");
    private static final Set<String> MANIFESTS = Set.of("package.json", "pom.xml", "build.gradle",
            "build.gradle.kts", "pyproject.toml", "go.mod", "cargo.toml");
    private static final Set<String> SOURCES = Set.of("java", "kt", "kts", "scala", "groovy", "go", "rs", "py", "js",
            "jsx", "mjs", "cjs", "ts", "tsx", "c", "h", "cc", "cpp", "hpp", "cs", "rb", "php", "swift", "m", "mm",
            "sh", "bash", "zsh", "sql", "vue", "svelte", "astro", "html", "css", "scss", "lua", "dart", "r", "jl",
            "toml", "xml", "gradle", "proto");

    /** Tika 实例是线程安全的，创建慢，全局共用一个。 */
    private static final Tika TIKA = new Tika();

    private final Limits limits;

    public LocalDirectoryScanner(Limits limits) {
        this.limits = limits;
    }

    /**
     * 校验用户给的目录：绝对路径、存在、可读；不能是 storage 本身、它的上级或它里面的目录。返回真实路径。
     */
    public static Path validateRoot(String location, Path storageRoot) {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("目录路径不能为空");
        }
        Path path = Path.of(location.strip());
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("要写目录的绝对路径：" + location);
        }
        if (!Files.isDirectory(path) || !Files.isReadable(path)) {
            throw new IllegalArgumentException("目录不存在或不可读：" + location);
        }
        Path real;
        Path storage;
        try {
            real = path.toRealPath();
            storage = Files.exists(storageRoot) ? storageRoot.toRealPath() : storageRoot.toAbsolutePath().normalize();
        } catch (IOException e) {
            throw new IllegalArgumentException("目录不存在或不可读：" + location);
        }
        if (storage.startsWith(real) || real.startsWith(storage)) {
            throw new IllegalArgumentException("不能登记 storage 本身、它的上级目录或它里面的目录：" + real);
        }
        return real;
    }

    /**
     * @param identities 用户确认过的邮箱或名字，用来算本人提交占比；空集合时不算
     */
    public SourceScan scan(Path root, Set<String> identities) {
        Walk walk = new Walk(root);
        walk.directory(root, List.of());
        List<String> warnings = new ArrayList<>(walk.warnings);
        if (walk.skippedTotal > MAX_SKIPPED_LISTED) {
            warnings.add("跳过的文件太多，只列出前 " + MAX_SKIPPED_LISTED + " 个（共 " + walk.skippedTotal + " 个）");
        }
        SourceScan.CommitSummary commits = null;
        if (Files.isDirectory(root.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
            commits = commitSummary(root.resolve(".git"), identities);
            if (commits == null) {
                warnings.add("提交历史读不了，没有提交摘要");
            }
        }
        return new SourceScan("files:" + walk.listHash(), walk.files, walk.skipped, walk.chars, commits, warnings, 0,
                walk.filesWarned || walk.charsWarned);
    }

    /** 一次遍历的状态。目录内按名字排序，结果是确定的。 */
    private final class Walk {

        final Path root;
        final List<SourceScan.FileEntry> files = new ArrayList<>();
        final List<SourceScan.Skipped> skipped = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final StringBuilder listing = new StringBuilder();
        long chars;
        int skippedTotal;
        boolean filesWarned;
        boolean charsWarned;

        /** 从根到当前目录的 .gitignore：目录（相对根）和规则。 */
        record Ignore(String dir, IgnoreNode node) {
        }

        Walk(Path root) {
            this.root = root;
        }

        void directory(Path dir, List<Ignore> ignores) {
            List<Ignore> here = new ArrayList<>(ignores);
            Path gitignore = dir.resolve(".gitignore");
            if (Files.isRegularFile(gitignore, LinkOption.NOFOLLOW_LINKS)) {
                IgnoreNode node = new IgnoreNode();
                try (InputStream in = Files.newInputStream(gitignore)) {
                    node.parse(in);
                    here.add(new Ignore(relative(dir), node));
                } catch (IOException e) {
                    warnings.add(relative(gitignore) + " 读不了，没有按它跳过");
                }
            }
            List<Path> children;
            try (Stream<Path> list = Files.list(dir)) {
                children = list.sorted().toList();
            } catch (IOException e) {
                skip(relative(dir) + "/", "目录不可读");
                return;
            }
            for (Path child : children) {
                String rel = relative(child);
                String name = child.getFileName().toString();
                BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (IOException e) {
                    skip(rel, "读不了文件属性");
                    continue;
                }
                if (attrs.isSymbolicLink()) {
                    skip(rel, "符号链接，不跟随");
                    continue;
                }
                if (attrs.isDirectory()) {
                    Optional<String> reason = SecretFileRules.skippedDirectory(name);
                    if (reason.isPresent()) {
                        skip(rel + "/", reason.get());
                    } else if (ignored(here, rel, true)) {
                        skip(rel + "/", "被 .gitignore 忽略");
                    } else {
                        directory(child, here);
                    }
                    continue;
                }
                if (!attrs.isRegularFile()) {
                    skip(rel, "不是普通文件");
                } else if (SecretFileRules.isSecret(name)) {
                    skip(rel, SecretFileRules.SECRET);
                } else if (ignored(here, rel, false)) {
                    skip(rel, "被 .gitignore 忽略");
                } else {
                    file(child, rel, name, attrs.size());
                }
            }
        }

        void file(Path path, String rel, String name, long size) {
            String kind = kind(name);
            if (kind == null) {
                skip(rel, "不在读取范围（二进制或其他类型）");
                return;
            }
            if (kind.equals("structured") && size > limits.maxStructuredBytes()) {
                skip(rel, "结构化文件超过 " + limits.maxStructuredBytes() / 1024 + "KB");
                return;
            }
            boolean binaryDoc = BINARY_DOCS.contains(extension(name));
            if (size > (binaryDoc ? DOC_MAX_BYTES : TEXT_MAX_BYTES)) {
                skip(rel, "文件太大");
                return;
            }
            if (files.size() >= limits.maxFiles()) {
                if (!filesWarned) {
                    warnings.add("超过 " + limits.maxFiles() + " 个文件的上限，后面的文件没有读；请缩小目录");
                    filesWarned = true;
                }
                skip(rel, "超过文件数上限");
                return;
            }
            if (chars >= limits.maxChars()) {
                warnCharsOnce();
                skip(rel, "超过字符数上限");
                return;
            }
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(path);
            } catch (IOException e) {
                skip(rel, "文件不可读");
                return;
            }
            long count;
            try {
                count = binaryDoc ? tikaChars(bytes, name) : kind.equals("source") ? headChars(bytes)
                        : decode(bytes).length();
            } catch (IOException | RuntimeException | TikaException e) {
                skip(rel, "读不出文字");
                return;
            }
            if (chars + count > limits.maxChars()) {
                warnCharsOnce();
                skip(rel, "超过字符数上限");
                return;
            }
            files.add(new SourceScan.FileEntry(rel, kind, count));
            chars += count;
            listing.append(rel).append('\t').append(sha256(bytes)).append('\n');
        }

        void warnCharsOnce() {
            if (!charsWarned) {
                warnings.add("超过 " + limits.maxChars() + " 个字符的上限，超出的文件没有读；请缩小目录");
                charsWarned = true;
            }
        }

        long tikaChars(byte[] bytes, String name) throws IOException, TikaException {
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, name);
            int budget = (int) Math.min(Integer.MAX_VALUE - 1, Math.max(1, limits.maxChars() - chars + 1));
            try (InputStream in = new ByteArrayInputStream(bytes)) {
                String text = TIKA.parseToString(in, metadata, budget);
                return text == null ? 0 : text.strip().length();
            }
        }

        long headChars(byte[] bytes) throws IOException {
            try (BufferedReader reader = new BufferedReader(new StringReader(decode(bytes)))) {
                long count = 0;
                String line;
                for (int i = 0; i < limits.sourceHeadLines() && (line = reader.readLine()) != null; i++) {
                    count += line.length() + 1;
                }
                return count;
            }
        }

        boolean ignored(List<Ignore> ignores, String rel, boolean directory) {
            for (int i = ignores.size() - 1; i >= 0; i--) {
                Ignore ignore = ignores.get(i);
                String path = ignore.dir().isEmpty() ? rel : rel.substring(ignore.dir().length() + 1);
                Boolean result = ignore.node().checkIgnored(path, directory);
                if (result != null) {
                    return result;
                }
            }
            return false;
        }

        void skip(String rel, String reason) {
            skippedTotal++;
            if (skipped.size() < MAX_SKIPPED_LISTED) {
                skipped.add(new SourceScan.Skipped(rel, reason));
            }
        }

        String relative(Path path) {
            return root.relativize(path).toString().replace('\\', '/');
        }

        String listHash() {
            return sha256(listing.toString().getBytes(UTF_8)).substring(0, 16);
        }
    }

    /** 文件归到哪一类；不在读取范围时返回 null。 */
    static String kind(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (MANIFESTS.contains(lower) || (lower.startsWith("requirements") && lower.endsWith(".txt"))) {
            return "manifest";
        }
        String ext = extension(lower);
        if (DOCS.contains(ext) || BINARY_DOCS.contains(ext)) {
            return "doc";
        }
        if (STRUCTURED.contains(ext)) {
            return "structured";
        }
        if (SOURCES.contains(ext)) {
            return "source";
        }
        return null;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 按 UTF-8 解码；不合法的字节换成替换字符，不报错。 */
    private static String decode(byte[] bytes) {
        try {
            return UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, UTF_8);
        }
    }

    /** 只读打开仓库，数提交、取时间范围、本人占比和最近的几条标题。读不了时返回 null（由调用方报出）。 */
    static SourceScan.CommitSummary commitSummary(Path gitDir, Set<String> identities) {
        Set<String> me = identities.stream().map(id -> id.strip().toLowerCase(Locale.ROOT))
                .filter(id -> !id.isEmpty()).collect(Collectors.toSet());
        try (Repository repository = new FileRepositoryBuilder().setGitDir(gitDir.toFile()).setMustExist(true)
                .build(); Git git = new Git(repository)) {
            if (repository.resolve("HEAD") == null) {
                return new SourceScan.CommitSummary(0, null, null, null, List.of());
            }
            int count = 0;
            int own = 0;
            String first = null;
            String last = null;
            List<String> titles = new ArrayList<>();
            for (RevCommit commit : git.log().setMaxCount(MAX_COMMITS).call()) {
                PersonIdent author = commit.getAuthorIdent();
                String when = Timestamps.format(OffsetDateTime.ofInstant(author.getWhenAsInstant(),
                        author.getZoneId()));
                if (last == null) {
                    last = when;
                }
                first = when;
                count++;
                if (me.contains(author.getEmailAddress().toLowerCase(Locale.ROOT))
                        || me.contains(author.getName().toLowerCase(Locale.ROOT))) {
                    own++;
                }
                if (titles.size() < RECENT_TITLES) {
                    String title = commit.getFullMessage().lines().findFirst().orElse("").strip();
                    titles.add(title.length() > 80 ? title.substring(0, 80) + "…" : title);
                }
            }
            Integer percent = me.isEmpty() || count == 0 ? null : Math.round(own * 100f / count);
            return new SourceScan.CommitSummary(count, first, last, percent, titles);
        } catch (IOException | GitAPIException | RuntimeException e) {
            return null;
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
