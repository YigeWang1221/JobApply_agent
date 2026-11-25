# pi（pi-mono）调研：比 opencode 更自主吗？

日期：2026-11-25。**参考材料，不是施工步骤，方向未定。**

读的代码：`github.com/badlogic/pi-mono`（已改到 `earendil-works/pi`），`--depth 1` 克隆，提交 **`745338811405a31831005300aedac173a512e0e4`**（2026-11-25 15:57 +0100），所有包版本 **1.6.1**。克隆用完已删除。
OpenMAIC 读的是本机 `../OpenMAIC@dff201b`（只读）。
下文路径相对 `packages/`，除非另行注明。

背景：[research-agent-platform.md](../research-agent-platform.md)、[B-our-agent.md](B-our-agent.md)、[C-opencode.md](C-opencode.md)、[D-other-agents.md](D-other-agents.md)、[plan-A-java.md](plan-A-java.md)、[plan-B-opencode.md](plan-B-opencode.md)。

> **更正 D-other-agents.md 2.4 节**：D 写的「`pi-agent-core`（循环 + 会话 + 压缩）」「OpenMAIC 直接调 `compact/shouldCompact/prepareCompaction`」只对 1.0 之前成立。**pi-agent-core 1.0.0（2026-10-01）把会话、会话存储、压缩、skill、提示词模板全部移出**，现在只剩 `Agent`、循环、代理流和类型（`agent/CHANGELOG.md:63-67`）。OpenMAIC 钉在 0.78.0，用的正是被删掉的那套（见第 5 节）。

---

## 0. 直接回答

**用 pi 的「库」那一层（`pi-ai` + `pi-agent-core`），自主度明显高于 opencode；用 pi 的 coding-agent SDK，自主度和 opencode 差不多，只是换成进程内嵌入。**

- opencode（plan-B）是**框架 + 服务器**：它拥有循环、存储（SQLite）、会话、事件格式、提示词装配；我们只能在它留的钩子里改，并且要关掉一堆它自带的东西（内置 agent、内置工具、项目配置发现、记忆）。
- `pi-agent-core` 是**库**：一个 623 行的 `Agent` 类 + 952 行的循环，状态全在内存里，**没有存储、没有 UI、没有配置发现、没有内置工具**。上下文怎么拼、存到哪、事件怎么推、UI 长什么样，全是我们的。我们需要的钩子（调模型前改上下文、工具前拦截、工具后改结果、插话、停止、每轮结束决定继续/结束）它都以构造参数直接给出。
- 代价：压缩、skill 加载、MCP 客户端这些「opencode 自带的」在纯库模式下要自己接（pi 里都有现成实现可以 import 或照抄，体量都是百行级），扩展 API（41 个事件）只在 coding-agent 那一层。

对 JobApply 的要求逐条看：自己的 React UI ✔（库不碰 UI）；自己的记忆 / storage ✔（库不存任何东西）；插件（压缩、上下文面板）✔ 但「插件系统」要么自己写一层薄的，要么用 coding-agent 的扩展；外部 Agent Skills ✔（pi 实现了规范，加载函数可复用）；用户自己的 Chrome ✔（与 pi 无关，浏览器层留在 Java 或搬到 Node 都行）；并行 Worker ✔（实测一个进程 6 个 `Agent` 并发，无共享状态）；不点最终提交 / 密钥不外泄 ✔（`beforeToolCall` 拦截，但守卫仍应留在浏览器工具内部）。

**风险**：API 变得快（1.0 以后 8 周 14 个版本，`pi-ai` 有 5 次破坏性变更）、对新贡献者自动关 issue/PR、需要 Node ≥ 22.19（我们打包机上现在是 22.13.1）。

---

## 1. 包与分层

npm 包已从 `@mariozechner/*`（最后 0.73.1，已标 deprecated："please use @earendil-works/... instead"）改名为 **`@earendil-works/*`**，全部 **MIT**（根 `LICENSE`：Copyright (c) 2025 Mario Zechner），`engines.node >= 22.19.0`。

