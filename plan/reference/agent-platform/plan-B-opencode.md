# 方向 B 细化：基于 opencode，插件还是 fork（草案）

**状态：草案，等用户选方向。不是施工步骤，没有动代码。** 选定后再拆成 `plan/coding/` 下的 Coding Plan，并按第 8 节改 `dev-limit.md` / `plan/master/*`（需用户确认）。

日期：2026-11-25（同日第二版）。核对的 opencode 版本：

- 源码：`anomalyco/opencode@ea8aa5b05ef49af86d6c78dad0a19e2877e034e0`（2.4.1），本地克隆在 scratchpad；OpenAPI 用的是本机隔离 HOME 下 `opencode-cli serve` 导出的那份（146 个路径）。
- npm：`@opencode/cli-darwin-arm64@2.4.1`（单文件 240MB）、`@opencode/plugin@2.4.1`。V2 从 2.0.0（2026-09-11）到 2.4.1（2026-11-23）发了 51 个版本。

背景材料：[C-opencode.md](C-opencode.md)（opencode 全貌）、[B-our-agent.md](B-our-agent.md)（我们运行时里哪些必须留下）、[A-context-panel.md](A-context-panel.md)、[../research-agent-platform.md](../research-agent-platform.md)。

**这一版改了什么**：用户新加两条要求——(1) 前端界面保持现在的 React 样子（侧栏 Project → Session → 工作台、带过程块的对话、工作台、Dashboard、资料 / 设置页）；(2) 记忆系统用我们自己的（`storage/` 下 md + csv + jsonl、画像、公司 / 站点记录、lessons、RAG、Session 交接摘要），不用 opencode 的。所以推荐从 B1（OpenCode Desktop 当界面）改成 **B2：opencode 当无界面的 Agent 内核，界面是我们的 React，Java 是领域和记忆服务**。B1、B3 只留短说明（第 6、7 节）。

## 0. 直接回答

### 0.1 插件还是 fork

**仍然做插件，不 fork。** B2 下需要的东西比 B1 更少：不要 GUI 插件，只要

- opencode 原版 CLI（固定 2.4.1，随 App 打包）；
- 一份由 Java 生成的配置（agent、权限、MCP、skill 路径）；
- 一个约几十行的 TS server 插件（只做一件事：每次调模型前把旧的浏览器快照换成一句「旧快照已省略」，见 3.4）；
- Java 写的 MCP 服务（浏览器工具 + 业务 / 记忆工具）。

逐项复查 B2 用到的点，没有一处要改 opencode 源码：

| B2 要的 | opencode 原版怎么给 | 出处 |
| --- | --- | --- |
| 无界面运行、固定版本 | `opencode-cli serve --hostname 127.0.0.1 --port <随机>`，密码 `OPENCODE_PASSWORD`；配置里 `update: "disable"` 关自动更新 | `cli/web.mdx`、`config.mdx` 第 167 行 |
| 不读用户自己的 opencode / Claude 配置、AGENTS.md、skill | 启动时把 `HOME`、`OPENCODE_CONFIG_DIR`、`XDG_*` 指到我们自己的目录；`OPENCODE_DISABLE_CLAUDE_CODE=1`（跳过所有 CLAUDE.md 和 `.claude` skill）；`OPENCODE_DISABLE_PROJECT_CONFIG=1`（不向上找项目里的 `.opencode`、AGENTS.md） | `packages/cli/src/server-process.ts` 第 122–139 行、`core/src/config/discovery.ts` 第 70–180 行、`instructions.mdx` |
| 不用 opencode 自己的记忆 | 内置 `home-memory` 插件只在「会话目录 = HOME」且开了 `home_memory` 标志时才工作（`home-memory.ts` 第 57–59 行）；我们的工作目录不是 HOME，天然不生效；保险起见配置里再写 `-opencode.home-memory` | 同左 |
| 自定义 agent 和系统提示 | 配置 `agents.<id>`（`system`、`model`、`permissions`、`steps`、`hidden`），内置 build / plan / general / explore 设 `disabled` | `agents.mdx` |
| 工具只来自 Java | 远程 MCP `jobapply`（`oauth:false`、`codemode:false`）；其余内置工具在权限里 deny；每次 MCP 调用 `_meta["ai.opencode/sessionID"]` 带会话 ID | `mcp-servers.mdx`、`core/src/mcp/client.ts` 第 942 行 |
| 后端主动建会话、开并行申请 | `POST /api/session`（可带 `agent`、`model`、`location.directory`、`metadata`、`permissions`）+ `POST /api/session/{id}/prompt`；不同会话并发 | OpenAPI |
| 插话 / 打断后发 / 停止 | `prompt` 的 `delivery: "steer" \| "queue"`；`POST /interrupt`（`resume=true` 时打断后马上处理待插话）；`GET /inbox`、`PATCH /inbox/{id}` | OpenAPI |
| 问用户 | 内置 `question` 工具 → `form.created` 事件 → `POST /form/{id}/reply`；`DELETE /form/{id}` 取消 | `tools.mdx` 第 184–193 行、OpenAPI |
| 往上下文里挂记忆 | `PUT /api/experimental/session/{id}/instructions/entries/{key}`：会话级 `<context key>` 块，下一步边界生效，不占消息轮次 | OpenAPI、`instructions.mdx`「Ordering」第 6 条 |
| 交接摘要、辅助模型 | `POST /api/session/{id}/generate`（基于会话上下文生成，不改历史）；`POST /api/experimental/generate/text`（无状态，一次生成，可指定模型） | OpenAPI |
| 完整原文 | `GET /api/session/{id}/message` 返回整个会话的消息（分页）；`GET /context` 才是压缩后的那段 | OpenAPI |
| 实时过程 | `GET /api/event`（SSE）：`session.text.*`、`session.reasoning.*`、`session.tool.called/progress/success/failed`、`session.step.*`、`session.execution.started/succeeded/failed/interrupted`、`session.idle`、`session.inbox.*`、`form.*`、`session.compaction.*`、`session.usage.updated` | OpenAPI |
| 附件 | `prompt.files[].uri` 写 `file:///绝对路径` 或 `data:`；文本、PNG/JPEG/GIF/WebP、PDF 能给模型；单个 ≤20MiB | `attachments.mdx` |
| skill | 配置 `skills: ["<storage>/playbook/skills"]`；支持分组目录里的 `SKILL.md`（`components/date-picker/SKILL.md`），ID 取路径；按 agent 用权限 `skill` 规则限定可见 | `skills.mdx` 第 63、149、228 行 |
| 旧快照省略 | server 插件 `ctx.session.hook("context")`：只改本次请求，不改历史 | `build/plugins/server.mdx` |

