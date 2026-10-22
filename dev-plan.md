# 开发计划

本文件只做**索引**：现在做什么、细节在 `plan/` 的哪一份。不写版本表、目录树、模块规格。那些一律在对应 plan 文件里。

施工约定见 `AGENTS.md`。边界见 `dev-limit.md`。

## 现在做什么

产品：**JobApply**（求职投递 Agent），放在本仓库。Spring Boot 在 `backend/`。一句话目标待用户确认；业务行为尚未开始。

## 现行 plan（细节在这些文件）

| 现在做什么 | 文件 | 状态 |
| --- | --- | --- |
| 产品目标、技术路线、边界 | [plan/jobapply-base.md](plan/jobapply-base.md) | 技术路线已确认；产品目标待确认 |
| 逻辑目录与模块职责 | [plan/jobapply-architecture.md](plan/jobapply-architecture.md) | 草案，待确认 |
| 本地脚手架（`backend/` 单模块） | [plan/jobapply-scaffold.md](plan/jobapply-scaffold.md) | 完成 |
| 安装开发流程文档 | [plan/00-process.md](plan/00-process.md) | 完成 |
| `plan/` 目录说明 | [plan/README.md](plan/README.md) | 完成 |

## 参考（不是施工步骤）

`../Eco_Agent/`：同栈参考仓库，只读。

## 停靠点

流程文档与脚手架已就位。等待用户给出 JobApply 的一句话目标和第一段要跑通的可观察行为。
