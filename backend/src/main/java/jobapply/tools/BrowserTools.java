package jobapply.tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jobapply.harness.policies.BrowserToolPolicy;
import jobapply.harness.policies.FieldIntent;
import jobapply.harness.policies.SubmitGuard;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 交给模型的浏览器工具：定义和参数翻译（agent-runtime.md 7.2）。
 * <p>
 * 定义是 Spring AI 的 {@link ToolCallback}，{@code call()} 一律抛异常：调用方直接调用 {@code ChatModel}，
 * 工具调用原样留在响应里，由 Harness 循环自己执行。工具名和 MCP 的工具名相同，和 {@link BrowserToolPolicy}
 * 的模型白名单一一对应（不开放 {@code handle_dialog}；{@code browser_tabs} 另由 {@link #tabs()} 定义，系统代发）。
 * <p>
 * 比 MCP 多出来的参数：{@code browser_fill_form} 的每个字段、{@code browser_type}、{@code browser_select_option}
 * 可以带 {@code fact}、{@code confidence}、{@code language}（都可选，写了就进报告）（{@link FieldIntent}）；{@code browser_click} 可以带
 * {@code purpose}、{@code final_step_suspected}、{@code evidence}（{@code final_step_suspected} 决定软边界按钮放不放行）（{@link SubmitGuard.ClickIntent}）；
 * {@code browser_file_upload} 只收文件编号（{@code files}，从 1 开始；可带上传位置的 {@code target}），系统换成 {@code storage/uploads/<job-id>/}
 * 下的路径，模型看不到路径。{@link #translate} 先取出这些参数并去掉，再交给白名单和守卫。
 */
public final class BrowserTools {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    /**
     * 申请 Agent 的标签页工具（名字同 MCP 的 {@code browser_tabs}，但不在 {@link #definitions()} 里、不经 {@link #translate}）：
     * 由 Harness 循环交给 {@link BrowserSession#tabs} / {@link BrowserSession#select}，只能看、只能切到这个 Job 自己的标签页（新开的、点击弹出的、用户转交的）。
     */
    public static final String TABS = BrowserToolPolicy.TABS;

    /** 文件上传的参数：文件编号。 */
    public static final String FILES = "files";

    /** 只读的工具。 */
    public static final Set<String> READ_ONLY = Set.of(BrowserToolPolicy.SNAPSHOT, BrowserToolPolicy.FIND);

    private static final String FIELD_EXTRA = """
            "fact":{"type":"string","description":"Optional: the profile key or answer id (ans-NNN) the value comes from; resume if taken from the resume text (read_resume); none if you inferred it"},\
            "confidence":{"type":"string","enum":["high","medium","low"],"description":"Optional"},\
            "language":{"type":"string","enum":["zh","en"],\
            "description":"Optional: use the value in this language instead of the page language"}""";

    private static final String TARGET = """
            "target":{"type":"string","description":"ref from the latest snapshot, e.g. e12"},\
            "element":{"type":"string","description":"Human-readable element description"}""";

    private static final List<ToolDefinition> DEFINITIONS = List.of(
            definition(BrowserToolPolicy.SNAPSHOT,
                    "Read the current page as an accessibility snapshot. Use it when the page may have changed without a "
                            + "write (e.g. after waiting) or the last snapshot was omitted. Refs in the latest snapshot are "
                            + "the only valid targets.",
                    """
                    {"type":"object","properties":{}}"""),
            definition(BrowserToolPolicy.FIND,
                    "Find elements on the current page by visible text or regex, e.g. the options of a long dropdown or an "
                            + "error message. Read-only; cheaper than reading the whole page again.",
                    """
                    {"type":"object","properties":{"text":{"type":"string"},"regex":{"type":"string"}}}"""),
            definition(BrowserToolPolicy.NAVIGATE,
                    "Navigate the job tab to a URL on the same site as the job posting (e.g. back to the posting or to the "
                            + "application form link shown on the page). Other sites are rejected.",
                    """
                    {"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}"""),
            definition(BrowserToolPolicy.CLICK,
                    "Click a button, link, tab, dropdown or option. Buttons whose name looks like submitting "
                            + "(Submit, Apply, Confirm, 提交, 投递, 申请, 确认...) are rejected: the user clicks those. "
                            + "For Next / Continue / Save buttons, look at the page first (step indicator, progress, text "
                            + "near the button) and pass final_step_suspected with evidence: false lets the click through; "
                            + "true means the system asks the user first (via ask_user) for this one button on this page. "
                            + "Without final_step_suspected such buttons are not clicked. Other elements need no notes.",
                    """
                    {"type":"object","properties":{%s,\
                    "purpose":{"type":"string","enum":["next_step","save_draft","open_control","unsure"]},\
                    "final_step_suspected":{"type":"boolean"},\
                    "evidence":{"type":"string","description":"Why, e.g. progress shows step 2 of 4"}},\
                    "required":["target"]}""".formatted(TARGET)),
            definition(BrowserToolPolicy.TYPE,
                    "Type text into one editable field (replaces its content). Use it for one field, rich text areas and "
                            + "autocomplete inputs (then wait for suggestions and click one). Never presses Enter.",
                    """
                    {"type":"object","properties":{%s,"text":{"type":"string"},%s},\
                    "required":["target","text"]}""".formatted(TARGET, FIELD_EXTRA)),
            definition(BrowserToolPolicy.FILL_FORM,
                    "Fill several fields at once: text boxes, checkboxes, radios, native comboboxes. Prefer this to fill "
                            + "everything you are sure about on the current page in one call. Custom dropdowns that open a "
                            + "list usually need browser_click instead.",
                    """
                    {"type":"object","properties":{"fields":{"type":"array","minItems":1,"items":{"type":"object",\
                    "properties":{"target":{"type":"string"},"name":{"type":"string"},\
                    "type":{"type":"string","enum":["textbox","checkbox","radio","combobox","slider"]},\
                    "value":{"type":"string"},%s},\
                    "required":["target","name","type","value"]}}},\
                    "required":["fields"]}""".formatted(FIELD_EXTRA)),
            definition(BrowserToolPolicy.SELECT_OPTION,
                    "Select option(s) in a native dropdown (select / listbox). Values must match the option text on the "
                            + "page.",
                    """
                    {"type":"object","properties":{%s,"values":{"type":"array","items":{"type":"string"}},%s},\
                    "required":["target","values"]}""".formatted(TARGET, FIELD_EXTRA)),
            definition(BrowserToolPolicy.FILE_UPLOAD,
                    "Upload files. Pass file numbers from the available files. With target (the ref of the upload "
                            + "button or upload area, e.g. 选择文件 / 上传简历): the system sets the files directly on that "
                            + "control's file input, no click and no file chooser needed; this works even when the button "
                            + "is covered or clicking it times out. Prefer this. Without target: answers a file chooser "
                            + "that a click already opened (the snapshot shows [File chooser]); omit files to cancel it.",
                    """
                    {"type":"object","properties":{%s,"files":{"type":"array","items":{"type":"integer","minimum":1}}}}""".formatted(TARGET)),
            definition(BrowserToolPolicy.PRESS_KEY,
                    "Press a navigation key (Tab, arrows, Escape, Backspace, Delete, Home, End, PageUp, PageDown).",
                    """
                    {"type":"object","properties":{"key":{"type":"string"}},"required":["key"]}"""),
            definition(BrowserToolPolicy.WAIT_FOR,
                    "Wait for text to appear or disappear (e.g. upload finished, suggestions loaded), or for up to 10 "
                            + "seconds. Returns without a new snapshot; read the page afterwards if needed.",
                    """
                    {"type":"object","properties":{"time":{"type":"number","maximum":10},\
                    "text":{"type":"string"},"textGone":{"type":"string"}}}"""));

    /**
     * 一次模型调用翻译后的结果。
     *
     * @param tool        MCP 工具名
     * @param args        发给 MCP 的参数（多出来的参数已去掉，文件编号已换成路径）
     * @param fields      写字段时每个字段的 {@link FieldIntent}，按顺序；不是写字段时为空
     * @param click       点击时的 {@link SubmitGuard.ClickIntent}；不是点击时为 null
     * @param fileNumbers 上传时模型给的文件编号；不是上传时为空
     */
    public record Call(String tool, Map<String, Object> args, List<FieldIntent> fields,
                       SubmitGuard.ClickIntent click, List<Integer> fileNumbers) {

        public Call {
            args = args == null ? Map.of() : args;
            fields = fields == null ? List.of() : List.copyOf(fields);
            fileNumbers = fileNumbers == null ? List.of() : List.copyOf(fileNumbers);
        }

        /** 会改变页面的调用（快照和查找以外的）。 */
        public boolean write() {
            return !READ_ONLY.contains(tool) && !BrowserToolPolicy.WAIT_FOR.equals(tool);
        }
    }

    private BrowserTools() {
    }

    private static final ToolDefinition TABS_DEFINITION = definition(TABS,
            "List or switch browser tabs. list shows every open tab (index, title, URL) and marks the one you are "
                    + "using and the ones that are yours. Only operate tabs you opened yourself, tabs your clicks "
                    + "opened, and tabs the user explicitly handed to you; never touch tabs the user opened earlier. "
                    + "select switches to an index from list and returns its snapshot. Use it when a click opened a "
                    + "new tab, when the user handed you a tab, or when the system says your tab was not found.",
            """
            {"type":"object","properties":{"action":{"type":"string","enum":["list","select"]},\
            "index":{"type":"integer","minimum":0,"description":"For select: an index from list"}},\
            "required":["action"]}""");

    /** 填写角色的全部浏览器工具定义。 */
    public static List<ToolCallback> definitions() {
        return DEFINITIONS.stream().map(BrowserTools::callback).toList();
    }

    /** 申请 Agent 的标签页工具定义（{@link #TABS}）。 */
    public static ToolCallback tabs() {
        return callback(TABS_DEFINITION);
    }

    public static boolean isBrowserTool(String name) {
        return DEFINITIONS.stream().anyMatch(d -> d.name().equals(name));
    }

    /**
     * 把模型的一次工具调用翻译成 MCP 调用。参数不是 JSON 对象、字段的 {@code language} 不对、
     * 文件编号不存在时抛出 {@link IllegalArgumentException}（说明交给模型，调用没有发出）。
     * 白名单和守卫不在这里检查。
     *
     * @param argumentsJson 模型给的参数（JSON 文本）
     * @param files         这次可以上传的文件（{@code storage/uploads/<job-id>/} 下的副本），编号 1 对应第一个
     */
    public static Call translate(String tool, String argumentsJson, List<Path> files) {
        if (!isBrowserTool(tool)) {
            throw new IllegalArgumentException("没有这个浏览器工具：" + tool);
        }
        Map<String, Object> args = parse(tool, argumentsJson);
        return switch (tool) {
            case BrowserToolPolicy.CLICK ->
                    new Call(tool, SubmitGuard.ClickIntent.strip(args), List.of(), SubmitGuard.ClickIntent.from(args),
                            List.of());
            case BrowserToolPolicy.TYPE, BrowserToolPolicy.SELECT_OPTION -> {
                FieldIntent intent = checked(tool, FieldIntent.from(args));
                yield new Call(tool, FieldIntent.strip(args), List.of(intent), null, List.of());
            }
            case BrowserToolPolicy.FILL_FORM -> fillForm(args);
            case BrowserToolPolicy.FILE_UPLOAD -> upload(args, files == null ? List.of() : files);
            default -> new Call(tool, args, List.of(), null, List.of());
        };
    }

    private static Call fillForm(Map<String, Object> args) {
        if (!(args.get("fields") instanceof List<?> list)) {
            // 交给白名单报错（缺少 fields）
            return new Call(BrowserToolPolicy.FILL_FORM, args, List.of(), null, List.of());
        }
        List<FieldIntent> intents = new ArrayList<>();
        List<Object> stripped = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> field = (Map<String, Object>) map;
                intents.add(checked(BrowserToolPolicy.FILL_FORM, FieldIntent.from(field)));
                stripped.add(FieldIntent.strip(field));
            } else {
                stripped.add(item);
            }
        }
        Map<String, Object> rest = new LinkedHashMap<>(args);
        rest.put("fields", stripped);
        return new Call(BrowserToolPolicy.FILL_FORM, rest, intents, null, List.of());
    }

    private static Call upload(Map<String, Object> args, List<Path> files) {
        Map<String, Object> rest = new LinkedHashMap<>(args);
        Object given = rest.remove(FILES);
        if (given == null) {
            return new Call(BrowserToolPolicy.FILE_UPLOAD, rest, List.of(), null, List.of());
        }
        if (!(given instanceof List<?> list)) {
            throw new IllegalArgumentException(BrowserToolPolicy.FILE_UPLOAD + " 的 files 必须是文件编号的列表");
        }
        List<Integer> numbers = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())
                    || number.intValue() < 1 || number.intValue() > files.size()) {
                throw new IllegalArgumentException(BrowserToolPolicy.FILE_UPLOAD + " 的文件编号 " + item
                        + " 不存在（可用 1–" + files.size() + "）");
            }
            numbers.add(number.intValue());
            paths.add(files.get(number.intValue() - 1).toAbsolutePath().normalize().toString());
        }
        rest.put("paths", paths);
        return new Call(BrowserToolPolicy.FILE_UPLOAD, rest, List.of(), null, numbers);
    }

    private static FieldIntent checked(String tool, FieldIntent intent) {
        String problem = intent.problem();
        if (problem != null) {
            throw new IllegalArgumentException(tool + "：" + problem);
        }
        return intent;
    }

    private static Map<String, Object> parse(String tool, String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = JSON.readValue(argumentsJson, MAP);
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (JacksonException e) {
            throw new IllegalArgumentException(tool + " 的参数不是 JSON 对象");
        }
    }

    private static ToolDefinition definition(String name, String description, String schema) {
        return ToolDefinition.builder().name(name).description(description).inputSchema(schema).build();
    }

    private static ToolCallback callback(ToolDefinition definition) {
        return new DefinitionOnly(definition);
    }

    /** 只有定义的工具：执行由 Harness 循环负责，{@code call()} 被用到说明调用方式错了。 */
    private record DefinitionOnly(ToolDefinition definition) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            throw new IllegalStateException("浏览器工具 " + definition.name() + " 由 Harness 循环执行，不应被 SDK 调用");
        }
    }
}
