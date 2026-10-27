# C011-fix · 一个标签页没响应，整个浏览器工具卡住

- 依据：用户（2026-10-24）：Session s-2026-10-24-003 里 Agent 既看不到已有标签页，也开不了新页，每条指令等 60 秒后报 `browser_tabs 调用失败 … TimeoutException within 60000ms`。
- 记录里的经过：18:17 前浏览器调用都正常；闲置约 1 小时；19:19 `browser_navigate` 等了 61 秒失败；19:23 自动重连（新的 MCP 进程）又等 60 秒失败；之后状态停在断开，浏览器调用和 web_fetch 改用浏览器的路径全部失败。
- 不做：换掉 Playwright MCP；自己写 CDP 客户端；改标签页归属规则。
- **用户选 B（2026-10-24）**，加「两条路都要做」那部分；用户的指令同时算对 master 里相应说法改动的确认。

## 原因（读 @playwright/mcp 0.0.83 源码 + 本次记录）

1. **不是 CDP 管道断了。** `60000ms` 是 Java 端 MCP SDK 的请求超时（`application.yml` 的 `call-timeout: 60s`），意思是 MCP 服务 60 秒没有回话。管道真断时 Playwright 马上返回 `browser has disconnected` / `Target closed`，不会等。
2. **为什么不回话。** 我们连的是用户 Chrome 的默认 context，里面是用户所有的标签页。Playwright MCP 在 `browser_tabs` 和**每个工具响应的末尾**都对每一页调 `page.title()`（`Tab.headerSnapshot`），新连接时 `connectOverCDP` 还要等每一页初始化（`_waitForAllPagesToBeInitialized`），这几处都没有超时。只要有一页的渲染进程不答（闲置久了被 Chrome 冻结 / 节省内存休眠、页面卡死、挂着对话框），所有会话的所有调用都卡住。焦点模拟只开在 Agent 自己的页上，用户的页和重连之后的旧页都没有。（推断：闲置 1 小时后被冻结或休眠的后台页；这次没记下是哪一页。）
3. **我们把「卡住」当成「断开」。** 超时 → `BrowserException.Disconnected` → 关掉服务进程、重连 → 新进程连同一个 Chrome，又卡在同一页 → 记为断开、不再自动重连，提示用户去查远程调试开关（方向不对）。每次重连都是一条新 websocket，Chrome 还要再弹一次允许。
4. 0.0.83 就是 npm 上的最新版，没有上游修复可以升级。

## 改法（两条路；用户选了 B）

**两条路都要做：别把卡住当断开。**

1. MCP 请求超时单独认出来（不并进 `Disconnected`）：不关服务进程、不重连；状态原因和给模型 / 用户的话写实情：「Chrome 里有标签页没响应，浏览器工具被它卡住了」，做法是在 Chrome 里把闲置很久的页点开或关掉（或重启 Chrome），然后点「重新连接」。「重新连接」先用原来的保活会话问一次，能答就恢复，答不了才重建。
2. 普通调用的超时缩短（例如 30 秒，`--timeout-navigation` 同步调到 25 秒，让 MCP 自己的导航超时先到）；只有建立连接（等用户点允许）还用 60 秒。

**A. 继续连用户的 Chrome（小改）**：只做上面两条。用户那边建议关掉 Chrome「设置 → 性能 → 节省内存」，或把常用招聘网站加进「始终保持这些网站处于活动状态」。局限：用户 Chrome 里任何一页卡住，浏览器工具仍会卡住，只是看得懂、能恢复。

**B. JobApply 用自己的 Chrome 窗口（结构修正，推荐）**：MCP 不再连用户正在用的 Chrome，改用 `--user-data-dir storage/cache/chrome-profile`（持久 profile，被 Git 忽略）自己启动 Chrome。用户第一次在这个窗口里登录招聘网站，之后登录一直保存。
- 浏览器里只有 Agent 的页。Playwright 自己启动 Chrome 时默认带防节流参数（`--disable-renderer-backgrounding`、`--disable-background-timer-throttling` 等），后台页不会被冻结；不再有允许提示；焦点模拟、「用户的页不碰」这类规则可以简化。
- 代价：各招聘站要在新窗口里登录一次；「把我 Chrome 里开着的页交给 Agent」改成在 JobApply 窗口里打开那个网址。
- C011 第 5.4 节比较过的 C 是「每个 Worker 独立 context / profile，没有登录」，持久 profile 当时没比较。选 B 要改 `plan/master/base.md`、`architecture.md` 里「本机 Chrome、用户自己登录」的说法，需要用户确认。

## 怎么确认能用

