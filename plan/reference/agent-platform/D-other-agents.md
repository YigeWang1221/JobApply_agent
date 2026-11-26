# 调研 D：主流 Agent 基底与扩展模型对比（JobApply 选型参考）

日期：2026-11-25。只读调研，未改用户仓库和参考仓库。opencode 由另一份调研深入覆盖，这里只作对照。

## 0. 来源与版本

本机只读克隆（未 pull）：

| 仓库 | 提交 | 提交日期 |
| --- | --- | --- |
| `../deepagents` | `3e7d5faa9d275beb3d54f11266aac3c60e3d50b9`（deepagents 0.7.31） | 2026-10-23 |
| `../langgraphjs` | `c6675bf03f353ff32fb2ee0a82adb3541c0aee40` | 2026-10-22 |
| `../OpenMAIC` | `dff201b49857918885cc5ebadb4a8af254b6ae9d`（依赖 `@earendil-works/pi-agent-core` 0.78.0） | 2026-10-23 |
| `../deep-agents-ui` | `f6a4f34565b42688be06498031fc9351c152614e` | 2026-05-14 |
| `~/.hermes/hermes-agent` | `e5dad4ac57ad11d39fe18b1ac895c0e0ae5b6494`（0.12.0；未读配置/密钥/memories/日志） | 2026-04-30 |

本次 `git clone --depth 1` 到 scratchpad `repos/`（未运行安装脚本）：

| 仓库 | 提交 | 日期 |
| --- | --- | --- |
| github.com/agentskills/agentskills | `83021e2be457a9b6679148719c1f6074f0ad975e` | 2026-11-04 |
| github.com/openai/codex | `9f3cb259535c72de6960cc837ac705a780c62995` | 2026-11-25 |
| github.com/block/goose（现 aaif-goose/goose） | `8256b9ac9ffae7d19ef1db1f57e37f84c44915bd` | 2026-11-25 |
| github.com/badlogic/pi-mono（npm 包已改名 `@earendil-works/*`） | `bfafd8ed5168bca1f1ca88ef0529cfd326664d16`（pi-coding-agent 1.6.1） | 2026-11-25 |
| github.com/spring-ai-community/spring-ai-agent-utils | `3c9d5dc2349af94de9fa5e14d312aa7308eadd35`（0.15.0-SNAPSHOT，基于 Spring AI 2.1.0） | 2026-11-24 |

网页来源列在每节末尾。标「未核对」的是只看到二手资料或凭记忆，没在源码/官方文档里确认。

JobApply 现状（只为对照）：`backend/pom.xml` 是 Spring Boot 4.1.1 + Spring AI 2.0.1，同时用 `spring-ai-openai` 和 `spring-ai-anthropic`（**多模型服务商是现实需求**）；自研循环 `harness/runtime/AgentLoop.java`、`ModelCaller.java`，已有 `harness/context/SkillCatalog.java`；压缩缺口见 `plan/reference/gap-context-compaction.md`。

---

## 1. Agent Skills 规范 与 Claude Agent SDK / Claude Code 插件

### 1.1 Agent Skills（agentskills.io）

- 由 Anthropic 提出后开放为标准，仓库 `agentskills/agentskills`（Apache-2.0，有 GOVERNANCE.md / MAINTAINERS.md），附 Python 参考校验器 `skills-ref/`。
- 格式（`docs/specification.mdx`）：一个目录 + `SKILL.md`。frontmatter 必填 `name`（≤64，小写字母数字连字符，需与目录名一致）、`description`（≤1024，写清做什么、何时用）；可选 `license`、`compatibility`（≤500）、`metadata`（字符串 map）、`allowed-tools`（实验）。可选目录 `scripts/`、`references/`、`assets/`。
- **渐进披露**三级：元数据（约 100 token，启动时全部加载）→ 正文（建议 <5000 token、<500 行，激活时加载）→ 资源（按需读文件/跑脚本）。
- 客户端适配指南：`docs/client-implementation/adding-skills-support.mdx`。
- 支持者（`docs/snippets/clients.jsx`，46 个）：Claude Code、Claude、ChatGPT & Codex、Gemini CLI、GitHub Copilot、VS Code、Cursor、OpenCode、OpenHands、Goose、pi、Amp、Junie、Kiro、Roo Code、Factory、Letta、TRAE、Mistral Vibe、Snowflake Cortex Code、Databricks、Tabnine、Qodo、Laravel Boost、**Spring AI**、**Hermes Agent**、OpenClaw、fast-agent、nanobot 等。
- 实践中的「导入/市场」：没有统一中央仓库。常见做法是 git 仓库 / zip 拷贝到约定目录（`~/.claude/skills`、`.agents/skills`、`~/.codex/skills` 等）；聚合站有 skills.sh、skillsmp.com、tessl.io registry、claudepluginhub 等；Hermes 有 Skills Hub；Spring 生态有「SkillsJars」（把 skill 放进 jar 的 `META-INF/skills`）。分发多数靠「插件」包住 skill（见下）。

