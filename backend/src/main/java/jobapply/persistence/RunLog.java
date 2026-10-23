package jobapply.persistence;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jobapply.domain.Timestamps;

/**
 * 追加 {@code jobs/<job-id>/run-log.jsonl}：填写过程的每一步一行（C004-B「关键规则 · 过程日志」）。
 * 每行的键固定：{@code ts, session, step, kind, tool, ref, element, decision, purpose, evidence, page_title,
 * url_path, outcome, note}。
 * <p>
 * <b>不记填进去的值、快照全文和模型对话原文。</b>条目只能用 {@link Entry} 的工厂方法构造，没有放「值」的地方：
 * 元素只有角色和名字（{@link Element}），页面只有标题和去掉查询参数的地址（{@link Page}），模型调用只有 token 数，
 * 用户信号只有信号名（{@code request_changes} 时加上用户写的说明）。
 * <p>
 * 只追加，不提交：定稿时由 {@link FillReportWriter#writeFinal} 和报告一起提交。
 */
public final class RunLog {

    /** 一行的种类。 */
    public enum Kind {
        MODEL_CALL("model_call"),
        TOOL_CALL("tool_call"),
        GUARD("guard"),
        WAIT("wait"),
        USER_SIGNAL("user_signal"),
        BUDGET("budget"),
        LOOP_DETECTED("loop_detected"),
        SKILL_LOADED("skill_loaded");

        private final String code;

        Kind(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** 页面上的元素：只有角色和名字（无障碍名，也就是字段的标签），不含它的值。 */
    public record Element(String role, String name) {

        Map<String, Object> toRecord() {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("role", role);
            record.put("name", name == null ? null : name.strip());
            return record;
        }
    }

    /** 当时所在的页面：标题，以及地址。地址在构造时就去掉用户名密码、查询参数和片段，传完整地址也可以。 */
    public record Page(String title, String urlPath) {

        public Page {
            urlPath = RunLog.urlPath(urlPath);
        }
    }

    /** 日志里的一行。只能用工厂方法构造。 */
    public static final class Entry {

        private final String session;
        private final int step;
        private final Kind kind;
        private final String tool;
        private final String ref;
        private final Element element;
        private final String decision;
        private final String purpose;
        private final String evidence;
        private final Page page;
        private final String outcome;
        private final String note;

        private Entry(String session, int step, Kind kind, String tool, String ref, Element element,
                      String decision, String purpose, String evidence, Page page, String outcome, String note) {
            if (session == null || session.isBlank()) {
                throw new IllegalArgumentException("过程日志缺少 session");
            }
            if (step < 0) {
                throw new IllegalArgumentException("步数不能为负：" + step);
            }
            this.session = session;
            this.step = step;
            this.kind = kind;
            this.tool = tool;
            this.ref = ref;
            this.element = element;
            this.decision = decision;
            this.purpose = purpose;
            this.evidence = evidence;
            this.page = page;
            this.outcome = outcome;
            this.note = note;
        }

        /** 一次模型调用：只记 token 用量。 */
        public static Entry modelCall(String session, int step, long inputTokens, long outputTokens) {
            return new Entry(session, step, Kind.MODEL_CALL, null, null, null, null, null, null, null, null,
                    "input_tokens=" + inputTokens + " output_tokens=" + outputTokens);
        }

        /**
         * 一次浏览器工具调用（写操作或快照）。
         *
         * @param purpose  模型给的用途（{@code click} 的 {@code purpose}），没有时为 null
         * @param evidence 模型给的依据（{@code click} 的 {@code evidence}），没有时为 null
         * @param outcome  结果，例如 {@code ok}、{@code error}、{@code page_changed}
         */
        public static Entry toolCall(String session, int step, String tool, String ref, Element element,
                                     String purpose, String evidence, Page page, String outcome) {
            return new Entry(session, step, Kind.TOOL_CALL, tool, ref, element, null, purpose, evidence, page,
                    outcome, null);
        }

        /**
         * 守卫的判断。
         *
         * @param decision 例如 {@code reject}、{@code handoff}、{@code needs_confirm}
         * @param reason   守卫给的原因（只含字段或按钮的名字）
         */
        public static Entry guard(String session, int step, String tool, String ref, Element element,
                                  String decision, Page page, String reason) {
            return new Entry(session, step, Kind.GUARD, tool, ref, element, decision, null, null, page, null,
                    reason);
        }

        /** 进入一个等待点（例如 {@code wait_input}），{@code reason} 是原因（不含填写的值）。 */
        public static Entry waiting(String session, int step, String waitPoint, Page page, String reason) {
            return new Entry(session, step, Kind.WAIT, null, null, null, waitPoint, null, null, page, null, reason);
        }

        /** 用户发来的信号：只记信号名（{@code answers} 的内容不记）。说明原文用 {@link #requestChanges}。 */
        public static Entry userSignal(String session, int step, String signal) {
            return new Entry(session, step, Kind.USER_SIGNAL, null, null, null, signal, null, null, null, null, null);
        }

        /** 用户在关卡 B 要求修改：记下用户写的说明（C004-B 待确认第 5 项）。 */
        public static Entry requestChanges(String session, int step, String note) {
            return new Entry(session, step, Kind.USER_SIGNAL, null, null, null, "request_changes", null, null, null,
                    null, note);
        }

        /** 预算：提醒或到达软上限。{@code decision} 例如 {@code warn_80}、{@code soft_limit}。 */
        public static Entry budget(String session, int step, String decision, long toolCalls, long tokens) {
            return new Entry(session, step, Kind.BUDGET, null, null, null, decision, null, null, null, null,
                    "tool_calls=" + toolCalls + " tokens=" + tokens);
        }

        /** 打转检测命中。{@code rule} 例如 {@code same_element_failed}、{@code same_result}、{@code no_change}。 */
        public static Entry loopDetected(String session, int step, String rule, String tool, String ref,
                                         Element element, Page page) {
            return new Entry(session, step, Kind.LOOP_DETECTED, tool, ref, element, rule, null, null, page, null,
                    null);
        }

        /** 读了一个 skill 的正文。 */
        public static Entry skillLoaded(String session, int step, String skill) {
            return new Entry(session, step, Kind.SKILL_LOADED, null, null, null, null, null, null, null, null, skill);
        }

        public Kind kind() {
            return kind;
        }

        Map<String, Object> toRecord(String ts) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("ts", ts);
            record.put("session", session);
            record.put("step", step);
            record.put("kind", kind.code());
            record.put("tool", tool);
            record.put("ref", ref);
            record.put("element", element == null ? null : element.toRecord());
            record.put("decision", decision);
            record.put("purpose", purpose);
            record.put("evidence", evidence);
            record.put("page_title", page == null ? null : page.title());
            record.put("url_path", page == null ? null : page.urlPath());
            record.put("outcome", outcome);
            record.put("note", note);
            return record;
        }
    }

