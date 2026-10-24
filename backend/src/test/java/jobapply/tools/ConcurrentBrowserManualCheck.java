package jobapply.tools;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jobapply.harness.policies.BrowserToolPolicy;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * C011 第 7 节的两个 Worker 矩阵，在<b>真实浏览器</b>上手动跑（第 2 阶段）：自己启动一个 Chrome for Testing（临时 profile、
 * 有头、{@code --remote-debugging-port} 用随机端口，<b>不是 9222，不连用户的 Chrome</b>），只打开本机 127.0.0.1 上的夹具页，
 * 走和应用一样的代码路径（{@link BrowserSession} → 一个 {@code @playwright/mcp@0.0.83 --port --shared-browser-context}
 * 服务进程，只是 {@code --cdp-endpoint} 换成这个 Chrome 的地址）。不访问外部网站，不提交任何东西。
 * 名字不以 Test / IT 结尾，{@code mvn verify} 不跑它。
 *
 * <pre>
 * mvn -B test -Dtest=ConcurrentBrowserManualCheck -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
class ConcurrentBrowserManualCheck {

    private static final Path CHROME = Path.of(System.getProperty("user.home"), "Library/Caches/ms-playwright/chromium-1243",
            "chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing");
    private static final Path MCP_CLI = Path.of(System.getProperty("user.home"),
            ".npm/_npx/381cec31605a419a/node_modules/@playwright/mcp/cli.js");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** 一行结果：场景、谁、做什么、相对开始的毫秒、通过没有、说明。 */
    private record Row(String scenario, String owner, String action, long start, long end, boolean ok, String detail) {
    }

    private final List<Row> rows = new CopyOnWriteArrayList<>();
    private long t0;
    private String base;
    private int cdpPort;

    @Test
    void twoWorkersOnOneRealChrome() throws Exception {
        assumeTrue(Files.isExecutable(CHROME), "没有 Chrome for Testing：" + CHROME);
        assumeTrue(Files.exists(MCP_CLI), "没有本机缓存的 @playwright/mcp 0.0.83：" + MCP_CLI);
        Path work = Files.createTempDirectory("c011-manual");
        HttpServer pages = fixtureServer();
        base = "http://127.0.0.1:" + pages.getAddress().getPort();
        cdpPort = freePort();
        Process chrome = new ProcessBuilder(CHROME.toString(), "--remote-debugging-port=" + cdpPort,
                "--user-data-dir=" + work.resolve("profile"), "--no-first-run", "--no-default-browser-check",
                "--disable-sync", "--disable-background-networking", "--disable-component-update", "--no-pings",
                "--password-store=basic", "--use-mock-keychain", "--window-size=900,700", "about:blank")
                .redirectErrorStream(true).redirectOutput(work.resolve("chrome.log").toFile()).start();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        BrowserLaunch launch = new BrowserLaunch(which("node"), List.of(MCP_CLI.toString(), "--cdp-endpoint",
                "http://127.0.0.1:" + cdpPort, "--snapshot-mode", "none", "--console-level", "error",
                "--image-responses", "omit", "--no-webmcp", "--timeout-action", "5000",
                "--output-dir", work.resolve("mcp-out").toString()),
                Duration.ofSeconds(60), System.getenv("PATH"), System.getProperty("user.home"),
                List.of(work.resolve("uploads")), work.resolve("mcp-out"));
        try (BrowserSession session = new BrowserSession(launch)) {
            waitForChrome();
            t0 = System.currentTimeMillis();
            try {
                run(session, pool);
            } catch (Exception e) {
                rows.add(new Row("中断", "-", "矩阵没跑完", now(), now(), false, rootMessage(e)));
            }
        } finally {
            pool.shutdownNow();
            chrome.destroy();
            chrome.waitFor(5, TimeUnit.SECONDS);
            chrome.destroyForcibly();
            pages.stop(0);
        }
        System.out.println("\n==== C011 第 7 节矩阵（真实 Chrome for Testing，有头，本机夹具页） ====");
        System.out.println("场景 | 谁 | 动作 | 开始–结束 ms | 结果 | 说明");
        rows.forEach(r -> System.out.printf("%s | %s | %s | %d–%d | %s | %s%n", r.scenario(), r.owner(), r.action(),
                r.start(), r.end(), r.ok() ? "通过" : "不通过", r.detail()));
        long failed = rows.stream().filter(r -> !r.ok()).count();
        System.out.println("不通过：" + failed);
    }

