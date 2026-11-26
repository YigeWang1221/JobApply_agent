# 方案 A：留在 Java，照 opencode 的插件形状重构 Agent 运行时

日期：2026-11-25。分支 `C30-plugins`（基于 `e255069`）。

**草案，只是一个备选方向，等用户在 A / B / C 里选（见 [research-agent-platform.md](../research-agent-platform.md) 第 4 节）。** 用户没选 A 之前，不写 Coding Plan，不动代码。选了 A 以后，把第 1、4 节里要改 Master 的部分回写 `plan/master/agent-runtime.md`、`architecture.md`（要用户确认），每个工作包再各写一份 Coding Plan。

依据：[B-our-agent.md](B-our-agent.md)（我们的运行时审计，带行号）、[C-opencode.md](C-opencode.md)（opencode V2 钩子）、[D-other-agents.md](D-other-agents.md)（Spring AI 2.1、spring-ai-agent-utils、Deep Agents、Hermes）、[A-context-panel.md](A-context-panel.md)（上下文面板）、[gap-context-compaction.md](../gap-context-compaction.md)。下文路径相对 `backend/src/main/java/jobapply/`。

## 0. 一句话

循环内核（`AgentLoop` 275 行）留着，把它外面按「三种 Agent」写死的东西拆开：**一个循环 + 一张工具表 + 一层插件 + 三份 Agent 定义**。压缩、上下文面板这类新功能都写成插件，不再改核心类；skill 按 Agent Skills 规范完整导入。提交守卫、占位符换值、快照脱敏留在浏览器工具里面，不做成可关的插件。

---

## 1. 目标架构

### 1.1 结构图

```
            Runner 层（workflow/）：线程、Worker 池、停止、收件箱、台账状态
  ApplicationRunner ─┐   SessionRunner ─┐   FollowupRunner ─┐
                     └──────────────────┴───────┬───────────┘
                                                ▼
                         AgentRun 核心（harness/runtime/）
   AgentSpec（agents/）──▶ 一个循环：停止? → 收件箱 → 上限? → 拼请求 → 调模型 → 执行工具 → 放回对话
   提示词 + 工具集 +        │            ▲                    │               │
   skill 范围 + 插件        │      PluginChain               ToolRegistry      EventBus
                            │  prompt / context /         （工具定义+执行器）  （tool.* / usage /
                            │  compaction / tool.before /        │             compaction.* / delta）
                            │  tool.after / response             │                    │
                            ▼                                    ▼                    ▼
                     ModelFactory（config/）           Toolsets（tools/）        RuntimeEvents → SSE
                     OpenAI / Anthropic / ChatGPT      Browser（内含提交守卫、
                     DeepSeek、Gemini 思考参数          占位符换值、快照脱敏）、
                                                       Control、Workbench、
                                                       Research、Followup、Skill、MCP
```

### 1.2 核心循环

- 还是现在 `AgentLoop.run()` 那一圈（停止检查 → 取收件箱 → 工具调用上限 → 调模型 → 只回文字就结束 → 依次执行工具 → 结果放回对话 → 下一轮；上限时多调一次让模型总结）。行为不变，只改三处：
  1. 拼请求不再问 `Agent.messages()`，而是：通用的消息拼装（`Conversation`）→ 插件的 `context` 钩子按顺序改这次请求 → 发出。
  2. 执行工具不再进每个 Loop 的 `switch`，而是：插件 `tool.before` → 工具表里的执行器 → 插件 `tool.after`。
  3. `LoopObserver` 换成事件总线：循环发事件，`RuntimeEvents` 和插件都是订阅方。
- 「结束这次运行」仍由工具自己声明（现在的 `Step.ending`，`ask_user`、`finish` 用它），不在循环里按名字判断。
- 停止、插话、上限三件事留在核心，不做成插件：它们决定循环怎么转，三种 Agent 都要，现在复制了三份（B 第 5 节第 4 条）。

### 1.3 插件接口（钩子名和语义对齐 opencode V2 / Claude Code）

一个插件 = 一个 Java 对象，有 `id`，实现下面任意几个钩子（都有空的默认实现）。按 Agent 定义里列的顺序串起来。

