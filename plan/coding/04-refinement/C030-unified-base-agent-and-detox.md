# C030 · 统一 Base Agent 运行时与安全排毒（Phase 1）

- 对应 Master：[agent-runtime.md](../../master/agent-runtime.md)、[architecture.md](../../master/architecture.md)、[workflow.md](../../master/workflow.md)、[memory.md](../../master/memory.md)、[tracking.md](../../master/tracking.md)、[ui.md](../../master/ui.md)
- 状态：**Slice 0–6 已施工（2026-11-25，多 Agent），等用户在本机用真实 Chrome + 真实模型试**
- 日期：2026-11-25
- 参考材料（非施工依据）：[research-agent-platform.md](../../reference/research-agent-platform.md)、[plan-A-java.md](../../reference/agent-platform/plan-A-java.md)

---

## 目标

一次性完成方案 A 升级版架构收束：

1. **Slice 0（本步施工）**：清理代码与文档中的过度安全设计（软边界审批流、死代码）及非核心防御性负向测试样本，使测试与边界回归用户真正定下的两条底线。
2. **Slice 1–6（直接在用户当前所在分支按序推进，不写死、不擅自切换分支）**：按 6 个有序、可验证的垂直切片一次性落地「**1 个 Base Agent + Plugin / Skill / Mode + Depth=2/3 单向 Subagent 树 + 统一 Job 线程 + `JobStatus`/侧栏 IA 瘦身**」：
   - 将当前分散的 `AgentLoop`、`HarnessLoop`、`SessionLoop`、`FollowupLoop` 收敛为单一循环核 + `PluginChain` + 统一 `ToolRegistry` + 外置 `Mode`；
   - 将 Job 投递段与后续段收拢到统一的 `JobThreadService` / `JobThreadStore`；
   - 引入 Depth=2/3 单向 `SubagentExecutor`，落地 `web-research`、`apply` + `reflect`、`portal-check`、`resume-match` / `interview-prep` 四条业务流；
   - 保持 **硬提交守卫**（硬提交词拦截 + 文本框回车/空格提交拦截）与 **C 级 `{KEY}` 底线**（`{KEY}` 占位符仅在浏览器填写前替换 + `SnapshotRedactor` / `FieldClassGuard` 脱敏）100% 完整，并保证已有 **`storage/` 100% 向后兼容**。

---

## 不做

- 不引入 `opencode` / `pi` 双进程或外部运行时服务器；
- 不将后端运行时重写为 TypeScript / Node；
- 不削弱硬提交守卫（`SubmitGuard` / `SubmitWords` 硬边界与回车/空格提交拦截）与 C 级 `{KEY}` 占位符 / `SnapshotRedactor` / `FieldClassGuard` 底线；
- 不主动新增安全校验或防御性负向测试；
- 以下能力列为后续演进，**不在 C030 Phase 1 内强塞**：
  - **Phase 2**：用量面板（Token/成本分项展示）、上下文自动压缩（compact）、外部 Skill 导入 UI 与脚本执行、模型流式输出（`delta`）、按 Mode 的模型分层路由；
  - **Phase 3**：SQLite FTS5 替代本地 ONNX 嵌入 RAG、薄层 Node Playwright Bridge 精简浏览器调用层。

---

## Slice 0 清单（本步施工）

### 1. 生产代码去软边界与死代码

- `GuardDecision`（`backend/src/main/java/jobapply/harness/policies/GuardDecision.java`）：删除 `AskUser`、`Explain`，保留 `Allow`、`Reject`、`Handoff`。
- `SubmitGuard`（`backend/src/main/java/jobapply/harness/policies/SubmitGuard.java`）：删除 `Approval`、`signal` 及软边界审批分支，保留硬提交词按钮拦截与含硬提交词对话框中的确认按钮拦截。
- `SubmitWords`（`backend/src/main/java/jobapply/harness/policies/SubmitWords.java`）：删除 `isSoft`、`isNext` 及软边界词表判断逻辑，保留硬提交词表与基础识别。
- `HarnessLoop`（`backend/src/main/java/jobapply/harness/runtime/HarnessLoop.java`）：删除 `pendingApproval` / `approval` 软边界审批状态与对应调用分支。
- `SiteNotes`（`backend/src/main/java/jobapply/persistence/SiteNotes.java`）：删除已无用途的死字段与逻辑 `confirmedButtons`。

