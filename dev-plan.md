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
| [C005 调研 Agent](plan/coding/C005-research-agent.md) | 里程碑第 4 段：Session 对话 Agent，搜索、读 JD、候选池、开始申请 | 计划已写，等用户确认 |
| [C005 界面](plan/coding/C005-ui.md) | 按示意页做应用：侧栏、Project 主页、对话视图、申请工作台、设置 | 计划已写，和调研 Agent 一起做 |

## 参考（不是施工步骤）

- `../Eco_Agent/`：同栈参考仓库，只读。
- [plan/reference/research-codex.md](plan/reference/research-codex.md)：旧 Codex 投递工作区复盘。
- [plan/reference/research-hermes.md](plan/reference/research-hermes.md)：Hermes Agent 设计参考。
- Dashboard 与界面示意页（虚构数据）：https://claude.ai/artifact/4K6eE85i51GvrLUGz8cARD

## 停靠点

2026-10-23：C004 完成（申请 Agent 在 13 个夹具上用真实模型跑通，停在提交前）。下一步是 C005：调研 Agent + 界面，计划见上表。真实 Chrome 冒烟未运行。