- `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -B -q clean verify` 退出码 0。
- 用户本机：浏览器闲置一段时间（或手动让某页卡住）后再让 Agent 用浏览器：A 下几十秒内得到「有标签页没响应」的说明，处理那一页后点「重新连接」能接着用，没有反复弹允许；B 下闲置后直接能用。

## 实际结果（2026-10-24，方案 B + 共同部分）

**B：JobApply 自己的 Chrome 窗口**
- `application.yml`：去掉 `--cdp-endpoint chrome`，改成 `--browser chrome`（本机 Google Chrome，有窗口），加 `--timeout-navigation 25000`；`call-timeout: 30s`，新增 `connect-timeout: 60s`。
- `BrowserMcpProperties.toLaunch` 像 `--output-dir` 一样追加 `--user-data-dir <storage>/cache/chrome-profile`（`StorageLayout.chromeProfileDir()`）；参数里已经有 `--cdp-endpoint` / `--extension` / `--user-data-dir` 时不追加（想改回 A 只改配置）。目录在 `storage/*` 下，被 Git 忽略；`clearOutputDir()` 只清 `playwright-mcp/`，不碰它。
- `BrowserLaunch` 加 `connectTimeout`：等服务进程监听端口、保活会话（第一次调用冷启动 Chrome）用它；使用者的会话用 `callTimeout`。`Server.openKeeper()` 是带长超时的保活会话。
- `Server.close()` 先 SIGTERM、最多等 5 秒（等 npx、node 和 Chrome 都退出，让 Chrome 正常写完资料目录）再强制结束。

**读 0.0.83 源码确认的事实**（`playwright-core/lib/coreBundle.js`）
- `--shared-browser-context` + HTTP：第一个会话第一次调工具时 `createPersistentBrowser` → `launchPersistentContext(userDataDir)` 只启动一次（`sharedBrowserPromise`），之后的会话 `connectToBrowserEndpoint` 连同一个浏览器、用同一个 context（`browser.contexts()[0]`）。最后一个会话关闭时关掉 Chrome，所以保活会话照旧要留着。
- 有窗口时没有空闲自动关闭（`idleTimeout` 只对 headless 生效）。
- 用户关掉窗口（macOS 上 Chrome 还在）：下一次 `ensureTab` 新开一页，会再出窗口。用户退出 Chrome（Cmd+Q）：各会话的 backend 收到 `disconnected` 被丢掉，下一次调用时 MCP **自己重新启动 Chrome**，不报断开；我们这边旧标签页认不出（`TabLost`），模型重新打开。冒烟里结束 Chrome 主进程验证过。
- 资料目录被占（另一个 Chrome 在用 / 开了两个 JobApply）：MCP 先等 5 秒，再报 `Browser is already in use for <dir>`。原来这条是普通工具错误，`connect()` 会当成连上了；现在加进 `CONNECTION_ERRORS`（连同 `is not installed`、`launchPersistentContext`），算断开，并补一句「把那个 Chrome 完全退出后点重新连接」。重连前 `drop()` 先关旧服务进程（它启动的 Chrome 一起关）。
- 反自动化检测：`validateBrowserConfig` 对 chromium 加 `--disable-blink-features=AutomationControlled`（`navigator.webdriver` 为 false）；这个版本的 `chromiumSwitches()` 里已经没有 `--enable-automation`，持久启动时应该也不会出现「受自动化软件控制」的提示条（推断，冒烟时没有截图核对）。还会带 `--use-mock-keychain`、`--password-store=basic`、`--disable-sync`（Chrome 里不能登录 Google 账号同步）、`--disable-extensions` 被 MCP 去掉（扩展可以装）。站点仍可能凭别的特征（CDP 痕迹、新 profile 没有历史）识别自动化，这次不做绕过。
- 防节流：Playwright 自己启动 Chrome 时带 `--disable-renderer-backgrounding`、`--disable-background-timer-throttling`、`--disable-backgrounding-occluded-windows`，后台页不被冻结。焦点模拟留着（无害）。

