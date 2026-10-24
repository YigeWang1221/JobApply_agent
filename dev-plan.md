# 开发计划

本文件只做**索引**：现在做什么、细节在 `plan/` 的哪一份。不写版本表、目录树、模块规格。那些一律在对应 plan 文件里。

施工约定见 `AGENTS.md`。边界见 `dev-limit.md`。计划分类（Master Plan / Coding Plan）见 [plan/README.md](plan/README.md)。

## 现在做什么

产品：**JobApply**（求职投递 Agent），放在本仓库。Spring Boot 在 `backend/`。按本地个人资料找岗位，用 Playwright MCP 在本机 Chrome 里代填申请，提交前停下由用户审阅并亲自提交，本地 md + csv 台账跟踪进度。业务行为尚未开始。

## Master Plan（`plan/master/`，已全部确认）

| 内容 | 文件 |
| --- | --- |
| 产品目标、技术路线、边界 | [plan/master/base.md](plan/master/base.md) |
| 逻辑目录与模块职责 | [plan/master/architecture.md](plan/master/architecture.md) |
| 主流程与里程碑 | [plan/master/workflow.md](plan/master/workflow.md) |
| Memory 与 storage 结构 | [plan/master/memory.md](plan/master/memory.md) |
| 追踪、Dashboard、复盘 | [plan/master/tracking.md](plan/master/tracking.md) |
| 界面与模型设置 | [plan/master/ui.md](plan/master/ui.md) |
| Agent 运行时（循环、工具、提示词、什么时候问用户） | [plan/master/agent-runtime.md](plan/master/agent-runtime.md) |

## Coding Plan（`plan/coding/`）