### 1.2 Claude Code 插件 / Claude Agent SDK

- **插件**：目录 + 可选 `.claude-plugin/plugin.json`；组件 `skills/`、`commands/`（旧式扁平 md）、`agents/`（子 agent）、`hooks/hooks.json`、`.mcp.json`。市场 = 一个含 `marketplace.json` 的 git 仓库，`/plugin marketplace add owner/repo` 后 `/plugin install x@market`。
- **SDK 加载插件**：`options.plugins: [{type:"local", path}]`，只支持本地路径；市场插件需先下载。TS 版 `reloadPlugins({addPlugins})` 可在运行中加插件。插件 skill 名带命名空间 `plugin:skill`。
- **Hook 事件**（官方表）：PreToolUse（可 allow/deny/ask/defer，可 `updatedInput` 改参数）、PostToolUse（`additionalContext` 追加或 `updatedToolOutput` 替换结果）、PostToolUseFailure、UserPromptSubmit（注入上下文）、SessionStart/End、SubagentStart/Stop、Stop、**PreCompact / PostCompact（PostCompact 仅 TS）**、PermissionRequest、Notification 等。**没有「改发给模型的整段消息」的 hook**——上下文管理由内置 auto-compact（`/compact`、`/context` 是内置命令）负责，插件只能观察/归档，不能替换压缩算法。
- **运行形态**：TS/Python SDK 内部启动一个 Claude Code 原生二进制子进程（随 SDK 版本固定）。每个会话一个子进程，多会话并发 = 多进程（内存开销未测）。
- **许可**：使用受 Anthropic Commercial Terms 约束，不是 OSI 开源；产品需用 API key 认证（不得用 Claude.ai 订阅登录），不得冒充 Claude Code 品牌。
- **模型**：只支持 Claude（Anthropic API / Bedrock / Vertex / Foundry）。JobApply 同时用 OpenAI，**这是硬伤**。
- 打包成本：Node 运行时 + SDK + 原生 Claude Code 二进制（体积未测），和现有 Java runtime 并存。

来源：<https://agentskills.io/>、<https://agentskills.io/specification>、<https://code.claude.com/docs/en/agent-sdk/hooks>、<https://code.claude.com/docs/en/agent-sdk/plugins>、<https://code.claude.com/docs/en/legal-and-compliance>、<https://code.claude.com/docs/en/agent-sdk/overview>

### 1.3 跨厂商插件标准：Agent Plugins（open-plugins.com → agent-plugins.org）

- 1.0.0：`plugin.json` + `skills/`（Agent Skills）+ `mcp.json`；hooks 不在可移植核心里，只作厂商扩展目录。核心维护者来自 Amazon、Cursor、Microsoft、OpenAI、Vercel。
- Goose 的 hooks 实现注释写明「modelled after the Open Plugins hooks specification」（`crates/goose/src/hooks/mod.rs`）；Codex 插件加载器把 `.claude-plugin/plugin.json` 当备选清单（`codex-rs/core-plugins/src/manifest.rs:685`）。**结论：「skills + MCP + plugin.json」正在成为事实标准，hook 事件名大家都抄 Claude Code（PreToolUse/PostToolUse/PreCompact…）。**

来源：<https://agent-plugins.org/>

---

## 2. 其他主流 Agent

### 2.1 OpenAI Codex CLI