**共同部分：卡住和断开分开**
- `BrowserException.Stalled`（新）：`PlaywrightMcpClient.send()` 的异常链上有 `TimeoutException`（MCP SDK 请求超时）就抛它，不再并进 `Disconnected`。说明写实情：「X 调用 N 秒没有回应：浏览器卡住了，多半是 JobApply 的 Chrome 窗口里有页面没响应…连接没有断」。
- `BrowserState` 加 `stalled`。`BrowserSession`：使用者的调用、保活会话列标签页、`sharedAlive` 遇到 `Stalled` → 记 `stalled`，不关进程、不重连、不清记录；之后任何一次调用答话就回到 `connected`（代数不变）。**服务进程已经退出时的超时仍算断开**（施工时发现：Streamable HTTP 下服务端死掉，客户端只会等到超时，不会马上报错；原有测试 `serverExitingMidCallIsADisconnect` 抓到的）。
- `reconnect()`：`stalled` 时先用保活会话问一次，答话了就恢复（不重建）；还是不答才 `lost()` → 关掉重开（Chrome 一起重开，标签页没了，登录还在）。
- 申请 Agent（`HarnessLoop`）遇到 `Stalled`：这一步失败、停下等你，等待说明写卡住和怎么办（看 JobApply 的 Chrome 窗口，把卡住的页面关掉或点一下、处理对话框，再回复或点「重新连接」）。对话 Agent（`SessionLoop`）：这一步失败，结果里叫模型先别再用浏览器、告诉用户怎么办，接着做别的。`/api/browser/tabs` 和转交遇到卡住返回 503 和对应说明。
- 前端：`BrowserState` 类型加 `stalled`；顶部状态条在 `stalled` 时也显示（「浏览器卡住了」+ 怎么办 + 重新连接），断开时的话改成「点重新连接会重新打开 JobApply 的 Chrome 窗口（登录还在）」。
- 时间：动作 15 秒 + 等待页面 settle 0.5 秒 + 导航后等 load 最多 10 秒 ≈ 26 秒，导航 25 秒，都在 30 秒内；超过 30 秒才算卡住。

**措辞**：去掉面向用户和模型的「chrome://inspect 远程调试开关」「允许提示」说法（`SessionLoop`、`HarnessLoop`、`BrowserBanner`、`application.yml` 注释）；登录提示改成「在 JobApply 的 Chrome 窗口里登录（每个网站一次，之后会记住）」（`ApplyAgent`、`ResearchAgent`、`ControlTools`、`ResearchTools`、skill `apply-form` / `verify-official`）；「请在 Chrome 里检查并提交」等改成「JobApply 的 Chrome 窗口」（`ApplicationRunner`、`HarnessLoop`）；「用我开着的那个页面」补一句用户自己的 Chrome 里的页看不到、请他贴网址。标签页归属 / 转交的代码没动。

**改的文件**：后端 `tools/`（`BrowserException`、`BrowserState`、`BrowserLaunch`、`BrowserSession`、`PlaywrightMcpClient`、`ControlTools`、`ResearchTools`）、`config/BrowserMcpProperties`、`persistence/StorageLayout`、`harness/runtime/`（`HarnessLoop`、`SessionLoop`）、`agents/`（`ApplyAgent`、`ResearchAgent`）、`api/`（`BrowserController` 注释、`BrowserTabsController`）、`workflow/ApplicationRunner`、`application.yml`、两个 skill；测试 `PlaywrightMcpClientTest`（`callTimeoutIsADisconnect` 改成 `callTimeoutIsAStallNotADisconnect`；启动参数那条改成 B 的参数并断言 `--user-data-dir`、两个超时）；前端 `api.ts`、`BrowserBanner.tsx`。

**验证**
- `mvn -B -q clean verify` 第一次退出码 1：`DeleteSessionProjectTest` 的存储快照里多了一个 playbook 写入中的临时文件（`.SKILL.md…tmp`，和本次改动无关的竞态）；再跑一次退出码 0。`npm run build` 退出码 0。
- 本机冒烟（临时资料目录、只开 127.0.0.1 的本地页面，不访问外网、不登录）：Chrome 有窗口启动（第一次连接 1.4 秒），开页、`browser_tabs list`、转交列表（只有 Chrome 起始的 `about:blank`）正常；关掉再启动，资料目录还在（`Local State`、`Default/Preferences`），第二次照常启动，输出目录被清空。卡住：本地页面让渲染进程死循环 → 另一个使用者的快照 8 秒（冒烟里调成 8 秒）报 `Stalled`，状态 `stalled`，标签页记录还在、没有重启；页面还卡着时点「重新连接」→ 问一次 15 秒不答 → 关掉重开，`connected` 第 2 次。结束 Chrome 主进程（模拟 Cmd+Q）→ 下一次调用 MCP 自己重开 Chrome，我们报 `TabLost`、再开页正常。冒烟结束后没有留下 Chrome 进程。
- 用户本机真实招聘网站：未运行。

**偏差**：`architecture.md` 里没有讲浏览器怎么连，没改。`dev-limit.md` 里「用户在本机 Chrome 里自己登录」这条边界没变，没改。

