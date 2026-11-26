# opencode 调研（给 JobApply 重建 Agent 底座用）

调研日期：2026-11-25
读的代码：`https://github.com/sst/opencode`（已 301 重定向到 **`anomalyco/opencode`**），默认分支 `dev`，提交 **`ea8aa5b05ef49af86d6c78dad0a19e2877e034e0`**（2026-11-25），根 `package.json` 版本 **2.4.1**。
本地克隆：`/private/tmp/claude-501/-Users-yigewang-Documents-Personal-learning-2026-JobApply-Agent/0193ca48-8b8c-46a2-bc41-c8aa64f661f0/scratchpad/repos/opencode`
官方文档的源文件就在仓库里：`services/site/src/docs/content/*.mdx`（V2），`services/site/src/docs/v1/content/*.mdx`（V1）。下文引用的「文档」都指这些文件（线上对应 opencode.ai/docs/...）。

> **最重要的前提：opencode 现在有两代。**
> - **V1**：npm `opencode-ai` / `@opencode-ai/sdk` / `@opencode-ai/plugin`，最新 1.19.6（2026-11-17 仍在发版）。网上大多数教程、社区插件、"tool.execute.before / chat.params / experimental.session.compacting" 这套钩子都是 V1。
> - **V2**：npm `@opencode/cli` / `@opencode/client` / `@opencode/sdk` / `@opencode/plugin`，2.0.0 发布于 2026-09-11，到 2.4.1 共 51 个版本（约 2.5 个月）。**插件 API、服务端 HTTP API、客户端全部是破坏性变更**（`migrate-v1.mdx`「Breaking changes」）。V1 插件在 V2 里不运行。
> - 如果要基于 opencode 重建，应该直接对 V2；但 V2 很新、变动快，大量接口标着 experimental。

---

## 1. 架构

| 项 | 结论 | 出处 |
| --- | --- | --- |
| 语言/运行时 | TypeScript 全栈，Bun 1.4.4 开发；核心大量使用 **Effect 4**（`effect` 库）；TUI 用 OpenTUI + Solid（不是 Go，早期 Go TUI 已不存在） | 根 `package.json`、`packages/tui` |
| 包结构 | `packages/schema → core/protocol → server`；`client`（浏览器可用，只依赖 schema/protocol）；`sdk` = client+core+server 的进程内组合；`plugin`；`ai`（自研 LLM 适配层，兼容 AI SDK `LanguageModelV3`）；`desktop`（Electron 类桌面端）；`app`（Web UI） | `AGENTS.md`、`packages/*` |
| 发布形态 | CLI 是 **`bun build --compile` 的单文件可执行**，每平台一个 npm 包；`@opencode/cli-darwin-arm64` 解包约 **240 MB**。另有 `script/build-node.ts`（Node 26.4 SEA 构建） | `packages/cli/script/build.ts`、`build-node.ts`、`npm view` |
| 客户端/服务端 | 一切都走服务端：TUI、Web、Desktop 都只是 HTTP 客户端。默认是**共享后台服务**（`opencode service ...`，macOS 默认端口 49374，密码保护）；`opencode serve --hostname --port --cors` 是前台独立进程 | `cli/web.mdx` |
| 认证 | HTTP Basic，用户名固定 `opencode`，密码 `OPENCODE_PASSWORD`（`OPENCODE_SERVER_PASSWORD` 兼容） | `cli/web.mdx`、`migrate-v1.mdx` |
| HTTP API | `/api/...`，OpenAPI 3.1：`packages/protocol/openapi.json`（info 写着 *"Experimental HttpApi surface"*）。主要资源：session（create/prompt/command/compact/interrupt/fork/revert/permission/form/context/message/inbox/synthetic/shell/model/agent）、agent、model、provider、mcp、skill、plugin、permission、credential、integration、fs、pty、`/api/rpc/{rpcID}/{method}`（插件 RPC） | openapi.json |
| 事件流 | `GET /api/event`，**`text/event-stream`（SSE）**，可按 `types` 过滤。约 100 种事件，含 `session.text.delta`、`session.reasoning.delta`、`session.tool.called/progress/success/failed`、`session.step.*`、`session.usage.updated`（tokens+cost）、`session.compaction.started/delta/ended/failed`、`permission.asked/replied`、`form.created`、`session.idle`、`mcp.status.changed` 等。**只推实时，无回放、无自动重连**，断线要重新拉状态 | openapi.json、`build/client/index.mdx` |
| JS 客户端 | `@opencode/client`：`OpenCode.make({baseUrl, headers})`，`client.session.create/prompt`，`for await (e of client.event.subscribe())`；`@opencode/client/service` 的 `Service.ensure()` 能发现/拉起本机服务 | `build/client/index.mdx` |
| 嵌入 | `@opencode/sdk`：`OpenCode.create({plugins})` 在进程内组装服务端，**无 HTTP 监听**，同一套 API；可在创建时传插件对象 | `build/sdk/index.mdx` |
| 存储 | SQLite（drizzle + `@effect/sql-sqlite-bun/node`，有 bun/node/workerd 三种驱动），会话、消息、权限批准、插件 storage 都在里面；有 V1→V2 迁移 | `packages/core/src/database/*` |
| 并发 | `SessionRunCoordinator`：**每个 session 串行，不同 session 并发**（Effect fiber） | `packages/core/src/session/run-coordinator.ts` |
| 许可证 | **MIT** | `LICENSE`、GitHub API |
| 热度 | ~22.9 万 star，open issues ~4.1k | GitHub API |

