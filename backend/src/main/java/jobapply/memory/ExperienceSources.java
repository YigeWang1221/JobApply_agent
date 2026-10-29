package jobapply.memory;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jobapply.domain.Job;
import jobapply.persistence.FrontmatterFile;
import jobapply.persistence.JobRepository;
import jobapply.persistence.StorageLayout;

/**
 * 经验检索读哪些文件、怎么切成片段（C022）：各工作台的轮次复盘 {@code jobs/*}{@code /rounds/*.md}、填写报告
 * {@code jobs/*}{@code /fill-report.md}（只取「字段」表：表单问题和当时填的回答），以及 {@code review/questions.md}、
 * {@code review/companies/*.md}。Session 对话、profile 和密钥文件不在这里。
 * <p>
 * 每个片段带出处：工作台（job、project）、公司、岗位、阶段、类型、日期、文件路径。片段按标题切，长的按行再切，
 * 表格切开时每段都带表头。
 */
final class ExperienceSources {

    static final String ROUND = "round";
    static final String FILL_REPORT = "fill_report";
    static final String QUESTION_BANK = "question_bank";
    static final String COMPANY_REVIEW = "company_review";
    static final Set<String> TYPES = Set.of(ROUND, FILL_REPORT, QUESTION_BANK, COMPANY_REVIEW);
    /** 一个片段最多多少字：e5 最多看 512 个词元，中文大致一字一个。 */
    private static final int MAX_CHARS = 600;
    private static final Set<String> WRITTEN = Set.of("oa", "written-test", "assessment");

    /** 一个文件读出来的样子：{@code fingerprint} 变了就要重建它的片段。 */
    record Source(String relative, String fingerprint, List<Chunk> chunks) {
    }

    /** 一个片段；{@code id} 由文件路径和序号决定，重建时不变。 */
    record Chunk(String id, String text, Map<String, Object> metadata) {
    }

    private final StorageLayout layout;
    private final JobRepository jobs;

    ExperienceSources(StorageLayout layout) {
        this.layout = layout;
        this.jobs = new JobRepository(layout);
    }

    /** 这个文件是不是检索范围里的（写入通知用它过滤）。 */
    boolean covers(Path file) {
        Path path = file.toAbsolutePath().normalize();
        if (!path.startsWith(layout.root()) || !path.toString().endsWith(".md")) {
            return false;
        }
        Path rel = layout.root().relativize(path);
        int n = rel.getNameCount();
        if (rel.startsWith("review")) {
            return n == 2 && rel.getFileName().toString().equals("questions.md")
                    || n == 3 && rel.getName(1).toString().equals("companies");
        }
        if (!rel.startsWith("projects") || n < 5 || !rel.getName(2).toString().equals("jobs")) {
            return false;
        }
        return n == 5 && rel.getFileName().toString().equals("fill-report.md")
                || n == 6 && rel.getName(4).toString().equals("rounds");
    }

    /** 检索范围里现在有的所有文件，按 storage 里的相对路径（{@code /} 分隔）列出。 */
    List<Path> files() {
        List<Path> out = new ArrayList<>();
        if (Files.isRegularFile(layout.reviewQuestions())) {
            out.add(layout.reviewQuestions());
        }
        out.addAll(list(layout.reviewCompaniesDir()));
        for (Path project : list(layout.projectsDir(), Files::isDirectory)) {
            for (Path job : list(project.resolve("jobs"), Files::isDirectory)) {
                if (Files.isRegularFile(job.resolve("fill-report.md"))) {
                    out.add(job.resolve("fill-report.md"));
                }
                out.addAll(list(job.resolve("rounds")));
            }
        }
        return out;
    }

    String relative(Path file) {
        return layout.root().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    /** 读一个文件并切好片段；文件格式不对时抛异常，由调用方记下并跳过。 */
    Source read(Path file) {
        String relative = relative(file);
        String text = read0(file);
        Path rel = Path.of(relative);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("source", relative);
        String title;
        String body;
        String jobPhase = "";
        if (relative.startsWith("review/")) {
            body = withoutGenerated(text);
            if (relative.equals("review/questions.md")) {
                meta.put("type", QUESTION_BANK);
                title = "常考题库";
            } else {
                meta.put("type", COMPANY_REVIEW);
                title = firstHeading(body);
                int dot = title.lastIndexOf(" · ");
                meta.put("company", dot > 0 ? title.substring(0, dot) : title);
            }
            meta.put("date", date(file));
        } else {
            String projectId = rel.getName(1).toString();
            String jobId = rel.getName(3).toString();
            Job job = jobs.load(projectId, jobId);
            jobPhase = job.status().phase() == null ? "lead" : job.status().phase().value();
            meta.put("job", jobId);
            meta.put("project", projectId);
            meta.put("company", nonNull(job.posting().company()));
            meta.put("title", nonNull(job.posting().title()));
            if (relative.endsWith("/fill-report.md")) {
                meta.put("type", FILL_REPORT);
                meta.put("phase", "prep");
                meta.put("date", date(file));
                title = "填写报告 · " + meta.get("company") + " · " + meta.get("title");
                body = fields(text);
            } else {
                FrontmatterFile.Document doc = FrontmatterFile.read(file);
                Map<String, Object> fm = doc.frontmatter();
                String roundType = fm.get("round") instanceof Map<?, ?> round && round.get("type") != null
                        ? round.get("type").toString() : "other";
                String roundName = fm.get("round") instanceof Map<?, ?> round && round.get("name") != null
                        ? round.get("name").toString() : roundType;
                meta.put("type", ROUND);
                meta.put("round_type", roundType);
                meta.put("phase", phaseOf(roundType));
                Object happened = fm.get("happened_at");
                meta.put("date", happened != null && happened.toString().length() >= 10
                        ? happened.toString().substring(0, 10) : date(file));
                title = meta.get("company") + " · " + meta.get("title") + " · " + roundName;
                body = doc.body();
            }
        }
        // 岗位的阶段变了，出处里的信息也要跟着变
        String fingerprint = sha256(text + "\n" + jobPhase);
        return new Source(relative, fingerprint, chunks(relative, title, body, meta));
    }

    /** 轮次类型对应的阶段：笔试 / OA 是 assessment，电话初筛是 screening，offer 沟通是 offer，其余是 interview。 */
    static String phaseOf(String roundType) {
        if (WRITTEN.contains(roundType)) {
            return "assessment";
        }
        return switch (roundType) {
            case "screen-call" -> "screening";
            case "offer-call" -> "offer";
            default -> "interview";
        };
    }

    /** 按 {@code #}/{@code ##}/{@code ###} 切节，每节一个片段；长的按行切，表格每段都带表头。 */
    private static List<Chunk> chunks(String relative, String title, String body, Map<String, Object> meta) {
        List<String> texts = new ArrayList<>();
        String h2 = null;
        String heading = null;
        List<String> lines = new ArrayList<>();
        for (String line : body.split("\n")) {
            if (line.startsWith("#")) {
                flush(texts, title, heading, lines);
                String text = line.replaceFirst("^#+\\s*", "").strip();
                if (line.startsWith("### ")) {
                    heading = h2 == null ? text : h2 + " / " + text;
                } else if (line.startsWith("## ")) {
                    h2 = text;
                    heading = text;
                } else {
                    h2 = null;
                    heading = null;
                }
                continue;
            }
            lines.add(line);
        }
        flush(texts, title, heading, lines);
        List<Chunk> out = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            Map<String, Object> m = new LinkedHashMap<>(meta);
            m.put("chunk", i);
            out.add(new Chunk(UUID.nameUUIDFromBytes((relative + "#" + i).getBytes(UTF_8)).toString(),
                    texts.get(i), m));
        }
        return out;
    }

