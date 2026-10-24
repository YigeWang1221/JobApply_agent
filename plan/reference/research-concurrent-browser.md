# 参考：同一个 Chrome 里多个申请并发（多标签页）

> **参考，不是施工步骤。** 问题：几个申请 worker 能不能同时跑，每个在同一个 Chrome 里用自己的标签页，共享登录状态，而且每次工具调用只落在自己的标签页上，不落在全局的「当前标签页」上。
> 日期：2026-10-24。来源：本机 `@playwright/mcp@0.0.83` 源码（`~/.npm/_npx/381cec31605a419a/node_modules/`，核心在 `playwright-core/lib/coreBundle.js`，版本 `1.64.0-alpha-1790635538000`，没有压缩，下面的行号指这个文件）；在会话临时目录里做的实验（自己启动的 Chrome for Testing（`ms-playwright/chromium-1243`），临时 user-data-dir，`--remote-debugging-port=9333`，只打开本机 127.0.0.1 的测试页；**没有连用户的 Chrome，也没有碰 9222**）；Chrome / chrome-devtools-mcp 的公开文档和 issue。
> 标记：**【实测】** 跑过实验；**【源码】** 读源码得出；**【文档】** 来自官方文档或维护者的回复；**【推断】** 没有直接证据。

## 结论先行

1. **MCP 0.0.83 的一个会话里只有一个「当前标签页」**，工具都不收标签页参数。服务端**不排队**，同一会话的并发调用会交错执行，「select 再操作」会落到别人的页上。【源码 + 实测】
2. **MCP 已经支持「一个进程、多个会话、一条 CDP 连接」**：`--port <p> --shared-browser-context --cdp-endpoint chrome`。每个 HTTP 会话有自己的 Context，也就有自己的当前标签页；所有会话共用一条到 Chrome 的连接，用同一个默认浏览器上下文，所以 cookie 和登录是共享的。【源码 + 实测：3 个会话只有 1 条 CDP 连接；会话 1 设的 cookie 会话 2 读得到】
3. **真正卡住并发的是 Chrome 对后台标签页的节流**，不是 MCP：后台标签页 `visibilityState=hidden`，`requestAnimationFrame` 一次都不跑，Playwright 点击前的「元素稳定」检查靠 rAF，所以**后台标签页里的点击一定超时**（fill 和 setInputFiles 不受影响）。headless 和 headful 都一样。【实测】
4. **解决办法是 CDP 的 `Emulation.setFocusEmulationEnabled({enabled:true})`**：对某个标签页打开后，它变成 `visible`，rAF 和定时器都恢复，点击正常，窗口最小化了也一样。Playwright 本来会对每个页打开这个设置，**唯独在 `connectOverCDP(..., {noDefaults:true})` 的默认上下文里跳过**，而 MCP 的 CDP 模式正好是这种情况（`coreBundle.js:38891-38895`）。现在的 MCP 里可以用 `browser_run_code_unsafe` 的固定模板对每个 worker 的页打开它。【源码 + 实测】
5. **Chrome 授权提示是按 WebSocket 连接弹的**，没有「记住」：每条新的 CDP 连接都要人点一次「允许」，同时开几条就叠几个提示。复用一条已经批准的连接，就不会再弹。【文档：chrome-devtools-mcp 维护者的回复；在用户的 Chrome 上没有实测】所以方案 B（每个 worker 一个 MCP 进程）有 N 个进程就有 N 次提示，方案 A（一个 `--port` 服务端、多个会话）只有一次。
6. **建议：方案 A（用现成的 MCP 能力），不写自己的 CDP 边车。** 一个常驻的 `@playwright/mcp --port … --shared-browser-context --cdp-endpoint chrome`；每个 worker 一个 MCP HTTP 会话（Java SDK 2.0.0 里有 `HttpClientStreamableHttpTransport`）；worker 用 `browser_tabs new` 开自己的页以后不再 `select`；开页后立刻对它打开焦点模拟。

---

## 1. Playwright MCP 0.0.83 内部

### 1.1 全局「当前标签页」【源码】