- Rust（Apache-2.0），活跃度极高（克隆当天有提交）。`codex-rs/` 下 150+ crate。
- **可嵌入**：`codex app-server`（JSON-RPC，stdio/WebSocket，`thread/start`、`turn/start`、审批请求回调、事件流），官方 `sdk/typescript`、`sdk/python` 包装它。VS Code 插件和 Codex 桌面 App 都走 app-server。
- **Hooks**（`codex-rs/hooks/src/schema.rs`）：PreToolUse、PermissionRequest、PostToolUse、**PreCompact、PostCompact**、SessionStart、UserPromptSubmit、SubagentStart/Stop、Stop、Interrupt。以命令型 hook 为主。
- **插件**：`core-plugins` 有 marketplace（add/remove/upgrade/policy）、npm 源、插件包归档校验；清单 `.codex-plugin/plugin.json`，兼容 `.claude-plugin/plugin.json`。
- Skills：支持 Agent Skills（文档指向 developers.openai.com/codex/skills）。MCP 客户端 + 自身可作 MCP server。
- 压缩：内置 auto-compact（OpenAI 模型可用服务端 compact；未逐行核对）。不可插拔替换算法，只有 Pre/PostCompact hook。
- 多会话：app-server 一个进程管理多个 thread。
- 模型：以 OpenAI 为主，支持 OSS/Ollama/LM Studio 及其它 provider 配置；**接 Anthropic 原生不是一等公民**（未核对最新状态）。
- 打包：单个原生二进制（体积未测），无需 Node。

### 2.2 Gemini CLI（Google）

- TypeScript/Node，Apache-2.0。
- 扩展：`gemini-extension.json`（MCP servers、上下文文件、排除工具、设置）+ `commands/` + `skills/`（自动发现）+ `hooks/hooks.json`。有官方扩展目录 geminicli.com/extensions。
- Hook 事件（凭记忆，未逐条核对）：BeforeTool/AfterTool、BeforeModel/AfterModel、BeforeToolSelection、BeforeAgent/AfterAgent、PreCompress、SessionStart/End、Notification。BeforeModel 能改请求，比 Claude Code 多一层「模型调用前」钩子。
- 无头：`gemini -p` + `--output-format stream-json`，以及 ACP 模式（Zed 集成）。没有像 Codex app-server 那样正式的多会话服务 API（未核对）。
- 模型：Gemini 为主，换别家模型不是设计目标。

来源：<https://geminicli.com/docs/extensions/reference/>

### 2.3 Goose（Block → Linux 基金会 AAIF）

- Rust，Apache-2.0，属于 Agentic AI Foundation。仓库拆为 **oi-kit**（可复用 Rust 组件：状态机、工具、provider、上下文管理）+ goose（完整 agent，桌面 App/CLI，**通过 ACP 作为你应用的后端**）。
- 扩展 = MCP server（内置 developer/computer-controller 等）；**recipes**（YAML 工作流：指令、扩展、参数、子 recipe）；现在还有 `plugins/`（git 安装、自动更新）、`skills/`（Agent Skills，含 supporting files）、`hooks/`（Open Plugins 规范：PreToolUse、PostToolUse、PostToolUseFailure、SessionStart/End、UserPromptSubmit、Before/AfterShellExecution、Stop…，目前只支持 command 型）、`context_mgmt/`（自动压缩）。
- 多 provider（OpenAI、Anthropic、Gemini、Ollama 等）一等支持。
- 嵌入：ACP（stdio JSON-RPC），多会话由 goose 进程管理。

### 2.4 pi-mono / pi coding agent（Mario Zechner，现 earendil-works）

- TypeScript，MIT。口号「minimal, extensible agent harness」；**故意不内置子 agent、plan mode**，靠扩展/包补。OpenMAIC 就是基于 `pi-agent-core` 做的（`lib/chat/pi/director-compaction.ts` 直接调 `compact/shouldCompact/prepareCompaction`）。OpenClaw 也基于 pi。
- 分层包：`pi-ai`（多 provider 统一流式 API）、`pi-agent-core`（循环 + 会话 + 压缩）、`pi-coding-agent`（CLI/SDK/RPC）、`tui`、`mcp` 等。
- **扩展 API 是本次看到最细的**（`packages/coding-agent/docs/extensions.md`）：`pi.on(...)` 事件约 40 个，包括 `context` / `context_with_system`（**改发给模型的消息**）、`before_provider_request` / `after_provider_response`、`tool_call`（可拦截改参）、`tool_result`（改结果）、`session_before_compact`（**接管压缩，自定义摘要**）、`session_compact`、`turn_start/end`、`message_*`、`input`、`model_select`；还能注册工具、命令、UI 组件（RPC 下有 `rpc-extension-ui`）。
- Skills：实现 Agent Skills 规范（`docs/skills.md`）。Pi packages：npm/git 分发扩展+skills+模板+主题。
- 压缩（`docs/compaction.md`）：阈值触发、溢出恢复、按用户消息切点、结构化摘要模板（Goal/Constraints/Progress/Decisions/Next/Critical Context）、分支摘要、按模型覆写设置。
- 嵌入：TS SDK `createAgentSession()`（一个 session 一份会话状态，可多实例并存）+ RPC 模式（stdio JSON）。
- 注意：仓库对新贡献者 PR/issue 默认自动关闭；包名从 `@mariozechner/*` 迁到 `@earendil-works/*`，API 变动较快。

