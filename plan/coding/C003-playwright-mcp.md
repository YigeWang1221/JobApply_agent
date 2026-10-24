# C003 · Playwright MCP 接通：连接本机 Chrome、交给用户登录、快照、身份核对、断开重连

> 更正（2026-10-23）：本文按当时的决定以扩展模式（`--extension`）为默认。现在默认是远程调试模式（`--cdp-endpoint chrome`），不用 Playwright 扩展，见 workflow.md「Chrome MCP」。下面保留当时的施工记录。

- 对应 Master：[workflow.md](../master/workflow.md)「里程碑」第 2 段；「Chrome MCP」「登录状态」「提交保护」（本段只开放打开页面和只读工具）「遗漏检查」第 6 项（故障恢复）；[memory.md](../master/memory.md)（`checkpoints.sqlite`、申请线程 thread id = job id、`browser/sites.md`）
- 状态：**完成**（用户已验收，2026-10-22）。登录与身份的判断从 C004 起交给模型，`LoginPageDetector` 改为只作提示，见 [C004 要点](C004-harness-guard.md)。日期：2026-10-22。

本文件只写这一次施工做什么、怎么做、怎么验收。设计依据在上面两份 Master，这里不重复。

## 用户已定的 4 项（2026-10-22）

1. 「我登录好了」通过**最小的本地 HTTP 接口**告诉系统，以后前端直接复用。
2. 暂停和恢复**现在就用 LangGraph4j 的中断和 Checkpoint**，不先写一个临时的等待。
3. 自动测试用模拟的 MCP 服务端。真实连接**只做手动冒烟**：用户装好扩展、选一个测试网站，由用户本人登录和操作。
4. 环境用本机已有的；缺什么再装。`@playwright/mcp` 的版本在起草时重新查过（见「依赖」）。

## 目标

后端可以对一个已经转为申请的 Job 跑一条**登录接力线程**（thread id = job id）。可观察行为（输入 → 状态变化 → 输出）：

1. **开始**：`POST` 开始请求，带 project、session，以及可选的账号标识（可以是 UID、用户名、邮箱、手机号，也可以不填） → 用 stdio 启动 `@playwright/mcp --extension`。Chrome 里弹出扩展的连接页，由用户同意。系统打开 `job.md` 里的岗位链接，取一次快照。`prep_state` 从 `selected` 变为 `preparing`。
2. **交给用户登录**：不管有没有识别到登录页，每次都停下来（master：每次申请都交给用户）。识别到登录页、SSO 或验证码时，`prep_state` 改为 `needs_login`，原因写「检测到登录页」；没识别到时，原因写「请确认已登录」。图在 Checkpoint 处中断，状态可以用 `GET` 查询。
3. **「好了」**：`POST` 信号 `logged_in` → 图恢复执行，重新取快照（只读）。如果还是登录页，就再次中断，原因写「仍在登录页」；用户可以带 `override: true` 强制继续，以免识别规则误判把流程卡死。离开登录页后，`prep_state` 回到 `preparing`。
4. **身份核对**：在快照的可见文字里找用户给的账号标识。找到了就核对通过；没给标识或没找到，就再中断一次，请用户确认（`identity_confirmed`），或者回报账号不对（`wrong_account`：回到登录接力，丢弃旧的 ref）。
5. **结束**：核对通过后，写入 `browser/sites.md`（站点、账号标识、最近确认登录的时间、状态），线程结束，状态为 `ready`，`prep_state` 停在 `preparing`。第 3 段的填写从这里接着做。
6. **断开与重新同意**：浏览器调用时，如果连接断开（MCP 进程退出，或扩展断开），或者用户没有在扩展的超时时间内同意连接，线程就中断为 `browser_disconnected`。用户在 Chrome 里准备好后发送 `reconnected` → 系统关掉旧进程、启动新进程，用户再同意一次。重连后**先取快照只读核对**：当前页面还在岗位的站点上，就接着走登录或身份那一步；不在，就重新打开岗位链接（本段还没有任何表单写入，重新打开不会重放写操作）。
7. **应用重启后恢复**：Checkpoint 存在 `storage/checkpoints.sqlite`。后端重启后可以查到中断点，发信号后从中断点继续。旧的 MCP 进程已经不在了，所以第一次调用浏览器时重新连接，用户需要再同意一次。
8. **工具白名单**：本段只放行 `browser_navigate`（只允许 http / https）、`browser_snapshot`（不允许 `filename` 参数）、`browser_tabs`（只允许 `action=list`）。其它工具（点击、输入、填表、上传、按键、执行 JS 等）在发出之前就被拒绝，MCP 服务端收不到这些调用。启动参数不加 `--caps`，所以坐标点击、存储、DevTools 类工具本身就不会出现。

## 不做（留给后续里程碑）

- 点击、输入、填表、上传，以及点击守卫和提交词表：属于第 3 段。本段的白名单里没有任何写操作工具，所以还用不到点击守卫。
- 模型、Harness 循环、把 MCP 工具注册成 Spring AI `ToolCallback`、工具名加服务器前缀：本段没有模型调用方，到第 3 段再做。
- 关卡 A、关卡 B、填写报告、岗位核实：第 3–5 段。
- 浏览器全局串行队列只做最简单的形式：同一时间只允许一条登录接力线程在跑，第二条开始请求返回 409。多线程排队到真正有并发调用时再做。
- 对话线程（thread id = session id）、前端界面、`PLAYWRIGHT_MCP_EXTENSION_TOKEN`（免确认连接）：master 规定每次连接都由用户同意，所以本段不用这个 token。
- 不读取 Cookie、localStorage，不导出登录状态。快照全文不写入 Checkpoint、日志和文档，只保存 URL、标题和判断结果。
- 自动测试不连接真实 Chrome，也不访问外部网站。