- `Context` 类（`coreBundle.js:66784` 起）用一个字段 `_currentTab` 记当前页。`currentTab()`、`currentTabOrDie()`（66828-66835）、`newTab()` 把新页设成当前页（66836-66841）、`selectTab(index)` **先 `page.bringToFront()`** 再设成当前页（66842-66850）、`ensureTab()` 没有当前页时新开一个（66851-66865）。
- `_onPageCreated`（66950 前后）：上下文里**任何**新页（包括别的客户端开的页和弹窗）都会包成 `Tab` 加进 `_tabs`；只有在当前没有标签页时才会把它设成当前页。`_onPageClosed`：关掉的是当前页时，换成它旁边的那一页。
- 每个 `Context` 在 `_initializeBrowserContext`（67036-67047）里把上下文已有的所有页都收进来，再订阅 `page` 事件。所以**一个 Context 能看到整个浏览器上下文里的所有标签页**。

### 1.2 工具不收标签页参数【源码】

- `defineTabTool`（65608-65622）：handler 里第一件事就是 `const tab = await context.ensureTab()`，即当前页。snapshot、click、type、fill_form、file_upload、run_code_unsafe 等都是 `defineTabTool`。
- 只有 `browser_tabs` 收 `index`（69373-69410，`list/new/close/select`）。没有工具收 pageId 或 tabId。
- `browser_run_code_unsafe`（69192-69240）在 vm 里运行代码，传进去的 `page` 是 `tab.page`，也就是当前页。
- aria ref：`browser_snapshot` 调的是公开 API `page.ariaSnapshot({mode:"ai"})`（66601-66625），点击时用 `page.locator("aria-ref=e5")` 找元素（66681-66700）。ref 是注入脚本**按页、按 frame**生成的（iframe 前缀 `f<seq>`）。【实测】两个页的快照都从 `e2/e3/…` 编号，互相不冲突，按 ref 只能在自己的页上解析。

### 1.3 一个会话里的并发：不排队，会交错（问题 1a）

- 【源码】`createServer` 的 `CallToolRequestSchema` handler（73890-73930）直接 `await backend.callTool(...)`；`BrowserBackend.callTool`（67546-67597）没有锁和队列，只有一个 `setRunningTool(name)` 字段，并发时会被覆盖。MCP SDK 收到请求就分派，不等上一个完成。
- 【实测】一个 stdio 会话里同时发两个各等 1.5 秒的 `browser_run_code_unsafe`，总共 2.0 秒，说明是并行的。
- 【实测】两个 worker 交错发「select 0、select 1、snapshot、snapshot」：worker 0 想看 T0，**拿到的是 T1**。这就是现在 Java 那边要加全局锁、每次操作前先 `select` 的原因。
- 【实测】一个会话里点开弹窗（`window.open`）：当前页**不变**，弹窗只出现在标签页列表里；但弹窗到了前台，原来的页变成 hidden，之后在原页上的点击超时（见 §5）。

### 1.4 多个独立会话（问题 1b）

两种做法：(i) 多个 MCP 进程（方案 B）；(ii) 一个 `--port` 服务端里多个 HTTP 会话。

- 【源码】`--port` 时每个 HTTP 会话（`handleStreamable`，73790 前后）都会 `connect(factory, …)`，每个会话有自己的 MCP Server、自己的 `BrowserBackend` 和 `Context`，也就有**自己的 `_currentTab`**。
- 【源码】`factory.create`（75580-75650）：
  - 有 `--shared-browser-context`（或 isolated）时，只在第一次 `createBrowserWithInfo` → `createCDPBrowser` → `chromium.connectOverCDP` **连一次**，之后 `browser.bind()` 在本进程开一个 Playwright 协议的 pipe 服务（54010-54050），其它会话用 `browserType().connect(endpoint)` 连这个 pipe。所有会话共用同一条 CDP WebSocket，`browser.contexts()[0]` 是同一个默认上下文，所以 cookie 是同一份。
  - **没有 `--shared-browser-context` 时，每个 HTTP 会话都会再 `connectOverCDP` 一次**，也就是一条新的 Chrome 连接、一次新的授权提示。
  - 最后一个会话断开时（`clientCount` 归零），共享连接也关掉；下一个会话进来时重新连接（又一次提示）。共享模式下 `browser_close` 直接报错（67621）。
