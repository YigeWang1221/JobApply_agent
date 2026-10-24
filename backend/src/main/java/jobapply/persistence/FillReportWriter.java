package jobapply.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jobapply.domain.FactClass;
import jobapply.domain.Language;
import jobapply.domain.Placeholder;
import jobapply.domain.Timestamps;
import jobapply.memory.ResolvedFact;

/**
 * 写 {@code jobs/<job-id>/fill-report.md}：填写过程中是草稿（头部标「草稿」，每次整份重写），到关卡 B 时定稿。
 * 格式见 C004-B「关键规则 · 填写报告」。
 * <p>
 * C 级字段只能用 {@link UserFilled} 记录（这个类型没有值），或者系统填的占位符 {@code {KEY}}（C014-D），所以系统不可能把
 * C 级的真实值写进报告。
 * 草稿和定稿写同一个文件，草稿的头部标「草稿」。
 * <p>
 * {@link #load} 把自己写出的文件读回成 {@link Report}（字段行读成 {@link Recorded}，原样保留单元格），应用重启后
 * 填写循环从这里接着累积，填过的值只在这个文件里，不进 Checkpoint。
 */
public final class FillReportWriter {

    static final String DRAFT = "草稿";
    static final String INFERRED = "模型推断";
    /** 申请 Agent 从简历原文取的值（{@code fact: resume}）。 */
    public static final String RESUME_SOURCE = "简历原文";
    /** 申请 Agent 起草的 Cover Letter（{@code fact: cover_letter}，C014）。 */
    public static final String COVER_LETTER_SOURCE = "Cover Letter：Agent 起草";
    static final String USER_FILLED_VALUE = "（用户已填）";
    static final String USER_FILLED_SOURCE = "用户亲填";
    private static final String NONE = "—";
    private static final String NONE_ITEM = "无";
    private static final String UNREVIEWED_MARK = "文字未审阅";
    private static final Pattern HEADER = Pattern.compile("^# 填写报告 · (.*?) · (.*)（第 (\\d+) 轮，.*）$");
    private static final Pattern UNREVIEWED_SUMMARY = Pattern.compile("^\\d+ 个字段用了未审阅的译文$");
    private static final Pattern GUARD_LINE = Pattern.compile("^- 「(.*)」：(.*)$");

    /** 模型给的置信度（可以不给，报告里记为「—」）。 */
    public enum Confidence {
        HIGH, MEDIUM, LOW;

        public String code() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Confidence of(String code) {
            for (Confidence confidence : values()) {
                if (confidence.code().equalsIgnoreCase(code == null ? "" : code.strip())) {
                    return confidence;
                }
            }
            throw new IllegalArgumentException("不认识的置信度（只有 high、medium、low）：" + code);
        }
    }

    /** 报告里的一行字段。 */
    public sealed interface Field permits Filled, UserFilled, Recorded {

        String page();

        String label();
    }

    /**
     * 系统填进去的 A / B 级字段。
     *
     * @param value     填进页面的值
     * @param fact      取值用的资料（来源层取 {@link ResolvedFact#label()}）；模型给 {@code fact: none} 时为 null，记为「模型推断」
     * @param factClass 分级；有 {@code fact} 时以资料的分级为准，没有时由调用方给出（A 或 B）
     * @param language  这一项按哪种语言取值；为 null 时取资料文字所用的语言，与语言无关的字段为 null
     * @param replaced  页面上原来的值（例如简历解析写错、模型改正之前的值）；没有改正时为 null
     */
    public record Filled(String page, String label, String value, ResolvedFact fact, Confidence confidence,
                         FactClass factClass, Language language, String replaced) implements Field {

        public Filled {
            requireText(label, "字段名");
            if (value == null) {
                throw new IllegalArgumentException("填进去的值不能为空：" + label);
            }
            if (fact != null) {
                factClass = fact.factClass();
                language = language == null ? fact.language() : language;
            }
            // C 级只能记占位符 {KEY}（C014-D：真实值只在发给浏览器的那一刻替换，不进报告）
            if (factClass == null || factClass == FactClass.C && !Placeholder.is(value)) {
                throw new IllegalArgumentException("系统填写的字段只能是 A 或 B 级，或者值是占位符的 C 级（其它 C 级用 UserFilled）："
                        + label);
            }
        }

        /** 按资料取值的字段。 */
        public static Filled fromFact(String page, String label, String value, ResolvedFact fact,
                                      Confidence confidence, Language language) {
            if (fact == null) {
                throw new IllegalArgumentException("缺少资料：" + label);
            }
            return new Filled(page, label, value, fact, confidence, fact.factClass(), language, null);
        }

        /** 模型推断的字段（{@code fact: none}）。 */
        public static Filled inferred(String page, String label, String value, Confidence confidence,
                                      FactClass factClass, Language language) {
            return new Filled(page, label, value, null, confidence, factClass, language, null);
        }

        /** 页面上原来有一个不同的值（例如简历解析写错），系统改正了它。 */
        public Filled replacing(String pageValue) {
            return new Filled(page, label, value, fact, confidence, factClass, language, pageValue);
        }

        /** 填进去的值和资料原值不一致（例如格式改写）时的资料原值；一致或没有资料时为 null。 */
        public String original() {
            if (fact == null || value.equals(fact.display()) || value.equals(fact.value())) {
                return null;
            }
            return fact.display() != null ? fact.display() : fact.value();
        }

        /** 用了还没审阅的文字（模型起草的译文，或者用另一种语言代替）。 */
        public boolean unreviewed() {
            return fact != null && fact.unreviewed();
        }
    }

