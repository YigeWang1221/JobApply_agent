# C006-fix · 浏览器：连接状态统一管理；标签页只碰自己的

- 依据：用户（2026-10-23）：
  1. 「不需要完全统一行为，而应该统一底层的浏览器连接状态管理」。
  2. 「收紧 Tab 权限，agent 只能管理和接触自己打开和创建的 tab（用户可以在这些 tab 上登录等操作，用户也将 tab 转交给 agent）」。
- 参考：Hermes e5dad4ac5 `tools/browser_tool.py:328`（`_ensure_cdp_supervisor` → `SUPERVISOR_REGISTRY.get_or_start`：一个全局登记处，幂等，重复调用不会重复连接）、`tools/browser_supervisor.py:504–587`（断了按 0.5–10 秒退避重连，连上后清零；连接层失败不拖垮任务）。
- 不做：两个 Agent 遇到断开时的应对统一成一样（申请 Agent 停下等你、对话 Agent 这一步失败接着做，保持不变）；多个浏览器；用户手动输入网址交给 Agent 以外的转交方式。

## 1. 浏览器连接：一份状态，一个入口

现状：连接状态在三处各存一份，互不同步——`BrowserSession` 的 `client` / `needsUser`；每个申请的 `AgentRun.disconnected`（回复时才重连）；对话 Agent 每次运行的 `reconnectTried`。界面只能按申请显示「断开了」，看不到浏览器本身的状态。

改成：
- `BrowserSession` 里只有一份连接状态：`未连接` / `连接中` / `已连接（第 N 次连接）` / `断开了（原因、时间）`。快照里的 ref 只在同一次连接里有效（已有规则）。
- 一个入口 `ensureConnected()`：幂等、同一时间只有一个连接在建立（别的调用等它的结果），参照 Hermes 的 `get_or_start`。两个 Agent 的每次浏览器调用、用户的「重新连接」都走它。
- 断开后：下一次有人用浏览器时自动重连一次（远程调试模式下重连不用再点确认，2026-10-23 起的默认）；连不上就进入「断开了」，不在后台反复重试（不让 Chrome 反复弹窗、不空转）。用户点「重新连接」或发下一条消息时再试。这一条替换原来「断开后一律等用户发 reconnected」的规则（那是扩展模式时定的）。
- 状态变化发全局事件（`browser`），界面顶部显示一条：断开时写原因和「在 Chrome 打开 chrome://inspect/#remote-debugging 的开关后点重新连接」，带按钮。`GET /api/browser`、`POST /api/browser/reconnect`。
- 两个 Agent 只读这份状态、各自决定怎么应对（保持现在的不同）：申请 Agent 停下等你，等待的原因写「浏览器断开」，状态从全局推出来，不再在 `AgentRun` 里另存一个 `disconnected`；对话 Agent 这一步失败、接着做别的。去掉对话 Agent 的 `reconnectTried`。

## 2. 标签页：只碰自己的；用户可以转交

规则（写进 workflow.md「Chrome MCP」，替换「同站点的空闲标签页也能列、能选」）：
- Agent 只能列出、切换、读取、操作**属于它的标签页**：它自己新开的、它点击后新弹出的、用户转交给它的。用户在这些标签页上登录、填 C 级字段、点按钮都可以，标签页仍属于它（同一页网址变了也是它的，已有规则）。
- 不属于它的标签页（用户自己的、别的申请的、空闲的），Agent 看不到——工具结果里不出现它们的标题和网址，不快照，不切换。
- **转交**：工作台上「把一个标签页交给 Agent」：列出 Chrome 里当前没有主人的标签页（只给你看标题和网址），你选一个，它就成了这个申请的。也可以在对话里说「用我开着的那个页面」，对话 Agent 不能自己挑，它用 `ask_user` 请你在工作台上选。
- **用户之前打开的标签页（用户补充，2026-10-23）：除非用户明确说交给 Agent，Agent 不能操作，也不能读。** 只有用户的明确动作才算转交：工作台上选中一个标签页交给它。Agent 不能自己判断「这一页大概是我的」然后拿过来。
- 重启以后：属于谁只记在内存里，重启就没了；**不再按网址自动认回**（同一个网址的那一页也可能是用户自己开的）。接着做时新开岗位页，并在等待说明里告诉用户：原来那页还开着、想接着用的话，在工作台上把它交给 Agent。去掉 `adopt` 按上次网址自动认回、`reclaim` 自动认回。
- 去掉：`tabs` 工具和 `reclaim` 里「同站点的空闲标签页」「可能是它的」候选和自动认回；模型的 `tabs list` 只列它自己的标签页。同一次运行里自己的标签页序号挪了、网址变了，仍按已有规则认（这是它自己的页，不是用户之前开的）。