### 2. 测试瘦身

- 删除 `SecretFileRulesTest`；
- 删除反射类扫描、响应体敏感词扫描类测试；
- 删除 `Origin` 403、Posix 文件权限检查、CSV 注入、路径穿越等防御性负向测试样本；
- 保留核心保护测试：硬提交守卫拦截（`SubmitGuardTest` 等硬边界核心用例）与 C 级 `{KEY}` 占位符替换、`FieldClassGuard`、`SnapshotRedactor` 脱敏核心测试。

### 3. 文档排毒与总设计对齐

- 更新 `dev-limit.md`、`AGENTS.md` 及 `plan/master/*.md`（`agent-runtime.md`、`architecture.md`、`workflow.md`、`memory.md`、`tracking.md`、`ui.md`），清理过时的软边界审批与过度防御表述，并与统一 Base Agent + Plugin / Skill / Mode + Depth=2/3 Subagent 树总设计对齐。

---

## 分情况 Subagent 数据流与回传矩阵

Subagent 采用 **Depth=2/3 单向树**（仅父向子单向委托，不可反向递归）。必须明确区分两类数据通路：
1. **写共享台账 / 产物 + SSE 直推 UI**（适用于需要实时呈现或跨任务沉淀的状态）；
2. **向上级 Agent 回传精简包**（仅回传结构化结论与简要摘要，不把大量原始网页快照、长篇 JD 或全量日志塞回上级对话上下文）。

| Case | Subagent / Mode | 最大深度 | 输入读取边界 | 副作用 / 共享写入 & SSE 直推 | 向上级 Agent 回传内容 |
| --- | --- | --- | --- | --- | --- |
| **Case A1** | `web-research`（只读检索 / 核实） | Depth 1 | 检索词、目标 URL、Project 方向摘要 | 只读网页搜索与读取；**不写** `JobLedger` | 精简结论包：核实结果、公司/岗位要点、来源 URL |
| **Case A2** | `web-research`（批量找岗入池） | Depth 1 | 检索要求、Project 偏好、既有候选摘要 | 直接持有绑定 `projectId + rootSessionId` 的 `add_lead` 工具，**当场写 `JobLedger` 并通过 SSE 实时冒入右栏与候选池** | 精简汇总包：新增入池数量、去重/跳过统计、关键岗位摘要 |
| **Case B1** | `apply`（投递填表） | Depth 1（或 Job 线程主运行） | Project 解析后资料（C 级为 `{KEY}`）、上传清单、`job.md`、站点/公司记忆与通用教训 | 写 `ApplicationState`、`fill-report.md`、`run-log.jsonl`、推进 `JobLedger` 状态；停下/完成时写父 Session 状态通知并推 SSE | 精简执行包：结束状态（待用户 / 停在提交前 / 失败）、`fill-report` 摘要、未决项 |
| **Case B2** | `reflect`（投递后复盘） | Depth 2 | **仅读脱敏后的** `run-log.jsonl`、`fill-report.md`、`ApplicationState.notes`（禁读未脱敏原值与 `personal.env`） | 检测到摩擦信号（重复失败、控件问题、站点/公司新规律）时，**静默更新** `playbook/sites/`、`companies/`、`review/lessons.md`、`playbook/skills/`；无摩擦时静默退出 | 仅回传精简修改注记：改动了哪些文件、沉淀了哪几条规则（无改动时为空/简短确认） |
| **Case C** | `portal-check`（官网状态巡检） | Depth 1 | 岗位门户 URL、官网当前状态页（只读用户 Chrome 登录态） | **不污染工作台主对话流**；通过 `record_portal_check` 写入待核准建议；需要手动登录或未找到状态页的岗位整批保留，批末统一由 `openForUser` 在用户 Chrome 中打开（每站一页、已开着不再开） | 精简巡检包：已查出建议数、需手动登录数、未找到状态页数 |
| **Case D** | `resume-match` / `interview-prep` | Depth 1–2 | 岗位 JD、对应 Project 简历与事实、`search_experience` 检索片段 | `resume-match` 按职责输出匹配评分与修改建议；`interview-prep` 按需沉淀轮次复盘、题库与准备提纲到 `rounds/` / `review/` | 精简结论包：匹配排名/关键差距建议，或面试准备要点与题库关联摘要 |