**留给以后**（这次不做）
1. 标签页转交在 B 下用处变小（只能转交 JobApply 窗口里用户自己开的页）；焦点模拟、`bringToFront` 兜底在不冻结后台页之后可能多余。要不要简化，等用户用一段时间再定。
2. `ConcurrentBrowserManualCheck`、`ApplicationManualSmoke` 还是按连用户 Chrome / 扩展写的手动检查，没改。
3. 招聘站的反自动化检测：真实跑时看有没有站点拦；要处理时先看 MCP 自己的 `--config` 里的 `launchOptions`（例如 `ignoreDefaultArgs`）能不能做到。
4. 只靠会话 Cookie（不带过期时间）的网站，Chrome 重启后要重新登录（Chrome 默认不恢复会话 Cookie）。
5. 用户退出 Chrome 后 MCP 自己重开时，保活会话的 backend 要到它下一次调用才重建；这期间如果所有使用者的会话都关了，MCP 会把新开的 Chrome 关掉，下一次调用再开（只影响标签页，不影响登录）。

**用户第一次用要做的**：不用再开 `chrome://inspect` 的远程调试开关。JobApply 第一次用浏览器时会自己打开一个 Chrome 窗口；在这个窗口里把要用的招聘网站各登录一次（Agent 遇到登录页也会用 ask_user 提醒），之后会记住。资料目录在 `storage/cache/chrome-profile/`，删掉就要重新登录。同一时间只开一个 JobApply（两个会抢同一个资料目录）。

## 撤回方案 B（2026-10-24）

- 依据：用户试用后指出：另起的空白 Chrome 没有用户的 profile 和 Google 账号，搜索、同步、匹配都用不了，还被网站拦截、电脑变卡。项目的前提是 Agent 在用户正在用的 Chrome 里工作（已写进 `AGENTS.md`）。推荐 B 是错的：卡住的根因（Playwright MCP 碰到一页不答就全体等待）在「连用户 Chrome」下用「卡住 ≠ 断开」就能处理，不需要换浏览器。
- 撤回：`--browser chrome` 和 `--user-data-dir`（`StorageLayout.chromeProfileDir`、`BrowserMcpProperties` 的追加）、自己启动 Chrome 的错误识别和提示、关进程时等 Chrome 写资料的逻辑；提示词、skill、界面里「JobApply 的 Chrome 窗口」全部改回「Chrome」，远程调试开关的说明恢复；`plan/master/*` 和 README 恢复到改动前。
- 保留（即方案 A）：`BrowserException.Stalled` / 状态 `stalled`，超时不重连、不清记录，「重新连接」先问原连接；`call-timeout: 30s`、`connect-timeout: 60s`、`--timeout-navigation 25000`；顶部「浏览器卡住了」条（文字改成在 Chrome 里把久没看的、卡住的标签页点一下或关掉）。
- 上面「实际结果」里关于 B 的部分作废。`storage/cache/chrome-profile/` 是那次试用留下的目录，没有删，用户决定。
- 验证：`backend/` 下 `mvn -B -q clean verify` 退出码 0；`frontend/` 下 `npm run build` 通过。用户真实 Chrome：未运行。

## 标签页全部可见（2026-10-24，用户指令）

- `OwnerBrowser.tabs()` 列出 Chrome 里所有的标签页（浏览器里的序号），`TabInfo` 加 `others`（别的使用者记下的页）；`select(index)` 按上次列出时的网址找到那一页，能切到任何一页，还不是自己的就记在名下。转交返回浏览器里的序号。
- 提示词（`ApplyAgent`、skill apply-form、`browser_tabs` 工具说明）：别的任务在用的页不要碰；用户自己开着的页，用户让用时再用，Agent 自己 list 找到再 select；上次做到一半的页可以 select 回去。重启后的说明不再叫用户去工作台转交。
- `plan/master/workflow.md`「Chrome MCP」补一句改回。`storage/cache/chrome-profile/` 已按用户指令删除。
- 验证：`backend/` 下 `mvn -B -q clean verify` 退出码 0（`BrowserSessionTabsTest` 按新行为改）。用户真实 Chrome：未运行。

## 浏览器越用越卡、抢焦点（2026-10-24）

- 依据：用户反馈 Mac 用一段时间后越来越卡（CPU 不高），Chrome 时不时跳到最前面。
- 原因（之前的排查，读 @playwright/mcp 0.0.83 源码和 Chromium 源码）：
  1. Agent 开的标签页从不关：白名单只有 list / new / select，`BrowserSession.owners` 也从不去掉。
  2. 焦点模拟每次开页 / 切页都 `newCDPSession(page)` 新建一个 CDP 会话、从不断开。Chromium 的实现是 `IncrementCapturerCount(stay_hidden=false)`：页面一直算「可见」，不节流、不冻结、照常渲染，只有会话断开、标签页关掉、CDP 连接断开才撤销。后台页越积越多、都在全速渲染 → GPU 和内存压力、换页，整台 Mac 卡。暂停、结束的使用者还留着 MCP 会话，Playwright MCP 每个会话给每一页记请求和事件。
  3. 抢焦点：MCP 的 `browser_tabs select` 每次都 `page.bringToFront()`，我们动作超时后的「切前台」兜底也是；CDP `Page.bringToFront` 在 macOS 上最终 `[NSApp activateIgnoringOtherApps:YES]`，把 Chrome 拉到所有应用前面。新开标签页（`Target.createTarget`）不激活。
  4. 每次动作前多一次 `browser_tabs list`；MCP 每次调用还对每一页取 `page.title()`（上游行为，改不了）。
