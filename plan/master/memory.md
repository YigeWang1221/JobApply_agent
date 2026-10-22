# 设计：Project / Session 与本地 Memory 文件结构

状态：**已确认**（2026-10-22）；未施工。日期：2026-10-22。

主流程见 [workflow.md](workflow.md)。来源经验见 [research-codex.md](../reference/research-codex.md)。

## 产品形态（用户给出，2026-10-22）

- **界面类似 Claude**：左侧是 Project 列表，每个 Project 下面是 Session 列表；中间是对话区；右侧可以看当前申请的状态和填写报告。
- **Session 只能存在于 Project 里**，不允许有游离的对话。
- **Project = 一种求职侧重**。每个 Project 有自己的本地 Memory：用哪份简历、强调哪些经历、个人信息截至哪个时间点。
  - 例：Project A 基于 2020 年的个人信息；Project B 是更新后的 2024、2025 年信息。
  - 不同 Project 在时间线上有继承关系，但彼此相对独立，可以**同时并行**，用两份不同的简历。
- **base**：所有 Project 共同继承的个人基础信息，放在 `storage/base/`。
- ~~一个 Session 基本对应投递一份简历~~ → **2026-10-22 修订**，原因见下一节。

## 修订：Session 与岗位解耦（用户提出，2026-10-22）

用户指出原模型的三个问题：
1. **同一家公司可以投好几个岗位**，而且这些岗位不一定符合 Project 名字所指的方向（例如一个叫「AI Agent」的 Project，匹配到的可能是三个后端岗）。
2. **调研和投递不在同一个 Session 里完成**。Web research 可能先找到一批公司和岗位，写进本地的推荐列表，过了一个、两个甚至好几个 Session 才开始投。
3. **一个 Session 的工作量不固定**：可能只做调研；可能调研加投递；也可能调研出很多结果，但受上下文等限制只投了一两个。

修订后的模型：

| 对象 | 是什么 | 生命周期 |
| --- | --- | --- |
| **Project** | 一份资料版本 + 一组偏好（简历版本、信息时间点、城市、方向权重） | 长期 |
| **Job（岗位）** | 一个具体岗位。先作为**候选**进入候选池，你选定后**转为申请**，之后经历各轮直到结束 | 跨多个 Session |
| **Session** | 一次对话 / 一段工作。可以做调研、投递、复盘中的任意组合，可以涉及零个或多个 Job | 一次对话 |
| **Company（公司）** | 全局。一家公司下面挂多个 Job，额度按公司或招聘项目计算 | 长期，跨 Project |

- 关系：Project 1─N Job，Project 1─N Session，Session N─N Job（通过事件日志里的 `session` 字段关联），Company 1─N Job。
- **Session 的类型不需要预先选**。系统根据 Session 里实际发生的事自动打标签：`research`（新增了候选）、`apply`（推进了申请）、`review`（记录了轮次或复盘），可以同时有多个标签。
- **跨 Session 接力**：每个 Session 结束时生成一段交接摘要，例如「本次调研出 12 个候选，投了 2 个，另有 1 个停在关卡 B」。新开 Session 时，Agent 先读候选池和未完成的事项，主动提出「下一家投什么」。
- **申请流程的 Checkpoint 按 Job 存，不按 Session 存**：线程 id 是 job id。一个申请可以在 Session A 里填到一半，停在关卡 B，再到 Session B 里继续。
- **Project 命名**：Project 代表「资料版本 + 偏好」，不代表岗位类别，里面的岗位不必和名字一致。建议用资料版本来命名，例如「2025 秋招 · 资料 v2」「2024 版 · 后端简历」。名字可以随时修改，修改不影响内部数据。

## storage 目录