    private final StorageLayout layout;
    private final Clock clock;
    private final EventLog lines = new EventLog();

    public RunLog(StorageLayout layout, Clock clock) {
        this.layout = layout;
        this.clock = clock;
    }

    public synchronized void append(String projectId, String jobId, Entry entry) {
        lines.append(layout.runLog(projectId, jobId), entry.toRecord(Timestamps.now(clock)));
    }

    public List<Map<String, Object>> read(String projectId, String jobId) {
        return lines.read(layout.runLog(projectId, jobId));
    }

    public Path path(String projectId, String jobId) {
        return layout.runLog(projectId, jobId);
    }

    /** {@code scheme://host[:port]/path}：去掉用户名密码、查询参数和片段。解析不了时只去掉 {@code ?} 和 {@code #} 之后的部分。 */
    static String urlPath(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String cut = url.strip();
        int query = indexOfAny(cut, '?', '#');
        if (query >= 0) {
            cut = cut.substring(0, query);
        }
        try {
            URI uri = URI.create(cut);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return cut;
            }
            String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            return uri.getScheme() + "://" + uri.getHost() + port + path;
        } catch (IllegalArgumentException e) {
            return cut;
        }
    }

    private static int indexOfAny(String text, char a, char b) {
        int first = text.indexOf(a);
        int second = text.indexOf(b);
        if (first < 0) {
            return second;
        }
        return second < 0 ? first : Math.min(first, second);
    }
}