- 【实测，连接数】用 ws:// 地址连（和 `chrome` 通道一样，不先发 HTTP 请求）：3 个 stdio 进程 = 3 条 CDP 连接；1 个 `--port --shared-browser-context` 服务端带 3 个会话 = **1 条**。内存：每个 stdio 进程约 126 MB；一个服务端带 3 个会话约 143 MB。
- 【实测】两种做法下，每个会话 `browser_tabs new` 以后当前页都留在自己的页上；并发 snapshot 各拿到自己的页；W1 设的 cookie W2 能读到。

**标签页归属和事件泄漏**（两种做法结果一样）【实测 + 源码】：

| 项 | 结果 |
| --- | --- |
| 别的会话开的新页 / 弹窗 | 会出现在我的 `_tabs` 里，但**不会变成我的当前页**（除非我还没有当前页）。【实测】 |
| 「Open tabs」列表 | 每个会话的回复都列出**所有**标签页，包括别的 worker 的标题和网址；只要任何页的标题有变化就会附上（67389-67394）。序号是全局的，别人开页、关页，我这边的序号就跟着变（例如关掉 0 号后 B 的 2 号变成 1 号）。【实测】 |
| console 消息 | 按 Tab 记录，回复里只带当前页的。S1 页上的 `console.error` 没有出现在 S2 的回复和 `browser_console_messages` 里。【实测】 |
| 对话框（alert 等）/ 文件选择框 | 每个会话都给每个页挂了 `dialog`、`filechooser` 监听（66330-66345），模态状态记在**自己那份 Tab 对象**上；回复只报当前页的模态状态。S1 页弹 alert 时 S2 照常 snapshot、点击自己的页，没有看到模态状态；S1 用 `browser_handle_dialog` 关掉。【实测】如果 S2 之后 `select` 到 S1 的页，会看到那份状态。【推断】 |
| 下载 | 每个会话的每个 Tab 都监听 `download` 并存到输出目录（66346、`_downloadStarted`），同一次下载可能被存好几份；只有当前页是那一页的会话会在回复里看到。【源码；没有实测】 |
| 网络请求 | 按 Tab 记录，`browser_network_requests` 只看当前页。【源码】 |
| 作用于整个上下文的工具 | `browser_cookie_*`、`browser_storage_state`、`browser_route`、localStorage 等是整个上下文共用的，一个 worker 清 cookie 会影响所有 worker。【源码】 |

**观察到的一个卡死**【实测，原因未确认】：S1 的页先用 `window.open` 开了一个同站弹窗（弹窗到前台），S1 的页已经打开焦点模拟，再点一个会弹 alert 的按钮：click 20 秒不返回，对话框事件没来；同时**另一个进程的 S2**（同一个站点 127.0.0.1）的 snapshot 也卡住了。没有弹窗时同样的 alert 正常。推断：Chrome 对「不在前台的标签页」的 JS 对话框会延后显示，同站的页可能共用一个渲染进程，被这个对话框一起堵住。真实场景（例如两个申请都在 myworkdayjobs.com 上）可能碰到；兜底办法是模态超时后把那一页 `bringToFront`。

### 1.5 其它选项【源码】

- `--isolated`：每个会话 `browser.newContext()`，cookie 不共享（方案 C）。
- `--endpoint <bound endpoint>`：连到另一个 MCP 进程 `bind()` 出来的 Playwright 服务（`createRemoteBrowser`，75420-75452）。理论上可以让 N 个进程只用一条 CDP 连接，但 endpoint 是进程内的 pipe 路径，要从 `serverRegistry` 里找，第一个进程退出时其它进程一起断。没有实测，不建议。
- `--extension`：走 Playwright 扩展，不是我们现在的连接方式。

## 2. 方案 B：每个 worker 一个 MCP 进程【实测】

- 每个进程各自 `connectOverCDP`，**各一条 CDP 连接**（连接数随进程数 1→2→3 增长）。按 §3 的结论，用户的 Chrome 上每个进程启动或重启都会弹一次授权。
- 可以同时存在；每个进程都看到全部标签页；进程 1 开的新页出现在进程 2 的列表里，不会变成进程 2 的当前页；关页会让另一个进程的序号移位。
- 并发点击：先开的那一页在后台，点击超时（和方案 A 一样，见 §5）。
- 代价：N 个 Node 进程（每个约 126 MB）、N 条连接、N 次提示；Java 现在的 stdio 客户端不用改。