```text
storage/                              # 整个目录被主仓库 .gitignore 忽略
├── .git/                             # 独立的本地 git：每次写入自动提交，只在本地保留版本，不设远程、不上传
├── .gitignore                        # 忽略 settings/.env、uploads/、*.sqlite、.obsidian/
│
├── base/                             # 全局个人基础信息（所有 Project 继承）
│   ├── facts.md                      # 身份、联系方式、教育、经历、技能、链接；每条带来源 / 确认日期 / 精度 / 有效期
│   ├── conflicts.md                  # 待核对冲突与空缺
│   ├── writing.md                    # 通用写作偏好
│   ├── assets.md                     # 所有原件的索引（文件名、SHA-256、类型、适用方向）
│   └── originals/                    # 原件统一保存在这里（简历 docx/pdf、补充 PDF、照片等）；Project 只引用，不复制
│
├── projects/<project-id>/            # 一个求职侧重
│   ├── project.md                    # frontmatter：继承关系、目标方向、默认简历；正文：这个侧重的说明
│   ├── overrides.md                  # 覆盖 / 补充 base 的事实（同一键名，带来源与日期）
│   ├── preferences.md                # 本 Project 的城市、方向、全职/实习、届别、评分权重
│   ├── assets.md                     # 本 Project 使用的简历版本、补充附件（引用 base/assets.md 的键）
│   ├── candidates.md                 # 待确认的 Memory 候选（来自本 Project 的 Session）
│   ├── jobs/<job-id>/                # 一个岗位：候选 → 申请 → 各轮 → 结束，跨多个 Session
│   │   ├── job.md                    # frontmatter = 岗位与申请的当前状态（单一事实来源）；正文 = JD 快照、资格映射、本次答案、授权、证据
│   │   ├── rounds/<seq>-<type>.md    # 每一轮的记录与复盘（用户确认的原始内容）
│   │   ├── events.jsonl              # 追加式事件；每条事件带 session 字段
│   │   └── fill-report.md            # 关卡 B 的填写报告
│   ├── sessions/<session-id>/        # 一次对话：调研 / 投递 / 复盘的任意组合
│   │   ├── session.md                # 标题、自动标签、涉及的 job 列表、结束时的交接摘要
│   │   └── transcript.jsonl          # 对话原文（不压缩，可追溯）
│   └── tracker/                      # 生成：leads / applications / rounds 各一组 md + csv，以及 dashboard.md 快照
│
├── review/                           # 复盘（跨 Project，按人汇总），详见 tracking.md
│   ├── questions.md                  # 生成：常考题库（按主题、出现次数）
│   ├── companies/<company>.md        # 生成：某公司历次各轮问过什么
│   └── lessons.md                    # 经验总结（候选经用户确认后写入）
│
├── playbook/                         # 流程记忆（跨 Project 共享，和个人侧重无关）
│   ├── general.md
│   └── sites/<site>/SKILL.md         # 一个站点类型一个 skill，格式兼容 Hermes / agentskills（见 research-hermes.md §5）
├── companies/<company>.md            # 公司记忆（跨 Project 共享）：招聘项目、额度规则及证据、已用额度、账号标识、排除原因
├── jobs-index.md                     # 生成：所有 Project 的岗位与申请总台账（防止跨 Project 重复投递）
├── settings/                         # 应用设置，见 ui.md「模型设置」
│   ├── models.yaml                   # 模型供应商、接口格式、Base URL、模型名、用途分配；只写 key 的变量名
│   └── .env                          # API key（用户确认：保存在本地 .env）；权限 600，被 storage/.gitignore 忽略，开发代理不读
├── browser/sites.md                  # 站点登录登记（全局，因为浏览器只有一个）
├── uploads/                          # MCP 允许读取的上传暂存区（等字节副本）
├── checkpoints.sqlite                # LangGraph4j 持久化 Checkpoint（sqlite-saver），按 session-id 区分
├── index.sqlite                      # 可重建的查询索引（台账、Dashboard、题库 FTS5），删掉也能从文件重建
└── exports/                          # 全局台账的 csv（各 Project 的 csv 在各自 tracker/ 下）
```

### 为什么这样分

| 放在哪里 | 内容 | 理由 |
| --- | --- | --- |
| `base/` | 一个人不会变、或者变了所有侧重都要跟着变的东西 | 改一次，所有 Project 继承 |
| `projects/<id>/` | 侧重相关的：简历版本、经历的强调方式、某个时间点的信息快照、偏好 | 并行的两份简历互不污染 |
| 全局（`playbook/`、`companies/`、`jobs-index.md`、`browser/`、`settings/`） | 和侧重无关、但和这个**人**有关的东西 | 额度、投递历史和登录账号是按人算的。如果放在 Project 里，两个 Project 可能重复投同一个岗位，或者把一家公司的额度用超 |

