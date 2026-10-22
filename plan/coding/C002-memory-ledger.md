# C002 · Memory 与台账：storage 读写、继承解析、状态机、台账生成

- 对应 Master：[workflow.md](../master/workflow.md)「里程碑」第 1 段；[memory.md](../master/memory.md)（storage 目录、继承与解析、Session 与 Job、事实条目格式、字段分级、Job 记录、存储格式）；[tracking.md](../master/tracking.md)（阶段与轮次、台账表格）
- 状态：**完成**（用户已验收，2026-10-22）。日期：2026-10-22。

本文件只写这一次施工做什么、怎么做、怎么验收。设计依据在上面三份 Master，不在这里重复。

## 目标

只提供纯 Java 的后端能力，用单元测试固定下来。存储根目录用临时目录（`@TempDir`）。不接模型、浏览器、HTTP，也不接 Spring Bean。

可观察行为（输入 → 状态变化 → 输出）：

1. **初始化 storage**：传入一个目录和 git 开关 → 建好骨架和 `storage/.gitignore`。开关打开时，执行 `git init` 并做一次提交；关闭时不建 `.git`，也不用其他方式保留版本。再次初始化不产生新提交。
2. **建 Project**：给出 id、标题、`inherits`、`base_pin`、`default_resume` → 写出 `project.md`、`overrides.md`、`preferences.md`。父 Project 不存在时报错，不写任何文件。
3. **候选池**：在某个 Session 里新增候选 Job → 写出 `jobs/<job-id>/job.md`（`lead_status: new`），`events.jsonl` 追加一条，重新生成 `tracker/leads.md` 和 `tracker/leads.csv`，开启 git 时提交到 `storage/.git`。「公司 + 招聘项目 + 岗位 ID」重复时拒绝，并返回已有的 job id。
4. **状态机**：候选可以标记为想投、不投（必须写原因）、转为申请；申请可以推进 `prep_state`、`phase`、`outcome`。非法跳转会被拒绝，拒绝时文件、事件、台账、git HEAD 都不变。没有证据不能进入 `applied`。
5. **台账**：每次状态变化后，重新生成本 Project 的 `leads` / `applications` 两组 md + csv，以及全局的 `jobs-index.md` 和 `exports/applications.csv`。
6. **Session 记录**：建 `session.md`，追加 `transcript.jsonl`，按本 Session 的事件自动打标签，写入交接摘要。
7. **继承解析**：给定 Project（可选再给本 Session 已确认的答案）→ 得到有效资料。每个字段带值、分级和来源层。规则包括 `unset`、`base_pin`（live；开启 git 时还可以是 commit / 日期），并检测循环继承。

## 不做（留给后续里程碑）

- 轮次写入、`tracker/rounds.*`、unknown 静默检测、`dashboard.md`、`review/`：属于第 6 段。本段 applications 表的「进度」「同步」两列先输出 `—`，表头保持最终形状。
- `index.sqlite`、FTS5、`checkpoints.sqlite`、LangGraph4j 图。
- Memory 候选流程（`candidates.md` → 确认 → 写入）、新建 Project 时的文档提取（Tika）、字符预算、注入检测。
- `companies/`、额度、转为申请时的跨 Project 重复提示（第 5 段）；`playbook/`、`browser/`、`settings/`、`uploads/`、`cache/`。
- Spring 配置、`api/`、`tools/` 中的 Agent Tool 封装。
- 不在真实的 `storage/` 上运行任何初始化或写入。测试只用临时目录。

## 依赖（用户已确认，2026-10-22）