| 包 | 版本 | 解包大小 | 是什么 | 层 |
| --- | --- | --- | --- | --- |
| `@earendil-works/pi-ai` | 1.6.1 | 4.6 MB | 多 provider 统一流式 API、OAuth、模型目录 | **库** |
| `@earendil-works/pi-agent-core` | 1.6.1 | 0.27 MB | `Agent` + 循环 + 工具执行 + 事件；只依赖 pi-ai、pi-schema | **库** |
| `@earendil-works/pi-mcp` | 1.6.1 | — | 独立 MCP 客户端（stdio、Streamable HTTP），不依赖官方 SDK 和其它 pi 包（`mcp/README.md:1-30`） | **库** |
| `@earendil-works/pi-durable` | 1.6.1 | 4.7 MB | 持久化会话/任务 harness（1.0 从 agent-core 挪过来的东西的新家），README 第一行：**"Experimental. The API changes without notice between releases."**（`durable/README.md:3`） | 库，实验 |
| `@earendil-works/pi-coding-agent` | 1.6.1 | 24.9 MB | CLI/TUI、SDK（`createAgentSession`）、RPC 模式、会话 JSONL、扩展、skill、压缩、MCP 扩展、codemode | **应用 / 框架** |
| `@earendil-works/pi-tui`、`chord`、`codemode`、`telemetry`、`schema`、`env` | 1.6.1 | — | 终端 UI、对象运行时、JS 沙箱、遥测契约、JSON Schema 构造器 | 支撑 |

依赖关系（各包 `package.json`）：`pi-agent-core → pi-ai, pi-schema`；`pi-coding-agent → pi-agent-core, pi-ai, pi-codemode, pi-mcp, pi-tui, jiti, undici, ...`。`AgentSession` 就是把 `pi-agent-core` 的 `Agent` 包起来（`coding-agent/src/core/sdk.ts:403` `new Agent({...})`，`:428` 注入 `transformContext`）。

### 1.1 只用 pi-agent-core + pi-ai：可以

`Agent` 构造参数（`agent/src/agent.ts:115-147`）：

- `streamFn`（必填，可用 pi-ai 的 `streamSimple`，也可像 OpenMAIC 一样自己写）、`getApiKey(provider)`（密钥由我们按调用给，pi 不落盘）；
- `transformContext(messages, signal)`：每次调模型前改消息（`agent/src/agent-loop.ts:399-400`），之后 `convertToLlm` 把自定义消息类型转成 LLM 消息（`:404`）；
- `beforeToolCall` → `{block, reason, terminate}`（`agent/src/types.ts:66-75`，循环调用处 `agent-loop.ts:737`）；`afterToolCall` → 替换 `content/details/isError/terminate`（`types.ts:92-104`，`agent-loop.ts:873`）；
- `prepareRequest` / `prepareNextTurn` / `finishTurn`（每轮决定继续或结束，`types.ts:147-191`、`agent-loop.ts:228,261,295`）；
- `onPayload` / `onResponse` / `onProviderStreamEvent`（看/改出站请求，做用量统计）；
- `toolExecution: "sequential" | "parallel"`，工具也能单独设 `executionMode`（`types.ts:47, 478-511`）。

方法：`prompt()`（忙时直接拒绝并提示用 steer/followUp，`agent.ts:380-386`）、`steer()` / `followUp()`（`:308-316`，循环每轮取 `getSteeringMessages`，`agent-loop.ts:185,214,304`）、`abort()`（`:350`）、`waitForIdle()`、`subscribe(listener)`（`:275`）、`state`（内存里的 messages / tools / model）。事件：`agent_start/end`、`turn_start/end`、`message_start/update/end`（token 级 delta）、`tool_execution_start/update/end`（带 `durationMs`）（`types.ts:528-551`）。

**没有**：存储、压缩、skill、MCP、插件系统、系统提示装配。这些在纯库模式下由我们写，或从别的包 import（见第 2 节）。

**实测**（scratchpad 里 `npm install --ignore-scripts` 装 pi-agent-core + pi-ai 1.6.1，faux provider，脚本已删）：
- Node 22.13.1：import 后 RSS 从 40 MB 到 67 MB；**6 个 `Agent` 在同一进程并发**各跑一轮工具调用，用时 208 ms，RSS 69.6 MB；其中一个用 `beforeToolCall` 返回 `{block:true}`，模型拿到的是 "blocked by guard" 的工具结果，其余正常。
- `node_modules` 106 MB（主要是 AWS / Google / Anthropic / OpenAI SDK）。
- 注意：22.13.1 低于 `engines` 要求，能跑只是凑巧；正式用要升到 ≥ 22.19。