## 继承与解析

`project.md` frontmatter：

```yaml
id: ai-infra-2025
title: AI Infra / 平台后端（2025 版信息）
inherits: [base]                      # 也可以是 [base, projects/backend-2024]，形成时间线
base_pin: live                        # live = 跟随 base 最新；或 storage git 的某个 commit / 日期，冻结在那一刻
default_resume: resume-ai-2025        # 引用 base/assets.md 的键
status: active | archived
```

填表时取值顺序（先命中先用）：

```text
本 Session 中用户确认的答案
  → 本 Project 的 overrides.md
    → 父 Project（按 inherits 顺序）
      → base（按 base_pin 取当时版本）
```

- 每个字段在填写报告里**都标出来源层**，比如「来自 Project ai-infra-2025 / overrides，2026-10-13 确认」，方便你在关卡 B 一眼看出是哪一层给的值。
- overrides 可以显式写 `unset`，表示「这个侧重下不要用 base 的这条」。
- `base_pin` 固定到某个时间点时，就算 base 以后更新了，这个 Project 也不受影响，正好对应「这个 Project 用的是 2020 年那一版信息」。
- 父子 Project 之间不允许出现循环继承，后端加载时要检测。

## Session 与 Job

- **Checkpoint 有两类线程**，都存在 `checkpoints.sqlite`：
  - 对话线程：thread id = session id，管这次对话；
  - 申请线程：thread id = job id，管一个岗位的申请流程。申请可以在任何 Session 里暂停和继续。

  关闭应用、隔天再打开，都能从暂停点继续，这取代了旧流程里手写的「明日继续」文件。
- 对话原文完整保存在 `transcript.jsonl`。给模型的上下文可以压缩，但原文不压缩。
- **候选池**：调研产出的岗位写成 `jobs/<job-id>/job.md`，`lead_status` 取值为 `new`（新发现）/ `shortlisted`（你标记想投）/ `skipped`（不投，注明原因）/ `converted`（已转为申请）。生成的 `tracker/leads.md` 按公司分组，同一家公司的多个岗位排在一起，同时生成 `tracker/leads.csv`。
- **转为申请**：你在对话里说「投澜川的这两个」，或者在候选池里勾选，对应的 Job 就从候选转为申请（`phase: prep`）。转换前先查 `jobs-index.md` 和 `companies/<company>.md`：别的 Project 是否已经投过这个岗位，这家公司的额度还剩多少（未核实就写未核实）。命中时提示你，由你决定是否继续。
- **Session 结束时的交接摘要**写进 `session.md`，下次开新 Session 时 Agent 先读它。

## 新建 Project（用户已确认，2026-10-22）

流程：**继承 + 用户上传文件或输入文字 → Agent 提取 → 用户核实 → 写入**。

1. **选继承**：选 `inherits`（base，或者 base 加某个已有 Project），选 `base_pin`（跟随最新，或固定在某个时间点）。
2. **上传或输入**：上传这个 Project 要用的简历和资料（Word / PDF / Markdown / 纯文本），或者直接粘贴一段文字。上传的原件存进 `base/originals/`，登记到 `base/assets.md`；本 Project 的 `assets.md` 只引用它。
3. **提取**：后端用文档读取工具把文件转成文本，Agent 从中提取候选事实（教育、经历、项目、技能、链接），并**和继承来的事实做对比**：
   - 新增：继承里没有的；
   - 不同：和继承的值不一样（例如学位写法、项目日期）；
   - 相同：直接沿用。
4. **核实**：用「带选项提问」逐组给你确认（参考 Hermes 的 clarify：每个问题给出选项，并且总有「其它」）。「不同」的条目由你选：写进本 Project 的 `overrides.md`（只影响这个 Project）、更新 base（影响所有跟随 base 的 Project）、或者写进 `conflicts.md` 以后再定。
5. **写入**：确认后写文件，并在 `storage/.git` 提交一次。C 级内容（证件号、工作授权等）即使出现在文件里也**不提取、不写入**。

### 文档读取工具