## 3. Chrome 授权提示（我们的连接方式：`--cdp-endpoint chrome`）

- 【源码】`chrome` 是通道名：`resolveChannelEndpoint`（44235-44253）读 Chrome 默认 user-data-dir 下的 `DevToolsActivePort`，拼成 `ws://localhost:<port>/devtools/browser`；`urlToWSEndpoint` 遇到 ws:// 直接返回（44215-44217），所以**一次 `connectOverCDP` 就是一条 WebSocket**，之后所有页都在这条连接上用 flatten session 复用，不会再开新连接。
- 【文档】Chrome 官方博客（M144 的新功能）："every time the Chrome DevTools MCP server requests a remote debugging session, Chrome will show a dialog to the user"；连接期间顶部显示「Chrome 正受到自动测试软件的控制」。
- 【文档】chrome-devtools-mcp issue #1794：维护者 OrKoN「each WebSocket connection to Chrome needs to be approved」，natorion「The prompt appears once per WebSocket connection by design」；多个客户端同时连「multiple dialogs are expected to show up」，每个提示挡住各自的客户端；有人指出「Reusing an established connection completely bypasses subsequent prompts」。issue #825（要求记住授权）被关为 not planned，理由是没有认证时任何本机程序都能读取用户 profile 里的数据。#2188（一台机器一个 HTTP 服务端给多个 Agent 用）还开着。
- 【本仓库已有观察】`application.yml` 的注释和 `PlaywrightMcpClient` 记过：MCP 进程每次重启都要重新点允许，30 秒内不点就 `Timeout 30000ms exceeded`。
- **结论**：按连接弹，不按进程、也不按浏览器会话记住；同时开几条就叠几个。复用一条已批准的连接是唯一不用再点的办法。前一句是【文档】，在用户的 Chrome 上【没有实测】；「一次 connectOverCDP = 一条 WebSocket」是【源码】。超时 30 秒来自本仓库此前的观察，没有找到 Chromium 源码里的出处。

## 4. 方案 A′：自己写 Node 边车（只连一次 CDP，按 pageId 操作）

- 可行：`chromium.connectOverCDP` 一次，每个 worker 持有一个 `Page`；快照用 `page.ariaSnapshot({mode:"ai"})`，点击和填写用 `page.locator("aria-ref=…")`，上传用 `setInputFiles`，焦点模拟用 `context.newCDPSession(page)`。【实测】这些在后台页上都能用（点击需要先开焦点模拟）。
- 可以暴露成同名的 MCP 工具再加一个 `pageId` 参数，Java 改动很小。
- 估计工作量：基本工具（navigate、snapshot、click、type、fill_form、select_option、file_upload、press_key、wait_for、find、tabs、run_code）加 stdio MCP 外壳，约 500–800 行 JS。
- 会丢掉、要自己重写的：动作后等待页面稳定（`waitForCompletion`）、模态状态（对话框、文件选择框）和 `browser_handle_dialog`、回复的格式（Open tabs、Page、Events、console 日志文件）、下载处理、文件路径检查、密钥替换、跟着 MCP 升级的修复。
- 结论：MCP 的 `--port --shared-browser-context` 已经给出「一条连接 + 每个会话一个当前页」，A′ 现在不划算；只有 A 走不通时才考虑。

## 5. Playwright 本身：一条连接多页并发、后台节流【实测】

实验脚本：自己启动 Chrome for Testing（没有加 Playwright 默认的 `--disable-background-timer-throttling` 等参数，和用户自己开的 Chrome 一样），`connectOverCDP(..., {noDefaults:true})`（和 MCP 一样），同一个窗口开两个标签页 p1（后台）、p2（前台）。