---

## 3 条 `storage/` 兼容性防线

为确保已有 `storage/` 工作区零迁移成本且不损坏历史数据，Slice 1–6 强制守住以下三条兼容性防线：

1. **`job.md` `phase` 为 Mode 唯一权威源 + 双附件路径 fallback**
   - 统一 Job 线程根据 `job.md` frontmatter 的 `phase` 自动决定运行 Mode（`prep` → `apply` Mode；`applied` 及之后阶段 → `followup` Mode），不依赖额外的冗余状态文件或前端硬传分支；
   - 对话与上传附件读取同时兼容既有投递段路径与历史 `followup/` 附件目录，双路径 fallback 保证存量 Job 附件可直接加载与续接。
2. **`PrepState.fromStorage()` 兼容映射 + `Outcome.NOT_SUBMITTED` + `Job.extra` 透传**
   - `PrepState.fromStorage()` 对旧版 `storage/` 中的细粒度子状态做向后兼容映射，防止反序列化旧 `job.md` 报错；
   - 保留 `Outcome.NOT_SUBMITTED` 枚举以兼容 C024 之前的历史关闭记录；
   - `Job.extra` 原样保留并透传 `job.md` frontmatter 中未显式建模的未知字段，读写往返不丢字段。
3. **浏览器 Subagent 独立 `sub:` `TabOwner` + `finally CLOSE_ALL` + `subRunId` 隔离与 `subruns/<subId>.json`**
   - 任何使用浏览器的 Subagent 必须以 `sub:<subRunId>` 作为独立 `TabOwner`，与主工作台及用户自己的标签页隔离；
   - Subagent 退出时在 `finally` 中强制执行 `CLOSE_ALL`，关闭自身打开的所有标签页（`portal-check` 交还用户登录的页面除外，按批末 `openForUser` 统一打开为归属用户的标签页）；
   - Subagent 的运行对话、步骤与结果按 `subRunId` 独立持久化在 `subruns/<subId>.json`，不写入父 Session `transcript.jsonl` 或工作台主对话流。

---

## Slice 1–6 实施计划（在用户当前分支按序推进）

### Slice 1：领域状态瘦身 + 兼容映射

- **范围**：
  - 梳理并收敛 `JobStatus`、运行态与生命周期状态，消除冗余与展示耦合字段；
  - 实现 `PrepState.fromStorage()` 兼容映射、`Outcome.NOT_SUBMITTED` 历史兼容以及 `Job.extra` 未知 frontmatter 字段透传。
- **涉及文件 / 模块**：
  - `backend/src/main/java/jobapply/domain/`（`Job.java`、`JobStatus.java`、`PrepState.java`、`Outcome.java`、`ApplicationState.java` 等）
  - `backend/src/main/java/jobapply/persistence/`（`JobRepository.java`、`JobLedger.java`、`TrackerWriter.java` 等）
- **验收方式**：
  - 旧格式 `job.md` 与历史 `storage/` 夹具读回、更新并写回后，未知字段不丢失，旧枚举值正常映射；
  - `backend/` 下 `mvn -B clean verify` 通过。

### Slice 2：统一 `ToolRegistry`

- **范围**：
  - 废除各 Loop 内按工具名 `switch` 分派与 `*DefinitionOnly` 空壳定义，建立统一的 `ToolRegistry`（工具定义 + 执行器 + 运行上下文注入 + 按 Mode 过滤）；
  - 将 `SnapshotRedactor` 脱敏边界整合到浏览器工具产出快照处，确保提交守卫（`SubmitGuard` / `SubmitWords` 硬边界与回车/空格提交拦截）、C 级 `{KEY}` 占位符实时替换与脱敏内嵌在浏览器工具执行器内部，任何插件与上层均接触不到 C 级原值。
- **涉及文件 / 模块**：
  - `backend/src/main/java/jobapply/tools/`（`BrowserTools.java`、`ControlTools.java`、`WorkbenchTools.java`、`ResearchTools.java`、`FollowupTools.java`、`ExperienceTools.java` 等）
  - `backend/src/main/java/jobapply/harness/policies/`（`SubmitGuard.java`、`FieldClassGuard.java`、`SnapshotRedactor.java`、`BrowserToolPolicy.java`）
  - `backend/src/main/java/jobapply/harness/runtime/`（移除 `ToolArgs` 中的分支胶水与重复分发）
