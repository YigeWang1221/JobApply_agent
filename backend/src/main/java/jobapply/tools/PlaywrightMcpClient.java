package jobapply.tools;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import jobapply.harness.policies.BrowserToolPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * 一个到 Playwright MCP 服务进程的 MCP 会话（C011 方案 A）。服务进程（{@link Server}）全应用一个，用
 * {@code --port <随机> --host 127.0.0.1 --shared-browser-context} 启动：所有会话共用它的一条 CDP 连接（Chrome 只弹一次
 * 允许）和同一个默认浏览器上下文（登录状态共享），每个会话有自己的「当前标签页」。会话走 Streamable HTTP
 * （{@link HttpClientStreamableHttpTransport}），一个使用者一个会话，不同使用者的调用可以同时进行。
 * <p>
 * 每次调用先过 {@link BrowserToolPolicy}，被拒绝的调用不会发出。服务进程用 {@code env -i PATH=… HOME=…} 启动，只留
 * PATH 和 HOME。日志只记工具名和耗时，不记返回内容（快照里可能有个人信息）。
 */
public final class PlaywrightMcpClient implements AutoCloseable {

    static final List<String> REQUIRED_TOOLS = List.of(
            BrowserToolPolicy.NAVIGATE, BrowserToolPolicy.SNAPSHOT, BrowserToolPolicy.TABS, BrowserToolPolicy.CLICK,
            BrowserToolPolicy.TYPE, BrowserToolPolicy.FILL_FORM, BrowserToolPolicy.SELECT_OPTION,
            BrowserToolPolicy.FILE_UPLOAD, BrowserToolPolicy.PRESS_KEY, BrowserToolPolicy.WAIT_FOR,
            BrowserToolPolicy.FIND);

    /**
     * 连接失败或连接已断开时，@playwright/mcp 返回的错误里会出现的片段（0.0.83 源码）。
     * {@code initializeServer}：远程调试模式下连不上 Chrome（「Could not connect to chrome」，或用户 30 秒内没有同意时的
     * 「Timeout 30000ms exceeded」），2026-10-23 冒烟时观察到。
     */
    private static final List<String> CONNECTION_ERRORS = List.of(
            "did not connect within", "extension connection", "has been closed", "browser has disconnected",
            "target closed", "not connected", "could not connect to chrome", "initializeserver", "econnrefused");

    private static final Logger log = LoggerFactory.getLogger(PlaywrightMcpClient.class);

    /**
     * MCP 的 {@code browser_run_code_unsafe}：不在白名单里，只由系统用固定模板代发（{@link #setInputFiles}、
     * {@link #diagnose}、{@link #clickThrough}、{@link #inspect}、选下拉和设日期的 {@link PageScripts}），模板里只嵌
     * ref、路径和值的 JSON 字符串。
     */
    static final String RUN_CODE = "browser_run_code_unsafe";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * 把文件直接设到上传位置的文件输入框上（Playwright {@code setInputFiles}：不点击、不等文件选择框，隐藏的
     * {@code input[type=file]} 和被盖住的按钮也行）。从 ref 指的元素往外找：它自己、它里面、label 关联的、
     * 再往上一层层找最近的那个文件输入框。只有 ref 和路径两个参数，都按 JSON 字符串嵌进去。
     */
    private static final String SET_INPUT_FILES = """
            async (page) => {
              const input = await page.locator(%s).evaluateHandle(el => {
                for (let n = el; n; n = n.parentElement) {
                  if (n.matches('input[type=file]')) return n;
                  if (n.tagName === 'LABEL' && n.control && n.control.type === 'file') return n.control;
                  const inside = n.querySelector('input[type=file]');
                  if (inside) return inside;
                }
                return null;
              });
              const element = input.asElement();
              if (!element) throw new Error('这个上传位置附近没有文件输入框（input type=file）');
              await element.setInputFiles(%s);
              return 'files set on the file input';
            }""";

    /**
     * 评测模式的提交拦截（C025 第 5 节）：在这个会话当前那页（申请自己的标签页）上挂一次 {@code page.route}，之后每次调用
     * 只取走拦下的记录。非 GET 请求的网址路径或请求体符合提交特征（正则，忽略大小写）时不发出（{@code abort}），记下方法、
     * 路径、时间和请求体的字段名（JSON 的键、表单的名字，不留值）；其余请求原样放行。拦截记在 Page 对象上，只管这一页，
     * 用户别的标签页不受影响。参数只有特征的 JSON 字符串。
     */
    private static final String INTERCEPT_SUBMITS = """
            async (page) => {
              if (!page.__jobapplyEval) {
                page.__jobapplyEval = [];
                const pattern = new RegExp(%s, 'i');
                const names = (body) => {
                  const out = new Set();
                  const walk = (value, prefix) => {
                    if (out.size >= 200 || value === null || typeof value !== 'object') return;
                    for (const [key, inner] of Object.entries(value)) {
                      const name = Array.isArray(value) ? prefix : (prefix ? prefix + '.' + key : key);
                      if (inner !== null && typeof inner === 'object') walk(inner, name);
                      else if (name) out.add(name);
                    }
                  };
                  try { walk(JSON.parse(body), ''); return [...out]; } catch (e) {}
                  for (const m of body.matchAll(/name="([^"]+)"/g)) out.add(m[1]);
                  if (out.size) return [...out];
                  try { for (const key of new URLSearchParams(body).keys()) out.add(key); } catch (e) {}
                  return [...out].slice(0, 200);
                };
                await page.route('**/*', async (route) => {
                  const request = route.request();
                  const method = request.method();
                  if (method === 'GET' || method === 'HEAD' || method === 'OPTIONS') return route.fallback();
                  let body = '';
                  try { body = request.postData() || ''; } catch (e) {}
                  let path = request.url();
                  try { path = new URL(request.url()).pathname; } catch (e) {}
                  if (!pattern.test(path) && !pattern.test(body)) return route.fallback();
                  page.__jobapplyEval.push({method, path, time: new Date().toISOString(), fields: names(body)});
                  return route.abort('blockedbyclient');
                });
              }
              return {submits: page.__jobapplyEval.splice(0)};
            }""";