## 审查后的结构修正（2026-10-23）

只读审查对照 Playwright MCP 0.0.83 源码，结论：**「序号 + 网址」逐条猜归属，保证不了「用户之前打开的标签页不碰」**，至少 5 条路径会违反（同网址被规则 ② 认走；「关一个开一个」时规则 ③ 认走同站点的用户页；Agent 的弹窗关闭后 MCP 切到相邻的用户页、系统直接快照；`browser_find` 原文带出所有标签页的标题和网址；点击期间用户新开的页被当成弹出页收下）。根子：MCP 的标签页列表没有稳定编号，逐条猜补不完。

结构上的改法：
- **整表对齐，不逐条猜**（审查找到的事实：Playwright 的标签页按创建顺序排，新开的总加在末尾，关掉的直接移除）。保存上一次的完整列表；新列表只用「删掉若干项 + 末尾追加若干项 + 原位换网址」来解释。只有**唯一**的对齐才接受；有歧义（例如个数没变、又有不止一页换了网址）就判定这个使用者的标签页丢失，走「请用户转交」。删掉规则 ②（网址唯一）、③（同序号同站点）、④（可能是它的）这类逐条猜测。
- **读之前核对当前页**：每次写操作（点击、导航、填写、上传）之后、取快照之前，用结果里的 `(current)` 标记（没有就再列一次）核对当前页还是这个使用者的；不是就不快照、抛 TabLost。`select` 之后同样核对。
- **点击后收下新标签页**：只在列表恰好末尾多出 1 页、它是当前页、且对齐唯一时收下；否则不收，结果里写「出现了新标签页；如果是用户的，请用户转交」。
- **`find` 只返回 `### Result`**，丢掉 `Open tabs`、`Page` 等小节。
- 小项：工作台订阅 `browser` 事件刷新断开提示；`retryOnNextUse` 不拿锁；`connect()` 准备目录失败抛 Disconnected；`GET /api/browser/tabs` 断开时提示先重新连接；不能转交给已结束的申请；`startPage` 里转交的页切换失败时仍告诉模型。

分工：N（`BrowserSession` 的对齐、`focus` / `act` / `select` / 新标签页收下、规则 ②③④ 删除，`HarnessLoop` / `SessionLoop` 里相应的 TabLost 处理）；O（`find` 清洗、小项，含前端 JobPage 和 BrowserTabsController）。共享 `BrowserSession`：O 只碰 `find` / `retryOnNextUse` / `connect`。

## 分工

- L（连接状态）：`BrowserSession` 的连接部分（`connect` / `reconnect` / `call` / `needsUser` / `isConnected`）、新的连接状态和事件、`ApplicationRunner` / `HarnessLoop` / `SessionLoop` 里的断开处理、`AgentRun.disconnected` 改成推导、`BrowserController`（`/api/browser`）、前端顶部状态条。
- M（标签页）：`BrowserSession` 的标签页部分（`claim` / `tabs` / `select` / `reclaim` / `adopt` / `choosable`）、转交接口（`GET /api/browser/tabs` 只给界面用、`POST /api/jobs/{id}/application/tab`）、`HarnessLoop.startPage`、工具说明和提示词、前端工作台的转交入口。
- 共享 `BrowserSession`、`HarnessLoop`：只做定点修改。各自先在本文件写「必须保持不变」清单再改。

### M 施工细节（第 2 节）

- `BrowserSession`（只动标签页部分）：
  - `tabs(owner)` / `select(owner, index)`：只列、只切到**确定是它的**标签页（认页规则 ①②③ 认出来的：它新开的、它点击弹出的、用户转交的）。去掉 `sites` 参数、「没人占用而且同一站点」的空闲页、④「可能是它的」候选。④ 的记录仍留在内部（挡住别人、下次 ①②③ 还能认出），只是不给模型看。
  - 删掉 `adopt(owner, url)`（按上次网址认回）和 `reclaim` / `Reclaimed`（接着做时系统自己认回）。
  - 新 `unowned()`：没有确定主人的标签页（序号、标题、网址），**只给界面用**，不进任何工具结果。
  - 新 `handOver(owner, index, url)`：再列一次，序号还在、网址还是界面看到的那个、没有确定主人 → 记给这个使用者（别的记录里写着这个序号的去掉），记一条「待告诉模型的转交」；否则抛 `HandoverRefused`（接口 409）。不切换、不取快照（模型下一次用的时候才读）。
  - 新 `takeHandover(owner)`：取一次就清掉。
