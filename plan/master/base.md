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
Frontend
        ↓
Spring Boot 应用层（API、配置、权限、业务服务）
        ↓
申请线程（workflow）：Worker 池并发、超出排队（C011）· 运行状态存盘 · 用户回复后接着跑
        ↓
Custom Agent Harness（自研 Model → Tool → Model，LLM 主持流程）
  Context / Memory / Tool / Skill / 兜底上限
        ↓
Spring AI ChatModel          Tools
```

| 类别 | 值 | 状态 |
| --- | --- | --- |
| 语言 | Java 25（LTS） | 已确认；2026-10-22 用户决定从 21 升到 25 |
| 构建 | Maven 单模块（`backend/`） | 已确认；是否拆多模块后置 |
| 应用框架 | Spring Boot 4.1.1 | 已确认 |
| 模型 / 消息 / 工具 | Spring AI 2.0.1 | 已确认；脚手架不注册可用模型 |
| Workflow Runtime | 不用图引擎：一个 Agent 循环 + 运行状态文件 | 2026-10-23 用户确认，取代 LangGraph4j 1.9.6（见 agent-runtime.md 第 7 节） |
| Agent 循环 | 自研 Custom Harness | 已确认 |

## 后置

- ~~模型供应商（Spring AI starter 选哪家）~~ → 已定（2026-10-22）：由用户在设置页自己配置，支持 OpenAI 兼容和 Anthropic 两种格式（`spring-ai-openai`、`spring-ai-anthropic` 2.0.1，运行时创建，不用自动配置），见 ui.md「模型设置」与 C004-A
- Chrome MCP 选型（倾向 `chrome-devtools-mcp`，见 design）
- 存储选型、是否上向量检索
- 前端框架与推送方式
- 岗位数据来源（以及条款是否允许抓取）
- 账号、部署

## 不做

- Alibaba Graph
- 用框架封装的 ReActAgent 当默认 Agent Loop
- 在 Harness 里再实现一套图执行引擎