- **验收方式**：
  - 不同 Mode 从 `ToolRegistry` 获取各自授权工具集并正确执行；
  - 硬提交拦截与 `{KEY}` 替换/快照脱敏核心测试通过；
  - `mvn -B clean verify` 通过。

### Slice 3：唯一循环核 + `PluginChain` + `Mode` 外置

- **范围**：
  - 将 `AgentLoop`、`HarnessLoop`、`SessionLoop`、`FollowupLoop` 合并为唯一核心循环（Base Agent 循环引擎）+ `PluginChain`（`prompt`、`tools`、`context`、`response`、`toolBefore`、`toolAfter`、`on(event)`）；
  - 将原硬编码在 `*Agent.java` / `*Loop.java` 中的提示词、上下文提供者、Skill 范围、插件组合与循环参数外置为 `Mode` 定义与 classpath Markdown；
  - 将 `ApplicationState` 注入与自动记录、页面快照省略（最新整页 + 弹出层保留基础页）、打转提醒（仅 `toolAfter` 追加提示）抽取为标准插件。
- **涉及文件 / 模块**：
  - `backend/src/main/java/jobapply/harness/runtime/`（唯一循环核、`PluginChain`、内置插件）
  - `backend/src/main/java/jobapply/harness/context/`（上下文拼装与插件化改造）
  - `backend/src/main/java/jobapply/agents/`、`backend/src/main/resources/agents/`（外置 `Mode` 规范与分段提示词）
- **验收方式**：
  - 单一循环核可按 `Mode` 驱动调研、投递、后续三类主场景，支持 `ask_user`、`finish`、运行中 `Inbox` 插话（steer）、停止与调用上限宽限总结；
  - 夹具场景测试与 `mvn -B clean verify` 通过。

### Slice 4：统一 Job 线程 `JobThreadService` / `JobThreadStore` + 修复 `AgentRunStore` 单例锁

- **范围**：
  - 以 `JobThreadService` 与 `JobThreadStore` 统一管理一个 Job 贯穿投递与后续的运行线程，严格以 `job.md` `phase` 作为 Mode 切换的唯一权威源，支持双附件路径 fallback；
  - 修复 `AgentRunStore` 单例锁并发争用（细化锁粒度或对齐 `StorageLock`），避免并发 Worker 读写相互阻塞或状态覆盖；
  - 统一运行控制（Worker 池、排队、停止、收件箱、事件总线 → `RuntimeEvents` SSE）。
- **涉及文件 / 模块**：
  - `backend/src/main/java/jobapply/workflow/`（`ApplicationRunner`、`FollowupRunner`、`SessionRunner` 收敛与 `JobThreadService` 落地）
  - `backend/src/main/java/jobapply/persistence/AgentRunStore.java`、`FollowupRepository.java`、`StorageLock.java`
- **验收方式**：
  - 同一 Job 从 `prep`（`apply` Mode）到 `applied`（自动切换 `followup` Mode）续接顺畅，旧 `runs/` 与 `followup/` 记录正常读取；
  - 多 Worker 并发读写下无锁死与覆盖问题；
  - `mvn -B clean verify` 通过。

### Slice 5：Depth=2/3 `SubagentExecutor` + 四条业务流

- **范围**：
  - 实现 Depth=2/3 单向 `SubagentExecutor`（记录 `depth`、`parentRunId`、`subRunId` 并持久化到 `subruns/<subId>.json`，禁止反向递归）；
  - 为需要浏览器的 Subagent 接入 `sub:<subRunId>` `TabOwner` 隔离与 `finally CLOSE_ALL` 清理防线；
  - 落地四条完整业务流：
    - Case A1/A2 `web-research`（A2 直接持绑定 `projectId + rootSessionId` 的 `add_lead` 当场写 `JobLedger` 并通过 SSE 实时推送 UI）；
    - Case B1/B2 `apply` + Depth-2 `reflect`（仅读脱敏 `run-log.jsonl`、`fill-report.md`、`ApplicationState.notes`，有摩擦时静默更新经验文件并回传精简修改注记）；
    - Case C `portal-check`（不污染工作台主对话，整批保留统一 `openForUser`）；
    - Case D `resume-match` / `interview-prep`。