**非 TUI 应用能否把它当服务器驱动？能，这是官方主推用法之一**（`build/index.mdx`「Run it as a server」）。React 前端可直接用 `@opencode/client`（浏览器兼容）连 serve，或由 Java 后端用 HTTP + SSE 代理（无官方 Java SDK，但有 OpenAPI 3.1 可生成，注意它标着 experimental）。权限/问题这类交互需要客户端自己实现 UI：`permission.asked` 事件 → `POST /api/session/{id}/permission/{requestID}/reply`（`once/always/reject`）；`question` 工具对应 `form.*` 事件和 `/api/session/{id}/form/{formID}/reply`。

## 2. Agent 模型

- **primary / subagent / all** 三种 mode（`agents.mdx`）。内置：`build`（默认，全权限）、`plan`（只读规划）、`general`（子 agent，不能再开子 agent）、`explore`（只读子 agent）；隐藏的 `compaction`、`title`、`summary`。
- **定义方式**：`.opencode/agents/<name>.md`（frontmatter + 正文=system prompt）或 `opencode.jsonc` 的 `agents` 下；字段：`description`、`mode`、`model`（`provider/model#variant`）、`system`、`permissions`（有序规则数组）、`steps`（最大步数，最后一步撤掉工具强制总结）、`hidden`、`color`、`disabled`。
- **子 agent**：通过 `subagent` 工具启动，跑在**子 session**里、全新上下文，**总是后台运行**；父会话在子会话 idle 时收到 `<subagent>` 消息；可用 sessionID 继续对话；默认嵌套深度 1。
- **主循环**：`packages/core/src/session/runner/step.ts`、`execution.ts`、`model-request.ts`：组装 system+历史+工具 → 流式调用模型 → 执行工具 → 判断是否继续（`Outcome: Completed/Retry/RecoverFull/Compacted/OutputLimit`），上下文溢出走 compaction 恢复。输入走 durable **inbox**（`steer` 插队 / `queue` 排队）。
- **内置工具**（`tools.mdx`）：read/glob/grep/edit/write/patch、shell、webfetch/websearch、image generate、question、skill、subagent、**execute（Code Mode：模型写 JS 组合调用工具）**、session/worktree 工具、`browser`（控制的是 **OpenCode Desktop 内置浏览器**，不是用户自己的 Chrome）。
- **自定义工具**：V2 通过插件 `ctx.tool.transform(editor => editor.add({name, description, input: JSONSchema, execute}))`，执行器拿到 `context.signal`、`context.progress()`。V1 时代的 `.opencode/tool/*.ts` 目录在 V2 文档里未出现（V2 插件迁移指南把它归到 `ctx.tool.transform`），按「V2 只靠插件/MCP 加工具」理解（推测，未逐行确认没有兼容加载）。
- **MCP**（`mcp-servers.mdx`）：`mcp.servers.<name>`，`type: local`（stdio，`command` 数组、`cwd`、`environment`）或 `remote`（Streamable HTTP，默认 OAuth）。超时 `startup 30s / catalog 30s / execution 12h`（长时间浏览器调用无压力）。**默认 MCP 工具通过 Code Mode（`execute`）暴露**，设 `codemode: false` 才直接列成工具。工具调用的 `_meta["ai.opencode/sessionID"]` 会带上会话 ID（多 worker 场景可用于在自家 MCP 里区分会话）。每个「目录/location」各自连一份 MCP；`pool: true` 才共享。
- **权限**（`permissions.mdx`）：有序 `{action, resource, effect: allow|ask|deny}`，最后匹配者胜，无匹配=ask。action 有 `read/edit/glob/grep/shell/subagent/skill/question/webfetch/websearch/external_directory/execute`，**MCP 工具的 action 是 `<server>_<tool>`，resource 固定 `*`**（也就是说配置规则无法按「点的是哪个按钮」区分，只能整工具 allow/ask/deny）。批准回复 `once/always/reject`，`always` 存为项目级规则。另有 `policies`（只能收紧）。

