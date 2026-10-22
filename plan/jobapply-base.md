# Base plan：JobApply

状态：技术路线已确认（沿用 `../Eco_Agent/` 的栈）。产品目标待用户确认。未写业务代码。日期：2026-10-22。

文件架构见 [jobapply-architecture.md](jobapply-architecture.md)。脚手架与精确版本见 [jobapply-scaffold.md](jobapply-scaffold.md)。本文件不重复目录树。

## 最终目的

**待用户确认。** 当前只知道产品是一个帮助求职投递的 Agent 应用（JobApply）。下列问题由用户回答后再写成已确认：

- 一句话目标：Agent 最终替用户交付什么（例如岗位筛选清单、定制简历 / Cover Letter、投递跟踪看板）。
- 第一段要跑通的可观察行为：输入什么，状态怎么变，输出什么。
- 哪些动作必须人工确认（尤其是任何对外提交）。

原则（沿用）：**Agent 负责推理与决策，系统负责状态管理、权限校验和可靠执行。**

## 技术路线

**不上** Spring AI Alibaba Graph。**不用**框架封装的 ReActAgent 当默认 Agent Loop。

```text
Frontend
        ↓
Spring Boot 应用层（API、配置、权限、业务服务）
        ↓
LangGraph4j 1.9.x — Workflow Runtime
  图状态 · 条件路由 · 并行 · Checkpoint · 暂停/恢复（人工确认点）
        ↓
Custom Agent Harness（自研 Model → Tool → Model）
  Context / Memory / Tool / Budget / Stop
        ↓
Spring AI ChatModel          Tools
```

| 类别 | 值 | 状态 |
| --- | --- | --- |
| 语言 | Java 21 | 已确认 |
| 构建 | Maven 单模块（`backend/`） | 已确认；是否拆多模块后置 |
| 应用框架 | Spring Boot 4.1.1 | 已确认 |
| 模型 / 消息 / 工具 | Spring AI 2.0.1 | 已确认；脚手架不注册可用模型 |
| Workflow Runtime | LangGraph4j 1.9.6 | 已确认 |
| Agent 循环 | 自研 Custom Harness | 已确认 |

## 后置

- 产品目标、Agent 角色与工作流
- 模型供应商（Spring AI starter 选哪家）
- 存储选型、是否上向量检索
- 前端框架与推送方式
- 岗位数据来源（以及条款是否允许抓取）
- 账号、部署

## 不做

- Alibaba Graph
- 用框架封装的 ReActAgent 当默认 Agent Loop
- 在 Harness 里再实现一套图执行引擎
