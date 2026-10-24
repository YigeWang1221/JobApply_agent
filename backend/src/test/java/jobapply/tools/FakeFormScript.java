package jobapply.tools;

import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link FakePlaywrightMcpServer} 的表单模式：按表单脚本让页面变化。表单脚本放在
 * {@code src/test/resources/fixtures/forms/<name>.scenario.json}，内容全部虚构，和同名的 {@code .html}（手动冒烟用）
 * 表达同一个表单。由 {@link FakeBrowser#form(String)} 读入场景文件的 {@code form} 键。
 * <p>
 * 格式：
 * <pre>{@code
 * {
 *   "name": "multi-page",
 *   "entry": "step1",                    // 可选：打开表单网址时进入哪一页；默认是 url 相同的第一页
 *   "pages": {                           // 页面 id → 页面，按书写顺序
 *     "step1": {
 *       "url": "https://jobs.example.test/apply/100",  // 几页可以共用一个 url（单页应用）
 *       "title": "填写申请 · 基本信息",
 *       "snapshot": ["- textbox \"姓名\" [ref=e2]", "- button \"下一步\" [ref=e9]"],  // 行的列表，或一个字符串
 *       "modal": "- [\"confirm\" dialog with message \"…\"]: …",  // 可选：这一页一直带着的 Modal state
 *       "clicks": {                      // 点击某个 ref 之后发生什么；没写的 ref 点了页面不变
 *         "e9": "step2",                 //   简写：转到另一页
 *         "e6": {
 *           "to": "step2",               //   可选：转到另一页
 *           "modal": "…",                //   可选：之后出现的 Modal state（例如安全验证、确认框）
 *           "fileChooser": "e7",         //   可选：打开文件选择框；上传后文件名写到 ref e7 这一行的行尾
 *           "blocked": true,             //   可选：点不到（被遮罩盖住），返回动作超时，什么都不发生；
 *                                        //   browser_run_code_unsafe 的 setInputFiles 仍能把文件设到 fileChooser 那一行
 *           "coveredBy": {"tag": "div", "cls": "bottom-bar", "text": "提交", "position": "fixed"},
 *                                        //   可选（和 blocked 一起）：系统检查时报告盖住它的固定层；
 *                                        //   系统的直接点击（PlaywrightMcpClient.clickThrough）照常生效
 *           "set": {"e3": "值"}           //   可选：（转到的）页面上这些 ref 的值
 *         }
 *       },
 *       "afterUpload": {"to": "…", "set": {"e3": "解析出的值"}}  // 可选：在这一页上传文件之后
 *     }
 *   }
 * }
 * }</pre>
 * 行为：
 * <ul>
 *   <li>{@code browser_tabs new}、{@code browser_navigate}：按网址进入页面（也认场景文件的 {@code redirects}）；
 *       网址不在脚本里时是一个空页面。</li>
 *   <li>{@code browser_type}、{@code browser_fill_form}、{@code browser_select_option}：值写到 ref 那一行的行尾
 *       （{@code - textbox "姓名" [ref=e2]: 值}）；勾选框、单选、开关的 {@code "true"} / {@code "false"} 改成
 *       {@code [checked]}。值按页面 id 记住，回到这一页时还在。</li>
 *   <li>ref 不在当前快照里：返回错误，页面不变。没有文件选择框时 {@code browser_file_upload} 返回错误；
 *       不带 {@code paths} 等于取消选择框。</li>
 *   <li>{@code browser_snapshot}、{@code browser_wait_for}、{@code browser_press_key} 返回当前页；
 *       {@code browser_find} 只返回含有 {@code text}（或匹配 {@code regex}）的行，不带整页快照。</li>
 *   <li>用户在浏览器里亲手改的值：场景文件的 {@code userEdits}（{@link FakeBrowser#userEdit}），每条只应用一次。</li>
 * </ul>
 * 状态只在服务端进程的内存里：重连（新进程）之后从头开始，只有 {@code userEdits} 会再应用一次。
 */
final class FakeFormScript {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> CHECKABLE = Set.of("checkbox", "radio", "switch");
    private static final Pattern REF_ATTR = Pattern.compile("\\[ref=([^\\]\\s]+)]");
    private static final Pattern CHECKED = Pattern.compile(" ?\\[checked(=[^\\]]*)?]");
    static final String FILE_CHOOSER = "- [File chooser]: can be handled by the \"browser_file_upload\" tool";

    private String page;
    private String url = "about:blank";
    private String modal = "";
    private String fileChooser;
    private final Map<String, Map<String, String>> values = new LinkedHashMap<>();
    private final Set<Object> appliedEdits = new HashSet<>();

    McpSchema.CallToolResult handle(String tool, Map<String, Object> args, Map<String, Object> scenario) {
        Map<String, Object> form = map(scenario.get("form"));
        applyUserEdits(scenario.get("userEdits"));
        return switch (tool) {
            case "browser_tabs" -> {
                if ("new".equals(args.get("action"))) {
                    open(form, redirect(scenario, String.valueOf(args.get("url"))));
                }
                yield text("### Open tabs\n- 0: (current) [" + title(form) + "](" + url + ")\n" + render(form));
            }
            case "browser_navigate" -> {
                open(form, redirect(scenario, String.valueOf(args.get("url"))));
                yield ran(form);
            }
            case "browser_snapshot", "browser_wait_for", "browser_press_key" -> text(render(form));
            case "browser_find" -> text("### Result\n" + find(form, args));
            case "browser_click" -> click(form, String.valueOf(args.get("target")));
            case "browser_type" -> fill(form, List.of(Map.of("target", String.valueOf(args.get("target")),
                    "value", String.valueOf(args.get("text")))));
            case "browser_select_option" -> {
                Object selected = args.get("values");
                String value = selected instanceof List<?> list
                        ? String.join(", ", list.stream().map(String::valueOf).toList())
                        : String.valueOf(selected);
                yield fill(form, List.of(Map.of("target", String.valueOf(args.get("target")), "value", value)));
            }
            case "browser_fill_form" -> {
                List<Map<String, Object>> fields = new ArrayList<>();
                if (args.get("fields") instanceof List<?> list) {
                    list.forEach(item -> fields.add(map(item)));
                }
                yield fill(form, fields);
            }
            case "browser_file_upload" -> upload(form, args);
            case "browser_run_code_unsafe" -> runCode(form, String.valueOf(args.get("code")));
            default -> text("### Result\nok");
        };
    }

    /** 系统的固定模板：按代码里的标记认出是哪一个（setInputFiles、超时后的检查、直接点击）。 */
    private McpSchema.CallToolResult runCode(Map<String, Object> form, String code) {
        Matcher ref = Pattern.compile("aria-ref=([a-z0-9]+)").matcher(code);
        if (code.contains("elementFromPoint") && ref.find()) {
            return diagnose(form, ref.group(1));
        }
        if (code.contains("clicked the element directly") && ref.find()) {
            McpSchema.CallToolResult clicked = click(form, ref.group(1), true);
            return Boolean.TRUE.equals(clicked.isError()) ? clicked
                    : text("### Result\n\"clicked the element directly\"\n" + render(form));
        }
        return setInputFiles(form, code);
    }

    /** 超时后的检查：{@code coveredBy} 写了就报被它盖住，否则报可见、没被盖住。 */
    private McpSchema.CallToolResult diagnose(Map<String, Object> form, String ref) {
        if (!hasRef(form, ref)) {
            return missingRef(ref);
        }
        Map<String, Object> cover = map(map(map(current(form).get("clicks")).get(ref)).get("coveredBy"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("visible", true);
        result.put("inViewport", true);
        result.put("covered", !cover.isEmpty());
        result.put("target", Map.of("tag", "button", "role", "", "text", ref, "cls", "", "position", "static"));
        result.put("cover", cover.isEmpty() ? null : cover);
        result.put("layer", cover.isEmpty() ? null : cover);
        result.put("popups", List.of());
        return text("### Result\n" + JSON.writeValueAsString(result) + "\n### Ran Playwright code\n```js\n// fake\n```");
    }

    private McpSchema.CallToolResult click(Map<String, Object> form, String ref) {
        return click(form, ref, false);
    }

    /** {@code direct}：系统的直接点击，不受 {@code blocked} 影响。 */
    private McpSchema.CallToolResult click(Map<String, Object> form, String ref, boolean direct) {
        if (!hasRef(form, ref)) {
            return missingRef(ref);
        }
        Object action = map(current(form).get("clicks")).get(ref);
        if (!direct && action instanceof Map<?, ?> step && Boolean.TRUE.equals(step.get("blocked"))) {
            return error("TimeoutError: browserBackend.callTool: Timeout 15000ms exceeded.");
        }
        if (action instanceof String to) {
            go(form, to);
        } else if (action instanceof Map<?, ?> step) {
            Map<String, Object> details = map(step);
            if (details.get("to") instanceof String to) {
                go(form, to);
            }
            if (details.get("modal") instanceof String text) {
                modal = text;
            }
            if (details.get("fileChooser") instanceof String target) {
                fileChooser = target;
                if (modal.isEmpty()) {
                    modal = FILE_CHOOSER;
                }
            }
            set(map(details.get("set")));
        }
        return ran(form);
    }

    private McpSchema.CallToolResult fill(Map<String, Object> form, List<Map<String, Object>> fields) {
        for (Map<String, Object> field : fields) {
            String ref = String.valueOf(field.get("target"));
            if (!hasRef(form, ref)) {
                return missingRef(ref);
            }
        }
        for (Map<String, Object> field : fields) {
            valuesOf(page).put(String.valueOf(field.get("target")), String.valueOf(field.get("value")));
        }
        return ran(form);
    }

    /** 系统的固定模板（{@code PlaywrightMcpClient.setInputFiles}）：从代码里取 ref 和路径，文件名写到那个上传位置。 */
    private McpSchema.CallToolResult setInputFiles(Map<String, Object> form, String code) {
        if (!modal.isEmpty()) {
            return error("Error: Tool \"browser_run_code_unsafe\" does not handle the modal state.\n### Modal state\n"
                    + modal);
        }
        Matcher ref = Pattern.compile("aria-ref=([a-z0-9]+)").matcher(code);
        Matcher files = Pattern.compile("setInputFiles\\((\\[.*?])\\)").matcher(code);
        if (!ref.find() || !files.find() || !hasRef(form, ref.group(1))) {
            return error("Error: 这个上传位置附近没有文件输入框（input type=file）");
        }
        Object slot = map(map(current(form).get("clicks")).get(ref.group(1))).get("fileChooser");
        if (!(slot instanceof String target)) {
            return error("Error: 这个上传位置附近没有文件输入框（input type=file）");
        }
        List<?> paths = JSON.readValue(files.group(1), List.class);
        valuesOf(page).put(target, String.join(", ", paths.stream()
                .map(path -> Path.of(String.valueOf(path)).getFileName().toString()).toList()));
        return text("### Result\n\"files set on the file input\"");
    }

    private McpSchema.CallToolResult upload(Map<String, Object> form, Map<String, Object> args) {
        if (!modal.contains("File chooser")) {
            return error("Error: The tool \"browser_file_upload\" can only be used when there is related modal "
                    + "state present.");
        }
        String target = fileChooser;
        modal = "";
        fileChooser = null;
        if (!(args.get("paths") instanceof List<?> paths)) {
            return ran(form);
        }
        if (target != null) {
            valuesOf(page).put(target, String.join(", ", paths.stream()
                    .map(path -> Path.of(String.valueOf(path)).getFileName().toString()).toList()));
        }
        Map<String, Object> after = map(current(form).get("afterUpload"));
        if (after.get("to") instanceof String to) {
            go(form, to);
        }
        set(map(after.get("set")));
        return ran(form);
    }

    private void open(Map<String, Object> form, String target) {
        url = target;
        modal = "";
        fileChooser = null;
        page = null;
        Map<String, Object> pages = map(form.get("pages"));
        if (form.get("entry") instanceof String id && target.equals(map(pages.get(id)).get("url"))) {
            page = id;
            return;
        }
        for (Map.Entry<String, Object> candidate : pages.entrySet()) {
            if (target.equals(map(candidate.getValue()).get("url"))) {
                page = candidate.getKey();
                return;
            }
        }
    }

    private void go(Map<String, Object> form, String id) {
        Map<String, Object> next = map(map(form.get("pages")).get(id));
        if (next.isEmpty()) {
            throw new IllegalStateException("表单脚本里没有页面 " + id);
        }
        page = id;
        url = String.valueOf(next.get("url"));
        modal = "";
        fileChooser = null;
    }

    private void set(Map<String, Object> changes) {
        changes.forEach((ref, value) -> valuesOf(page).put(ref, String.valueOf(value)));
    }

    private void applyUserEdits(Object edits) {
        if (!(edits instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            Map<String, Object> edit = map(item);
            if (appliedEdits.add(edit.get("id"))) {
                valuesOf(String.valueOf(edit.get("page"))).put(String.valueOf(edit.get("ref")),
                        String.valueOf(edit.get("value")));
            }
        }
    }

    private Map<String, String> valuesOf(String pageId) {
        return values.computeIfAbsent(pageId == null ? "" : pageId, key -> new LinkedHashMap<>());
    }

    private Map<String, Object> current(Map<String, Object> form) {
        return page == null ? Map.of() : map(map(form.get("pages")).get(page));
    }

    private String title(Map<String, Object> form) {
        Object title = current(form).get("title");
        return title == null ? "" : title.toString();
    }

    /** 当前页的快照行，已经带上字段的值。 */
    private List<String> lines(Map<String, Object> form) {
        Object snapshot = current(form).get("snapshot");
        List<String> raw;
        if (snapshot instanceof List<?> list) {
            raw = list.stream().map(String::valueOf).toList();
        } else if (snapshot instanceof String text) {
            raw = List.of(text.split("\n", -1));
        } else {
            raw = List.of("- generic [ref=e1]");
        }
        Map<String, String> filled = valuesOf(page);
        List<String> rendered = new ArrayList<>();
        for (String line : raw) {
            Matcher ref = REF_ATTR.matcher(line);
            rendered.add(ref.find() && filled.containsKey(ref.group(1))
                    ? withValue(line, ref, filled.get(ref.group(1)))
                    : line);
        }
        return rendered;
    }

    static String withValue(String line, Matcher ref, String value) {
        String body = line.stripLeading();
        String role = body.startsWith("- ") ? body.substring(2).split("[ \"\\[:]", 2)[0] : "";
        String beforeRef = line.substring(0, ref.start());
        String refAttr = line.substring(ref.start(), ref.end());
        String rest = line.substring(ref.end());
        if (CHECKABLE.contains(role) && ("true".equals(value) || "false".equals(value))) {
            String unchecked = CHECKED.matcher(beforeRef).replaceAll("");
            String spacer = unchecked.endsWith(" ") ? "" : " ";
            return unchecked + ("true".equals(value) ? spacer + "[checked] " : spacer) + refAttr + rest;
        }
        int colon = rest.indexOf(':');
        String attrs = colon < 0 ? rest : rest.substring(0, colon);
        return beforeRef + refAttr + attrs + ": " + quote(value);
    }

    private static String quote(String value) {
        if (value.isEmpty() || value.contains(": ") || value.contains("\"") || value.startsWith("'")
                || value.startsWith("[") || value.startsWith("{") || value.contains("\n")) {
            return JSON.writeValueAsString(value);
        }
        return value;
    }

    private boolean hasRef(Map<String, Object> form, String ref) {
        return lines(form).stream().anyMatch(line -> line.contains("[ref=" + ref + "]"));
    }

    private String find(Map<String, Object> form, Map<String, Object> args) {
        String needle = args.get("text") instanceof String text ? text : "";
        Pattern regex = args.get("regex") instanceof String pattern ? Pattern.compile(pattern) : null;
        List<String> found = lines(form).stream()
                .filter(line -> regex != null ? regex.matcher(line).find() : line.contains(needle))
                .map(String::strip)
                .toList();
        return found.isEmpty() ? "No matches" : String.join("\n", found);
    }

    private String render(Map<String, Object> form) {
        String text = "### Page\n- Page URL: " + url + "\n- Page Title: " + title(form) + "\n"
                + "### Snapshot\n```yaml\n" + String.join("\n", lines(form)) + "\n```";
        String state = !modal.isEmpty() ? modal
                : current(form).get("modal") instanceof String fixed ? fixed : "";
        return state.isEmpty() ? text : text + "\n### Modal state\n" + state;
    }

    private McpSchema.CallToolResult ran(Map<String, Object> form) {
        return text("### Ran Playwright code\n```js\n// fake\n```\n" + render(form));
    }

    private static McpSchema.CallToolResult missingRef(String ref) {
        return error("Error: Ref " + ref + " not found in the current page snapshot. Try capturing new snapshot.");
    }

    private static String redirect(Map<String, Object> scenario, String target) {
        Object redirect = map(scenario.get("redirects")).get(target);
        return redirect == null ? target : redirect.toString();
    }

    private static McpSchema.CallToolResult text(String text) {
        return McpSchema.CallToolResult.builder().addTextContent(text).build();
    }

    private static McpSchema.CallToolResult error(String text) {
        return McpSchema.CallToolResult.builder().addTextContent("### Error\n" + text).isError(true).build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
