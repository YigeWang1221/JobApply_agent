# C011 · 多个申请 Worker 并发（共享一个 Chrome、各自的标签页）

- 依据：用户（2026-10-24）「Job Apply 项目：多 Agent 并发浏览器自动化架构」：一个 Main Agent / Scheduler 调度多个独立 Worker，每个 Worker 独占自己的 Chrome 标签页，通过共享的 MCP / CDP 基础设施并发完成多个申请，不发生浏览器操作冲突；尽量复用现有 Chrome、MCP、登录状态和工具；增量改造。
- 用户补充（2026-10-24）：**施工前有一个架构决定检查点**；C011 写完、带证据给出推荐方案、用户批准之后才拉施工代理。优先级：浏览器隔离正确 + 真正并行，保留现有登录，改动最小。
- 状态：**等用户批准架构**（调研完成 2026-10-24，证据见 [research-concurrent-browser.md](../reference/research-concurrent-browser.md)，实验脚本在会话 scratchpad `exp/`）。批准前不施工。
- 不做：真实提交申请（测试里也不做）；为每个任务启动一套独立 Chrome；把系统改成替用户点最终提交（`SUBMITTED` 只由用户确认后记，和现在一样）。

## 1. 现状和目标的差别

| | 现在 | 目标 |
| --- | --- | --- |
| 调度 | `ApplicationRunner` 一个单线程执行器（「浏览器串行队列」），同一时间一个申请在跑，其余 `queued` | Worker 池（初始 3），N 个申请真正同时跑，超出的排队 |
| Agent 循环 | 每次运行新建 `HarnessLoop` / `AgentLoop`，已经是独立实例 | 不变 |
| 浏览器 | 一个 `@playwright/mcp@0.0.83` 进程、一条 CDP 连接；`BrowserSession` 所有公开方法一把锁，每次动作「列标签页 → select 到自己的页 → 动作 → 快照」 | 每个 Worker 的动作只落在自己的标签页，不靠全局「当前标签页」；共享的东西才加锁 |
| 标签页归属 | 内存里按使用者记「序号 + 网址」，新弹出页按全局个数判断 | 每个 Worker 显式绑定自己的页（标识取决于选定方案），弹窗 / 关闭 / 重定向有确定的归属规则 |
| 状态 | `prep_state`（preparing / needs_input / awaiting_review / …）+ `AgentRun.status`（running / queued / waiting_user / ready / failed / interrupted） | 沿用，映射见第 6 节；不另造一套 |

## 2. 串行在哪里（代码证据，审查 2026-10-24，分支 C008）

路径相对 `backend/src/main/java/jobapply/`。

1. **真正的瓶颈：`workflow/ApplicationRunner.java`**
   - :116 `Executors.newSingleThreadExecutor(... "browser-queue")`，类注释 :58–64「同一时间只有一条线程在跑」。
   - :135 `runningJob`（单值）、:136 `current`（单个 Future）；:532 `waitInLine = runningJob != null || !queued.isEmpty()`；`status()` :464–467、`busy()` :782–784、`awaitIdle()` :495–515 都按「一个在跑」写。
   - 已经按 Job 分开的：`live`（:127）、`stopRequested`（:121）、`inbox`（:124）、每个 Job 的文件；`execute()` :636–645 每次新建 `HarnessLoop`。
2. **浏览器层：`tools/BrowserSession.java`**
   - 所有公开方法 `synchronized`（:127–386），类注释 :26–41「全局唯一的浏览器连接持有者」。
   - 每次动作：`focus()`（:394–407）先 `browser_tabs list`、`refresh()` 重算所有人的归属（:422–449），不是当前页就 `browser_tabs select`，再发动作（:178），最后 `read()` 快照（:471）。
   - Playwright MCP 只有一个「当前标签页」；去掉这把锁，A 选了页、B 又选了页，A 的点击会落到 B 的页上。