| 格式 | 库（Maven Central 最新版，2026-10-22 查询；施工时钉精确版本） |
| --- | --- |
| 统一入口（自动识别类型） | Apache Tika `tika-core` / `tika-parsers-standard-package` 4.1.0 |
| PDF | Apache PDFBox 3.0.8（Tika 内部也用它） |
| Word（docx） | Apache POI `poi-ooxml` 6.0.0（Tika 内部也用它） |
| Markdown / 纯文本 | 直接读取 |

- 倾向只引入 Tika 一个入口，由它统一处理各种格式。
- 扫描件 PDF（图片，没有文字层）需要 OCR，**后置**；遇到时提示你换成文字版或直接粘贴文字。
- 提取结果只用于生成候选，原文件不修改。
- Hermes 的 `productivity/ocr-and-documents` skill 是 Python 实现，只作参考。

## 站点在线简历与备份（遗漏检查第 2、8 项，已确认）

- **在线简历**：`companies/<company>.md` 里按账号记录这个账号在线简历的状态：最近一次完善和核对的时间、已核对的栏目（教育、项目、附件等）、已知的解析问题。同一账号再投时，先只读核对，只补差异，不重复全量填写。
- **备份**：`storage/` 不上传。首次使用引导里建议开启 Time Machine，备份 `storage/`。

## 事实条目格式

```markdown
## education.masters.program_name
- value: <值>
- class: A | B                       # 字段分级，C 级不允许出现在任何文件里
- source: user-confirmed | resume:<键> | portfolio-meta:<路径> | site-observed
- confirmed_at: 2026-10-13
- valid_from / valid_to: 2025-01 / —  # 用于时间线：这条信息从什么时候起成立
- precision: year-month               # 不补造精度
- notes: 与旧简历写法不一致，见 conflicts.md#degree-name
```

- 精度只能保持或降低，不能凭空提高。
- 同一含义的字段不能互相替代：degree、major、college、当前居住地、籍贯、意向城市各自独立。
- 冲突写进 `conflicts.md`，填表时提示你确认，不自动选一个。

## 字段分级

| 级别 | 例子 | 存放 | 填写方式 |
| --- | --- | --- | --- |
| A 普通事实 | 学校、专业、技能、项目描述、链接 | base / overrides | Agent 自动填写，在关卡 B 报告中列出 |
| B 个人信息 | 姓名、手机、邮箱、照片、精确日期、地址 | base / overrides（`class: B`） | **Agent 能填的统一填完，再交给你在关卡 B 审核**（用户已确认，2026-10-22）；报告里高亮这一类字段 |
| C 敏感 / 法律 | 证件号、工作授权、签证担保、人口统计、健康、薪资期望、法律声明 | **不存** | Agent 不填；人工接力，由你在浏览器里亲自填写，之后只读核验 |

## Memory 写入（候选 → 确认 → 写入）

1. Session 结束（或你回报结果）后，Agent 提出候选，分为三类：
   - 个人事实
   - 通用流程经验
   - 站点观察
2. 后端检查候选：是否重复、是否冲突、是否属于 C 级（是就丢弃），作用域是否正确。
3. 候选**默认写回本 Project**（`candidates.md` → 你确认 → `overrides.md`）。要写进 `base/`，必须你明确同意。候选**不会横向复制到兄弟 Project**。
4. 站点观察直接写进 `playbook/sites/`，标注 `observed_count`；在 ≥2 类站点上出现过、并经你同意后，才升级到 `general.md`。
5. 每次写入都在 `storage/.git` 中自动提交一次。

## Job 记录（`job.md` frontmatter）

```yaml
id: 2026-10-22-001
project: 2025-fall-v2
company: <公司>                       # 对应 companies/<company>.md，同一公司可以有多个 Job
discovered_in: <session-id>            # 在哪个 Session 里作为候选发现
source: web-search | ats-api | user-link
lead_status: converted                # new / shortlisted / skipped / converted
skip_reason: null
site: moka | feishu | self-campus | workday | ...
recruitment_project: <招聘项目或批次>
title: <岗位名>
job_id: <官方 ID>
type: campus-fulltime | intern | experienced
city: [<城市>]
url: <岗位链接>
history_url: <官方投递历史页>
phase: interview                      # 只在 lead_status=converted 后才有；固定阶段，由 rounds 推导，见 tracking.md
prep_state: null                      # 只在 phase=prep 时使用：preparing / needs_input / awaiting_review …
outcome: null                         # 只在 phase=closed 时使用：accepted / rejected / withdrawn …
rounds:                               # 只放摘要；每一轮的详情在 rounds/ 目录下
  - { seq: 1, name: 一面, type: technical, happened_at: ..., result: passed, result_source: user }
next_event: { what: 三面, at: ... }
score: 8.1
eligibility: meets | equivalent_pending | hard_gap
materials:
  resume: { asset: resume-ai-2025, upload_name: <文件名>, sha256: <哈希> }
  attachments: [...]
authorization: { scope: job | batch:<id>, granted_at: ..., covers: [select, materials] }
evidence:
  submitted: { kind: success-page | history-record, text: "<官网原文>", official_time: ..., local_time: ..., verified_at: ... }
last_checked_at: 2026-10-22T10:00:00-04:00
next_action: <下一步>
blocker: <阻塞原因>
```