## 环境（2026-10-22 在本机核实）

| 项 | 本机现状 | 处理 |
| --- | --- | --- |
| Node | `v22.13.1`（nvm，`~/.nvm/versions/node/v22.13.1/bin`），npm `10.9.2` | 已有；`@playwright/mcp` 0.0.83 要求 Node ≥ 18。不另装 |
| npx | 同上目录 | 已有。后端从 IDE 启动时 PATH 里可能没有 nvm 的目录，所以命令可以配置成绝对路径（`jobapply.browser.mcp.command`） |
| `@playwright/mcp` | 未全局安装 | **不全局安装**。用 `npx -y @playwright/mcp@0.0.83 --extension` 启动，第一次运行时 npx 会下载并缓存这个包（这是唯一的网络操作，只在手动冒烟时发生） |
| Playwright 浏览器扩展 | 未知 | 用户在日常用的 Chrome 里安装（README 指向 `microsoft/playwright` 仓库的 `packages/extension`）。具体安装入口在冒烟时由用户确认，结果记到「实际结果」 |
| Java / Maven | 原来是 Homebrew `openjdk@21`；Maven 在非交互 shell 里实际跑在 `openjdk` 27 上 | **用户决定升到 Java 25 LTS（2026-10-22）**：`brew install openjdk@25`（25.0.5），`pom.xml` 的 `java.version` 改为 25，卸载 `openjdk@21`。`openjdk` 27 是 Homebrew Maven 的依赖，所以保留；Oracle JDK 19（`/Library/Java/JavaVirtualMachines/jdk-19.jdk`）需要 sudo，由用户自己删除 |

从 `@playwright/mcp` 0.0.83 及其依赖 `playwright-core` 1.64.0-alpha 的源码中确认的事实（只读查看 npm 包）：
- 扩展模式在打开连接页后，**只等 30 秒**；超时报错 `Playwright extension did not connect within 30s ...`。这个时长只能用测试用的环境变量 `PWTEST_EXTENSION_CONNECT_TIMEOUT` 修改，属于未公开的接口，**本段不使用**。超时就进入 `browser_disconnected`，由用户重试。
- 工具返回的是 Markdown 文本，分成几个小节：`### Page`（包含 `- Page URL:`、`- Page Title:`）、`### Snapshot`（YAML 格式的可访问性树）、`### Modal state`、`### Error`。快照解析只依赖这几个小节标题，解析不出来就报错，不猜。
- `browser_navigate`、`browser_tabs` 的元数据都标为非只读；`browser_snapshot` 标为只读。打开页面必须用 `browser_navigate`，所以白名单里有它，但只允许 http / https 地址。

## 依赖（待确认）

| 用途 | 坐标 | 说明 |
| --- | --- | --- |
| MCP 客户端（stdio） | `org.springframework.ai:spring-ai-starter-mcp-client`（BOM 管理，2.0.1） | master 已定。传递引入 MCP Java SDK `io.modelcontextprotocol.sdk:mcp` 2.0.0。**不写任何 `spring.ai.mcp.client.*` 连接配置**，自动配置就不会在启动时建立连接；客户端由代码在需要时创建，因为连接要等用户在 Chrome 里同意，不能在应用启动时做。Maven Central 上已有 2.0.2，本段仍跟 Spring AI 2.0.1 对齐 |
| Checkpoint | `org.bsc.langgraph4j:langgraph4j-sqlite-saver:1.9.6` | master（memory.md「存储格式」）已定。传递引入 `org.xerial:sqlite-jdbc` 3.53.2.0。施工时在 `SQLiteSaver` 和 `SQLiteSaverV2` 之间，选能按 thread 恢复中断的那个，选择结果写进「实际结果」 |
| 本地 HTTP | `org.springframework.boot:spring-boot-starter-webmvc`（Boot 管理版本） | 新增。Boot 4 起 `starter-web` 改名为 `starter-webmvc`。测试用的 MockMvc 如果需要单独的测试 starter，同样用 Boot 管理版本 |
| `@playwright/mcp` | `0.0.83` | 2026-10-22 查 npm：`latest` 是 0.0.83（2026-09-28 发布），和之前查到的一样。版本写死在默认启动参数里，不用 `@latest` |
| 测试用 MCP 服务端 | 不新增 | 用 MCP Java SDK 自带的 `StdioServerTransportProvider` 写在测试源码里 |

## 要改的文件

类名在施工中可以微调，职责不变。**不新增子包**，都放在 architecture.md 里已有的包下面。

**`backend/pom.xml`**：加上表中三项。

**`jobapply.config`**
- `BrowserMcpProperties`：`jobapply.browser.mcp.command`（默认 `npx`）、`args`（默认 `-y @playwright/mcp@0.0.83 --extension`）、`profile-dir-name`（可选，对应 `--profile-dir-name`）、`call-timeout`（默认 60s，因为需要覆盖扩展的 30s 连接等待）。
- `StorageProperties`：`jobapply.storage.root`（默认 `../storage`，也就是在 `backend/` 下启动时的仓库 `storage/`）。只有调用接口时才会读写，应用启动时不初始化 storage。
- `application.yml`：`server.address: 127.0.0.1`，端口沿用默认值，可以覆盖。

