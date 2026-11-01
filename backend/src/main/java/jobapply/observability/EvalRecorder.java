package jobapply.observability;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import jobapply.harness.policies.SnapshotRedactor;
import jobapply.tools.PageSnapshot;
import tools.jackson.databind.json.JsonMapper;

/**
 * 评测模式下一次申请的记录（C025 第 5 节），存在 {@code storage/eval/<组名>/<run-id>/}（run-id 就是 Job 编号）：
 * <ul>
 *   <li>{@code steps/NNN.json}：每次工具调用一份，工具调用 id、工具、结果状态、结果文字，和调用之后的网址、标题、
 *       {@code modalState}、快照（没有新页面时为空）；</li>
 *   <li>{@code submits.jsonl}：拦下的提交请求，方法、网址路径、时间、触发前最后一次点击的 ref、请求体的字段名（不留值）；</li>
 *   <li>{@code meta.json}：模型、开关和参数、提示词 / skill 的版本（哈希）、起点是否干净、每一段运行的结束状态和停下原因、
 *       步数、拦下次数、C 级值出现次数。</li>
 * </ul>
 * 落盘前全部遮掉：C 级输入框的值（{@link SnapshotRedactor#redact}），personal.env 的真实值（只数出现次数，换成
 * {@code [C]}），资料和本次答案里的 A / B 级值、这次运行键入过的值（换成 {@code [字段名]} / {@code [已填值]}）。
 * 只在评测打开时创建；写不进只让这一步的记录缺一份，不影响申请本身（调用方吞掉异常并记日志）。
 */
public final class EvalRecorder {

    /** 停下原因（C025 第 5 节，统一成几类）。 */
    public static final String READY = "停在提交前";
    public static final String WAITING = "等用户";
    public static final String BLOCKED_BY_SITE = "被站点拦下";
    public static final String ERROR = "出错";
    public static final String LIMIT = "到工具调用上限";
    public static final String STOPPED = "用户叫停";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 太短的值（「男」「是」）遮了只会把页面结构一起遮掉。 */
    private static final int MIN_MASK = 2;

    private final EvalOptions options;
    private final Path dir;
    private final Clock clock;
    private final Map<String, String> secrets;
    /** 值 → 标记，长的先换。 */
    private final Map<String, String> masks = new LinkedHashMap<>();
    private final String startedAt;
    private int step;
    private long cLevelHits;
    private long intercepted;
    private final List<String> interceptErrors = new ArrayList<>();
    private String lastClick;