| 用途 | 坐标 | 说明 |
| --- | --- | --- |
| 本地 git 自动提交，按 commit 读历史版本（`base_pin`） | `org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r` | **唯一新增的构件**。2026-10-22 查 Maven Central `maven-metadata.xml`，是最新 release。**git 是可选项**：用户可以开启或不开启；不开启时不用其他版本方式，也不调用系统 `git` 命令 |
| YAML frontmatter | `org.yaml:snakeyaml`（Boot 管理版本，当前解析为 2.6） | 已经通过 `spring-boot-starter` 传递引入。改为显式声明，不写版本号 |
| JSONL、Map 与 record 互转 | `tools.jackson.core:jackson-databind`（当前解析为 3.1.5） | 已经通过 Spring AI 传递引入。改为显式声明，不写版本号 |
| CSV | 不引入库 | 自己写，按 RFC 4180 转义 |
| 测试 | JUnit 5 + AssertJ（`spring-boot-starter-test`） | 已有 |

## 要改的文件

类名在施工中可以微调，职责不变。不新增子包。

**`backend/pom.xml`**：加上表中三项。

**`jobapply.domain`**（业务结构与规则，不碰文件）
- `LeadStatus`、`Phase`、`PrepState`、`Outcome`、`FactClass`：枚举，取值与 Master 一致。
- `Job`：record，对应 `job.md` 的 frontmatter。保留未识别的键，用户手改后再写回时不丢字段。
- `Evidence`、`JobEvent`、`ProjectSpec`、`Fact`、`SessionRecord`：record。
- `ApplicationStateMachine`：纯函数。输入当前 `Job` 和一次跳转请求，输出新的 `Job`，或抛出 `IllegalTransitionException`（带原因）。

**`jobapply.persistence`**（文件读写、git、生成视图）
- `StorageLayout`：所有路径从这里取，不在别处拼路径。
- `StorageInitializer`：建骨架、写 `.gitignore`、`git init`、做首个提交。
- `StorageGit`：只在开启 git 时起作用。只提交本次操作写过的路径（不做 `git add -A`），并能按 commit 或日期读取历史版本的文件。
- `FrontmatterFile`：读写「YAML frontmatter + Markdown 正文」，保留正文、键的顺序和未识别的键。
- `FactsFile`：读写 `facts.md` / `overrides.md` 的事实条目。
- `EventLog`：追加和读取 `events.jsonl`、`transcript.jsonl`。
- `ProjectRepository`、`JobRepository`、`SessionRepository`：读写各自的文件。
- `TrackerWriter`：从全部 `job.md` 生成 md 和 csv 两种台账。
- `JobLedger`：Job 写操作的唯一入口。一次调用就是一个工作单元，依次是「状态机校验 → 写 `job.md` → 追加事件 → 重新生成台账 → git 提交（只在开启 git 时）」。
- `StorageFormatException`：文件格式错误，带文件路径。

**`jobapply.memory`**
- `ProfileResolver`：继承解析和循环检测，输出 `ResolvedFact`（值、分级、来源层、确认日期）。

**测试** `backend/src/test/java/jobapply/{domain,persistence,memory}/…`：见「测试」一节。

**文档**：完成后补本文件的「实际结果」和「偏差」，并更新 `dev-plan.md` 和 `plan/README.md` 的索引行、`dev-log.md`、`dev-recoding.md`。如果下面「待确认」里涉及 Master 的条目获批，回写对应的 Master。

## 关键规则（施工时照此实现）

### 文件与格式

