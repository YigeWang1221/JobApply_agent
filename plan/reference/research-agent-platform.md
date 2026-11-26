# 调研：把 Agent 换成现代、可插件化的基底

时间：2026-11-25。用户提出：参考 `claude-context-panel.zip`，看能不能以 opencode 这类主流开源 Agent 为基底，功能用插件加（例如上下文压缩，见 [gap-context-compaction.md](gap-context-compaction.md)），并能导入外部 skill。

**这是参考材料，不是施工步骤。方向未定，等用户选。**

四份原始报告在 [agent-platform/](agent-platform/)：A 那个 zip、B 我们自己的 Agent、C opencode、D 其它主流 Agent 和 skill 标准。外部仓库的提交号写在 C、D 里（opencode 读的是 `anomalyco/opencode@ea8aa5b`，2.4.1）。

## 1. 那个 zip 是什么

- 它是 **OpenCode Desktop 的插件**（`@opencode/plugin@2.4.1`），不是 Claude Code 的。名字里的 Claude 指的是界面在模仿 Claude Code 的 Context 面板。
- 插件分 server 和界面两半，两边只靠一份有类型的 RPC 契约连接。
  - server 那半：在每次调模型前读完整请求（system 分段、消息、工具定义），读用量，触发宿主的压缩。
  - 界面那半：加面板、插槽、斜杠命令。
- **它自己不做压缩**，只把 token 按 9 类（工具结果、skill、system、用户消息……）拆开显示，压缩用的是宿主 opencode 自带的。
- 可以直接借的：按文字类别估 token 的算法（几十行）；"用服务商报的总量，按估算比例分到各类"的做法。

## 2. 我们的 Agent 现在卡在哪

- **循环本身不乱**：`AgentLoop` 275 行，结构清楚。
- **问题在循环之外**：
  - 投递、Session、后续三种 Agent 各写一套。停止、问用户、读 skill、限次这些逻辑各复制了三份；`HarnessLoop` 1805 行。
  - 工具只登记定义，真正执行靠每个循环里按工具名 switch。Spring AI 自带的工具执行和 Advisor 都没用。
  - **没有中间件层**：`LoopObserver` 只能看，不能改发给模型的消息，也拦不住工具调用。所以压缩、新工具、新 provider、子 Agent 都只能改核心类。
  - 模型调用不是流式的，前端只能按步看到进度。
- **外部 skill 导入卡在**：
  - 没有导入入口；
  - 读不到 `references/`、`assets/`，跑不了 `scripts/`；
  - 正文超过 8000 字会截断；
  - Session 只列 `research` 目录下的 skill；
  - `allowed-tools` 不生效。
- **必须留下的求职专有部分**：
  - 在用户自己 Chrome 上的浏览器层；
  - 提交守卫（必须留在浏览器工具内部，不能做成可关的插件）；
  - 敏感信息占位符和快照脱敏；
  - 申请状态、台账、记忆 / RAG、工作台界面；
  - skill 和提示词的内容。

## 3. 外面的现状

- **Skill 格式已经统一**：Agent Skills（SKILL.md）有 46 个客户端支持，包括 Claude Code、Codex、opencode、Gemini CLI、Goose、Hermes、Spring AI 等。"导入"实际上就是把目录、zip 或 git 仓库拷到约定的位置，没有统一的中央市场。
- **插件形态在趋同**：`plugin.json` + `skills/` + MCP + hooks，hook 事件名沿用 Claude Code 的叫法（PreToolUse / PostToolUse / PreCompact）。
- **opencode**：MIT 许可，TS + Bun。
  - 可以作为服务器被我们自己的界面驱动：`opencode serve` 提供 HTTP + SSE + OpenAPI。
  - 原生 SKILL.md，会扫描 `.claude/skills`，按需加载正文。
  - 自动压缩：剩余窗口 15% 时用结构化摘要压掉旧对话，保留最近约 15k token 原文。
  - 插件钩子很全：调模型前改请求、接管压缩、工具执行前后、权限判定。
  - **但它正处在 V1 → V2 的破坏性重写里**：V2 两个半月发了 51 个版本，OpenAPI 自己标着 Experimental，网上教程和社区插件大多还是 V1 写法。
  - 近期有服务端内存泄漏的 issue，单文件可执行约 240MB。
  - 同一目录下的多个会话共用一份 Playwright MCP 连接，我们并行的投递 Worker 会抢同一个 Chrome 标签页，要另做映射。