    /** C 级字段：由用户亲自填写。这个类型没有值。 */
    public record UserFilled(String page, String label) implements Field {

        public UserFilled {
            requireText(label, "字段名");
        }
    }

    /**
     * 从已经写出的报告里读回的一行 A / B 级字段：单元格原样保留（值、来源层、语言、置信度、分级），再写出时不变。
     * 由 {@link #load} / {@link #parse} 构造；申请 Agent 从简历原文取的值也用它（来源层写 {@link #RESUME_SOURCE}）。
     */
    public record Recorded(String page, String label, String value, String source, String language,
                           String confidence, String grade) implements Field {

        public Recorded {
            requireText(label, "字段名");
        }

        /** 来源层里标了「未审阅」。 */
        public boolean unreviewed() {
            return source != null && source.contains(UNREVIEWED_MARK);
        }
    }

    /** 上传的附件。 */
    public record Attachment(String fileName, String assetKey, long size, String sha256, String readBack) {

        public Attachment {
            requireText(fileName, "文件名");
        }
    }

    /** 守卫记录的结果。 */
    public enum GuardOutcome {
        REJECTED("被守卫拒绝"),
        NEEDS_CONFIRM("要用户确认用途"),
        CONFIRMED_SAFE("用户确认：不会提交"),
        CONFIRMED_UNSAFE("用户确认：会提交，已停下"),
        HANDOFF("转交用户");

        private final String text;