### 1.2 无头嵌入 coding-agent SDK：可以，但更像 opencode

`createAgentSession()`（`coding-agent/docs/sdk.md`；`src/core/sdk.ts` 481 行；`agent-session.ts` 4579 行）。每个边界都能替换：`modelRuntime`（认证/模型文件路径可指到我们目录，可 `setRuntimeApiKey`）、`settingsManager`（`SettingsManager.inMemory({...})`）、`sessionManager`（`SessionManager.inMemory()`）、`resourceLoader`（完全自定义：扩展、skill、提示词、AGENTS 文件、系统提示都由我们给），见 `coding-agent/examples/sdk/12-full-control.ts`。不给 UI 时扩展拿到的是 no-op UI 上下文（`src/core/extensions/runner.ts:332, 633-634`）。

- 同进程多会话：没发现 `process.chdir` 之类全局状态（`grep src/core` 无结果）；文档只在 codemode 处提到「一个进程跑多个会话」（`docs/environment-variables.md:88`，`PI_CODEMODE_MAX_WORKERS` 默认 2）。**未实测**：coding-agent 1.6.1 依赖 `undici@8.12.4`（`engines >= 22.19`），在本机 Node 22.13 / 23.7 上 import 直接失败，内存没量到。
- 另有 **RPC 模式**：`pi --mode rpc --no-session`，stdin/stdout JSONL 命令 + 事件，给「其它语言 / 自定义 UI」用（`coding-agent/docs/rpc.md:1-25`）。Java 可以直接驱动它（每个 Worker 一个子进程），但扩展仍是 TS。

---

## 2. coding-agent 层的能力（扩展、skill、压缩、会话、provider）

### 2.1 扩展 API

`ExtensionAPI.on(...)` 共 **41 个事件**（`coding-agent/src/core/extensions/types.ts:1646-1718`）：

- 资源 / 会话：`project_trust`、`resources_discover`、`session_start`、`session_info_changed`、`session_before_switch`、`session_before_fork`、**`session_before_compact`**（可接管压缩，带 `reason: manual|threshold|overflow`、`willRetry`，`:811-821`）、`session_compact`、`session_compact_failed`、`session_shutdown`、`session_before_tree`、`session_tree`、`mcp_servers_change`；
- 调模型前：**`context`**（改消息，返回 `{messages}`，`:1472-1474`；说明在 `:904-910`）、`context_with_system`、`cache_warming_decision`、**`before_provider_request`**、`before_provider_headers`、`after_provider_response`、`provider_stream_event`；
- 运行：`before_agent_start`、`agent_start/end`、`agent_before_settle`、`agent_settled`、`turn_start/end`、`message_start/update/end`（`message_end` 可替换整条消息，`:1520-1523`）；
- 工具：**`tool_call`**（`{block, reason, terminate}`，改参数就地改 `event.input`，`:1483-1492`）、**`tool_result`**（改 content/details/isError，`:1512-1518`）、`tool_execution_start/update/end`、`user_bash`；
- 其它：`input`、`model_select`、`thinking_level_select`、`ui_prompt_start/end`。

注册：`registerTool`、`registerCommand`、`registerToolRenderer`、`registerProvider/unregisterProvider`、`sendMessage`、`appendEntry`（`types.ts:1725-1931`）。UI 钩子（`ctx.ui.select/confirm/input/notify/custom`、header/footer 组件）是**终端 UI** 的（参数是 `TUI`、`Theme`，`types.ts:150-240`）；RPC 下有 `rpc-extension-ui` 把对话框转成协议消息。**对我们的 React 界面，这些 UI 钩子基本用不上**，上下文面板要我们自己从事件 + `context` 钩子取数据。

### 2.2 Skills