- **涉及文件 / 模块**：
  - `backend/src/main/java/jobapply/agents/`、`backend/src/main/java/jobapply/harness/runtime/`
  - `backend/src/main/java/jobapply/tools/BrowserSession.java`、`OwnerBrowser.java`
  - `backend/src/main/java/jobapply/persistence/`（`subruns/<subId>.json` 存储、经验写入集成）
- **验收方式**：
  - Case A2 入池实时推送前端 SSE；Case B2 隔离读取脱敏文件并沉淀经验；Case C 整批 `openForUser` 不污染工作台对话；Subagent 异常与正常退出均在 `finally` 关闭自建标签页；
  - `mvn -B clean verify` 通过。

### Slice 6：前后端 API & UI/IA 对齐 + 真机闭环

- **范围**：
  - 前后端 API 契约与 `JobStatus` / 侧栏 IA（信息架构）瘦身对齐（侧栏导航、工作台状态展示、Dashboard 筛选与核准、Subagent 过程折叠/状态对齐）；
  - 真实 Chrome + 真实 LLM 端到端闭环验证：调研找岗（A2 实时入池）→ 工作台选简历填表（B1）→ 停在最终提交前 → `reflect`（B2）静默沉淀 → 投递后门户巡检（C）/ 面试准备（D）。
- **涉及文件 / 模块**：
  - `backend/src/main/java/jobapply/api/`
  - `frontend/src/`
- **验收方式**：
  - `frontend/` 下 `npm run build` 退出码 0；
  - `backend/` 下 `mvn -B clean verify` 退出码 0；
  - 用户监控下真实 Chrome 端到端闭环试跑通过。

---

## Slice 0 实际结果与验证

- **实际结果（2026-11-25，Slice 0）**：
  - 生产代码（9 个文件）：`SiteNotes` 清理 `Verdict`/`ButtonConfirmation`/`CONFIRMED_BUTTONS`/`confirmedSafe()`/`recordButton()` 并将 `Site` 收敛为 `(host, platform, notes)`（兼容读取旧 `confirmed_buttons`）；`MemoryNotes.site` 不再写 `confirmed_buttons`；`GuardDecision` 删除 `AskUser`/`Explain`；`SubmitWords` 删除 `NEXT_*`/`SAVE_*`/`isSoft`/`isNext`；`SubmitGuard` 删除 `STEPS`/`Approval`/`signal()` 及软边界 4 参 `click`，`click(latest, ref)` 直接委托 `readOnly`；`HarnessLoop` 删除 `pendingApproval`/`approval` 及软边界分支；`BrowserTools`、`ApplyAgent`、`PlaywrightMcpClient` 去掉 `final_step_suspected` 提示。硬提交词拦截、Enter/空格提交拦截、禁止任意 JS 开放、C 级 `{KEY}` 占位符与 `SnapshotRedactor`/`FieldClassGuard` 全部保留。
  - 测试代码：删除 `SecretFileRulesTest`（1 个文件）；精简并清理 `SubmitGuardTest`、`BrowserToolPolicyTest`（23 → 6 个核心用例）、`RunLogTest`、`SiteNotesTest`、`ModelSettingsControllerTest`、`AssetControllerTest`、`SourceControllerTest`、`EnvFileTest`、`TrackerWriterTest`、`AssetRegistryTest`、`ChatGptTokenStoreTest`、`LocalDirectoryScannerTest`、`PlaywrightMcpClientTest`、`BrowserToolsTest`、`BrowserSessionActTest`、`DirectoryBrowserTest`、`GitHubPublicReaderTest`、`FakeFormScriptTest` 中的反射、响应体全扫描、`Origin` 403、Posix 权限、CSV 注入、路径穿越与死软边界测试；保留硬提交守卫及 `FieldClassGuardTest`、`SnapshotRedactorTest`、`PersonalPlaceholderTest`。
  - 文档与计划：`dev-limit.md` 删去「未写入放宽一律禁止」及过时段落并收束为 4 类真底线；`AGENTS.md` 与 `.cursor/rules/dev-process.mdc` 增加「不主动新增防御守卫、输入净化层或安全/负向测试」原则；将 `agent-runtime.md` 的 2026-10-23 迁移记录归档至 `plan/reference/archive-agent-runtime-2026-10-23.md`；同步更新 `plan/master/*.md`（含 C030 单 Base Agent + Plugin/Skill/Mode + Depth=2/3 Subagent 树设计）、`plan/README.md`、`dev-plan.md`、`README.md` 与本计划 `C030`。