- **原子写入**：先写到同目录下的临时文件，再用 `ATOMIC_MOVE` 替换。
- **YAML 时间不转成 `Date`**：SnakeYAML 默认会把 `2026-10-22T10:00:00-04:00` 解析成 `java.util.Date`，时区偏移会丢。所以要用自定义 Resolver，让时间保持字符串，再转成 `OffsetDateTime`。写出时间时统一带偏移。
- **frontmatter 字段**：常用字段平铺（`id`、`project`、`company`、`phase`、`lead_status`、`tags` 等）。`materials`、`evidence`、`authorization`、`rounds` 保持嵌套。`tags` 写 `jobapply/job`、`jobapply/session`、`jobapply/project`。
- **`job.md` 正文**：建候选时写出这些空标题：`## JD 快照`、`## 资格映射`、`## 本次答案`、`## 授权`、`## 证据`。调用方可以传入 JD 快照文本。之后更新 frontmatter 时，正文原样保留。
- **事实条目**：沿用 memory.md「事实条目格式」，即 `## <key>` 下面跟 `- 字段: 值`。读和写时遇到 `class: C` 都直接报错。`overrides.md` 里用 `- unset: true` 表示屏蔽继承来的同名条目。
- **生成文件**：首行写 `> 生成文件，请勿手改。`，不写生成时间，避免每次重新生成都产生无意义的 diff。
- **csv**：UTF-8 带 BOM（Excel 才能正确识别中文），行尾用 CRLF，按 RFC 4180 转义。以 `= + - @` 开头的单元格前面加 `'`，防止公式注入（JD 标题来自网页）。嵌套字段展开成点号列名（如 `evidence.submitted.text`），列表用 `; ` 连接。列的集合固定，表头稳定。

### ID

- **job id**：`YYYY-MM-DD-NNN`，跨所有 Project 按日期连续编号，因为它同时是申请线程的 thread id，必须全局唯一。编号时扫描现有的 Job 目录。本段假定只有一个进程，在 `JobLedger` 内部串行。
- **session id**：`s-YYYY-MM-DD-NNN`。
- **project id**：用户给出，只允许小写字母、数字和 `-`。
- 时间一律取自注入的 `Clock`，测试用固定时钟。

### storage 初始化

- 建立：`.gitignore`、`base/facts.md`、`base/conflicts.md`、`base/originals/`、`projects/`、`exports/`、`jobs-index.md`（空表）。其它目录由各自的里程碑创建。
- `.gitignore` 包含 memory.md 列出的 `settings/.env`、`uploads/`、`*.sqlite`、`.obsidian/`，**另加 `example.data/`**，保证真实数据不会进入 `storage/.git`（见「待确认」）。
- **git 开关**：`storage/.git` 存在就视为已开启，不另设配置文件。`StorageInitializer` 接受 `git: on | off`；之后可以单独调用 `enableGit()`，它会 `git init` 并把当前状态提交一次。本段**不提供关闭 git 的方法**，因为关闭等于删除版本历史，由用户自己处理。用户在哪个界面选择这个开关，留给第 7 段（前端）。
- JGit 提交时显式设置作者 `JobApply <jobapply@localhost>`，并关闭签名，不受用户全局 `~/.gitconfig` 影响。

### 候选池

- 去重键：公司、招聘项目、岗位 ID 各自去掉首尾空格并转小写后拼接。没有岗位 ID 时，改用去掉查询参数和片段后的 URL。
- `new → shortlisted | skipped`；`shortlisted → skipped | new`；`skipped` 必须带 `skip_reason`；`new | shortlisted → converted`。转为申请后，`phase: prep`、`prep_state: selected`。`converted` 不可撤回。

### 状态机（`prep_state`，只在 `phase=prep` 时存在）

| 从 | 可以到 | 依据 |
| --- | --- | --- |
| `selected` | `preparing`、`paused` | workflow 图 |
| `preparing` | `needs_input`、`needs_login`、`handoff_user`、`failed`、`awaiting_review`、`paused` | workflow 图 |
| `needs_input` / `needs_login` / `handoff_user` / `failed` | `preparing`、`paused` | 图中的 ↕ |
| `awaiting_review` | `user_submitting`、`preparing`*、`paused` | *推断：关卡 B 时用户要求修改 |
| `user_submitting` | `verification_pending`、`unconfirmed` | workflow 图 |
| `verification_pending` | `unconfirmed` | workflow 图 |
| `unconfirmed` | （只能凭证据进入 `applied`） | *推断 |
| `paused` | `selected`、`preparing`* | *推断：恢复后先只读核对，所以不回到中间状态 |