## 3. 插件系统（V2，`build/plugins/*.mdx`）

**入口签名**
```ts
import { Plugin } from "@opencode/plugin"
export default Plugin.define({
  id: "example",
  async setup(ctx) { /* 注册 transform / hook */ return () => {/* cleanup */} },
})
```
- `ctx` 本质是一个服务端客户端（同 `@opencode/client` 的方法）+ 插件专用：`ctx.location`、`ctx.options`、`ctx.storage`（插件私有 KV，持久化）、`ctx.event.subscribe()`、各域 `transform/hook/reload`、`ctx.experimental.generate.text()`（不建会话直接调模型）。
- 包可以有 4 个部分：server（`.`）、TUI（`./tui`）、GUI（`./gui-experimental`，只给 OpenCode Desktop 加面板）、RPC（`./rpc`，类型化方法+事件，供客户端经 `/api/rpc/...` 调用——**这是给自家 React UI 暴露插件数据的正路**）。
- 也有 Effect 版插件 API（`build/plugins/effect.mdx`）。

**加载**：`.opencode/plugins/`（及 `~/.config/opencode/plugins/`）里的 `.ts/.js` 文件或包目录自动加载；`opencode.jsonc` 的 `"plugins": [...]` 写 npm 包名（可带版本/范围）、git 规格、本地目录、`{package, options}`；`-id` 禁用、`*` 通配；`opencode plugin add/list/check/update/remove`；带范围的包每 24h 自动更新；监听目录变化自动重载。

**Transforms（改状态）**：`ctx.agent / provider / model / command / integration / mcp / reference / skill / tool / vcs / websearch / worktree .transform(editor => ...)`，同步、可重放。可以增删改 agent、工具、skill、provider、模型。

**Hooks（拦截运行中操作）**
| 钩子 | 能做什么 | 对应 V1 |
| --- | --- | --- |
| `ctx.session.hook("prompt")` | 用户消息入库前改 text/files/skills/metadata/delivery | `chat.message` |
| `ctx.session.hook("context")` | **每次主循环调模型前**改 `system[]`、`messages[]`、`tools`、`options`（温度、maxTokens、provider 选项）；只影响本次请求不改历史 | `chat.params`、`experimental.chat.system.transform`、`experimental.chat.messages.transform` |
| `ctx.session.hook("compaction")` | 改被总结的 transcript；设 `event.result = {summary}` **完全接管摘要**，跳过模型调用 | `experimental.session.compacting` |
| `generate` / `title` | 辅助请求 | — |
| `experimental.model.resolve` | 按请求换模型（同 provider） | — |
| `model.request` / `http.request` / `http.response` / `experimental.ws.*` | 改请求头、原始 HTTP/WS | `chat.headers` |
| `retry` | 改重试策略 | — |
| `fork` | 会话分叉后 | — |
| `ctx.permission.hook("evaluate")` | 在配置规则之后、执行/弹窗之前改 `effect`（allow/ask/deny）+ `message`；显式 deny 不进钩子 | `permission.ask` |
| `ctx.tool.hook("execute.before")` | 看/改工具名和 input；**throw 即拒绝本次调用**（转为 `Tool.Error`，模型能看到原因）；Code Mode 里的嵌套调用也会触发（带 `codemode` 标志） | `tool.execute.before` |
| `ctx.tool.hook("execute.after")` | 改结果/观察错误 | `tool.execute.after` |
| `ctx.shell.hook("create.before")` | 改 shell 命令/env/超时 | `shell.env` |