### 2.5 Cline / Kilo Code

- Cline：TypeScript，Apache-2.0；新出 `@cline/sdk`，可编程注册工具和生命周期 hook；CLI 有 headless 模式（来源为 fork 的 README，未核对官方）。
- Kilo Code CLI 1.0：`kilo serve` 起本地 HTTP+SSE 服务给编辑器客户端用，支持 Agent Skills；据报道其 CLI 基于 opencode 重构（未核对）。
- 两者核心场景是 IDE 编码，嵌入到非编码产品的价值低于 opencode/pi。

来源：<https://kilo.ai/docs/cli>、<https://kilo.ai/docs/contributing/architecture/cli-runtime>

### 2.6 OpenHands Software Agent SDK

- Python（MIT，未核对 LICENSE），四个包：`openhands.sdk`（Agent/Conversation/Event、LLM、skills、**condenser**、security）、`tools`、`workspace`（Docker/远程）、`agent_server`（FastAPI，REST + WebSocket，多用户隔离会话，还暴露 OpenAI 兼容端点）。MLSys 2026 论文。
- Skills：触发词激活的提示（旧称 microagents），也有公共 skill 市场；Agent Skills 客户端名单里有 OpenHands。
- Condenser 可插拔（LLM 摘要等多种实现），是「压缩作为插件」的典型。
- 偏重沙箱化编码，Docker 工作区是一等公民；在桌面 App 里驱动用户 Chrome 不是它的路数。

来源：<https://docs.openhands.dev/sdk/arch/overview>、<https://arxiv.org/html/2511.03690v2>

### 2.7 opencode（仅对照，详见另一份调研）

TypeScript/Bun，MIT；`opencode serve` HTTP+SSE + 生成的 SDK；JS 插件 hook（工具前后、事件、chat 参数等）；支持 Agent Skills；多 provider；自动压缩。克隆在 scratchpad `repos/opencode`（`ea8aa5b05ef49af86d6c78dad0a19e2877e034e0`，由另一代理拉取）。

---

## 3. Hermes Agent（本机）

- Python，MIT，0.12.0。
- **插件**（`hermes_cli/plugins.py`）：目录 + `__init__.py` 的 `register(ctx)`。`ctx.register_tool / register_command / register_cli_command / register_context_engine / register_image_gen_provider / register_platform / register_hook / register_skill`。
- Hook（`VALID_HOOKS`，L75 起）：pre/post_tool_call、transform_tool_result、transform_terminal_output、pre/post_llm_call、pre/post_api_request、on_session_start/end/finalize/reset、subagent_stop、pre_gateway_dispatch、pre_approval_request/post_approval_response。另有 shell hooks（`agent/shell_hooks.py`）。
- **压缩是可替换引擎**（`agent/context_engine.py`）：抽象类 `ContextEngine`，接口 `update_from_response(usage)`、`should_compress()`、`compress(messages)`、`on_session_start/end`，可额外暴露工具（如 `lcm_grep`）；配置 `context.engine` 选一个，默认 `compressor`（`agent/context_compressor.py`：保护头 3 条 / 尾 6 条或按 token 预算，阈值 75%，结构化摘要含已解决/待解决问题，多次压缩时迭代更新上一份摘要）。插件可通过 `register_context_engine` 或放进 `plugins/context_engine/<name>/` 替换。**这是「压缩做成插件」最贴切的现成样板**；token 状态字段（`last_prompt_tokens`、`threshold_tokens`、`context_length`）直接可做「上下文用量面板」。
- Skills：兼容 agentskills.io（README），自带 `skills/`、`optional-skills/`，Skills Hub；frontmatter 额外放 `metadata.hermes.*`。
- Memory：`agent/memory_manager.py` + `memory_provider.py`（可插拔 provider，如 Honcho），后台回顾写 memory/skill（JobApply 已有既定做法，见 `plan/reference/research-hermes.md`）。
- 嵌入：有 ACP adapter、gateway（多平台消息）、`mcp_serve.py`；没有面向自定义 UI 的正式多会话 HTTP API。

