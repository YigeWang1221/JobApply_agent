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
- **Coding Plan** 命名为 `C<三位编号>-<短名>.md`，编号只增不减。文首写明：
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
| [master/memory.md](master/memory.md) | Project / Job / Session / Company，storage 结构，继承，新建 Project，字段分级，存储格式，Obsidian 兼容 | 已确认 |
| [master/tracking.md](master/tracking.md) | 阶段与轮次、unknown 规则、台账栏目（md + csv）、Dashboard、面试复盘与题库 | 已确认 |
| [master/ui.md](master/ui.md) | 界面结构、候选池、模型设置、密钥存放、首次使用引导、隐私说明 | 已确认 |

## Coding Plan

| 文件 | 对应 Master | 状态 |
| --- | --- | --- |
| [coding/C000-process.md](coding/C000-process.md) | 流程本身 | 完成 |
| [coding/C001-scaffold.md](coding/C001-scaffold.md) | base、architecture | 完成 |

下一份是 `C002`，对应 workflow「里程碑」第 1 段（Memory 与台账），尚未起草。

## 参考（`reference/`）

| 文件 | 内容 |
| --- | --- |
| [reference/research-codex.md](reference/research-codex.md) | 旧 Codex + 浏览器 MCP 投递工作区复盘（已抽象，无个人信息） |
| [reference/research-hermes.md](reference/research-hermes.md) | Hermes Agent 的搜索、浏览器、审批、memory、skills、MCP 设计参考 |