- 接口（新 `api/BrowserTabsController`，不碰 L 的 `BrowserController`）：`GET /api/browser/tabs` → `{"tabs":[{"index","title","url"}]}`；`POST /api/jobs/{id}/application/tab` `{"index","url"}` → 200 `{"index","title","url"}`，没有申请线程 404，标签页变了 / 有主人了 409，浏览器断开 503；成功后发 `changed(null, jobId)`。
- `HarnessLoop.startPage`：有待告诉模型的转交 → 切过去（`select`），页面说明「用户把标签页 N（标题、网址）交给你了」；否则自己的标签页认得出就用它；否则新开岗位链接。有 `lastPage.url`（接着做）而自己的标签页没了时，页面说明写：系统不再按网址认回；原来那页要是还开着、用户想接着用，请他在工作台上「把一个标签页交给 Agent」。运行中用户转交了：下一次浏览器工具的结果后面附一句「用户把标签页 N（…）交给你了，要用它就 browser_tabs select N」。`browser_tabs`、`tabLost` 改成只列自己的。
- 提示词和工具说明：`browser_tabs` 说明改成只列你自己的；`ApplyAgent`「新标签页」一条加「用户说用他开着的页面时，请他在工作台上转交，你不能自己去找」；`ResearchAgent` 加同样一条（它没有标签页工具，请用户在申请工作台转交或者把链接贴过来）。
- 前端 `JobPage` 右栏：「把一个标签页交给 Agent」：点开拉列表，选一个，提交，toast 结果。
- 测试（`BrowserSessionTabsTest`）：改依赖被删行为的 3 条（`reclaim` 的两条改成「不认回、只列自己的」、登录绕行那条改成转交），`tabs(JOB, sites)` 调用改签名；新加：用户的标签页没转交时不被列、不被快照；转交后能用；标签页变了或有主人时拒绝转交。

必须保持不变：
1. 自己的标签页里登录、网址变了（同站点、个数不变）仍是它的 —— `theSameTabWithAChangedUrlOnTheSameSiteIsStillThisJobs`。
2. 自己点击弹出的新标签页自动记给它、系统切过去 —— `act` 的 CLICK 分支不动。
3. `open()` 不删别人的记录 —— `openingANewTabKeepsAnotherOwnersRecordAfterTheUserClosedAnEarlierTab`（不改）。
4. 别的使用者的、用户自己的标签页从不被读、被操作、被快照，也不出现在给模型的列表里（新测试断言只调了 `browser_tabs`、列表里没有它们）。
5. 现有测试里的 MCP 调用序列不变（`BrowserSessionActTest`、`BrowserSessionConnectionTest`、`FakeFormScriptTest`、`PlaywrightMcpClientTest`、场景测试）。
6. 硬边界（不点最终提交、白名单、模型不能直接调 MCP 的 `browser_tabs`）不碰 —— `BrowserToolPolicyTest`、`BrowserToolsTest` 不改。

### L 施工细节（第 1 节）

- `BrowserSession`（只动连接部分）：
  - 一份状态 `BrowserState(state, reason, since, generation)`：`not_connected` / `connecting` / `connected` / `disconnected`。`volatile`，读状态不拿锁（界面查询不被一次长的浏览器调用挡住）。
  - `ensureConnected()`：已连上（或这条连接正在建立）→ 直接用；`not_connected` → 建立；`disconnected` 且还有一次自动重连 → 用掉这一次，建立；否则抛 `Disconnected`（原因 + 「打开开关后点重新连接」）。所有方法本来就是 `synchronized`，同一时间只有一个调用在建连接，别的调用等这一次的结果：成功就直接用；失败时自动重连已经用掉，直接拿到 `Disconnected`，不会再连一次（单飞）。每次浏览器调用（`call`）都先过它。
  - 连接建立后第一次调用成功才算 `connected`（Playwright MCP 进程起来时还没碰 Chrome），代数加一。用过的连接（`connected`）断开 → `disconnected`，留一次自动重连；还没成功过的连接（`connecting`）失败 → `disconnected`，不留（这就是那一次重连失败了，不反复弹窗、不空转）。没有后台重试。
  - `retryOnNextUse()`：用户发了下一条消息（对话、申请回复）→ `disconnected` 时再留一次，下一次用浏览器时才连（不用浏览器的消息不碰 Chrome）。
  - `reconnect()`：用户点「重新连接」。已连上就不动；否则留一次、建立、用 `browser_tabs list` 试一次（结果丢掉，不碰标签页记录），返回状态（失败不抛）。
  - 状态变化交给一个监听者（配置里接到 `RuntimeEvents.browser(...)`），`needsUser()` 去掉，`isConnected()` 保留。