        GuardOutcome(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    /** 守卫记录：只记按钮（或字段）的名字和结果。 */
    public record GuardNote(String button, GuardOutcome outcome) {

        public GuardNote {
            requireText(button, "按钮名字");
            if (outcome == null) {
                throw new IllegalArgumentException("缺少守卫结果：" + button);
            }
        }
    }

    /**
     * 一份报告的内容。
     *
     * @param round      第几轮（关卡 B 要求修改后加一）
     * @param unresolved 未解决的问题（问题原文与原因）；「N 个字段用了未审阅的译文」由系统自动补上
     */
    public record Report(String company, String title, int round, List<Field> fields, List<Attachment> attachments,
                         List<String> unresolved, List<GuardNote> guardNotes) {

        public Report {
            if (round < 1) {
                throw new IllegalArgumentException("轮次从 1 开始：" + round);
            }
            fields = fields == null ? List.of() : List.copyOf(fields);
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
            unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
            guardNotes = guardNotes == null ? List.of() : List.copyOf(guardNotes);
        }

        /** 加一条守卫记录（已有同样的记录时不重复）。 */
        public Report withGuardNote(GuardNote note) {
            if (guardNotes.contains(note)) {
                return this;
            }
            List<GuardNote> notes = new ArrayList<>(guardNotes);
            notes.add(note);
            return new Report(company, title, round, fields, attachments, unresolved, notes);
        }
    }

    private final StorageLayout layout;
    private final Clock clock;

    public FillReportWriter(StorageLayout layout, Clock clock) {
        this.layout = layout;
        this.clock = clock;
    }

    /** 写草稿（整份重写，不提交）。 */
    public Path writeDraft(String projectId, String jobId, Report report) {
        Path file = layout.fillReport(projectId, jobId);
        AtomicFiles.write(file, render(report, true, Timestamps.now(clock)));
        return file;
    }

    /** 定稿。 */
    public Path writeFinal(String projectId, String jobId, Report report) {
        Path file = layout.fillReport(projectId, jobId);
        AtomicFiles.write(file, render(report, false, Timestamps.now(clock)));
        List<Path> written = new ArrayList<>(List.of(file));
        Path runLog = layout.runLog(projectId, jobId);
        if (Files.exists(runLog)) {
            written.add(runLog);
        }
        return file;
    }

    /** 报告的 Markdown 文本（草稿或定稿）；还没有报告时为空。中断后恢复时交给模型，也用于 HTTP 返回。 */
    public Optional<String> read(String projectId, String jobId) {
        Path file = layout.fillReport(projectId, jobId);
        return Files.exists(file) ? Optional.of(AtomicFiles.read(file)) : Optional.empty();
    }

    /**
     * 把已经写出的报告读回来（草稿或定稿）；还没有报告时为空。字段行读成 {@link Recorded}、C 级行读成
     * {@link UserFilled}；系统自动补的「N 个字段用了未审阅的译文」不读回（再写出时会重新算）。
     *
     * @throws IllegalStateException 文件不是这个类写出的格式
     */
    public Optional<Report> load(String projectId, String jobId) {
        return read(projectId, jobId).map(FillReportWriter::parse);
    }

    /** 报告是不是草稿；还没有报告时为空。 */
    public Optional<Boolean> isDraft(String projectId, String jobId) {
        return read(projectId, jobId).map(text -> {
            String header = text.lines().findFirst().orElse("");
            return header.endsWith("，" + DRAFT + "）");
        });
    }

    static String render(Report report, boolean draft, String time) {
        StringBuilder out = new StringBuilder();
        out.append("# 填写报告 · ").append(inline(report.company())).append(" · ").append(inline(report.title()))
                .append("（第 ").append(report.round()).append(" 轮，").append(time)
                .append(draft ? "，" + DRAFT : "").append("）\n\n");

        out.append("## 字段\n\n");
        out.append("| 页面 | 字段 | 值 | 来源层 | 语言 | 置信度 | 分级 |\n");
        out.append("| --- | --- | --- | --- | --- | --- | --- |\n");
        int unreviewed = 0;
        for (Field field : report.fields()) {
            switch (field) {
                case Filled filled -> {
                    if (filled.unreviewed()) {
                        unreviewed++;
                    }
                    String grade = filled.factClass().name();
                    row(out, filled.page(), filled.label(), value(filled), source(filled),
                            filled.language() == null ? NONE : filled.language().code(), filled.confidence() == null ? NONE : filled.confidence().code(),
                            filled.factClass() == FactClass.B ? "**" + grade + "**" : grade);
                }
                case UserFilled user -> row(out, user.page(), user.label(), USER_FILLED_VALUE, USER_FILLED_SOURCE,
                        NONE, NONE, FactClass.C.name());
                case Recorded recorded -> {
                    if (recorded.unreviewed()) {
                        unreviewed++;
                    }
                    row(out, recorded.page(), recorded.label(), recorded.value(), recorded.source(),
                            recorded.language(), recorded.confidence(), recorded.grade());
                }
            }
        }

        out.append("\n## 附件\n\n");
        out.append("| 文件 | 原件键 | 大小 | SHA-256 | 页面回读 |\n");
        out.append("| --- | --- | --- | --- | --- |\n");
        for (Attachment attachment : report.attachments()) {
            row(out, attachment.fileName(), attachment.assetKey(), attachment.size() + " B", attachment.sha256(),
                    attachment.readBack());
        }

        out.append("\n## 未解决\n\n");
        List<String> unresolved = new ArrayList<>(report.unresolved());
        if (unreviewed > 0) {
            unresolved.add(unreviewed + " 个字段用了未审阅的译文");
        }
        if (unresolved.isEmpty()) {
            out.append("- 无\n");
        }
        unresolved.forEach(item -> out.append("- ").append(inline(item)).append('\n'));

        out.append("\n## 守卫记录\n\n");
        if (report.guardNotes().isEmpty()) {
            out.append("- 无\n");
        }
        report.guardNotes().forEach(note -> out.append("- 「").append(inline(note.button())).append("」：")
                .append(note.outcome().text()).append('\n'));
        return out.toString();
    }

    /** {@link #render} 的逆过程。 */
    static Report parse(String markdown) {
        List<String> lines = markdown.lines().toList();
        Matcher header = HEADER.matcher(lines.isEmpty() ? "" : lines.getFirst());
        if (!header.matches()) {
            throw new IllegalStateException("填写报告的标题行格式不对，读不回来");
        }
        List<Field> fields = new ArrayList<>();
        List<Attachment> attachments = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        List<GuardNote> guardNotes = new ArrayList<>();
        String section = "";
        int tableRow = 0;
        for (String line : lines.subList(1, lines.size())) {
            if (line.startsWith("## ")) {
                section = line.substring(3).strip();
                tableRow = 0;
                continue;
            }
            if (line.startsWith("|")) {
                // 每张表的前两行是表头和分隔线
                if (tableRow++ < 2) {
                    continue;
                }
                List<String> cells = cells(line);
                switch (section) {
                    case "字段" -> fields.add(field(cells));
                    case "附件" -> attachments.add(attachment(cells));
                    default -> {
                    }
                }
                continue;
            }
            if (!line.startsWith("- ")) {
                continue;
            }
            String item = line.substring(2).strip();
            switch (section) {
                case "未解决" -> {
                    if (!UNREVIEWED_SUMMARY.matcher(item).matches()) {
                        unresolved.add(item);
                    }
                }
                case "守卫记录" -> {
                    Matcher guard = GUARD_LINE.matcher(line);
                    if (guard.matches()) {
                        guardNotes.add(new GuardNote(guard.group(1), outcome(guard.group(2))));
                    }
                }
                default -> {
                }
            }
        }
        if (unresolved.size() == 1 && NONE_ITEM.equals(unresolved.getFirst())) {
            unresolved.clear();
        }
        return new Report(orNull(header.group(1)), orNull(header.group(2)), Integer.parseInt(header.group(3)),
                fields, attachments, unresolved, guardNotes);
    }

    private static Field field(List<String> cells) {
        if (cells.size() != 7) {
            throw new IllegalStateException("填写报告的字段行应有 7 列，实际 " + cells.size() + " 列");
        }
        if (USER_FILLED_VALUE.equals(cells.get(2)) && USER_FILLED_SOURCE.equals(cells.get(3))) {
            return new UserFilled(orNull(cells.get(0)), cells.get(1));
        }
        return new Recorded(orNull(cells.get(0)), cells.get(1), orNull(cells.get(2)), orNull(cells.get(3)),
                orNull(cells.get(4)), orNull(cells.get(5)), orNull(cells.get(6)));
    }

    private static Attachment attachment(List<String> cells) {
        if (cells.size() != 5) {
            throw new IllegalStateException("填写报告的附件行应有 5 列，实际 " + cells.size() + " 列");
        }
        String size = cells.get(2).endsWith(" B") ? cells.get(2).substring(0, cells.get(2).length() - 2) : cells.get(2);
        long bytes;
        try {
            bytes = Long.parseLong(size.strip());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("填写报告的附件大小读不出来：" + cells.get(2));
        }
        return new Attachment(cells.get(0), orNull(cells.get(1)), bytes, orNull(cells.get(3)), orNull(cells.get(4)));
    }

    private static GuardOutcome outcome(String text) {
        for (GuardOutcome outcome : GuardOutcome.values()) {
            if (outcome.text().equals(text.strip())) {
                return outcome;
            }
        }
        throw new IllegalStateException("填写报告里有不认识的守卫结果：" + text);
    }

    /** 一行表格的单元格，按没有转义的竖线拆开，还原 {@link #cell} 的转义。 */
    private static List<String> cells(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = null;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length() && (line.charAt(i + 1) == '\\' || line.charAt(i + 1) == '|')) {
                if (current != null) {
                    current.append(line.charAt(i + 1));
                }
                i++;
            } else if (c == '|') {
                if (current != null) {
                    cells.add(current.toString().strip().replace("<br>", "\n"));
                }
                current = new StringBuilder();
            } else if (current != null) {
                current.append(c);
            }
        }
        return cells;
    }