| 情况 | p1 可见性 | p1 rAF / 2 秒 | p1 定时器（100 ms） / 2 秒 | p1 点击 |
| --- | --- | --- | --- | --- |
| 什么都不做（headless 和 headful 一样） | hidden | 0 | 2（被压到 1 次/秒） | **超时** |
| `Emulation.setFocusEmulationEnabled` | visible | 106–120 | 20 | 成功（约 75 ms） |
| `Page.setWebLifecycleState active` | hidden | 0 | 2 | 超时 |
| `Page.startScreencast`（单独用） | hidden | 0 | 2 | 超时 |
| `bringToFront` | p1 visible，p2 变成 hidden | 120 | 20 | p1 成功，p2 超时 |
| 每个页单独一个窗口（`Target.createTarget newWindow`） | 都 visible | 120 | – | 两个都成功 |
| headful，窗口最小化：开了焦点模拟的页 / 没开的页 | visible / hidden | 100 / 0 | 20 / 2 | 成功 / 超时 |

- 后台页上：`fill` 成功、`setInputFiles` 成功、`ariaSnapshot` 成功，只有需要「元素稳定」检查的动作（click、check、hover 等）卡住。原因：注入脚本的 `_checkElementIsStable` 用 `requestAnimationFrame` 轮询，隐藏页里 rAF 不跑。
- 打开焦点模拟后，两个页上的点击可以真的同时进行（两个都约 70 ms 完成）；一条连接驱动多个页没有问题。
- Playwright 自己启动浏览器时会加 `--disable-background-timer-throttling`、`--disable-backgrounding-occluded-windows`、`--disable-renderer-backgrounding`（36098-36120），也会对每个页打开焦点模拟；连用户自己开的 Chrome 时这些参数加不上，焦点模拟也被 `noDefaults` 跳过（38891-38895）。所以在现在的 MCP 里要自己补：用 `browser_run_code_unsafe` 跑固定模板 `async (page) => { const s = await page.context().newCDPSession(page); await s.send('Emulation.setFocusEmulationEnabled', {enabled:true}); }`。【实测】在 `--port` 模式下这样做以后，两个会话同时点击，两个都成功（565 ms）；不做时后台那个超时。
- 副作用：被模拟的页 `document.hasFocus()` 为 true，几个页会同时认为自己有焦点；对申请表单影响不大。【推断】
- 弹窗、新开的页也要各开一次（设置是按页的）。【推断，按 CDP 的设计】
- 顺带：这也能解释单 worker 时「打开弹窗后原页点击超时」：弹窗到前台，原页变 hidden（§1.3 的实测）。

## 6. 方案 C：每个 worker 一个 BrowserContext 或 profile

- `browser.newContext()`（MCP 的 `--isolated`）在 Chrome 里是一个类似隐身窗口的独立上下文，cookie 和 storage 不和默认上下文共享，**用户已经登录的状态用不上**；单独的 profile（另一个 user-data-dir）更是另一个浏览器。【源码：`--isolated` 走 `newContext`；cookie 隔离是 Playwright 和 Chrome 的既定语义，本次没有单独实测】
- 还有用的地方：不需要登录的调研和抓取 worker；打开不可信页面时想和用户的账号隔开；某个站点要用另一个账号。

## 7. 参考仓库（只读）

| 仓库 | 提交 | 多浏览器会话的做法 |
| --- | --- | --- |
| Hermes `~/.hermes/hermes-agent` | `e5dad4ac5` | 按 `task_id` 分会话（`tools/browser_tool.py`：`_active_sessions`、`_get_session_info`）。本地模式每个任务启动一个独立的无头 Chromium（`agent-browser --session h_xxx`），相当于方案 C。CDP 模式（`_create_cdp_session`，连用户给的 cdp_url）所有任务连同一个地址，每个任务有自己的 socket 目录和 agent-browser 守护进程，相当于方案 B；另外每个任务一个 `CDPSupervisor`（`tools/browser_supervisor.py`），用一条常驻 WebSocket attach 到**第一个** page target，管对话框和 frame。没有按标签页隔离任务。没有读配置、密钥、memories、日志。 |
| OpenMAIC | `dff201b` | `render-service/src/preview-renderer.ts:539-557`：每次预览渲染 launch 一个无头浏览器、`newPage`，用 `Semaphore` 限并发。一次请求一个浏览器，不共享登录。 |
| deepagents | `3e7d5fa` | 核心库没有浏览器；只有 `libs/talon/.../whatsapp_bridge/bridge.js` 用 puppeteer 跑 WhatsApp Web，单会话。 |
| langgraphjs | `c6675bf` | 没有浏览器自动化。 |
| deep-agents-ui | `f6a4f34` | 没有浏览器自动化。 |
| chrome-devtools-mcp（issue，不是本机仓库） | – | #2188 有人 fork 出「一个 HTTP 服务、一条 Chrome 连接、每个会话自己的选中页」，和本文的方案 A 一样；fork 作者说明标签页没有自动归属，cookie 共享。 |