fork 的理由在 B2 下更弱了：B1 时唯一想 fork 的是 Desktop 一级页面和去掉写代码界面，B2 根本不用 Desktop。

### 0.2 推荐

**推荐 B2。** 界面、记忆、业务数据都留在我们这边，opencode 只负责「循环 + 模型适配 + 压缩 + skill 加载 + 子会话并发」。

## 1. 目标架构（B2）

```
浏览器（我们的 React，现有页面）
   │  只连 Java：现有 REST + /api/events（SSE，带 Last-Event-ID 补发）
   ▼
Java 后台（Spring Boot）
   ├─ 对话网关（新）：把页面的发消息 / 插话 / 停止 / 回答 / 重新生成翻成 opencode HTTP 调用；
   │                 订阅 opencode /api/event，翻成我们现有的事件和 ChatMessage，
   │                 同时追加写 transcript.jsonl（记录原文）
   ├─ opencode 进程管理（新）：启动 / 重启 opencode-cli serve，随机端口和密码只在内存里
   ├─ jobapply MCP 服务（新，Streamable HTTP）：
   │     浏览器工具（守卫 / 占位符 / 脱敏在内，按 _meta 会话 ID 找 OwnerBrowser）
   │     业务和记忆工具：update_state、简历、JD、remember、search_experience、候选池、台账、
   │                    start_application、mark_submitted、后续段工具、读公司 / 站点记录
   ├─ 记忆注入（新）：按会话把画像摘要、Project 偏好、上次交接、申请状态、公司 / 站点记录
   │                 写成 opencode 的 instruction entries
   ├─ 浏览器层 ──▶ 共享 Playwright MCP（node）──CDP──▶ 用户自己的 Chrome
   ├─ 台账、记忆、RAG、导入、报告、Dashboard REST（不变）
   └─ 定时：后续提醒、查状态 → 建 followup 会话
          │ HTTP + SSE（127.0.0.1，Basic 密码）
          ▼
opencode-cli serve 2.4.1（随 App 打包，Java 拉起）
   ├─ 私有 HOME / 配置 / 数据目录：<storage>/agent/opencode/（SQLite 在这里）
   ├─ agents：session / apply / followup / helper（隐藏、无工具）；内置 agent 全部 disabled
   ├─ skills：直接指向 <storage>/playbook/skills
   ├─ 插件：snapshot-trim.ts（context 钩子，只省略旧快照）
   └─ MCP：只有 jobapply（远程，codemode:false）
```

要点：**浏览器只跟 Java 说话**，opencode 对前端不可见。

## 2. 前端怎么连 opencode

### 2.1 结论：经 Java 转一层，不在浏览器里用 `@opencode/client`

