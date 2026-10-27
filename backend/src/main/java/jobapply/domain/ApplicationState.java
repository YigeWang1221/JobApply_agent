package jobapply.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * 一份申请的申请状态（C019）：现在在网站流程的哪一步、计划、字段清单、决定和站点规律。开始申请时是空的，
 * 存在运行记录（{@link AgentRun#state()}）里，每轮和对话一起存盘。模型用 {@code update_state} 改（字段按 id 合并），
 * 系统在填写工具的结果出来时自动记（填了、核对上了、要问用户、见过的选项）。每一步接在最后一条消息末尾给模型看，
 * 工作台侧栏显示进度，填写报告的字段行也从这里生成。
 * <p>
 * C 级字段只记占位符 {@code {KEY}}（dev-limit「C 级占位符」）：真实值从不进这里。
 *
 * @param stage  现在在网站流程的哪一步（模型写）；没有时为 null
 * @param steps  计划（原 {@code update_plan}），整表替换
 * @param fields 字段清单，按 {@link Field#id()} 合并
 * @param notes  这次申请里的决定和站点规律
 */
public record ApplicationState(String stage, List<Step> steps, List<Field> fields, List<String> notes) {

    public static final String TODO = "todo";
    public static final String FILLED = "filled";
    public static final String VERIFIED = "verified";
    public static final String NEEDS_USER = "needs_user";
    public static final String SKIPPED = "skipped";
    public static final Set<String> FIELD_STATUSES = Set.of(TODO, FILLED, VERIFIED, NEEDS_USER, SKIPPED);
    public static final Set<String> STEP_STATUSES = Set.of("todo", "doing", "done", "blocked");
    /** 见过的选项最多记几个。 */
    static final int MAX_OPTIONS = 60;

    public ApplicationState {
        stage = blankToNull(stage);
        steps = steps == null ? List.of() : List.copyOf(steps);
        fields = fields == null ? List.of() : List.copyOf(fields);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public static ApplicationState empty() {
        return new ApplicationState(null, null, null, null);
    }

    /** 计划里的一项。{@code status}：{@code todo} / {@code doing} / {@code done} / {@code blocked}。 */
    public record Step(String text, String status) {
    }

    /**
     * 字段清单里的一个字段。
     *
     * @param id      模型起的 id（例如 {@code edu1.school}）；系统新加的是 {@code f1}、{@code f2}…
     * @param section 区域（例如「教育经历 1」）；没有时为 null
     * @param label   页面上的标签
     * @param widget  控件：text、textarea、select、cascader、date、radio、checkbox、file 等；不知道时为 null
     * @param value   要填 / 填进去的值；C 级只能是占位符
     * @param source  值的来源：资料键、答案编号 {@code ans-NNN}、{@code resume}、{@code cover_letter}、{@code none}
     * @param status  {@link #TODO} / {@link #FILLED} / {@link #VERIFIED} / {@link #NEEDS_USER} / {@link #SKIPPED}；
     *                合并用的补丁里为 null 表示不改
     * @param note    最近一次的结果或说明（一行）
     * @param options 见过的下拉选项原文
     * @param report  填写报告里这个字段的那一行（系统填写时记下）；还没填时为 null。不给模型看
     */
    public record Field(String id, String section, String label, String widget, String value, String source,
                        String status, String note, List<String> options, ReportRow report) {

        public Field {
            options = options == null ? List.of() : List.copyOf(options);
        }

        public boolean done() {
            return FILLED.equals(status) || VERIFIED.equals(status) || SKIPPED.equals(status);
        }

        public Field withStatus(String status, String note) {
            return new Field(id, section, label, widget, value, source, status, note, options, report);
        }

        public Field withNote(String note) {
            return new Field(id, section, label, widget, value, source, status, note, options, report);
        }

        public Field withOptions(List<String> seen) {
            List<String> kept = seen.size() > MAX_OPTIONS ? seen.subList(0, MAX_OPTIONS) : seen;
            return new Field(id, section, label, widget, value, source, status, note, kept, report);
        }

        /** 系统填进去了：值、来源、控件、状态、说明和报告那一行。 */
        public Field filled(String widget, String value, String source, String status, String note, ReportRow report) {
            return new Field(id, section, label, widget == null ? this.widget : widget, value,
                    source == null ? this.source : source, status, note, options, report);
        }

        /** 用户回答了（答案编号、回答原文）：回到待填，由模型照答案去填。 */
        public Field answered(String answerId, String answer) {
            return new Field(id, section, label, widget, answer, answerId, TODO, "用户回答了（" + answerId + "）", options,
                    report);
        }
    }

    /**
     * 填写报告里一个字段的那一行（{@code FillReportWriter} 的单元格原样）。{@code value} 为 null 且分级是 C 时是
     * 「用户亲填」。
     */
    public record ReportRow(String page, String value, String source, String language, String confidence,
                            String grade) {
    }

    // ---- 模型的更新 ----

    public ApplicationState withStage(String stage) {
        return new ApplicationState(stage, steps, fields, notes);
    }

    public ApplicationState withSteps(List<Step> steps) {
        return new ApplicationState(stage, steps, fields, notes);
    }

    /** 加几行决定 / 站点规律（已有的不重复），去掉 {@code drop} 里原文相同的几行。 */
    public ApplicationState withNotes(List<String> add, List<String> drop) {
        Set<String> kept = new LinkedHashSet<>(notes);
        drop.forEach(kept::remove);
        add.stream().map(String::strip).filter(note -> !note.isEmpty()).forEach(kept::add);
        return new ApplicationState(stage, steps, fields, List.copyOf(kept));
    }

    /**
     * 按 id 合并一个字段：已有的只覆盖 {@code patch} 里不为 null 的项（选项给了就整份替换），没有的加在最后。
     *
     * @throws IllegalArgumentException 新字段没有标签
     */
    public ApplicationState merge(Field patch) {
        List<Field> merged = new ArrayList<>(fields);
        for (int i = 0; i < merged.size(); i++) {
            Field old = merged.get(i);
            if (old.id().equals(patch.id())) {
                merged.set(i, new Field(old.id(), or(patch.section(), old.section()), or(patch.label(), old.label()),
                        or(patch.widget(), old.widget()), or(patch.value(), old.value()),
                        or(patch.source(), old.source()), patch.status() == null ? old.status() : patch.status(),
                        or(patch.note(), old.note()), patch.options().isEmpty() ? old.options() : patch.options(),
                        old.report()));
                return new ApplicationState(stage, steps, merged, notes);
            }
        }
        if (patch.label() == null || patch.label().isBlank()) {
            throw new IllegalArgumentException("新字段 " + patch.id() + " 要写 label");
        }
        merged.add(patch.status() == null ? patch.withStatus(TODO, patch.note()) : patch);
        return new ApplicationState(stage, steps, merged, notes);
    }

    public ApplicationState remove(String id) {
        return new ApplicationState(stage, steps, fields.stream().filter(field -> !field.id().equals(id)).toList(),
                notes);
    }

    // ---- 系统的更新 ----

    /**
     * 填写工具对应的字段：{@code id} 写了就按 id 找（没有就用这个 id 新加）；没写就按标签找，先找还没填好的；
     * 都找不到就新加一个（id {@code f1}、{@code f2}…）。返回找到或新加的字段的 id 和加过字段的状态。
     */
    public Located locate(String id, String label) {
        String wanted = blankToNull(id);
        if (wanted != null) {
            Optional<Field> found = find(wanted);
            return found.isPresent() ? new Located(this, wanted)
                    : added(new Field(wanted, null, label == null ? wanted : label, null, null, null, TODO, null, null,
                    null));
        }
        String key = normalize(label);
        if (!key.isEmpty()) {
            Optional<Field> open = fields.stream().filter(field -> key.equals(normalize(field.label())) && !field.done())
                    .findFirst();
            Optional<Field> any = open.isPresent() ? open
                    : fields.stream().filter(field -> key.equals(normalize(field.label()))).findFirst();
            if (any.isPresent()) {
                return new Located(this, any.get().id());
            }
        }
        String fresh;
        int n = fields.size() + 1;
        do {
            fresh = "f" + n++;
        } while (find(fresh).isPresent());
        return added(new Field(fresh, null, label == null || label.isBlank() ? "（未命名字段）" : label.strip(), null, null,
                null, TODO, null, null, null));
    }

    /** {@link #locate} 的结果：加过字段之后的状态、字段 id。 */
    public record Located(ApplicationState state, String id) {
    }

    private Located added(Field field) {
        List<Field> more = new ArrayList<>(fields);
        more.add(field);
        return new Located(new ApplicationState(stage, steps, more, notes), field.id());
    }

    /** 改一个字段（没有这个 id 时不变）。 */
    public ApplicationState update(String id, UnaryOperator<Field> change) {
        return new ApplicationState(stage, steps, fields.stream()
                .map(field -> field.id().equals(id) ? change.apply(field) : field).toList(), notes);
    }

    /**
     * 用户回答了一个问题：标签和问题原文一样、正在等用户的字段回到待填，来源写答案编号。没有这样的字段时不变
     * （回答照旧作为下一条消息交给模型）。
     */
    public ApplicationState answered(String question, String answerId, String answer) {
        String key = normalize(question);
        return fields.stream().filter(field -> NEEDS_USER.equals(field.status()) && key.equals(normalize(field.label())))
                .findFirst().map(field -> update(field.id(), f -> f.answered(answerId, answer))).orElse(this);
    }

    public Optional<Field> find(String id) {
        return fields.stream().filter(field -> field.id().equals(id)).findFirst();
    }

    public long count(String status) {
        return fields.stream().filter(field -> status.equals(field.status())).count();
    }

    public boolean isEmpty() {
        return stage == null && steps.isEmpty() && fields.isEmpty() && notes.isEmpty();
    }

    /** 比较标签用：去掉首尾空白、必填星号和冒号，忽略大小写。 */
    static String normalize(String label) {
        if (label == null) {
            return "";
        }
        return label.strip().replaceAll("^[*＊\\s]+|[*＊:：\\s]+$", "").toLowerCase(Locale.ROOT);
    }

    private static String or(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