---

## 4. LangChain Deep Agents（本机）与 LangGraph.js

- Python（MIT），`create_deep_agent()` 组装一串 **middleware**（`graph.py` L737–988）：TodoList、Filesystem（含大结果转存文件）、SubAgent / AsyncSubAgent、Skills、Memory、Summarization、PatchToolCalls、HumanInTheLoop、AnthropicPromptCaching、UnsupportedContent、ToolExclusion。
- Middleware 钩子（langchain 1.x `AgentMiddleware`）：`before_agent`、`before_model`、`after_model`、`wrap_model_call`（可改 request：消息、工具、系统提示，或重试）、`wrap_tool_call`、`modify_request`，外加自带 state schema 和工具。**这是最干净的「功能 = 插件」模型**：压缩、技能、文件系统、子 agent 都只是一个 middleware。
- Summarization（`middleware/summarization.py`）：`trigger=("fraction",0.85)`、`keep=("fraction",0.10)`；被摘要的原文写到 backend 文件；捕获 `ContextOverflowError` 时强制压缩再重试；另有 `SummarizationToolMiddleware` 暴露 `compact_conversation` 工具让模型/用户手动触发；还有只截工具参数的轻量截断。
- Skills middleware：Agent Skills 格式，渐进披露。
- JS 版：`deepagentsjs`（未克隆）；`langgraphjs/libs` 有 `langgraph-api`、`sdk`、`sdk-react`，配 `deep-agents-ui` 可做现成前端（`useStream`）。
- 嵌入：LangGraph Server / `langgraph-api`（线程、运行、流式、checkpoint），多会话天然支持。许可 MIT；部署平台部分商业。

---

## 5. 留在 Java

### 5.1 Spring AI 2.0 / 2.1（官方）

- **Advisor 链就是 middleware**：`ChatClient` 每次请求过有序 advisor 链，advisor 可重入下游链实现循环。2.0 GA 起 `ToolCallingAdvisor` 自动注册，接管工具调用循环（各模型里自带的循环已移除）；`ToolSearchToolCallingAdvisor`（工具渐进披露）；`StructuredOutputValidationAdvisor`。RC1 起 memory advisor 默认包在工具循环外层（order +200 vs 工具 +300）。
- **Session 模块（Spring AI 2.1，`org.springframework.ai:spring-ai-session*`）**：事件溯源、只追加日志；**按轮次边界的可插拔压缩**（触发器如 `TurnCountTrigger`，策略如 `WindowCompactionStrategy`，`ChatModelSummarizer` 做 LLM 摘要）；压缩不改原始日志，只追加边界——**与 JobApply `memory.md`「transcript.jsonl 原文不压缩」的原则完全一致**；`SessionAdvisors` 接 `ChatClient`；仓储 InMemory / JDBC / 自定义 SPI。文档是 2.1 参考；JobApply 当前 2.0.1，需升到 2.1。
- MCP：MCP Java SDK 2.0.0（2025-11-25 规范），Streamable HTTP 默认，`@McpTool` 等注解并入 Spring AI。
- 来源：<https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/>、<https://docs.spring.io/spring-ai/reference/api/session.html>、<https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/upgrade-notes.html>

### 5.2 spring-ai-agent-utils（Spring AI 社区，Apache-2.0）

- 「受 Claude Code 启发」的工具/advisor 集：`SkillsTool`（**Agent Skills 的 Spring 原生实现**，名单里的「Spring AI」即它；支持目录和 classpath / SkillsJars）、FileSystem/Shell/Grep/Glob、SmartWebFetch、BraveWebSearch、AskUserQuestion、TodoWrite、`TaskTool`（子 agent，含 Claude 风格子 agent 定义、A2A 后端）、AutoMemoryTools(+Advisor)、AutoDream（后台整理记忆）、`InterruptAdvisor`（停止按钮/预算）、`ToolCallListener`（审计/进度流）。
- **没有压缩 advisor**（源码 grep 无 compact 实现），压缩交给 Spring AI Session。
- 版本：main 要求 Spring AI 2.1 / Boot 4.2；早期 0.4–0.10 对应 2.0.0-Mx（具体哪个版本对应 2.0.1 需查 Maven Central，未核对）。孵化项目，API 仍在变。
- 来源：<https://github.com/spring-ai-community/spring-ai-agent-utils>、<https://spring.io/blog/2026/01/13/spring-ai-generic-agent-skills/>