    private static String orNull(String cell) {
        return cell == null || cell.isEmpty() || NONE.equals(cell) ? null : cell;
    }

    private static String value(Filled filled) {
        StringBuilder value = new StringBuilder(filled.value().isEmpty() ? "（空）" : filled.value());
        String original = filled.original();
        if (original != null) {
            value.append("（资料原值：").append(original).append("）");
        }
        if (filled.replaced() != null) {
            value.append("（页面原值：").append(filled.replaced()).append("，已改正）");
        }
        return value.toString();
    }

    private static String source(Filled filled) {
        return filled.fact() == null ? INFERRED : filled.fact().label();
    }

    private static void row(StringBuilder out, String... cells) {
        out.append('|');
        for (String cell : cells) {
            out.append(' ').append(cell == null || cell.isEmpty() ? NONE : cell(cell)).append(" |");
        }
        out.append('\n');
    }

    /** 表格单元格：竖线转义，换行写成 {@code <br>}，不会把表格拆坏。 */
    private static String cell(String text) {
        return text.replace("\\", "\\\\").replace("|", "\\|").replace("\r\n", "<br>").replace("\n", "<br>")
                .replace("\r", "<br>");
    }

    /** 标题和列表项：换行压成空格。 */
    private static String inline(String text) {
        return text == null || text.isBlank() ? NONE : text.strip().replaceAll("\\s*[\\r\\n]+\\s*", " ");
    }

    private static void requireText(String text, String what) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("缺少" + what);
        }
    }
}