- `RuntimeEvents`：新事件 `browser`（`session`、`job` 都为 null，只在全局流里），payload 是状态。`EventController` 把它写成 `{"browser": {state, reason, since, generation}}`。
- 新 `api/BrowserController`：`GET /api/browser` → 状态；`POST /api/browser/reconnect`（JSON `{}`，过本地请求防护的写接口规则）→ 状态。
- `AgentRun`：去掉 `disconnected` 字段（旧文件里有这个键，`AgentRunStore` 忽略未知键，照样读）；`waiting(...)` 去掉布尔参数，`reconnected()` 去掉。`ApplicationStatus.disconnected` 保留，改成推导：在等你 / 中断了，而且浏览器现在是 `disconnected`。
- `ApplicationRunner.execute`：去掉自己的重连分支，换成 `browser.retryOnNextUse()`（开始和回复都是用户的动作），连接由 `HarnessLoop` 第一次用浏览器时的 `ensureConnected` 建立；连不上照旧走 `HarnessLoop` 的「浏览器断开」等你。
- `HarnessLoop`（只动断开处理）：等你的说明写「浏览器断开」+ 原因 + 打开开关、点顶部「重新连接」或者回复；不再存 `disconnected`。
- `SessionLoop`：去掉 `reconnectTried` / `reconnectIfNeeded`；开始运行时 `browser.retryOnNextUse()`；断开时这一步失败、接着做（文字照旧）。
- 前端：`App.tsx` 顶部一条（新 `BrowserBanner`，用全局事件流的 `browser` 事件，`caught_up` / `reset` 时重新 `GET /api/browser`）：只在 `disconnected` 时显示原因、「在 Chrome 打开 chrome://inspect/#remote-debugging 的开关后点重新连接」和按钮。
- 文档：workflow.md「Chrome MCP」加一条（2026-10-23）；agent-runtime.md 里「断开后等用户说重连」两处标注被替换。
- 测试：`BrowserSessionConnectionTest` 第二条（建立失败不重试）只去掉对 `needsUser` 和「reconnected」文字的断言；`PlaywrightMcpClientTest.serverExitingMidCallIsADisconnect` 按新规则改成下一次使用自动重连一次。新加一条：用过的连接断开 → 下一次自动重连成功；再断、重连失败 → 之后不再碰 MCP，`reconnect()` 才再连；状态和代数对得上。

必须保持不变：
1. 快照里的 ref 不跨连接：断开时清掉所有标签页记录（`disconnect()` 不动）—— `disconnectDuringActDropsTheTabOwnership`（不改）。
2. 同一时间只有一个浏览器动作：`BrowserSession` 的公开方法仍然全是 `synchronized`，建连接也在锁里。
3. 一条连接给所有 Job；连着的时候「重新连接」不关、不重开 —— `oneConnectionServesEveryJobInTurn`（不改）。
4. 写操作中途断开不在同一次调用里重放（自动重连只在下一次使用时）。
5. 申请 Agent 断开时停下等你，说明里写清楚要做什么；对话 Agent 这一步失败、接着做别的。
6. 只对自己的标签页取快照：重连后不读「当前标签页」，`ownsTab` 为 false，先新开或由 M 的规则认（不碰）。
7. 现有 MCP 调用序列不变：`BrowserSessionActTest`、`FakeFormScriptTest`、`PlaywrightMcpClientTest`（除上面改的一条）、场景测试；`AgentLoop` 的行为（上限、停止、只回文字、事件顺序）不动。
8. 建立失败（Chrome 没开开关、超时）记为断开，不自己反复重试 —— `aTimeoutWhileWaitingForTheUsersConsentCountsAsDisconnected`、`extensionConsentTimeoutIsADisconnect`、`callTimeoutIsADisconnect`。

### O 施工细节（审查后的 `find` 清洗、小项）