实现 Agent Skills 规范（`coding-agent/docs/skills.md:1-110`）：目录 + `SKILL.md`，启动时只把 name / description / 路径写进系统提示，正文由模型用 `read` 工具按需读；支持 `references/`、`scripts/`、`assets/`、`disable-model-invocation`、`allowed-tools`（标注 experimental）；扫描 `~/.agents/skills`、`.agents/skills`。代码：`src/core/skills.ts`（505 行），`loadSkills()`（`:414`）、`formatSkillsForPrompt(skills, "read"|"bash"|"indirect")`（`:358`），都从包根导出（`src/index.ts:330-334`）。
→ 纯库模式下：要么 import 这两个函数（会拉进整个 coding-agent 依赖），要么照抄（约百行）+ 给模型一个只读的 `read_skill_file` 工具。

### 2.3 压缩

`src/core/compaction/compaction.ts`（1104 行），说明 `docs/compaction.md`：
- 触发：`contextTokens > contextWindow - reserveTokens`，默认 `reserveTokens=16384`、`keepRecentTokens=20000`（`src/core/settings-defaults.ts:10-12`）；多轮运行中在工具结果追加后、下一次模型请求前检查；provider 报上下文超长或过早 `stopReason:"length"` 时压缩后重试一次。
- 做法：从后往前累计到 `keepRecentTokens` 找切点 → 结构化摘要（带上一次摘要迭代）→ **追加** `CompactionEntry{summary, firstKeptEntryId}`，原始条目不删，重建上下文时用「摘要 + 切点之后的原文」。这和我们 `memory.md`「原文不压缩、只追加分界」的原则一致。
- 导出函数：`shouldCompact`（`:267`）、`estimateTokens`（`:298`）、`findCutPoint`（`:446`）、`generateSummary`（`:644`）、`prepareCompaction`（`:859`）、`compact`（`:952`），包根导出（`src/index.ts:28-51`）。
- AgentSession 里接管点：`agent-session.ts:2929-2968`（有 `session_before_compact` 处理器就交给扩展）。

### 2.4 会话存储

JSONL，每行一个带 `type` 的对象，`id/parentId` 组成树（分支不新建文件），版本 v3，默认位置 `~/.pi/agent/sessions/--<path>--/<ts>_<id>.jsonl`（`docs/session-format.md:1-30`）。`SessionManager` 是**具体类**，不是可插拔接口：`create(cwd, sessionDir?)`、`open(path)`、`inMemory(cwd, opts, entries?)`（`src/core/session-manager.ts:999, 1777, 1788, 1826`），写盘用 `appendFileSync`（`:1199`）。
→ 能「拥有」存储的方式：把 `sessionDir` 指到 `storage/` 下；或用 `inMemory(entries)` 恢复 + 订阅事件自己写 `transcript.jsonl`。纯库模式下这个问题不存在——`Agent` 只有内存状态，存储完全是我们的。

### 2.5 Provider（`ai/src/providers/`、`ai/src/api/`、`ai/src/auth/oauth/`）

| 要的 | pi-ai 有没有 | 出处 |
| --- | --- | --- |
| DeepSeek 思考 | 有，`deepseek` provider 走 openai-completions，读写 `reasoning_content` 并回传 | `providers/deepseek.ts:1-15`；`api/openai-completions.ts:286, 598-618, 1360, 1405` |
| Gemini 思考签名 | 有，`thoughtSignature` 收集与回传（同 provider+模型时才回传） | `api/google-shared.ts:171-188, 289` |
| ChatGPT 订阅 OAuth | 有，`openai-codex` provider（`https://chatgpt.com/backend-api`）+ PKCE / device code 登录 | `api/openai-codex-responses.ts:45`；`auth/oauth/openai-codex.ts:1-30`、`openai-chatgpt.ts` |
| Anthropic | 有，API key；thinking 签名 `signature_delta` 回传；还有 message threads（只发增量） | `api/anthropic-messages.ts:879, 939-942`；`ai/CHANGELOG.md` 1.5.1 |
| 其它 | OpenAI、Azure、Bedrock、Google / Vertex、OpenRouter、Mistral、xAI、Groq、Kimi、Qwen、MiniMax、GitHub Copilot 等约 40 个 | `providers/*.ts` |
| 凭据存储 | 默认 `InMemoryCredentialStore`，注释写「Apps inject persistent stores」 | `auth/credential-store.ts:4-9` |