| | 经 Java（推荐） | 浏览器直连 `@opencode/client` |
| --- | --- | --- |
| 前端改动 | 小：`SessionView`、`ChatMessage`、`/api/events` 形状不变，对话组件基本不动 | 大：对话界面按 opencode 的 message / part / 事件重写（`MessageList`、`ProcessBlock`、`process.ts`、`useLiveUpdates`、`SessionPage`、`JobPage`） |
| 断线 | 沿用我们的事件日志（序号、`Last-Event-ID` 补发、`reset`）；Java 和 opencode 之间断线时，Java 用 `GET /message` 补齐 | opencode 的 SSE 不补发，慢了还会断（`/api/event` 描述写明 volatile），前端每次都要重拉 |
| 密码 / 跨域 | opencode 密码只在 Java 内存；不用开 CORS | 密码要下发到页面，要开 `--cors` |
| transcript 原文、台账联动、侧栏运行圆点、申请回报 | Java 本来就要看这些事件，一处做完 | Java 仍要另订一份事件做这些，等于两份消费 |
| 代价 | Java 要写一个翻译层（只覆盖用到的十几个接口和二十来种事件），opencode 升级时跟着核对 | 前端跟 opencode 升级 |

没有官方 Java SDK。Java 用 JDK `HttpClient` + Jackson 手写用到的接口，SSE 按行读；不从 146 个路径的 OpenAPI 全量生成（它标着 experimental）。

### 2.2 对话里的每个动作怎么对上

| 我们现在的 | opencode 里 | Java 做什么 / 前端改什么 |
| --- | --- | --- |
| 发消息 `POST /api/sessions/{id}/messages` | `POST /api/session/{oc}/prompt`（`text`、`files`） | Java 查 Session ↔ opencode 会话的对应（`session.md` 里存），没有就先建；前端不改 |
| 运行中插话（现在的 `inserted`） | 同一个 `prompt`，`delivery: "steer"`；待处理的看 `GET /inbox` 和 `session.inbox.*` 事件 | Java 把未送达的 inbox 项填进 `SessionView.inserted`；前端不改 |
| 打断并发送 | 先 `prompt(delivery: "steer")`，再 `POST /interrupt?resume=true` | 前端如果要单独一个「打断并发送」按钮，在 Composer 加一个动作；接口 Java 加一个参数 |
| 停止 `POST /stop` | `POST /interrupt`（不带 resume） | Java 收到 `session.execution.interrupted` 后照现在一样写一句结束说明；前端不改 |
| ask_user | 内置 `question` 工具：`form.created`（带问题、选项、是否多选）→ `POST /form/{id}/reply`；用户不按表单、直接在输入框打字时，Java `DELETE /form/{id}` 再把这句话作为新消息发 | 前端要改：`ask` 块从「只显示原话」变成能点选项 / 填字的卡片，`api.ts` 加 `answerForm`。模型也可以不用表单，直接用文字问然后结束本轮（和现在一样） |
| 重新生成 `POST /regenerate` | `POST /revert/stage`（`messageID` = 最后一条用户消息，`files:false`）→ `POST /revert/commit` → 用原文和原附件重新 `prompt` | Java 做；`transcript.jsonl` 里旧回复保留，标为「已重新生成」；前端不改。浏览器里已经做过的操作照旧不撤销 |
| 附件 | 页面照旧传给 Java，Java 存到 `storage/uploads/`，`prompt.files` 写 `file:///…` 绝对路径 | 前端不改（20MB 上限和现在一致） |
| 过程块：思考 | `session.reasoning.started/delta/ended` | Java 在 `ended` 时出一个 `thinking` 步骤 |
| 过程块：工具 | `session.tool.called` → `progress` → `success/failed`；MCP 工具名是 `jobapply_<tool>` | Java 去掉 `jobapply_` 前缀，出 `tool` 步骤（`running → done/failed`），`process.ts` 的工具文案照用 |
| Agent 的文字 | `session.text.started/delta/ended` | Java 在 `ended` 时出一段文字（和现在一样按段出，不做逐字流式） |
| 当前在做什么（`activity`） | `session.step.started`、`tool.called`、`reasoning.started` | Java 维护，和现在一样 |
| 运行开始 / 结束 / 出错 | `session.execution.started/succeeded/failed/interrupted`、`session.idle`、`session.retry.scheduled` | Java 更新 `status`、写结束说明 |
| 压缩 | `session.compaction.started/ended/failed` | Java 出一条状态（「已压缩较早的对话」）；前端最多加一种状态文案 |
| 申请线程回报 Session | 见 3.5：Java 在申请会话空闲时往 Session 发一条 `synthetic` 消息 | Java 记为 `kind: "report"`，前端本来就不显示 |
| 权限请求 | 我们的配置里不留 `ask`，正常不会出现；万一来了（`permission.asked`） | Java 出一张「允许 / 拒绝」小卡片，回 `POST /permission/{id}/reply`；前端加一个小组件 |
| 用量 | `session.usage.updated`（tokens、cost） | Java 记进 `SessionView`；界面要不要显示「上下文占用」由用户定，本计划不默认加 |

### 2.3 前端哪些变、哪些不变

