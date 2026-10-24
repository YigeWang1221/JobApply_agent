# C011 · 多个申请 Worker 并发（共享一个 Chrome、各自的标签页）

- 依据：用户（2026-10-24）「Job Apply 项目：多 Agent 并发浏览器自动化架构」：一个 Main Agent / Scheduler 调度多个独立 Worker，每个 Worker 独占自己的 Chrome 标签页，通过共享的 MCP / CDP 基础设施并发完成多个申请，不发生浏览器操作冲突；尽量复用现有 Chrome、MCP、登录状态和工具；增量改造。
- 用户补充（2026-10-24）：**施工前有一个架构决定检查点**；C011 写完、带证据给出推荐方案、用户批准之后才拉施工代理。优先级：浏览器隔离正确 + 真正并行，保留现有登录，改动最小。
- 状态：**用户批准方案 A（2026-10-24「在 subagent 上进行 A」），施工中**；master 已回写（workflow.md、agent-runtime.md、architecture.md、base.md）。（调研完成 2026-10-24，证据见 [research-concurrent-browser.md](../reference/research-concurrent-browser.md)，实验脚本在会话 scratchpad `exp/`）。批准前不施工。
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

## 实际结果 · Worker 池（2026-10-24）

只做了调度这一半；浏览器层（每个使用者一个 MCP 会话、焦点模拟）由另一个施工代理并行做，这里没碰 `BrowserSession` / `PlaywrightMcpClient` / MCP 参数。

- `workflow/ApplicationRunner.java`：单线程执行器换成 N 个 Worker 的固定线程池（线程名 `application-worker-N`）。`runningJob` / `current` 换成 `running`（Job → 网站 + 任务）；`queued` 改成 Job → 轮到时要跑的那次 `AgentRun`，**排队的不再提前交给执行器**，只有 `dispatch()` 放行的才交给 Worker。`dispatch()` 在排队、运行结束、排队时停下又接着跑时调用：按排队顺序，在跑的少于 Worker 数、并且同一网站在跑的少于每站上限时开始；网站满了的跳过，后面别的网站可以先走。
- 排队凭证和 `cancel(false)` 去掉了：它们是为了「执行器已经取走、在等锁的任务」，现在排队的任务还没交给执行器，排队时停止直接从 `queued` 移出（`STOPPED_IN_QUEUE`，存盘、不回报，有插话就接着排队）。
- 插话（C010）不变：在跑或排队时进 `Inbox`；跑完还剩的由 `resumeWithInserted` 接着排（在空出位置、`dispatch()` 之前）。
- 锁（第 3 节）：实例锁只保护 `queued` / `running`、排队和结束时的存盘；`stopRequested` 换成并发集合，每一步的停止检查不拿锁；`status()` 只在锁里读运行记录和运行表（结束时的存盘和移出运行表也在锁里，读不到「文件在跑、内存已经没了」的中间态），台账、报告、原件、附件、浏览器状态在锁外读。`run()`、`report()`、`statusIfAny()` 不再拿实例锁（`AgentRunStore` 自己同步）。`StorageLock.WRITES` 没动。
- `awaitIdle(Duration)` 等所有在跑和排队的结束；新增 `awaitIdle(jobId, Duration)` 等一个 Job。用 `wait/notifyAll`，运行结束和排队时停下都会通知。
- 防重复：同一个 Job 还在跑或在排队时开始返回 409（`live`），和以前一样。
- 同站点礼貌限速：`BrowserToolPolicy.siteOf(岗位链接)`，默认每站同时 2 个；认不出网站的不限。
- `config/HarnessProperties.java`、`application.yml`：`jobapply.harness.application-workers`（默认 3）、`applications-per-site`（默认 2）。
- `workflow/SessionRunner.java`：`start_application` 只在真的排上队时说「排上队」，文案改成「同时在跑的申请已经满了（或者同一个网站上的申请够多了）」。`tools/ResearchTools.java` 的工具说明「One application runs at a time」改成可以同时跑、多出来的排队。两个 Agent 的提示词里没有串行的说法，没改。
- 前端：`status.tsx`、`JobPage.tsx` 都按单个 Job 显示，没有假设只有一个在跑，没改。
- 测试：`ApplicationRunnerPoolTest`（假模型按公司名认出申请、卡在门闩上直到测试放行；假浏览器服务端）。第一个用例：3 个 Worker，A/B/C 都进了模型调用而谁都没出来（门闩证明同时在跑，另外断言最晚开始早于最早结束）；D、E 排队；排队时停 E → 等你 + `STOPPED_IN_QUEUE`，模型从没见过 E；放行 A（抛不可重试的错）→ A 失败，D 开始，B/C 仍在跑；最后 B/C/D 等你。第二个用例：每站 2 个，同站点第三个排队，后面别的网站的直接开始，前面的放行后第三个开始。
- 验证：`mvn -B -q clean verify` 通过；新测试单独连跑 3 次都通过。没有在用户的 Chrome 上跑（浏览器层还没换）。