## 8. 方案对比

| | A：一个 MCP 服务端，多会话（`--port --shared-browser-context`） | A′：自写边车，按 pageId | B：每个 worker 一个 MCP 进程 | C：每个 worker 一个上下文或 profile |
| --- | --- | --- | --- | --- |
| 真并发 | 能（会话之间并行；后台页要开焦点模拟） | 能（同样要焦点模拟） | 能（同样要焦点模拟） | 能 |
| 标签页隔离 | 每个会话自己的当前页【实测】；别人的页看得到，能被 select，要在 Java 的工具规则里拦 | 按 pageId，最干净 | 同 A【实测】 | 天然隔离 |
| 共享登录 | 是【实测】 | 是 | 是 | **否** |
| 授权提示 | 服务端活着且至少有一个会话时只有 1 次 | 1 次 | N 个进程 N 次，每次重启再来 | 新开浏览器不需要；连用户 Chrome 时同 A |
| 资源 | 1 个 Node 进程（3 会话约 143 MB）、1 条连接 | 1 个进程、1 条连接 | N 个进程（每个约 126 MB）、N 条连接 | 每个上下文或浏览器单独占内存 |
| 实现成本 | 低：Java 换成 Streamable HTTP 传输（SDK 2.0.0 已有），每个 worker 一个会话；启动参数加 `--port`；开页后跑焦点模拟模板 | 中：500–800 行 JS，并重写 MCP 已有的等待、模态、下载 | 很低：多起几个 stdio 客户端 | 低，但不满足需求 |
| 风险 | 「Open tabs」把别的 worker 的页暴露给模型，序号会移位；下载可能存多份；整个上下文共用的工具会互相影响；本机 HTTP 端口要只绑 127.0.0.1；最后一个会话断开会断掉共享连接；同站对话框可能卡住（§1.4） | 自己维护；MCP 的修复跟不上 | 提示太多，用户要一直盯着 | 用不上登录状态 |

## 9. 建议和第二阶段的最小验证

**建议方案 A**，成本最低，也最可靠：

- Java 在启动时拉起一个常驻进程 `npx @playwright/mcp@0.0.83 --cdp-endpoint chrome --port <随机端口> --host 127.0.0.1 --shared-browser-context …（其余参数不变）`。再常开一个「保活」会话，让共享连接不会因为 worker 都结束而断开（否则下一个 worker 又要点一次允许）。
- 每个申请 worker 开一个 MCP HTTP 会话（`HttpClientStreamableHttpTransport`）。worker 第一件事是 `browser_tabs new`（之后当前页一直是它），然后跑焦点模拟模板。**不再用全局锁加 `select`**；`select` 会 `bringToFront`，只有当 worker 需要切到自己的弹窗时才用。
- 工具规则：worker 只能 `select` / `close` 自己的页（按 URL 或标题认，不按序号）；弹窗出现时由 worker 自己 `select` 过去，并对它也开焦点模拟。
- 不改 MCP 包；`browser_run_code_unsafe` 仍然只走系统的固定模板。

**第二阶段最小验证（两个 worker）**：

1. 用户在自己的 Chrome 里开着远程调试开关，启动应用：**只弹一次**允许。
2. 同时开始两个申请（最好是两个不同站点的真实表单页，停在提交前）。看到两个新标签页，各自的快照、填写、点击、上传只发生在自己的页上（看页面内容和工具日志里的 URL）。
3. 用户这时在 Chrome 里看第三个标签页或者把窗口最小化：两个 worker 的点击都不超时。
4. 其中一个 worker 打开弹窗或新页：另一个 worker 的当前页不变；打开弹窗的 worker 能切过去继续。
5. 结束一个 worker、再开一个新的：不再弹允许。
6. 记下：允许提示的次数、有没有 click 超时、有没有落到别的页上的操作。