- 用户的决定（2026-10-24，一次做完）：停下时关自己开的页、释放 MCP 会话；焦点模拟只留在正在用的那页；去掉抢焦点（含我们的切前台兜底）；少列一次标签页；接口里客户端断开的 Broken pipe 不再记 ERROR。

### 实际结果

**1. 关页、释放会话**
- 白名单：`browser_tabs` 本来就只由系统发，`checkTabs` 加 `close`（必须给非负整数 index；不给 index = 关当前页，不允许）。
- `BrowserSession.Leave`：`KEEP_ALL`（等你、出错）、`KEEP_CURRENT`（停在提交前）、`CLOSE_ALL`（调研一次做完、申请彻底结束）。`leave(owner, keep)`：按它关掉这个使用者**自己开的**页（`browser_tabs new` 开的、点击弹出的），关掉留下那页上的焦点模拟，再关掉它的 MCP 会话；记录留着，接着做时新会话里按网址找回、重新开焦点模拟。`release(owner)` = `CLOSE_ALL` + 从 `owners` 去掉。浏览器没连着（断开、卡住、还没连）时不碰浏览器、不去连，只关会话；不抛异常，出错记日志。
- 不关的页：用户转交的、模型 `select` 过去用的用户自己的页（`Tab.opened = false`）。一页的网址在浏览器里不止一个、别的使用者也记着这个网址、或者自己记着两页同一个网址时认不准，**不关**（宁可留着）。
- 什么时候调：
  - 申请（`ApplicationRunner.work`）：每次运行停下后，`ready` → `KEEP_CURRENT`（填好的表留给用户审阅），等你 / 出错 → `KEEP_ALL`。
  - 申请彻底结束：`ApplicationRunner.closeTabs(jobId)` → `release`；在跑或排队时记下，停下时再关。调用处：`JobController` 的已提交、不投了（giveUp）、岗位下线（已转申请）；`SessionLoop` 的 `mark_submitted`、`record_status`（之后不在准备阶段）；`SessionRunner.deleteProject` 删掉的 Job。`SessionLoop.Applications` 加 `closeTabs`。
  - 调研（`SessionRunner`）：每次运行结束 `leave(session, CLOSE_ALL)`，下一次要用时再开；删除 Session（`forget`）时 `release`。
- 文件：`harness/policies/BrowserToolPolicy`、`tools/OwnerBrowser`（`Tab.opened`、`leave` / `closeOwn`）、`tools/BrowserSession`（`Leave`、`leave`、`release`）、`workflow/ApplicationRunner`、`workflow/SessionRunner`、`harness/runtime/SessionLoop`、`api/JobController`。

**2. 焦点模拟只在正在用的页**
- `PlaywrightMcpClient.FOCUS_ON`：每页最多一个 CDP 会话，存在这个 MCP 会话的 page 对象上（`page.__jobapplyFocus`），再开时复用；会话断了重开一次；失败只返回一句话、不抛出（页面关掉时的报错会被认成连接断开）。`FOCUS_OFF`：`setFocusEmulationEnabled(false)` 再 `detach()`。page 对象每个 MCP 会话各一份（源码：每个会话 `browserType.connect(endpoint)` 自己连共享浏览器），所以只会关掉这个使用者自己开的。
- 开：开页、切到自己的页 / 弹窗、会话重建后找回自己的页（原来的 `focus()`，不变）。关：切走之前（`switchTo`、`open` 先 `unfocus()`）、停下时（`leave`）；关掉的页不用关（标签页关了 Chrome 自己撤销）。
- 源码里看到的：服务端 `CDPSessionDispatcher` 在客户端断开时**不会** detach 它的 CDP 会话（`server/dispatchers/cdpSessionDispatcher`），所以关 MCP 会话之前一定先关焦点模拟，原来那种只关会话的做法会一直留着。

