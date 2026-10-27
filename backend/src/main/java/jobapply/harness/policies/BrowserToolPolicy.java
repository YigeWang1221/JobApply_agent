package jobapply.harness.policies;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import jobapply.tools.SnapshotIndex;

/**
 * 浏览器工具白名单（C004-A「关键规则 · 白名单」）。规则写在代码里，不从配置、skill 或站点记录读取。发出之前检查，
 * 不允许时抛出 {@link ToolNotAllowedException}，调用方不得把这次调用发给 MCP 服务端。
 * 这里只管「能不能调、参数对不对」；点击和填写还要再过 {@link SubmitGuard}、{@link FieldClassGuard}（由 C004-B 的循环组合）。
 * 不开放：evaluate、run_code_unsafe（只由系统用固定模板代发：{@code browser_file_upload} 带 target、动作超时后的检查和被盖住时的直接点击、
 * 填写前的只读检查、{@code browser_select_option} 和 {@code browser_set_date}，C016）、handle_dialog、hover、drag、drop、navigate_back、close、console_messages、
 * network_*、take_screenshot，以及只有加 {@code --caps} 才出现的工具。{@code browser_tabs} 只由系统发（list / new / select，
 * 停下时 close 自己开的页），模型的标签页操作经过 {@code BrowserSession}。
 */
public final class BrowserToolPolicy {

    public static final String TABS = "browser_tabs";
    public static final String SNAPSHOT = "browser_snapshot";
    public static final String FIND = "browser_find";
    public static final String NAVIGATE = "browser_navigate";
    public static final String CLICK = "browser_click";
    public static final String TYPE = "browser_type";
    public static final String FILL_FORM = "browser_fill_form";
    public static final String SELECT_OPTION = "browser_select_option";
    public static final String FILE_UPLOAD = "browser_file_upload";
    public static final String PRESS_KEY = "browser_press_key";
    public static final String WAIT_FOR = "browser_wait_for";
    /** 设日期（C016 E）：不是 MCP 的工具，过了白名单后由系统用固定脚本（{@code PageScripts.DATE}）代发。 */
    public static final String SET_DATE = "browser_set_date";

    static final Set<String> FILL_ROLES = Set.of(
            "textbox", "combobox", "checkbox", "radio", "slider", "spinbutton", "searchbox");
    /** 下拉的触发元素（C016 B）：原生 select，以及自定义下拉常见的角色（只读输入框、按钮、没有角色的 div）。 */
    static final Set<String> SELECT_ROLES = Set.of("combobox", "listbox", "textbox", "searchbox", "button", "generic");
    /** 日期框（C016 E）。 */
    static final Set<String> DATE_ROLES = Set.of("textbox", "combobox", "searchbox", "button", "generic");
    private static final Pattern DATE = Pattern.compile("\\d{4}(-\\d{2}(-\\d{2})?)?");
    static final Set<String> KEYS = Set.of(
            "Tab", "Shift+Tab", "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "Escape", "Backspace", "Delete",
            "Home", "End", "PageUp", "PageDown");
    static final int MAX_WAIT_SECONDS = 10;

    /** 多段的公共后缀：主机名取最后三段作为站点（{@code jobs.example.com.cn} → {@code example.com.cn}）。 */
    private static final Set<String> MULTI_PART_SUFFIXES = Set.of(
            "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "ac.cn", "com.hk", "com.tw", "com.sg", "com.au",
            "net.au", "org.au", "co.uk", "org.uk", "ac.uk", "co.jp", "ne.jp", "or.jp", "co.kr", "co.in", "co.nz",
            "com.br", "com.mx");

    public enum Caller {
        SYSTEM, MODEL
    }

    /**
     * 一次检查的上下文。
     *
     * @param caller     谁发起的调用
     * @param latest     最新一次快照的索引；ref 只认这里出现过的
     * @param siteUrls   允许导航到的站点（岗位链接、当前页面）；按 registrable domain 比较
     * @param uploadsDir 这个 Job 的上传暂存目录 {@code storage/uploads/<job-id>/}；没有时不能上传
     */
    public record Context(Caller caller, SnapshotIndex latest, List<String> siteUrls, Path uploadsDir) {

        public Context {
            Objects.requireNonNull(caller, "caller");
            latest = latest == null ? SnapshotIndex.empty() : latest;
            siteUrls = siteUrls == null ? List.of() : List.copyOf(siteUrls);
        }