**`jobapply.tools`**（浏览器 MCP 接入）
- `PlaywrightMcpClient`：启动或关闭 MCP 子进程；检查 `listTools` 里有需要的 3 个工具；`call(name, args)` 先过白名单再发出。子进程用 `env -i PATH=… HOME=… <command> <args>` 启动，只传 PATH 和 HOME（MCP SDK 的 stdio 传输默认会继承全部环境变量，用 `env -i` 清空，是为了满足 master「每个 MCP 子进程只拿到它需要的环境变量」）。进程退出、读写管道失败、超时，都转成 `BrowserDisconnectedException`。
- `BrowserSession`：全局唯一的连接持有者。没有连接时，第一次调用会建立连接；`reconnect()` 会关掉旧进程、启动新进程，并清空 ref。
- `PageSnapshot`：把 MCP 返回的文本解析为 URL、标题、快照 YAML 文本、modal state、error。
- `LoginPageDetector`：纯函数，输入 `PageSnapshot`，输出 `LOGIN | CHALLENGE | NONE` 以及命中的规则。规则见下方「关键规则」。

**`jobapply.harness.policies`**
- `BrowserToolPolicy`：本段的工具白名单和参数检查，拒绝时抛出 `ToolNotAllowedException`，错误信息里写明「不要重试」。规则写在代码里，不从配置或 Playbook 读取。

**`jobapply.workflow.state`**：`LoginHandoffState`（LangGraph4j `AgentState`，只放简单的值：project、session、job、url、account hint、`status`、`waiting_for`、`reason`、`page_url`、`page_title`、`detected`、`identity`、`last_error`）。

**`jobapply.workflow.nodes`**：`OpenPageNode`、`CheckLoginNode`、`CheckIdentityNode`、`ReconnectNode`、`FinishNode`，以及几个等待节点（`wait_login`、`wait_identity`、`wait_reconnect`，节点本身什么也不做，靠 `interruptBefore` 停住）。

**`jobapply.workflow.graphs`**
- `LoginHandoffGraph`：建 `StateGraph`、连接条件边，编译时设置 `checkpointSaver` 和 `interruptBefore(wait_*)`。
- `LoginHandoffRunner`：对外的入口，提供 `start`、`signal`、`status`。用一个单线程执行器执行图，这也是本段的「浏览器串行队列」，HTTP 请求因此不用等用户同意连接就能返回。信号和当前等待的内容不符时直接拒绝，Checkpoint 不变。

**`jobapply.workflow.checkpoints`**：`CheckpointStore`，在 `storage/checkpoints.sqlite` 上创建 sqlite saver。

**`jobapply.persistence`**
- `StorageLayout`：加上 `checkpointsDb()`、`browserSites()`。
- `SiteRegistry`：读写 `browser/sites.md`（按站点 origin 更新一行，开启 git 时提交）。

**`jobapply.api`**
- `LoginHandoffController`：3 个接口，见「关键规则 · HTTP」。
- `LocalRequestGuard`：拒绝带 `Origin` 请求头的请求，写接口要求 `Content-Type: application/json`。

**测试**：见「测试」。测试用的 MCP 服务端 `FakePlaywrightMcpServer` 放在 `backend/src/test/java/jobapply/tools/`。

**文档**：完成后补本文件的「实际结果」和「偏差」，更新 `dev-plan.md`、`plan/README.md` 的索引行、`dev-log.md`、`dev-recoding.md`；下方「回写 Master」获批后改对应 Master。

## 关键规则

### 图

```text
START → open_page → check_login ─┬─(登录页 / 验证 / 未确认)→ [wait_login] → check_login
                                 └─(已离开登录页)→ check_identity ─┬─ 匹配 → finish → END
                                                                   ├─ 待确认 → [wait_identity] ─┬ identity_confirmed → finish
                                                                   │                            └ wrong_account → [wait_login]
任意浏览器节点 ─(BrowserDisconnectedException)→ [wait_reconnect] ─ reconnected → reconnect ─┬ 仍在岗位站点 → check_login
                                                                                            └ 不在 → open_page
```

- 方括号是 `interruptBefore` 的等待节点。恢复的方式是：先 `updateState` 写入信号，再 `invoke(GraphInput.resume(), config)`。
- `check_login` 第一次执行时，就算没识别到登录页，也要进入 `wait_login`（每次都交给用户）。之后只有识别到登录页并且信号里没有 override 时，才会再次进入 `wait_login`。
- 能接收的信号：`wait_login` 只接受 `logged_in`（可以带 `override`）；`wait_identity` 只接受 `identity_confirmed` 和 `wrong_account`；`wait_reconnect` 只接受 `reconnected`。其它信号返回 409。
- `recursionLimit` 设为 50，防止误判导致无限循环（只有用户发信号才会让循环继续，所以正常情况下碰不到这个上限）。

### 与台账的衔接

- 开始时，Job 必须是 `phase=prep`，`prep_state ∈ {selected, preparing, needs_login, paused}`，否则返回 409。状态从 `selected` 或 `paused` 进入时，先转为 `preparing`。
- 识别到登录页时，`preparing → needs_login`（`actor=system`）；离开登录页时，`needs_login → preparing`（发信号的那一步，`actor=user`）。一律经过 `JobLedger`，所以事件、台账、git 提交都会跟着更新。
- 断开、等待身份确认这两种情况不改 `prep_state`：它们是这次运行的状态，不是申请的状态。

### 登录页识别（`LoginPageDetector`）