- **Claude Agent SDK**：只能用 Claude 模型，不是开源许可，每个会话起一个原生子进程。不适合当基底。
- **pi**（TS，MIT）：扩展接口最细，约 40 个事件，OpenMAIC 在用；API 变得快。
- **Hermes / Deep Agents**：都把压缩做成可替换的模块（Hermes 的 `ContextEngine`；Deep Agents 的 summarization middleware：85% 时总结、保留最后 10%、服务商报超长时强制压缩重试）。
- **留在 Java 这条路比预想的成熟**：
  - Spring AI 的 advisor 链本身就是中间件模型；
  - Spring AI 2.1 新增可插拔的会话压缩（只追加分界、不改原始日志，符合 `memory.md`「原文不压缩」）；
  - 社区的 spring-ai-agent-utils（Apache-2.0，孵化中）提供 Agent Skills、子 Agent、TodoWrite 的 Java 实现。
  - 我们现在是 Spring AI 2.0.1，用这些要先升 2.1。

## 4. 可选方向

| | A. Java 内重构，照 opencode 的插件形状 | B. opencode serve 做核心，Java 退成业务服务 | C. 换成 Node 上的 pi / Deep Agents JS |
| --- | --- | --- | --- |
| 做法 | 升 Spring AI 2.1；三套循环合成一个循环 + 中间件接口（调模型前改上下文、压缩、工具前后、提示词段落、工具注册），钩子名对齐 opencode / Claude Code；skill 按 Agent Skills 全规范导入 | React 前端直接连 opencode 的 API；JobApply 写成 opencode 的 agent 定义 + 插件（提交守卫、占位符换值）；Java 只把浏览器、台账、记忆暴露成 MCP 工具 | 循环搬到 Node（App 里已经带 node），Java 只做业务服务 |
| 马上得到 | 压缩、skill 导入、插件接口都要自己写，但都有现成的样板可抄 | 压缩、子 Agent、skill、订阅登录、用量事件全是现成的 | 最细的钩子 |
| 代价 | 第三方只能导入 skill 和 MCP，不能导入 JS 代码插件 | V2 不稳定；内存问题；App 增大约 240MB；Spring AI 循环和大半运行时作废；并行 Worker 抢标签页要另做；内置提示词是写代码用的，要覆盖 | 运行时、浏览器工具、守卫都要重写；两种语言之间要设计协议 |
| 风险 | 低，可以一步步改 | 高 | 中高 |

**我的倾向是 A**，理由：
- 我们的问题是没有中间件层、三套循环重复，不是 Java 本身做不到；
- 求职专有的部分（浏览器、守卫、脱敏）全在 Java 里；
- opencode V2 现在还在剧烈变动，内存问题又正好撞上 C029 刚压下去的那块；
- 照 opencode 的插件形状（`context` / `compaction` / `tool.execute.before/after`）来定义我们的接口，以后真要换到 opencode，接口也能对上。

**如果更看重"直接拿到主流生态"，选 B**：先锁定一个 V2 版本，只跑通"opencode serve + Playwright MCP + JobApply agent + 提交守卫插件"这一份申请，再决定要不要整体迁。

两种方向的详细计划草案（2026-11-25，等用户选）：[Java 重构](agent-platform/plan-A-java.md)、[opencode](agent-platform/plan-B-opencode.md)。

后续追问（2026-11-25）：[skill 脚本怎么执行](agent-platform/E-skill-scripts.md)、[opencode 界面插件能盖住多少](agent-platform/F-opencode-gui-coverage.md)、[全部改写成 TS 的工作量](agent-platform/G-ts-rewrite-sizing.md)、[pi 作基底](agent-platform/H-pi.md)。更正：第 3 节说「OpenMAIC 在用 pi」，实际 OpenMAIC 锁在 pi 0.78.0、用的是 1.0 已删掉的会话 / 压缩 API；pi 1.x 的 `pi-agent-core` 只剩循环库（见 H-pi.md）。

## 5. 没核实的

- `@opencode/sdk` 能不能在我们已带的 node 下完整运行（能的话 B 可以不带 240MB 二进制）。
- Gemini CLI 的 hook 清单、Codex 对非 OpenAI 模型的支持、OpenHands 的许可。
- opencode V2 的 `context` 钩子改写请求的边界（报告 A 只核实了只读的用法；报告 C 读源码确认能改出站请求）。