**3. 不抢焦点**
- 新资源 `backend/src/main/resources/browser/no-bring-to-front.cjs`：`exports.default = async ({page}) => { Object.getPrototypeOf(page).bringToFront = noBringToFront; }`（改在 Page 原型上，这个 MCP 进程里所有页一起生效）。`BrowserMcpProperties.toLaunch` 启动时把它写到 `storage/cache/playwright-init/`（不放输出目录，那里每次连接都清空），追加 `--init-page <绝对路径>`。
- 在 0.0.83 源码（`playwright-core/lib/coreBundle.js`）里核对的：
  - `--init-page <path...>` 帮助写「TypeScript file」，实际是 `Tab._initialize` 里 `const { default: func } = require(initPage); await func({ page })`；Node 22.13 不剥 TS 类型，所以用 CommonJS `.cjs`。路径必须存在（`validateBrowserConfig` 启动时查）。
  - 每个会话 `ensureBrowserContext` 对 `browserContext.pages()` 里已有的页和之后的 `page` 事件都建 `Tab`，所以 CDP / 共享上下文模式下每个会话、每一页（含连上时已经开着的页）都跑一次。
  - `selectTab`：`await tab.page.bringToFront(); await tab.updateWebMCPTools(); this._setCurrentTab(tab)`，`bringToFront` 变成空函数后当前页照样设好。`_initialize` 是异步的，新会话里第一次 select 某页时理论上可能早于这一页的 init 跑完；改在原型上之后只要进程里任何一页跑过一次就全部生效。
  - 进程里别的 `bringToFront` 调用只在 recorder / dashboard，我们不用。
- 去掉 `PlaywrightMcpClient.BRING_TO_FRONT` / `bringToFront()` 和 `OwnerBrowser.afterTimeout` 里的切前台；诊断和直接点击保留，错误说明去掉「系统把这一页切到了前台」。`C011-concurrent-workers.md` 第 5.1 节和 K1 补了一句。

**4. 少列一次标签页**
- 会改页面的操作（导航、点击、输入、填写、选择、上传、按键、设日期）之前**仍列一次**：自己的页在两次调用之间被用户关掉时，MCP 已经换到旁边那页（可能是用户的或别人的），不先核对就会写到别人的页上；用返回核对只能事后发现，写已经发生了。这是指令里「不列就保不住的情况」，只留在这里。
- 只读的操作不先列：快照（`snapshot()`、模型的 `browser_snapshot`）、`browser_wait_for`、`browser_find`、填写前的检查（`inspect`，紧接着的填写会先核对）。调用之后用返回里的「Open tabs」/「Page」核对（`OwnerBrowser.verify`）：MCP 只在标签页有变化时附这两节（只有一页时只有 Page），都没有 = 没变。规则和原来一样：当前页是别人记下的网址，或者标签页变少了、自己的网址也不在了 → `TabLost`；否则认作同一页换了网址、留说明。操作之后的那次快照也核对，但网址变了是操作带来的，不留说明；「变少了」还要换到的是原来就在的页才算被关（别人同时关了一页、自己点进新网址不算）。
- 白名单拒绝的操作先查、不再先列标签页。返回里同一序号出现两次（工具结果和 Open tabs 都列）时只算一次。
- 次数：取快照 2 → 1 次；查找 2 → 1；填写 / 键入（带填写前检查）5 → 4；点击、导航仍是 3（列表 + 动作 + 快照）。

**5. Broken pipe 日志**：`api/ApiErrors.unexpected` 开头：异常链上有 `AsyncRequestNotUsableException`（客户端已经走了，Spring 7 + Jackson 3 包成 `HttpMessageNotWritableException … Broken pipe`）时记一行 DEBUG、返回 null（不再写响应）。不按「broken pipe」文字认，往外调 MCP / 模型时真的断了仍是 500。

**测试**：`FakePlaywrightMcpServer` 多标签页模式支持 `browser_tabs close`（关当前页时换到旁边那页，和 MCP 一样），带快照的返回在不止一个标签页时附「Open tabs」。按新行为改：`BrowserSessionActTest` 三处、`PlaywrightMcpClientTest` 两处（调用序列少了只读前的列表；close 不给 index 被拒）、`BrowserToolPolicyTest`（close 只由系统按序号发）。新增 `BrowserSessionTabsTest.stoppingClosesOnlyTabsTheOwnerOpenedAndKeepsTheFormForReview`（停在提交前留下表单、转交的页和用户的页从不关；彻底结束关掉自己开的页）；启动参数测试加 `--init-page` 的断言。

**验证**：`backend/` 下 `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -B -q clean verify` 退出码 0（合并了 C016 之后的整棵树）。用户真实 Chrome：未运行。