（`auth/oauth/anthropic.ts` 存在，但 Claude.ai 订阅接第三方产品受 Anthropic 条款限制，见 D-other-agents.md 第 50 行，不建议用。）

这一块正好能替掉我们自写的 `ChatGptResponsesChatModel`（511 行）、`GeminiThoughtSignatures`、`thinkingBody` 按模型名分支（B-our-agent.md 1.8 节）。

---

## 3. 自主度对比：pi vs opencode

| | pi 库（agent-core + ai） | pi coding-agent SDK / RPC | opencode V2（plan-B） |
| --- | --- | --- | --- |
| 形态 | 库，进程内 new 对象 | 框架，进程内或子进程 | 框架 + HTTP 服务器（单文件 240 MB） |
| 谁拥有循环 | pi 写循环，每个决策点都是我们给的回调 | pi | opencode |
| 谁拥有存储 | **我们**（pi 不存） | pi 的 JSONL（可指目录 / 用内存） | opencode 的 SQLite |
| 谁拥有 UI / 事件格式 | **我们** | 我们（TUI 钩子用不上） | opencode 事件，我们翻译 |
| 系统提示 / 内置工具 | 什么都没有，全是我们的 | 有写代码用的默认（可整个替换 `resourceLoader`） | 有，要逐个 disable / deny |
| 插件 | 无插件系统，用回调自己组 | 41 个事件的扩展 API | 插件 + RPC，钩子也全 |
| 压缩 / skill / MCP | 自己接（可借 pi 的函数和 pi-mcp） | 自带 | 自带 |
| 并行 Worker | 每个 Worker 一个 `Agent`，实测可行 | 每个一个 `AgentSession`（未实测） | 每会话串行、会话间并发；Playwright MCP 连接按目录共享要另做映射 |
| 内存 | 实测 import 后约 +27 MB，6 个并发几乎不增 | 未量到（需 Node ≥ 22.19） | 有服务端内存泄漏 issue |
| 运行时 | Node ≥ 22.19（可用 App 已带的 node，需升级） | 同左 | Bun 单文件或 Node 26 SEA |

**API 稳定性**（npm `time`，`*/CHANGELOG.md`）：
- pi-agent-core 共 66 个版本（2026-05 至今），**1.0.0 于 2026-10-01**，之后 8 周 14 个版本（1.0.0 → 1.6.1）。
- 1.0 以后的破坏性变更：pi-agent-core 1 次（1.5.0，工具参数改用 pi-schema / 任意 JSON Schema，`agent/CHANGELOG.md:25-31`）；pi-ai 5 次（1.1、1.3、1.4、1.5、1.5.1：去掉 TypeBox 导出、遥测选项改名、Azure provider 改名、空 tools 字段等，`ai/CHANGELOG.md:96-290`）；coding-agent 几乎每个小版本都有（`coding-agent/CHANGELOG.md:17, 80, 182, 296, 516, 651`）。**版本号不按 semver：小版本也会破坏。** 要锁精确版本、升级时读 changelog。
- 1.0 前更剧烈：0.80–0.87 每隔一两周一次破坏性变更；1.0.0 一次删掉了整个 harness（会话、存储、压缩、skill）。
- **贡献政策**：README 顶部与 `CONTRIBUTING.md`「Contribution Gate」：新贡献者的 issue 和 PR 默认自动关闭，维护者每天看，回复 `lgtmi` / `lgtm` 才放行；周五到周日的 issue 不保证看；滥发会被永久封号。意味着：遇到 bug 很难推上游修，**要做好自己 patch / 绕过的准备**——库那层代码小（agent-core 共 2549 行），这件事可承受；coding-agent（7.9 万行）就难了。
- 社区面：OpenClaw、OpenMAIC 基于 pi；Agent Skills 46 个客户端之一。

**结论**：「更自主」只在用库那层时成立。SDK/RPC 那层的取舍和 opencode 类似（得到现成压缩和扩展，交出存储与循环细节），并且 coding-agent 的变动比 agent-core 快得多。

---

## 4. OpenMAIC 怎么用 pi（`../OpenMAIC@dff201b`，只读）