### 5.3 Embabel

- Rod Johnson 主导，Kotlin/Java，Apache-2.0，1.0 GA 于 2026-07。GOAP 式规划 + 强类型 action/goal；有 MCP、A2A、skills 模块。1.0 基于 Spring AI 1.1.x / Boot 3.5（与 JobApply 的 Boot 4 / Spring AI 2 不同代）。
- 设计哲学是「代码里定义动作和目标、规划器选路径」，与 JobApply「流程交给模型随机应变」的原则相反。
- 来源：<https://www.danvega.dev/blog/2026/07/20/embabel-1-0-ga>、<https://github.com/embabel/embabel-agent>

### 5.4 LangChain4j

- `langchain4j-skills`（1.12.1 起，FileSystem/ClassPath SkillLoader，工具模式；shell 执行在 experimental 模块），`langchain4j-agentic`（多 agent 编排，仍实验）。Quarkus 有集成。换框架 = 重写 Spring AI 那层，收益不明显。
- 来源：<https://docs.langchain4j.dev/tutorials/skills>

---

## 6. 对比表

| 基底 | 语言/运行时 | 能否被自有 UI 无头驱动 | 插件 hook 面 | Skills | 压缩 | 多会话并发 | 多模型 | 许可 | 成熟度/活跃 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Claude Agent SDK | TS/Py + 原生 CC 二进制 | 是（SDK，每会话一子进程） | Pre/PostToolUse(改参/改结果)、UserPromptSubmit、Pre/PostCompact、Session/Subagent…；**不能改消息/替换压缩** | 原生，插件+市场 | 内置 auto-compact，不可替换 | 多子进程 | **仅 Claude** | 商业条款，非开源 | 高 |
| Codex CLI | Rust 二进制 | 是（app-server JSON-RPC + TS/Py SDK） | 命令型 hooks（含 Pre/PostCompact） | 原生，插件+市场（兼容 .claude-plugin） | 内置 | 单进程多 thread | OpenAI 为主 | Apache-2.0 | 极高 |
| Gemini CLI | TS/Node | 部分（`-p` stream-json、ACP） | Before/AfterModel、Before/AfterTool、PreCompress…（未逐条核对） | 原生，扩展目录 | 内置 | 弱 | Gemini 为主 | Apache-2.0 | 高 |
| Goose / oi-kit | Rust | 是（ACP；或用 oi-kit 自建） | Open Plugins hooks（command 型） | 原生 + recipes | context_mgmt 内置 | 是 | **一等多 provider** | Apache-2.0（LF） | 高 |
| pi（pi-agent-core） | TS/Node | 是（SDK `createAgentSession` + RPC） | **最全**：context 改消息、provider 请求前后、tool_call/result、`session_before_compact` 接管压缩、UI 扩展 | 原生 + pi packages | 内置，可被扩展接管 | 多 session 实例 | **一等多 provider** | MIT | 中高（API 变动快，外部 PR 默认关） |
| OpenHands SDK | Python | 是（agent_server REST/WS） | tools、condenser、security analyzer | skills（触发式）+ 市场 | **可插拔 condenser** | 是 | 多（LiteLLM） | MIT（未核对） | 高 |
| Hermes | Python | 弱（ACP/gateway，无正式多会话 HTTP API） | pre/post_llm_call、pre/post_tool_call、transform_tool_result、session…；注册工具/命令/引擎 | 原生 agentskills + Hub | **可替换 ContextEngine** | 弱 | 多 | MIT | 中高 |
| Deep Agents / LangGraph | Python / JS | 是（LangGraph Server + SDK + deep-agents-ui） | **middleware**：before/after_model、wrap_model_call、wrap_tool_call | Skills middleware | Summarization middleware（85%/10%、溢出重试、转存文件、手动工具） | 是（thread） | 多 | MIT | 高 |
| Spring AI 2.1 + agent-utils | Java | 就是现在的后端 | **Advisor 链**（around 整个请求，可重入循环） | SkillsTool（Agent Skills） | Session 模块：轮次边界可插拔压缩、原始日志不改 | 是（按 sessionId） | 多 | Apache-2.0 | Spring AI 高；agent-utils 孵化 |
| Embabel | Kotlin/Java | 是 | GOAP action | 有 | 未核对 | 是 | 多 | Apache-2.0 | 1.0，但 Boot 3.5 代 |
| LangChain4j | Java | 是 | AiServices/agentic | skills 模块 | ChatMemory 窗口为主 | 是 | 多 | Apache-2.0 | 高；skills/agentic 仍 beta |