    /**
     * 目标可见、只是被别的层盖住时（{@link Diagnosis#coveredButVisible()}），系统在同一个 ref 上直接派发一次点击：
     * 按真实点击的顺序派发 pointerdown / mousedown / pointerup / mouseup，再调 {@code element.click()}。事件直接发给目标，
     * 不经过屏幕坐标，所以盖在上面的层收不到。只在点击守卫已经放行这个 ref 之后、由 {@link BrowserSession#act} 调用。
     */
    private static final String CLICK_THROUGH = """
            async (page) => page.locator(%s).evaluate(el => {
              el.scrollIntoView({block: 'center', inline: 'center'});
              const r = el.getBoundingClientRect();
              const at = {bubbles: true, cancelable: true, composed: true, view: window, button: 0,
                clientX: r.left + r.width / 2, clientY: r.top + r.height / 2};
              const pointer = {...at, pointerId: 1, pointerType: 'mouse', isPrimary: true};
              el.dispatchEvent(new PointerEvent('pointerdown', pointer));
              el.dispatchEvent(new MouseEvent('mousedown', at));
              el.dispatchEvent(new PointerEvent('pointerup', pointer));
              el.dispatchEvent(new MouseEvent('mouseup', at));
              el.click();
              return 'clicked the element directly';
            })""";

    /**
     * 焦点模拟（C011 第 5.3 节）：Chrome 冻结后台标签页（{@code requestAnimationFrame} 不跑），Playwright 点击前的
     * 「元素稳定」检查靠它，所以后台页上的点击必然超时。对这个会话的当前页打开 CDP 的
     * {@code Emulation.setFocusEmulationEnabled}，页面就一直算「可见、有焦点」，窗口最小化了也一样。
     * <p>
     * 只开在正在用的那页上（C011-fix 2026-10-24）：开着的页 Chrome 不节流、不冻结、照常渲染，开得多了整台 Mac 都卡。
     * 每页最多一个 CDP 会话，存在这个会话的 page 对象上，再开时复用（原来每次新建一个、从不断开）；会话已经断了时重开一次。
     * 失败只返回一句话，不抛出（页面关掉时的报错会被认成连接断开）。关掉用 {@link #FOCUS_OFF}。
     */
    private static final String FOCUS_ON = """
            async (page) => {
              for (let attempt = 0; attempt < 2; attempt++) {
                try {
                  page.__jobapplyFocus = page.__jobapplyFocus || await page.context().newCDPSession(page);
                  await page.__jobapplyFocus.send('Emulation.setFocusEmulationEnabled', {enabled: true});
                  return 'focus emulation on';
                } catch (e) {
                  page.__jobapplyFocus = undefined;
                }
              }
              return 'focus emulation unavailable';
            }""";

    /**
     * 关掉 {@link #FOCUS_ON} 开的焦点模拟并断开那个 CDP 会话（Chrome 在会话断开时也会撤销）。page 对象是每个 MCP 会话
     * 自己的，所以只会关掉这个使用者自己开的，碰不到别人的页。没开过时什么都不做。
     */
    private static final String FOCUS_OFF = """
            async (page) => {
              const session = page.__jobapplyFocus;
              page.__jobapplyFocus = undefined;
              if (!session) return 'focus emulation was off';
              await session.send('Emulation.setFocusEmulationEnabled', {enabled: false}).catch(() => {});
              await session.detach().catch(() => {});
              return 'focus emulation off';
            }""";

    /** 当前页打开的弹窗（{@code page.opener()} 是当前页的那些页）的网址：新出现的标签页是不是这个会话点出来的。 */
    private static final String POPUPS = """
            async (page) => {
              const opened = [];
              for (const p of page.context().pages()) {
                if (p !== page && await p.opener() === page) opened.push(p.url());
              }
              return opened;
            }""";

    private final McpSyncClient client;
    /** 只有 {@link #start} 建的单独客户端才带着自己的服务进程，关客户端时一起关。 */
    private final Server owned;
    /** 这个会话的请求超时，写进「卡住了」的说明里。 */
    private final Duration timeout;

    private PlaywrightMcpClient(McpSyncClient client, Server owned, Duration timeout) {
        this.client = client;
        this.owned = owned;
        this.timeout = timeout;
    }

    /**
     * 单独用的客户端：启动一个服务进程，开一个会话。关闭客户端时服务进程一起关。失败时进程已关闭。
     */
    public static PlaywrightMcpClient start(BrowserLaunch launch) {
        Server server = Server.start(launch);
        try {
            return new PlaywrightMcpClient(server.openClient(launch.connectTimeout()), server, launch.connectTimeout());
        } catch (RuntimeException e) {
            server.close();
            throw e;
        }
    }

    /**
     * 一个 Playwright MCP 服务进程（{@code --port --host 127.0.0.1 --shared-browser-context}）。进程第一次被调用工具时才
     * 连 Chrome（这时弹允许提示），之后所有会话共用这一条连接；最后一个会话断开时连接也断开，所以调用方一直留一个空闲
     * 会话（{@link BrowserSession}）。
     */
    public static final class Server implements AutoCloseable {