- `find` 清洗：`BrowserSession.find` 拿到 MCP 原文后只留 `### Result` 小节的正文（到下一个行首 `### ` 为止），丢掉 `Open tabs`、`Page`、`Modal state`、`Events`、`Ran Playwright code` 等其余小节；没有 `Result` 小节时返回空串。清洗在 `BrowserSession` 里做，`HarnessLoop.find` / `SessionLoop` 拿到的已经是干净的（C 级值遮挡照旧在它们那里做），日志本来就只记工具名和耗时。依据：0.0.83 `Response._build` 的小节顺序 Error / Result / Ran Playwright code / Open tabs / Page / Modal state / Events，`find` 的结果走 `addResult`（`### Result` 下面，快照行都以 `- ` 或缩进开头，不会出现行首 `### `）。
- JobPage：另订阅全局流，收到 `browser` 事件就重读（和 `?job=` 那条合并重读一样）；不往 `?job=` 发事件。
- `retryOnNextUse`：`retryLeft` 改成 `volatile`，方法不加锁（只在断开时置 true；和正在建立的连接碰上，最坏多留一次自动重连，用户刚发了消息，可以接受）。
- `connect()` 准备目录失败：抛 `BrowserException.Disconnected`（状态照旧记为断开、不留重连）。
- `GET /api/browser/tabs`（和转交）断开时 503，消息「浏览器断开了，先点页面顶部的「重新连接」。」；HandTab 显示这句（503 时不带状态码前缀）。
- 转交：`ApplicationStatus.status` 是 `done`（用户已经定下了：已提交 / 已投递 / 已结束 / 下线，见 `JobLedger.userDecided`）时 409「这个申请已经结束了，不能再把标签页交给它」。`failed` / `interrupted` 回复后能接着做，照旧能转交。
- `startPage`：转交的页 `select` 抛 `ToolFailed`（或 `TabLost`）时，页面说明照样写「用户把标签页 N（标题，网址）交给你了」，加「系统切过去时出错（原因），要用它就 browser_tabs select N」，然后照旧走自己的标签页 / 新开岗位链接。`Disconnected` 照旧往外抛。
- 测试：`BrowserSessionActTest` 加一条：假 MCP 的 `browser_find` 返回带 `Open tabs`（含用户的邮箱标签页）、`Page`、`Events` 的原文，`find` 结果只有匹配行，没有别的标签页的标题和网址。假 MCP 加一个场景键 `findText`（有它时 `browser_find` 原样返回它）。

必须保持不变：
1. `find` 仍返回模型要的匹配行（`findIsReadOnlyAndReturnsTheMatchingLines` 不改通过），只读、先核对标签页属于这个使用者，MCP 调用序列不变。
2. C 级值遮挡（`SnapshotRedactor.redact`）在 `HarnessLoop.find`、`SessionLoop` 里照旧。
3. JobPage 其余行为不变（`?job=` 订阅、合并重读、保底轮询、HandTab 列表只在点开时读）。
4. 「重新连接」接口语义不变：200 + 状态，连不上也是 200（`connect()` 换异常类型后 `reconnect()` 照旧接住）。
5. 转交的 TOCTOU 核对不变：`handOver` 里再列一次、序号 / 网址 / 没有主人，否则 409；只在前面加一个「申请已结束」的拒绝。
6. `BrowserSession` 只动 `find` / `retryOnNextUse` / `connect`（N 的认页部分不碰）；公开的浏览器方法除 `retryOnNextUse` 外仍是 `synchronized`。

## 怎么确认能用

- `mvn -B clean verify`、`npm run build` 通过；改坏的测试按新规则改。读到不属于自己的标签页是实际损失，这条要有测试。
- 你在本机：关掉 Chrome 的远程调试开关，界面顶部出现断开提示，申请 Agent 停下、对话 Agent 说这一步做不了；打开开关点「重新连接」，两个都能接着用。工作台把一个你自己开的页面交给 Agent，它能接着填；没交的页面它看不到。

## 实际结果

（施工后填写）

### L 实际结果（第 1 节）

- 新 `tools/BrowserState(state, reason, since, generation)`：`not_connected` / `connecting` / `connected` / `disconnected`。
- `BrowserSession`（只动连接部分）：`needsUser` 换成 `volatile BrowserState state` + `retryLeft`（断开后还能不能自己再连一次）+ 监听者。
  - `ensureConnected()`（公开，`synchronized`）：有连接且没断 → 返回；`disconnected` 且 `retryLeft` → 用掉、连；`disconnected` 且没有 → 抛 `Disconnected("还没有重新连上，上次断开的原因：…")`，不碰 MCP；`not_connected` → 连。`call()` 第一行就是它。
  - `connect()` 先发 `connecting`；进程起不来 / 目录准备失败 → `disconnected`、不留重连。第一次调用 MCP 答话（成功或 `ToolFailed`）→ `connected`，代数加一。调用中 `Disconnected`：原来是 `connected`（用过的连接）→ `disconnected`、留一次；原来是 `connecting`（就是那一次重连）→ `disconnected`、不留。`disconnect()` 照旧清掉所有标签页记录。
  - `retryOnNextUse()`：`disconnected` 时再留一次（用户消息用）。`reconnect()` 返回 `BrowserState`：连着就不动；否则留一次、发一次 `browser_tabs list`（结果丢掉，不进标签页记录），失败不抛。`state()`、`isConnected()` 不拿锁；`onChange(listener)`。