| 钩子 | 什么时候调、拿到什么、能改什么 | 对照 |
| --- | --- | --- |
| `prompt(sections)` | 一次运行开始时。拿到系统提示词的分段表（每段有 `key` 和文字），可以加段、改段、删段。 | opencode `instructions.entry`、Hermes `prompt_builder` |
| `tools(registry)` | 一次运行开始时。可以给这个 Agent 加工具、去掉工具、改工具说明。 | opencode `ctx.tool.transform` |
| `context(request)` | **每次调模型前**。拿到这次要发的请求：系统分段、消息列表、工具定义、选项（maxTokens 等）、本次运行的身份（Agent、Session / Job）和上一次的用量。可以改；**只影响这一次请求，不改存下的对话**。 | opencode `session.hook("context")`、Deep Agents `wrap_model_call`、pi `context` |
| `response(response)` | 每次模型回来后。拿到回复和用量（输入、输出、缓存读写、思考），只读。 | Hermes `post_llm_call`、Deep Agents `after_model` |
| `compaction(event)` | 压缩时。拿到要被总结的那段对话和上一份摘要；可以改输入，或者直接给出 `summary` 接管（不再调模型）。 | opencode `session.hook("compaction")`、Claude Code `PreCompact` |
| `toolBefore(call)` | 执行一个工具前。拿到工具名和参数，可以改参数；抛「拒绝」就只拒绝这一次调用，原因作为工具结果交给模型。 | opencode `tool.execute.before`、Claude Code `PreToolUse` |
| `toolAfter(call, result)` | 执行完以后。可以改结果文字、在后面追加一句（例如重复提醒）、看到出错。 | opencode `tool.execute.after`、Claude Code `PostToolUse` |
| `on(event)` | 订阅事件（`run.started/ended`、`tool.called/done`、`usage.updated`、`compaction.*`），也可以发自己的事件。 | opencode `ctx.event.subscribe`、Claude Code `Stop` 等 |

约定：
- 插件出错只记日志、跳过这个插件，不打断循环（A 报告第 3 节的做法）；`toolBefore` 的「拒绝」是有意的结果，不算出错。
- 插件拿依赖走 Spring 注入（台账、记忆、设置），不再在构造器里 `new`（B 第 5 节第 2 条）。
- 每段系统提示词、每类上下文都带 `key`（来源），面板和压缩靠它分类（A 报告第 3 节第 5 点）。
- 第三方不能往这里塞代码，见第 4.3 节。

### 1.4 三种 Agent 变成三份定义

`AgentSpec`（放 `agents/`，这个包本来就是「Agent 角色与 Catalog」）：

| 字段 | 投递段 apply | Session | 后续段 followup |
| --- | --- | --- | --- |
| 提示词 | `resources/agents/apply.md` | `research.md` | `followup.md` |
| 工具集 | Browser（全部）、Control、Workbench、`mark_submitted` | Research、只读 Browser、Control（不含 `finish`、`update_state`） | Followup、只读 Browser、Workbench、Control |
| 上下文提供者 | 资料、本次答案、可上传文件、站点 / 公司记录、lessons、画像、岗位备注（现在 `FillContext` 的内容） | 候选池、Project 简介 | 整理好的岗位材料 |
| skill 范围 | `apply` | `session` | `followup` |
| 插件 | application-state、page-elision、loop-reminder、compaction、context-usage | page-elision、compaction、context-usage | page-elision、compaction、context-usage |
| 上限 | 每次运行（设置页） | 每条消息（设置页） | 每次运行 |
| 停止时 | 丢掉在途回复 | 记下回复，工具写「没有执行」 | 同 Session |

- 提示词从 Java 文本块（`ApplyAgent.java:17-163` 等）搬到 classpath 的 markdown：frontmatter 写 Agent 的元数据，正文按 `##` 分段，每段就是一个带 `key` 的提示词段落。内容原样搬，不改写。
- 每种 Agent 自己特有的一次运行状态（投递段的申请状态、报告草稿、守卫批准、占位符键名、最新页面）放进一个「运行状态」对象，由工具和插件从工具上下文里取。投递段这个对象就是 `HarnessLoop.Round` 剩下的领域部分，会小很多，但不会消失。
- 「子 Agent」现在已经有一种：Session 的 `start_application` 让投递段在后台跑，结束时回报父 Session（`agent-runtime.md` 第 8 节第 2 条），语义和 opencode 的后台子会话一样。重构后它就是「用另一份 AgentSpec 起一次运行」，不另造。

