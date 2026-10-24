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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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
            "target closed", "not connected", "could not connect to chrome", "initializeserver");

    private static final Logger log = LoggerFactory.getLogger(PlaywrightMcpClient.class);

    /**
     * MCP 的 {@code browser_run_code_unsafe}：不在白名单里，只由系统用固定模板代发（{@link #setInputFiles}、
     * {@link #diagnose}、{@link #clickThrough}），模板里只嵌 ref 和路径的 JSON 字符串。
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
     * 动作超时之后的检查（C006-fix-real-run 第 20 条）：MCP 的超时错误不带 Playwright 的调用日志，看不出是被盖住、
     * 不可见还是弹出层没关。只读：把元素滚到屏幕中间（{@code scrollIntoView}，不等可操作性），量它的位置，用
     * {@code elementFromPoint} 看中心那一点最上层是谁；被盖住时报盖住它的元素和它所在的 fixed / sticky 层；
     * 再列出页面上还开着的弹出层（下拉、级联、菜单、对话框）。不点击、不输入。
     */
    private static final String DIAGNOSE = """
            async (page) => page.locator(%s).evaluate(el => {
              const brief = n => n && {
                tag: n.tagName.toLowerCase(),
                role: n.getAttribute('role') || '',
                text: (n.innerText || n.value || n.getAttribute('aria-label') || n.getAttribute('placeholder') || '')
                  .replace(/\\s+/g, ' ').trim().slice(0, 60),
                cls: String(typeof n.className === 'string' ? n.className : '').trim().slice(0, 80),
                position: getComputedStyle(n).position
              };
              const shown = n => {
                const b = n.getBoundingClientRect(), s = getComputedStyle(n);
                return b.width > 0 && b.height > 0 && s.visibility !== 'hidden' && s.display !== 'none';
              };
              const layerOf = n => {
                for (; n && n !== document.body; n = n.parentElement) {
                  const p = getComputedStyle(n).position;
                  if (p === 'fixed' || p === 'sticky') return n;
                }
                return null;
              };
              el.scrollIntoView({block: 'center', inline: 'center'});
              const r = el.getBoundingClientRect();
              const visible = shown(el);
              const x = r.left + r.width / 2, y = r.top + r.height / 2;
              const inViewport = visible && x >= 0 && y >= 0 && x < innerWidth && y < innerHeight;
              const top = inViewport ? document.elementFromPoint(x, y) : null;
              const covered = !!top && top !== el && !el.contains(top);
              const popups = [];
              document.querySelectorAll('[role=listbox],[role=menu],[role=tree],[role=dialog],[role=alertdialog],'
                  + '[class*=popover],[class*=dropdown],[class*=cascader],[class*=picker],[class*=popup]').forEach(n => {
                const p = getComputedStyle(n).position;
                if (popups.length < 3 && shown(n) && !n.contains(el) && (p === 'fixed' || p === 'absolute')
                    && !popups.some(o => o.contains(n))) popups.push(n);
              });
              return {visible, inViewport, covered, target: brief(el), cover: covered ? brief(top) : null,
                layer: covered ? brief(layerOf(top)) : null, popups: popups.map(brief)};
            })""";

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
     * {@code Emulation.setFocusEmulationEnabled}，页面就一直算「可见、有焦点」，窗口最小化了也一样。按页生效：
     * 新开的页、选过去的弹窗、转交的页都要各开一次。CDP 会话留在 Playwright 的连接上，设置一直有效。
     */
    private static final String FOCUS_EMULATION = """
            async (page) => {
              const session = await page.context().newCDPSession(page);
              await session.send('Emulation.setFocusEmulationEnabled', {enabled: true});
              return 'focus emulation on';
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

    /**
     * 把当前页切到前台（C011 第 5.1 节的兜底）：同站点的几个页面时，后台页上弹出的对话框可能一直不出来、把点击卡住；
     * 动作超时之后切一次前台，对话框才会出来。
     */
    private static final String BRING_TO_FRONT = """
            async (page) => {
              await page.bringToFront();
              return 'brought to front';
            }""";

    private final McpSyncClient client;
    /** 只有 {@link #start} 建的单独客户端才带着自己的服务进程，关客户端时一起关。 */
    private final Server owned;

    private PlaywrightMcpClient(McpSyncClient client, Server owned) {
        this.client = client;
        this.owned = owned;
    }

    /**
     * 单独用的客户端：启动一个服务进程，开一个会话。关闭客户端时服务进程一起关。失败时进程已关闭。
     */
    public static PlaywrightMcpClient start(BrowserLaunch launch) {
        Server server = Server.start(launch);
        try {
            return new PlaywrightMcpClient(server.openClient(), server);
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
                    "HOME=" + launch.home(), launch.command()));
            command.addAll(launch.args());
            command.addAll(List.of("--port", String.valueOf(port), "--host", "127.0.0.1"));
            if (!launch.args().contains("--shared-browser-context")) {
                command.add("--shared-browser-context");
            }
            log.info("启动浏览器 MCP：{} {} --port {} --host 127.0.0.1 --shared-browser-context", launch.command(),
                    String.join(" ", launch.args()), port);
            Process process;
            try {
                process = new ProcessBuilder(command).redirectErrorStream(true).start();
            } catch (IOException e) {
                throw new BrowserException.Disconnected("浏览器 MCP 启动失败：" + e.getMessage(), e);
            }
            Thread.ofVirtual().name("browser-mcp-output").start(() -> drain(process));
            Server server = new Server(process, "http://localhost:" + port + "/mcp", launch);
            long deadline = System.nanoTime() + launch.callTimeout().toNanos();
            while (!listening(port)) {
                if (!process.isAlive() || System.nanoTime() > deadline) {
                    server.close();
                    throw new BrowserException.Disconnected("浏览器 MCP 启动失败：" + (process.isAlive()
                            ? launch.callTimeout().toSeconds() + " 秒内没有开始监听端口" : "进程退出了"));
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

        /** 开一个新的会话（自己的 MCP 客户端、自己的当前标签页）。失败时抛出 {@link BrowserException}。 */
        public PlaywrightMcpClient open() {
            return new PlaywrightMcpClient(openClient(), null);
        }

        /**
         * 完成 MCP 初始化（声明 {@link BrowserLaunch#roots()}，Playwright MCP 把第一个当 workspace），检查必需的工具都在。
         * 用 {@code localhost}：Playwright MCP 只认 {@code Host: localhost:<端口>}（0.0.83 {@code installHttpTransport}）。
         */
        private McpSyncClient openClient() {
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(endpoint)
                    .clientBuilder(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                            .connectTimeout(Duration.ofSeconds(5)))
                    .jsonMapper(McpJsonDefaults.getMapper())
                    .build();
            McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(launch.callTimeout())
                    .initializationTimeout(launch.callTimeout())
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
                    log.debug("浏览器 MCP 输出：{}", line);
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

    /** 对这个会话的当前页打开焦点模拟（{@link #FOCUS_EMULATION}），系统代发，模型不能调用。 */
    void focusEmulation() {
        send(RUN_CODE, Map.of("code", FOCUS_EMULATION), "焦点模拟");
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

    /** 把当前页切到前台（{@link #BRING_TO_FRONT}），系统代发。 */
    void bringToFront() {
        send(RUN_CODE, Map.of("code", BRING_TO_FRONT), "切到前台");
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
        return send(tool, arguments, tool);
    }

    /** 带 target 的上传：过了白名单（ref 在最新快照里、路径都在上传目录里）之后，换成固定模板的代码。 */
    static Map<String, Object> setInputFiles(String ref, Object paths) {
        String code = SET_INPUT_FILES.formatted(JSON.writeValueAsString("aria-ref=" + ref), JSON.writeValueAsString(paths));
        return Map.of("code", code);
    }

    /** 系统的检查（{@link #DIAGNOSE}），不过白名单，模型不能调用。{@code ref} 已经由刚才那次调用的白名单查过。 */
    Diagnosis diagnose(String ref) {
        return Diagnosis.parse(send(RUN_CODE, code(DIAGNOSE, ref), "系统检查"));
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
     * {@link #DIAGNOSE} 的结果。{@link #describe()} 是给模型的话：元素可不可见、在不在屏幕里、被谁盖住、还有哪些弹出层开着。
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
            } else {
                text.append("它可见、在屏幕里，现在没有被别的元素盖住；刚才可能在动画或被临时的加载层挡住，可以再试一次。");
            }
            if (!popups.isEmpty()) {
                text.append("页面上还开着这些弹出层：")
                        .append(String.join("；", popups.stream().map(Diagnosis::element).toList()))
                        .append("。先按 Escape 或点一下空白处把它关掉，再重试。");
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

    /** 发给 MCP；{@code shown} 是模型看到的工具名，写进错误里。 */
    private String send(String tool, Map<String, Object> arguments, String shown) {
        long started = System.nanoTime();
        McpSchema.CallToolResult result;
        try {
            result = client.callTool(new McpSchema.CallToolRequest(tool, arguments));
        } catch (McpError e) {
            throw new BrowserException.ToolFailed(shown + " 调用失败：" + e.getMessage());
        } catch (RuntimeException e) {
            throw new BrowserException.Disconnected(shown + " 调用失败，浏览器连接不可用：" + e.getMessage(), e);
        } finally {
            log.info("浏览器工具 {}，{} ms", tool, (System.nanoTime() - started) / 1_000_000);
        }
        String text = text(result);
        if (Boolean.TRUE.equals(result.isError())) {
            if (isConnectionError(text)) {
                throw new BrowserException.Disconnected(shown + "：浏览器连接不可用：" + firstLine(text));
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