证据：`packages/plugin/src/promise/adapter.ts`（`// Only execute.before may reject a call; a rejection fails that call alone`）、`packages/core/src/tool.ts` 第 161–238 行。

**另外两个对我们有用的会话 API**：`ctx.session.instructions.entry.put({sessionID, key, value})`（往会话指令里挂 `<context key>` 块，不占消息轮次——适合注入「当前岗位/简历摘要」）、`ctx.session.compact()`、`ctx.session.synthetic()`（插入合成消息）。

**社区插件实例**（`docs/v1/content/ecosystem.mdx`，**几乎都是 V1 API**）：`opencode-dynamic-context-pruning`（剪旧工具输出）、`opencode-morph-plugin`（含上下文压缩）、`opencode-openai-codex-auth`（ChatGPT 订阅）、`opencode-gemini-auth`、`opencode-supermemory`（跨会话记忆）、`oh-my-opencode`（后台 agent + Claude Code 兼容）、`opencode-notifier`、`opencode-vibeguard`（发给 LLM 前脱敏）、`opencode-goal-plugin`、`opencode-scheduler`、`@tavily/opencode-plugin`、`opencode-firecrawl`。基于 SDK 的第三方 UI：OpenChamber、CodeNomad、portal、OpenWork、kimaki（说明「自己做 UI 驱动 opencode 服务」是成熟路线，但它们多数也还在 V1 API 上）。
内置插件也是很好的范例：`packages/core/src/plugin/home-memory.ts`（跨会话记忆目录 + 每日整理）、`plugin/provider/chatgpt.ts`、`plugin/provider/openai-context-caps.ts`、`plugin/goal.ts`。

## 4. Skills

**原生支持 SKILL.md**（`skills.mdx`）：
- 发现路径：全局 `~/.config/opencode/skills`，**兼容 `~/.claude/skills`、`~/.agents/skills`**；项目 `.opencode/skills`，**兼容 `.claude/skills`、`.agents/skills`**（从当前目录向上到项目根）；`opencode.jsonc` 的 `skills: [路径或 https 目录]`（HTTP catalog 用 `index.json`）。
- 形态：`<id>/SKILL.md`（推荐，可带 scripts/references）或根级 `<id>.md`。ID 来自路径而非 `name`。
- **渐进披露**：每步只把有 `description` 的 skill 的 id/name/description 列给模型；模型调 `skill` 工具（`{id}`）才注入正文 + 基目录 + 最多 10 个附属文件路径；附属文件由模型按需 read。`@skill-id` 可手动加载。`disable-model-invocation: true` / `metadata.opencode/autoinvoke: false` 隐藏。`skill` 权限可按 ID allow/ask/deny。插件可 `ctx.skill.transform` 动态增删。
- 结论：Claude Code 格式的外部 skill 基本可以直接放进去用（`license`、`compatibility` 等字段接受但不解释）。

## 5. 上下文管理