- `RuntimeEvents.browser(state)`：事件名 `browser`，`session` / `job` 为 null，只在全局流（`?session=` / `?job=` 的过滤器本来就不收）。`ApplicationConfiguration` 里 `browser.onChange(events::browser)`。`EventController` 写成 `{"session":null,"job":null,"browser":{"state","reason","since","generation"}}`。
- 新 `api/BrowserController`：`GET /api/browser`、`POST /api/browser/reconnect`（JSON `{}`，过 `LocalRequestGuard` 的写接口规则，不用改防护）→ `{"state":"disconnected","reason":"…","since":"2026-10-23T…Z","generation":1}`。连不上也是 200，看 `state`。
- `AgentRun`：去掉 `disconnected` 组件和 `reconnected()`，`waiting(message, questions, slots)` 去掉布尔参数（旧文件里的 `disconnected` 键被 `AgentRunStore` 忽略未知键）。`ApplicationStatus.disconnected` 保留，由 `ApplicationRunner.status` 推出：状态是 `waiting_user` / `interrupted`，而且 `browser.state()` 是 `disconnected`。
- `ApplicationRunner.execute`：去掉自己的重连分支，换成 `browser.retryOnNextUse()`；真正的连接在 `HarnessLoop` 第一次用浏览器时经 `ensureConnected` 建立，连不上走原来的 `disconnectedRun`。和计划原文「回复路径调 `ensureConnected`」的差别：没有在这里先连一次（否则要在两处写「连不上」的等你说明），效果一样——回复后只试一次。
- `HarnessLoop`（只动断开处理）：两处 `waiting(..., false)` 去掉参数；`disconnectedRun` 的说明改成「浏览器断开：<原因>。请确认 … 开关已经打开，然后回复（或者先点页面顶部的「重新连接」），我会接着做。」类注释一行。
- `SessionLoop`：删掉 `reconnectTried`、`reconnectIfNeeded` 和两处调用；`run(...)` 开头 `browser.retryOnNextUse()`；断开那一步的失败文字加「点页面顶部的「重新连接」或者回复」。类注释改成「重连不归这里管」。
- 前端：`api.ts` 加 `BrowserState`、`api.browser()`、`api.reconnectBrowser()`；`eventStream.ts` 的事件名加 `browser`、`LiveEvent.browser`；新 `BrowserBanner.tsx`（`App.tsx` 里，`Toasts` 后面）：订阅全局流，`browser` 事件直接用，`caught_up` / `reset` 时重新 `GET`；只在 `disconnected` 时浮在顶部，写「浏览器断开了（原因）。在 Chrome 打开 chrome://inspect/#remote-debugging 的开关后点重新连接。」和「重新连接」按钮，再连不上写「还是连不上」。`styles.css` 加 `.browserbar`（固定定位，不挤布局，原因最多两行）。
- 文档：workflow.md「Chrome MCP」加「断开以后」一条（旧规则划掉注明作废）；agent-runtime.md 两处「等用户说重连」划掉 / 改写。
- 测试：
  - 改：`BrowserSessionConnectionTest.aFailedConnectionIsNotRetriedUntilTheUserReconnects`（`needsUser()` → `state().disconnected()` / `connected()`，错误文字「reconnected」→「还没有重新连上」，调用序列和启动次数断言不变）；`aTimeoutWhileWaitingForTheUsersConsentCountsAsDisconnected`（同样只换 `needsUser()`）；`PlaywrightMcpClientTest.serverExitingMidCallIsADisconnect`（按新规则：断开后下一次 `open` 自动重连，启动 2 次，不再先抛一次）。
  - 新加：`BrowserSessionConnectionTest.aUsedConnectionReconnectsOnceOnTheNextUseAndThenWaitsForTheUser`（断开 → 下一次自动重连成功、代数 2；再断 + 重连失败 → 之后不再碰 MCP、启动次数不变；`retryOnNextUse` 后再连上、代数 3；连着时 `reconnect()` 不动；通知的状态序列）。单飞没有单独写并发测试：靠方法同一把锁，「失败后等着的调用不再连一次」就是上面「之后不再碰 MCP」那一段。
- 「必须保持不变」逐条核对：1 ✓ `disconnect()` 没动（M 加的转交清理也在里面），`disconnectDuringActDropsTheTabOwnership` 未改通过；2 ✓ 公开的浏览器方法仍全是 `synchronized`，`connect` 只在 `ensureConnected` 里（锁内）；`state()` / `isConnected()` 只读 volatile；3 ✓ `oneConnectionServesEveryJobInTurn` 未改通过（连着时 `reconnect()` 直接返回）；4 ✓ 断开的那次调用直接抛出，重连只在下一次 `call` 的 `ensureConnected` 里；5 ✓ `HarnessLoop` 仍 `ended(... disconnectedRun)` 等你，`SessionLoop.disconnected` 仍 `failed` 接着做；6 ✓ 断开清掉归属，`ownsTab` 为 false，重连后先 `open` / 转交（M）；7 ✓ `BrowserSessionActTest`、`FakeFormScriptTest`、`BrowserSessionTabsTest`、`PlaywrightMcpClientTest` 其余 13 条未改通过（单条连接时调用序列不多一次）；`AgentLoop` 没碰；8 ✓ 三条超时 / 同意超时的测试通过，建立失败不留重连。
- 验证：`mvn -B clean verify`（scratchpad 副本，含 M 当时的改动）退出码 0：surefire 624、failsafe 1，全过。`npm run build` 通过。真实 Chrome 里关掉开关看顶部提示、点「重新连接」：**未运行**。

