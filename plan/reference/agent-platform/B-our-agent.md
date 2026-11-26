# JobApply 现有 Agent 架构审计（B）

日期：2026-11-25。分支 `C30-plugins`（基于 `e255069`）。只读审计，没有改仓库文件，没有读密钥文件。
路径都相对 `backend/src/main/java/jobapply/`，除非另行注明。行号以当前工作区为准。

---

## 0. 一句话结论

循环内核其实很小、也算干净（`AgentLoop` 275 行，`ModelCaller` 111 行，`LoopObserver` 34 行），问题不在"循环写错了"，而在**循环之外的一切都按"三种 Agent"硬编码**：工具的定义和执行分离、按名字 `switch` 分派；提示词是 Java 文本块；上下文由三套手写的 `context()` 拼；skill 目录在每个循环里按目录名过滤；没有中间件 / 钩子层，所以"上下文压缩""子 Agent""新工具"这类横切能力只能改核心类。`HarnessLoop`（1805 行）是把"通用 Agent 能力 + 填表领域逻辑 + 持久化 + 守卫编排"揉在一个内部类 `Round` 里的上帝类。

真正有价值、别人没有的是**浏览器层与守卫层**（在用户自己的 Chrome 上通过共享 Playwright MCP 并发填表、提交守卫、C 级占位符脱敏、申请状态、台账/记忆/工作台 UI）。通用部分（循环、消息拼装、工具调用、skill、事件流、provider 管理、压缩、子 Agent）都可以交给一个基座。

---

## 1. 运行时地图

### 1.1 文件规模（大文件）

| 文件 | 行数 | 角色 |
| --- | --- | --- |
| `harness/runtime/HarnessLoop.java` | **1805** | 投递段 Agent（填表）：工具执行、守卫编排、申请状态、报告草稿、日志 |
| `workflow/ApplicationRunner.java` | 1173 | 投递段线程：Worker 池、停止、收件箱、状态、恢复 |
| `persistence/JobLedger.java` | 1086 | 台账 |
| `tools/PageScripts.java` | 900 | 系统代发的固定页面脚本（下拉、日期、只读检查） |
| `tools/PlaywrightMcpClient.java` | 873 | MCP 客户端 + 服务进程管理 |
| `tools/BrowserSession.java` | 759 | 全局浏览器连接、状态机、使用者会话 |
| `harness/runtime/SessionLoop.java` | 675 | 对话/调研 Agent |
| `config/ChatGptAuth.java` | 646 | ChatGPT 套餐登录 |
| `tools/OwnerBrowser.java` | 625 | 每个使用者的浏览器会话 |
| `workflow/FollowupRunner.java` | 605 | 后续段线程 |
| `harness/runtime/FollowupLoop.java` | 531 | 后续段 Agent |
| `config/ChatGptResponsesChatModel.java` | 511 | 自写的 Responses API ChatModel |
| `workflow/SessionRunner.java` | 510 | Session 线程 |
| `workflow/RuntimeEvents.java` | 413 | 事件日志 + LoopObserver 实现 |
| `harness/policies/BrowserToolPolicy.java` | 398 | 浏览器工具白名单 |
| `harness/context/SkillCatalog.java` | 293 | skill 扫描与加载 |
| `harness/runtime/AgentLoop.java` | 275 | 共用循环 |
| `harness/policies/SubmitGuard.java` | 255 | 点击守卫 |
| `harness/context/Conversation.java` | 224 | 消息拼装 |
| `harness/context/FillContext.java` | 218 | 投递段上下文 |
| `config/ModelFactory.java` | 214 | Provider 工厂 |

后端 main 合计约 39.8k 行 Java；`harness/` 6.6k 行；前端约 10k 行（`JobPage.tsx` 1124、`api.ts` 966）。测试 94 个文件。

### 1.2 循环

