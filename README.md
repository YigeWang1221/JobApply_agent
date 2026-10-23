# JobApply

本地运行的求职投递 Agent。它根据你本地的个人资料找到匹配的岗位，在你自己的 Chrome 里代填申请表，**在提交前停下来交给你审核，最终提交由你本人点击**，然后把结果记进本地台账，并跟踪之后的笔试、面试和复盘。

> 状态：设计已确认，正在按里程碑施工。完成了第 1 段（Memory 与台账）和第 2 段（Playwright MCP 接通），见下方「进度」。还不能实际投递。

## 核心原则

- **系统永远不点最终提交。** 这条不是只写在提示词里，而是由工具白名单、点击守卫和流程关卡三层强制执行：「提交 / 投递 / Apply / Submit」这类按钮一律拦截；遇到验证码就停下，交给你处理。
- **没有证据就不算已投递。** 只有读到官网的成功页或官方投递历史，才会记为 `applied`。
- **数据只在本机。** 简历、个人信息、台账、复盘都放在 `storage/`，不进主仓库的 Git，也不上传。`storage/` 可以选择用本地 git 保留版本。
- **敏感信息不落盘。** 个人信息分为 A / B / C 三级。C 级（证件号、工作授权、薪资期望等）系统不存、不填，由你在浏览器里亲自填写。
- **你登录，Agent 不碰密码。** 系统直接连接你日常使用的 Chrome，每次申请先交给你登录；不读取、不保存密码和 Cookie。

## 怎么工作

```text
Project（一份资料版本 + 偏好）
  └─ Session（一次对话：调研、投递、复盘，可以任意组合）
       ├─ 调研：搜索 → 核实 JD → 打分 → 写入候选池
       └─ 投递：选岗（关卡 A）→ 打开申请页 → 你登录 → 核实岗位
                → 上传简历、逐页填写 → 保存并回读
                → 关卡 B：你审阅填写报告 → 你本人点提交
                → 只读核验结果 → 写入台账 → 你确认后更新经验
```

- **Project**：一种求职侧重，例如「2025 秋招 · 资料 v2」。所有 Project 都继承 `base`（个人基础信息），可以覆盖其中的字段，也可以固定在某个时间点的版本。多个 Project 可以并行，各用各的简历。
- **Job**：一个具体岗位。先进入候选池，你选定后转为申请，之后经历「阶段 + 轮次」直到结束。一个 Job 可以跨多个 Session。
- **台账**：每个 Project 都有 `leads`、`applications`、`rounds` 三组 md + csv。md 给人和 AI 读，可以直接用 Obsidian 打开；csv 可以直接用 Excel 打开。另外还有每个 Project 的 Dashboard，以及跨 Project 的复盘题库。

## 技术栈

| 层 | 选型 |
| --- | --- |
| 后端 | Java 25 · Spring Boot 4.1.1 · Spring AI 2.0.1 |
| 流程编排 | LangGraph4j 1.9.6（图执行、Checkpoint、暂停与恢复） |
| Agent 循环 | 自研 Harness（不用框架封装的 ReActAgent），每次工具调用都先过守卫 |
| 浏览器 | `@playwright/mcp`，以 `--extension` 模式连接本机 Chrome |
| 搜索 | 后端直接调用搜索 API，免费来源优先，额度用完后切到 Brave（key 可选） |
| 存储 | Markdown + YAML frontmatter（事实来源），JSONL（事件、对话），SQLite（可重建的索引、Checkpoint） |
| 前端 | React 19 + Vite 8 + TypeScript（目前只有「设置 · 模型」和「资料」两页；「资料」页有「原件」「来源」「整理与确认」「事实与画像」四个页签） |
| 模型 | 用户自配：OpenAI 兼容格式或 Anthropic 格式，支持本地模型 |

## 进度