- **进入 `phase=applied`**：只能从 `user_submitting`、`verification_pending`、`unconfirmed` 进入，并且必须附带 `evidence.submitted`（`kind` 为 `success-page` 或 `history-record`，`text`、`local_time`、`verified_at` 都不能为空）。进入后清空 `prep_state`。
- `user_submitting` 和 `verification_pending` **不能**转为 `paused`，因为提交可能已经发生了（推断）。
- **`phase` 在 applied 之后**：`screening`、`assessment`、`interview`、`offer` 之间可以互相跳转（最新一轮可能是面试之后的笔试），不能回到 `prep` 或 `applied`。
- **`closed`**：必须带 `outcome`。`not_submitted` 只能从 `prep` 进入；`accepted`、`declined` 只能从 `offer` 进入；`rejected`、`withdrawn`、`ghosted` 只能从 `applied` 及之后的阶段进入；`ghosted` 要求 `actor=user`。`closed` 是终态，本段不支持重新打开（推断）。
- 非 `closed` 时不能有 `outcome`；非 `prep` 时不能有 `prep_state`。

### 事件

每次写操作都在 `events.jsonl` 追加一行：`{at, session, actor, type, from, to, reason?, evidence?}`。其中 `actor ∈ user | agent | system`；`type ∈ lead_created | lead_status_changed | converted | prep_state_changed | phase_changed | closed`。`session` 必填。

### Session

- `session.md` 的 frontmatter：`id`、`project`、`title`、`created_at`、`tags`、`jobs`。正文：`## 交接摘要`。
- 自动标签从本 Session 的事件推导：有 `lead_created` 就打 `research`；有 `converted`、`prep_state_changed`、`phase_changed`、`closed` 中的任意一种就打 `apply`。`review` 要等第 6 段有了轮次事件才会出现。
- `jobs` 由本 Session 的事件里出现过的 job id 推导，不需要手工维护。

### 继承解析

- 取值顺序：本 Session 的答案 → 本 Project 的 `overrides.md` → 父 Project 的 `overrides.md`（按 `inherits` 顺序，读当前版本）→ base 的 `facts.md`（按**本 Project** 的 `base_pin` 取版本）。`base` 永远是最后一层（推断，见「待确认」）。
- `inherits` 必须包含 `base`。父 Project 必须存在。沿 `inherits` 深度优先遍历，出现环就报错，错误信息列出整条环（如 `a → b → a`）。
- `base_pin`：没有开启 git 时只能是 `live`，建 Project 时传入别的值会被拒绝；加载时如果发现不是 `live`，就报错并说明「需要开启 git」。`live` 读工作区里的文件；commit 读那个提交里的 `base/facts.md`；日期 `YYYY-MM-DD` 读当天结束前（本地时区）的最后一个提交。找不到对应版本时报错，不回退到 live。
- 来源标签的格式：`来自 Project <id> / overrides，<confirmed_at> 确认`、`来自 base（pin <x>）`、`来自本 Session 答案`。

### 台账

- **`tracker/leads.md`**：tracking.md 没有定义栏目，本段提议为：公司 | 岗位 | 城市 | 类型 | 状态（`lead_status`）| 评分 | 资格 | 发现于（Session）| 链接 | 不投原因。按公司分组，同一家公司的岗位排在一起；`converted` 的排到最后。
- **`tracker/applications.md`**：使用 tracking.md 表 1 的 13 列。「岗位」链接到 `job.md`，不链接到 Session，因为 Session 与 Job 已经解耦。分组顺序：`prep → interview → assessment → screening → applied → offer → closed`。
- **`jobs-index.md`** 和 **`exports/applications.csv`**：全局版本，多一列 Project，包含候选和申请。
- md 里的链接一律用相对路径，Obsidian 和 GitHub 都能识别。

## 实现步骤（5 个垂直切片，每片结束时 `mvn -B clean test` 全绿）

