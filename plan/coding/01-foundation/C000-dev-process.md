# C000 · 安装开发流程

- 对应 Master：无（流程本身）
- 状态：完成

## 题目

在仓库里装上固定的开发流程，让后续每一步都有索引、细节、边界和日志。流程沿用 `../Eco_Agent/`。

## 已确认决定

| 文件 | 职责 |
| --- | --- |
| `AGENTS.md` | 代理每次开工要遵守的做法 |
| `CLAUDE.md` | 只引用 `AGENTS.md`，让 Claude Code 也加载同一份约定 |
| `dev-plan.md` | 短索引：现在大体到哪一步，指向 `plan/` |
| `plan/*.md` | 该步的全部细节 |
| `dev-limit.md` | 现在不能越过的线 |
| `dev-log.md` | 已经发生的事实和验证 |
| `dev-recoding.md` | 原意、取舍、未决问题 |

`.cursor/rules/dev-process.mdc` 始终生效，提醒代理先读上述文件。

## 完成定义

- 上表文件都在仓库根目录，`plan/` 有本文件和 `README.md`。
- `dev-plan.md` 没有展开架构、版本或目录树。

## 不做的事

- 不定义 JobApply 的产品目标。