- 依赖钉死 `@earendil-works/pi-agent-core` 和 `pi-ai` **0.78.0**（`package.json:56-57`），在 `next.config.ts:44-45` 列为服务端外部包。
- **只用库那层**：业务代码 import `pi-agent-core` 45 处、`pi-ai` 8 处，没有用 coding-agent。
- **LLM 调用不用 pi-ai 的 provider**：自己写 `createCallLlmStreamFn`（`lib/agent/runtime/stream-fn.ts:255-285`），把自家 `streamLLM`（`lib/ai/llm`）的流翻成 pi 的 `AssistantMessageEventStream`；`Agent` 的 model 是一个 stub（`lib/agent/runtime/build-agent.ts`，`contextWindow: 1_000_000`，注释说为了让 harness 不自动压缩）。pi-ai 只用来拿类型。
- 工具门、配额、超时都用 `afterToolCall` 等回调和包装函数做（`build-agent.ts` import `makeAllowlistGate`、`makeQuotaHook`、`withAgentToolTimeout`）。
- 会话：用 0.78 的 `Session` / `SessionStorage`，自己实现存储接到 `@openmaic/storage`（`lib/server/agent-runtime/entry-tree-storage.ts:1-20`，301 行）；runner 2127 行（`runner.ts:12`）。
- 压缩：`lib/chat/pi/director-compaction.ts:1-22` 从 pi-agent-core import `compact / prepareCompaction / shouldCompact / InMemorySessionRepo`，挂在 `transformContext` 上。
- **这些 API（`Session`、`SessionStorage`、`compact` 等、`/node` 子路径）在 1.0.0 已被删除**（`agent/CHANGELOG.md:63-67`）。OpenMAIC 要升级就得迁到 pi-durable 或 coding-agent 的导出，或者自己留一份。对我们的启示：**照 OpenMAIC 的「pi 只当循环库，模型调用 / 存储 / 压缩自己掌握」的用法最稳**；别依赖 pi 的会话和压缩 API 的位置。

---

## 5. JobApply 的三种接法

### P1：Node 服务用 pi-agent-core 当循环，Java 留作领域服务

```
React（现有页面，不变）
   │ 现有 REST + /api/events（SSE，Last-Event-ID）——仍只连 Java，或 SSE 改由 Node 出
   ▼
Java（Spring Boot）
   ├─ 台账、记忆 / RAG、导入、报告、Dashboard、设置（不变）
   ├─ 浏览器层：BrowserSession / OwnerBrowser / PlaywrightMcpClient / PageScripts（不变）
   │    提交守卫、C 级占位符换值、快照脱敏仍在浏览器工具内部（不变，硬线不出 Java）
   ├─ 对外暴露成 MCP（Streamable HTTP，127.0.0.1）或内部 HTTP：
   │    browser_* 、update_state、remember、search_experience、record_outcome、mark_submitted…
   │    每次调用带 workerId / sessionId → 找对应 OwnerBrowser
   ├─ 拉起并看守 Node 进程（随机端口 + 一次性 token，只在内存）
   └─ 事件：Node 推来的 agent 事件 → RuntimeEvents → 现有 SSE
          │ HTTP / WebSocket（127.0.0.1）
          ▼
Node agent 服务（新，TS，pi-agent-core + pi-ai + pi-mcp，锁精确版本）
   ├─ 每个申请 Worker / Session / 后续 = 一个 Agent 实例（并发）
   ├─ 工具：从 Java 的 MCP 列表生成 AgentTool；execute 直接转发给 Java
   ├─ 中间件链（自己写的一层薄插件接口，挂在 Agent 回调上）：
   │    transformContext：旧快照省略、记忆 / 申请状态注入、压缩（借 pi compaction 的算法）
   │    beforeToolCall / afterToolCall：限次、重复检测、结果裁剪（守卫不放这里）
   │    onPayload / message_end：上下文面板的分类 token 统计（借 A-context-panel 的算法）
   ├─ skill：照 pi loadSkills / formatSkillsForPrompt 的做法，读 storage/playbook/skills，导入外部 SKILL.md
   ├─ provider：pi-ai（DeepSeek 思考、Gemini 签名、ChatGPT 订阅、Anthropic、OpenAI）；密钥由 Java 按调用给（getApiKey），不落盘
   └─ transcript：每条 message_end 回写 Java（Java 追加 transcript.jsonl），Node 不持久化
```