1. **storage 与 git**：`StorageLayout`、`StorageInitializer`（git 开 / 关两种初始化）、`StorageGit`、`FrontmatterFile`，加上 pom 依赖。
2. **第一条完整路径**：用 `ProjectRepository.create` 建 Project，用 `SessionRepository.create` 建 Session（只写 `session.md` 的骨架），然后用 `JobLedger` 新增、标记、转换候选，生成 `leads.md/csv` 并提交。
3. **状态机**：`ApplicationStateMachine`，接入 `JobLedger` 的跳转方法，生成 `applications.md/csv`、`jobs-index.md`、`exports/applications.csv`。
4. **Session 完整版**：`transcript.jsonl` 追加、自动标签、`jobs` 推导、交接摘要。
5. **继承解析**：`FactsFile`、`ProfileResolver`，包括 `base_pin` 的三种形式和循环检测。

## 测试

全部使用虚构数据（例如「示例公司甲」「Backend Engineer」），不引用 `storage/example.data/`，确定性运行，不访问网络。

| 测试类 | 固定的行为 |
| --- | --- |
| `StorageInitializerTest` | 开启 git：生成骨架和 `.gitignore` 的各行，产生 1 个提交；第二次初始化不产生新提交；目录里预先放一个 `example.data/x.md`，它不出现在提交树中。关闭 git：骨架相同，没有 `.git`。先关后调用 `enableGit()`：产生 1 个提交，包含已有的文件 |
| `StorageGitTest` | 只提交指定的路径，手改的其它文件不会被带进提交；按 commit 和按日期都能读到旧版本 |
| `FrontmatterFileTest` | 读写往返后正文、键顺序、未识别的键都不变；时间保留时区偏移；缺少结束的 `---` 时抛出 `StorageFormatException`，并带上路径 |
| `FactsFileTest` | 能解析条目的各个字段；`class: C` 读写都报错；能识别 `unset` |
| `JobLedgerLeadTest` | 新增候选后，id 为 `2026-10-22-001`，`job.md`、事件、`leads.md/csv`、提交都齐全；重复时拒绝并返回已有的 id；不投却不写原因时拒绝；转为申请后是 `phase=prep`、`prep_state=selected`；第二个 Project 当天的下一个 Job 编号为 `-002` |
| `ApplicationStateMachineTest` | 用表驱动测试覆盖上面状态表里的每一条合法跳转，以及各类非法跳转；没有证据、证据不完整时进入 `applied` 都被拒绝；`outcome` 的约束；离开 `prep` 时清空 `prep_state`；`closed` 是终态 |
| `JobLedgerAtomicityTest` | 非法跳转之后，`job.md`、`events.jsonl`、台账文件、git HEAD 都和之前完全一样 |
| `JobLedgerNoGitTest` | 关闭 git 时，新增候选、跳转、生成台账的结果和开启时一样，只是没有提交，也不会报错 |
| `TrackerWriterTest` | applications 的分组顺序；首行声明「生成文件」；csv 有 BOM，逗号、引号、换行都正确转义，`=SUM(1)` 前面加了 `'`；「进度」「同步」两列是 `—`；全局表带 Project 列 |
| `SessionRepositoryTest` | 自动标签 `research` / `apply`；`jobs` 推导；transcript 逐行追加；交接摘要写入正文 |
| `ProfileResolverTest` | 四层的优先级和来源标签；`unset` 能屏蔽 base 的条目；base 更新后，pin 到旧 commit 的 Project 仍然得到旧值；pin 到日期的情况；`a → b → a` 报错并列出整条环；父 Project 不存在时报错；`inherits` 缺少 `base` 时报错；关闭 git 时 `base_pin` 不是 `live` 会被拒绝 |
| `JobApplyApplicationTest` | 原有测试仍然通过 |

## 验收标准

- 在 `backend/` 执行 `mvn -B clean test`，退出码 0，上表所有测试类都通过，没有跳过的测试。
- 主仓库的 `git status` 只多出本步计划内的文件；`storage/` 的内容没有变化（没有 `storage/.git`）。
- 测试源码和测试资源里搜不到 `example.data`，`StorageInitializerTest` 里用于验证 `.gitignore` 的那一处除外。
- 没有新增本文件以外的依赖、包或目录。