- **验证**：
  - `backend/` 下在 JDK 25（`/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`）执行 `mvn -B -f backend/pom.xml clean verify`：退出码 `0`，`BUILD SUCCESS`（耗时 `01:13 min`）；surefire `Tests run: 566, Failures: 0, Errors: 0, Skipped: 0`（瘦身前 668）；failsafe `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`（`MemoryLedgerScenarioIT`）。
  - 文档内链检查 `BROKEN: 0`；`git status` 确认 `storage/` 无任何变更。
  - 前端 `npm run build`、真实 Chrome 端到端试跑：未运行（Slice 0 未改前端与浏览器运行链路；Slice 1–6 待用户确认后实施）。
  - 测试数更正（2026-11-25 审查核对）：上面写的 surefire 566 与 HEAD 不符，按源码静态计数 HEAD 约 546。

---

## Slice 1–6 施工记录（2026-11-25，多 Agent）

施工方式：主会话协调，子 Agent 施工。Slice 1（独立 worktree）与 Slice 2+3 并行，其后 4 → 5 → 6 顺序，一轮独立审查，审查的小问题一次修完。均未提交。

### Slice 1：领域状态
- `PrepState` 收成 5 个：`preparing`、`needs_input`、`awaiting_review`、`paused`、`failed`。`PrepState.fromStorage()` 映射旧值：`selected`→`preparing`；`needs_login`、`handoff_user`→`needs_input`；`user_submitting`、`verification_pending`、`unconfirmed`→`awaiting_review`。下次更新时写成新值。
- 状态机：Convert 从 `preparing` 开始；MarkApplied 任何 prep 状态都可（只看证据）；`JobLedger.markSubmitted` 直接到 applied。`JobBucket.WAITING` = `needs_input` 或 `failed`。
- `Outcome.NOT_SUBMITTED` 保留；`Job.extra` 原本已能往返，未改。
- `Phase.applySegment` / `JobStatus.applySegment()`：无 phase 或 prep → apply Mode，applied 及之后 → followup Mode。
- 夹具测试 `JobLedgerTest.oldFormatJobFilesLoadAndRoundTrip`（旧 prep 值、NOT_SUBMITTED、未知字段）。

### Slice 2+3：ToolRegistry、唯一循环核、PluginChain、Mode
- 删除 `AgentLoop`、`HarnessLoop`、`SessionLoop`、`FollowupLoop`、`ReadOnlyTab`、`ToolArgs`、`ApplyAgent`、`ResearchAgent`、`FollowupAgent` 和 5 个 `DefinitionOnly`。
- `harness/runtime/AgentLoopEngine`（唯一循环）+ `Loop`、`LoopEvent`、`LoopEnd`、`RunControl`、`ToolRegistry`、`ToolBox`。
- `harness/plugins/`：`Plugin`（`prompt`、`tools`、`context`、`response`、`toolBefore`、`toolAfter`、`on`）、`PluginChain`；标准插件 `ApplicationStatePlugin`、`PageElisionPlugin`、`LoopReminderPlugin`、`JobPagePlugin`、`ConversationEndingPlugin`、`OldResultsPlugin`、`ResearchContextPlugin`、`FollowupContextPlugin`、`LimitNotePlugin`。
- `tools/`：`Tool`、`ToolInput`、`ToolOutcome`、`RunContext`、`AgentServices`、`Workbench`；工具组 `BrowserFillTools`、`BrowserReadTools`、`ControlTools`、`ResearchTools`、`FollowupTools`、`WorkbenchTools`、`ExperienceTools`。
- `agents/BaseAgent`、`agents/Modes`；`resources/modes/{session,apply,followup}.md`（frontmatter：工具组、`allow-tools`、插件、`subagents`、上限、skill 范围；正文为提示词，与旧类逐字一致）。
- 硬提交守卫、`FieldClassGuard`、`{KEY}` 替换（`withRealValues`）、`SnapshotRedactor` 全在 `BrowserFillTools` 内；回车 / 空格 / `submit:true` 拦截仍在 `BrowserToolPolicy`。