V2（`compaction.mdx`、`packages/core/src/session/compaction.ts`）：
- **触发**：`compaction.auto`（默认 true）；`buffer` 默认窗口的 15%、至少 16k 留空，达到其余部分即压缩；provider 报「太长」时压缩后重试一次。手动：`POST /api/session/{id}/compact`（下一个安全点执行）。
- **做法**：把**整段对话**（复用 prompt cache）+ 摘要指令发给模型，生成结构化摘要（Objective / Requirements / Decisions / Work State[Completed/Active/Blocked] / Next Move / Relevant Files / Important Context，模板见 `compaction.ts` 第 175–206 行），再保留最近约 `keep.tokens`=15000 的原文；保留部分里长工具输出被截短、附件换成描述。之后的压缩是「更新已有 checkpoint」。
- **后台预压缩**（`background: true`）：离阈值 60k token 时提前在后台写摘要，到点直接换入，不卡会话。
- 可用 `agents.compaction.model` 指定摘要模型；OpenAI Responses 可用 provider 原生压缩（加密 checkpoint）。
- V1 的「剪旧工具输出（prune）」在 V2 中改为摘要 + keep.tokens。
- **Token 记账**：每条 assistant 消息、Session.Info 都有 `tokens {input, output, reasoning, cache{read,write}}` 和 `cost`；`session.usage.updated` 事件实时推送；`GET /api/experimental/session/stats` 有按模型/工具的统计；模型窗口上限来自 models.dev（`/api/model`）。**「上下文用量面板」在自家 UI 里 = 订阅 `session.usage.updated` + 读模型 `limit`**，不需要插件；如果要更细（system/工具 schema/历史各占多少），写一个插件在 `context` 钩子里估算并经 RPC 暴露。
- **插件定制**：`compaction` 钩子可改输入或整个接管摘要；`context` 钩子可以在每次调用前自行裁剪 `messages`（实现自定义 prune）；`ctx.session.compact()` 主动触发。

## 6. Provider

- 模型目录来自 **models.dev**；V2 用自研 `@opencode/ai`（内置 deepseek、google、google-vertex、openai、anthropic、openrouter、xai、moonshot、zai、alibaba 等几十个 provider 驱动，见 `packages/ai/src/providers/`），同时兼容 AI SDK `LanguageModelV3` 的 npm provider 包（`packages/core/src/aisdk.ts`）。
- **自定义 OpenAI 兼容**：`providers.<id> = { package: "@opencode/ai/providers/openai-compatible", settings: {baseURL}, env: ["XX_API_KEY"], models: {...} }`；DeepSeek、Gemini 已在目录内，直接 `/connect` 配 key 即可。
- **订阅登录**：ChatGPT Plus/Pro 「Sign in with ChatGPT」内置（`plugin/provider/chatgpt.ts`；默认把上下文上限压到 272k/500k，可用 `-opencode.provider.openai-context-caps` 关掉）；GitHub Copilot 内置；**Claude 订阅**通过「Claude Agent」provider——实际在本机调用已登录的 Claude Code 2.1.248+ 来发模型请求，opencode 负责工具/权限/压缩（`claude-agent.mdx`，标 noindex，限制：不支持温度等、`http.*` 钩子不生效、Claude Code 系统提示替换 opencode 的）。Gemini 订阅靠社区 `opencode-gemini-auth`（V1）。
- 凭证存 opencode 自己的 credential 存储（`/api/credential`），也可用 env。

## 7. 对 JobApply 的适配评估

### 现状回顾（来自题述）
Java 25 / Spring AI 后端跑 Agent 循环；React 前端；Swift 壳打包 Java runtime + node + Playwright MCP；Agent 通过 Playwright MCP 驱动用户自己正在用的 Chrome；提交前停；本地 markdown/csv 记忆；多个申请 worker 并行。

### 方案 (a)：opencode serve 做 Agent 核心，Java 保留为领域服务（MCP / 自定义工具）
做法：Swift 壳额外带 `opencode` 可执行（或用已打包的 node 跑 Node 构建 / `@opencode/sdk` 小 sidecar）；`opencode.jsonc` 里配 Playwright MCP（`codemode:false`，`--cdp-endpoint` 连用户 Chrome）、Java 后端暴露成 remote MCP（简历/岗位/记忆/CSV）；JobApply 的 agent、skill 放 `.opencode/agents`、`.opencode/skills`；提交守卫、上下文面板数据、记忆注入写成一个本地插件；React 前端经 Java 代理或直接 `@opencode/client` 消费 SSE。
- 得到：成熟的循环、子 agent（后台并行）、自动/后台压缩、skill 渐进披露、Claude/ChatGPT 订阅登录、权限/问答协议、token/cost 统计、SQLite 会话持久化，全部现成。
- 每个申请 = 一个 session（或主 session 派子 agent），opencode 本身支持不同 session 并发。