- **不变**：`App.tsx` 侧栏和路由、`dashboard/*`、`ProjectPage`、`Profile`、`Assets`、`Sources`、`ImportReview`、`PersonalSettings`、`StorageLocation`、`RagSettings`、`CheckSettings`、`WorkerSettings`、`chat/MessageList`、`ProcessBlock`、`Composer`、`Markdown`、`eventStream`、`useLiveUpdates`、`useAttachments`、`SessionPage`、`JobPage` 的结构。
- **小改**：`process.ts` / `ProcessBlock` 加问用户表单卡片和权限卡片；`api.ts` 加 `answerForm`、（可选）打断并发送；`ToolCallSettings` 改成设置每个 agent 的 `steps`。
- **改数据来源、保留样子**：`ModelSettings.tsx`（838 行）。页面仍是「供应商列表 + key + 用途分配 + 思考强度」，但供应商和模型列表来自 opencode（`/api/model`、`/api/integration/*`、`/api/credential`，经 Java 转），ChatGPT 套餐登录用 opencode 的登录流程。主模型 = 各 agent 的 `model`；辅助模型 = `helper` agent 和压缩用的模型。连接测试改成用 `generate/text` 发一句最小请求。

## 3. 记忆和上下文

### 3.1 我们的记忆怎么到模型面前

三条路，全由 Java 管；opencode 只是把它们拼进请求。

1. **按需读：Java MCP 工具。** 现有的 `read_resume`、`read_jd`、`choose_resume`、`remember`、`search_experience`（本地 RAG）、`list_jobs`、读公司记录 / 站点记录 / lessons 等，原样挂到 `jobapply` MCP。写记忆仍走 `remember`，规则不变（C 级明文拒绝、默认写当前 Project）。
2. **常驻：instruction entries。** Java 在建会话时、以及相关数据变了时，调 `PUT /instructions/entries/{key}` 写这些块：
   - `profile`：画像摘要 + Project 偏好（替代 `FillContext` 的资料部分）；
   - `handoff`：新 Session 时放上一个 Session 的交接摘要和未完成事项；
   - `state`：申请状态视图（`StateView` 的文字）；`update_state` 是 Java 的工具，调完 Java 立刻更新这一块，替代现在的 `appendToLast`；
   - `site` / `company`：浏览器打开新站点时（Java 的浏览器工具知道），换成这个站点的记录和这家公司的记录；
   - `job`：岗位备注、本次答案。

   这条路**不需要插件**（插件里的 `ctx.session.instructions.entry.put` 是同一件事，HTTP 接口也有）。接口标着 experimental，升级时要核对。
3. **流程经验：skill。** opencode 直接读 `<storage>/playbook/skills`（不复制，storage 仍是唯一来源）。现在按目录名决定「哪个 agent 看得见」的规则，改成每个 agent 的 `skill` 权限（例如 session 只放行 `research/*`、`memory-update`）。`applies_to.url_patterns` opencode 不认，改写进 `description`，或由 Java 在 `site` 块里点名「这个站先读 xx skill」。skill 正文里引用的 `references/` 要模型能读：给 `read` 权限只放行 skill 目录。

### 3.2 关掉 opencode 自己的指令 / 记忆 / skill 来源

Java 拉起 opencode 时设：

- `HOME=<storage>/agent/opencode/home`、`OPENCODE_CONFIG_DIR=<storage>/agent/opencode/config`、`XDG_DATA_HOME` / `XDG_STATE_HOME` / `XDG_CACHE_HOME` 指到同目录下。效果：不读用户的 `~/.config/opencode`（全局 AGENTS.md、插件、skill）、`~/.claude`、`~/.agents`，也不和用户自己装的 OpenCode Desktop 共用数据库和服务。
- `OPENCODE_DISABLE_CLAUDE_CODE=1`、`OPENCODE_DISABLE_PROJECT_CONFIG=1`：不找 CLAUDE.md、项目 `.opencode`、项目 AGENTS.md。
- 会话的工作目录（opencode 叫 location）是一个空目录 `<storage>/agent/opencode/workspace`，里面不放任何文件。
- 配置：`plugins` 里写 `-opencode.home-memory`；`snapshots: false`（不需要文件快照）；`instructions` 不配；`update: "disable"`；内置 agent `disabled`；权限默认 deny，只放行 `jobapply_*`、`question`、`skill`（按 agent 限定）、`read`（只限 skill 目录）。`shell`、`edit`、`write`、`execute`、`browser`、`subagent`、`external_directory` 全 deny。
- 我们的 agent 写完整 `system`。opencode 还会加日期 / 环境说明，这部分无害；如果实测发现还有写代码相关的段落，用同一个插件的 `context` 钩子删掉。

### 3.3 原文记录放哪：两份，各管各的