## 决定记录

**全部已确认（2026-10-22，用户回复「按建议」）**。第 4 项（原本没有写建议）定为：5 片一次做完，每片测试全绿，最后统一汇报。第 5–12 项已回写到 workflow / memory / tracking。


已确认（2026-10-22）：
- 依赖表全部同意：JGit；SnakeYAML 和 Jackson databind 改为显式声明；CSV 不引入库；测试用现有的 JUnit 5 + AssertJ。
- git 是可选项，可以开也可以不开，不开时不用其他方法保留版本。

**施工选择**
3. 分包：状态机放 `domain`；文件读写、git、`JobLedger`、`TrackerWriter` 放 `persistence`；`ProfileResolver` 放 `memory`。不建子包。
4. 是否每完成一个切片就停下来给你看，还是 5 片一次做完再汇报。

**Master 的缺口或歧义**（确认后回写对应的 Master；本段先按左边的理解施工）
5. workflow 里程碑 1 写的是「没有证据不能进入 `submitted`」，但状态机里没有 `submitted`。本段理解为 `phase=applied`。
6. 复盘文件的路径：tracking.md 写的是 `sessions/<s>/rounds/`，memory.md 修订后改成了 `jobs/<job-id>/rounds/`。以 memory.md 为准。
7. `prep_state` 里的 `discovered`、`matched`、`skipped`、`monitoring` 与 `lead_status` 重叠。本段规定：转为申请后 `prep_state` 从 `selected` 开始，转换之前只用 `lead_status`；「监控」留到第 4 段再定。
8. 状态表中标了 * 的推断跳转：`awaiting_review → preparing`、`paused → selected | preparing`、`user_submitting` 和 `verification_pending` 不能暂停、`unconfirmed` 凭证据进入 `applied`、`closed` 是终态。
9. 继承：父 Project 这一层只取它的 `overrides.md`（当前版本）；base 这一层统一按子 Project 的 `base_pin`；`inherits` 必须包含 `base`。
10. 写法和编号：`unset` 写成 `- unset: true`；job id 跨 Project 按日期编号；session id 用 `s-YYYY-MM-DD-NNN`。
11. 栏目：采用上面提议的 `leads` 栏目；applications 的「岗位」链接到 `job.md`。
12. `storage/.gitignore` 在 memory.md 列出的几项之外再加 `example.data/`。

## 追加：测试整理（用户确认，2026-10-22）

用户在验收前提出两项：

1. **合并 JobLedger 测试**：`JobLedgerLeadTest`、`JobLedgerAtomicityTest`、`JobLedgerNoGitTest` 合并为一个 `JobLedgerTest`，用 JUnit `@Nested` 分成三组：候选池、原子性、不开 git。测试内容不变。
2. **端到端场景测试** `jobapply.MemoryLedgerScenarioIT`：在一个临时 storage（开启 git）里走完整条流程：
   - 初始化，写入 base 事实，建 Project，并在 overrides 里覆盖一条；
   - Session 1（调研）：新增 3 个候选，1 个标记不投（写原因），1 个标记想投，记录两句对话，写交接摘要；
   - Session 2（投递）：把想投的那个转为申请，依次经过 preparing、awaiting_review、user_submitting，凭证据进入 applied，再进入 interview，最后以 rejected 结束；
   - 检查：`job.md` 的最终状态；事件日志的条数和各条所属的 Session；两个 Session 的自动标签和 jobs；leads 和 applications 台账；`jobs-index.md` 和 `exports/applications.csv`；继承解析的结果；**工作区是干净的**（每个写过的文件都已提交），提交数与步骤数一致。

   为此 `backend/pom.xml` 加入 `maven-failsafe-plugin`（版本和执行配置由 Spring Boot 父 POM 管理）。`*IT` 只在 `mvn verify` 时运行，所以本机的验证命令从 `mvn -B clean test` 改为 `mvn -B clean verify`，`AGENTS.md`「本机构建」同步修改。