**偏差 / 留给以后**
- 第 4 条只砍掉了只读操作前的列表，写操作前的保留（原因见上）。
- 应用重启前开的页不认识，不关。认不准的页（同一网址开了几页）不关。
- 服务端 CDP 会话在 MCP 会话断开时不 detach：会话坏掉重建（`BrowserSession.use` 里的 `dropClient`）时那页的焦点模拟会留到标签页关掉；这种情况少见，没处理。

**用户在自己 Chrome 上看什么**：申请停在提交前时只留填好的那页（弹窗、多开的页没了）；点「已投递」、不投了、岗位下线后那一页也关掉；调研一次做完后它开的页关掉；转交给 Agent 的页、自己开的页一直不动。Agent 切标签页、动作超时时 Chrome 不再跳到别的应用前面。用一段时间后 Mac 不再越来越卡（活动监视器里 Chrome 的 GPU / 内存不再一路涨）。

## 卡住不再停下申请（2026-10-25）

- 依据：用户否决「浏览器卡住就停下申请等你」：多一个断点，违反 AGENTS.md（不加停顿和确认）。
- 改法（协调代理改的代码，这里只记录）：`HarnessLoop.stalled` 现在返回一个普通的失败工具结果，由模型决定重试、取快照、重新打开页面还是问用户；`startPage` 里卡住时结果后面补一句叫模型再 `browser_navigate` 一次；`stalledRun` 删掉。`SessionLoop.stalled` 的话去掉「先别再用浏览器工具」。上面「共同部分」里「申请 Agent 遇到 Stalled：这一步失败、停下等你」作废。
- 验证：`backend/` 下 `mvn -B -q clean verify` 退出码 0（那次改动之后）。

## 连不上：休眠标签页卡住连接（2026-10-25）

- 依据：用户界面显示「浏览器断开了（60 秒内没有连上 Chrome（MCP 没有回话）…）…还是连不上」。Chrome 开着、9222 在听，但渲染进程很少：大部分标签页在睡（被节省内存丢弃、冻结，或者恢复后一直没加载）。

**原因**（读 0.0.83 `playwright-core/lib/coreBundle.js`，并在 scratchpad 里自己起的 headless Chrome for Testing 153 上复现；没连用户的 Chrome）
1. 连接时等每一页（已验证）：`connectOverCDP` → `CRBrowser.connect` 最后 `await browser._waitForAllPagesToBeInitialized()`（39640、39684），等用户 profile 里**每一页**的 `CRPage` 初始化完（`FrameSession._initialize`，38832：`Page.getFrameTree`、`Runtime.enable`、`Runtime.runIfWaitingForDebugger`，还要等第一次非空导航提交）。没加载的页、挂着 alert 的页、卡死的页、崩溃的页都初始化不完。这一段在 `progress.race` 里（44415，`_connectOverCDPImpl`），所以受 `--cdp-timeout`（默认 30000）限制：30 秒后报 `TimeoutError: async initializeServer: Timeout 30000ms exceeded.`，而且之后每次调用都重新连、再等 30 秒。复现：后台页用旧式丢弃（`--disable-features=WebContentsDiscard` + chrome://discards 丢弃，等于恢复后没加载的页）、alert 页、死循环页、崩溃页，各自都让第一次 `browser_tabs list` 30 秒报错。新式（原地）丢弃和 `Page.setWebLifecycleState frozen` 的页不卡。
2. 之后每次调用等每一页（已验证）：MCP 每个调用的末尾（67390）和 `browser_tabs`（69409）对每个标签页 `Tab.headerSnapshot` → `page.title()`（66480），客户端 `kNoTimeout`（61971），服务端 `frame.title` 要页面执行脚本、没有上限（25667）。连上以后只要有一页的渲染进程不答，所有会话的所有调用都不回话。复现：连上后让一个后台页死循环，之后 `list`、`new`、`navigate` 全部 45 秒无回应。
3. 用户看到的是 60 秒（Java 的 `connect-timeout`，保活会话第一次调用没回话），不是 30 秒的 Playwright 报错，说明 MCP 60 秒都没回话，和第 2 条（连上以后某页取标题不答）的表现一致（推断：是哪一页、它为什么初始化之后不答，没在用户机器上核对）。两条都会让连接失败，都要修。
4. 之前的做法：连接失败后不留自动重连，点「重新连接」又是同样的等，所以一直连不上。