        private final Process process;
        private final String endpoint;
        private final BrowserLaunch launch;

        private Server(Process process, String endpoint, BrowserLaunch launch) {
            this.process = process;
            this.endpoint = endpoint;
            this.launch = launch;
        }

        /**
         * 选一个空闲端口启动服务进程，等它开始监听。HTTP 端口只听 127.0.0.1。失败时进程已关闭，抛出
         * {@link BrowserException.Disconnected}。
         */
        public static Server start(BrowserLaunch launch) {
            int port = freePort();
            List<String> command = new ArrayList<>(List.of("/usr/bin/env", "-i", "PATH=" + launch.path(),
                    "HOME=" + launch.home()));
            boolean preload = launch.preload() != null && Files.isRegularFile(launch.preload());
            if (preload) {
                // 睡着、卡住的标签页不再拖住连接和每次调用（C011-fix 2026-10-25）；npx 和它起的 node 都会加载，只认 coreBundle
                command.add("NODE_OPTIONS=--require \"" + launch.preload() + "\"");
            } else if (launch.preload() != null) {
                log.warn("浏览器 MCP 的 preload 不在了（{}），这次不用它启动", launch.preload());
            }
            command.add(launch.command());
            command.addAll(launch.args());
            command.addAll(List.of("--port", String.valueOf(port), "--host", "127.0.0.1"));
            if (!launch.args().contains("--shared-browser-context")) {
                command.add("--shared-browser-context");
            }
            log.info("启动浏览器 MCP：{} {} --port {} --host 127.0.0.1 --shared-browser-context{}", launch.command(),
                    String.join(" ", launch.args()), port, preload ? "（preload " + launch.preload() + "）" : "");
            Process process;
            try {
                process = new ProcessBuilder(command).redirectErrorStream(true).start();
            } catch (IOException e) {
                throw new BrowserException.Disconnected("浏览器 MCP 启动失败：" + e.getMessage(), e);
            }
            Thread.ofVirtual().name("browser-mcp-output").start(() -> drain(process));
            Server server = new Server(process, "http://localhost:" + port + "/mcp", launch);
            long deadline = System.nanoTime() + launch.connectTimeout().toNanos();
            while (!listening(port)) {
                if (!process.isAlive() || System.nanoTime() > deadline) {
                    server.close();
                    throw new BrowserException.Disconnected("浏览器 MCP 启动失败：" + (process.isAlive()
                            ? launch.connectTimeout().toSeconds() + " 秒内没有开始监听端口" : "进程退出了"));
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    server.close();
                    throw new BrowserException.Disconnected("浏览器 MCP 启动被中断", e);
                }
            }
            return server;
        }

        /** 进程还活着。 */
        public boolean alive() {
            return process.isAlive();
        }

        /** 开一个新的会话（自己的 MCP 客户端、自己的当前标签页），用普通调用的超时。失败时抛出 {@link BrowserException}。 */
        public PlaywrightMcpClient open() {
            return new PlaywrightMcpClient(openClient(launch.callTimeout()), null, launch.callTimeout());
        }

        /** 开保活会话：用建立连接的超时，它的第一次调用要连 Chrome、等用户点允许。 */
        public PlaywrightMcpClient openKeeper() {
            return new PlaywrightMcpClient(openClient(launch.connectTimeout()), null, launch.connectTimeout());
        }

        /**
         * 完成 MCP 初始化（声明 {@link BrowserLaunch#roots()}，Playwright MCP 把第一个当 workspace），检查必需的工具都在。
         * 用 {@code localhost}：Playwright MCP 只认 {@code Host: localhost:<端口>}（0.0.83 {@code installHttpTransport}）。
         */
        private McpSyncClient openClient(Duration timeout) {
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(endpoint)
                    .clientBuilder(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                            .connectTimeout(Duration.ofSeconds(5)))
                    .jsonMapper(McpJsonDefaults.getMapper())
                    .build();
            McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(timeout)
                    .initializationTimeout(launch.connectTimeout())
                    .clientInfo(new McpSchema.Implementation("jobapply", "0.0.1"))
                    .roots(launch.roots().stream()
                            .map(root -> new McpSchema.Root(root.toUri().toString(), root.getFileName().toString()))
                            .toList())
                    .build();
            try {
                client.initialize();
                Set<String> tools = client.listTools().tools().stream()
                        .map(McpSchema.Tool::name)
                        .collect(Collectors.toSet());
                List<String> missing = REQUIRED_TOOLS.stream().filter(name -> !tools.contains(name)).toList();
                if (!missing.isEmpty()) {
                    throw new BrowserException.ToolFailed("浏览器 MCP 缺少工具：" + String.join(", ", missing));
                }
            } catch (BrowserException.ToolFailed e) {
                closeQuietly(client);
                throw e;
            } catch (RuntimeException e) {
                closeQuietly(client);
                throw new BrowserException.Disconnected("浏览器 MCP 会话建立失败：" + e.getMessage(), e);
            }
            return client;
        }

        /** 关掉进程和它的子进程（{@code npx} 会再起一个 node）。 */
        @Override
        public void close() {
            List<ProcessHandle> children = process.descendants().toList();
            process.destroy();
            children.forEach(ProcessHandle::destroy);
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        }