---

## 7. 对 JobApply 的建议（2–3 个方向）

背景约束：用户自己的 Chrome + Playwright MCP、并行多个申请 worker、OpenAI 与 Anthropic 都要用、macOS App 已打包 Java runtime + node + Playwright MCP、内存曾经出过问题（C28）。

### 方向 A（推荐首选）：留在 Java，把「插件」定义为 Spring AI Advisor + Agent Skills 目录

- 做法：升 Spring AI 2.1；把自研 `AgentLoop/ModelCaller` 逐步收敛到 `ChatClient` + advisor 链（或保留自研循环，但内部定义同形的 `AgentPlugin` 接口：`beforeModel / wrapModelCall / wrapToolCall / afterTurn` + 可贡献工具，这正是 Deep Agents middleware 与 Hermes hook 的交集）。压缩用 Spring AI Session（轮次边界、原始日志不改，正合 `memory.md` 原则）或照 Deep Agents/Hermes 写一个 compaction advisor（85% 触发、溢出重试、原文转存）。上下文用量面板读每次响应的 usage + 窗口大小（Hermes `ContextEngine` 的字段即可）。Skills 用 spring-ai-agent-utils `SkillsTool` 或扩展现有 `SkillCatalog` 按 agentskills.io 格式解析，导入 = 拷目录/zip/git（顺带兼容 Claude 插件包里的 `skills/`）。
- 优点：零运行时迁移、零新增打包体积、保留多模型、并行 worker 继续是线程；插件模型对齐行业事实标准（skills + MCP + 类 PreToolUse hook）。
- 代价：agent-utils 是孵化项目、需 Spring AI 2.1；插件只能是 Java（外部第三方插件只能导入 skills/MCP，不能导入 JS/Python 代码插件）。

### 方向 B：换成 TypeScript 的 pi-agent-core（或 Deep Agents JS），Java 退为业务服务

- 做法：Node 侧用 `@earendil-works/pi-agent-core` / `pi-coding-agent` SDK 跑循环（OpenMAIC 已这样用），每个申请 worker 一个 `AgentSession`；压缩、用量面板、skills、子 agent 都写成 pi 扩展；Java 保留资料/记忆/持久化 API，或整体迁走。
- 优点：hook 面最全（能改发给模型的消息、接管压缩）、天然多 provider、生态（pi packages、OpenClaw）在长、app 里已有 node。
- 代价：重写运行时与工具层（Browser/Workbench 工具、守卫要移植）；两种语言两套进程的协作成本；pi API 变化快且不收外部 PR；Java↔Node 之间要设计会话/事件协议。

### 方向 C（不推荐作为基底，可作参照或可选后端）：嵌入成品 agent（Claude Agent SDK / Codex app-server / Goose ACP）

- 做法：Java/前端只做 UI 与业务，agent 循环交给外部二进制，经 SDK/JSON-RPC/ACP 驱动；复用其插件市场。
- 优点：立刻获得成熟的 skills、插件市场、压缩、子 agent、hooks。
- 代价：Claude Agent SDK 只支持 Claude 且受商业条款约束，每会话一子进程，与并行 worker + 内存约束冲突；Codex 偏 OpenAI、Goose 偏编码/桌面，二者都要额外打包一个大原生二进制；「最终提交不点」「用用户 Chrome」等核心逻辑只能靠 hook/MCP 间接实现，压缩算法不可换；产品被上游节奏绑架。

**一句话**：插件/skill 的「格式标准」（Agent Skills + plugin.json + MCP + PreToolUse/PostToolUse/PreCompact 式 hook）可以在 Java 里直接采用，而「运行时」换语言的收益主要是 pi/Deep Agents 那种更细的 hook 面；按现有约束，A 的性价比最高，B 是若未来要大量吸收 JS 生态插件时的备选。
