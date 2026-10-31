# plan/

所有计划都写在本目录。`dev-plan.md` 只做索引，不重复细节。

## 两类计划（用户确认，2026-10-22）

| | Master Plan（`master/`） | Coding Plan（`coding/`） |
| --- | --- | --- |
| 核心问题 | 做什么、为什么、总体怎么设计 | 这一次具体实现什么 |
| 粒度 | 一个功能模块，或系统级设计 | 一次可以执行的开发任务 |
| 内容 | 需求、架构、接口契约、模块关系 | 具体文件、实现步骤、测试、验收标准 |
| 修改频率 | 相对稳定 | 随开发持续更新 |
| 对应关系 | 1 | N |

另有 `reference/`：调研和参考材料，**不是施工步骤**，也不是已经实现的事实。

## 约定

- **Master Plan** 至少写清：状态、需求或已确认决定、模块关系与接口契约、不做的事、待用户决定。改动 Master Plan 要经过用户确认。
- **Coding Plan** 命名为 `C<三位编号>-<短名>.md`，编号只增不减。一段拆成几份时用字母：`C004-A`、`C004-B`；插在某一份之后、属于同一段的追加工作用 `-<序号>`：`C004-A-1`（用户定的命名，2026-10-22）。文首写明：
  - 对应 Master：链接到一份或多份 Master Plan，以及其中的里程碑；
  - 状态：草案 / 待确认 / 进行中 / 完成 / 废弃。

  正文至少有：目标、不做、要改的文件、实现步骤、测试、验收标准；完成后补「实际结果」和「偏差」。
- Coding Plan 发现 Master Plan 有缺口时，先回到 Master Plan 补上，并请用户确认，不在 Coding Plan 里自行扩大范围。
- 未确认的选型标成后置，不写进代码。

## Master Plan

| 文件 | 内容 | 状态 |
| --- | --- | --- |
| [master/base.md](master/base.md) | 产品目标、技术路线、边界 | 已确认 |
| [master/architecture.md](master/architecture.md) | 逻辑目录、模块职责、领域对象 | 已确认 |
| [master/workflow.md](master/workflow.md) | 主流程、提交保护、流程优化边界、浏览器、登录、分层 Web 工具、匹配、额度、状态、预算与停止条件、里程碑、遗漏检查 | 已确认 |
| [master/memory.md](master/memory.md) | Project / Job / Session / Company，storage 结构，继承，新建 Project，资料导入与画像，字段分级，存储格式，Obsidian 兼容 | 已确认 |
| [master/tracking.md](master/tracking.md) | 阶段与轮次、unknown 规则、台账栏目（md + csv）、Dashboard、面试复盘与题库 | 已确认 |
| [master/ui.md](master/ui.md) | 界面结构、候选池、模型设置、密钥存放、首次使用引导、隐私说明 | 已确认 |

## Coding Plan

`coding/` 按开发阶段分四个子文件夹（2026-10-31 整理）；每份 C 计划的 `-fix`、拆分段和附带脚本与它放在同一个文件夹：

| 文件夹 | 阶段 | 编号 |
| --- | --- | --- |
| `coding/01-foundation/` | 起步：开发流程、脚手架、Memory 与台账、Playwright MCP | C000–C003 |
| `coding/02-core-agent/` | 核心 Agent：模型与资料导入、申请 Agent 循环、调研 Agent、应用界面、运行时修正（含作废的 C004-B / C004-C 历史稿） | C004–C006 |
| `coding/03-features/` | 功能扩展：投递后、简历匹配、插话、并发 Worker、删除、Move forward、README、下拉框、macOS App、工具上限、申请状态 | C007–C019 |
| `coding/04-refinement/` | 整体打磨：整体精炼、工作台闭环与侧栏、后续与经验检索、界面打磨 | C020– |

新的 Coding Plan 放进当前阶段的文件夹；文件名一律 `C###-<做了什么>.md`。

| 文件 | 对应 Master | 状态 |
| --- | --- | --- |
| [coding/01-foundation/C000-dev-process.md](coding/01-foundation/C000-dev-process.md) | 流程本身 | 完成 |
| [coding/01-foundation/C001-scaffold.md](coding/01-foundation/C001-scaffold.md) | base、architecture | 完成 |
| [coding/01-foundation/C002-memory-ledger.md](coding/01-foundation/C002-memory-ledger.md) | workflow 里程碑 1、memory、tracking | 完成 |
| [coding/01-foundation/C003-playwright-mcp.md](coding/01-foundation/C003-playwright-mcp.md) | workflow 里程碑 2、memory | 完成 |
| [coding/02-core-agent/C004-harness-guard.md](coding/02-core-agent/C004-harness-guard.md) | workflow 里程碑 3（总表） | 已拆分 |
| [coding/02-core-agent/C004-A-model-guard.md](coding/02-core-agent/C004-A-model-guard.md) | workflow 里程碑 3、ui、memory | 完成 |
| [coding/02-core-agent/C004-A-1-profile-import.md](coding/02-core-agent/C004-A-1-profile-import.md) | memory「资料导入与画像」、workflow 里程碑 3 的前置 | 施工完成，等手动检查 |
| [coding/02-core-agent/C004-A-2-profile-review.md](coding/02-core-agent/C004-A-2-profile-review.md) | memory「资料导入与画像」、workflow 里程碑 3 的前置 | 施工完成，等手动检查 |
| [coding/02-core-agent/C004-B-fixed-graph-harness.md](coding/02-core-agent/C004-B-fixed-graph-harness.md) | workflow 里程碑 3、memory | 已确认，待施工（在 C004-A-2 之后） |
| [coding/04-refinement/C020-holistic-refinement.md](coding/04-refinement/C020-holistic-refinement.md) | agent-runtime、workflow、memory | 已施工，等用户试（JDK 25 `mvn -B clean verify`、无头 Chromium 5 项 DOM 验证、`npm run build` 全部通过，2026-10-28） |
| [coding/04-refinement/C021-workspace-flow-and-sidebar.md](coding/04-refinement/C021-workspace-flow-and-sidebar.md) | agent-runtime、workflow、memory、ui | 已施工，等用户在本机试（A/B/C 三包合龙：JDK 25 `mvn -B clean verify` 660+1 全通过、`npm run build` 通过、`WorkspaceSummary` 契约核对一致，2026-10-28） |

## 参考（`reference/`）

| 文件 | 内容 |
| --- | --- |
| [reference/research-codex.md](reference/research-codex.md) | 旧 Codex + 浏览器 MCP 投递工作区复盘（已抽象，无个人信息） |
| [reference/research-hermes.md](reference/research-hermes.md) | Hermes Agent 的搜索、浏览器、审批、memory、skills、MCP 设计参考 |