### 方案 (b)：把 opencode 的设计移植进 Java 运行时
值得抄的点（都很小、能看懂）：
1. 压缩：阈值 = 窗口 − max(15%,16k)；整段对话 + 结构化摘要模板 + 保留最近 ~15k 原文；保留区截断长工具输出；后续压缩做「更新 checkpoint」；溢出时压缩重试一次（`compaction.ts`）。
2. 每次调模型前的 `context` 钩子：只改出站请求不改历史——Java 里对应一个 `ChatRequest` 拦截链。
3. `tool execute.before` 抛错 = 只拒绝这一次调用并把原因还给模型（提交守卫的最自然形态）。
4. skill 渐进披露：每步只列 id/name/description，`skill` 工具按需加载正文 + 附属文件清单；兼容 `.claude/skills`。
5. 子 agent 后台运行 + 空闲时回报父会话。
6. 事件模型：`text.delta / tool.called / tool.success / usage.updated / compaction.*` 一套 SSE 事件给前端。
7. instruction entries：会话级 `<context key>` 块，改动在下一步边界生效，不占消息轮次。

### 方案 (c)：混合 / 其它
- (c1) 先只借 opencode 的「Claude/ChatGPT 订阅登录」或压缩算法，循环留在 Java——收益小，复杂度不小。
- (c2) 用 `@opencode/sdk` 在 Node sidecar 内嵌（复用已打包的 node，不另带 240MB 二进制），Java 通过 sidecar 的 HTTP 交互——需确认 Node 下 SDK 的完整性（仓库有 `sqlite.node.ts`、`build-node.ts`，但主发行物是 Bun 编译版，**未验证**）。

### 具体风险

| 风险 | 说明 | 严重度 |
| --- | --- | --- |
| **V2 接口稳定性** | V2 两个半月 51 个版本；OpenAPI 自称 experimental；大量 `experimental.*` 钩子/路由；文档里还写着某些字段「preserved but not yet sent」。插件 API 刚整体重写过一次，社区插件大多还停在 V1。锁定版本可缓解，但升级会持续有迁移成本。 | 高 |
| **打包体积/运行时** | 不用单独打包 Bun：发行物是 bun 编译的单文件（darwin-arm64 ≈240MB 解包），Intel 需另一份。加上现有 JRE + node + Playwright，App 会明显变大。Node SEA 构建存在但不是主渠道。 | 中 |
| **内存** | issue 里近期有多起：`#66058` 13 个空闲 TUI 会话 ~10 GiB、`#64159` serve watcher 泄漏、`#61313` transcript 内存随全量日志增长、`#64896` SQLite OOM、`#20695` Memory Megathread（已关）。很多是 TUI/桌面端问题，但 server 也有。JobApply 刚做过 C28「RAM 占用过高」修复，这里要实测。 | 中-高 |
| **并行 worker** | 会话间并发 OK；但 **一个 location（目录）共用一份 MCP 连接**，几个 session 同时用同一个 Playwright MCP（连同一个用户 Chrome）会抢当前 tab——这本来就是 JobApply 自己要解决的问题，opencode 不会替你解决。可借 `_meta["ai.opencode/sessionID"]` 在自家 MCP/包装层里把会话映射到 tab；或每个 worker 用不同目录让 MCP 各起一份。 | 中 |
| **长时间浏览器调用** | MCP 执行超时默认 12h，工具执行器有 `signal` 可中断，`session.tool.progress` 可推进度——没问题。注意 MCP 默认走 Code Mode，要对 Playwright 设 `codemode:false`。 | 低 |
| **自定义 UI 替代 TUI** | 官方支持（Desktop/Web 本身就是这么做的）。需要自己实现：事件→消息渲染、权限请求回复、`question` 表单回复、断线重拉（SSE 无回放）。Desktop 的 GUI 插件面板**不能**用在我们的 React 里，插件数据要走 RPC。 | 低-中 |
| **提交守卫** | 可做且干净：插件 `ctx.tool.hook("execute.before")` 检查 Playwright 的 `browser_click` 等输入（元素描述/ref 指向的按钮文字），命中「Submit/提交」类时 throw，模型收到拒绝原因继续；或在钩子里 `ctx.permission.assert({action:"submit", ...})` 走 ask，交给用户。注意：仅靠配置规则做不到（MCP 工具 resource 固定 `*`）；判断「是不是最终提交」仍要靠我们自己的启发式/页面快照，和现在 Java 里的难度一样。Code Mode 嵌套调用也会触发该钩子。 | 低（机制）/中（判定逻辑） |
| **Agent 默认人设是写代码的** | 内置 build/plan 和系统提示面向编码；要用自定义 primary agent + `system` 覆盖，并 deny 掉 edit/shell 等用不上的工具，否则模型会乱用。`instructions`/AGENTS.md 发现逻辑也会把工作目录的文件带进来，要控制工作目录。 | 中 |
| **与现有 Java 资产的关系** | 选 (a) 后 Spring AI 循环、现有压缩/记忆/通知代码大部分作废，Java 退化为领域 MCP 服务 + 前端代理；这是一次大迁移，违背「一次只改一件事」的仓库原则，必须拆成多步。 | 高（工程量） |
| **安全默认** | V2 默认 `external_directory` allow、build agent 全权限；作为用户本机工具可接受（符合「不过度设计安全」），但要确认 `.env`/密钥读取规则（build 对敏感 env 文件默认 ask）。 | 低 |

