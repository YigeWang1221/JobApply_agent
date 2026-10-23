package jobapply.tools;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jobapply.harness.policies.SubmitWords;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 控制 {@link FakePlaywrightMcpServer} 的场景文件，并读取它的调用记录。页面数据全部虚构。
 */
public final class FakeBrowser {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final Pattern BUTTON = Pattern.compile("(?:button|link) \"([^\"]*)\" [^\\n]*\\[ref=([^\\]\\s]+)]");

    public final Path scenarioFile;
    public final Path callLog;
    private final Map<String, Object> scenario = new LinkedHashMap<>();

    private FakeBrowser(Path dir) {
        this.scenarioFile = dir.resolve("fake-browser.json");
        this.callLog = dir.resolve("fake-browser.calls.jsonl");
        scenario.put("pages", new LinkedHashMap<String, Object>());
        scenario.put("redirects", new LinkedHashMap<String, Object>());
        save();
    }

    public static FakeBrowser create(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new FakeBrowser(dir);
    }

    /** 用当前 JVM 的 java 和测试 classpath 启动假服务端。 */
    public BrowserLaunch launch(Duration callTimeout) {
        return launch(callTimeout, List.of(), null);
    }

    /** 同上，并声明 roots、管理输出目录。 */
    public BrowserLaunch launch(Duration callTimeout, List<Path> roots, Path outputDir) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new BrowserLaunch(java, List.of("-cp", System.getProperty("java.class.path"),
                FakePlaywrightMcpServer.class.getName(), scenarioFile.toString(), callLog.toString()),
                callTimeout, System.getenv("PATH"), System.getProperty("user.home"), roots, outputDir);
    }

    public BrowserLaunch launch() {
        return launch(Duration.ofSeconds(10));
    }

    @SuppressWarnings("unchecked")
    public synchronized FakeBrowser page(String url, String title, String snapshot) {
        Map<String, Object> pages = (Map<String, Object>) scenario.get("pages");
        pages.put(url, Map.of("title", title, "snapshot", snapshot));
        return save();
    }

    @SuppressWarnings("unchecked")
    public synchronized FakeBrowser redirect(String from, String to) {
        ((Map<String, Object>) scenario.get("redirects")).put(from, to);
        return save();
    }

    @SuppressWarnings("unchecked")
    public synchronized FakeBrowser clearRedirect(String from) {
        ((Map<String, Object>) scenario.get("redirects")).remove(from);
        return save();
    }

    /**
     * 进入表单模式：读入 {@code fixtures/forms/<name>.scenario.json}（格式见 {@link FakeFormScript}）。
     */
    public synchronized FakeBrowser form(String name) {
        String resource = "/fixtures/forms/" + name + ".scenario.json";
        try (var in = FakeBrowser.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("没有表单夹具：" + resource);
            }
            scenario.put("form", JSON.readValue(in, MAP));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return save();
    }

    /** 表单的入口网址：{@code entry} 那一页的 url，没有 {@code entry} 时是第一页的 url。 */
    @SuppressWarnings("unchecked")
    public synchronized String formUrl() {
        Map<String, Object> form = (Map<String, Object>) scenario.get("form");
        if (form == null) {
            throw new IllegalStateException("还没有调用 form(...)");
        }
        Map<String, Object> pages = (Map<String, Object>) form.get("pages");
        Object entry = form.get("entry");
        Object page = entry != null ? pages.get(entry) : pages.values().iterator().next();
        return (String) ((Map<String, Object>) page).get("url");
    }

    /** 模拟用户在浏览器里亲手改了表单页 {@code page} 上 {@code ref} 的值（下一次调用时生效，只生效一次）。 */
    @SuppressWarnings("unchecked")
    public synchronized FakeBrowser userEdit(String page, String ref, String value) {
        List<Object> edits = (List<Object>) scenario.computeIfAbsent("userEdits", key -> new ArrayList<>());
        edits.add(Map.of("id", edits.size() + 1, "page", page, "ref", ref, "value", value));
        return save();
    }

    /** 下一次调用时服务端直接退出，不回应（一次性）。 */
    public synchronized FakeBrowser exitOnNextCall() {
        scenario.put("exitOnNextCall", true);
        writeJson(scenarioFile, scenario);
        return this;
    }

    /** 之后每次调用都返回这条错误，直到传入 null。 */
    public synchronized FakeBrowser toolError(String error) {
        scenario.put("toolError", error == null ? "" : error);
        return save();
    }

    public synchronized FakeBrowser callDelay(Duration delay) {
        scenario.put("callDelayMillis", delay.toMillis());
        return save();
    }

    public synchronized FakeBrowser omitTools(String... tools) {
        scenario.put("omitTools", List.of(tools));
        return save();
    }

    public List<Map<String, Object>> records() {
        if (!Files.exists(callLog)) {
            return List.of();
        }
        List<Map<String, Object>> records = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(callLog, UTF_8)) {
                if (!line.isBlank()) {
                    records.add(JSON.readValue(line, MAP));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return records;
    }

    /** 服务端收到的工具名，按顺序。 */
    public List<String> toolCalls() {
        return records().stream().filter(r -> "call".equals(r.get("event"))).map(r -> (String) r.get("tool")).toList();
    }

    /** 服务端收到的某个工具的每次调用参数，按顺序。 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> calls(String tool) {
        return records().stream()
                .filter(r -> "call".equals(r.get("event")) && tool.equals(r.get("tool")))
                .map(r -> (Map<String, Object>) r.get("args"))
                .toList();
    }

    /** 最近一次调用时，服务端从客户端拿到的 roots（URI）。 */
    @SuppressWarnings("unchecked")
    public List<String> lastRoots() {
        return records().stream().filter(r -> "call".equals(r.get("event")))
                .reduce((first, second) -> second)
                .map(r -> (List<String>) r.get("roots"))
                .orElse(List.of());
    }

    public int starts() {
        return (int) records().stream().filter(r -> "start".equals(r.get("event"))).count();
    }

    /** 最近一次启动时子进程看到的环境变量名。 */
    @SuppressWarnings("unchecked")
    public List<String> lastEnvNames() {
        return records().stream().filter(r -> "start".equals(r.get("event")))
                .reduce((first, second) -> second)
                .map(r -> (List<String>) r.get("env"))
                .orElse(List.of());
    }

    /**
     * 表单夹具里所有名字命中硬边界词表（提交类）的按钮和链接的 ref，用来断言它们一次都没有被点到
     * （{@code calls("browser_click")} 的 target 里没有）。夹具里没有提交类按钮时报错。
     */
    @SuppressWarnings("unchecked")
    public static Set<String> hardRefs(String fixture) {
        String resource = "/fixtures/forms/" + fixture + ".scenario.json";
        try (var in = FakeBrowser.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("没有表单夹具：" + resource);
            }
            Map<String, Object> form = JSON.readValue(in, MAP);
            Set<String> refs = new HashSet<>();
            for (Object page : ((Map<String, Object>) form.get("pages")).values()) {
                Object snapshot = ((Map<String, Object>) page).get("snapshot");
                String text = snapshot instanceof List<?> lines
                        ? String.join("\n", lines.stream().map(String::valueOf).toList()) : String.valueOf(snapshot);
                Matcher matcher = BUTTON.matcher(text);
                while (matcher.find()) {
                    if (SubmitWords.isHard(matcher.group(1))) {
                        refs.add(matcher.group(2));
                    }
                }
            }
            if (refs.isEmpty()) {
                throw new IllegalStateException("夹具 " + fixture + " 里没有提交类按钮");
            }
            return refs;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 虚构的登录页快照。 */
    public static String loginSnapshot() {
        return """
                - heading "账号登录" [level=1] [ref=e1]
                - textbox "手机号 / 邮箱" [ref=e2]
                - textbox "密码" [ref=e3]
                - button "登录" [ref=e4]""";
    }

    /** 虚构的已登录岗位页快照，页面上显示账号。 */
    public static String jobPageSnapshot(String account) {
        return """
                - banner [ref=e1]:
                  - link "我的投递" [ref=e2]
                  - text: "%s"
                - heading "Backend Engineer" [level=1] [ref=e3]
                - button "立即申请" [ref=e4]""".formatted(account);
    }

    private FakeBrowser save() {
        // 一次性的 exitOnNextCall 由服务端用掉（它把文件里的值改回 false）：以文件里的为准，免得之后的修改把它又写回 true
        if (Boolean.TRUE.equals(scenario.get("exitOnNextCall")) && Files.exists(scenarioFile)
                && !Boolean.TRUE.equals(readJson(scenarioFile).get("exitOnNextCall"))) {
            scenario.put("exitOnNextCall", false);
        }
        writeJson(scenarioFile, scenario);
        return this;
    }

    static Map<String, Object> readJson(Path file) {
        try {
            return JSON.readValue(Files.readString(file, UTF_8), MAP);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void writeJson(Path file, Map<String, Object> content) {
        try {
            Path temp = Files.createTempFile(file.getParent(), ".fake", ".tmp");
            Files.writeString(temp, JSON.writeValueAsString(content), UTF_8);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