    private void run(BrowserSession session, ExecutorService pool) throws Exception {
        // 0. 建立共享连接：数一下到 Chrome 的 CDP 连接
        session.reconnect();
        check("0 连接", "-", "建立共享连接", session.isConnected(), "状态 " + session.state().state());
        int atConnect = cdpConnections();
        // connectOverCDP(http://…) 先发一次 HTTP 取 /json/version，那条 keep-alive 连接几秒后才断；等它断了再数 websocket
        Thread.sleep(6000);
        int afterConnect = cdpConnections();
        check("0 连接", "-", "到 Chrome 的连接数（刚连上 / 6 秒后）", afterConnect == 1, atConnect + " / " + afterConnect
                + "；" + lsof());

        // 1. 同时开页、同时填写、同时取快照、同时点击
        both("1 同时", session, pool, "open",
                o -> session.open(base + "/form?n=" + o, o),
                (o, p) -> p.title().equals(o));
        both("1 同时", session, pool, "type",
                o -> session.act(o, BrowserToolPolicy.TYPE, Map.of("target", ref(session.snapshot(o), "textbox", "Name"),
                        "element", "Name", "text", "value-" + o), model(o, session)),
                (o, p) -> p.snapshot().contains("value-" + o) && !p.snapshot().contains("value-" + other(o)));
        both("1 同时", session, pool, "snapshot", session::snapshot, (o, p) -> p.title().equals(o));
        both("1 同时", session, pool, "click Go",
                o -> click(session, o, "button", "Go"),
                (o, p) -> p.snapshot().contains("clicked 1") && p.title().equals(o));
        int afterOwners = cdpConnections();

        // 2. A 被重定向（同站换网址）时 B 在填写
        Future<PageSnapshot> redirect = pool.submit(() -> timed("2 重定向", "A", "click Redirect",
                () -> click(session, "A", "button", "Redirect")));
        Future<PageSnapshot> typing = pool.submit(() -> timed("2 重定向", "B", "type",
                () -> session.act("B", BrowserToolPolicy.TYPE, Map.of("target", ref(session.snapshot("B"), "textbox", "Name"),
                        "element", "Name", "text", "during-redirect"), model("B", session))));
        redirect.get();
        PageSnapshot bTyped = typing.get();
        Thread.sleep(500);
        PageSnapshot aAfter = session.snapshot("A");
        check("2 重定向", "A", "redirect 后快照", aAfter.title().equals("A-step2") && session.ownsTab("A"),
                aAfter.url() + "；说明：" + session.takeNotice("A"));
        check("2 重定向", "B", "B 不受影响", bTyped.title().equals("B") && bTyped.snapshot().contains("during-redirect"),
                bTyped.url());

        // 3. A 点击弹出新标签页（window.open），同时 B 在点击
        Future<PageSnapshot> popup = pool.submit(() -> timed("3 弹窗", "A", "click Popup",
                () -> click(session, "A", "button", "Popup")));
        Future<PageSnapshot> bClick = pool.submit(() -> timed("3 弹窗", "B", "click Go",
                () -> click(session, "B", "button", "Go")));
        PageSnapshot aPopup = popup.get();
        PageSnapshot bAfterPopup = bClick.get();
        check("3 弹窗", "A", "弹窗归 A、A 切过去", aPopup.title().equals("A-step2-pop"),
                aPopup.url() + "；说明：" + session.takeNotice("A"));
        check("3 弹窗", "B", "B 的当前页不变", bAfterPopup.title().equals("B") && session.snapshot("B").title().equals("B"),
                bAfterPopup.url());
        check("3 弹窗", "B", "B 的列表里没有 A 的页",
                session.tabs("B").stream().allMatch(t -> t.url().contains("n=B")), session.tabs("B").toString());
        check("3 弹窗", "A", "A 的列表", session.tabs("A").size() == 2, session.tabs("A").toString());

        // 3b. noopener 弹窗（target=_blank 链接）
        session.select("B", 0);
        PageSnapshot blank = timed("3b noopener", "B", "click Blank link", () -> click(session, "B", "link", "Blank link"));
        check("3b noopener", "B", "target=_blank 的新页", blank.title().equals("B-blank"),
                blank.url() + "；说明：" + session.takeNotice("B"));
        session.select("B", 0);

        // 8. 用户在看第三个标签页：两个 Worker 的页都在后台，同时点击都不超时（焦点模拟）
        String userTab = openUserTab(base + "/form?n=USER");
        activate(userTab);
        Thread.sleep(500);
        both("8 后台页", session, pool, "click Go（用户在看第三页）",
                o -> click(session, o, "button", "Go"),
                (o, p) -> p.snapshot().contains("clicked"));
        check("8 后台页", "-", "第三页不归任何人", session.unowned().stream().anyMatch(t -> t.url().contains("n=USER")),
                session.unowned().toString());

        // 6. 文件选择框 / 对话框出现在 C 的页上，B 同时动作
        session.open(base + "/form?n=C", "C");
        Future<PageSnapshot> chooser = pool.submit(() -> timed("6 文件选择框", "C", "click Upload",
                () -> click(session, "C", "button", "Upload")));
        Future<PageSnapshot> bDuring = pool.submit(() -> timed("6 文件选择框", "B", "snapshot",
                () -> session.snapshot("B")));
        String cModal;
        try {
            cModal = chooser.get().modalState();
        } catch (Exception e) {
            // 0.0.83：文件选择框开着时 browser_snapshot 本身报错（点击之后的那次快照），错误里说明了选择框
            cModal = rootMessage(e);
        }
        PageSnapshot bSnap = bDuring.get();
        check("6 文件选择框", "C", "C 看到文件选择框", cModal.contains("File chooser") || cModal.contains("文件选择框"), cModal);
        check("6 文件选择框", "B", "B 没有串到", bSnap.modalState().isEmpty() && bSnap.title().equals("B"), bSnap.modalState());

        session.open(base + "/form?n=D", "D");
        Future<PageSnapshot> alert = pool.submit(() -> timed("6 对话框", "D", "click Alert",
                () -> click(session, "D", "button", "Alert")));
        Future<PageSnapshot> bDuringAlert = pool.submit(() -> timed("6 对话框", "B", "click Go",
                () -> click(session, "B", "button", "Go")));
        String dModal;
        try {
            dModal = alert.get(40, TimeUnit.SECONDS).modalState();
        } catch (Exception e) {
            dModal = rootMessage(e);
        }
        PageSnapshot bAlert = bDuringAlert.get(40, TimeUnit.SECONDS);
        check("6 对话框", "D", "D 看到对话框", dModal.contains("dialog") || dModal.contains("modal state"), dModal);
        check("6 对话框", "B", "B 没有串到", bAlert.modalState().isEmpty() && bAlert.title().equals("B"), bAlert.modalState());

        // 对话框开着的页会挡住所有会话的标签页列表（见第 9 步），关掉 D 的页（关页就关了对话框）
        closeTarget(targetId("n=D"));
        Thread.sleep(500);

        // 7. 一个 Worker 抛异常：B 在动作中途抛出，A 不受影响
        Future<Object> thrower = pool.submit(() -> {
            session.snapshot("B");
            throw new IllegalStateException("B 的 Worker 抛异常");
        });
        Future<PageSnapshot> aGoes = pool.submit(() -> timed("7 抛异常", "E", "open + click",
                () -> {
                    session.open(base + "/form?n=E", "E");
                    return click(session, "E", "button", "Go");
                }));
        try {
            thrower.get();
        } catch (Exception expected) {
            // 预期
        }
        PageSnapshot eClicked = aGoes.get();
        check("7 抛异常", "E", "别的 Worker 继续", eClicked.snapshot().contains("clicked 1"), eClicked.url());

        // 5. B 的标签页被关掉：B 得到「页不见了」，E 继续
        closeTarget(targetId("n=B"));
        Thread.sleep(500);
        long s5 = now();
        try {
            PageSnapshot gone = session.snapshot("B");
            rows.add(new Row("5 关页", "B", "snapshot", s5, now(), false, "没有报页不见了：" + gone.url()));
        } catch (BrowserSession.TabLost lost) {
            rows.add(new Row("5 关页", "B", "snapshot", s5, now(), true, "TabLost：" + lost.getMessage()));
        }
        PageSnapshot eAfter = timed("5 关页", "E", "click Go", () -> click(session, "E", "button", "Go"));
        check("5 关页", "E", "E 继续", eAfter.title().equals("E"), eAfter.url());

        // 4. 一个 Worker 结束、另一个开始：不再弹允许（共享连接保持），CDP 连接数不变
        PageSnapshot f = timed("4 新 Worker", "F", "open", () -> session.open(base + "/form?n=F", "F"));
        int afterNew = cdpConnections();
        check("4 新 Worker", "F", "新使用者开页", f.title().equals("F"), f.url());
        check("4 连接数", "-", "CDP 连接数：连上 / 多个使用者 / 新使用者",
                afterConnect == 1 && afterOwners == 1 && afterNew == 1,
                afterConnect + " / " + afterOwners + " / " + afterNew);
        check("4 连接数", "-", "连接代数", session.state().generation() == 1, "generation=" + session.state().generation());

        // 9. 同一网站：A 已经开着同站弹窗，A 的原页上再弹对话框（C011 第 5.1 节的卡住）
        session.select("A", 0);
        Future<PageSnapshot> sameSite = pool.submit(() -> timed("9 同站对话框", "A", "click Alert（同站弹窗开着）",
                () -> click(session, "A", "button", "Alert")));
        Future<PageSnapshot> bSameSite = pool.submit(() -> timed("9 同站对话框", "E", "snapshot",
                () -> session.snapshot("E")));
        // 兜底试验：8 秒后用 Chrome 的 HTTP 接口把 A 的页切到前台（不建 websocket），看卡住的点击会不会回来
        Thread.sleep(8000);
        activate(targetId("n=A-step2"));
        check("9 同站对话框", "-", "8 秒后把 A 的页切到前台", true, "activate n=A-step2");
        try {
            PageSnapshot a9 = sameSite.get(40, TimeUnit.SECONDS);
            check("9 同站对话框", "A", "记录", true, "modal=" + a9.modalState());
        } catch (Exception e) {
            check("9 同站对话框", "A", "记录", true, "异常：" + rootMessage(e));
        }
        try {
            PageSnapshot b9 = bSameSite.get(40, TimeUnit.SECONDS);
            check("9 同站对话框", "E", "E 没有卡住", b9.title().equals("E"), b9.url());
        } catch (Exception e) {
            check("9 同站对话框", "E", "E 没有卡住", false, rootMessage(e));
        }
        check("9 同站对话框", "-", "之后的连接状态", true, session.state().state() + " generation="
                + session.state().generation() + " reason=" + session.state().reason());
        try {
            PageSnapshot f9 = session.snapshot("F");
            check("9 同站对话框", "F", "之后 F 还能用", f9.title().equals("F"), f9.url());
        } catch (Exception e) {
            check("9 同站对话框", "F", "之后 F 还能用", false, rootMessage(e));
        }

    }