**保留**：React 前端；Java 的浏览器层、守卫、脱敏、台账、记忆 / RAG、导入、报告、API、RuntimeEvents/SSE；skill 和提示词内容。
**重写 / 删除**：`AgentLoop`、`ModelCaller`、`Conversation`、`HarnessLoop` 的循环部分、`SessionLoop`、`FollowupLoop`、`FillContext` 等上下文拼装（搬到 Node）；`ModelFactory` 和 `ChatGptResponsesChatModel`、`ChatGptAuth`、`GeminiThoughtSignatures`（由 pi-ai 替）；三个 Runner 的循环驱动改成「调用 Node 开 / 停 / 插话」，线程池、收件箱、停止状态留在 Java 或移到 Node 二选一。新写：Node 服务、Java 侧 MCP 服务端、进程管理、事件桥。
**风险**：
- 两种语言之间要定协议（工具调用、事件、插话 / 停止、ask_user 回答）；`HarnessLoop` 里「循环 + 填表领域逻辑 + 守卫编排」揉在一起（B-our-agent.md 第 0 节），拆的时候要小心把守卫留在 Java 的工具执行里。
- pi 版本变得快：锁 `1.6.1` 精确版本；只依赖 `Agent` / `AgentTool` / 事件 / pi-ai 的 provider 这几个面。
- 需要 Node ≥ 22.19：`appbuild/*/build.sh` 现在用构建机上的 node（本机 22.13.1），要固定一个 22.19+ / 24 LTS 的 node 一起打包。
- App 增大：pi-ai 全量依赖约 106 MB `node_modules`（可用 pi-ai 的 lazy provider / 打包裁剪减小，未验证）。

### P2：全部搬到 TS（去掉 Java）

浏览器层（共享 CDP、每使用者 MCP 会话、标签页认领、焦点模拟，约 3k 行，B-our-agent.md 4.1）、守卫、脱敏、台账（1086 行）、记忆 / RAG、导入流水线、API 全部用 TS 重写。可以直接用 Playwright 的 Node API 连用户 Chrome（CDP），不再经 Playwright MCP。工作量见 [G-ts-rewrite-sizing.md](G-ts-rewrite-sizing.md)。得到的是单语言、单进程；代价是把最难复刻、踩坑最多的浏览器层重做一遍。pi 本身对 P2 和 P1 没有区别——它只管循环。

### P3：把 pi 的设计借进 Java（= plan A）

pi-agent-core 的回调面正好是 plan A 需要的中间件接口清单：`transformContext`、`convertToLlm`、`beforeToolCall{block,reason,terminate}`、`afterToolCall{content,isError,terminate}`、`prepareNextTurn`、`finishTurn{continue|end}`、`getSteeringMessages / getFollowUpMessages`、工具 `executionMode`、事件 `message_update / tool_execution_*`。压缩照 pi 的「追加 CompactionEntry、firstKeptEntryId、keepRecentTokens 20k、reserve 16k、超长重试一次」实现。skill 照 `formatSkillsForPrompt` + 按需读文件。这样 plan A 的接口能对齐 pi，也能对齐 opencode。代价是 provider 适配（DeepSeek / Gemini / ChatGPT 订阅）仍是我们自己维护。

### 怎么选（只陈述取舍）

- 想要最大自主（UI、存储、记忆、循环细节都在我们手里）又想拿到现成的多 provider 适配：**P1**。比 plan-B（opencode）少交出存储和循环，比 plan-A 少维护 provider。
- 不想引入第二种语言：**P3**（plan A，接口照 pi 定）。
- P2 只有在本来就决定去掉 Java 时才有意义。

---

## 6. 没核实的

- coding-agent SDK 同进程多 `AgentSession` 并发与内存（本机 node 版本不够，没跑起来）。
- pi-ai 打包裁剪后的体积（lazy provider 入口 `pi-ai/lazy-oauth`、`lazy-bedrock-provider` 存在，未验证效果）。
- Node 22.13 上 pi-agent-core 能跑只是实测一次，官方要求 ≥ 22.19。