不做：不把其它测试改名为 IT；不改变任何主代码的行为。

## 追加：三方评审修复（用户确认，2026-10-22）

三方评审提出 6 条问题，逐条对照代码后都属实。用户决定修复第 1–5 条，第 6 条（CRLF 换行的 frontmatter）不处理。每条都先补一个能复现问题的测试，看到它失败后再改代码。

| # | 问题 | 修法 | 新测试 |
| --- | --- | --- | --- |
| 2 | 状态更新会整体覆盖 `evidence`，`evidence` 下本段没建模的子字段（如 `qualification`）和 `submitted` 里多出来的字段都会丢 | 写回时在已有的 `evidence` 上合并：只替换 `submitted` 里本段建模的字段，其余原样保留 | `JobLedgerTest.Leads`：手工加的嵌套证据字段在多次跳转后仍然存在 |
| 3 | 用 `../` 形式的 ID 可以跨 Project 改到别的岗位 | 新增 `domain.Ids`，统一定义 project / job / session id 的格式；`StorageLayout` 拼路径时一律校验，不合法就报错；列目录时跳过不合规的目录名 | `JobLedgerTest.InputValidation`：带 `../` 的 jobId 和 projectId 被拒绝，另一个 Project 的文件不变；jobs 目录下放一个不合规的目录，不影响列出岗位 |
| 1 | 自动提交会带上用户事先暂存的其他文件 | 只检查本次路径有没有变化；提交时用 JGit 的 `setOnly` 限定在本次路径，用户暂存的东西仍然留在暂存区 | `StorageGitTest`：事先暂存的文件不进本次提交，提交后仍处于暂存状态；本次路径没有变化时不产生提交 |
| 4 | `actor` 为空时，报错前已经写出了 `job.md` | `JobLedger` 在写任何文件之前先检查参数、构造好事件；`JobEvent` 构造时要求 actor 和 type 不为空 | `JobLedgerTest.InputValidation`：actor、transition 为空时被拒绝，目录快照不变 |
| 5 | 事实写入器能写出自己读不了的文件（重复 key） | 渲染前检查重复 key，有重复就报错，原文件不动 | `FactsFileTest`：重复 key 被拒绝，原文件内容不变 |

不做：第 6 条；不改其它行为。

## 实际结果