### Slice 4：统一 Job 线程
- `workflow/JobThreadService`（原 `ApplicationRunner`，并入 `FollowupRunner` 的单 Job 部分）：一个 Worker 池、一张运行表、每个 Job 最多一个在跑的运行。`reply()` 是用户消息唯一入口，在跑时进 Inbox（steer）。Mode 只看 `job.md` phase，`/application/reply` 与 `/followup/messages` 走同一路。
- `persistence/JobThreadStore`（取代 `AgentRunStore`、`FollowupRepository`）：路径不变（`runs/<job>.json`、`jobs/<id>/followup/`），每个运行文件一把锁（静态 map），JSON 解析在锁外。
- 附件双路径：当前段目录为主、另一段为后备。
- `workflow/StatusCheckBatch`（原 `FollowupRunner`）只做批量查状态协调，批末 `openForUser` 照旧。

### Slice 5：Subagent
- `agents/SubagentExecutor`：Session 深度 0，Job 线程深度 1，子运行深度 2（`MAX_DEPTH = 2`，深度 2 没有 `delegate`）。父 Mode 只能派给自己 `subagents:` 列出的子 Mode。子运行 TabOwner `sub:<subRunId>`，`finally` 里 `browser.release(owner)` 关自己的页；继承父的停止和调用上限，不继承用户 Inbox。
- 记录在 `projects/<project-id>/subruns/<subId>.json`（`domain/SubRun`、`persistence/SubRunStore`），不进父对话。
- `delegate {mode, task, job?}`，父只拿回「子任务 <mode>（<subId>）做完了/停下了/出错了：<结果>」，截到 3000 字。
- 子 Mode：`web-research`（A1/A2，`add_lead` 绑定 Project + 根 Session，当场写台账）、`reflect`（`read_apply_record` 读脱敏记录，经 `remember` 沉淀；何时派由模型按提示词判断）、`portal-check`（查状态改为子运行，不进后续对话）、`resume-match`、`interview-prep`。

### Slice 6：API 与界面
- `api/SubRunController`：`GET /api/sessions/{id}/subruns`、`GET /api/jobs/{id}/subruns`、`GET /api/projects/{project}/subruns/{subId}`。SSE `subrun` 事件带子运行内容（不含步骤）。
- 工作台能停自己那次查状态（已有的 `POST /api/status-checks/stop {jobIds:[id]}`）。
- 前端：5 个准备状态标签（`needs_input` 显示「等你处理」）；`chat/SubRuns.tsx` 子任务折叠卡；工作台查状态栏下显示最近一次 portal-check；Project 页并入 Dashboard（删除 `pages/ProjectPage.tsx`，旧 `#/project/<id>` 链接跳到 Dashboard）。

### 审查（一轮）与修正
- 结论：两条底线完整，旧 `storage/` 能读，无死锁或丢更新。
- 修正的小问题：单个查状态在后台线程失败时只记日志（改为同步报错 / 显示在查状态状态里）；`SubRunStore.list` 每次全量解析带步骤的文件；Session 里 `delegate` 漏了 `job` 时直接开跑然后出错（改为立即返回工具错误）。修正结果见 `dev-log.md`。
- 有意保留：旧的 `user_submitting` / `verification_pending` / `unconfirmed` Job 映射成「等你审阅」后可以重新开始，需要用户再点一次「我已经提交了」。

### 与 Master Plan 的出入（待用户确认是否回写）
- Master 写 Depth=2/3；实现为最深 2 层（Session 0 → Job 线程 1 → 子运行 2）。
- Master 写统一 `thread.jsonl`；实现沿用原来的 `runs/<job>.json` 和 `followup/` 路径，不迁移。
- Master 写 `SessionService`、`SegmentBoundary`、`SubagentExecutor` 在 `workflow/`；实现保留 `SessionRunner`，Mode 切换直接用 `applySegment`，`SubagentExecutor` 在 `agents/`。

### 已知未做
- 有查状态在跑时侧栏工作台没有提示点；同时选中「Dashboard」和某个 Project 时两行都高亮；深度 2 的子运行只在父卡片里显示。
- 真实 Chrome + 真实模型端到端：未运行（等用户）。
