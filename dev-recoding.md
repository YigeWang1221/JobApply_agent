# 开发实录

这里记过程：用户要什么、当时怎么理解、放弃过什么、还没定什么。它不是变更清单；变更清单在 `dev-log.md`。

一条实录回答四件事：

1. 用户这一步的原意。
2. 实际采用的理解。
3. 故意没做的选择。
4. 留给用户决定的问题。

## 2026-10-22 · 流程文档与脚手架

用户要用 Spring AI + LangGraph4j 开发 JobApply，参考 `Eco_Agent` 的 md 文件和 `backend/`、`frontend/` 结构，先搭基础脚手架。

采用的理解：流程文件（`AGENTS.md`、`dev-*.md`、`plan/`）沿用 Eco 的分工；版本沿用 Eco 已验证的 Java 21 / Spring Boot 4.1.1 / Spring AI 2.0.1 / LangGraph4j 1.9.6。包根用 `jobapply`。目录参照 Eco，但去掉了 Python 执行层和 `workflow.delegation`，因为求职场景暂时用不上。另加 `CLAUDE.md` 引用 `AGENTS.md`，因为用户在用 Claude Code。

故意没做：不写产品目标，不加 Web starter 和模型 starter，不选前端框架，不建 `python/`，不提交。

留给用户：一句话产品目标；第一段可观察行为；模型供应商；前端框架；架构草案里去掉的两块是否确实不要。