3. **MCP 客户端：`tools/PlaywrightMcpClient.java`**：一个 `McpSyncClient`（:140），本身没有锁；stdio 传输用单播 sink，两个线程同时发会「Failed to enqueue message」，被当成断开（:317–318）——**同一个客户端上不能并发发请求**（待调研确认）。三个固定模板（`SET_INPUT_FILES` / `DIAGNOSE` / `CLICK_THROUGH`）都在服务端当前页上按 aria-ref 找元素。
4. **已经在并发的**：`SessionRunner` 的 Session 用线程池同时跑（`workflow/SessionRunner.java:80`），调研 Agent 和正在跑的申请已经在同一个 `BrowserSession` 上按次交替使用浏览器。

## 3. 锁的分类（用户要求：不是简单去掉全局锁）

| 锁 | 保护什么 | 结论 |
| --- | --- | --- |
| `BrowserSession` 方法级 `synchronized` | **浏览器归属**：「切到自己的页 → 动作 → 快照」必须原子，因为 MCP 只有一个当前页；还保护标签页记账（`tabs`/`yours`/`notices`/`handed`、`tabCount`） | 单连接方案下**必须保留**（按连接一把，而不是按整个浏览器一把，见第 5 节）；问题是持锁时间长（动作超时最长 15 秒 + 诊断 + 快照），会让别的 Worker 等 |
| `StorageLock.WRITES`（全局写锁，`JobLedger`、`JobAnswers`、`JobMaterials`、`SessionRepository`） | **持久化一致性**：读-改-写 `job.md`、台账总表 | 保留；持锁很短，只是轻微争用 |
| `ApplicationRunner` 实例 `synchronized`（所有公开方法 + `stopRequested`） | 运行表、排队凭证、状态推导 | 保留，但**不必要地串行化**了每一步的 `stopRequested(jobId)` 检查和 `status()` 里的读文件 → 停止标记换成并发集合、读文件移出锁 |
| 单线程执行器 | 「同一时间一个申请」 | **不必要的串行**，换成 N 个 Worker |
| `AgentRunStore` / `RunLog` / `UploadArea` / `FillReportWriter` | 每个 Job 一个文件 / 目录 | 已经按 Job 分开，安全 |
| `SiteNotes.update`（无锁读-改-写） | 站点记录 | 目前主代码只读，安全；以后加写要放进写锁 |
| `RuntimeEvents`、`Inbox` | 事件、插话 | 已经按 Session / Job 分开且同步，安全 |

## 4. 并发的三个层次（分别验证）

1. **Agent 并发**：多个 `HarnessLoop` 同时跑 —— 代码上已经是独立实例，只差调度（第 2 节第 1 条）。
2. **MCP 并发**：
   - (a) **同一个 MCP 会话内**并发 / 交错调用；
   - (b) **多个独立 MCP 会话**（多个进程，或一个服务的多个会话）连同一个浏览器。
   两种都要对 0.0.83 验证：各自的当前页、一个会话打开的新页 / 弹窗会不会被另一个收走、页面事件（console、dialog、文件选择框、下载、「Open tabs」列表）会不会串到别的会话的返回里。
3. **页面隔离**：每次工具调用落在对的标签页上；导航、重定向、弹窗、关闭后归属仍然对。

## 5. 方案比较和推荐

证据标记：[源码] `@playwright/mcp` 0.0.83 / playwright-core 1.64.0-alpha 的 coreBundle.js；[实验] 调研代理在自己的 Chrome for Testing（临时 profile、9333 端口、本地页面）上做的；[文档] 官方文档 / 维护者说明；[推断] 没实测。

### 5.1 Playwright MCP 0.0.83 里的事实