    // ---- 工具 ----

    private interface Act {
        PageSnapshot run(String owner) throws Exception;
    }

    private interface Expect {
        boolean ok(String owner, PageSnapshot page);
    }

    /** A、B 同时做同一件事（栅栏一起开始），各自记时间，再记一行「时间区间重叠」。 */
    private void both(String scenario, BrowserSession session, ExecutorService pool, String action, Act act,
                      Expect expect) throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        List<Future<long[]>> futures = new ArrayList<>();
        for (String owner : List.of("A", "B")) {
            futures.add(pool.submit(() -> {
                start.await();
                long s = now();
                PageSnapshot page;
                try {
                    page = act.run(owner);
                } catch (Exception e) {
                    rows.add(new Row(scenario, owner, action, s, now(), false, rootMessage(e)));
                    return new long[] {s, now()};
                }
                long end = now();
                rows.add(new Row(scenario, owner, action, s, end, expect.ok(owner, page), page.title() + " " + page.url()));
                return new long[] {s, end};
            }));
        }
        long[] a = futures.get(0).get(60, TimeUnit.SECONDS);
        long[] b = futures.get(1).get(60, TimeUnit.SECONDS);
        rows.add(new Row(scenario, "A+B", action + "：时间区间重叠", Math.max(a[0], b[0]), Math.min(a[1], b[1]),
                Math.max(a[0], b[0]) < Math.min(a[1], b[1]), "A " + a[0] + "–" + a[1] + "，B " + b[0] + "–" + b[1]));
    }

    private PageSnapshot timed(String scenario, String owner, String action, Callable<PageSnapshot> call) {
        long s = now();
        try {
            PageSnapshot page = call.call();
            rows.add(new Row(scenario, owner, action, s, now(), true, page.title() + " " + page.url()));
            return page;
        } catch (Exception e) {
            rows.add(new Row(scenario, owner, action, s, now(), false, rootMessage(e)));
            throw new IllegalStateException(e);
        }
    }

    private void check(String scenario, String owner, String what, boolean ok, String detail) {
        rows.add(new Row(scenario, owner, what, now(), now(), ok, detail));
    }

    private PageSnapshot click(BrowserSession session, String owner, String role, String name) {
        PageSnapshot page = session.snapshot(owner);
        return session.act(owner, BrowserToolPolicy.CLICK, Map.of("target", ref(page, role, name), "element", name),
                BrowserToolPolicy.Context.model(SnapshotIndex.of(page), List.of(base), null));
    }

    private BrowserToolPolicy.Context model(String owner, BrowserSession session) {
        return BrowserToolPolicy.Context.model(SnapshotIndex.of(session.snapshot(owner)), List.of(base), null);
    }

    private static String ref(PageSnapshot page, String role, String name) {
        Matcher m = Pattern.compile(role + " \"" + Pattern.quote(name) + "\"[^\\n]*\\[ref=([a-z0-9]+)]").matcher(page.snapshot());
        if (!m.find()) {
            throw new IllegalStateException("快照里没有 " + role + " \"" + name + "\"：" + page.snapshot());
        }
        return m.group(1);
    }

    private static String other(String owner) {
        return "A".equals(owner) ? "B" : "A";
    }

    private long now() {
        return System.currentTimeMillis() - t0;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    /** 本机夹具页：标题是 n，带输入框、按钮、弹窗、重定向、对话框、上传。全部虚构。 */
    private static HttpServer fixtureServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/form", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String n = query == null ? "T" : query.replaceFirst("^n=", "");
            String html = """
                    <!doctype html><html><head><title>%1$s</title></head><body>
                    <h1>page %1$s</h1>
                    <input aria-label="Name">
                    <button onclick="window.k=(window.k||0)+1;document.getElementById('c').textContent='clicked '+window.k">Go</button>
                    <span id="c">none</span>
                    <button onclick="window.open('/form?n=%1$s-pop')">Popup</button>
                    <a href="/form?n=%1$s-blank" target="_blank">Blank link</a>
                    <button onclick="setTimeout(()=>location.href='/form?n=%1$s-step2',200)">Redirect</button>
                    <button onclick="alert('hi %1$s')">Alert</button>
                    <button onclick="document.getElementById('f').click()">Upload</button>
                    <input type="file" id="f" aria-label="Resume" style="display:none">
                    <script>window.raf=0;(function r(){window.raf++;requestAnimationFrame(r)})();</script>
                    </body></html>""".formatted(n);
            byte[] body = html.getBytes(UTF_8);
            exchange.getResponseHeaders().set("content-type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private void waitForChrome() throws Exception {
        for (int i = 0; i < 100; i++) {
            try {
                HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + cdpPort + "/json/version")).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    return;
                }
            } catch (IOException e) {
                // 还没起来
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Chrome 没起来");
    }

    /** 「用户」自己开一页（Chrome 的 HTTP 接口，不建 websocket）。返回 target id。 */
    @SuppressWarnings("unchecked")
    private String openUserTab(String url) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + cdpPort
                + "/json/new?" + url)).PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        return String.valueOf(JSON.readValue(r.body(), Map.class).get("id"));
    }

    private void closeTarget(String target) throws Exception {
        HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + cdpPort + "/json/close/" + target)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void activate(String target) throws Exception {
        HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + cdpPort + "/json/activate/" + target)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private String targetId(String urlPart) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + cdpPort + "/json/list"))
                .build(), HttpResponse.BodyHandlers.ofString());
        for (Object item : JSON.readValue(r.body(), List.class)) {
            Map<String, Object> target = (Map<String, Object>) item;
            if ("page".equals(target.get("type")) && String.valueOf(target.get("url")).endsWith(urlPart)) {
                return String.valueOf(target.get("id"));
            }
        }
        throw new IllegalStateException("没有网址以 " + urlPart + " 结尾的页");
    }

    /** 到 Chrome 调试端口的 CDP 连接数（node 进程那一侧的 ESTABLISHED 连接）。 */
    private int cdpConnections() throws Exception {
        Process lsof = new ProcessBuilder("lsof", "-nP", "-iTCP:" + cdpPort, "-sTCP:ESTABLISHED").start();
        String out = new String(lsof.getInputStream().readAllBytes(), UTF_8);
        lsof.waitFor();
        return (int) out.lines().filter(line -> line.startsWith("node")).count();
    }

    private String lsof() throws Exception {
        Process lsof = new ProcessBuilder("lsof", "-nP", "-iTCP:" + cdpPort, "-sTCP:ESTABLISHED").start();
        String out = new String(lsof.getInputStream().readAllBytes(), UTF_8);
        lsof.waitFor();
        return String.join(" ; ", out.lines().filter(line -> line.startsWith("node"))
                .map(line -> line.replaceAll("\\s+", " ")).toList());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static String which(String command) {
        for (String dir : System.getenv("PATH").split(":")) {
            Path candidate = Path.of(dir, command);
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return command;
    }
}