```
Runner（线程 / 收件箱 / 停止）            Loop（一个 Agent 的工具、上下文、结束条件）        内核
ApplicationRunner ──new HarnessLoop()──▶ HarnessLoop.Round  implements AgentLoop.Agent<AgentRun> ─┐
SessionRunner     ──new SessionLoop()──▶ SessionLoop.Round  implements AgentLoop.Agent<Void>     ─┼─▶ AgentLoop.run()
FollowupRunner    ──new FollowupLoop()─▶ FollowupLoop.Round implements AgentLoop.Agent<Void>     ─┘      │
                                                                                                   ModelCaller.call()
                                                                                                   LoopObserver → RuntimeEvents → SSE
```

- **`AgentLoop<R>`**（`harness/runtime/AgentLoop.java:155-210`）：停止检查 → 取收件箱插话（`heard`，`:216`）→ 工具调用上限 → 调模型 → 只回文字就 `answered` 结束 → 依次执行工具（计时、`observer.tool/done`、记最新页面）→ 结果作为一条 `tool` turn 放回 → `roundDone` → 下一轮。上限时多调一次让模型总结（`:227`）。
- **`AgentLoop.Agent<R>` 接口**（`:41-76`）是唯一的"扩展点"：`messages()`、`responded()`、`handle()`、`sawPage()`、`answered()`、`stopped()`、`limitTurn()`、`summarized()`、`roundDone()`、`heard()`。它把"一个 Agent"建模成一个大对象，而不是"工具集 + 中间件链"。
- **`Step<R>`**（`:84`）：工具结果 + 可选页面 + 可选"结束这次运行"的 Supplier。`ask_user`/`finish` 就是靠 `ending()` 结束运行。
- **`ModelCaller`**：同步 `model.call(prompt)`，2 分钟内指数退避重试，按异常类名判断是否暂时性（`ModelCaller.java:23-61`）；`thinking()` 从 Anthropic thinking 块 / OpenAI `reasoningContent` 取思考原文；`tokens()`。**没有流式**：整个系统没有 `stream()` / `Flux` 调用，前端看到的是"步"级事件，不是 token 级。
- **`LoopObserver`**（34 行）：`thinking / replied / tool / heard / done` 五个回调，只读，用于 UI 推送；不能改消息、不能拦截工具。
- **`LoopDetector`**（91 行）：只在 HarnessLoop 用，重复操作时往工具结果追加一句提醒。

三个 Runner 各自复制了一套线程池 + `stopRequested` 集合 + `Inbox` + `OpenCodeSession.run(...)`（`ApplicationRunner.java:138-150`、`SessionRunner.java:78-92`、`FollowupRunner.java:81-88`），循环对象每次运行时 `new`（`ApplicationRunner.java:950`、`SessionRunner.java:395`、`FollowupRunner.java:522`），不走 Spring 注入。

### 1.3 消息怎么拼

- **`Conversation.messages(systemPrompt, context, turns, latestPage, attachments, state)`**（`harness/context/Conversation.java:75-155`）：`SystemMessage(系统提示词)` → `UserMessage(每步重新生成的上下文)` → 对话 turns 映射（assistant 带 tool calls、`reasoningContent` 只回传最后一条用户消息之后的；tool 结果；`page` turn；用户消息带附件/图片）→ 把"申请状态"接在**最后一条消息末尾**（`appendToLast`，`:158`）。
- 内置的"减量"规则全写在这里：只有最新一份快照原样给（旧的写"旧快照已省略"，`:146`），弹出层视图时保留上一份整页（`LatestPage.base`）；只发最近 3 条带图用户消息的图片（`IMAGE_TURNS`）；无结果的工具调用补 `INTERRUPTED`（相当于 deepagents 的 PatchToolCallsMiddleware）；快照过 `SnapshotRedactor`（`render`，`:211`）。
- **`FillContext`**（218 行）：投递段的上下文——中英两份有效资料、本次答案、可上传文件编号、skill 目录、站点记录、公司记录、lessons、画像、岗位备注；`Inputs` record 有 13 个字段并保留了 3 个"兼容构造"（`FillContext.java:50-79`）。
- **`StateView`**（112 行）：申请状态的文字视图。
- **SessionLoop / FollowupLoop 的上下文**：各自私有 `context()` 方法手拼字符串（`SessionLoop.java:268-285`、`FollowupLoop.java` 约 200-237 行）。SessionLoop 另有 `shortened()`（`:247-263`）：本次运行之前的 tool 结果截到 1500 字——这是唯一一处"历史压缩"，且是硬截断。
- **没有上下文压缩**：`plan/reference/gap-context-compaction.md` 已确认缺失（无窗口占比触发的总结、无超长报错后的压缩重试、无大结果转存文件）。

