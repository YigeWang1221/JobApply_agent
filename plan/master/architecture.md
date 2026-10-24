# JobApply 文件架构

状态：**已确认**（2026-10-22）。目录已按此建好，但每个包只有 `package-info.java`。日期：2026-10-22。

参照 `../Eco_Agent/plan/afts-architecture.md` 精简而来：去掉了 Python 执行层（`execution/`、`python/`）和动态委托（`workflow/delegation`），因为 JobApply 目前没有这类需求。需要时再加回，并更新本文件。

Spring Boot 在 `backend/`。图中的 `backend/` 子目录是包，物理路径为 `backend/src/main/java/jobapply/...`。

## 目录树

```text
JobApply_Agent/
├── backend/                    # Spring Boot
│   ├── config/
│   ├── agents/                 # Agent 角色与 Catalog
│   ├── harness/                # 自定义 Agent Loop
│   │   ├── runtime/
│   │   ├── context/
│   │   └── policies/
│   ├── workflow/               # 申请线程：Worker 池（并发 + 超出排队，C011）、运行状态、台账（不用图引擎）
│   ├── memory/                 # 长期记忆（如用户求职画像）
│   ├── tools/                  # Agent Tool 接口
│   ├── domain/                 # 业务数据结构
│   ├── persistence/            # 数据持久化
│   ├── observability/          # 事件与追踪
│   └── api/
├── frontend/                   # 前端，框架未定
├── storage/                    # 数据与产物（隐私数据不进 Git）
└── docs/
```

## 目录与模块

| 路径 | 模块 | 职责 |
| --- | --- | --- |
| `backend/config` | 系统配置 | 系统、模型与运行配置 |
| `backend/agents` | Agent Management | 角色定义、Catalog、实例生命周期 |
| `backend/harness` | Custom Harness | 自研单 Agent loop。子包 `runtime`、`context`、`policies` |
| `backend/workflow` | Workflow | 申请线程：开始、回复、停止、状态；运行状态存 `storage/runs/<job-id>.json`。流程由 harness 里的模型决定，不用 LangGraph4j（用户确认，2026-10-23，见 agent-runtime.md 第 7 节） |
| `backend/memory` | Memory | Session / 用户画像等 |
| `backend/tools` | Tool Gateway | Agent 可调用的工具 |
| `backend/domain` | 领域结构 | 业务数据结构 |
| `backend/persistence` + `storage/` | Persistence | 任务、Session、产物 |
| `backend/observability` | 观测 | 追踪、审计 |
| `backend/api` | 对外接口 | Web / API。只监听 127.0.0.1；带 `Origin` 请求头的请求，只放行允许名单里的本机前端地址（默认 `127.0.0.1:5173`、`localhost:5173`），不带的照常放行（用户确认，2026-10-22；C003 时是一律拒绝） |
| `frontend/` | 前端 | 产品界面。React + Vite + TypeScript；开发时用 Vite 开发服务器（只监听 127.0.0.1）代理 `/api`；由 Spring Boot 提供打包后的静态文件留到第 7 段 |

## 领域对象（来自 2026-10-22 的设计）

`domain` 至少会有 Project、Session、Application 状态、Fact（带作用域与来源）、Company 额度。Project / Session 的含义和 storage 布局见 [memory.md](memory.md)。`persistence` 负责把它们读写到 `storage/` 下的 Markdown / JSONL，并维护可重建的 `storage/index.sqlite`；申请线程的运行状态存到 `storage/runs/<job-id>.json`。storage 不做版本控制。存储格式见 [memory.md](memory.md)「存储格式」。

## 暂不固定

- 具体 Agent 角色、职责与工具
- 主图的节点与人工确认点
- 存储技术、前端框架
- 是否需要 Python 层或动态委托