- **一个会话一个当前页** [源码]：`Context._currentTab` 单值；所有页面工具先 `ensureTab()`，没有页面 ID 参数，只有 `browser_tabs` 带序号；`selectTab` 会 `bringToFront()`（切前台）。`browser_run_code_unsafe` 跑在当前页上。
- **ref 按页** [源码 + 实验]：快照来自 `page.ariaSnapshot({mode:"ai"})`，两页的 ref 各自从 e2 编号，只在自己的页上能解析。
- **(a) 同一个会话内并发** [源码 + 实验]：服务端没有队列也没有锁（两个 1.5 秒的调用并行 2.0 秒做完）；但交错 `select 0 / select 1 / snapshot / snapshot` 时 Worker 0 拿到了另一页的快照 → **同一会话不能给多个 Worker 用**（这就是现在 Java 端那把全局锁的原因）。Java 端 stdio 客户端两个线程同时发还会失败（审查）。
- **(b) 多个独立会话** [源码 + 实验]：`--port`（HTTP）下每个会话有自己的 Backend / Context / 当前页。加 `--shared-browser-context` 时 `connectOverCDP` **只做一次**，后来的会话共用这一条 CDP 连接和同一个 context（3 个会话 = 1 条连接，143 MB；会话 2 能读到会话 1 设的 cookie）。不加就每个会话各连一次。最后一个会话断开时共享连接关闭。
- **归属和事件串扰** [实验]：别的会话新开的页 / 弹窗会出现在我的标签页列表里，但**不会变成我的当前页**；console、dialog、文件选择框按页记，响应只报自己当前页的，**不串**；但每个响应的「Open tabs」列出所有页（别人的标题和网址），序号全局、关页会挪；下载每个会话都会存一份 [源码，未测]；cookie / storage / route 类工具作用于整个共享 context [源码]（我们不开放这些给模型）。
- **一个没解释清的卡住** [实验]：开了焦点模拟的页弹出同站点新页后再弹 alert，点击卡 20 秒没有 dialog 事件，另一个进程的同站页取快照也卡。推断：Chrome 不给非前台页弹对话框、同站点页可能共用渲染进程。同一网站上两个申请并发时可能遇到；兜底：超时后把那一页切到前台。

### 5.2 Chrome 授权（按我们实际的连接方式）