- **opencode 的 SQLite**：运行用的会话状态，就是模型看到的历史，压缩后也保留原消息（`GET /message` 能拿全）。但它是 opencode 的内部格式，会随版本迁移；`revert` 会删消息；数据库坏了或换版本时不可控。
- **`transcript.jsonl`（我们的，记录原文）**：**要镜像。** Java 本来就在消费事件，在每段文字结束、每个工具结束、每条用户消息送达时，用现在的 `ChatMessage` 格式追加一行（带 opencode 的 message / part ID，重复的跳过）。Java 和 opencode 之间断线重连后，用 `GET /message` 补上漏掉的。
- 这样 `memory.md`「对话原文完整保存在 transcript.jsonl，给模型的上下文可以压缩，但原文不压缩」不用改，只是写入者从我们的循环变成 Java 的事件网关。页面的历史也从 `transcript.jsonl` 读，所以旧 Session 和新 Session 显示方式一样。
- **交接摘要**：Session 空闲一段时间或用户离开时，Java 调 `POST /session/{oc}/generate`（不改历史）生成，写进 `session.md`；新 Session 时放进 `handoff` 块。
- **工作台的运行日志、填表报告**：工具在 Java 里执行，照写 `run-log.jsonl`、`fill-report.md`，不变。

### 3.4 旧快照和上下文长度

现在 `Conversation` 只把最新一份页面快照原样给模型、旧的写「旧快照已省略」、只发最近 3 条带图消息的图片。opencode 会把每个工具结果都留在历史里，浏览器快照很大，不处理的话很快就触发压缩、费用也高。

- 插件 `snapshot-trim.ts`：`context` 钩子里，把除最新一份以外的 `jobapply_browser_*` 快照结果换成一句话（弹出层视图时保留上一份整页，规则照搬 `LatestPage.base`），旧图片同理。只改这次请求，不改历史，所以 `transcript.jsonl` 和 SQLite 里都是全文。
- 压缩用 opencode 自带的（剩 15% / 至少 16k 时结构化摘要，保留最近约 15k 原文）；压缩模型设成辅助模型。

### 3.5 Project / Session / Job 和 opencode 会话怎么对应

| 我们的 | opencode | 存在哪 |
| --- | --- | --- |
| Project | 不对应。所有会话都在同一个 location（空工作目录），只连一份 MCP | 会话 `metadata.project` |
| Session（对话） | 一个顶层会话，agent = `session` | `session.md` 记 `opencode_session` |
| 一份申请的投递段（工作台上半） | 一个**顶层**会话，agent = `apply`，由 Java 的 `start_application` 工具（或候选池的按钮）建，不用 opencode 的 `subagent` 工具 | `job.md` 记 `opencode_apply` |
| 后续段（工作台下半） | 另一个顶层会话，agent = `followup` | `job.md` 记 `opencode_followup` |
| 辅助调用（导入抽取、译文、画像、候选池归并、连接测试） | 不建会话：`POST /api/experimental/generate/text`，指定辅助模型 | — |

为什么申请不用 opencode 的子会话：`memory.md` 定了 Job 跨 Session、可以在任何 Session 里继续；opencode 子会话的回报固定发给建它的那个父会话。改成 Java 建顶层会话后，申请会话空闲时（`session.idle`）由 Java 往**当前相关的 Session**（`job.md` 里记的那个）发一条 `synthetic` 回报（`delivery: queue`），语义和现在的「申请线程回报 Session」一致。并行上限（`WorkerSettings`）也由 Java 控制：超过上限的申请先排队，不发第一条 prompt。

浏览器：Java 收到 MCP 调用时用 `_meta["ai.opencode/sessionID"]` 找这个会话的 `OwnerBrowser`，后面走现有的共享 Playwright MCP / 认领标签页 / 转交逻辑。

### 3.6 守卫、占位符、脱敏

不变，都在 Java 浏览器工具里执行：提交守卫只在 Java（不在 opencode 钩子里再做一层）；模型只看到 `{KEY}`，Java 发给 Playwright MCP 时才换真值；快照返回前脱敏。真值不进 opencode 的 SQLite、事件和日志，也不进 `transcript.jsonl`。

### 3.7 模型和密钥

- DeepSeek（思考）、Gemini（thought signature）、ChatGPT 套餐、OpenCode Zen / Go 都是 opencode 内置 provider。我们自写的 `ChatGptAuth`、`ChatGptResponsesChatModel`、`GeminiThoughtSignatures`、`OpenCodeSession` 都可以删。**未用真实模型验证**，WP1 第一件事就是用用户的 DeepSeek / Gemini 各跑一次。
- 密钥：推荐交给 opencode 的凭证库（在 `<storage>/agent/opencode/` 的私有数据目录里，随 storage 走；开发代理同样禁读）。设置页保存 key 时 Java 转发 `/api/credential`。这会改掉 `ui.md`「API key 存 `storage/settings/.env`」的决定，需用户确认（第 8 节）。另一种做法是 key 仍放 `.env`，Java 启动 opencode 时作为环境变量传入，代价是改 key 要重启 opencode、ChatGPT 套餐登录仍在 opencode 凭证库里。
- C 级 `personal.env` 不变，只有 Java 读。

### 3.8 打包和进程