依次检查，命中一条就停：
1. **CHALLENGE**：快照文字里有 验证码、安全验证、滑块、人机验证、captcha、verify you are human、unusual traffic。
2. **LOGIN**：快照里有一个 `textbox` 的名字匹配 密码 / password / passcode。
3. **LOGIN**：页面 URL 的路径或主机名里有 `login`、`signin`、`sign-in`、`sso`、`passport`、`oauth`、`auth`、`cas`，**并且**快照里有 登录 / 登入 / Sign in / Log in / Continue with 一类的按钮或链接。
4. 都没命中：**NONE**。

只看快照里可以看到的文字，不读 Cookie。规则用表格驱动的测试固定。

### 身份核对（第 5 项用户已定，2026-10-22）

- 账号标识**可选**，由用户在开始请求里自己填写。各个站点的登录方式不同（邮箱、账号密码、公司自家的社交 app 等），所以 UID、用户名、邮箱、手机号都可以，也可以留空。系统不检查它是哪一种，只做两项检查：去掉首尾空格后不超过 128 个字符；不能含换行或 `|`，否则会写坏 `sites.md` 的表格。
- 匹配方法：去掉首尾空格，忽略大小写，在快照文字里做包含匹配。留空，或者页面上没有显示这个标识（比如用社交 app 登录后，页面只显示头像），就进入 `wait_identity`，由用户确认。
- `browser/sites.md` 原样保存用户填的标识；留空就写 `—`。`storage/` 不进主仓库的 Git，符合 `dev-limit.md` 中「隐私数据只放在 `storage/` 下被忽略的位置」的规定。标识不写进日志。

### `browser/sites.md`

```markdown
# 站点登录登记

| 站点 | 账号标识 | 最近确认登录 | 状态 | 核对方式 |
| --- | --- | --- | --- | --- |
| https://careers.example.com | user123 | 2026-10-22T10:00:00-04:00 | logged_in | matched |
| https://jobs.example.cn | — | 2026-10-22T11:00:00+08:00 | logged_in | user_confirmed |
```

按 origin 去重，每个 origin 一行，按 origin 排序。这个文件不是生成文件（以后会有人工补的列），所以不写「请勿手改」的说明；读的时候保留未识别的列。

### HTTP（只监听 127.0.0.1）

| 方法 | 路径 | 请求体 | 返回 |
| --- | --- | --- | --- |
| `POST` | `/api/jobs/{jobId}/login-handoff` | `{"project","session","accountHint"?}` | 202 + 状态；Job 不存在时 404；prep_state 不对或已有一条在跑时 409 |
| `GET` | `/api/jobs/{jobId}/login-handoff` | — | 200 + 状态；没有这条线程时 404 |
| `POST` | `/api/jobs/{jobId}/login-handoff/signal` | `{"session","signal","override"?}` | 202 + 状态；信号和当前等待的内容不符时 409 |

状态：`{"status": running | waiting | ready | failed, "waitingFor": login | identity | reconnect | null, "reason", "detected", "pageUrl", "pageTitle", "identity", "lastError"}`。

- 带 `Origin` 请求头的请求返回 403。浏览器里的网页能向 `127.0.0.1` 发请求，这一条防的是网页冒充用户发出「好了」；curl 和以后的前端开发服务器代理不会带这个头（前端在第 7 段再定，到时候改成允许名单）。写接口还要求 `Content-Type: application/json`，网页就不能用简单的表单绕过预检。
- 错误统一返回 `{"error": "<原因>"}`，不吞掉，也不返回 200。

### 子进程与日志

- 启动时只传 PATH 和 HOME；`--profile-dir-name` 配了才加。
- 日志里记录命令、参数、工具名和耗时，**不记录快照全文**（里面可能有姓名、邮箱等个人信息）。快照只在内存里用完就丢；Checkpoint 只存 URL、标题和判断结果。
- 应用关闭时关掉 MCP 子进程。

## 实现步骤（5 个垂直切片，每片结束时 `mvn -B clean verify` 全绿）

1. **MCP 连接与白名单**：加 pom 依赖，写 `BrowserMcpProperties`、`PlaywrightMcpClient`、`BrowserSession`、`PageSnapshot`、`BrowserToolPolicy`，以及测试用的 `FakePlaywrightMcpServer`。先跑通「启动假服务端 → listTools → navigate → snapshot → 关闭」这一条路径，再加上拒绝和断开。
2. **登录页识别**：`LoginPageDetector`，用表格驱动测试。
3. **图与 Checkpoint**：`LoginHandoffState`、各节点、`LoginHandoffGraph`、`CheckpointStore`、`LoginHandoffRunner`，接上 `JobLedger`。先跑通「开始 → wait_login → logged_in → 身份匹配 → ready」，再加上仍在登录页、override、身份待确认、账号不对、断开重连、重启恢复。
4. **sites.md**：`SiteRegistry`，在 `finish` 里写入。
5. **HTTP**：`LoginHandoffController`、`LocalRequestGuard`、`application.yml`，再加一个从 HTTP 走到底的场景测试。

## 测试

自动测试不访问网络，不启动真实 Chrome，不调用 npx。MCP 服务端一律是 `FakePlaywrightMcpServer`，用当前 JVM 的 `java` 和测试 classpath 以子进程方式启动，走真实的 stdio 协议。场景（每个 URL 返回什么快照、第几次调用后退出、第一次调用前等多久）从一个 JSON 文件读取；它收到的每次调用都追加记到一个文件里，测试可以据此断言「被拒绝的工具没有到达服务端」。