`jobs-index.md`、各 Project 的 `tracker/`、`review/` 下的生成文件，都由后端从这些 frontmatter 和 `rounds/` 生成，并注明「生成文件，请勿手改」。表格栏目与 Dashboard 见 [tracking.md](tracking.md)。

## 存储格式

### 主流的本地 Memory 存储方式（参考，2026-10-22）

| 方式 | 典型代表 | 优点 | 缺点 |
| --- | --- | --- | --- |
| 1. 纯 Markdown 文件 | Claude Code 的 `CLAUDE.md` 和 memory 目录、Codex 的 `AGENTS.md`、Cursor rules；你旧工作区的 PROFILE / WORK_BY_STEPS | 人和 AI 都能直接读；git 能看差异；零依赖 | 没有结构，程序难以准确更新；容易膨胀和重复（旧工作区的痛点） |
| 2. Markdown + YAML frontmatter | Obsidian 笔记、Claude Code 带 frontmatter 的 memory 文件、静态站点内容 | 兼顾可读和结构：frontmatter 能被程序解析和校验，正文自由书写 | 跨文件查询（统计、搜索）要靠程序扫描全部文件 |
| 3. JSON / JSONL | 事件溯源日志、对话记录；MCP 官方 memory server 用 JSONL 存知识图谱 | 只追加，适合记录历史；易于重放 | 不适合人去编辑 |
| 4. SQLite（+ FTS5 全文检索） | 大量本地应用和 Agent；LangGraph / LangGraph4j 的 SQLite checkpointer；Spring AI 的 JDBC ChatMemory | 查询、聚合、全文检索都很快；单个文件；支持事务 | 人和 AI 不能直接读；git 看不了差异 |
| 5. 本地向量库 | Chroma、LanceDB、Qdrant 本地模式、sqlite-vec；mem0、Letta 的 archival memory | 语义检索，例如「找相似的题目」 | 结果不精确、难以溯源；要有 embedding 模型（会把文本发给外部服务，除非用本地模型）；对小数据量来说是过度设计 |
| 6. 知识图谱 | Graphiti / Zep、Neo4j、MCP memory server | 适合实体关系复杂、随时间演变的数据 | 运维和建模成本高 |
| 7. Memory 框架 | mem0（自动抽取事实，存进向量库，可选图）、Letta / MemGPT（core memory 块 + archival + recall）、LangMem（按命名空间的 KV + 可选向量） | 开箱即用的「抽取 → 存储 → 检索」 | 自动抽取不经过人确认，和本项目「候选 → 确认 → 写入」以及 C 级字段不落盘的规则冲突；大多是 Python 生态 |

### 本项目的方案：文件是事实来源，SQLite 是可重建的索引（用户已同意，2026-10-22）

| 数据 | 格式 | 理由 |
| --- | --- | --- |
| 个人事实、Project 覆盖、偏好、Playbook、公司记忆、Job 记录、Session 摘要、轮次复盘、经验总结 | **Markdown + YAML frontmatter**（方式 2） | 这些是需要你确认、阅读、偶尔手改的内容；git 能看差异；AI 直接可读 |
| 事件日志、对话原文 | **JSONL**（方式 3） | 只追加，可追溯，也可以重放 |
| 台账、Dashboard 数据、题库检索、跨 Project 去重 | **SQLite 索引**（方式 4，`storage/index.sqlite`），含 FTS5 全文检索 | 后端启动时（或文件变化时）从上面两类文件**重建**；删掉也不丢数据。Dashboard 的聚合和「搜一道题」都靠它 |
| LangGraph4j Checkpoint | **SQLite**（`langgraph4j-sqlite-saver` 1.9.6，Maven Central 已确认存在；`storage/checkpoints.sqlite`） | 官方模块，可以跨天恢复；Checkpoint 只是运行状态，不是记忆，不需要人读 |
| 生成视图（台账、题库、`dashboard.md`） | Markdown | 给你和 AI 看的，每次重新生成 |