- 打包：App 多带 `opencode-cli`（npm `@opencode/cli-darwin-arm64@2.4.1`，240MB；Intel Mac 另一份）和 `snapshot-trim.ts`。版本写死，构建时核对文件哈希。
- **由 Java 拉起 opencode**（和现在 Java 管 Playwright MCP 的 node 进程一样），不由 Swift 壳管：Java 需要知道端口和密码，也要能在崩溃后重启。参数：`serve --hostname 127.0.0.1 --port <随机>`，`OPENCODE_PASSWORD` 每次随机，加上 3.2 的环境变量，再加 `OPENCODE_DISABLE_FILEWATCHER=1`（工作目录是空的，不需要监视；也避开 #64159 的 watcher 泄漏）。
- Java 启动时查 `GET /api/info` 的版本，不等于 2.4.1 就在界面提示一次。
- 开发时：binary 路径用一个本地配置项指过去，Maven 构建不依赖它。

### 3.9 内存

- 实测：`serve` 刚起 RSS 约 160MB；请求了 agent、plugin、openapi 和 **Web 首页** 几个只读接口后约 730MB 并保持。B2 里我们不请求 Web 首页，也关了 filewatcher，可能会低一些，**未核实**。
- 加上 Java 和 Playwright 的 node，总量会比 C028 之后明显多。不预先加限制；WP1 跑通后，在 3 个并行申请下记录 opencode、Java、node 三个进程的 RSS 写进 dev-log，超过用户能接受的线再处理（例如申请做完就归档会话、换修复了泄漏的版本）。

## 4. 工作包（按顺序，每包可单独 review、可停）

1. **WP1 opencode 进程 + Java MCP（只有浏览器工具）+ 一个 apply agent**
   - Java 拉起固定版本的 `opencode-cli serve`，生成配置（3.2），写好 `snapshot-trim.ts`。
   - Java 用 MCP Java SDK 2.0.0 的 Streamable HTTP 服务端暴露现有 13 个浏览器工具和 `browser_tabs`；按 `_meta` 会话 ID 找 `OwnerBrowser`；守卫、占位符、脱敏原样复用。
   - 先用一个临时入口（工作台「开始申请」直接接到新路径）验证。
   - 用户能看到：一份申请打开岗位页、读快照、填几项；两份申请同时各用各的标签页；点「提交」被拒并看到原因；DeepSeek 和 Gemini 各跑通一次；三个进程的内存数字。
2. **WP2 对话网关（Java ↔ opencode ↔ 现有页面）**
   - 2.2 表里的全部映射：发消息、插话、打断并发、停止、问用户表单、重新生成、附件、过程块、状态、权限卡片。
   - 事件翻译进现有 `RuntimeEvents`；`transcript.jsonl` 镜像和断线补齐。
   - 前端：问用户表单卡片、权限卡片、`answerForm`。
   - 用户能看到：Session 和工作台的对话和现在长得一样，过程块正常折叠，插话、停止、回答、重新生成都能用；关掉再开，历史还在。
3. **WP3 业务 / 记忆工具、instruction entries、三个 agent、skill**
   - 其余工具挂到 MCP（含 `start_application`、`mark_submitted`、`update_state`、`remember`、`search_experience`、后续段工具）；记忆注入（3.1 第 2 条）；session / apply / followup / helper 的提示词从 Java 文本块搬成配置里的 `.md`；skill 指向 storage，按 agent 设权限。
   - 申请会话 → Session 的回报、并行上限排队。
   - 交接摘要改用 `/generate`。
   - 用户能看到：从对话开始找岗位 → 开始申请 → 填到提交前停下 → Session 收到回报；台账、Dashboard 里能看到；新开 Session 时 Agent 知道上次做到哪。
4. **WP4 辅助模型、模型设置页、后续段和定时**
   - 导入抽取、译文、画像、候选池归并改用 `generate/text`。
   - 模型设置页换数据来源（2.3）；`ToolCallSettings` 改 `steps`。
   - Java 定时器建 followup 会话；查状态走同一条路。
   - 用户能看到：设置页能配 DeepSeek / Gemini / ChatGPT 套餐并测试；资料导入照常；到点出现后续会话，结果写回台账。
5. **WP5 删除旧运行时**
   - 删第 4.1 节列出的代码；同步第 8 节的文档（需用户确认）。
   - 用户能看到：`mvn -B clean verify`、`npm run build` 通过，后端明显变小。
6. **WP6 打包**
   - App 带上 `opencode-cli` 和插件文件；首次启动时建 `<storage>/agent/opencode/`。
   - 用户能看到：装好 JobApply 后不需要另装 OpenCode，点图标就能开始一次申请；用户自己的 OpenCode Desktop（如果装了）不受影响。

### 4.1 删除清单（WP5）