### 1.4 工具怎么注册、怎么暴露

- **定义与执行彻底分离**：所有工具都是"只有定义"的 `ToolCallback`，`call()` 直接抛异常——这个私有 record `DefinitionOnly` 被复制了 5 份：`tools/BrowserTools.java:315`、`tools/ControlTools.java:235`、`tools/ResearchTools.java:188`、`tools/ExperienceTools.java:51`、`tools/FollowupTools.java:214`。Spring AI 的工具执行机制完全不用（`ModelFactory` 文档 `:1-8` 明确不用 `ChatClient`/advisor）。
- **每种 Agent 的工具清单**是静态方法：`ApplyAgent.tools()`（浏览器 13 个 + tabs + ControlTools 10 个 + mark_submitted，`agents/ApplyAgent.java:167-173`）、`ResearchAgent.tools()`、`FollowupAgent.tools()`；两段工作台共用的 `search_experience`、`record_outcome` 由 `WorkbenchTools.with()` 追加（`harness/runtime/WorkbenchTools.java:25,43`）。
- **执行**是每个 Loop 的 `handle()` 里按名字 if/switch：
  - HarnessLoop：`handle()` `:505-532` → `WorkbenchTools.handle` → `mark_submitted` → `ControlTools` → `browser_tabs` → `BrowserTools.translate` → `browser()` `:534-672`（守卫、C 级、只读框检查、占位符换真值、MCP 调用、记字段、日志、重复检测、弹出层视图）；`control()` `:893-913` 再一个 switch 分 10 个控制工具。
  - SessionLoop：`handle()` `:299-330` switch 13 个工具；浏览器类交给 `ReadOnlyTab`。
  - FollowupLoop：`handle()` `:332-365` 同理。
- **浏览器工具**：`BrowserTools` 手写 13 个定义（`grep -c definition(` = 13），名字沿用 Playwright MCP；`translate()` 把模型参数翻成 MCP 参数（加文件路径、拆出 `FieldIntent`/`ClickIntent`）。`PlaywrightMcpClient` 用 MCP Java SDK（`McpSyncClient`，`:4,276`）连一个共享的 `@playwright/mcp` 服务进程（`--shared-browser-context`），`listTools()` 只用来核对（`:286`），**不是**从 MCP 动态生成工具。
- 前端也按工具名硬编码展示文案（`frontend/src/chat/process.ts:143-169`）。

### 1.5 skill 怎么加载

- 位置：classpath `playbook/skills/**/SKILL.md`，由 `PlaybookSeeder` 按写死的 `BUNDLED` 清单复制到 `storage/playbook/skills/`（按 `version` 只升不降，用户改过的保留）。运行时只扫 `storage/playbook/skills`（`SkillCatalog.scan(layout)`，`SkillCatalog.java:95`）。
- 现有 11 个 skill：`apply-form`、`memory-update`、`research/{broad-search,verify-official}`、`components/{resume-parse,cover-letter,date-picker,readonly-select,file-upload,repeatable-entries,cascader-city}`。每个目录只有一个 `SKILL.md`，没有任何附属文件。
- frontmatter（SnakeYAML SafeConstructor 解析）：必填 `name`（`[a-z0-9]+(-[a-z0-9]+)*` 且与所在目录同名，`:211-217`）、`description`；可选 `applies_to.url_patterns`、`applies_to.page_markers`、`version`（字符串/数字）、`observed_count`（非负整数）。其他键被忽略（不报错）。
- `group` = 相对路径第一层目录（`:239`）；`platforms/` 下的 skill 按 `url_patterns` 进"建议先读"（`autoInclude`，`:141`）。
- 渐进加载：上下文只列"名字：描述"；正文由工具 `read_skill(name)` 读，超过 8000 字符截断（`BODY_BUDGET`，`:38,158-173`）。
- **每个 Loop 自己决定哪些 skill 可见**（硬编码目录名）：
  - 投递段：全部列出（`FillContext.java:186-188`）；
  - Session：只列 `group == "research"`（`SessionLoop.java:275-278`）；
  - 后续段：排除 `components`、`research` 和 `apply-form`（`FollowupLoop.java:232-235`）。