### M 实际结果（第 2 节）

- `BrowserSession`（只动标签页部分）：
  - `tabs(owner)` / `select(owner, index)`（去掉 `sites` 参数）只列、只切到确定是它的标签页（认页规则 ①②③）；新的 `mine` 替换 `choosable`，不再给「没人占用、同一站点」的空闲页和 ④「可能是它的」候选。④ 记录仍在内部，挡住别人，下次 ①②③ 认出来才又列给它。
  - 删掉 `adopt`、`reclaim`、`Reclaimed`。`TabInfo` 去掉 `own`（列给模型的都是它自己的）。
  - 新 `unowned()`（没有确定主人的标签页，只给界面）、`handOver(owner, index, url)`（再列一次核对：序号在、网址相同、没有确定主人，否则 `HandoverRefused`；记给它；它已经有正在用的页时记成它的别的页，不偷换它脚下的页——正在跑时旧快照的 ref 不会落到新页上）、`takeHandover(owner)`（取一次就清；断开时清掉）。转交本身不切换、不取快照。
- `HarnessLoop`：`startPage` 先看有没有转交（有就 `select` 过去，页面说明「用户把标签页 N（标题，网址）交给你了。系统切过去了」）；再看自己的标签页；否则新开岗位链接。接着做（有 `lastPage.url`）而自己的标签页没了时，页面说明写系统不按网址认回、请模型顺带告诉用户可以在工作台上转交。运行中转交：下一次浏览器工具 / `browser_tabs` / 标签页丢失的结果后面附一句「用户把标签页 N（…）交给你了，要在它上面做，用 browser_tabs select N 切过去」。`tabLost` 只列它自己的别的页，没有就新开并提示转交。删掉只给 `reclaim` 用的 `siteUrls(String)`。
- 接口（新 `api/BrowserTabsController`，没碰 L 的 `BrowserController`）：
  - `GET /api/browser/tabs` → `{"tabs":[{"index":0,"title":"…","url":"…"}]}`。
  - `POST /api/jobs/{id}/application/tab` `{"index":1,"url":"…"}` → 200 `{"index":1,"title":"…","url":"…"}`；缺参数 400，没有申请线程 404，变了 / 有主人 409，浏览器断开 503；成功后 `changed(null, jobId)`。
- 提示词：`browser_tabs` 工具说明改成只列你自己的（含用户转交的），用户要你用他的页面时请他在工作台转交；`ApplyAgent`「新标签页」一条改成「你只看得到自己的标签页」，新加「用户说用我开着的那个页面」一条（`ask_user` 请他在工作台转交）；`ResearchAgent` 加同一情形（请用户在申请工作台转交，或者贴链接）。
- 前端：`api.ts` 加 `BrowserTab`、`freeTabs`、`handOverTab`；`JobPage` 右栏（有申请线程时）加「把一个标签页交给 Agent」：点「选标签页」拉列表、下拉选一个、「交给 Agent」，toast 结果，409 时自动刷新列表。
- `workflow.md`「Chrome MCP」加 2026-10-23 的规则（原来的 C006-fix-runtime-2 那句保留并注明被替换），「登录状态」那条加一句改法。`AgentRun.lastPage.url` 的注释改成不再用来认回。
- 测试（`BrowserSessionTabsTest`）：
  - 改：`theSameTab…`、`closingATab…` 只换成新签名 / `tabs("A")` 为空（原来断言 `reclaim` 不认回）；`resumingNeverReadsTheUsersOwnTab…` 改成邮箱连候选都不给模型（`tabs` 空、`select` 拒绝、只调了 `browser_tabs`、界面列表里有它）；`resumingReclaimsTheJobsOwnTabAfterALoginDetour` 改成 `afterALoginDetourTheJobsOwnTabIsListedAgainWhenItComesBackToTheSameSite`（自动认回删了；登录绕行回到同一站点后，它自己的页按规则 ③ 又列出来、能 `select`）。
  - 新加：`theUsersTabsAreNeverListedOrReadEvenOnTheSameSiteUntilHandedOver`（没新开时不能快照；同站点的用户页不列、不能选，没有 `select`，只取过新开那页的快照）；`aHandedOverTabBelongsToTheJobAndCanBeUsed`（重启后转交 → 能快照、能列；已有正在用的页时转交不偷换，`select` 过去；别人看不到、不能再交）；`handoverIsRefusedWhenTheTabChangedOrAlreadyHasAnOwner`（别人的、自己的、网址变了、序号不在都 409，别人的页照旧）。