        public static Context system() {
            return new Context(Caller.SYSTEM, SnapshotIndex.empty(), List.of(), null);
        }

        public static Context model(SnapshotIndex latest, List<String> siteUrls, Path uploadsDir) {
            return new Context(Caller.MODEL, latest, siteUrls, uploadsDir);
        }
    }

    private BrowserToolPolicy() {
    }

    /** 系统自己的调用（新开标签页、取快照、列出标签页）。 */
    public static void check(String tool, Map<String, Object> args) {
        check(tool, args, Context.system());
    }

    public static void check(String tool, Map<String, Object> args, Context context) {
        Map<String, Object> arguments = args == null ? Map.of() : args;
        boolean model = context.caller() == Caller.MODEL;
        switch (tool == null ? "" : tool) {
            case TABS -> {
                systemOnly(tool, model);
                checkTabs(arguments);
            }
            case SNAPSHOT -> checkSnapshot(arguments);
            case FIND -> {
                modelOnly(tool, model);
                onlyKeys(tool, arguments, Set.of("text", "regex"));
            }
            case NAVIGATE -> {
                modelOnly(tool, model);
                checkNavigate(arguments, context);
            }
            case CLICK -> {
                modelOnly(tool, model);
                checkClick(arguments, context);
            }
            case TYPE -> {
                modelOnly(tool, model);
                checkType(arguments, context);
            }
            case FILL_FORM -> {
                modelOnly(tool, model);
                checkFillForm(arguments, context);
            }
            case SELECT_OPTION -> {
                modelOnly(tool, model);
                onlyKeys(tool, arguments, Set.of("target", "element", "values", "search"));
                if (!(arguments.get("values") instanceof List<?> values) || values.isEmpty()
                        || !values.stream().allMatch(value -> value instanceof String text && !text.isBlank())) {
                    throw new ToolNotAllowedException(tool + " 的 values 要写选项原文的列表（级联每层一个）");
                }
                requireRole(tool, ref(tool, arguments, context), context, SELECT_ROLES);
            }
            case SET_DATE -> {
                modelOnly(tool, model);
                onlyKeys(tool, arguments, Set.of("target", "element", "date"));
                if (!(arguments.get("date") instanceof String date) || !DATE.matcher(date.strip()).matches()) {
                    throw new ToolNotAllowedException(tool + " 的 date 要写成 YYYY-MM-DD、YYYY-MM 或 YYYY");
                }
                requireRole(tool, ref(tool, arguments, context), context, DATE_ROLES);
            }
            case FILE_UPLOAD -> {
                modelOnly(tool, model);
                checkUpload(arguments, context);
            }
            case PRESS_KEY -> {
                modelOnly(tool, model);
                checkKey(arguments);
            }
            case WAIT_FOR -> {
                modelOnly(tool, model);
                checkWait(arguments);
            }
            default -> throw new ToolNotAllowedException("工具 " + tool + " 不在白名单内");
        }
    }

    private static void checkTabs(Map<String, Object> args) {
        Object action = args.get("action");
        if ("list".equals(action)) {
            onlyKeys(TABS, args, Set.of("action"));
        } else if ("new".equals(action)) {
            onlyKeys(TABS, args, Set.of("action", "url"));
            requireHttpUrl(TABS + " new", args.get("url"));
        } else if ("select".equals(action) || "close".equals(action)) {
            // select：切回某个 Session / Job 自己新开的标签页（系统按记下的序号切换）；
            // close：一次申请 / 调研停下时关掉它自己开的页（C011-fix 2026-10-24，只由系统按序号发，模型调不到）
            onlyKeys(TABS, args, Set.of("action", "index"));
            if (!(args.get("index") instanceof Integer index) || index < 0) {
                throw new ToolNotAllowedException(TABS + " " + action + " 要给非负整数 index");
            }
        } else {
            throw new ToolNotAllowedException(TABS + " 只允许 action=list、new、select 或 close");
        }
    }

    private static void checkSnapshot(Map<String, Object> args) {
        onlyKeys(SNAPSHOT, args, Set.of("target", "depth", "boxes"));
        if (args.containsKey("target")) {
            requireRefFormat(SNAPSHOT, args.get("target"));
        }
    }