        private static void drain(Process process) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    if (line.startsWith("[jobapply]")) {
                        // preload 自己的话：补丁打没打上、连接时跳过了几个没回应的标签页
                        log.info("浏览器 MCP：{}", line);
                    } else {
                        log.debug("浏览器 MCP 输出：{}", line);
                    }
                }
            } catch (IOException e) {
                // 进程关了
            }
        }

        private static int freePort() {
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                return socket.getLocalPort();
            } catch (IOException e) {
                throw new BrowserException.Disconnected("找不到空闲端口：" + e.getMessage(), e);
            }
        }

        private static boolean listening(int port) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 200);
                return true;
            } catch (IOException e) {
                return false;
            }
        }
    }

    /** 对这个会话的当前页打开焦点模拟（{@link #FOCUS_ON}），系统代发，模型不能调用。 */
    void focusOn() {
        send(RUN_CODE, Map.of("code", FOCUS_ON), "焦点模拟");
    }

    /** 关掉这个会话在当前页上开的焦点模拟（{@link #FOCUS_OFF}），系统代发。 */
    void focusOff() {
        send(RUN_CODE, Map.of("code", FOCUS_OFF), "关焦点模拟");
    }

    /** 当前页打开的弹窗的网址（{@link #POPUPS}），系统代发。 */
    List<String> popups() {
        String text = send(RUN_CODE, Map.of("code", POPUPS), "弹窗检查");
        Object parsed;
        try {
            parsed = JSON.readValue(result(text), Object.class);
        } catch (RuntimeException e) {
            throw new BrowserException.ToolFailed("弹窗检查的结果读不出来：" + e.getMessage());
        }
        return parsed instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    /** MCP 返回的 {@code ### Result} 小节（模板的返回值）。 */
    static String result(String text) {
        int at = text.indexOf("### Result");
        if (at < 0) {
            throw new BrowserException.ToolFailed("系统检查没有返回结果");
        }
        String body = text.substring(at + "### Result".length()).strip();
        int next = body.indexOf("\n###");
        return next < 0 ? body : body.substring(0, next);
    }

    /**
     * 系统自己的调用，返回文本内容。不在白名单内时抛出 {@link jobapply.harness.policies.ToolNotAllowedException}。
     */
    public String call(String tool, Map<String, Object> args) {
        return call(tool, args, BrowserToolPolicy.Context.system());
    }

    /**
     * 按给定的上下文（系统或模型）检查白名单，再调用。点击和填写的守卫由调用方先判断，这里只管白名单。
     */
    public String call(String tool, Map<String, Object> args, BrowserToolPolicy.Context context) {
        Map<String, Object> arguments = args == null ? Map.of() : args;
        BrowserToolPolicy.check(tool, arguments, context);
        if (BrowserToolPolicy.FILE_UPLOAD.equals(tool) && arguments.get("target") instanceof String ref) {
            return send(RUN_CODE, setInputFiles(ref, arguments.get("paths")), tool);
        }
        if (BrowserToolPolicy.SELECT_OPTION.equals(tool)) {
            return select(arguments);
        }
        if (BrowserToolPolicy.SET_DATE.equals(tool)) {
            return setDate(arguments);
        }
        return send(tool, arguments, tool);
    }

    /**
     * 选下拉（C016 B）：过了白名单之后跑 {@link PageScripts#SELECT}。原生 select 由脚本找到选项原文，再交给 MCP 的
     * {@code browser_select_option}；别的下拉由脚本点完。返回给模型的一句话（不带值）；有歧义、没找到、没打开列表时抛出
     * {@link BrowserException.ToolFailed}，说明里是这一层能看到的选项原文。
     */
    /** 选下拉读回对得上时那句话（C019：申请状态把这个字段记成「已核对」，见 {@link #readBackMatched}）。 */
    static final String SELECT_MATCHED = "控件现在显示的值和选中的项对得上。";
    static final String NATIVE_SELECTED = "已在原生下拉框里选中（系统按选项原文选的）。";
    static final String DATE_MATCHED = "，读回的值对得上。";

    /** 选下拉、设日期的那句话（{@link BrowserSession#takeNotice}）说读回对得上（原生下拉按选项原文选的也算）。 */
    public static boolean readBackMatched(String notice) {
        return notice != null && (notice.contains(SELECT_MATCHED) || notice.contains(NATIVE_SELECTED)
                || notice.contains(DATE_MATCHED));
    }

    private String select(Map<String, Object> args) {
        String ref = String.valueOf(args.get("target"));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("target", "aria-ref=" + ref);
        input.put("values", args.get("values"));
        input.put("search", Boolean.TRUE.equals(args.get("search")));
        Map<String, Object> done = json(send(RUN_CODE, Map.of("code", PageScripts.code(PageScripts.SELECT, input)),
                BrowserToolPolicy.SELECT_OPTION), BrowserToolPolicy.SELECT_OPTION);
        String status = String.valueOf(done.get("status"));
        int levels = done.get("levels") instanceof Number n ? n.intValue() : 0;
        String verified = Boolean.TRUE.equals(done.get("verified")) ? SELECT_MATCHED
                : "控件显示的值和选中的项对不上，看新快照核对。";
        return switch (status) {
            case "native" -> {
                Map<String, Object> native_ = new LinkedHashMap<>();
                native_.put("target", ref);
                if (args.get("element") != null) {
                    native_.put("element", args.get("element"));
                }
                native_.put("values", done.get("values"));
                send(BrowserToolPolicy.SELECT_OPTION, native_, BrowserToolPolicy.SELECT_OPTION);
                yield NATIVE_SELECTED;
            }
            case "selected" -> "已选中（点了 " + levels + " 层）。" + verified
                    + (Boolean.TRUE.equals(done.get("closed")) ? "" : "弹出列表还开着。");
            case "closed_early" -> "点完第 " + levels + " 层列表就关了（这个控件可能只有 " + levels + " 层，或者选中的是上一级）。"
                    + verified;
            case "need_more_levels" -> throw new BrowserException.ToolFailed(choices(done));
            case "no_popup" -> throw new BrowserException.ToolFailed(BrowserToolPolicy.SELECT_OPTION + "：点开之后没有出现选项列表"
                    + "（能输入的下拉已经输入了第一个值，仍没有结果）。这个元素可能不是下拉框的触发元素，看新快照找对的那个；"
                    + "远程搜索没有结果时换一种写法再试，或者问用户。");
            default -> throw new BrowserException.ToolFailed(choices(done));
        };
    }

    /** 没有选：第几层、为什么、这一层能看到的选项原文（快照的写法，之后照快照一样过脱敏）。 */
    static String choices(Map<String, Object> done) {
        if ("need_more_levels".equals(done.get("status"))) {
            int n = done.get("levels") instanceof Number num ? num.intValue() : 0;
            int next = n + 1;
            StringBuilder text = new StringBuilder(BrowserToolPolicy.SELECT_OPTION + "：已依次点中前 " + n
                    + " 层，但级联还展开了第 " + next + " 层，尚未完成选择，弹出列表还开着。");
            List<String> chosen = strings(done.get("chosen"));
            if (!chosen.isEmpty()) {
                text.append("已经点中：")
                        .append(String.join(" → ", chosen.stream().map(c -> "「" + c + "」").toList()))
                        .append("。");
            }
            List<String> options = strings(done.get("options"));
            if (options.isEmpty()) {
                text.append("第 ").append(next).append(" 层没有看到选项。");
            } else {
                text.append("第 ").append(next).append(" 层现在能看到的选项（原文）：\n- listbox ")
                        .append(JSON.writeValueAsString(String.valueOf(done.getOrDefault("name", "")))).append(":");
                options.forEach(option -> text.append("\n  - option ").append(JSON.writeValueAsString(option)));
                if (Boolean.TRUE.equals(done.get("more"))) {
                    text.append("\n（还有更多没列出）");
                }
            }
            return text.append("\n请补上下一层的值再调用 browser_select_option，或直接在新快照里点击该项；"
                    + "若资料里没有这一层（如具体区县）且必选，用 ask_user 问用户。").toString();
        }
        int level = done.get("level") instanceof Number n ? n.intValue() + 1 : 1;
        StringBuilder text = new StringBuilder(BrowserToolPolicy.SELECT_OPTION + "：没有选，第 " + level + " 层");
        if ("ambiguous".equals(done.get("status"))) {
            text.append("有几项都对得上（").append(String.join("、", strings(done.get("candidates")).stream()
                    .map(c -> "「" + c + "」").toList())).append("），系统没有点。");
        } else {
            text.append("没有找到要选的项，系统没有点。");
        }
        List<String> options = strings(done.get("options"));
        if (options.isEmpty()) {
            text.append("这一层没有看到选项。");
        } else {
            text.append("这一层现在能看到的选项（原文）：\n- listbox ").append(JSON.writeValueAsString(
                    String.valueOf(done.getOrDefault("name", "")))).append(":");
            options.forEach(option -> text.append("\n  - option ").append(JSON.writeValueAsString(option)));
            if (Boolean.TRUE.equals(done.get("more"))) {
                text.append("\n（还有更多没列出：写得更完整再选，或者带 search: true 先输入过滤）");
            }
        }
        if (Boolean.FALSE.equals(done.get("closed"))) {
            text.append("\n弹出列表还开着。");
        }
        return text.append("\n从里面挑原文再调用 browser_select_option（级联每层写一个原文）；没有语义一致的项就用 ask_user 问用户，"
                + "不要硬选相近的。").toString();
    }

    /**
     * 设日期（C016 E）：过了白名单之后跑 {@link PageScripts#DATE}。返回给模型的一句话；没设上时抛出
     * {@link BrowserException.ToolFailed}，说明里是面板上看到的内容。
     */
    private String setDate(Map<String, Object> args) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("target", "aria-ref=" + args.get("target"));
        input.put("date", args.get("date"));
        Map<String, Object> done = json(send(RUN_CODE, Map.of("code", PageScripts.code(PageScripts.DATE, input)),
                BrowserToolPolicy.SET_DATE), BrowserToolPolicy.SET_DATE);
        String tool = BrowserToolPolicy.SET_DATE;
        String how = switch (String.valueOf(done.get("how"))) {
            case "native" -> "原生日期框，直接设了值";
            case "typed" -> "按输入框占位符的格式键入后按了 Tab";
            case "wheel" -> "在滚轮面板上按列选好";
            default -> "在日历面板上点选";
        };
        String read = Boolean.TRUE.equals(done.get("verified")) ? DATE_MATCHED : "，读回的值对不上，看新快照核对。";
        return switch (String.valueOf(done.get("status"))) {
            case "set" -> "日期已设好（" + how + "）" + read;
            case "needs_confirm" -> "已在面板上点了日期，但面板上还有「确定」要点才生效" + read
                    + "在新快照里找到面板上的「确定」，用 browser_click 点它（purpose 写 confirm，final_step_suspected 写 false），"
                    + "再看新快照核对。";
            case "open" -> "已在面板上点了日期，面板还开着" + read + "范围选择要再给结束日期的框设一次。";
            case "closed" -> "面板关了" + read;
            // 不算填好（报告里不记）：说清楚缺的是日，让模型去问用户（C016 实跑后修正）
            case "need_day" -> throw new BrowserException.ToolFailed(tool + "：need_day，没有设。这个日期框要精确到日，"
                    + "你给的只有年月（或只有年）。"
                    + ("panel".equals(done.get("how")) ? "面板翻到过你给的那一年那一月，没有点任何日期，"
                    + (Boolean.TRUE.equals(done.get("closed")) ? "之后按 Escape 关上了。" : "面板还开着。")
                    : "wheel".equals(done.get("how")) ? "滚轮已选到年、月，没有选日，"
                    + (Boolean.TRUE.equals(done.get("closed")) ? "之后按 Escape 关上了。" : "面板还开着。")
                    : "日期框没有动。")
                    + "不要自己编日：用 ask_user 问用户这一项是哪一天（这一页有几个这样的日期就一起问），用户回答后用 "
                    + tool + " 写完整的 YYYY-MM-DD 再设一次。");
            case "need_month" -> throw new BrowserException.ToolFailed(tool + "：这是原生年月框，date 要写到月（YYYY-MM）");
            case "disabled" -> throw new BrowserException.ToolFailed(tool + "：这个日期框是禁用的，现在不能填");
            case "no_panel" -> throw new BrowserException.ToolFailed(tool + "：点开之后没有出现日历面板"
                    + (Boolean.TRUE.equals(done.get("typedTried")) ? "，键入的值也没有生效" : "")
                    + "。这个元素可能不是日期框本身，看新快照找对的那个；还不行就手动点开面板或问用户。");
            default -> throw new BrowserException.ToolFailed(panel(done));
        };
    }

    /** 没设上：面板上看到的头部和格子。 */
    private static String panel(Map<String, Object> done) {
        StringBuilder text = new StringBuilder(BrowserToolPolicy.SET_DATE + "：在日历面板上没找到要的日期，系统停在这里，没有再点。");
        if (done.get("panel") instanceof Map<?, ?> panel) {
            text.append("面板现在是").append(switch (String.valueOf(panel.get("kind"))) {
                case "day" -> "日期表";
                case "month" -> "月份表";
                case "year" -> "年份表";
                case "decade" -> "年代表";
                case "columns" -> "滚轮面板";
                default -> "认不出的样子";
            }).append("；头部：").append(String.join(" ", strings(panel.get("header"))))
                    .append("；格子：").append(String.join(" ", strings(panel.get("cells")))).append("。");
        }
        return text.append("可以照面板自己点（点头部的年份 / 月份切换），或者用 ask_user 请用户选。").toString();
    }

    /**
     * 填写之前的只读检查（C016 A）：这些 ref 指的元素的标签、type、readonly、disabled、是不是原生 select、能不能键入。
     * 查不到的 ref 不在结果里。系统代发，模型不能调用；ref 都是这次调用里、白名单之前的。
     */
    Map<String, Map<String, Object>> inspect(List<String> refs) {
        Map<String, Object> input = Map.of("targets", refs.stream().map(ref -> "aria-ref=" + ref).toList());
        Map<String, Object> found = json(send(RUN_CODE, Map.of("code", PageScripts.code(PageScripts.INSPECT, input)),
                "填写前检查"), "填写前检查");
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        found.forEach((key, value) -> {
            if (value instanceof Map<?, ?> info) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) info;
                out.put(key.substring("aria-ref=".length()), typed);
            }
        });
        return out;
    }

    /**
     * {@link #inspect} 的一项说明这个字段键入 / 填写不会生效时的原因（交给模型），会生效时为 null。
     *
     * @param type {@code browser_fill_form} 字段的 type；{@code browser_type} 写 textbox
     */
    static String cannotFill(Map<String, Object> info, String type) {
        if (info == null || !("textbox".equals(type) || "combobox".equals(type))) {
            return null;
        }
        if (Boolean.TRUE.equals(info.get("disabled"))) {
            return "是禁用的（disabled），现在不能填";
        }
        boolean nativeSelect = Boolean.TRUE.equals(info.get("nativeSelect"));
        if ("combobox".equals(type)) {
            return nativeSelect ? null : "不是原生下拉框（select），fill_form 选不了：用 browser_select_option";
        }
        if (nativeSelect) {
            return "是原生下拉框（select）：用 browser_select_option";
        }
        if (!Boolean.TRUE.equals(info.get("fillable"))) {
            String role = info.get("role") instanceof String r && !r.isEmpty() ? " role=" + r : "";
            return "不是输入框（<" + info.get("tag") + role + ">），键入不会生效：下拉用 browser_select_option，日期用 "
                    + "browser_set_date，别的先点开看看";
        }
        if (Boolean.TRUE.equals(info.get("readonly"))) {
            return "是只读的显示框（readonly，日期和下拉控件常这样），键入不会生效：日期用 browser_set_date，下拉用 "
                    + "browser_select_option，或者点它打开面板";
        }
        return null;
    }

    /** 模板返回的 JSON 对象（{@code ### Result} 小节）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String text, String shown) {
        try {
            Object parsed = JSON.readValue(result(text), Object.class);
            return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        } catch (RuntimeException e) {
            throw new BrowserException.ToolFailed(shown + " 的结果读不出来：" + e.getMessage());
        }
    }

    private static List<String> strings(Object value) {
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    /** 带 target 的上传：过了白名单（ref 在最新快照里、路径都在上传目录里）之后，换成固定模板的代码。 */
    static Map<String, Object> setInputFiles(String ref, Object paths) {
        String code = SET_INPUT_FILES.formatted(JSON.writeValueAsString("aria-ref=" + ref), JSON.writeValueAsString(paths));
        return Map.of("code", code);
    }

    /** 系统的检查（{@link PageScripts#DIAGNOSE}），不过白名单，模型不能调用。{@code ref} 已经由刚才那次调用的白名单查过。 */
    Diagnosis diagnose(String ref) {
        return Diagnosis.parse(send(RUN_CODE, Map.of("code", PageScripts.code(PageScripts.DIAGNOSE,
                Map.of("target", "aria-ref=" + ref))), "系统检查"));
    }

    /**
     * 评测模式（C025）：在当前页上装好提交拦截（装过就不再装），取走这之后拦下的提交请求。不过白名单，模型不能调用。
     * 每一条是 {@code method}、{@code path}、{@code time}、{@code fields}（字段名）。
     */
    List<Map<String, Object>> interceptSubmits(String pattern) {
        Map<String, Object> found = json(send(RUN_CODE, Map.of("code",
                INTERCEPT_SUBMITS.formatted(JSON.writeValueAsString(pattern))), "评测拦截"), "评测拦截");
        List<Map<String, Object>> out = new ArrayList<>();
        if (found.get("submits") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> typed = new LinkedHashMap<>();
                    map.forEach((key, value) -> typed.put(String.valueOf(key), value));
                    out.add(typed);
                }
            }
        }
        return out;
    }

    /** 系统直接点击（{@link #CLICK_THROUGH}），不过白名单，模型不能调用。调用方先确认点击守卫放行了这个 ref。 */
    String clickThrough(String ref) {
        return send(RUN_CODE, code(CLICK_THROUGH, ref), BrowserToolPolicy.CLICK);
    }

    private static Map<String, Object> code(String template, String ref) {
        return Map.of("code", template.formatted(JSON.writeValueAsString("aria-ref=" + ref)));
    }

    /** MCP 的动作超时错误（{@code TimeoutError: … Timeout 15000ms exceeded.}）。 */
    static boolean isActionTimeout(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return lower.contains("timeout") && lower.contains("exceeded");
    }

    /**
     * {@link PageScripts#DIAGNOSE} 的结果。{@link #describe()} 是给模型的话：元素可不可见、在不在屏幕里、被谁盖住、是不是
     * 只读 / 禁用 / 原生 select、还有哪些弹出层开着（C016 A：弹出层只算里面真有选项或是对话框的浮层）。
     */
    record Diagnosis(boolean visible, boolean inViewport, boolean covered, Map<String, Object> target,
                     Map<String, Object> cover, Map<String, Object> layer, List<Map<String, Object>> popups) {

        /** MCP 返回的 {@code ### Result} 小节里是模板返回值的 JSON。 */
        @SuppressWarnings("unchecked")
        static Diagnosis parse(String text) {
            Map<String, Object> m;
            try {
                m = JSON.readValue(result(text), Map.class);
            } catch (RuntimeException e) {
                throw new BrowserException.ToolFailed("系统检查的结果读不出来：" + e.getMessage());
            }
            return new Diagnosis(Boolean.TRUE.equals(m.get("visible")), Boolean.TRUE.equals(m.get("inViewport")),
                    Boolean.TRUE.equals(m.get("covered")), map(m.get("target")), map(m.get("cover")),
                    map(m.get("layer")),
                    m.get("popups") instanceof List<?> list ? list.stream().map(Diagnosis::map).toList() : List.of());
        }

        /** 目标看得见、在屏幕里，只是中心那一点被别的元素盖住：可以直接点它。 */
        boolean coveredButVisible() {
            return visible && inViewport && covered;
        }

        String describe() {
            StringBuilder text = new StringBuilder("系统检查了这个元素（已把它滚到屏幕中间）：");
            boolean kind = Boolean.TRUE.equals(target.get("disabled")) || Boolean.TRUE.equals(target.get("nativeSelect"))
                    || Boolean.TRUE.equals(target.get("readonly"));
            if (Boolean.TRUE.equals(target.get("disabled"))) {
                text.append("它是禁用的（disabled），现在不能操作。");
            } else if (Boolean.TRUE.equals(target.get("nativeSelect"))) {
                text.append("它是原生下拉框（select）：用 browser_select_option，值写选项原文。");
            } else if (Boolean.TRUE.equals(target.get("readonly"))) {
                text.append("它是只读的输入框（readonly，日期和下拉控件常这样）：键入不会生效，日期用 browser_set_date，"
                        + "下拉用 browser_select_option，或者点它打开面板。");
            }
            if (!visible) {
                text.append("它现在不可见（大小为 0 或被隐藏），可能在折叠的区域里、还没渲染出来，或者快照里的是另一个同名元素。");
            } else if (!inViewport) {
                text.append("它可见，但滚过之后中心仍不在屏幕里。");
            } else if (covered) {
                text.append("它可见，但中心那一点最上层是另一个元素 ").append(element(cover));
                if (!layer.isEmpty()) {
                    text.append("，它在一个 position: ").append(layer.get("position")).append(" 的层里 ")
                            .append(element(layer)).append("（固定在屏幕上的栏或浮层）");
                }
                text.append("。");
            } else if (!kind) {
                text.append("它可见、在屏幕里，现在没有被别的元素盖住；刚才可能在动画或被临时的加载层挡住，可以再试一次。");
            }
            if (!popups.isEmpty()) {
                text.append("页面上开着这些弹出层（里面有选项，或者是对话框）：")
                        .append(String.join("；", popups.stream().map(Diagnosis::element).toList()))
                        .append("。要选里面的项就直接点那一项；要关掉它按一次 Escape 或点一下空白处，不要反复按。");
            }
            return text.toString();
        }

        private static String element(Map<String, Object> node) {
            StringBuilder text = new StringBuilder("<").append(node.getOrDefault("tag", "?"));
            if (node.get("role") instanceof String role && !role.isEmpty()) {
                text.append(" role=").append(role);
            }
            if (node.get("cls") instanceof String cls && !cls.isEmpty()) {
                text.append(" class=\"").append(cls).append('"');
            }
            text.append('>');
            if (node.get("text") instanceof String content && !content.isEmpty()) {
                text.append("「").append(content).append("」");
            }
            return text.toString();
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> map(Object value) {
            return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        }
    }

    /**
     * 发给 MCP；{@code shown} 是模型看到的工具名，写进错误里。请求超时（MCP 没回话）是「卡住了」
     * （{@link BrowserException.Stalled}），不是断开：连接还在，只是有页面不答（C011-fix）。
     */
    private String send(String tool, Map<String, Object> arguments, String shown) {
        long started = System.nanoTime();
        McpSchema.CallToolResult result;
        try {
            result = client.callTool(new McpSchema.CallToolRequest(tool, arguments));
        } catch (McpError e) {
            throw new BrowserException.ToolFailed(shown + " 调用失败：" + e.getMessage());
        } catch (RuntimeException e) {
            if (timedOut(e)) {
                // 别的标签页睡着、卡住不再拖住调用（preload），这里没回话的是这次操作的那一页
                throw new BrowserException.Stalled(shown + " 调用 " + timeout.toSeconds() + " 秒没有回应：正在用的这一页"
                        + "没有响应（卡死转圈，或者挂着要点的对话框）。连接没有断", e);
            }
            throw new BrowserException.Disconnected(shown + " 调用失败，浏览器连接不可用：" + e.getMessage(), e);
        } finally {
            log.info("浏览器工具 {}，{} ms", tool, (System.nanoTime() - started) / 1_000_000);
        }
        String text = text(result);
        if (Boolean.TRUE.equals(result.isError())) {
            if (isConnectionError(text)) {
                log.info("浏览器连接不可用（{}）：{}", tool, firstLine(text));
                throw new BrowserException.Disconnected(connectionProblem(text));
            }
            throw new BrowserException.ToolFailed(shown + " 返回错误：" + firstLine(text) + hint(shown, text));
        }
        return text;
    }

    @Override
    public void close() {
        closeQuietly(client);
        if (owned != null) {
            owned.close();
        }
    }

    /**
     * MCP 的错误本身不说原因（超时没有调用日志），模型会自己编「浏览器安全限制」。这里按 0.0.83 的错误文本补一句实情。
     */
    static String hint(String tool, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("does not handle the modal state") && lower.contains("file chooser")) {
            return "。说明：页面上有一个已经打开的文件选择框，别的操作都要等它处理完：用 browser_file_upload（不带 target）"
                    + "把文件交给它，或者不带 files 取消它";
        }
        if (BrowserToolPolicy.FILE_UPLOAD.equals(tool)
                && (lower.contains("related modal state") || lower.contains("no file chooser"))) {
            return "。说明：现在没有打开的文件选择框。用 browser_file_upload 带 target（上传按钮或上传区域的 ref），"
                    + "系统直接把文件设到它的文件输入框上，不需要点击";
        }
        if (lower.contains("timeout") && lower.contains("exceeded")) {
            return "。说明：这是动作超时，元素在等待时间内一直不能操作（被加载遮罩或别的层盖住、还在动画、不可见），"
                    + "这次操作没有发生，不是浏览器的安全限制。等页面加载完（browser_wait_for）再取快照重试，或者换页面上"
                    + "可见的那个元素；上传按钮点不动时用 browser_file_upload 带 target 直接上传";
        }
        return "";
    }

    /** MCP SDK 的请求超时（{@code TimeoutException: Did not observe any item … within …ms}）在异常链上。 */
    static boolean timedOut(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    /**
     * 连不上、断开时给用户和模型的话，按 0.0.83 的错误文本说实情；认不出时用原文第一行。{@code initializeServer} 的超时是
     * MCP 的 {@code --cdp-timeout}（默认 30 秒）：睡着的标签页已经不拖住连接（preload），剩下的多半是 Chrome 在等用户允许。
     */
    static String connectionProblem(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("initializeserver") && lower.contains("timeout")) {
            return "30 秒内没有连上 Chrome：Chrome 可能在等你允许这次远程调试连接（看看 Chrome 有没有弹出提示，点允许），"
                    + "也可能 Chrome 本身没有响应";
        }
        if (lower.contains("econnrefused")) {
            return "连不上 Chrome（127.0.0.1:9222 没有在监听）：Chrome 没有开着，或者没打开远程调试（chrome://inspect/#remote-debugging 里的开关）";
        }
        if (lower.contains("could not connect to chrome")) {
            return "连不上 Chrome：Chrome 没有开着，或者没打开远程调试（chrome://inspect/#remote-debugging 里的开关）";
        }
        return "浏览器连接不可用：" + firstLine(text);
    }

    static boolean isConnectionError(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return CONNECTION_ERRORS.stream().anyMatch(lower::contains);
    }

    private static String text(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(content -> ((McpSchema.TextContent) content).text())
                .collect(Collectors.joining("\n"));
    }

    private static String firstLine(String text) {
        return text.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("###"))
                .findFirst()
                .orElse("（无说明）");
    }

    /**
     * 不等服务端确认（{@code closeGracefully} 在服务进程已经退出时要等满 10 秒）。服务端那边的会话靠 Playwright MCP 的
     * 心跳（每 3 秒 ping 一次，5 秒不答就关）自己清掉；服务进程一关，所有会话都没了。
     */
    private static void closeQuietly(McpSyncClient client) {
        try {
            client.close();
        } catch (RuntimeException e) {
            log.warn("关闭浏览器 MCP 时出错：{}", e.getMessage());
        }
    }
}