给浏览器层合并时要知道的：

- 现在申请线程真的会并发调用 `BrowserSession`（open / snapshot / act / select / takeHandover / ownsTab / retryOnNextUse / state）；在新浏览器层落地之前靠它现有的全局锁保持正确，只是浏览器动作仍是一个接一个。
- 拆成「每个使用者一个会话」时，使用者就是 Job 编号（申请）和 Session 编号（调研），`HarnessLoop.startPage()` 用的接口（`takeHandover`、`ownsTab`、`snapshot`、`open`、`select`）保持不变就不用改这里。
- `execute()` 每次运行前调 `browser.retryOnNextUse()`（全局）；并发下一个 Job 的重连会影响共享连接，新层要决定它按会话还是全局。
- Worker 数和每站上限都在 `HarnessProperties`，浏览器层不用再加限速。

## 实际结果 · 浏览器层（2026-10-24，施工代理）

### 改了什么

- `tools/PlaywrightMcpClient.java`：stdio 换成 Streamable HTTP（MCP Java SDK 2.0.0 `HttpClientStreamableHttpTransport`，HTTP/1.1，地址用 `http://localhost:<端口>/mcp`，因为 0.0.83 只认 `Host: localhost:<端口>`）。新的 `Server`：一个长期运行的进程，程序追加 `--port <随机空闲端口> --host 127.0.0.1 --shared-browser-context`（其余参数照旧），`env -i PATH HOME` 启动，等端口开始监听；关闭时连 `npx` 起的 node 子进程一起关。`Server.open()` 开一个会话（一个客户端、声明 roots、检查必需工具）。原来的 `start()` 保留成「一个进程 + 一个会话」的单独客户端。三个固定模板不变，新增三个：焦点模拟（`newCDPSession(page)` → `Emulation.setFocusEmulationEnabled`）、弹窗检查（当前页打开的页，`page.opener()`）、切到前台（`page.bringToFront()`）；模型都调不到。关会话不再 `closeGracefully`（服务进程已退出时要等满 10 秒），服务端靠自己的心跳清掉。
- `tools/BrowserSession.java`（共享连接）：一个服务进程 + 一个空闲的保活会话；建立连接 = 起进程、开保活会话、用它列一次标签页（这时才连 Chrome、弹一次允许），答话了才算 `connected`。`BrowserState`、自动重连一次、`reconnect` / `retryOnNextUse`（只在 `disconnected` 时才留一次重连，不碰健康的连接和别人的会话）、状态事件都和原来一样。**没有全局锁**：连接只在建立时拿一把锁，使用者的调用各拿自己的锁。断开时分清谁坏了：服务进程死了或保活会话也不答话 = 共享连接断了，所有使用者的记录作废（按连接序号懒清空），都得到 `Disconnected`；保活会话还好 = 只是这个使用者的会话坏了，关掉它、下次用时重建，这次报工具错误让模型取快照核对，别人不受影响。`unowned` / `handOver` 用保活会话列标签页。
- 新 `tools/OwnerBrowser.java`（每个使用者一个）：自己的 MCP 会话、自己的标签页记录、弹窗、说明、转交，**锁按使用者**。`open` = 自己会话里 `browser_tabs new` + 焦点模拟；动作不再先 `select`，只在开页、切到自己的弹窗、转交 / 会话重建后找回自己的页时 `select`（之后都开焦点模拟）。每次动作前在自己会话里列一次（不切换）核对当前页还是自己的：当前页变成别人记下的网址、或者标签页变少且自己的网址不见了 = 自己的页被关了 → `TabLost`；否则认作自己的页换了网址。点击后的弹窗：返回里的标签页列表比上次多出来的页，再用弹窗检查模板核对 opener 是自己的当前页才收（见下面「偏差」）。给模型的 `tabs` 只列自己的页、序号是自己的编号；`select` 只能切自己的页。标签页按网址认，记下的序号只当提示（别人开关页会让全局序号移动）。
- 兜底（第 5.1 节）：点击 / 输入 / 选择动作超时后，先把这页切到前台一次，再做原来的只读检查。
- 小改：`HarnessLoop` 两句给模型的话（列表只有自己的页；上次的页要用请用户转交）；`ApplyAgent` 提示词「标签页」「用我开着的那个页面」两条、skill apply-form 一句；`application.yml` 注释；`plan/master/workflow.md` 标签页规则后补一句。`ApplicationRunner` 没动。
- 测试：假服务端（`FakePlaywrightMcpServer`）改成子进程里的 Tomcat（只听 127.0.0.1），走真实的 Streamable HTTP，每个会话自己的当前页（多标签页模式），调用记下会话、开始 / 结束时间和当时的网址，等待不在锁里。改了的断言只有架构带来的：调用序列多了保活会话的一次列表和开页后的焦点模拟；列表只有自己的页、转交后序号是自己的编号；`oneConnectionServesEveryJobInTurn` 改成 `oneSharedConnectionAndOneSessionPerOwner`（1 个服务进程、3 个会话）。新增 `BrowserSessionConcurrencyTest`：两个使用者同时填写 + 取快照，断言两次填写的时间区间重叠、每个会话从开页起每次调用都落在自己的页、没有任何 `select`（一次运行：两次填写 640337865–640645761 和 640337865–640646570 微秒，重叠约 308 ms）。