- 三个 Loop 各自 `SkillCatalog.scan(layout)`（`HarnessLoop.java:306`、`SessionLoop.java:185`、`FollowupLoop.java:138`）并各自实现一遍 `readSkill`（`HarnessLoop.java:1025`、`SessionLoop.java:611`、`FollowupLoop.java:494`）。

### 1.6 提示词怎么组织

- 系统提示词是 Java 文本块常量：`ApplyAgent` 的 `IDENTITY / METHOD / SITUATIONS / WRITING_AND_MEMORY / LINES` 用 `String.join` 拼（`agents/ApplyAgent.java:17-163`）；`ResearchAgent`（106 行）、`FollowupAgent`（67 行）同理。改提示词 = 改 Java + 重新编译；用户/插件无法追加段落。
- 动态部分：每步重新生成的 context（见 1.3），以及系统往对话里插的 `page`/`note`/`user` turn（例如 `HarnessLoop.java:330-331,399-428` 里一堆"系统打开了岗位链接……"中文句子），以及工具结果里追加的"系统提醒："（`:648-660`）。

### 1.7 事件怎么推到前端

- `RuntimeEvents`：应用级带序号事件日志，事件名 `activity / step / message / status / changed / browser`（`workflow/RuntimeEvents.java:37-43`），`observer(session, job)` 返回把 `LoopObserver` 回调翻成事件的实现（`:313-370`）；还维护"正在做什么"和"进行中的这一轮"（`sessionActivity`、`jobInFlight` 等）。
- `api/EventController`：`GET /api/events` SSE，`Last-Event-ID` 补发、`caught_up`、`reset`、心跳（`:72-192`）。这一块设计是对的（照 OpenMAIC/Deep Agents 的"事件日志 + 尾巴"），但事件粒度是"步"，没有 token 流。

### 1.8 Provider 管理

- `ModelFactory.create(Endpoint)`（`config/ModelFactory.java:95-130`）：三种格式 `openai`（Spring AI `OpenAiChatModel`）、`anthropic`（`AnthropicChatModel`）、`chatgpt`（自写 `ChatGptResponsesChatModel`，511 行）。OkHttp 拦截器补 `x-opencode-session`（`OpenCodeSession`）和 Gemini 思考签名（`GeminiThoughtSignatures`）。
- 思考强度按模型名子串分支（`thinkingBody`，`:142-168`："以后别家模型在这里加分支"）。
- `withTools(ChatModel, tools)`（`:186-196`）：`mutate()` 复制选项再塞工具定义；Anthropic 时 `maxTokens=16000`。
- `ModelRegistry` / `ModelSettingsService` / `ModelCatalogClient` / `ModelPresets` / `ConnectionTester` 负责设置页、热替换、模型列表、连接测试。主模型以 `Supplier<ChatModel>` 交给 Runner。
- 新增一种 provider 格式要改 `Endpoint` 构造校验（`:68-70`）、`create`、`withTools(Endpoint,...)`、前端设置页。

---

## 2. 扩展点：现在有 vs 缺失