| 测试类 | 固定的行为 |
| --- | --- |
| `PageSnapshotTest` | 解析 Page / Snapshot / Modal state / Error 小节；缺少 Page 小节时报错 |
| `LoginPageDetectorTest` | 每条规则的命中和不命中；中英文；URL 有 login 但页面没有登录按钮时不算登录页；CHALLENGE 优先于 LOGIN |
| `BrowserToolPolicyTest` | 放行 3 个工具；拒绝 click、type、fill_form、file_upload、press_key、evaluate、run_code_unsafe、handle_dialog 和未知工具；navigate 拒绝 `javascript:`、`file:`、`data:`；snapshot 拒绝 `filename`；tabs 拒绝除 `list` 以外的 action；拒绝信息里有「不要重试」 |
| `PlaywrightMcpClientTest` | 连接假服务端并检查工具列表（缺少必需工具时报错）；navigate 与 snapshot 的往返；被拒绝的调用没有到达服务端；子进程只看得到 PATH 和 HOME（假服务端把它看到的环境变量**名**写进记录，不写值）；服务端中途退出 → `BrowserDisconnectedException`；`close()` 后子进程已退出 |
| `LoginHandoffRunnerTest` | 用 `@TempDir` 里的 storage 和固定时钟：正常路径（`prep_state` 依次为 selected → preparing，结束时 `ready`）；识别到登录页 → `needs_login` → `logged_in` → `preparing`；还在登录页时再次等待；override；身份匹配 / 待确认 / `identity_confirmed` / `wrong_account`；断开 → `wait_reconnect` → `reconnected` → 只读核对后接着走（重连后的第一个调用是 snapshot，不是 navigate，除非页面已经不在岗位站点上）；第二条开始请求返回 409；信号不符时被拒绝并且 Checkpoint 不变；**重启恢复**：新建一个 Runner（新的 saver 指向同一个 sqlite 文件），能查到中断点，发信号后能走完；Job 的 prep_state 不对时开始请求被拒绝，并且没有写任何文件 |
| `SiteRegistryTest` | 新增一行；同一 origin 更新而不是新增；保留未识别的列；开启 git 时提交 |
| `LoginHandoffControllerTest` | 202 / 200 / 404 / 409 / 400（账号标识超过 128 个字符、含换行或竖线时也返回 400；留空不报错）；带 `Origin` 返回 403；非 JSON 的写请求返回 415；错误体的格式 |
| `LoginHandoffScenarioIT` | 启动 Spring 应用（随机端口，storage 指向临时目录，MCP 命令指向假服务端）：先用 C002 的 API 建好一个转为申请的 Job，然后全部通过 HTTP 走完「开始 → 登录 → 断开 → 重连 → 身份确认 → ready」，最后检查 `job.md`、`events.jsonl`、台账、`sites.md`、`checkpoints.sqlite` 都存在，git 工作区干净 |

## 手动冒烟（真实 Chrome，由用户操作）

按 `dev-limit.md`，代理不登录、不操作外部网站。冒烟的步骤由用户执行；代理只准备本地的东西，并在用户执行完后整理记录。

1. 用户在日常用的 Chrome 里安装 Playwright 扩展，自己选一个测试网站（建议找一个有登录页、但不是真实投递目标的站点），把网址告诉代理。
2. 代理在一个被 git 忽略的临时 storage 里建 Project、Session，加入一个指向该网址的候选并转为申请（只写本地文件）。冒烟入口是一个**不会被自动运行**的测试类 `LoginHandoffManualSmoke`（名字不以 Test / IT 结尾）。它用真实的 `npx -y @playwright/mcp@0.0.83 --extension` 启动应用，监听 127.0.0.1 的固定端口，打印下面几步要用的 curl 命令，然后等待用户操作（最多 15 分钟）。用户运行命令：`mvn -B test -Dtest=LoginHandoffManualSmoke -Dsurefire.failIfNoSpecifiedTests=false -Djobapply.smoke.url=<网址>`。
3. 用户在 30 秒内同意扩展的连接 → 页面打开 → `GET` 看到 `waitingFor: login`。
4. 用户自己登录 → 发 `logged_in` → 看到身份核对的结果；需要时再发 `identity_confirmed`。
5. 断开测试：用户在扩展里断开连接（或关掉那个标签页）→ 看到 `waitingFor: reconnect` → 发 `reconnected` → 再同意一次 → 流程继续。
6. 看到 `status: ready` 后，冒烟测试打印 `job.md` 的 `prep_state` 和 `sites.md` 那一行，然后退出。
7. 记录：扩展的安装入口和名称、连接页长什么样、导航是开新标签页还是用用户交给它的标签页、真实快照能不能被 `PageSnapshot` 解析、登录页识别有没有误判。这些都写进「实际结果」；有误判就开一份追加修正的条目，经用户确认后再改。

冒烟没做就在 `dev-log.md` 写「未运行」，不写成通过。

## 验收标准

- `mvn -B clean verify` 全绿（新测试加上原有的 172 + 1 个）。
- 自动测试过程中没有网络访问，也没有调用 npx（测试用的 MCP 命令一律是假服务端）。
- 白名单以外的工具调用一次都没有到达 MCP 服务端（用假服务端的调用记录断言）。
- 用户完成手动冒烟，或者明确决定暂缓冒烟。

## 回写 Master（用户已确认，2026-10-22，已回写）

