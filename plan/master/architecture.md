# JobApply 文件架构

状态：**已确认**（2026-10-22；2026-11-25 按 C030 目标演进对齐）。

Spring Boot 在 `backend/`。图中的 `backend/` 子目录是包，物理路径为 `backend/src/main/java/jobapply/...`。

## 目录树

```text
JobApply_Agent/
├── backend/                    # Spring Boot
│   ├── config/                 # 系统、模型与运行配置
│   ├── agents/                 # 外置 Mode 规格与角色装配
│   ├── harness/                # 单 Base Agent 运行时
│   │   ├── runtime/            # AgentLoopEngine、ToolRegistry、EventBus
│   │   ├── plugins/            # PluginChain、6 个 Capability Plugins、SegmentBoundaryPlugin
│   │   ├── context/            # 上下文构建与裁剪
│   │   └── policies/           # 硬提交词与 C 级占位符策略
│   ├── workflow/               # 统一工作流：SessionService、JobThreadService、SubagentExecutor、Worker 池与台账
│   ├── memory/                 # 资料、Skill、站点与公司记忆、本地 RAG 检索
│   ├── tools/                  # Agent Tool 接口与工具网关
│   ├── domain/                 # 业务数据结构
│   ├── persistence/            # 文件持久化（Markdown / YAML frontmatter / JSONL）
│   └── api/                    # 本地 Web / SSE API
├── frontend/                   # 前端：React + Vite + TypeScript
├── storage/                    # 数据与产物（隐私数据不进 Git）
└── docs/
```

## 目录与模块

| 路径 | 模块 | 职责 |
| --- | --- | --- |
| `backend/config` | 系统配置 | 系统、模型与运行配置 |
| `backend/agents` | Mode & 装配 | 声明式 `resources/modes/*.md` 解析与上下文装配 |
| `backend/harness` | Base Agent Harness | 统一单个 `AgentLoopEngine` + `ToolRegistry` + `PluginChain` + `EventBus`。`plugins/` 承载 `WebSearch`、`BrowserRead`、`BrowserFill`（内嵌硬提交守卫与 `{KEY}` 占位符替换/遮值）、`ApplicationState`、`Tracking`、`Memory` 与 `SegmentBoundaryPlugin` |
| `backend/workflow` | Workflow | 统一工作流：`SessionService`（Session 对话）、`JobThreadService`（单 Job Thread 上经 `SegmentBoundary` 切换 `apply` / `followup` Mode）、`SubagentExecutor`（Depth=2/3 单向 Subagent 树与 `subruns/<subId>.json`）、Worker 池（并发 + 排队）与台账生成 |
| `backend/memory` | Memory | 个人资料四层记忆、Skill、站点/公司记录、本地 ONNX 经验检索（`cache/rag/`） |
| `backend/tools` | Tool Gateway | Agent 可调用工具注册与分发 |
| `backend/domain` | 领域结构 | Project、Session、Job、Round、Fact、PrepState（5 态）等业务数据结构 |
| `backend/persistence` + `storage/` | Persistence | 统一读写 `storage/` 下的 Markdown / YAML frontmatter / JSONL（含统一 `thread.jsonl` 与 `subruns/<subId>.json`） |
| `backend/api` | 对外接口 | 仅监听 `127.0.0.1` 的 REST 与 SSE（`/api/events`）接口 |
| `frontend/` | 前端 | React + Vite + TypeScript；开发时通过 Vite 代理 `/api`，打包后由 Spring Boot 提供静态资源 |

## 领域对象与存储演进

`domain` 涵盖 Project、Session、Job（`phase` + `PrepState` + `rounds`）、Fact（带作用域与来源）、Company 额度。所有状态的单一事实来源均是 `storage/` 下的 Markdown / YAML frontmatter 与 JSONL 文件（`JobThreadStore` 统一管理 `jobs/<job-id>/thread.jsonl` 并兼容读取旧 `runs/<job-id>.json` 与 `followup/transcript.jsonl`）；`storage/` 不做版本控制。SQLite + FTS5 全文检索索引（`index.sqlite`）保留为 **Phase 3 未来扩展**，当前由文件直读与 `cache/rag/` 本地向量检索承载。存储格式见 [memory.md](memory.md)「存储格式」。