### 1.5 Spring AI：用什么、不用什么

现状：Spring Boot 4.1.1 + Spring AI 2.0.1（`backend/pom.xml:10,22`），本地 Maven 仓库只有 2.0.1。

| Spring AI 的东西 | 决定 | 理由 |
| --- | --- | --- |
| 升到 2.1 | **这次不升，不是前提** | 下面要用的东西 2.0.1 都有。2.1 要 Spring Framework 7.1（spring-ai-agent-utils 的 pom 写的是 `spring-ai 2.1.0` + `spring-framework 7.1.0`），大概率要连带升 Boot 4.2，**未核对**。以后真要用 2.1 的东西再单独升一次。 |
| `ChatModel.call()` / `stream()`、消息类型、`ToolDefinition` | 用 | 现在就在用；流式用 `stream()`（第 5 节 WP7）。 |
| `ToolCallback` 真正执行（`call()`） | 部分用 | 工具表里每个工具仍以 `ToolCallback` 的定义交给模型；执行由我们的工具表调，不交给 `ToolCallingManager`，因为执行中要能停、要带页面、要能结束运行。删掉 5 份「只有定义、`call()` 抛异常」的 `DefinitionOnly`。 |
| `ChatClient` + Advisor 链 + `ToolCallingAdvisor` | 不用 | `ToolCallingAdvisor` 在内部自己跑工具循环，我们要的「两个工具之间查停止、取插话、工具声明结束」放不进去；`dev-limit.md` 也写了不用框架封装的 ReAct Agent。只借它「有序拦截链」的形状。 |
| Session 模块（2.1 的会话压缩） | 不用，照它的原则自己写 | 原则一样：压缩只追加分界，原文不改，正合 `memory.md`「transcript 原文不压缩」。但它自带事件存储（InMemory / JDBC），我们的对话是 `transcript.jsonl` 和 `runs/<job>.json`，接过去要写仓储适配和两套格式映射；触发器是按轮数的（`TurnCountTrigger`），我们要按窗口占比和「超长报错」触发，还要和页面省略配合。自己写约三四百行，比接它省事。 |
| spring-ai-agent-utils（`SkillsTool`、`TaskTool` 等） | 不引入，参考 | 孵化项目、要 2.1；我们已有 `SkillCatalog`，补齐规范比换掉便宜。读它的 `SkillsTool` 对照规范细节。 |
| MCP Java SDK（`spring-ai-starter-mcp-client`） | 继续用 | Playwright MCP 已在用；第三方 MCP 服务器（WP8）也用它。 |

---

## 2. 现有功能各变成什么

