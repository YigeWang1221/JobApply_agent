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

## Coding Plan（`plan/coding/`）

| 编号 | 内容 | 状态 |
| --- | --- | --- |
| [C000](plan/coding/C000-process.md) | 安装开发流程 | 完成 |
| [C001](plan/coding/C001-scaffold.md) | 本地单模块脚手架 | 完成 |
| [C002](plan/coding/C002-memory-ledger.md) | workflow 里程碑第 1 段：Memory 与台账 | 完成 |
| [C003](plan/coding/C003-playwright-mcp.md) | workflow 里程碑第 2 段：Playwright MCP 接通 | 完成 |
| [C004](plan/coding/C004-harness-guard.md) | workflow 里程碑第 3 段（总表：拆分、已定决定、回写 Master 清单） | 已拆分 |
| [C004-A](plan/coding/C004-A-model-guard.md) | 模型接入、资料原件、设置页、提交守卫 | 已确认，待施工 |
| [C004-B](plan/coding/C004-B-harness-review.md) | Harness 循环、skill、申请线程、关卡 B | 已确认（在 C004-A 之后施工） |

## 参考（不是施工步骤）

- `../Eco_Agent/`：同栈参考仓库，只读。
- [plan/reference/research-codex.md](plan/reference/research-codex.md)：旧 Codex 投递工作区复盘。
- [plan/reference/research-hermes.md](plan/reference/research-hermes.md)：Hermes Agent 设计参考。
- Dashboard 与界面示意页（虚构数据）：https://claude.ai/artifact/4K6eE85i51GvrLUGz8cARD

## 停靠点

C003（里程碑第 2 段：Playwright MCP 接通）已完成，用户已验收（2026-10-22）。下一步是 C004（里程碑第 3 段：Harness 循环、提交守卫、关卡 B）。用户的约定是**一个 session 只做一份 Coding Plan**，C004 已拆成 [C004-A](plan/coding/C004-A-model-guard.md) 和 [C004-B](plan/coding/C004-B-harness-review.md) 两份，用户已全部确认，[总表](plan/coding/C004-harness-guard.md)的 9 项已回写进 Master（2026-10-22）。本 session 只做计划，不写代码；下一个 session 施工 C004-A。

### 新会话从这里接手

1. 读 `AGENTS.md` → `dev-limit.md` → 本文件 → `plan/README.md`。
2. 读 [C004 总表](plan/coding/C004-harness-guard.md)、[C004-A](plan/coding/C004-A-model-guard.md)、[C004-B](plan/coding/C004-B-harness-review.md)，以及它们列出的 Master 章节；参考已完成的 [C003](plan/coding/C003-playwright-mcp.md)（「实际结果」「手动冒烟结果」「偏差」）。
3. 按 C004-A 的 7 个切片施工（第 1 片是小样核实）；C004-B 在 C004-A 完成并验收后施工。
4. 验证命令：在 `backend/` 执行 `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -B clean verify`（代理的 shell 不加载 `~/.zshrc`）。
5. 已知的一点：`dev-log.md`、`dev-recoding.md` 是历史记录，里面的旧路径不代表现状。