- 验证：`npm run build` 通过。`mvn -B clean verify`（scratchpad 副本，含 L 当时进行中的改动）：surefire 624 条只有 `PlaywrightMcpClientTest.serverExitingMidCallIsADisconnect` 失败——那条是 L 的第 1 节要改的「断开后不自动重连」（L 清单里「除上面改的一条」），和标签页无关；排除它再跑，其余全部通过（`BrowserSessionTabsTest` 8 条全过），failsafe 1 条通过。真实 Chrome 里转交一页再接着填：**未运行**。
- 「必须保持不变」：1 ✓ `theSameTabWithAChangedUrlOnTheSameSiteIsStillThisJobs` 只换了签名，通过；2 ✓ `act` 的 CLICK 分支没动；3 ✓ `openingANewTabKeepsAnotherOwnersRecord…` 未改通过；4 ✓ 新测试断言用户页 / 别人的页不列、不快照、没有 `select`；5 ✓ `BrowserSessionActTest`、`BrowserSessionConnectionTest`、`FakeFormScriptTest` 未改通过（`PlaywrightMcpClientTest` 除 L 那条外通过）；6 ✓ `BrowserToolPolicy` 没动，`BrowserToolPolicyTest`、`BrowserToolsTest` 未改通过。

## 第 3 节：标签页改回提示词约束（2026-10-23）

- 依据：用户指出第 2 节和「审查后的结构修正」把「只碰自己的标签页」做成了代码硬约束，违反 AGENTS.md「Agent 开发原则」。规则不变，执行方式改成提示词 / skill；代码硬线只留不点最终提交、密钥不外泄。第 2 节和「审查后的结构修正」里代码执行的部分作废（整表对齐 N 没做完，本节不做）。
- 提示词：`ApplyAgent`「标签页」一条（只操作自己打开的、点击弹出的、用户明确交给你的；用户之前开的不碰；分不清就问用户；找不到正在用的页时 list / select 或 `browser_navigate` 重开岗位链接），「用我开着的那个页面」改成用户说清是哪一页（或在工作台转交）后 select；`ResearchAgent` 同一规则；`browser_tabs` 工具说明；skill apply-form「其它」加一条。
- `BrowserSession` 只留系统需要的记账：
  - 每个使用者正在用的那页（序号 + 最近网址），操作前先切回去；认页：序号和网址都对上 → 网址唯一 → 记下的序号还在（网址变了，留一句说明给模型）；都不成立就去掉记录，抛 `TabLost`（原话：找不到你正在用的标签页，list / select 或重新打开）。
  - 记在名下的页（新开的、点击弹出的、转交的），只用来在列表里标「你的」；只按网址认。
  - `tabs` 列出所有标签页（`TabInfo` 加 `yours`）；`select` 能切到任何序号；`unowned` / `handOver` / `takeHandover` 保留（转交只拒绝：序号不在、网址变了、别的使用者正在用）。
  - 正在用的页认不出时，`browser_navigate` 过了白名单就新开一页打开（模型「重新打开岗位链接」靠它）。
- 删掉：规则 ③④、`shrank`、`homes`、`sameSite`、`claim` / `Claim`、`release`、`mine`、`others`、`tabOwner`、`requireOwnTab`、`findResult`（`find` 返回 MCP 原文）。
- `HarnessLoop`：`tabLost` 不再自动列页 / 新开，只把原话交给模型（附转交说明）；`browser_tabs list` 的结果标「正在用」「你的」并重述规则；接着做时告诉模型上次做到的网址，可以 list / select 找回去，分不清时问用户。`SessionLoop` 没改（`TabLost` 照旧按 `IllegalStateException` 写成「失败：…」，`browser_navigate` 认不出时会新开）。
- 测试：`BrowserSessionTabsTest` 8 条改成 5 条（删掉逐条猜归属的 3 条：用户页滑进记下的序号、登录绕行、关页后同站点别人的页；其余改成新语义）；`BrowserSessionActTest` 删掉 `find` 清洗那条，`FakeBrowser.findText` 一并删掉。没有新增测试；最终提交的测试没动。
- 验证：`mvn -B -q clean verify` 退出码 0（surefire 621 = 625 − 4、failsafe 1）。前端没改。真实 Chrome、真实模型：**未运行**。