| 功能 | 现在在哪 | 重构后 | 能不能关 |
| --- | --- | --- | --- |
| 提交守卫 | `HarnessLoop.browser()` 里调 `SubmitGuard` | 浏览器工具执行器内部，发 MCP 之前 | 不能（用户的线） |
| 占位符换真值 | `HarnessLoop.java:617-619` | 浏览器工具执行器内部，发 MCP 的那一刻；插件看到的参数和结果都只有占位符 | 不能 |
| 快照脱敏 | `Conversation.render()` 里 `SnapshotRedactor` | **挪到浏览器工具产出页面的地方**：交给循环的页面已经脱敏，任何插件、日志、面板都拿不到 C 级原值。守卫判断仍用工具内部的原始快照（只看元素名，不看值）。 | 不能 |
| 白名单、上传路径 | `BrowserToolPolicy`、`WebUrlPolicy` | 浏览器工具执行器内部 | 不能 |
| 申请状态 | `StateView` + `Conversation.appendToLast` | 插件 application-state：`context` 里接在最后一条消息末尾；`toolAfter` 里自动记填写结果（现在的 `recordFields`）；`update_state` 工具由它注册 | 只挂在投递段 |
| 只给最新快照 | `Conversation` 里 `OMITTED_SNAPSHOT`、`LatestPage.base` | 插件 page-elision（`context`），算法照搬，包括弹出层时留住整页 | 三段都挂 |
| 打转提醒 | `LoopDetector`，只在 HarnessLoop | 插件 loop-reminder（`toolAfter` 追加一句），只在结果里说，不强制 | 投递段 |
| Session 旧工具结果截到 1500 字 | `SessionLoop.shortened()` | 删，并进压缩插件的「保留区里旧的长工具结果在发出时截短」 | — |
| 工具调用上限 | 三个 Loop 的 `limitTurn` / `summarized` | 核心循环，上限值来自 AgentSpec（设置页的两个数，`ToolCallLimits` 不变） | 设置页设 0 = 不限 |
| 插话（steer / inbox） | 三个 Runner 各一份 `Inbox` | 核心循环取；Runner 共用一个「运行控制」（停止标记 + 收件箱 + 线程），`ApplicationRunner` 的 Worker 池和台账逻辑不动 | — |
| 停止 | 三份 `stopRequested` | 同上；「丢不丢在途回复」是 AgentSpec 的一个字段 | — |
| 事件日志 / SSE | `RuntimeEvents` + `LoopObserver` 五个回调 | `RuntimeEvents` 订阅事件总线，现有事件名（activity / step / message / status / changed / browser）不变；新增 `delta`（流式文字、思考）、`usage`、`compaction` | — |
| 记忆 / RAG | `search_experience`、`remember` 等散在三个 Loop | 工具进 Workbench / Control 工具集，各写一次；资料、lessons 的注入是 AgentSpec 的上下文提供者 | — |
| skill 目录 | 每个 Loop 自己 `scan`、按目录名过滤、各写一遍 `readSkill` | 一个 `SkillCatalog` 服务，范围由 skill 自己声明 + Agent 定义过滤；`read_skill`、`read_skill_file` 两个工具，见第 4 节 | 每个 skill 可在设置页停用 |
| 模型（ChatGPT 套餐、DeepSeek / Gemini 思考） | `ModelFactory`、`ChatGptResponsesChatModel`、`GeminiThoughtSignatures` | **不动**。只在模型设置里加「上下文窗口」一个数（面板和压缩要用）。不做 provider 插件 | — |
| 前端工具展示 | `process.ts:143-169` 按工具名写死 | 工具定义多带 `label`、`icon`，随 Agent 的工具表经接口给前端；`process.ts` 先用接口给的，没有再用现在的表 | — |

---

## 3. 内置插件

### 3.1 compaction（上下文压缩）

照 opencode V2 `compaction.ts` 和 Deep Agents summarization，参数取 opencode 的默认值。

- **什么时候压**：
  - 每次调模型前估这次请求多大：上一次回复报的输入 token + 之后新加的对话按估算器（3.2）算。超过 `窗口 − max(15% 窗口, 16k)` 就先压缩再发。
  - 服务商报上下文超长（按错误文字认：`context_length_exceeded`、`prompt is too long`、`maximum context length` 等，放在 `ModelCaller` 的错误分类旁边）：强制压缩，再重试一次；还不行照常失败，原因给用户。
  - 用户在面板上点「压缩」：在跑时在下一个安全点（两次调模型之间）做，空闲时马上做。
- **怎么压**：
  - 从末尾往前留大约 15k token 的原文（不超过窗口的 25%），切点只放在用户消息之前，不把一次工具调用和它的结果拆开。
  - 切点之前的对话 + 上一份摘要，发给主模型（同一个窗口、能用上缓存；设置里可改成辅助模型），按固定模板写摘要：目标、用户说过的要求和决定、已完成、进行中、卡住的、下一步、要记住的事实（文件编号、站点规律、用户给过的答案）。投递段的字段清单已经在申请状态里，摘要不重复它。
  - 再压一次时是「更新上一份摘要」，不是从头总结（Hermes、opencode 都这样）。
  - 保留区里较早的长工具结果在发出时截短（取代 `SessionLoop.shortened()`）；快照照旧由 page-elision 处理。
- **存在哪**：
  - `transcript.jsonl` 和 `runs/<job>.json` 的原文**一条都不改、不删**。在对话末尾追加一条新类型的对话 `compaction`：摘要文字 + 第一条保留的对话序号。
  - 拼消息时遇到最后一条 `compaction`：系统提示词 → 上下文 → 摘要（作为一条用户消息，标明「较早对话的摘要」）→ 保留的对话 → 之后的新对话。
  - 界面上 `compaction` 显示成一条分隔线「较早的对话已压缩」，能展开看摘要；之前的消息照常能翻。
