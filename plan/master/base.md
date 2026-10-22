# Base plan：JobApply

状态：技术路线已确认（沿用 `../Eco_Agent/` 的栈）。产品目标已由用户给出；各 Master Plan **已确认**（2026-10-22）。未写业务代码。日期：2026-10-22。

文件架构见 [architecture.md](architecture.md)。脚手架与精确版本见 [C001-scaffold.md](../coding/C001-scaffold.md)。本文件不重复目录树。

## 最终目的

用户已给出（2026-10-22）：

- 根据本地存放的个人资料，找到符合条件的岗位。
- 通过 Chrome MCP 在浏览器里替用户填写申请表。
- **提交前停下来交给用户审核，最终提交由用户本人完成。**
- 本地维护一份易于 AI 阅读的投递台账（Markdown 等），记录投了哪些岗位、处于什么阶段、最近一次查询的时间。
- 设计偏重 MCP，自研 Tool 较轻。

流程见 [workflow.md](workflow.md)，Memory 见 [memory.md](memory.md)，追踪见 [tracking.md](tracking.md)，界面与模型设置见 [ui.md](ui.md)。

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
| 语言 | Java 25（LTS） | 已确认；2026-10-22 用户决定从 21 升到 25 |
| 构建 | Maven 单模块（`backend/`） | 已确认；是否拆多模块后置 |
| 应用框架 | Spring Boot 4.1.1 | 已确认 |
| 模型 / 消息 / 工具 | Spring AI 2.0.1 | 已确认；脚手架不注册可用模型 |
| Workflow Runtime | LangGraph4j 1.9.6 | 已确认 |
| Agent 循环 | 自研 Custom Harness | 已确认 |

## 后置

- 模型供应商（Spring AI starter 选哪家）
- Chrome MCP 选型（倾向 `chrome-devtools-mcp`，见 design）
- 存储选型、是否上向量检索
- 前端框架与推送方式
- 岗位数据来源（以及条款是否允许抓取）
- 账号、部署

## 不做

- Alibaba Graph
- 用框架封装的 ReActAgent 当默认 Agent Loop
- 在 Harness 里再实现一套图执行引擎