| 想加的东西 | 今天能否"不改核心类"加进来 | 要改哪里 |
| --- | --- | --- |
| 新工具（给某个 Agent） | **不能** | 在 `*Tools` 里写定义 + 名字常量 + `isXxxTool` 集合；在 `ApplyAgent/ResearchAgent/FollowupAgent.tools()` 加；在对应 Loop 的 `handle()`/`control()` switch 加分支；前端 `process.ts` 加文案 |
| 两段工作台共用的工具 | 半能 | `WorkbenchTools`（`SHARED` + `handle` 里 if） |
| 新 skill（内置） | 半能 | 写 `SKILL.md` + 加进 `PlaybookSeeder.BUNDLED` + 放进"对的"目录名才会被某个 Loop 看见 |
| 新 skill（用户导入） | 勉强 | 手动丢进 `storage/playbook/skills/<name>/SKILL.md`；没有导入 UI/API；只有正文能用 |
| 新 provider 格式 | 不能 | `ModelFactory` 三处 + 设置页 |
| 新模型的思考参数 | 不能 | `ModelFactory.thinkingBody` 加分支 |
| 横切功能（压缩、token 计数提醒、日志、缓存提示） | **不能** | 没有中间件层；要么改 `AgentLoop.call()`，要么改 `Conversation.messages()`，要么三个 Loop 的 `messages()` 各改一遍 |
| 子 Agent | 不能 | 现在的"子 Agent"是 `start_application` 让 `ApplicationRunner` 起线程，不是通用的 `task` 工具 |
| 新 Agent 类型 | 不能 | 新写一个 `XxxLoop` + `XxxRunner` + `XxxAgent` + `XxxTools`，复制 ask_user/read_skill/remember/上限/停止那一套 |
| UI 事件 | 半能 | `LoopObserver` 只有 5 个固定回调；新事件类型要改 `RuntimeEvents` 和前端 |
| 外部 MCP 服务器的工具 | 不能 | 只有 Playwright MCP，工具定义手写 |

**插件需要能挂的点**（对照 deepagents middleware / opencode plugin hooks 的形状）：

1. `beforeModel(messages, state)`：改/压缩消息（压缩、注入申请状态、注入最新页面、注入 skill 目录）。
2. `afterModel(response)`：记用量、思考、校验工具调用。
3. `wrapToolCall(call, next)`：守卫（提交守卫、C 级、白名单）、占位符换真值、结果脱敏、重复检测提醒、计时日志。
4. 工具注册表：插件提供 `{定义, 执行器, 可见范围}`，按 Agent 配置组合，而不是 switch。
5. 系统提示词段落注册：插件贡献一段提示词（例如"压缩说明""skill 使用说明"）。
6. 事件 sink：插件可发自定义事件（例如"已压缩 N 条"）。
7. 运行结束条件：`ask_user`/`finish` 这类"结束运行"的工具由工具本身声明（现在已有 `Step.ending`，这一点可以保留）。

今天这些逻辑分别埋在：`AgentLoop.call()`（`:236-242`）、`Conversation.messages()`、三个 `Round.messages()`、`HarnessLoop.Round.browser()`（`:534-672`，守卫+脱敏+日志+检测全在一个方法里）、`RuntimeEvents.observer()`。

---

## 3. Skill 格式兼容性（对照开放 Agent Skills 规范）

规范要点（agentskills.io / Anthropic Agent Skills）：目录名 = `name`；`SKILL.md` frontmatter 必填 `name`（≤64，小写字母数字连字符）、`description`（≤1024，写"做什么、何时用"）；可选 `license`、`compatibility`、`metadata`（任意键值）、`allowed-tools`（实验）；可选子目录 `scripts/`、`references/`、`assets/`；三级渐进披露：元数据常驻 → 触发时读 `SKILL.md` 正文 → 正文里按相对路径按需读 references / 运行 scripts。