- **事件**：`compaction.started / ended / failed`，带压掉多少、留下多少。
- **出错时**：摘要调用失败，如果还没到硬上限就不压、照常发；到了就失败，原因写进对话的结束说明。

### 3.2 context-usage（上下文用量面板）

照 A 报告里那个 zip 的做法：类别比例靠估，总量用服务商报的真数。

- **后端**：
  - 估算器：按字符类别估 token（中日韩 1.5 字符 / token，其它 4），每条消息加 4；几十行，不引 tokenizer。
  - 插件在 `context` 钩子里**最后一个**执行，把这次真正要发的请求按 `key` 分类：系统提示词各段、工具定义（逐个）、skill 正文（`read_skill` 的结果）、当前页面快照、申请状态、其它工具结果、用户消息、模型回复和思考、压缩摘要。
  - `response` 钩子拿到真实输入 token 后，把估算比例等比缩放到真实总量；再给出：窗口、已用、剩余、离自动压缩还差多少、缓存命中率、上一次调用时间。
  - 每个对话（Session / 投递段 / 后续段）在内存里留最近一份；应用重启后第一次打开面板时从存下的对话估一份（标明是估算）。
  - 接口：`GET` 取这份用量，`POST` 触发压缩（3.1）；每次调模型后发一个 `usage` 事件。
- **前端**：
  - 对话视图和申请工作台两段的输入框上方放一个小用量条（「上下文 43%」）。
  - 点开是侧栏面板：一条按类别分色的横条 + 每类的数 + 展开看明细（每个工具定义、每个读过的 skill 各多少）+「压缩」按钮。
  - 靠 `usage` 事件刷新，不轮询。
- **上下文窗口从哪来**：模型设置里每个模型加 `contextWindow`。预设的模型（`ModelPresets`）写好已知值；模型列表接口返回了就用它的；都没有时用 128k 并在设置页提示用户填。

### 3.3 其它（都是把已有逻辑搬过来，不是新功能）

- application-state、page-elision、loop-reminder：见第 2 节。
- 不另加新插件。审计里提到的「缓存断点提示」「大结果转存文件」这次不做（第 6 节）。

---

## 4. Skill 导入与插件打包

### 4.1 支持完整的 Agent Skills 规范

- **frontmatter**：认规范里的全部字段：`name`（≤64，和目录同名）、`description`（≤1024）、`license`、`compatibility`、`metadata`、`allowed-tools`。
  - `allowed-tools` 读进来、在设置页显示，不生效：它在 Claude Code 里是「免确认的工具」，我们本来就不弹确认，没有对应的东西。
  - 我们自己的键（`applies_to`、`version`、`observed_count`）以后写在 `metadata.jobapply.*` 下，旧的顶层写法继续认。
- **渐进加载三级**：
  1. 每步只列名字和描述（不变）。
  2. `read_skill(name)` 返回正文 + skill 目录里的文件清单（相对路径、大小，最多几十个）。正文上限从 8000 字放到约 30000 字，超了照旧标明截断。
  3. 新工具 `read_skill_file(name, path)`：读 `references/`、`assets/` 等 skill 目录里的文本文件，只能读这个 skill 目录里面的；二进制文件只在清单里列出名字和大小。
- **`scripts/` 不执行**。理由：
  - 我们没有 shell 工具，也不打算加；Agent 的动作发生在浏览器里，这类脚本（PDF、表格、格式化）是给写代码的 Agent 准备的，对投递帮助小。
  - 跑第三方脚本 = 在用户机器上执行别人的代码，能读到 `storage/settings/.env`、`personal.env`，直接撞上「密钥不外泄」这条用户的线。
  - 脚本可以当文本读（`read_skill_file`），模型能看懂它的逻辑；提示词里加一段兼容说明：「skill 里说用 Read 读文件 → 用 `read_skill_file`；说运行脚本或 Bash → 本应用不支持，需要时告诉用户」。
  - 以后真有需要，再单独讨论「用 App 已带的 node 起子进程、去掉环境变量、工作目录限在 skill 目录」。
