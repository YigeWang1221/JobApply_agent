# A. claude-context-panel 调研（只读）

源：用户提供的 zip，解压于 scratchpad/ctxpanel/claude-context-panel/。下文路径相对该目录。未运行其中任何脚本，未安装依赖；dist bundle 只看了开头。

## 1. 它是什么，挂在哪个宿主上

- **宿主是 OpenCode Desktop，不是 Claude Code**。"Claude" 只指 UI 风格：模仿 Claude Code Desktop 的 Context 面板（README.md:1-4，BRIEF.md:1-4）。
- 用 OpenCode 的插件 SDK `@opencode/plugin@2.4.1`（package.json:14-16）。插件分**两半**：
  - **server 半**（Node/Bun 进程内，跑在 agent loop 旁）：`src/index.ts`，入口 `exports["."]`（package.json:9-13）。
  - **GUI 半**（桌面窗口里，Solid.js）：`src/gui.tsx`，用 OpenCode 的 vite preset `@opencode/plugin/gui/vite` 打成 `dist/gui-experimental.cjs`，入口 `exports["./gui-experimental"]`（package.json:12，vite.config.ts）。
  - **共享 RPC 契约**：`src/rpc.ts`，入口 `exports["./rpc"]`。
- **安装/清单**：没有独立 manifest 文件，`package.json` 的 `exports` 三个键就是清单；用户在 `~/.config/opencode/opencode.json` 写 `"plugins": ["./claude-context-panel"]` 加载（README.md:15-22）。运行时不需要 node_modules，SDK 由宿主提供（build.sh:3）。

### 用到的确切 API

server 半（src/index.ts）：
- `import { Plugin } from "@opencode/plugin"`；`export default Plugin.define({ id: "claude-context-panel", async setup(ctx) {...} })`（index.ts:9, 71-73）
- `ctx.session.hook("context", (event) => ...)` —— 每次 agent loop 发模型请求**之前**触发，event 是 `SessionContext`：`{ sessionID, agent, model:{providerID,id}, system:[{type:"text",text,metadata?.experimental?.instructions:[{key,text}]}], messages:[...], tools:{name:{description,input}}, options:{maxTokens?} }`（index.ts:107；BRIEF.md:30-48）
- `ctx.rpc.register(ContextPanelRpc, { overview, compact })`（index.ts:134-154）
- `ctx.session.compact({ sessionID })` —— 触发宿主自带的会话压缩（index.ts:148）
- `ctx.session.get({sessionID}).tokens` / `ctx.session.context({sessionID})` / `ctx.session.list({parentID, limit})`（overview.ts:503, 525, 604-605）
- `ctx.model.list()` → `limit.context` 得到窗口大小（overview.ts:606；BRIEF.md:69）
- `ctx.mcp.list()` → `{location, data}`（index.ts:86）
- `ctx.app.version`（index.ts:157）

RPC（src/rpc.ts）：
- `import { Rpc } from "@opencode/plugin/rpc"`；`Rpc.define({ id, methods: { overview:{input,output}, compact:{input,output} }, events: {} })`，input/output 都是 **JSON Schema**（rpc.ts:7-181）。`events: {}` 说明 RPC 也支持服务端推事件，本插件没用。

GUI 半（src/gui.tsx）：
- `import { Command, Panel, Plugin, Slot, Style } from "@opencode/plugin/gui"`（gui.tsx:1）
- `Plugin.define({ setup(ctx) {...} })`（gui.tsx:10-11），`ctx.id` 用来拼 panel key `${ctx.id}:main`（gui.tsx:12）
- `ctx.add(Panel, { id, region:"side", width:340, list:()=>[tab], render })`，tab 字段 `id,title,label,pinned,fallback,closable`（gui.tsx:15-31）
- `ctx.add(Style, css)`（gui.tsx:33）
- `ctx.add(Command, { id, title, description, keywords, section, slash:{name:"context-panel"}, run })`（gui.tsx:35-50）
- `ctx.add(Slot, { at: "session.header", render })`（gui.tsx:109-120）
- `ctx.layout.open/close/state/toggle(key, session, {tab:"select"})`，`ctx.sessions.current()`（gui.tsx:43-101）
- 调 RPC：`props.session.server.client.rpc(ContextPanelRpc).overview({ sessionID })`（panel/Panel.tsx:99, 182, 303）