| 方面 | 我们 | 兼容性 |
| --- | --- | --- |
| `name` 规则、与目录同名 | 一致（`SkillCatalog.java:45,211-217`） | 兼容；未检查长度上限，无害 |
| `description` | 必填，空白折叠 | 兼容 |
| 未知 frontmatter 键（`license`、`metadata`、`allowed-tools`、`compatibility`） | 忽略 | 能加载，但 `allowed-tools` 不生效 |
| 自有扩展键 `applies_to`/`version`/`observed_count` | 若外部 skill 恰好用同名键但类型不同（例如 `version: "1.0.0"` 是字符串 OK；`version: {..}` 会报错） | 基本兼容，极少数会进 `unavailable` |
| 元数据常驻 + 正文按需 | 有（目录 + `read_skill`） | 兼容第 1、2 级 |
| **第 3 级：references/assets** | **没有**：`read_skill` 只返回 `SKILL.md` 正文；没有"读 skill 目录里的文件"的工具，也没有通用文件读取工具 | **阻断**：正文里写"见 references/forms.md"时模型读不到 |
| **scripts/** | **没有**任何脚本/shell 执行能力（这也是 `dev-limit` 的安全取向） | **阻断**：依赖脚本的 skill（PDF/表格/格式化类）用不了 |
| 正文预算 | 8000 字符硬截断 | 规范建议 <500 行、~5k token，常见外部 skill 会被截 |
| 发现位置 | 只有 `storage/playbook/skills`；内置的要写进 `PlaybookSeeder.BUNDLED` | 无 `~/.claude/skills`、项目级目录、插件目录；无导入 UI/API |
| 可见范围 | 由 Loop 按第一层目录名硬编码过滤 | 顶层导入的 skill（`group = name`）：投递段、后续段能看到，**Session 看不到**（只列 `research`） |
| 触发 | 只靠模型读目录后自己 `read_skill`；平台 skill 按 URL 建议 | 与规范一致（规范也是模型决定） |
| 重复名 | 按路径排序第一个生效，后面的进 unavailable | 可接受 |

**今天导入一个外部 skill 文件夹会卡在**：(1) 没有导入入口，只能手动拷到 storage；(2) 若放在顶层，Session Agent 看不到；(3) `references/`、`assets/` 读不到，`scripts/` 跑不了；(4) 正文超 8000 字被截；(5) skill 的工具名假设（`Read`、`Bash` 等）在我们这里不存在，正文里的操作指令会失效。纯"经验文字型"的 skill（类似我们的 `components/*`）可以直接用。

---

## 4. 领域价值 vs 通用管道

### 4.1 真正领域化、值得保留的（基座里没有）

| 能力 | 代码 | 说明 |
| --- | --- | --- |
| 在用户自己的 Chrome 上并发操作 | `tools/BrowserSession`(759)、`OwnerBrowser`(625)、`PlaywrightMcpClient`(873)、`BrowserLaunch`、`resources/browser/*.cjs` | 共享 CDP 连接 + 每使用者一个 MCP 会话 + 按网址认标签页 + 转交 + 焦点模拟 + 卡住≠断开。踩坑最多、最难复刻的部分 |
| 固定页面脚本 | `tools/PageScripts`(900) | 下拉/级联/日期一次调用、只读框检查、上传 `setInputFiles` |
| 快照处理 | `PageSnapshot`、`SnapshotIndex`、`PopupView`、`LatestPage.base` | 只给最新快照、弹出层增量视图 |
| 提交守卫 | `harness/policies/SubmitGuard`(255)、`SubmitWords`、`GuardDecision` | 用户定下的硬线 |
| C 级字段与占位符 | `FieldClassGuard`、`SnapshotRedactor`、`Placeholder`、`PersonalEnv`，以及 `HarnessLoop.java:617-619` 的"发给 MCP 那一刻换真值" | 隐私硬线 |
| 浏览器白名单 | `BrowserToolPolicy`(398)、`WebUrlPolicy` | |
| 申请状态 | `domain/ApplicationState`、`StateView`、`update_state` 与 `recordFields` 自动记录 | 模型的工作底稿 |
| 资料/记忆 | `memory/*`（`ProfileResolver`、`FactWriter`、`MemoryWriter`、`ExperienceIndex` 本地 RAG、导入流水线） | |
| 台账与状态机 | `persistence/JobLedger`(1086)、`ApplicationStateMachine`、`HiringRound`、`TrackerWriter` | |
| 报告、运行日志 | `FillReportWriter`、`RunLog` | |
| 工作台 UI 与业务 API | `api/*`、`frontend/` | |
| 领域 skill 内容 | 11 个 `SKILL.md`、`SiteNotes` | 内容本身是资产，格式可迁移 |
| 领域提示词 | `agents/*` 的文本 | 内容是资产，载体可换 |

### 4.2 通用管道（基座可以替换）

| 能力 | 现在 | 基座通常提供 |
| --- | --- | --- |
| Agent 循环 | `AgentLoop` | 有（且带中间件/钩子） |
| 消息拼装、补中断调用、思考回传 | `Conversation` | 有 |
| 工具注册与分派 | `DefinitionOnly` ×5 + 三个 switch | 有（注册表 + schema） |
| 重试 | `ModelCaller` | 有 |
| 上下文压缩 | **缺** | opencode `compaction`、deepagents `SummarizationMiddleware` |
| skill（Agent Skills 全规范） | 半套 | 有（含 references/scripts） |
| 子 Agent | 自写线程 | `task` 工具 |
| 事件流 | `RuntimeEvents` + SSE | 有（opencode 有 bus + SSE；但我们的事件日志/重连语义已做好） |
| Provider 管理 | `ModelFactory` + 自写 ChatGPT 模型 | opencode 用 models.dev + AI SDK，覆盖面更广 |
| 运行中插话、停止、上限 | 三个 Runner 各一份 | 多数基座有 |
| Todo/计划 | `update_plan`（已并入 `update_state`） | 有 |

注意：Spring AI 本身在 1.1/2.x 已有 `ToolCallingManager`、Advisor 链和 MCP 工具适配；项目有意绕开（`ModelFactory.java` 类注释）。即使不换语言，"用 Advisor/中间件 + 真正的 ToolCallback 执行"也是一条路。

---

## 5. 看得到的痛点（带位置）

1. **上帝类 `HarnessLoop`（1805 行）**：内部类 `Round` 同时承担工具分派、20+ 个工具实现、守卫编排、占位符替换、申请状态维护、报告草稿、日志、开局打开页面、简历选择、用量统计。`browser()` 一个方法 `:534-672` 约 140 行，里面串了 SubmitGuard → 下拉/日期守卫 → C 级 → 只读框检查 → 白名单 → 换真值 → MCP → 记字段 → 日志 → 系统提醒 → 重复检测 → 弹出层视图，这些本应是 `wrapToolCall` 中间件链。
2. **构造器套娃 + 手动 new 依赖**：`HarnessLoop` 4 个重载构造器（`:158-189`），在构造器里 `new` 了 12 个持久化对象（`:176-185`）；`handle()` 每次工具调用都 `new JobLedger(layout, clock)`（`:508-509`，另见 `:1153`）。SessionLoop、FollowupLoop 同样有多重构造器（`SessionLoop.java:116-140`）。不可替换、不可测试、插件拿不到这些依赖。
3. **工具定义与执行分离且分散**：`DefinitionOnly` 复制 5 份（见 1.4）；名字常量在 `*Tools`，执行在 Loop 的 switch（`HarnessLoop.java:505-532,893-913`，`SessionLoop.java:299-330`，`FollowupLoop.java:332-365`）；`ResearchTools` 把 `ASK_USER/READ_SKILL/REMEMBER` 指向 `ControlTools` 常量（`ResearchTools.java:27-30`），但执行仍各写一遍。
4. **三个 Loop 的重复**：
   - `STOPPED` 常量 ×3（`HarnessLoop.java:109`、`SessionLoop.java:76`、`FollowupLoop.java:63`）；
   - `askUser` ×3（`HarnessLoop.java:915`、`SessionLoop.java:604`、`FollowupLoop.java:487`）；
   - `readSkill` ×3（`:1025` / `:611` / `:494`），`SkillCatalog.scan` ×3；
   - `limitTurn`/`summarized` ×3（`HarnessLoop.java:489-500`、`SessionLoop.java:234-244`、`FollowupLoop.java:179-188`），文案几乎相同；
   - `remember`、`read_resume`、`read_jd`、`choose_resume` 在两到三个 Loop 各有实现；
   - Runner 层：线程池 + `stopRequested` + `Inbox` + `OpenCodeSession.run` ×3（见 1.2）。
5. **按 Agent 硬编码的行为**：`dropReplyWhenStopped` 布尔参数区分申请/对话（`AgentLoop.java:120,166`）；skill 可见范围按目录名（`SessionLoop.java:275-278`、`FollowupLoop.java:232-235`）；`shouldDeferBrowser()` 靠对话里是否出现"匹配简历"四个字（`HarnessLoop.java:351-362`）；`SessionLoop.shortened()` 硬截 1500 字（`:247-263`）是另一个 Loop 没有的临时压缩。
6. **提示词在 Java 里**：`agents/ApplyAgent.java:17-163` 约 150 行中文文本块；系统插入对话的中文句子散落在 HarnessLoop（`:330-331,404-428`）和工具结果拼接里（`:648-660`）。无法外置、无法由插件追加、不能热改。
7. **`FillContext.Inputs` 13 参数 record + 兼容构造**（`FillContext.java:50-79`）：每加一种上下文就加一个参数，典型的"缺少上下文提供者注册"信号。
8. **没有中间件层**：`AgentLoop.call()`（`:236-242`）直接 `agent.messages()` → `ModelCaller.call()`，中间没有可挂钩的位置；`LoopObserver` 只读。压缩、缓存断点、超长重试都没有地方放。
9. **没有流式**：`ModelCaller.call` 同步阻塞；前端只能看到"正在思考"→ 整段回复。
10. **Provider 分支按模型名子串**：`ModelFactory.thinkingBody`（`:142-168`）、Anthropic 特判 `maxTokens`（`:192-194`）；自写 `ChatGptResponsesChatModel`（511 行）+ `ChatGptAuth`（646 行）维护成本高。
11. **前端按工具名硬编码**：`frontend/src/chat/process.ts:143-169`，新增工具不改前端就显示原名。
12. **浏览器层与 Loop 耦合点**：`HarnessLoop` 直接调用 `browser.act/open/select/takeHandover/takeNotice/ownsTab`，并在 Loop 里处理 `Disconnected/Stalled/ToolFailed/TabLost` 五种异常（`:620-635`）；SessionLoop/FollowupLoop 经 `ReadOnlyTab` 又是另一套。若迁移基座，这一层最好收成"一个浏览器工具服务"（自己是 MCP server 或一组工具），守卫在服务内部执行，Loop 不感知。

---

## 6. 对"换基座 / 插件化"的含义（只陈述事实，不做决定）

- 可以被基座直接替换的：`AgentLoop`、`ModelCaller`、`Conversation` 的通用部分、`DefinitionOnly`/switch 分派、`SkillCatalog`（换成支持完整规范的实现）、三个 Runner 的线程/停止/插话、Provider 工厂（若基座覆盖 ChatGPT 套餐登录和 OpenCode 头）。
- 需要以"插件/工具服务"形式迁过去的：浏览器服务（BrowserSession/OwnerBrowser/PlaywrightMcpClient/PageScripts + 白名单 + 提交守卫 + C 级/占位符 + 快照脱敏/弹出层视图）、申请状态（作为状态 + `update_state` 工具 + `beforeModel` 注入）、台账/记忆/RAG 工具、报告与运行日志（`afterTool` 钩子）。
- 关键约束：提交守卫和占位符换真值必须在**工具执行内部**（不能是可被绕过的提示词或可选中间件）；快照脱敏必须在结果进入模型之前；这些在任何基座里都应放进浏览器工具服务本身。
- 现有 SSE 事件日志（序号、补发、`reset`）和工作台 UI 是领域外壳，换基座后仍需要一个适配层把基座事件翻成现有事件，或者前端改接基座的事件流。
- 语言栈：基座若是 opencode（TypeScript/Bun），会和 Java 后端形成两个进程；浏览器层、台账、记忆可以作为 MCP server 或 HTTP 工具留在 Java 侧，也可以整体迁移。具体取舍属于计划范畴，需用户定。