    /**
     * @param masks   资料和本次答案的 A / B 级值 → 字段名
     * @param secrets personal.env 的 {@code KEY → 值}（只在内存里比对，不写出）
     */
    public EvalRecorder(EvalOptions options, String runId, Map<String, String> masks, Map<String, String> secrets,
                        Clock clock) {
        this.options = options;
        this.dir = options.dir().resolve(runId);
        this.clock = clock;
        this.secrets = secrets == null ? Map.of() : secrets;
        masks.forEach(this::mask);
        this.startedAt = clock.instant().toString();
        try {
            Files.createDirectories(dir.resolve("steps"));
            try (Stream<Path> files = Files.list(dir.resolve("steps"))) {
                step = (int) files.filter(file -> file.getFileName().toString().endsWith(".json")).count();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("建不了评测目录 " + dir + "：" + e.getMessage(), e);
        }
    }

    public Path dir() {
        return dir;
    }

    /** 这次运行键入过的值（填写工具的参数）：之后页面上回显时也遮掉。 */
    public void written(Collection<String> values) {
        values.forEach(value -> mask(value, "已填值"));
    }

    /** 一次工具调用之后存一份；{@code page} 是调用之后的页面，没有时为 null。点击记下 ref，拦下提交时用。 */
    public void step(String callId, String tool, String target, String status, String result, PageSnapshot page) {
        step++;
        if ("browser_click".equals(tool) && target != null) {
            lastClick = target;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("step", step);
        out.put("call_id", callId);
        out.put("tool", tool);
        out.put("target", target);
        out.put("status", status);
        out.put("at", clock.instant().toString());
        out.put("result", clean(result));
        if (page != null) {
            PageSnapshot redacted = SnapshotRedactor.redact(page);
            out.put("url", clean(redacted.url()));
            out.put("title", clean(redacted.title()));
            out.put("modal_state", clean(redacted.modalState()));
            out.put("snapshot", clean(redacted.snapshot()));
        }
        write(dir.resolve("steps").resolve("%03d.json".formatted(step)), JSON.writeValueAsString(out));
    }

    /** 这一步拦下的提交请求（{@code BrowserSession#interceptSubmits} 的结果）。 */
    public void submits(List<Map<String, Object>> requests) {
        for (Map<String, Object> request : requests) {
            intercepted++;
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("at", request.getOrDefault("time", clock.instant().toString()));
            line.put("method", request.get("method"));
            line.put("path", clean(String.valueOf(request.get("path"))));
            line.put("last_click_ref", lastClick);
            line.put("step", step);
            line.put("fields", request.get("fields") instanceof List<?> names
                    ? names.stream().map(name -> clean(String.valueOf(name))).toList() : List.of());
            try {
                Files.writeString(dir.resolve("submits.jsonl"), JSON.writeValueAsString(line) + "\n", UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** 拦截没装上 / 读不回（只记原因，不影响申请）。 */
    public void interceptFailed(String reason) {
        if (interceptErrors.size() < 20) {
            interceptErrors.add(clean(reason));
        }
    }

    /**
     * 一段运行结束：更新 {@code meta.json}（第一次写入固定信息，之后每段追加一条）。
     *
     * @param fixed 模型、版本哈希等；只在还没有 meta.json 时写进去
     */
    public void finish(Map<String, Object> fixed, String status, String reason, Map<String, Object> usage) {
        Path file = dir.resolve("meta.json");
        Map<String, Object> meta = new LinkedHashMap<>();
        try {
            if (Files.exists(file)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> read = JSON.readValue(Files.readString(file, UTF_8), Map.class);
                meta.putAll(read);
            }
        } catch (IOException | RuntimeException e) {
            meta.clear();
        }
        if (meta.isEmpty()) {
            meta.put("run_id", dir.getFileName().toString());
            meta.put("group", options.group());
            meta.putAll(fixed);
            meta.put("params", options.params());
            meta.put("full_snapshots", options.fullSnapshots());
            meta.put("skills", options.skills());
            meta.put("submit_pattern", options.submitPattern());
            meta.put("clean_start", options.cleanStart());
            meta.put("segments", new ArrayList<>());
        }
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("started_at", startedAt);
        segment.put("ended_at", clock.instant().toString());
        segment.put("status", status);
        segment.put("stop_reason", reason);
        segment.put("usage", usage);
        segment.put("intercept_errors", interceptErrors);
        @SuppressWarnings("unchecked")
        List<Object> segments = meta.get("segments") instanceof List<?> list ? (List<Object>) list : new ArrayList<>();
        segments = new ArrayList<>(segments);
        segments.add(segment);
        meta.put("segments", segments);
        meta.put("end_status", status);
        meta.put("stop_reason", reason);
        meta.put("usage", usage);
        meta.put("steps", step);
        meta.put("submits_intercepted", number(meta.get("submits_intercepted")) + intercepted);
        meta.put("c_level_hits", number(meta.get("c_level_hits")) + cLevelHits);
        write(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(meta));
        intercepted = 0;
        cLevelHits = 0;
    }

    /** C 级值出现几次就记几次（不记值），换成 [C]；再遮 A / B 级的值。 */
    String clean(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (String value : secrets.values()) {
            String v = value == null ? "" : value.strip();
            if (v.length() < MIN_MASK) {
                continue;
            }
            int at = out.indexOf(v);
            while (at >= 0) {
                cLevelHits++;
                at = out.indexOf(v, at + v.length());
            }
            out = out.replace(v, "[C]");
        }
        for (Map.Entry<String, String> entry : masks.entrySet()) {
            out = out.replace(entry.getKey(), "[" + entry.getValue() + "]");
        }
        return out;
    }

    private void mask(String value, String label) {
        String v = value == null ? "" : value.strip();
        if (v.length() < MIN_MASK || masks.containsKey(v)) {
            return;
        }
        masks.put(v, label);
        List<Map.Entry<String, String>> sorted = new ArrayList<>(masks.entrySet());
        sorted.sort(Comparator.comparingInt((Map.Entry<String, String> entry) -> entry.getKey().length()).reversed());
        masks.clear();
        sorted.forEach(entry -> masks.put(entry.getKey(), entry.getValue()));
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0;
    }

    private static void write(Path target, String content) {
        try {
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temp, content, UTF_8);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("写不了评测记录 " + target + "：" + e.getMessage(), e);
        }
    }
}