1. workflow.md「Chrome MCP」：版本那一列加上「2026-10-22 复查仍是 0.0.83」；写明扩展只等 30 秒，超时就进入断开重连。
2. workflow.md「登录状态」：「点『好了』」改成「通过本地 HTTP 接口发送 `logged_in` 信号（以后前端复用这个接口）」；补上「识别规则可能误判，用户可以 override」。
3. architecture.md「目录与模块」`api` 一行：写明只监听 127.0.0.1，拒绝带 `Origin` 头的请求。

## 待确认（用户已确认，2026-10-22：全部按建议）

1. **依赖表**三项（MCP client starter 2.0.1、sqlite-saver 1.9.6、webmvc starter）。建议：同意。
2. **和台账绑定**：登录接力线程必须挂在一个已经转为申请的 Job 上（thread id = job id），并且推进 `prep_state`。另一种做法是只传一个 URL、跑一条独立的线程，更简单，但和 master 的申请线程设计不一致。建议：绑定。
3. **白名单**：`browser_navigate`、`browser_snapshot`、`browser_tabs(list)`。`navigate` 不是只读工具，但没有它就打不开页面。建议：同意。
4. **override**：识别到仍在登录页时，允许用户强制继续。建议：允许，并在事件的 reason 里记下 `override`。
5. ~~账号标识~~ **已定（2026-10-22）**：可选，由用户自己填写，UID、用户名、邮箱、手机号都可以，也可以留空，`sites.md` 原样保存。规则见「关键规则 · 身份核对」。已经回写到 workflow.md「登录状态」。
6. **本地 HTTP 的防护**：只监听 127.0.0.1、拒绝 `Origin`、写接口必须是 JSON。建议：同意。
7. **子进程环境**：用 `env -i` 只传 PATH 和 HOME。建议：同意。
8. **冒烟方式**：用不会被自动运行的测试类在临时 storage 上启动应用，用户用 curl 发信号。建议：同意。

## 追加：远程调试模式与新开标签页（用户确认，2026-10-22）

用户在 Chrome 155 里打开了 `chrome://inspect/#remote-debugging` 的「Allow remote debugging for this browser instance」，冒烟改用 `--cdp-endpoint chrome` 连接（每次连接 Chrome 会弹窗确认）。读 `playwright-core` 1.64.0-alpha 源码发现：这种模式下 Playwright MCP 会把已打开的所有标签页都收进来，第一个成为当前标签页，于是 `browser_navigate` 会把用户已有的标签页换掉，重连后的快照也会读到那个标签页。这违反了 master「只操作它自己打开的标签页」。用户选择改成新开标签页：

- 白名单：`browser_tabs` 除了 `action=list`，还允许 `action=new`，但必须带 `url`，并且只能是 http / https。`select`、`close` 仍然拒绝。`browser_navigate` 保留（只允许 http / https），第 3 段在自己的标签页里翻页时会用到；本段不再调用它。
- 打开岗位：`open_page` 改用 `browser_tabs {action: new, url}` 新开标签页，新标签页随即成为当前标签页，后面的快照都只读这个标签页。
- 重连：不再对重连后的「当前标签页」取快照（在远程调试模式下，那可能是用户自己的标签页），改为直接回到 `open_page` 新开一个岗位页，然后照常交给用户确认登录。`resume_to` 不再需要，删掉。
- 每次开始或重连都会新开一个标签页，旧的留着，由用户自己关。
- 扩展模式下 `browser_tabs new` 是否可用，要等扩展模式冒烟时再确认。
- 测试：改 `BrowserToolPolicyTest`、`PlaywrightMcpClientTest`、`LoginHandoffRunnerTest`、`LoginHandoffScenarioIT` 的预期；假服务端支持 `browser_tabs new`。

## 追加：评审修复（用户要求现在修，2026-10-22）

三条评审意见逐条对照代码后都属实。每条先补一个复现测试，确认它失败，再修。

1. **P1：重启后跨 Job 读取页面。** 原因：`BrowserSession` 只记「有没有自己的标签页」，不记是哪个 Job 的；`signal()` 只检查有没有在执行的线程，不检查另一个 Job 是否还在等待。A 在等待时应用重启，B 开始并新开了 B 的标签页；A 发 `logged_in` 后，`check_page` 读到的是 B 的页面，可能核对通过并返回 ready。修法：
   - `BrowserSession` 记住标签页属于哪个 Job（`open(url, jobId)`）；`snapshot(jobId)` 遇到标签页不属于这个 Job 时拒绝。
   - `check_page` 发现标签页不是本 Job 的（包括新连接上还没有标签页），回到 `open_page` 重新新开。
   - `signal()` 和 `start()` 一样：另一个 Job 正在等待时返回 409。
2. **P2：身份确认绕过了暂停。** 原因：只有 `start()` 检查 `prep_state`，`signal()` 和 `finish` 都不检查。修法：`signal()` 在写任何东西之前，先检查 Job 仍是 `phase=prep` 并且 `prep_state ∈ {preparing, needs_login}`，否则返回 409；`finish` 在写 `sites.md` 之前再检查一次，不符就让这次运行失败，不写登记。
3. **P2：override 缺少审计事件。** 原因：只有 `prep_state` 真的变化时才写事件，Job 本来就是 `preparing` 时，用了 override 不会留下任何记录。修法：`JobEvent.Type` 新增 `login_override`；只要识别规则认为仍在登录页、而用户带了 override 继续，就由 `JobLedger.recordLoginOverride` 追加一条事件（from / to 都是当前状态，reason 写命中的规则），不改 `job.md`。Session 的自动标签把它算作 `apply`。C004 如果去掉 override，这类事件也随之不再产生，历史记录保留。