    private static void flush(List<String> texts, String title, String heading, List<String> lines) {
        String prefix = heading == null ? title : title + " / " + heading;
        List<String> header = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String raw : lines) {
            String line = raw.stripTrailing();
            if (line.isBlank()) {
                if (!current.isEmpty()) {
                    current.append('\n');
                }
                continue;
            }
            boolean row = line.startsWith("|");
            if (!row) {
                header.clear();
            } else if (header.isEmpty()) {
                header.add(line);
            } else if (header.size() == 1 && separator(line)) {
                header.add(line);
            }
            if (current.length() + line.length() > MAX_CHARS && !current.isEmpty()) {
                add(texts, prefix, current);
                current.setLength(0);
                // 表格切开时，后面每段都带上表头
                if (row && header.size() == 2 && !separator(line)) {
                    current.append(header.get(0)).append('\n').append(header.get(1)).append('\n');
                }
            }
            current.append(line.length() > MAX_CHARS * 2 ? line.substring(0, MAX_CHARS * 2) : line).append('\n');
        }
        add(texts, prefix, current);
        lines.clear();
    }

    private static void add(List<String> texts, String prefix, StringBuilder content) {
        String text = content.toString().strip();
        List<String> lines = text.lines().toList();
        // 空的节、只有表头的表不算片段
        boolean onlyHeader = lines.size() <= 2 && lines.stream().allMatch(line -> line.startsWith("|"))
                && lines.stream().anyMatch(ExperienceSources::separator);
        if (!text.isEmpty() && !onlyHeader) {
            texts.add(prefix + "\n" + text);
        }
    }

    private static boolean separator(String line) {
        return line.matches("\\|[\\s|:-]*-[\\s|:-]*");
    }

    /** 填写报告只取「字段」表，每行写成「页面 · 字段：值」；用户亲填的（C 级）没有值，不要。 */
    private static String fields(String text) {
        StringBuilder out = new StringBuilder("## 字段\n");
        boolean in = false;
        for (String line : text.split("\n")) {
            if (line.startsWith("## ")) {
                in = line.strip().equals("## 字段");
                continue;
            }
            if (!in || !line.startsWith("|") || separator(line) || line.startsWith("| 页面 |")) {
                continue;
            }
            String[] cells = line.substring(1).split("(?<!\\\\)\\|");
            if (cells.length < 3) {
                continue;
            }
            String page = cells[0].strip();
            String label = cells[1].strip();
            String value = cells[2].strip();
            if (value.isEmpty() || value.equals("（用户已填）") || value.equals("—")) {
                continue;
            }
            out.append("- ").append(page.isEmpty() || page.equals("—") ? "" : page + " · ").append(label)
                    .append("：").append(value.replace("\\|", "|")).append('\n');
        }
        return out.toString();
    }

    private static String withoutGenerated(String text) {
        return text.lines().filter(line -> !line.startsWith("> 生成文件")).collect(Collectors.joining("\n"));
    }

    private static String firstHeading(String body) {
        return body.lines().filter(line -> line.startsWith("# ")).findFirst()
                .map(line -> line.substring(2).strip()).orElse("");
    }

    private static String date(Path file) {
        try {
            return LocalDate.ofInstant(Files.getLastModifiedTime(file).toInstant(), ZoneId.systemDefault()).toString();
        } catch (IOException e) {
            return "";
        }
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    private static String read0(Path file) {
        try {
            return Files.readString(file, UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取失败：" + file, e);
        }
    }

    private static List<Path> list(Path dir) {
        return list(dir, file -> Files.isRegularFile(file) && file.toString().endsWith(".md"));
    }

    private static List<Path> list(Path dir, Predicate<Path> filter) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(filter).filter(file -> !file.getFileName().toString().startsWith("."))
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("列目录失败：" + dir, e);
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
