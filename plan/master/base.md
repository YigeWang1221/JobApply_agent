# Base plan：JobApply

状态：技术路线与各 Master Plan 已确认（2026-10-22 起持续修订，2026-11-25 对齐 C030 目标架构）。

文件架构见 [architecture.md](architecture.md)。脚手架与精确版本见 [C001-scaffold.md](../coding/01-foundation/C001-scaffold.md)。本文件不重复目录树。

## 最终目的

用户已给出（2026-10-22）：

- 根据本地存放的个人资料，找到符合条件的岗位。
- 通过 `@playwright/mcp` 连接本机 Chrome 在浏览器里替用户填写申请表。
- **提交前停下来交给用户审核，最终提交由用户本人完成。**
- 本地维护一份易于 AI 阅读的投递台账（Markdown + CSV），记录投了哪些岗位、处于什么阶段、最近一次查询的时间，并支持投递后轮次复盘与官网门户查状态。
- 设计偏重 MCP，自研 Tool 较轻。

流程见 [workflow.md](workflow.md)，Memory 见 [memory.md](memory.md)，追踪见 [tracking.md](tracking.md)，界面与模型设置见 [ui.md](ui.md)，运行时见 [agent-runtime.md](agent-runtime.md)。

原则（沿用）：**Agent 负责推理与决策，系统负责状态管理、底线守卫与可靠执行。**

## 求职表达与包装（用户修订，2026-10-22）

本项目服务于用户的个人求职目标。Agent 按用户需求、目标公司的 JD 和业界常用表达，主动提炼亮点、重组经历、突出相关技能、润色职责与成果，并提供中英文版本。最终采用的表述和用途由用户决定。

- 抽取和画像的提示词均允许提出估算、量化口径、技能定位和措辞候选，由用户决定采用；不因原材料没有明确写出数字或细节就停止起草。新增细节先作为待确认候选。
- 指标可以来自用户确认、已有简历、项目材料或估算；已有测试条件和出处时可附上，没有时不阻止保存、生成简历或填表。估算和候选表述在内部保留说明，供用户审阅。
- 不要求把「事实」和「包装」做成互斥的内容或审批流程；结构化资料与岗位表达可以配合使用。用户确认的补充、改写和量化口径可以进入资料层。
- 经历、技能、成果、指标均可由用户确认后使用；证据状态只提供参考，不自动限制为面试用途。团队项目允许突出个人负责、主导或独立完成的具体部分，不按关键词拒绝保存。
- 材料之间有冲突时给出差异和建议，由用户选定采用的版本；模型提出的新增细节作为候选交给用户，不自行记成用户已确认。

## 技术路线

**不上** Spring AI Alibaba Graph。**不用**框架封装的 ReActAgent 当默认 Agent Loop。

```text
Frontend（React + Vite + TypeScript，SSE 实时推送）
        ↓
Spring Boot 应用层（API、配置、业务服务）
        ↓
统一工作流（workflow）：SessionService · JobThreadService（apply / followup 同线程切 Mode）· SubagentExecutor（Depth=2/3 单向树）
        ↓
Base Agent Harness（AgentLoopEngine + ToolRegistry + PluginChain + 6 Capability Plugins + 外置 Modes）
        ↓
Spring AI ChatModel          Playwright MCP / Web Search / Local Memory & Ledger
```

| 类别 | 值 | 状态 |
| --- | --- | --- |
| 语言 | Java 25（LTS） | 已确认 |
| 构建 | Maven 单模块（`backend/`） | 已确认 |
| 应用框架 | Spring Boot 4.1.1 | 已确认 |
| 模型 / 消息 / 工具 | Spring AI 2.0.1（`spring-ai-openai`、`spring-ai-anthropic`，运行时动态创建） | 已确认 |
| 浏览器自动化 | `@playwright/mcp`（0.0.83，远程调试模式连接本机 Chrome） | 已确认 |
| 存储与检索 | Markdown + YAML frontmatter + JSONL + 本地 ONNX RAG（`cache/rag/`）；SQLite FTS5 为 Phase 3 未来扩展 | 已确认 |
| 前端与推送 | React + Vite + TypeScript + SSE（`/api/events`） | 已确认 |
| Agent 运行时 | 自研单 Base Agent + Plugin / Skill / Mode + Depth=2/3 Subagent 树（不用图引擎） | 已确认（2026-10-23 确立循环，2026-11-25 对齐 C030） |

## 暂不纳入 / 后续演进

- 岗位数据来源的平台覆盖扩展（按实际投递站点持续补充）
- Phase 3 未来扩展：SQLite + FTS5 全文查询索引
- 部署只做本机自用的 macOS App（用户确认，2026-10-27，见 [C017](../coding/03-features/C017-macos-app.md)），不做分发、签名公证、自动更新

## 不做

- Alibaba Graph 或任何固定图执行引擎
- 用框架封装的 ReActAgent 当默认 Agent Loop
- 引入 opencode / pi 双进程运行时