结果（2026-10-22）：补了 3 个复现测试（`LoginHandoffRunnerTest` 的 `afterRestartAnotherJobsTabIsNeverReadForThisJob`、`signalsAreRejectedOnceTheJobLeftTheHandoffStates`、`overrideIsAuditedEvenWhenPrepStateDoesNotChange`），原有的 override 测试也加了审计事件的断言。修复前这 4 个按预期失败；修复后在 `backend/` 用 JDK 25 执行 `mvn -B clean verify`，退出码 0：surefire 256、failsafe 2，均无失败，没有调用 npx。改动：`BrowserSession`（`open(url, jobId)`、`snapshot(jobId)`、`ownsTab(jobId)`）、`OpenPageNode`、`CheckPageNode`、`FinishNode`、`PrepStateSync`（`requireHandoffState`、`recordOverride`）、`LoginHandoffRunner.signal`、`LoginHandoffGraph`、`JobEvent.Type.LOGIN_OVERRIDE`、`JobLedger.recordLoginOverride`、`SessionRepository`（`login_override` 算作 `apply`）；`PlaywrightMcpClientTest` 改用新签名，并加了「为另一个 Job 新开的标签页不能读」的断言。

## 实际结果（2026-10-22）

**代码**（5 片按顺序做完；每片结束时跑一次测试）
- `pom.xml`：新增 `spring-boot-starter-webmvc`、`spring-ai-starter-mcp-client`（2.0.1，传递引入 MCP Java SDK 2.0.0）、`langgraph4j-sqlite-saver` 1.9.6（`sqlite-jdbc` 由 Boot 管理，实际解析为 3.53.2.1）。`java.version` 按用户要求从 21 改为 25（见「偏差」）。
- `harness.policies`：`BrowserToolPolicy`、`ToolNotAllowedException`。
- `tools`：`BrowserLaunch`、`PlaywrightMcpClient`、`BrowserSession`、`PageSnapshot`、`LoginPageDetector`、`BrowserException`（嵌套 `Disconnected`、`ToolFailed`）。
- `workflow.state`：`LoginHandoffState`、`LoginHandoffStatus`、`LoginHandoffSignal`。`workflow.nodes`：`NodeIds`、`OpenPageNode`、`CheckPageNode`、`WaitIdentityNode`、`ReconnectNode`、`FinishNode`、`PrepStateSync`、`Nodes`。`workflow.graphs`：`LoginHandoffGraph`、`LoginHandoffRunner`、`LoginHandoffException`（嵌套 `NotFound`、`Conflict`）。`workflow.checkpoints`：`CheckpointStore`（用的是 `SQLiteSaver`，`createTables(true)`，已有数据保留）。
- `persistence`：`SiteRegistry`；`StorageLayout` 加 `checkpointsDb()`、`browserSites()`。
- `domain`：`Transition.ChangePrepState` 加可选的 `reason`（保留原来的单参数构造），`JobLedger` 把它写进事件。
- `config`：`StorageProperties`、`BrowserMcpProperties`、`LoginHandoffConfiguration`；`application.yml` 写了默认值（`server.address: 127.0.0.1`、`npx -y @playwright/mcp@0.0.83 --extension`、`call-timeout: 60s`、`storage.root: ../storage`）。
- `api`：`LoginHandoffController`、`ApiErrors`、`LocalRequestGuard`。
- 测试：`BrowserToolPolicyTest`、`PageSnapshotTest`、`LoginPageDetectorTest`、`PlaywrightMcpClientTest`、`SiteRegistryTest`、`LoginHandoffRunnerTest`、`LoginHandoffControllerTest`、`LoginHandoffScenarioIT`；辅助类 `FakePlaywrightMcpServer`、`FakeBrowser`、`LocalApi`，`TestStorage` 加 `open()`。手动冒烟入口 `LoginHandoffManualSmoke`。

**施工时确认的 LangGraph4j 行为**（先在会话临时目录写了小样验证，再动手）
- `interruptBefore` 的节点每次走到都会中断，恢复后再次走到同一节点仍会中断；新建图实例、指向同一个 sqlite 文件，能读到中断点并继续（重启恢复）。
- 图走到 END 后，`SQLiteSaver` 会释放这个线程的 Checkpoint，之后 `getState` 报 `Missing Checkpoint`。所以图在最后加了一个 `done` 等待点：线程停在那里就是 `ready`，重启后也能查到，第 3 段从这里接着走。
- 同一线程再次 `invoke(新输入)` 会从 START 重新开始，但会合并上次的状态，所以 `LoginHandoffState.initial()` 给所有键都设了初值。

**验证**
- 在 `backend/` 用 JDK 25 执行 `mvn -B clean verify`，退出码 0：surefire Tests run: 251（原有 172 + 新增 79），failsafe Tests run: 2（原有 1 + `LoginHandoffScenarioIT`），Failures / Errors / Skipped 均为 0。`LoginHandoffManualSmoke` 没有被运行。
- 构建日志里 30 次「启动浏览器 MCP」全部是 JDK 自带的 `java`（假服务端），没有一次是 `npx`；自动测试没有访问网络。
- 白名单以外的工具一次都没有到达假服务端（`PlaywrightMcpClientTest`、`LoginHandoffScenarioIT` 用调用记录断言）。
- **手动冒烟：已运行（2026-10-22，用户全程监控）**，见下一节。

## 手动冒烟结果（2026-10-22，真实 Chrome）