规则：
- **写入顺序**：先写文件，再更新索引；然后提交到 `storage/.git`。索引和文件不一致时，以文件为准，重建索引。
- **SQLite 驱动**：`org.xerial:sqlite-jdbc`（Maven Central 最新 3.53.4.0，施工时钉精确版本）。
- **对话记忆**：Spring AI 2.0.1 有 `spring-ai-model-chat-memory-repository-jdbc`，可以把模型上下文窗口接到 SQLite；原文仍以 `transcript.jsonl` 为准。是否采用，施工时再定。
- **Obsidian 兼容**（用户要求）：`storage/` 可以直接作为 Obsidian vault 打开浏览。
  - 文件内的链接用相对路径的 Markdown 链接，Obsidian 和 GitHub 都能识别，并显示在反向链接和关系图里。
  - frontmatter 的常用字段尽量平铺（`company`、`phase`、`project`、`tags`、日期等），这样能在 Obsidian 的 Properties 面板里正常显示；嵌套结构（`materials`、`evidence`、`rounds`）只给程序使用。
  - 用 `tags` 字段标注 `jobapply/session`、`jobapply/round`、`topic/system-design` 等，在 Obsidian 里可以按标签筛选。
  - 生成的文件（台账、题库、`dashboard.md`）也是普通 Markdown 表格，在 Obsidian 里直接可读。
  - `.sqlite`、`.jsonl` 文件 Obsidian 默认不显示，不影响浏览。
  - `.obsidian/` 配置目录写进 `storage/.gitignore`。
  - 不依赖任何 Obsidian 插件。以后可以选用 Dataview 做查询，但这不是必需的。
- **向量检索后置**：题库归并先靠模型建议 + 你确认 `canonical`。以后题目多了要做相似题检索，再加 sqlite-vec，并且只用本地 embedding 模型，不把复盘内容发到外部。

### 借鉴 Hermes 的约束（见 [research-hermes.md](../reference/research-hermes.md) §4、§5）

- **字符预算**：`base/facts.md`、Project `overrides.md`、`playbook/general.md` 这些会注入上下文的文件各设字符上限；超出时提示整理，不让它们越写越长。
- **写入前检测**：Memory 候选和 Playbook 修补在写入前检测注入指令和不可见 Unicode，来自 JD 或网页的内容尤其需要。
- **冻结快照**：Session 开始时把有效资料冻结成快照放进上下文；中途确认的新事实从下一轮生效。
- **事实与流程分开**：事实写进 Memory，流程写进 Playbook skill；写陈述句，不写命令句。
- **修补优先**：复盘出来的站点经验，优先修补已有的站点 skill，不新建；修补同样要你确认。
- **历史检索**：`index.sqlite` 用 FTS5 支持「搜历史 Session 和复盘」。

## 不做

- 现在不做向量检索（见上方「存储格式」的后置说明）。
- 不照搬 Hermes 的后台自动写 memory / skill。
- 原件只在 `base/originals/` 保存一份；Project 不复制原件，`uploads/` 只放上传时临时的等字节副本。
- 模型不直接写 `storage/` 下的任何文件，一律通过后端 Tool 提交。

## 待用户决定

已确认（2026-10-22）：
1. Project 创建时用 `inherits`（加 `base_pin`），再由用户上传文件或输入文字核实，见「新建 Project」。
2. Session 类型改为自动打标签（见「修订：Session 与岗位解耦」）。
3. `storage/` 用本地 git 保留版本，不设远程、不上传。
4. B 级个人信息：Agent 能填的统一填完，再由用户在关卡 B 审核。
5. 原件保存在 base（`base/originals/`），其它内容跟随各 Project 的 memory。

待用户决定：无。