- 运行时：`harness/runtime/*`（`AgentLoop`、`HarnessLoop` 的循环部分、`SessionLoop`、`FollowupLoop`、`WorkbenchTools`、`LoopDetector`）、`harness/context/Conversation`、`FillContext`（内容改由记忆注入生成）、`SkillCatalog`；`agents/*` 文本搬走后删；`tools/*Tools` 的 `DefinitionOnly` 定义改为 MCP 工具定义；`workflow/ApplicationRunner`、`SessionRunner`、`FollowupRunner` 的线程池、停止、收件箱部分（排队和并行上限留下）；`RuntimeEvents.observer()`（`LoopObserver` 那半）。
- `HarnessLoop.Round.browser()` 那条守卫 → 换值 → MCP → 记字段 → 日志的流水线**不删**，搬进 MCP 浏览器工具服务。
- 模型：`ModelFactory`、`ModelCaller`、`ChatGptAuth`、`ChatGptResponsesChatModel`、`GeminiThoughtSignatures`、`OpenCodeSession`、`ModelRegistry`、`ModelSettingsService`、`ModelCatalogClient`、`ModelPresets`、`ConnectionTester`、`ModelSettingsFile`（`settings/models.yaml`）；依赖 `spring-ai-openai`、`spring-ai-anthropic`（RAG 若用到 Spring AI 的部分则保留那部分）。
- 前端：基本不删。`ModelSettings.tsx` 重写数据部分，样子保留。
- 留下：浏览器层、守卫、占位符、脱敏、台账、记忆、RAG、导入、报告、事件日志 + SSE、全部页面。`PlaybookSeeder` 留着（继续把内置 skill 放进 storage）。

### 4.2 数据迁移

- 业务数据不动：`storage/` 下台账、记忆、资料、简历、站点和公司记录照旧。
- `transcript.jsonl` 格式不变，旧 Session 照常显示。
- 旧 Session / 旧申请**可以继续**：用户在一个没有 `opencode_session` 的旧 Session 里发消息时，Java 新建 opencode 会话，`handoff` 块放它的交接摘要和最近几轮原文，然后照常发消息。旧申请同理：申请状态本来就在 Java（`job.md` / `ApplicationState`），注入 `state` 块后接着填。
- 模型设置：WP4 时把 `models.yaml` 里的供应商和用途分配转成 opencode 配置一次；key 怎么迁看用户对 3.7 的选择（迁移脚本不读 `.env` 内容，由 Java 运行时转交，或由用户在设置页重新填）。
- `checkpoints.sqlite` 不再使用，原地留着。

### 4.3 风险

| 风险 | 说明 | 处理 |
| --- | --- | --- |
| V2 接口变化快 | 51 个版本 / 2.5 个月；HTTP API 自称 experimental；instruction entries、`generate/text` 在 experimental 路径下 | 打包固定 2.4.1、关自动更新；Java 只用十几个接口；升级 = 一份 C###-fix（读 changelog 和 `migrate-*.mdx`，跑一次真实申请） |
| 内存 | 实测 serve 160MB → 约 730MB（含请求 Web 首页）；issue 里有 watcher、transcript、SQLite 相关泄漏 | 关 filewatcher、单 location、不请求 Web 界面；WP1 实测三进程，结果给用户定 |
| 包体 | +240MB（每个平台一份） | 接受；不走 SDK + Node 26（更大，且要自己装内置插件） |
| 翻译层出错 | 事件漏翻、断线漏消息、`transcript.jsonl` 和 SQLite 不一致 | 镜像按 opencode ID 去重；重连后 `GET /message` 补齐；页面历史只认 `transcript.jsonl` |
| 两份历史 | SQLite 和 `transcript.jsonl` 都有原文 | 规定 `transcript.jsonl` 是记录，SQLite 是运行状态；删 Session 时两边一起删 |
| 宿主提示词冲淡 | opencode 仍会加日期 / 环境说明，可能还有写代码向的工具说明 | 自定义 agent 写完整 `system`；必要时插件 `context` 钩子删段 |
| 真实模型未验证 | DeepSeek 思考回传、Gemini 签名、ChatGPT 套餐靠 opencode 实现 | WP1 先跑 |
| 问用户交互变了 | `question` 是表单（选项 / 多选），和现在的自由文字 ask_user 不同 | 用户打字时取消表单、当普通消息发；模型也能直接文字问 |
| 改动量 | 这是迁移，不是一次小改 | 按 WP 拆，每包可单独 review、可停 |

### 4.4 不做

- 不 fork opencode，不改它的源码。
- 不用 OpenCode Desktop、不写 GUI 插件、不在浏览器里直连 opencode。
- 不用 opencode 的记忆（home-memory）、AGENTS.md / CLAUDE.md、用户目录里的 skill 和插件。
- 不用 opencode 的 `subagent` 工具开申请（申请会话由 Java 建）。
- 不在 opencode 插件里做提交守卫或占位符替换（只在 Java 浏览器工具里）。
- 不用内置 `browser`、`shell`、`execute`、文件编辑工具驱动网页；不另起 Chrome、不换 profile。
- 不迁移 `transcript.jsonl` 到 opencode 格式，也不用 `/api/experimental/session/import`。
- 不做逐字流式显示、不默认加上下文占用面板（用户要时再提）。
- 不用 `@opencode/sdk` + Node 26 内嵌。
- 不加额外的确认、预算、限制；`steps` 只用来防死循环，沿用现在的上限值。