    private static void checkNavigate(Map<String, Object> args, Context context) {
        onlyKeys(NAVIGATE, args, Set.of("url"));
        URI target = requireHttpUrl(NAVIGATE, args.get("url"));
        String site = site(target.getHost());
        boolean sameSite = context.siteUrls().stream()
                .map(BrowserToolPolicy::hostOf)
                .filter(Objects::nonNull)
                .map(BrowserToolPolicy::site)
                .anyMatch(site::equals);
        if (!sameSite) {
            throw new ToolNotAllowedException(NAVIGATE + " 只能去岗位链接或当前页面所在的站点，" + target.getHost() + " 不是");
        }
    }

    private static void checkClick(Map<String, Object> args, Context context) {
        Set<String> allowed = new HashSet<>(Set.of("target", "element", "button"));
        allowed.addAll(SubmitGuard.ClickIntent.KEYS);
        onlyKeys(CLICK, args, allowed);
        if (args.containsKey("button") && !"left".equals(args.get("button"))) {
            throw new ToolNotAllowedException(CLICK + " 只允许左键");
        }
        ref(CLICK, args, context);
    }

    private static void checkType(Map<String, Object> args, Context context) {
        onlyKeys(TYPE, args, Set.of("target", "element", "text", "slowly", "submit"));
        if (args.containsKey("submit") && !Boolean.FALSE.equals(args.get("submit"))) {
            throw new ToolNotAllowedException(TYPE + " 不允许 submit: true（等于输完按回车）");
        }
        if (!(args.get("text") instanceof String)) {
            throw new ToolNotAllowedException(TYPE + " 缺少 text");
        }
        ref(TYPE, args, context);
    }

    private static void checkFillForm(Map<String, Object> args, Context context) {
        onlyKeys(FILL_FORM, args, Set.of("fields"));
        if (!(args.get("fields") instanceof List<?> fields) || fields.isEmpty()) {
            throw new ToolNotAllowedException(FILL_FORM + " 缺少 fields");
        }
        for (Object field : fields) {
            if (!(field instanceof Map<?, ?> map)) {
                throw new ToolNotAllowedException(FILL_FORM + " 的每个字段都必须是对象");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) map;
            onlyKeys(FILL_FORM + " 字段", entry, Set.of("target", "element", "name", "type", "value"));
            requireRole(FILL_FORM, ref(FILL_FORM, entry, context), context, FILL_ROLES);
        }
    }

    /**
     * 不带 {@code target}：交给已经打开的文件选择框。带 {@code target}（上传按钮 / 上传区域的 ref）：系统把文件直接设到
     * 它的文件输入框上（{@link jobapply.tools.PlaywrightMcpClient} 用固定模板代发），这时必须带 paths。
     */
    private static void checkUpload(Map<String, Object> args, Context context) {
        onlyKeys(FILE_UPLOAD, args, Set.of("paths", "target", "element"));
        Object paths = args.get("paths");
        if (args.containsKey("target")) {
            ref(FILE_UPLOAD, args, context);
            if (paths == null) {
                throw new ToolNotAllowedException(FILE_UPLOAD + " 带 target 时要写 files（文件编号）");
            }
        }
        if (paths == null) {
            return; // 不带 paths 等于取消文件选择框
        }
        if (!(paths instanceof List<?> list)) {
            throw new ToolNotAllowedException(FILE_UPLOAD + " 的 paths 必须是列表");
        }
        if (context.uploadsDir() == null) {
            throw new ToolNotAllowedException(FILE_UPLOAD + " 没有这个 Job 的上传目录，不能上传");
        }
        Path root = context.uploadsDir().toAbsolutePath().normalize();
        for (Object item : list) {
            if (!(item instanceof String path) || !insideUploads(path, root)) {
                throw new ToolNotAllowedException(FILE_UPLOAD + " 只能上传 " + root + " 下面的文件");
            }
        }
    }

    private static boolean insideUploads(String path, Path root) {
        try {
            Path candidate = Path.of(path);
            if (!candidate.isAbsolute() || !candidate.normalize().startsWith(root)
                    || candidate.normalize().equals(root)) {
                return false;
            }
            if (Files.exists(candidate) && Files.exists(root)) {
                return candidate.toRealPath().startsWith(root.toRealPath());
            }
            return true;
        } catch (InvalidPathException | IOException e) {
            return false;
        }
    }