**改法**
- 新资源 `backend/src/main/resources/browser/skip-unresponsive-tabs.cjs`：Node preload。`BrowserMcpProperties.toLaunch` 像 init page 一样写到 `storage/cache/playwright-init/`，放进 `BrowserLaunch.preload`；`PlaywrightMcpClient.Server.start` 在 `env -i PATH=… HOME=…` 后面加 `NODE_OPTIONS=--require "<路径>"`。它挂在 `Module._load` 上，等 MCP 加载 `playwright-core/lib/coreBundle` 时打两处补丁，都走 0.0.83 导出的类：
  - `server.BrowserContext.prototype.initialize`（`CRBrowser.connect` 在等所有页之前先调默认 context 的它）里把那个浏览器类的 `_waitForAllPagesToBeInitialized` 换成「原来的等待，最多 5 秒」。没答的页先不管：服务端 `context.pages()` 只返回初始化好的页，所以它们不出现在标签页列表里；用户点开、它醒了之后初始化完，照常出现（复现里验证过）。
  - `tools.Tab.prototype.headerSnapshot` 第一次调用时把 `page.title` 换成「最多 2 秒，没答用上次的标题（没有时为空）；那一页上一次还没答完时直接用上次的，不再问」。一个卡住的页只让下一次调用多 2 秒，之后不再拖。
  - 打不上（导出变了、方法不在）时打一行日志，MCP 照原样运行；文件写不出来、启动时不在就不带 `NODE_OPTIONS`。preload 的日志（`[jobapply]` 开头）在 Java 里记 INFO：补丁打没打上、连接时跳过了几页。
- `BrowserSession.connect`：第一次建立连接失败以后留一次自动重连（下一次用浏览器时自己再连，不用点）；那一次也失败才停下等用户发消息或点「重新连接」（原来的「最多弹两次允许」不变）。
- 话改成实情：连接时 60 秒没回话 →「连 Chrome 等了 60 秒没有回应：Chrome 可能在等你允许这次连接（看看有没有弹出提示），也可能 Chrome 本身没有响应」；`initializeServer` 超时 →「30 秒内没有连上 Chrome：Chrome 可能在等你允许…」；`Could not connect to chrome` →「连不上 Chrome：Chrome 没有开着，或者没打开远程调试…」（`PlaywrightMcpClient.connectionProblem`，原文记进日志）。调用卡住（`Stalled`）的话改成「正在用的这一页没有响应」（别的页不再拖住调用）。顶部状态条：卡住时不再叫用户去点、关别的标签页；断开时说明下次用时会自己再连。`Stalled` 仍是普通的工具失败，不停下运行。
- 改的文件：`browser/skip-unresponsive-tabs.cjs`（新）、`config/BrowserMcpProperties`、`tools/BrowserLaunch`、`tools/PlaywrightMcpClient`、`tools/BrowserSession`、`application.yml` 注释、前端 `BrowserBanner.tsx`；测试 `BrowserSessionConnectionTest`（`aFailedConnectionIsNotRetriedUntilTheUserReconnects` 改成 `aFailedConnectionIsRetriedOnceOnTheNextUseThenWaitsForTheUser`）、`PlaywrightMcpClientTest` 启动参数那条加 preload 的断言。

**验证**
- 本地复现（scratchpad，headless Chrome for Testing 153，只开 127.0.0.1 的本地页面，直接跑 `npx -y @playwright/mcp@0.0.83 --cdp-endpoint http://127.0.0.1:9333 --shared-browser-context`；没连用户的 Chrome，进程都已结束）：

  | 情形 | 不带补丁 | 带补丁 |
  | --- | --- | --- |
  | 一个后台页旧式丢弃（没加载） | 每次调用 30 秒报 `initializeServer: Timeout 30000ms exceeded` | 第一次 `list` 5.1 秒，那页不在列表里；`new`、`navigate` 几十毫秒；把那页切到前台后它出现在列表里 |
  | 挂着 alert / 死循环 / 崩溃的页 | 同上，30 秒报错 | 第一次 `list` 约 5.1 秒，之后正常 |
  | 连上以后一个后台页死循环 | `list`、`new`、`navigate`、`snapshot` 全部 45 秒无回应 | 下一次 `list` 2.0 秒（那页标题用上次的），之后 5–50 毫秒；第二个会话同样能开页、导航 |
  | 按 Java 的方式启动（`env -i` + 带引号的 `NODE_OPTIONS`） | — | 补丁日志出现，丢弃页 + 死循环页同时存在时照常用 |
- `backend/` 下 `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -B -q clean verify` 退出码 0；`frontend/` 下 `npm run build` 退出码 0。
- 用户真实 Chrome：未运行。

**留给以后**
- 有睡着的页时每次新建共享连接多等 5 秒（只在连接时）。
- 跳过的页在它醒来之前，Agent 的标签页列表里看不到它；用户要 Agent 用某个睡着的页时，点一下那页就会出现。
- 补丁绑在 0.0.83 的内部方法名上；升级 `@playwright/mcp` 时看一眼启动日志里有没有「标签页补丁已打上」。