- **范围**：不再按第一层目录名写死过滤。
  - skill 在 `metadata.jobapply.agents` 里写给哪些 Agent（`apply` / `session` / `followup`）；导入的 skill 没写时默认三段都可见。
  - 内置 skill 补上这个键；用户改过、没有这个键的旧 skill 按现在的目录规则推出来（`research` → Session，`components` 和 `apply-form` → 投递段……），行为和今天一样。
  - 设置页的 skill 列表能停用某个 skill、改它给哪些 Agent。

### 4.2 导入

- 入口：设置页「Skill」，三种来源：
  - **文件夹**：用现有的目录选择（`DirectoryBrowser`）选本机文件夹；
  - **zip**：上传；
  - **git**：填仓库地址（可带分支和子路径），用已有的 JGit 浅克隆到临时目录。
- 识别：
  - 根目录有 `SKILL.md` → 一个 skill；
  - 有 `skills/*/SKILL.md`（Claude Code 插件、Agent Plugins 的布局，带不带 `.claude-plugin/plugin.json` 都行）→ 多个；
  - 其它情况在三层目录内找 `SKILL.md`。
- 导入前先给预览：每个 skill 的名字、描述、文件清单和大小、有没有 `scripts/`（标明「不会执行」）、规范校验的问题。用户勾选后装到 `storage/playbook/skills/<name>/`；重名时问覆盖还是跳过。
- 解压和拷贝时不写出目标目录、不跟符号链接（这是正确处理文件，不是额外的守卫）。
- 记一份来源（目录 / zip 名 / git 地址和提交），设置页能看到、能删除、git 来源能「更新」。
- 不自动扫 `~/.claude/skills`、`~/.agents/skills`：那里多是写代码用的 skill，自动带进来会污染投递 Agent 的目录。想要就用「文件夹」导入。

### 4.3 第三方插件能做到哪一步（说实话）

- **能导入的**：skill（4.1、4.2），以及 MCP 服务器提供的工具（WP8）。一个按 Claude Code / Agent Plugins 布局的插件文件夹（`plugin.json` + `skills/` + `.mcp.json`）可以整个导入：skill 照装，`.mcp.json` 里的服务器列出来让用户勾选加进 MCP 设置。
- **不能导入的**：
  - JS / Python 代码插件：Java 里跑不了，opencode 的插件生态用不上；
  - `hooks/hooks.json` 的命令型 hook：等于执行 shell 命令，同 `scripts/` 的理由；
  - Java 的第三方 jar 插件：技术上能用 `ServiceLoader` + 独立类加载器加载，但没有隔离、和内部类型绑死、我们的接口还在变，维护成本远大于收益。不做。
- **插件接口本身**只给我们自己写的内置插件用（3.1–3.3），设置页能按 Agent 开关。因为钩子形状照 opencode V2 定，以后真要换到 opencode（方向 B），这些插件的逻辑能一一对上。

---

## 5. 工作包（按施工顺序）

一份 Coding Plan 一个工作包，一个工作包一次提交，单独能 review。每个做完都要能跑：真实模型 + 夹具试一次投递，Session 和后续段各试一次对话。大小是粗估的改动行数。