### 第 7 节矩阵（手动检查 `ConcurrentBrowserManualCheck`，不进 verify）

自己启动的 Chrome for Testing（chromium-1243，有头，临时 profile，`--remote-debugging-port` 随机端口，不是 9222），本机 127.0.0.1 夹具页，同一条代码路径（`BrowserSession` → 一个 `@playwright/mcp@0.0.83 --port --shared-browser-context` 进程，`--cdp-endpoint http://127.0.0.1:<端口>`，`--timeout-action 5000`）。最后一次运行（时间是相对开始的毫秒）：

| 场景 | 结果 | 证据 |
| --- | --- | --- |
| 同时开页 / 填写 / 取快照 / 点击 | 通过 | 区间重叠：开页 A 6715–7523、B 6715–7502；填写 7524–7638 / 7524–7623；快照 7638–7653 / 7638–7652；点击 7653–8240 / 7653–8237。每个结果都是自己的页（标题、填的值只在自己页上） |
| A 重定向（同站换网址）时 B 在填写 | 通过 | A 8271–8605 到 `A-step2`，仍归 A；B 8271–8316 填写落在 B |
| A 点击弹窗（`window.open`），同时 B 点击 | 通过 | 弹窗归 A、A 切过去并留说明；B 当前页不变，B 的列表只有 B |
| `target=_blank`（Chrome 默认 noopener）的新页 | 通过 | opener 核对认出来，B 切过去（11507–13266） |
| 用户在看第三个标签页（两个 Worker 的页都在后台） | 通过 | 同时点击 14375–15037 / 14375–15034，都没超时（焦点模拟）；第三页列在 `unowned` 里 |
| 文件选择框 / 对话框出现在一个页上 | 通过（隔离） | C 的文件选择框、D 的 alert 只出现在它们自己的结果里；B 同时取快照 / 点击，`modalState` 为空。注：0.0.83 有模态框时点击之后那次 `browser_snapshot` 报错，所以 C / D 的这次操作在 Java 端是 `ToolFailed`（错误里说明了选择框），和改造前一样 |
| 一个 Worker 抛异常 | 通过 | B 的线程抛出，E 同时开页 + 点击成功 |
| 一个标签页被关掉（Chrome `/json/close`） | 通过 | B 得到 `TabLost`（19443–19471），E 继续点击 |
| 一个 Worker 结束、新 Worker 开始；CDP 连接数 | 通过 | 到 Chrome 的连接：建立后 6 秒 1 条（刚连上时 2 条，多的那条是 `connectOverCDP(http://…)` 取 `/json/version` 的 keep-alive，几秒后断；用 `--cdp-endpoint chrome` 时没有这条）；多个使用者时 1 条；新使用者开页后 1 条；连接代数一直是 1（没有重连 = 不会再弹允许） |
| 同站两个页（A 开着同站弹窗）时 A 的页上弹 alert | **不通过（复现了 5.1 的卡住）** | A 的点击 60 秒没回来（调用超时）；8 秒时用 Chrome 的 HTTP 接口把 A 的页切到前台也没用；之后 F 的列表调用也卡 60 秒（列表要取每个页的标题，挂着的那页取不到）。同时进行的 E 的快照（21309–21348）没受影响 |