- 环境：Chrome 155.0.8059.86，用户打开了 `chrome://inspect/#remote-debugging` 的远程调试开关；`npx -y @playwright/mcp@0.0.83 --cdp-endpoint chrome`；测试网址由用户指定：`https://careers.oppo.com/university/oppo/campus`（OPPO 校园招聘首页）。登录、确认账号都由用户完成；代理只在用户告知后才发信号。
- 第一次运行失败，还没有连接 Chrome：`jobapply.storage.root: ../storage` 在真实的 Web 应用里不能绑定成 `Path`（Spring 会把它当成 Servlet 资源路径）。在 `backend/` 下正常启动应用也会这样失败。原来的启动测试用的是 MOCK 环境，没有发现。修复：`StorageProperties` 改成用 String 绑定，再转成 `Path`；`JobApplyApplicationTest` 改用真实端口启动并断言根目录；冒烟类改用命令行参数传配置（builder 的 `properties()` 优先级低于 application.yml）。
- 第二次运行通过（`BUILD SUCCESS`，492 秒）：
  1. 开始：第一次 `browser_tabs` 用了 11.9 秒（包含 npx 启动和用户在 Chrome 里的确认），新开了一个标签页，标题解析正确，`detected: NONE`，停在 `waitingFor: login`。
  2. 用户登录后发 `logged_in`：只读快照，登录检查通过；没填账号标识，所以停在 `waitingFor: identity`。登录后站点自己的标题变成了「… - undefined」。
  3. 断开测试：用户在 Chrome 那边关掉的东西没有断开远程调试连接（再次开始时 745 ms 就新开了标签页）。代理随后结束了 MCP 子进程，模拟崩溃，然后发 `logged_in`：60 秒后识别为断开，停在 `waitingFor: reconnect`，`lastError` 是超时。
  4. 发 `reconnected`：启动了新的 MCP 进程，新开了一个岗位页，回到 `waitingFor: login`。**这一次 Chrome 没有再弹出确认。**
  5. `logged_in` → `identity_confirmed`（用户确认了账号）→ `ready`。`prep_state` 是 `prep:preparing`；`sites.md` 写入 `https://careers.oppo.com | — | … | logged_in | user_confirmed`；冒烟用的 storage 的 git 工作区干净；结束后没有残留的 MCP 进程。
- 发现：
  - 远程调试模式下，开关打开期间，**只有第一次连接要用户确认，之后的连接（包括重连）不再确认**。这和 master「断开后重新同意一次」不一致，只有扩展模式能做到。
  - 新开标签页的改法有效：所有操作都在系统自己新开的标签页里（本次共新开 3 个），用户已有的标签页没有被换掉（需要用户确认）。
  - 断开时的提示原来只写了「扩展」，已改成两种模式都适用的说法。
  - 登录页识别：OPPO 校园首页登录前后都是 `NONE`，因为这个页面本身不要求登录。真实登录页的识别这次没有测到。
  - 扩展模式没有测（用户没有装扩展）；扩展模式下 `browser_tabs new` 能不能用，仍然未知。
- 用户决定（2026-10-22）：远程调试模式作为正式支持的连接方式写进 master（workflow.md「Chrome MCP · 两种连接方式」「登录状态」），扩展模式仍是默认。

## 偏差

- **Java 21 → 25**：施工中用户要求升级本机 Java（2026-10-22）。代理装了 `openjdk@25`（25.0.5），改了 `pom.xml`、base.md、C001 备注、`AGENTS.md`、README，卸载了 `openjdk@21`。卸载时 Homebrew 顺带自动清掉了它判断已经没人用的几个包，日志里看得到的有 `fdk-aac`、`jpeg-xl`、`highway`；输出被截断，前面是否还有别的包不确定。`openjdk`（27）是 Homebrew Maven 的依赖，保留；Oracle JDK 19 需要 sudo，留给用户自己删。用户的 `~/.zshrc` 里 `JAVA_HOME` 和 `PATH` 还指向已卸载的 `openjdk@21`，需要用户手动改。
- 节点类名：计划里的 `CheckLoginNode` 和 `CheckIdentityNode` 合并成了 `CheckPageNode`，因为两者要用同一次快照，而快照全文不能放进 Checkpoint；另外新增了处理身份信号的 `WaitIdentityNode`、和 `done` 等待点（原因见上）。
- 信号枚举放在 `workflow.state`，不放在 `workflow.graphs`，这样节点就不依赖 graphs 包。
- 「同一时间只允许一条在跑」的实现：执行中或在等待的线程都算在跑；对同一个 Job 重新开始可以丢弃它上一次的运行；还没有取消接口，所以另一个 Job 一直在等待时，只能先把它走完（或重启应用）。
- 控制器测试没用 MockMvc（Boot 4 要另加测试 starter），改用 JDK `HttpClient` 打随机端口，顺带测到了 127.0.0.1 上的真实监听。
- 用户审阅后的调整（2026-10-22）：撤回了 8 个 `package-info.java` 的一行说明（它们仍写着「这一步没有类型」）；4 个异常类合并成 2 个文件，两种情况改为嵌套子类，调用方仍然按类型捕获。之后 `mvn -B clean verify` 退出码 0，surefire 251、failsafe 2，均无失败。
- 已知限制：MCP 进程崩溃时，客户端要等到 `call-timeout`（默认 60 秒）才会判断为断开。扩展断开时 MCP 进程还在，会直接返回错误，不受这条影响。连接错误按 `@playwright/mcp` 0.0.83 源码里的报错片段识别，真实报错要冒烟时核对。