## 5. 核实过的事实（第一版留下的，B2 仍相关）

| 问题 | 结论 | 怎么核实的 |
| --- | --- | --- |
| `serve` 提供什么 | 同一端口是 v2 API 也是 Web 界面（`/` 返回 HTML）；OpenAPI 在 `/openapi.json`，146 个路径，描述写着 "Experimental HttpApi surface" | 隔离 HOME 下启动实测，已停 |
| 内置 agent 默认权限 | `build` 全 allow、`.env` 读取 ask、内置 `browser` deny（它控制 Desktop 自带浏览器，不是用户的 Chrome） | 实测 `GET /api/agent` |
| 并行会话共用 MCP | 同一目录所有会话共用一份 MCP 连接；每次工具调用 `_meta` 带 `"ai.opencode/sessionID"` | `core/src/mcp/client.ts` 第 942 行 |
| `execute.before` 改输入 | 改过的输入会执行；落库的是模型原始参数。我们不用它换真值 | `core/src/tool.ts` 第 161–238 行 |
| `@opencode/sdk` 能否用我们打包的 node | Node 22.13 不行，Node 26.4 可以（RSS 约 410–443MB，依赖 399MB） | scratchpad 实测，未调模型 |
| Java 侧 MCP 服务端 | classpath 里的 MCP Java SDK 2.0.0 自带 `HttpServletStreamableServerTransportProvider` | `~/.m2` 里的 `mcp-core-2.0.0.jar` |
| 许可 | MIT | `LICENSE` |

## 6. B1 短说明（原推荐，现不推荐）

用户装 OpenCode Desktop，对话、会话列表、模型设置都用 Desktop 的，我们的 React 页面用 GUI 插件的内嵌网页放进侧栏面板 / 设置页。和新要求冲突：对话界面不再是我们的（过程块、工作台两段、侧栏三级都没了），Desktop 不能加一级页面，自动更新锁不住版本，GUI 插件 API 是 `gui-experimental`。记忆仍可以用我们的（同 3.1），但界面一条不满足，所以不推荐。

## 7. B3 短说明

fork 后大改：维护约 140 万行 TS、每周发版的仓库，Bun / Effect / Electron 构建链全部接手。B2 用不到 Desktop，fork 唯一的好处也没了。不做。

## 8. 选定 B2 后要改的约定（需用户确认）

- `dev-limit.md`：「默认 Agent Loop 不用框架封装的 ReActAgent」「暂停和恢复靠把对话存进 Job 目录」两条要改——循环交给 opencode，运行状态在 opencode 的 SQLite，记录原文仍在 `transcript.jsonl`。
- `plan/master/memory.md`：`transcript.jsonl`「不压缩、可追溯」不变，写入者改为 Java 事件网关；「Checkpoint 两类线程存 `checkpoints.sqlite`」改为「Session / 申请 / 后续段各对应一个 opencode 会话，ID 记在 `session.md` / `job.md`」；交接摘要的生成方式改为 `/generate`；storage 目录树加 `agent/opencode/`（opencode 的私有 HOME、配置、数据，可删重建但会丢运行中的会话状态）。
- `plan/master/ui.md`：界面结构不变；「模型设置 · 实现（Spring AI 2.0.1）」整节改为「经 Java 转 opencode 的 provider / 凭证接口」；「密钥存放 `.env`」按 3.7 的选择改或保留；ask_user 改为表单卡片。
- `plan/master/agent-runtime.md`、`architecture.md`、`base.md`（技术路线和版本表：加 opencode CLI 2.4.1 固定版本、一个 TS 插件文件，不需要装 Bun）要回写。
- 仓库里放 opencode 配置模板、agent 提示词 `.md`、`snapshot-trim.ts` 的目录（例如 `agent/opencode/`）是新目录，按 AGENTS.md 要先问用户。
- AGENTS.md「本机构建」加一句：开发时 opencode binary 放在哪、怎么指给 Java。
- 不变：「系统永远不执行最终提交」「C 级占位符只在发给 Playwright MCP 那一刻替换」「浏览器就是用户正在用的 Chrome」，执行点都在 Java。

## 9. 没核实的

- 不请求 Web 首页、关 filewatcher 后 serve 的实际内存，以及 3 个并行申请下的曲线。
- `revert/stage` + `commit` 后重新 `prompt` 是否正好等于「重新生成最后一条回复」（消息边界的细节）。
- `question` 表单是否允许自由填写（文档只写了选项和多选）。
- `HOME` 指到私有目录后，ChatGPT 套餐登录、models.dev 缓存是否都落在这个目录里。
- 分组目录里的 skill ID 具体长什么样（`components/date-picker` 还是 `date-picker`），影响按 agent 设权限的写法。
- MCP 工具名在事件里是不是 `jobapply_<tool>`（文档写 `<server>_<tool>`）。
- 真实模型下 DeepSeek 思考回传、Gemini thought signature、ChatGPT 套餐的表现。
- opencode 加给系统提示的固定段落具体有哪些。