| 里程碑 | 内容 | 状态 |
| --- | --- | --- |
| 1 | Memory 与台账：storage 布局、继承解析、候选池、状态机、台账生成、可选的本地 git | 已实现 |
| 2 | Playwright MCP 接通：连接本机 Chrome、交给用户登录、身份核对 | 完成 |
| 3 | 提交守卫与关卡 B（在 HTML 夹具上测试） | 进行中：模型接入、资料原件、设置页、守卫规则已实现（C004-A）；资料导入的事实格式、按语言取值、来源登记与估算已实现（C004-A-1）；整理、确认与画像已实现（C004-A-2）；Harness 循环与关卡 B 未开始 |
| 4 | 岗位搜索与核实 | 未开始 |
| 5 | 匹配、额度与关卡 A | 未开始 |
| 6 | 投递后追踪与复盘 | 未开始 |
| 7 | 前端 | 未开始 |
| 8 | 状态查询 | 未开始 |

## 目录

```text
backend/    Spring Boot 应用（包：domain、persistence、memory、harness、workflow、tools 等）
frontend/   前端（React + Vite；目前是设置页和资料页）
storage/    本地数据（被 .gitignore 忽略，不进 Git）
plan/       计划：master/（总体设计）、coding/（每次施工）、reference/（参考材料）
docs/       文档
```

## 本地构建

需要 JDK 25 和 Maven。

```bash
cd backend
mvn -B clean verify    # 单元测试 + 端到端场景测试
mvn -B clean test      # 只跑单元测试
```

测试全部使用虚构数据，不访问网络，也不调用真实模型。

## 前端

需要 Node 20.19+ 或 22.12+。开发时用 Vite 开发服务器，`/api` 代理到后端，两者都只监听 127.0.0.1。

```bash
# 终端 1：后端（storage 默认是仓库的 storage/）
cd backend
mvn -B -q -DskipTests package && java -jar target/jobapply-0.0.1-SNAPSHOT.jar

# 终端 2：前端
cd frontend
npm install
npm run dev            # 打开 http://127.0.0.1:5173
npm run build          # 类型检查 + 打包
```

- **设置 · 模型**：添加提供方（选预设，或自定义接口格式和 Base URL）→ 填 Key → 拉取模型列表（拉不到时手填）→ 逐个「测试」→ 分配主模型、辅助模型。Key 由后端写进 `storage/settings/.env`（权限 600），`storage/settings/models.yaml` 里只有占位符 `${变量名}`，页面上只显示末四位。没有通过工具调用测试的模型不能用作主模型。
- **资料**：把简历等原件拖进来，保存在 `storage/base/originals/`，登记在 `storage/base/assets.md`；同一份文件不会重复保存。登记时会把文件开头的文字发给辅助模型，写一句说明（没有配置模型时跳过）。
  - **来源**：登记本地目录或 GitHub 公开仓库（只读、不复制），估算会读哪些文件、跳过哪些、要调用几次模型。秘密文件（`.env`、私钥、证书等）按名字跳过，不会被打开。
  - **整理与确认**：确认估算后「开始整理」，后台为每个来源写一张清单卡（`storage/base/evidence/`），抽出候选的事实和项目卡，和已有的事实层对比（新增 / 不同 / 相同）。这一步会把扫描出的文字发给你配置的模型。候选按组列出，可以确认、修改后确认、放进 `conflicts.md` 或拒绝；缺的那种语言在确认时由模型起草，标为未审阅。确认的内容写进 `storage/base/facts.md`、`storage/base/projects/<slug>.md`，开启 storage git 时每个决定提交一次。关掉页面以后可以回来接着确认。
  - **事实与画像**：按组浏览和修改事实，项目卡中英对照修改、标为已审阅；根据事实层生成画像（方向、技能分组、亮点、定位），确认后写进 `storage/base/profile.md`，手改过的小节以后重新生成时不覆盖。
- 后端只接受来自 `http://127.0.0.1:5173`、`http://localhost:5173` 的网页请求（`jobapply.api.allowed-origins`），其它网页发来的请求一律 403。

## 设计文档

- [产品目标与技术路线](plan/master/base.md)
- [模块与目录](plan/master/architecture.md)
- [主流程、提交保护、浏览器、搜索、预算](plan/master/workflow.md)
- [Project / Session / Job 与 storage 结构](plan/master/memory.md)
- [阶段与轮次、台账、Dashboard、复盘](plan/master/tracking.md)
- [界面与模型设置](plan/master/ui.md)

开发流程约定见 [AGENTS.md](AGENTS.md)，当前进度索引见 [dev-plan.md](dev-plan.md)。