- `--cdp-endpoint chrome` 读 `DevToolsActivePort` 得到 `ws://localhost:<port>/devtools/browser`，一次 `connectOverCDP` = 一条 websocket，所有页共用 [源码]。
- 允许提示**按每条 websocket 连接**弹，不按进程、不记住；同时几条连接就叠几个对话框；只有复用已经允许过的连接才不再弹 [文档：Chrome 博客 M144、chrome-devtools-mcp #1794 / #825]。**没在用户的 Chrome 上实测**；30 秒超时来自本仓库之前的观察。
- 推论：B（每个 Worker 一个进程）= 每次启动 / 重启 N 个提示，不可接受；A / A′ 只有 1 个。

### 5.3 真正挡住并发的是后台标签页被冻结（关键）

- [实验，有头 + 无头] 后台标签页是 `hidden`，`requestAnimationFrame` 0 次、计时器降到每秒 1 次；Playwright 点击前的「稳定」检查靠 rAF → **后台页上的点击必然超时**（填写、setInputFiles、快照仍能用）。
- [源码] Playwright 平时给每个页面开「焦点模拟」，但 MCP 的 CDP 模式（`noDefaults:true`）对默认 context 不开；用户正在跑的 Chrome 也加不了防节流启动参数。
- [实验] 修法：对每个 Worker 的页发 CDP `Emulation.setFocusEmulationEnabled({enabled:true})`，页面变 `visible`、帧和计时器恢复，窗口最小化也行。在现有 MCP 里用固定模板 `browser_run_code_unsafe`（`newCDPSession(page)` → 发这条）就能做到；两个 `--port` 会话同时点击都成功（565 ms），不开的那个超时。`Page.setWebLifecycleState`、截屏流都没用；`bringToFront` 只是把问题挪给另一页。
- **顺带发现** [实验]：单个 Worker 时，点出弹窗会让原页变成后台页，之后在原页上的点击超时 —— **很可能就是真实跑里第 20 条（项目经历「添加」点不动）的根子**。焦点模拟对单 Worker 也有用。

### 5.4 比较

| | A：一个 MCP 服务，`--port --shared-browser-context`，每个 Worker 一个 HTTP 会话 | A′：自己写 CDP 小服务 | B：每个 Worker 一个 MCP 进程 | C：每个 Worker 独立 context / profile |
| --- | --- | --- | --- | --- |
| 真正并发 | 是（加焦点模拟） | 是（加焦点模拟） | 是（加焦点模拟） | 是 |
| 标签页隔离 | 每个会话自己的当前页；别人的页看得见、能 select，要 Java 端挡 | 最干净（按 pageId） | 同 A | 完全 |
| 共享登录 | 是 | 是 | 是 | **否** |
| 允许提示 | 1 次（服务一直保持至少一个会话） | 1 次 | N 次，每次重启再来 | 不需要（自己起浏览器）但没有登录 |
| 资源 | 1 进程、1 连接 | 1 进程、1 连接 | N 进程、N 连接（约 126 MB / 个） | 每个 context / 浏览器各自的内存 |
| 改造成本 | 低：Java 换成 SDK 2.0.0 里已有的 Streamable HTTP 传输 + 启动参数 + 一个模板 | 500–800 行 JS，要自己重写等待页面稳定、对话框 / 文件选择框、响应格式、下载、路径检查、遮挡等，以后的 MCP 修复也拿不到 | 很低 | 低，但不满足需求 |
| 风险 | 「Open tabs」带出别的 Worker 的页、序号会挪；下载重复；作用于整个 context 的工具互相影响（不开放）；HTTP 端口必须只听 127.0.0.1；所有会话都断了会再弹提示；同站点对话框卡住 | 自己维护 | 提示疲劳 | 没登录 |

参考项目：Hermes（e5dad4ac5）本地模式每任务一个无头 Chromium（C），CDP 模式每任务一个守护进程连同一个地址（B），没有标签页隔离；OpenMAIC（dff201b）每个渲染请求一个无头浏览器 + 信号量；deepagents、deep-agents-ui、langgraphjs 没有浏览器自动化。chrome-devtools-mcp #2188 讨论的就是 A 这种设计。

### 5.5 推荐：A

- 一个长期运行的 `@playwright/mcp@0.0.83 --cdp-endpoint chrome --port <随机> --host 127.0.0.1 --shared-browser-context`（其余参数不变），不改 MCP 包、不写小服务。
- Java 端一直保持一个空闲会话，共享连接不掉，用户只在应用启动时点一次允许。
- 每个 Worker（申请线程；调研 Session 也一样）一个 MCP HTTP 会话：`browser_tabs new` 开自己的页，立刻跑焦点模拟模板；弹窗由打开它的 Worker 选过去，也开焦点模拟。
- 每个会话只有自己一个使用者 → **去掉全局浏览器锁和每次动作前的 select**；锁降到「每个会话一把」（同一个 Worker 的调用本来就是一个接一个）。
- Java 端的归属检查（用户要求的统一路由和所有权检查）：Worker 只能 select / 关自己的页，按网址 + 会话内记录认，不按全局序号；给模型的标签页列表只列它自己的（过滤「Open tabs」）。
- 连接状态（`BrowserState`）仍然一份（就是那一条共享连接）；一个 Worker 的会话出错只重建它自己的会话，不清别人的记录。

**需要用户批准的**：方案 A；这会改掉 master 里「一条串行队列 / 一个后台线程」的决定（workflow.md、agent-runtime.md 等，见第 8 节）。

## 6. 任务状态

用户给的 `PENDING → ASSIGNED → RUNNING → REVIEW_REQUIRED / SUBMITTED / FAILED` 映射到现有状态，不另造：PENDING = `queued`；ASSIGNED + RUNNING = `running`；REVIEW_REQUIRED = `waiting_user` / `ready`（`needs_input` / `awaiting_review`）；SUBMITTED = 用户确认后记 `applied`（系统不点最终提交）；FAILED = `failed` / `interrupted`。已有：状态落盘、停止、插话、防重复（同一个 Job 只有一个申请线程）。提交结果不确定时先核验（`page_shows_submitted`、用户确认），不重新提交。

## 7. 两个 Worker 的最小验证（第 2 阶段，必须证明真的重叠执行）

不提交任何申请；用本地夹具页（`backend/src/test/resources` 的 HTML 夹具）和自己启动的无头 Chrome，不连用户的 Chrome。

| 场景 | 证明什么 |
| --- | --- |
| 两个 Worker 同时导航、同时填表、同时取快照，记录每个动作的开始 / 结束时间 | **时间区间有重叠**（不是先后成功）；每个快照、每个填写都落在自己的页 |
| A 被重定向（同站换网址）时 B 在填写 | A 的页仍归 A，B 不受影响 |
| A 点击弹出新标签页，同时 B 在动作 | 新页归 A，不被 B 收走 |
| A 的标签页被关掉 | A 得到「页不见了」，B 继续 |
| A 的 Worker 抛异常 / 被停止 | B 继续，A 的页归属清理干净 |
| 文件选择框 / 对话框出现在 A 的页 | 不挡 B，事件不串到 B |
| 用户在看第三个标签页、或者把窗口最小化 | 两个 Worker 的点击都不超时（焦点模拟生效） |
| 一个 Worker 结束、另一个开始 | 不再弹允许提示（共享连接保持） |
| 同一网站两个 Worker（弹窗 + 对话框） | 记录是否出现 5.1 的卡住，以及切前台兜底是否有效 |

自动部分在自己启动的无头 / 有头 Chrome 上跑（本地夹具页）；最后一步在用户的 Chrome 上做一次手动冒烟（两个真实申请同时填到提交前、不提交），记下允许提示次数、点击超时次数、有没有动作落错页。

## 8. 要改的模块（按方案 A）

- `workflow/ApplicationRunner.java`：单线程执行器 → N 个 Worker（`HarnessProperties.applicationWorkers`，默认值待定）；`runningJob` → 集合；`status` / `busy` / `awaitIdle` / 排队逻辑跟着改；停止标记移出锁。
- `config/HarnessProperties.java`、`application.yml`：Worker 数。
- `tools/PlaywrightMcpClient.java`：启动一个 `--port --host 127.0.0.1 --shared-browser-context` 的服务进程；每个会话一个 `HttpClientStreamableHttpTransport` 客户端（SDK 2.0.0 已有）；保持一个空闲会话；焦点模拟模板（和 `SET_INPUT_FILES` / `DIAGNOSE` / `CLICK_THROUGH` 同一做法，模型调不到）。
- `tools/BrowserSession.java`：拆成「共享连接（状态、服务进程、空闲会话）」+「每个使用者一个会话（自己的页、弹窗、记账、每会话一把锁）」；去掉全局锁和每次动作前的 select；新弹出页按集合差异 + 打开者判断；给模型的标签页列表只列自己的；一个会话坏了只重建它。
- `config/ApplicationConfiguration.java`、`config/BrowserMcpProperties.java`：启动参数、端口。
- 调研 Session（`SessionLoop`）也走自己的会话，和申请一样。
- `workflow/SessionRunner.java`：「浏览器在做别的申请、排上队」的说法只在池满时出现。
- 测试：没有 `ApplicationRunner` 的单元测试；第 7 节的验证落成一个并发测试（重叠执行 + 不串页）。
- 文档（批准后回写）：`plan/master/workflow.md`（:125「所有浏览器任务共用同一条串行队列」等）、`agent-runtime.md`（:146「一个后台线程（浏览器串行）」等）、`base.md`、`architecture.md`、C006-fix-runtime-2 / C006-fix-browser 里「单线程执行器不变」「一条连接给所有 Job」这些不变量。

## 9. 风险和测试方法

| 风险 | 测法 |
| --- | --- |
| 动作落到别的 Worker 的页上 | 第 7 节每个场景断言页面 URL / 内容 |
| 弹窗被错的 Worker 收走 | 同时点击弹出 + 别的 Worker 动作 |
| 一个断开 / 崩溃拖垮所有 Worker | 杀掉一个 MCP 会话或抛异常，看别的 |
| 后台标签页被 Chrome 冻结，点击必然超时 | 每个 Worker 的页开焦点模拟；第 7 节「看第三页 / 最小化」场景 |
| 前台标签页来回切（`selectTab` 会 `bringToFront`）打扰用户 | 方案 A 下每个会话只在开页 / 选弹窗时 select 一次，不再每个动作都切；冒烟时观察 |
| 所有会话都断开 → 共享连接关闭 → 再弹允许提示 | 保持一个空闲会话；冒烟时数提示次数 |
| HTTP 端口被本机其它程序访问 | 只听 127.0.0.1、随机端口 |
| 同站点两个 Worker 时对话框卡住（5.1） | 第 7 节同站点场景；超时后切前台兜底 |
| 下载在每个会话各存一份 | 申请流程不下载文件（已有规则）；输出目录按会话分开或不清空 |
| 同一网站 / 账号并发请求过多、出验证码 | 同站点限速（每站同时最多 1–2 个）；验证码交给用户 |
| token 和模型限流成倍增加 | Worker 数可配；记录总用量 |