## 2. 功能细节

### estimate.ts：纯启发式估 token
- 按 Unicode 脚本分密度：CJK 1.5 字符/token，西里尔 2.5，其它 4；每个 block/message 加 4 token 结构开销；向上取整（estimate.ts:16-26, 86-120）。
- 工具结果四种形态 `content/text/error/json` 分别算，未知形态按 JSON 算，"永不白给"（estimate.ts:170-190）。
- 图片：宽×高/750，未知尺寸按 800（estimate.ts:28-32, 198-217）。
- 全是纯函数，不调 tokenizer。

### fold.ts：不是压缩，是"分类记账"
- **fold 不改消息**，是把一次外发请求"折叠"成 9 个类别的相对 token 数（fold.ts:1-20，"The fold is pure: it reads the event, never mutates it"）。
- 分类：assistant 文本 + tool-call 入参；toolResult；other（reasoning/media/compaction/未知）；systemTools / mcpTools（按 `mcp__`/`mcp_` 前缀或 MCP server 名切，fold.ts:93-102）；skills（tool-result 首段以 `<skill_content` 开头，fold.ts:35, 257-268）；system；user；instructions（system part 的 `metadata.experimental.instructions` 里像 AGENTS.md/rules 的 key，fold.ts:146-160, 325-349）。
- 同时产出明细列表：每个工具定义、每个 skill、每个指令文件各占多少（fold.ts:209-227, 371-376），以及 `options.maxTokens` 作为保留输出（fold.ts:301-305）。

### overview.ts：估算 × 真实读数
- 真实占用 `used = input+output+reasoning+cache.read+cache.write`，取最后一条 assistant 消息的 usage（overview.ts:148, 192-194 节选第 192 行附近）。
- 把 fold 的相对比例**按真实 used 等比缩放**，余数给最大行，使 9 行之和精确等于 used；free = window-used（overview.ts `buildRows`，约 325-359 行）。思路：类别比例靠估，总量靠 provider 报的真数。
- 自动压缩距离：`buffer = min(window, max(15% window, 16000))`，`threshold = window - buffer`，`distance = threshold - used`（`compactionOf`，约 355-364 行）。这是**复刻宿主的压缩策略来显示**，插件自己并不执行自动压缩。
- 没有 hook 快照时（例如重启后）用 `session.context` 的历史消息做 fallback 估算（`fallbackContributions`，约 270-323 行）。
- 统计：cache 命中率、输出 tok/s、TTFT；成本（USD→CNY，订阅制 cost=0 时按模型单价估，含子 agent 递归≤3 层/64 个）；750ms 缓存防轮询重复遍历（overview.ts:38-40, 53-59, 668-690）。

### rpc.ts / 面板通信
- GUI 每 1.5–2s **轮询** `overview` RPC（Panel.tsx:111, 215）；"Compact session" 按钮调 `compact` RPC → 服务端 `ctx.session.compact` → 再轮询（Panel.tsx:295-309）。
- RPC 方法内部全包 try/catch，永不向外抛，失败返回空 overview 或 `{ok:false,error}`（index.ts:134-154）。hook 回调也 try/catch，"A malformed request must never break the agent loop"（index.ts:126-128）。

## 3. 插件架构模式（重点）

1. **声明**：`Plugin.define({ id, setup(ctx) })`，一个默认导出；能力都通过 `ctx` 拿，不是继承类或注解。server 和 GUI 各一个 `Plugin.define`，两边 SDK 子路径不同（`@opencode/plugin` vs `@opencode/plugin/gui`）。
2. **加载**：宿主读用户配置 `plugins: [...]` 里的路径/包名 → 按 `package.json exports` 找 server 入口（`.`）与 GUI 入口（`./gui-experimental`）→ 调 `setup(ctx)`。SDK 是宿主运行时注入的 peer 依赖。
3. **server 侧能观察/改变的**：
   - 观察：`ctx.session.hook("context")` 拿到**完整外发请求**（system parts 带来源元数据、messages、tools schema、options）；`session.get/context/list` 读会话记录、usage、子会话；`model.list` 读模型窗口与价格；`mcp.list`。
   - 改变：`ctx.session.compact` 触发宿主压缩。本插件**没有改写消息**；hook 的返回值能否改写请求，本仓库代码未体现，未核实（需要查 OpenCode 文档 `opencode.ai/docs/build/plugins/server`）。
   - 暴露：`ctx.rpc.register` 把服务端函数以 JSON Schema 契约暴露给 GUI。