- 依赖：`backend/pom.xml` 新增 `org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r`，并显式声明了 `org.yaml:snakeyaml`（解析为 2.6）和 `tools.jackson.core:jackson-databind`（解析为 3.1.5），这两项都不写版本号。
- `jobapply.domain`：`LeadStatus`、`Phase`、`PrepState`、`Outcome`、`Actor`、`FactClass`；`Job`、`JobPosting`、`JobStatus`、`Evidence`、`JobEvent`、`ProjectSpec`、`Fact`、`SessionRecord`、`Timestamps`；`Transition`（sealed 接口）、`ApplicationStateMachine`、`IllegalTransitionException`。
- `jobapply.persistence`：`StorageLayout`、`StorageInitializer`、`StorageGit`、`FrontmatterFile`、`FactsFile`、`EventLog`、`JobRepository`、`ProjectRepository`、`SessionRepository`、`TrackerWriter`、`JobLedger`、`StorageFormatException`、`DuplicateLeadException`；以及包内的 `AtomicFiles`、`CsvWriter`、`IdSequence`。
- `jobapply.memory`：`ProfileResolver`、`ResolvedFact`、`ProfileResolutionException`。
- 测试（第一次实现时）：在 `backend/` 执行 `mvn -B clean test`，退出码 0，Tests run: 166, Failures: 0, Errors: 0, Skipped: 0。各测试类：`ApplicationStateMachineTest` 127（其中 121 个是 prep_state 两两组合的跳转用例）、`JobLedgerLeadTest` 7、`ProfileResolverTest` 6、`StorageInitializerTest` 5、`FrontmatterFileTest` 4、`FactsFileTest` 4、`SessionRepositoryTest` 4、`TrackerWriterTest` 4、`StorageGitTest` 2、`JobLedgerAtomicityTest` 1、`JobLedgerNoGitTest` 1、`JobApplyApplicationTest` 1。
- 测试（追加整理之后）：三个 JobLedger 测试类合并为 `JobLedgerTest`（`@Nested`：`Leads` 7、`Atomicity` 1、`WithoutGit` 1），用例数不变；新增 `MemoryLedgerScenarioIT` 1 个；`backend/pom.xml` 加入 `maven-failsafe-plugin`，`TestStorage` 加了 `isClean()`。在 `backend/` 执行 `mvn -B clean verify`，退出码 0；surefire 166 个、failsafe 1 个，均为 Failures: 0, Errors: 0, Skipped: 0。
- 三方评审修复之后：第 1–5 条各补了一个复现测试，修复前 5 个全部失败（「不合规目录」那个测试修复前就能通过，它是新 ID 校验的回归保护）。主代码改动：新增 `domain.Ids`；`StorageLayout` 校验 id，列目录时跳过不合规的名字；`ProjectSpec` 改用 `Ids`；`JobEvent` 要求 actor 和 type；`JobLedger` 在写文件前检查参数并先构造事件；`JobRepository` 合并写回 `evidence`；`FactsFile` 渲染前检查重复 key；`StorageGit` 只看本次路径的变化，用 `setOnly` 提交。在 `backend/` 执行 `mvn -B clean verify`，退出码 0；surefire 172 个、failsafe 1 个，均为 Failures: 0, Errors: 0, Skipped: 0。
- 人工抽查：保留了一次测试的临时目录，查看生成的 `job.md`、`applications.md`、`events.jsonl`、`session.md`、`git log`、`.gitignore`，内容与本文件的规则一致。看完后已删除这个目录。
- 主仓库的 `storage/` 没有变化（只有 `.gitkeep` 和空的 `example.data/`，没有 `.git`）。

## 偏差

- **施工顺序**：先写完全部主代码，再按 1 → 5 片的顺序写测试，每片的测试跑绿后再写下一片。不是严格的「一片代码加一片测试」交替进行。
- **公司排序**：leads 按公司名的 Unicode 码点排序，例如「乙」(U+4E59) 排在「甲」(U+7532) 前面。分组满足计划要求，但不是拼音顺序。选码点排序，是因为它的结果不依赖 JDK 的本地化规则。
- **比计划多做的**：
  - `ProjectRepository.create` 在新建时一并写出空的 `tracker/` 四个文件。
  - `JobLedger` 每次写操作都会更新 `session.md` 的 tags 和 jobs，和 Job 的改动放在同一个提交里。
  - 项目内的 `applications.csv` 和全局的 `exports/applications.csv` 使用同一组列，其中包含 `lead_status` 和 `skip_reason`，因为全局表也要列出候选。
  - 额外增加了几个测试：已有文件不会被覆盖、事实条目的格式错误、按 URL 去重、未知 Session 被拒绝、手工加的键在写回后保留。
- **`example.data` 的引用**：测试代码里有 3 处，都在 `StorageInitializerTest` 中，用来验证 `.gitignore` 和「`example.data/` 不进入提交」。这 3 处只是路径字符串，没有读取任何真实数据。
- **三方评审**发现了我第一次实现时测试没有覆盖到的边界：嵌套字段的保留、非法 ID、参数缺失、用户事先暂存的文件、写入前的重复检查。修复见「追加：三方评审修复」。CRLF 换行的 frontmatter 按用户决定不处理，这类文件仍会被当成没有 frontmatter。
- **已知限制**：工作单元只在校验阶段保证「失败时什么都不写」。如果写到一半发生磁盘错误，可能留下部分写入的文件。本段假定只有一个进程（见「ID」）。