| # | 工作包 | 改什么 | 做完用户能看到什么 | 大小 |
| --- | --- | --- | --- | --- |
| WP1 | 工具表 | 新的工具接口：定义 + 执行器 + 展示信息（`label`、`icon`）；按领域分成几个工具集（Browser、Control、Workbench、Research、Followup、Skill）。删掉 5 份 `DefinitionOnly` 和三个 Loop 里按名字的 `switch`；`askUser`、`readSkill`、`remember`、`read_resume` 等只留一份。浏览器工具集内部带守卫、占位符、脱敏，从 `HarnessLoop.browser()` 原样搬出。 | 行为不变。新加工具只改一个地方。前端工具名的显示改成后端给的。 | 大，约 1500 行动，净减 300 |
| WP2 | 一个循环 + Agent 定义 | `AgentSpec`；提示词搬到 `resources/agents/*.md`；三个 `Round` 合成「通用运行 + 各自的运行状态 + 上下文提供者」；三个 Runner 共用一个运行控制（停止、收件箱、线程）。`HarnessLoop`（1805）、`SessionLoop`（675）、`FollowupLoop`（531）拆掉。 | 行为不变。三段对话照常；提示词能直接看 md 文件。 | 大，约 2000 行动，净减 1000 |
| WP3 | 插件层 + 事件总线 | 插件接口和插件链；`LoopObserver` 换成事件总线，`RuntimeEvents` 改为订阅方；申请状态、只给最新快照、打转提醒、Session 截短这四处搬成插件；快照脱敏挪到浏览器工具出口。 | 行为不变。设置页能看到每段挂了哪些插件。 | 中，约 800 行 |
| WP4 | 用量记录 + 上下文面板 | 模型设置加 `contextWindow`；每次调模型记完整用量；估算器；context-usage 插件；接口和 `usage` 事件；前端用量条和侧栏面板。 | 三段对话都能看到上下文用了多少、各类占多少。 | 中，后端约 400、前端约 400 |
| WP5 | 压缩 | compaction 插件；`compaction` 对话类型和消息拼装；超长报错后压缩重试；面板上的「压缩」按钮；前端分隔线。 | 长对话不再撑爆窗口；面板上能手动压缩；对话里能看到在哪压过、摘要写了什么。 | 中，约 600 行 |
| WP6 | Skill 全规范 + 导入 | frontmatter 全字段；`read_skill` 带文件清单、放宽正文上限；`read_skill_file`；按 skill 声明的范围；设置页 Skill 列表（停用、范围、来源）；文件夹 / zip / git 导入和预览；内置 skill 补 `metadata.jobapply`。 | 能导入一个带 `references/` 的外部 skill，三段都能读到它的附属文件。 | 中大，后端约 700、前端约 400 |
| WP7 | 流式 | 调模型改用 `stream()`，边收边发 `delta` 事件（文字、思考），收完拼成一条照旧存；前端边收边显示。每个接口格式能单独退回 `call()`（设置里一个开关），先确认 OpenAI 兼容、Anthropic、ChatGPT 三种格式下流式的工具调用能正确拼起来。 | 回复和思考一个字一个字出来，不再整段等。 | 中，约 500 行 |
| WP8 | 第三方 MCP 工具 | 设置页加 MCP 服务器（本地命令或 HTTP 地址，环境变量只给用户写明的那些，可用 `${变量名}` 引用 `.env`，和模型 Key 一样）；连上后列出工具，按 `mcp__服务器__工具` 命名进工具表，按 Agent 勾选；插件文件夹里的 `.mcp.json` 能导入成这里的一条。 | 能接一个外部 MCP 服务器，让 Session Agent 用它的工具。 | 中小，约 400 行 |

顺序的理由：WP1 → WP2 → WP3 是地基（工具执行只有一个入口，钩子才有地方挂）；WP4 在 WP5 前，因为压缩要用窗口和用量；WP6、WP7、WP8 互不依赖，可以并行施工，注意设置页和 `api.ts` 的共享改动。

### 5.1 删掉什么

- `DefinitionOnly` ×5、三个 Loop 里的工具 `switch`。
- `HarnessLoop`、`SessionLoop`、`FollowupLoop` 三个类（领域部分搬进投递段的运行状态和各工具集）；`AgentLoop.Agent` 这个大接口；`LoopObserver`。
- 三份 `STOPPED`、`askUser`、`readSkill`、`limitTurn` / `summarized`；三份 Runner 的停止和收件箱。
- `SessionLoop.shortened()`；按目录名过滤 skill 的三处；`shouldDeferBrowser()` 靠「匹配简历」四个字判断的写法（改成投递段运行状态里的一个明确标记）。
- `ApplyAgent`、`ResearchAgent`、`FollowupAgent` 里的提示词文本块（内容搬进 md）。
- `FillContext.Inputs` 的 13 个参数和兼容构造（换成上下文提供者）。
- 测试跟着代码走：测被删的内部方法的测试（如 `PersonalPlaceholderTest`、`StateValueTest` 里依赖 `HarnessLoop` 内部的部分）随代码移动或删除；守卫、脱敏、浏览器、持久化格式的测试保留。不为新结构补写同类测试。

### 5.2 存下的对话怎么迁移