4. **GUI 侧能加的**：`Panel`（侧栏面板+tab）、`Slot`（插到宿主预留的位置，如 `session.header`）、`Command`（命令面板+斜杠命令）、`Style`（注入 CSS）、`layout` 操作（甚至关掉内置面板 `context:main` 换成自己的，gui.tsx:52-107）。
5. **关键设计点**：
   - 宿主自己的元数据（system part 标注 `instructions:[{key,text}]`、tool-result 里 `<skill_content name=...>` 标记）让插件能按来源分类——**宿主要把"这段上下文从哪来"作为结构化信息传给 hook**。
   - server/GUI 之间只靠一份 typed RPC 契约，GUI 不直接碰 agent 内部。
   - 插件失败隔离：所有回调都吞掉自己的异常，不影响 agent loop。

## 4. 能移植到 JobApply 的部分 / 我们的运行时需要暴露什么

JobApply 现状：每步发全量对话，无压缩。若要把"上下文管理"做成插件，宿主（我们的 agent 运行时）至少要暴露：

| 宿主钩子/能力 | 对应 OpenCode | 用途 |
| --- | --- | --- |
| **before-model-call 钩子**，拿到本次要发的 `{sessionId, model, systemParts(带来源key), messages, tools, options}` | `session.hook("context")` | 记账、面板。若允许**返回改写后的 messages**，即可实现插件式压缩/裁剪工具结果（OpenCode 这个插件只读，改写需我们自己设计）。 |
| **after-model-call / usage 事件**：provider 返回的 input/output/cache tokens、耗时、cost | `session.get().tokens`、消息上的 usage | 用真实 used 校准估算比例；算 cache 命中、速度、成本。 |
| **模型元数据**：上下文窗口、单价 | `model.list().limit.context` | 算占用率和压缩阈值。 |
| **压缩动作**：`compact(sessionId)`，由宿主执行（生成摘要替换旧消息，消息里留 `compaction` part） | `session.compact` | 手动按钮 + 阈值自动触发（15%/16k buffer 是可参考的默认策略）。 |
| **会话读取**：历史消息、子会话列表 | `session.context/list` | 重启后 fallback 估算；子 agent 汇总。 |
| **结构化来源标注**：system prompt 分段带 key（AGENTS/指令/环境），skill 内容带标记，MCP 工具名带前缀 | `metadata.experimental.instructions`、`<skill_content>` | 让插件知道哪块是 skill、哪块是工具 schema、哪块是浏览器快照等，才能有针对性压缩。 |
| **插件 ↔ 前端 RPC**（JSON Schema 契约）+ **UI 扩展位**：侧栏 Panel、header Slot、斜杠 Command、CSS | `rpc.register`、`Panel/Slot/Command/Style` | 前端（React）面板展示占用条和"压缩"按钮。 |
| **失败隔离**：插件回调异常不能打断 agent loop | 插件自己 try/catch | 宿主侧也应兜底。 |

直接可抄的思路：
- 估算器（脚本密度 + 块开销）在 Java 里几十行即可实现；中文页面内容多，CJK 1.5 字符/token 的区分有意义。
- "估算比例 × 真实总量"的归一化，避免启发式漂移。
- 按类别看占用，尤其 toolResult（我们的浏览器快照/页面文本大概率是大头）——这正是压缩的首要目标。

不可直接移植：它本身不实现压缩算法（压缩是宿主 `session.compact` 做的）；UI 是 Solid.js + OpenCode Desktop 专有组件，不能在我们的 React 前端里直接用。

注意：BRIEF.md 引用了作者本机路径与第三方 MIT 参考仓库，与我们无关。