### 和计划不一样的地方

- **弹窗只按 opener 认，不按「多出来一页」猜**：第一次手动跑时 B 的点击结果里多出了 A 同时打开的弹窗，B 的检查没找到自己的 opener，按「只多出一页」收了它（B 跑到了 A 的弹窗上）。去掉这条猜测后，`target=_blank` 的新页 Playwright 照样报 opener，弹窗归属全部正确。
- **每次动作前仍列一次标签页**（不切换）：计划说去掉「每次动作前的 select」，做到了；但还留着一次 `browser_tabs list`，因为自己的页被关掉时 MCP 会悄悄把当前页换成旁边那一页（可能是别人的），不核对的话点击会落到别人的页上。列表调用通常十几毫秒。已知漏洞：自己的页被关掉、恰好同时别人开了一页、MCP 换到的又是没人记的页时，会被当成「自己的页换了网址」。
- **切前台兜底对复现出来的卡住没用**：保留了「动作超时后切一次前台」（小，只在超时时），但第 5.1 节那种卡住不是动作超时，是整次调用不返回；切前台（MCP 内、Chrome HTTP 接口都试了）也不让对话框出来。真实使用时遇到：用户在 Chrome 里关掉那个对话框或那一页；下一步可以考虑系统自动处理对话框，或者有同站弹窗开着时不给原页开焦点模拟（都没做）。
- 转交后给模型的序号是它自己的编号（不是浏览器里的序号）；界面的 `unowned` 仍是浏览器里的序号。

### 用户在自己 Chrome 上冒烟时看什么

- 启动后第一次用浏览器：**只弹一次**允许；之后开两三个申请、调研同时用浏览器、一个结束再开新的，都不该再弹（连接代数保持不变）。
- 两个申请同时填：各自的页上动作，前台页不会来回跳（只有开页、切到自己的弹窗、转交时 `select` 会把那页切到前台）；用户看别的页或把窗口最小化时点击不超时。
- 同一网站两个申请、其中一个开着同站弹窗又遇到 alert：可能卡住（上表最后一行），要用户手动关掉对话框。