| 编号 | 内容 | 状态 |
| --- | --- | --- |
| [C000](plan/coding/C000-process.md) | 安装开发流程 | 完成 |
| [C001](plan/coding/C001-scaffold.md) | 本地单模块脚手架 | 完成 |
| [C002](plan/coding/C002-memory-ledger.md) | workflow 里程碑第 1 段：Memory 与台账 | 完成 |
| [C003](plan/coding/C003-playwright-mcp.md) | workflow 里程碑第 2 段：Playwright MCP 接通 | 完成 |
| C004 | 里程碑第 3 段：模型接入、资料导入、申请 Agent。**已完成，不再开发。** 施工记录：[模型与资料](plan/coding/C004-A-model-guard.md)、[申请 Agent](plan/coding/C004-D-agent-loop.md)；B、C 是被推翻的旧做法，只留作历史 | 完成 |
| [C005 调研 Agent](plan/coding/C005-research-agent.md) | 里程碑第 4 段：Session 对话 Agent，搜索、读 JD、候选池、开始申请 | 完成（用户真实冒烟通过，2026-10-23） |
| [C005 界面](plan/coding/C005-ui.md) | 按示意页做应用：侧栏、Project 主页、对话视图、申请工作台、设置 | 完成（用户真实冒烟通过，2026-10-23） |
| [C005-fix](plan/coding/C005-fix-posting-status.md) | 岗位下线的状态（候选 closed / 申请 posting_closed）；调研时进详情页看要求和招聘状态 | 已施工，等用户试（2026-10-23） |
| [C006-front](plan/coding/C006-front.md) | 对话界面照 ChatGPT / Claude 重做：Markdown 渲染、思考和工具调用展开列表、正在做什么、输入框 | 已施工，等用户在浏览器里看（2026-10-23） |
| [C006-agent](plan/coding/C006-agent.md) | 后端补真实思考、完整步骤、当前活动、按步推送、安全的重新生成（给 C006-front 用） | 已施工，等用户用真实模型试（2026-10-23） |
| [C006-fix](plan/coding/C006-fix-attachments.md) | C006 的修补：申请工作台整屏；两个输入框都能附文件和图片，图片发给模型看 | 已施工，等用户在本机试（2026-10-23） |
| [C006-fix 版式](plan/coding/C006-fix-ui-mockup.md) | 照最早的示意页改全局 / Project 页：页签、8 格 KPI、漏斗、看板、时间线、申请总表 | 已施工，等用户在浏览器里对照（2026-10-23） |
| [C006-fix 运行时](plan/coding/C006-fix-runtime.md) | 结构修正：事件日志 + 可续接推送、申请线程回报 Session、用户事实优先、工具可重放、文件一张清单、补中断的调用 | 已施工，等用户在本机试（2026-10-23） |
| [C006-fix 第二阶段](plan/coding/C006-fix-runtime-2.md) | 之前工作里同类的结构问题：申请排队、软按钮判断交给模型、「在跑」推导、标签页认领、合并两个循环、小项 | 已施工，经两轮只读审查和修正，等用户在本机试（2026-10-23；对话压缩、资料导入不做） |
| [C006-fix 浏览器](plan/coding/C006-fix-browser.md) | 浏览器连接状态只有一份、一个入口；标签页只碰自己打开 / 点出 / 用户转交的 | 连接部分已做；标签页规则已改回提示词 / skill 约束，代码只记账（第 3 节，2026-10-23）；真实 Chrome 未跑 |
| [C007](plan/coding/C007-after-submit.md) | 提交以后：对话里说或点确认记「已投递」；每 3–5 天提醒查看邮箱 / 官网，对话里反馈（里程碑第 8 段改版） | 已施工，等用户在本机试（2026-10-23） |
| [C008](plan/coding/C008-resume-match.md) | 按岗位给简历排名、给修改建议，选定的简历申请时默认上传（里程碑第 5 段的一部分） | 已施工，等用户试（2026-10-23） |
| [C006-fix 真实跑通](plan/coding/C006-fix-real-run.md) | 第一次、第二次真实跑通（小米 Agent工程师）发现的问题和状态 | 1–4、6、8、10、14、15 已修（10、14、15 真实页面未跑）；7、18 待用户决定；9 结构修正、12、16、17 待修；5、11、19 token 记录（2026-10-23） |
| [C008-fix](plan/coding/C008-fix-resume-confirm.md) | 简历在对话里和用户定好；申请开始时放进上传区成为文件 1，直接上传 | 已施工，真实跑未运行（2026-10-23） |
| [C009](plan/coding/C009-sensitive-placeholders.md) | 敏感信息用占位符记在资料里，真实值在本地密钥文件，填表时替换 | **待用户确认**（动 dev-limit 和 master 的 C 级规则） |
| [C010](plan/coding/C010-steer.md) | 运行中插话（steer）：Agent 跑着时也能发消息，下一步看到；「打断并发送」 | 已施工，等用户试（2026-10-24；master 回写已完成） |
| [C011](plan/coding/C011-concurrent-workers.md) | 多个申请 Worker 并发：共享一个 Chrome、各自的标签页，Worker 池 | 方案 A 已施工：Worker 池 + 浏览器层合并、构建通过；自己的 Chrome 上手动检查通过（同站点对话框卡住除外）；等用户在本机 Chrome 冒烟（2026-10-24） |
| [C012](plan/coding/C012-worker-settings.md) | 设置页改 Worker 数量（同时申请数、每站上限），保存后马上生效、存进 settings/workers.yaml | 已施工，等用户试（2026-10-24） |
| [C013](plan/coding/C013-delete-session-project.md) | 删除 Session / Project：挪进 storage/.trash/（软删、可手动恢复），侧栏一个确认框；删 Session 时 Job 留在 Project 里 | 已施工，等用户试（2026-10-24；memory.md 加 `.trash/` 待确认） |

## 参考（不是施工步骤）

- `../Eco_Agent/`：同栈参考仓库，只读。
- [plan/reference/research-codex.md](plan/reference/research-codex.md)：旧 Codex 投递工作区复盘。
- [plan/reference/research-hermes.md](plan/reference/research-hermes.md)：Hermes Agent 设计参考。
- Dashboard 与界面示意页（虚构数据）：https://claude.ai/artifact/4K6eE85i51GvrLUGz8cARD

## 停靠点

2026-10-23 晚暂停：标签页改回提示词、C008、真实跑通的修补都已提交（分支 C008，最新 7613292）。申请 2026-10-23-001 停在「浏览器断开」。明天按 dev-log.md 最后一条「暂停交接」：先接着跑完这次申请，再定第 18 条和 C009，再修 9、12、16、17 和 token。