### 我的倾向（供决策，不是结论）
- 如果目标是**尽快获得**压缩、上下文面板、skill 导入、子 agent、订阅登录，且能接受一个快速变化的上游：选 **(a)**，锁定一个 V2 版本，Java 变成 MCP 领域服务；第一步可以只做「opencode serve + Playwright MCP + 一个 JobApply agent + 提交守卫插件」跑通一份申请，再决定是否迁移 UI。
- 如果更看重**稳定、内存可控、单一 Java 运行时、少一个 240MB 进程**：选 **(b)**，按上面 7 点把设计搬进 Spring AI 运行时，每点一份 Coding Plan；其中「压缩算法 + context 拦截链 + skill 渐进披露 + execute.before 守卫」收益最大。

---

## 主要来源
- 仓库：https://github.com/anomalyco/opencode @ `ea8aa5b05ef49af86d6c78dad0a19e2877e034e0`
- 文档源文件（V2）：`services/site/src/docs/content/` 下 `plugins.mdx`、`build/plugins/index.mdx`、`build/plugins/server.mdx`、`build/plugins/migrate-v1.mdx`、`build/client/index.mdx`、`build/sdk/index.mdx`、`cli/web.mdx`、`agents.mdx`、`tools.mdx`、`permissions.mdx`、`mcp-servers.mdx`、`skills.mdx`、`compaction.mdx`、`providers.mdx`、`claude-agent.mdx`、`migrate-v1.mdx`（线上：https://opencode.ai/docs/...）
- V1 文档：`services/site/src/docs/v1/content/ecosystem.mdx`、`server.mdx`、`plugins.mdx`、`custom-tools.mdx`
- 代码：`packages/protocol/openapi.json`；`packages/core/src/session/compaction.ts`、`session/run-coordinator.ts`、`session/runner/step.ts`；`packages/core/src/tool.ts`；`packages/core/src/plugin/hooks.ts`、`plugin/home-memory.ts`、`plugin/provider/*`；`packages/plugin/src/promise/adapter.ts`；`packages/cli/script/build.ts`、`build-node.ts`；`packages/ai/src/providers/`
- npm：`@opencode/cli` 2.4.1（2026-11-23）、`@opencode/cli-darwin-arm64` unpacked 240,033,041 B、`opencode-ai` 1.19.6（2026-11-17）
- Issues：#66058、#64159、#61313、#64896、#20695（GitHub 搜索 "memory"，2026-11-25）