- **不用迁移**。`AgentRun.Turn` 的格式不变，`transcript.jsonl`、`runs/<job>.json`、后续段的 transcript 照旧读。
- 只新增一种对话类型 `compaction`。旧对话里没有它，就整段发；很长的旧对话第一次跑时会先被压缩一次，原文照旧留着。
- 旧前端遇到不认识的类型要能跳过不报错（WP5 里一起改）。
- 用户改过的 skill 没有新键，按旧目录规则推范围（4.1）。`models.yaml` 没有 `contextWindow` 时用预设或默认值。
- 回退：每个工作包单独提交，任何一个出问题可以单独撤回；存储格式只多不改，撤回后旧代码照样能读（`compaction` 对话会被旧代码当作未知类型，WP5 里确认旧读取逻辑能跳过）。

### 5.3 风险

| 风险 | 说明 | 怎么办 |
| --- | --- | --- |
| WP1、WP2 改动大 | 碰到投递段最核心、踩坑最多的代码（守卫、占位符、弹出层视图、浏览器异常的五种处理） | 浏览器工具执行器里的逻辑原样搬，不顺手改；每个包做完用夹具 + 用户 Chrome 各跑一次投递 |
| 流式的工具调用拼装 | Spring AI 各模型流式时工具调用分片怎么合并、思考原文在不在流里，要逐个核对；`GeminiThoughtSignatures` 拦截器现在读的是非流式的回复 | WP7 每种格式单独开关，有问题的格式先退回 `call()` |
| 压缩丢信息 | 摘要漏掉用户说过的关键要求，模型之后做错 | 投递段的关键数据本来就在申请状态和本次答案里，不靠对话；摘要模板单列「用户的要求和决定」；原文都在，能翻 |
| 估算偏差 | 中文网页、工具 schema 的估算不准 | 只用来分比例，总量用服务商的真数；压缩触发主要看真数 |
| 上下文窗口不准 | 自定义的 OpenAI 兼容模型不知道窗口 | 默认 128k 并在设置页提示；超长报错后压缩重试兜底 |
| 插件顺序 | 几个插件都改同一次请求，顺序不对结果就不对（例如 context-usage 必须在最后） | 顺序写在 AgentSpec 里，固定，不让插件自己排 |
| 新目录 | 插件层要放在哪个包：建议 `harness/plugins/`（和 `runtime`、`context`、`policies` 并列） | 要用户确认，确认后改 `architecture.md` |

---

## 6. 不做的事

- 不换语言、不引入 opencode / pi / Deep Agents 的运行时；不升 Spring AI 2.1，不用 `ChatClient` / Advisor / Session 模块（理由见 1.5）。
- 不执行 skill 的 `scripts/`，不加 shell 工具，不支持命令型 hook。
- 不加载第三方 Java / JS / Python 代码插件；不做插件市场、自动更新。
- 不做通用的 `task` 子 Agent 工具。理由：现在没有「需要开一个干净上下文去调研再回来」的实际卡点；有了压缩以后 Session 的上下文压力会小很多；AgentSpec 结构在，以后要加只是一个工具。
- 不做 provider 插件、不换掉 `ModelFactory` / ChatGPT 套餐登录 / 思考参数的写法。
- 不做「大工具结果转存文件」「缓存断点提示」「后台预压缩」。
- 不自动扫 `~/.claude/skills` 等目录。
- 不改提示词的内容、不改 skill 的内容（只搬位置、补 frontmatter 键）。
- 不加新的守卫、确认、预算；`allowed-tools` 不生效。
- 不改浏览器层（`BrowserSession`、`OwnerBrowser`、`PlaywrightMcpClient`、`PageScripts`）和存储格式（只新增 `compaction` 一种对话）。
- 不为新结构补写测试。

## 7. 没核实的

- Spring AI 2.1 是否必须 Boot 4.2（只看到 agent-utils 的 pom 要 Framework 7.1）。
- Spring AI 2.0.1 下 OpenAI 兼容 / Anthropic 的 `stream()` 在关掉内部工具执行时，工具调用是否已合并好；DeepSeek `reasoning_content` 是否在流里。
- 各服务商「上下文超长」报错的确切文字。
- Anthropic / OpenAI 的模型列表接口是否返回上下文窗口。