    private static void checkKey(Map<String, Object> args) {
        onlyKeys(PRESS_KEY, args, Set.of("key"));
        Object key = args.get("key");
        if (key instanceof String name && (name.equalsIgnoreCase("Enter") || name.equals(" ")
                || name.equalsIgnoreCase("Space") || name.equalsIgnoreCase("Return")
                || name.toLowerCase(Locale.ROOT).contains("enter"))) {
            throw new ToolNotAllowedException(PRESS_KEY + " 不允许回车和空格（可能触发提交或点击）");
        }
        if (!(key instanceof String name) || !KEYS.contains(name)) {
            throw new ToolNotAllowedException(PRESS_KEY + " 只允许 " + String.join("、", KEYS.stream().sorted().toList()));
        }
    }

    private static void checkWait(Map<String, Object> args) {
        onlyKeys(WAIT_FOR, args, Set.of("time", "text", "textGone"));
        Object time = args.get("time");
        if (time != null && (!(time instanceof Number seconds) || seconds.doubleValue() < 0
                || seconds.doubleValue() > MAX_WAIT_SECONDS)) {
            throw new ToolNotAllowedException(WAIT_FOR + " 的 time 不能超过 " + MAX_WAIT_SECONDS + " 秒");
        }
    }

    /** {@code target} 必须是最新快照里出现过的 ref；选择器一律拒绝。 */
    private static String ref(String tool, Map<String, Object> args, Context context) {
        String ref = requireRefFormat(tool, args.get("target"));
        if (!context.latest().contains(ref)) {
            throw new ToolNotAllowedException(tool + " 的 target " + ref + " 不在最新快照里，先重新取快照");
        }
        return ref;
    }

    private static String requireRefFormat(String tool, Object target) {
        if (!(target instanceof String ref) || !SnapshotIndex.REF.matcher(ref).matches()) {
            throw new ToolNotAllowedException(tool + " 的 target 只接受快照里的 ref（例如 e12），不接受选择器");
        }
        return ref;
    }

    private static void requireRole(String tool, String ref, Context context, Set<String> roles) {
        String role = context.latest().find(ref).map(SnapshotIndex.Node::role).orElse(null);
        if (role == null || !roles.contains(role)) {
            throw new ToolNotAllowedException(tool + " 不能用在 ref " + ref + "（角色 " + role + "）上");
        }
    }

    private static void systemOnly(String tool, boolean model) {
        if (model) {
            throw new ToolNotAllowedException(tool + " 只能由系统调用");
        }
    }

    private static void modelOnly(String tool, boolean model) {
        if (!model) {
            throw new ToolNotAllowedException(tool + " 不在系统调用的白名单内");
        }
    }

    private static URI requireHttpUrl(String tool, Object url) {
        URI uri = url instanceof String text ? httpUri(text) : null;
        if (uri == null) {
            throw new ToolNotAllowedException(tool + " 只允许 http / https 地址");
        }
        return uri;
    }

    private static void onlyKeys(String tool, Map<String, Object> args, Set<String> allowed) {
        for (String key : args.keySet()) {
            if (!allowed.contains(key)) {
                throw new ToolNotAllowedException(tool + " 不允许参数 " + key);
            }
        }
    }

    private static URI httpUri(String url) {
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null ? uri : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static String hostOf(String url) {
        URI uri = url == null ? null : httpUri(url);
        return uri == null ? null : uri.getHost();
    }

    /** 网址所在的站点（{@link #site(String)}）；不是 http / https 地址时为 null。 */
    public static String siteOf(String url) {
        String host = hostOf(url);
        return host == null ? null : site(host);
    }

    /** registrable domain 的近似：最后两段；公共后缀是两段时取最后三段。IP 和单段主机名原样比较。 */
    static String site(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        if (h.startsWith("[") || h.matches("[0-9.]+")) {
            return h;
        }
        String[] labels = h.split("\\.");
        if (labels.length <= 2) {
            return h;
        }
        String lastTwo = labels[labels.length - 2] + "." + labels[labels.length - 1];
        int keep = MULTI_PART_SUFFIXES.contains(lastTwo) ? 3 : 2;
        StringBuilder site = new StringBuilder();
        for (int i = Math.max(0, labels.length - keep); i < labels.length; i++) {
            site.append(site.isEmpty() ? "" : ".").append(labels[i]);
        }
        return site.toString();
    }
}
