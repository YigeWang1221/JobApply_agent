# C004-A-1 · 资料导入（一）：事实格式、按语言取值、来源扫描

- 对应 Master：[memory.md](../master/memory.md)「资料导入与画像」「事实条目格式」「字段分级」「继承与解析」；[workflow.md](../master/workflow.md)「里程碑」第 3 段的前置
- 前置：[C004-A](C004-A-model-guard.md)（原件登记、「资料」页、C 级词表）。
- 后续：[C004-A-2](C004-A-2-profile-review.md)（整理、确认、画像），然后 [C004-B](C004-B-harness-review.md)。
- 状态：**施工完成，等用户找人审核代码和手动检查**（2026-10-22）。用户确认（2026-10-22）：「同意拆分，其他的也同意」。见文末「实际结果」「偏差」。

## 本次规则修订（用户指令，2026-10-22）

按 [base.md](../master/base.md)「求职表达与包装」执行；以下内容已同步修改。本次仅调整文档，尚未施工的规则与测试以本版为准。

## 目标

本段**不调用模型**。做完以后，C004-A-2 需要的几样东西都已经备齐：双语的事实格式和各个文件的读写与校验、按语言取值、只读的来源扫描和 GitHub 读取、导入前的估算。可观察行为（输入 → 状态变化 → 输出）：

1. **事实格式**：`facts.md` 可以成对保存中英文；用户确认的经历、技能、成果和指标不要求关联论断即可保存；`ledger.md`、`base/projects/<slug>.md`、`sources.md`、`evidence/<来源键>.md`、`glossary.md`、`profile.md` 都能读写，并且按「关键规则」校验（小节对应、可选引用的有效性）。
2. **按语言取值**：`ProfileResolver` 可以按 `zh` / `en` 取值；文字字段取对应语言，数字和日期按语言格式输出；取到未审阅的译文时，结果里带标记。继承规则不变。
3. **登记来源**：在「资料」页的「来源」页签输入一个本地目录的绝对路径，或者一个 GitHub 仓库地址 → 后端校验（目录存在且可读；GitHub 仓库是公开的）→ 登记进 `base/sources.md`，返回来源键 `s-YYYY-MM-DD-NNN`。只给 owner 时列出它的公开仓库让用户选。
4. **估算**：选几个来源 → 后端只读扫描，列出会读的文件、跳过的文件（只列名字和原因）、文字量、预计的模型调用次数和 GitHub 接口调用次数 → 建一条导入记录（状态 `estimated`）。本段到这里为止，「开始整理」留给 C004-A-2。
5. **秘密文件绝不读取**：`.env*`、私钥、证书、`credentials*` / `secrets*` 一类的文件只按文件名跳过，不打开。

## 不做

- 清单卡、抽取、合并、确认写入、译文起草、画像生成：都调用模型，放在 C004-A-2。
- Project 层的导入、各 Project 选重点项目、按岗位生成简历、OCR、私有仓库和 token、通用网页抓取、对话式确认、向量检索：同总设计，见 C004-A-2「不做」。

## 关键规则

### 事实条目（`facts.md`，向后兼容 C002 的格式）

```markdown
## experience.internship-1.role
- zh: 后端开发实习生
- en: Backend Engineering Intern
- class: A
- source: user-confirmed | resume:<原件键> | source:<来源键>
- claim: c-0007                  # 可选，已有台账论断时关联
- reviewed: zh, en               # 已经审阅过的语言；模型起草、还没审阅的不在这里
- confirmed_at: 2026-10-22
- valid_from / valid_to: 2025-06 / 2025-09
- precision: year-month
```

- `value`（与语言无关）和 `zh` / `en`（文字）二选一；旧文件只有 `value` 的照常读。
- **各类字段统一按用户确认使用**：`experience.*`、`skills.*`、`outcomes.*`、`metrics.*` 与基础信息一样，用户确认后即可保存。`claim` 可选；填了时检查引用存在。前缀只用于分组，不作为证据门槛；用途由用户决定，不因缺少仓库证据拒绝保存。
- C 级仍然不能构造、不能写入（C002 的约束不变）。

### 论断台账（`ledger.md`）

```markdown
# 论断台账

| id | 类别 | 论断 | 来源 | 出处 | 值与条件 | 状态 | 用途 | 确认时间 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| c-0007 | 经历 | 实习期间负责订单服务的接口开发 | s-2026-10-22-001 | README.md#L12 | — | owner_attested | forms_and_resume | 2026-10-22 |
```

- id 是 `c-NNNN`，全局连续编号，删除后不复用。论断用中文写（台账是内部材料）；对外的文字在事实层成对保存。
- 状态：`verified` / `partial` / `owner_attested` / `discrepancy` / `needs_verification`；用途：`forms_and_resume` / `interview_only` / `none`。
- **默认用途**：用户确认的 `verified`、`partial`、`owner_attested` 均为 `forms_and_resume`，包括指标；`needs_verification` 表示尚待用户确认，确认后可用于简历和填表；`discrepancy` 显示差异，由用户选择采用版本和用途，不自动禁用。出处和测试条件允许留空。
- 读的时候保留未识别的列，做法同 C004-A 的 `assets.md`。

### 项目卡（`base/projects/<slug>.md`）

frontmatter 放与语言无关的字段，正文是两种语言、小节一一对应的描述：

```markdown
---
slug: distributed-training
aliases: [<别名>, ...]
title: { zh: <中文名>, en: <English title> }
kind: course | personal | internship | research | work
period: { from: 2025-01, to: 2025-05, precision: year-month }
team_size: 3
role_claim: c-0012
stack: [PyTorch, Slurm]
links: [{ kind: code, url: https://github.com/<owner>/<repo> }]
facts:
  speedup: { value: "3.9", unit: "×", condition: { zh: <条件>, en: <condition> }, claim: c-0015 }
sources: [s-2026-10-22-001, a-2026-10-22-002]
reviewed: [zh]
---

## zh
### 一句话 {#summary}
### 我的职责 {#role}
### 关键做法 {#approach}
### 结果 {#results}

## en
### Summary {#summary}
### My role {#role}
### Approach {#approach}
### Results {#results}
```

- 两种语言的小节 id 必须相同、顺序相同。
- **量化表达自由编辑**：数字加单位、百分比、倍数可以直接写在描述里，也可以用 `{{fact:speedup}}` 引用。引用时渲染值和单位；条件、出处可选。直接写量化表达不拒绝保存。
- `facts` 条目的 `claim`、`role_claim`、`team_size` 均可选，提供引用时检查存在。团队项目可以突出用户确认的个人贡献，不按「独立完成 / solely」等关键词拒绝保存。
- 项目对应 `facts.md` 里的 `projects.<slug>.*` 键，`ProfileResolver` 解析时能取到项目卡的字段。

### 来源登记（`sources.md`）与扫描规则

```markdown
| 键 | 类型 | 位置 | 版本 | 登记时间 | 最近扫描 | 读取文件数 | 说明 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| s-2026-10-22-001 | dir | <绝对路径> | files:<文件清单哈希> | … | … | 37 | — |
| s-2026-10-22-002 | github | <owner>/<repo> | <commit SHA> | … | … | 12 | — |
```

**本地目录**（`LocalDirectoryScanner`，只读）：
- 不跟随符号链接；不越出登记的目录；目录不能是 storage 本身或它的上级。
- **只按名字跳过，不打开**：`.env`、`.env.*`、`*.pem`、`*.key`、`*.p12`、`*.pfx`、`id_rsa*`、`id_ed25519*`、`*credentials*`、`*secret*`、`.npmrc`、`.pypirc`、`.netrc`、`*.keystore`、`*.jks`。
- 跳过的目录：`.git/`（提交历史另外用 JGit 只读读取摘要）、`node_modules/`、`dist/`、`build/`、`target/`、`.venv/`、`venv/`、`__pycache__/`、`.next/`、`.astro/`、`.wrangler/`、`.idea/`、`.vscode/`，以及被目录里的 `.gitignore` 忽略的路径。
- 读取范围：文档（md、txt、pdf、docx）、依赖清单（`package.json`、`pom.xml`、`build.gradle*`、`pyproject.toml`、`requirements*.txt`、`go.mod`、`Cargo.toml`）、结构化内容（yaml、json，单个文件不超过 200KB）。源代码只读文件树和每个文件的前 40 行（用于识别技术栈和模块），不整份发给模型。
- 上限：单个来源最多 400 个文件、文字总量 1.5M 字符；超过时在估算阶段提示，由用户缩小目录。
- 提交历史摘要：提交数、时间范围、本人提交占比（按用户在 `facts.md` 里确认过的邮箱或名字匹配；没有就不算）、最近的提交标题。

**GitHub 公开仓库**（`GitHubPublicReader`，JDK `HttpClient`，不带 token）：
- 接受 `https://github.com/<owner>/<repo>`、`<owner>/<repo>`；只有 owner 时列出它的公开仓库让用户选。
- 读取：`GET /repos/{owner}/{repo}`（仓库信息、默认分支、是否 fork）、`/languages`、`/topics`、`/commits/{branch}`（固定 SHA）、`/git/trees/{sha}?recursive=1`、README 和依赖清单的内容（按 SHA 读）、`/commits?per_page=30`（摘要）。和本地目录用同一套跳过规则。
- 接口地址可以配置（测试指向假服务端）；未认证的额度是每小时 60 次，估算阶段显示调用次数；返回 403 / 429 时报出原因和重置时间，不自动重试。
- 仓库不是公开的、或者是空仓库时，登记失败并说明原因。

### HTTP（沿用 C004-A 的本地请求防护）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` / `POST` | `/api/sources` | 列出来源 / 登记来源 `{type: dir\|github, location}`；只给 GitHub owner 时返回它的公开仓库列表，不登记 |
| `DELETE` | `/api/sources/{key}` | 删除登记（不删除目录或仓库里的任何东西；已经写进事实层的内容保留，出处标为「来源已删除」） |
| `POST` | `/api/import-runs` | `{sources[]}` → 扫描，建导入记录（`estimated`），返回估算 |
| `GET` | `/api/import-runs/{id}` | 导入记录（本段只有估算） |

错误统一 `{"error": ...}`；路径和仓库名在返回里可以出现，文件内容不出现在错误信息里。

### 前端

「资料」页改成页签：**原件**（C004-A 已有）和 **来源**（登记本地目录和 GitHub 仓库、看估算）。另外两个页签（整理与确认、事实与画像）在 C004-A-2 加。页面上提示：「秘密文件按名字跳过，不会被读取；整理时（下一步），扫描出的文字会发给你配置的模型」。

## 要改的文件

**不新增子包**；类名施工时可以微调，职责不变。

- `domain`：`Fact` 加 `zh`、`en`、`claim`、`reviewed`；新增 `Claim`（状态、用途枚举）、`ProjectCard`、`Source`、`EvidenceTier`（键前缀 → 内容分组，不设证据门槛）；`Ids` 加来源键 `s-…`、论断 id `c-NNNN`、项目 slug 的校验。
- `persistence`：`FactsFile` 扩展（兼容旧格式）；新增 `LedgerFile`、`ProjectCardFile`（含小节对应、可选引用的检查）、`SourceRegistry`、`EvidenceCardFile`、`GlossaryFile`、`ProfileSummaryFile`、`ImportRunStore`；`StorageLayout` 加对应路径。
- `tools`：`SecretFileRules`、`LocalDirectoryScanner`（含 JGit 只读的提交摘要）、`GitHubPublicReader`。
- `memory`：`ProfileResolver` 加按语言取值，`ResolvedFact` 带语言和未审阅标记；新增 `ImportEstimator`（扫描结果 → 估算）。
- `config`：`ImportProperties`（上限、GitHub 接口地址）；Bean 装配。
- `api`：`SourceController`、`ImportRunController`（本段只有建记录和查询）；`ApiErrors` 补映射。
- `frontend/`：`src/pages/Assets.tsx` 改成页签，新增 `src/pages/Sources.tsx`，`src/api.ts` 补接口。
- 文档：完成后补「实际结果」「偏差」，更新 `dev-plan.md`、`plan/README.md`、`dev-log.md`、`dev-recoding.md`、README。

## 实现步骤（垂直切片，每片结束时 `mvn -B clean verify` 全绿）

1. **小样核实**（会话临时目录，不进仓库）：JGit 只读读取一个本地仓库的提交摘要；GitHub REST 各接口的返回结构，先按官方文档写假服务端，再在用户监控下用用户指定的一个公开仓库真实调用一次，核对字段（只读，未认证；见「已确认」第 3 项）。
2. **格式**：`Fact` 双语与证据字段、`FactsFile` 兼容、`LedgerFile`、`ProjectCardFile`、`SourceRegistry`、`EvidenceCardFile`、`GlossaryFile`、`ProfileSummaryFile`、`ImportRunStore`，以及用户确认和可选引用规则。
3. **按语言取值**：`ProfileResolver`、`ResolvedFact`。
4. **来源与估算**：`SecretFileRules`、`LocalDirectoryScanner`、`GitHubPublicReader`、`ImportEstimator`、`SourceController`、`ImportRunController`。
5. **前端**：「来源」页签；`npm run build` 通过。

## 测试

自动测试**不访问外部网络、不调用模型、不读测试目录以外的任何本地目录**。GitHub 用本地假服务端（JDK `HttpServer`），本地目录用 `@TempDir` 里现造的虚构项目。

| 测试类 | 固定的行为 |
| --- | --- |
| `FactsFileTest`（扩展） | 双语字段读写往返；旧格式照常读；`value` 和 `zh` / `en` 不能同时出现 |
| `EvidenceTierTest` | 各前缀只影响分组；用户确认的经历、技能、成果、指标没有 `claim` 也可保存；提供的引用不存在时报错 |
| `LedgerFileTest` | 编号连续不复用；状态和用途的默认值；保留未识别的列 |
| `ProjectCardFileTest` | 两种语言小节不对应时拒绝；直接量化表达可保存；`{{fact:}}` 指向不存在的键时拒绝；团队项目中用户确认的个人贡献表述可保存；条件、出处和论断引用可省略 |
| `SourceRegistryTest`、`EvidenceCardFileTest`、`GlossaryFileTest`、`ProfileSummaryFileTest`、`ImportRunStoreTest` | 读写往返；保留未识别的内容 |
| `ProfileResolverTest`（扩展） | 按语言取值；未审阅标记；数字和日期按语言格式输出；继承规则不变 |
| `SecretFileRulesTest`、`LocalDirectoryScannerTest` | 秘密文件只按名字跳过、**从未被打开**（测试里把它们设成不可读，扫描仍然成功）；不跟随符号链接、不越出目录；生成目录和 `.gitignore` 里的路径被跳过；上限；源代码只取前 40 行；提交摘要 |
| `GitHubPublicReaderTest` | 正常读取并固定 SHA；私有或不存在的仓库（404）、额度用完（403 / 429）报出原因；只有 owner 时列出仓库；不带 token |
| `ImportEstimatorTest` | 文字量、模型调用次数、GitHub 调用次数的估算；超过上限时提示 |
| `SourceControllerTest`、`ImportRunControllerTest` | 各接口的状态码；Origin 允许名单；错误信息里没有文件内容 |

**前端**：`npm run build` 通过。

## 手动检查（用户操作，代理在旁边看结果）

1. GitHub 接口核对：用户指定一个公开仓库，代理真实调用一次（第 1 片，只读）。
2. 用户在「来源」页登记一个本地目录（由用户当次决定用哪个），看估算和跳过的文件。本段不调用模型，扫描出的文字不发给任何地方。
3. 结果写进「实际结果」；没做就在 `dev-log.md` 写「未运行」。

## 验收标准

- `mvn -B clean verify` 全绿；`npm run build` 通过。
- 自动测试没有访问外部网络，没有调用模型，没有读测试目录以外的本地文件。
- 秘密文件在任何测试里都没有被打开。
- 用户确认的求职内容无论是否关联论断均可保存；本人确认的指标可用于简历和填表；描述允许直接量化表达及突出个人贡献；中英文小节一一对应，可选引用有效。
- `ProfileResolver` 能按语言取值。
- 用户完成手动检查，或者明确决定暂缓。

## 已确认（用户确认（2026-10-22）：「同意拆分，其他的也同意」。）

1. **拆成两段施工**：本段是第 1–4 片加「来源」页签，不调用模型；整理、确认、画像在 [C004-A-2](C004-A-2-profile-review.md)。
2. **求职内容以用户确认和用途选择为准**（2026-10-22 用户修订）：键前缀只用于分组；论断引用、出处和条件可选，`owner_attested` 的指标默认可用于简历和填表。
3. **GitHub 接口的核对**：按官方文档写假服务端；另外在用户监控下，用用户指定的一个公开仓库真实调用一次（只读，未认证）。仓库到施工时由用户指定。
4. **本地目录的读取范围和上限**：源代码只读文件树和每个文件的前 40 行；单个来源最多 400 个文件、150 万字符。
5. **画像的小节**：见 C004-A-2「画像的小节」。
6. **手动检查用的材料**：到手动检查时由用户当次决定。本段的手动检查不调用模型。
7. **施工后确认的两处偏差**（用户确认，2026-10-22：「没有问题了」）：登记的目录也不能在 storage 里面（避免把事实层当来源再抽一遍；用资料填表走 `ProfileResolver`，不受影响）；估算时重新读仓库信息和 SHA，按当时的最新版本估算（开始整理前最多 5 次 GitHub 请求）。

## 实际结果（2026-10-22）

**切片 1：小样核实**（会话临时目录，没进仓库）
- JGit 7.8：`FileRepositoryBuilder().setGitDir(…).setMustExist(true)` 只读打开仓库可行；空仓库的 `HEAD` 是 null，直接 `log()` 会抛 `NoHeadException`，所以先判断 `HEAD`；`RevCommit.getShortMessage()` 会把多行标题拼成一行，标题改为取完整信息的第一行。
- GitHub REST（docs.github.com/en/rest，API 版本 2022-11-28）：按文档写了假服务端 `FakeGitHubServer`，覆盖仓库信息、`/commits/{branch}`、`/git/trees/{sha}?recursive=1`、`/contents/{path}?ref=`（base64）、`/languages`、`/commits?sha=&per_page=30`、`/users/{owner}/repos`，以及 404、409（空仓库）、403 / 429（额度，`x-ratelimit-remaining`、`x-ratelimit-reset`、`retry-after`）。**用户指定公开仓库的真实调用：未运行**（等用户指定仓库，见「手动检查」第 1 项）。

**切片 2–5：代码**
- `domain`：`Fact` 加 `zh`、`en`、`claim`、`reviewed`（`value` 与 `zh` / `en` 二选一）；新增 `Language`（`zh` / `en`，日期和数值的语言格式）、`Claim`（状态、用途、默认用途）、`EvidenceTier`（只分组；`requireKnownClaims` 只检查填了的引用）、`ProjectCard`（小节对应、`{{fact:}}` 检查和渲染）、`Source`、`SourceScan`、`ImportRun`；`Ids` 加来源键、导入记录 id、论断 id、项目 slug。
- `persistence`：`FactsFile` 扩展（旧格式照常读）；新增 `LedgerFile`、`ProjectCardFile`、`SourceRegistry`、`EvidenceCardFile`、`GlossaryFile`、`ProfileSummaryFile`、`ImportRunStore`，共用的 `MarkdownTable`（表格，保留未识别的列和表格前后内容）、`BilingualSections`（`## zh` / `## en` 小节）；`StorageLayout` 加对应路径和 `projectCardSlugs()`；`StorageGit` 加 `commitAtEndOfDay`、`listAtCommit`（项目卡按 `base_pin` 取版本时用）。所有写入在开启 git 时各提交一次。
- `memory`：`ProfileResolver` 加 `resolve(projectId, answers, Language)`（原来的两参数版本取中文）；base 层加入项目卡推出的 `projects.<slug>.title / kind / period.from / period.to / team_size / stack / links.<kind> / facts.<名字> / sections.<小节 id>`，`facts.md` 写了同名键时以 `facts.md` 为准；`ResolvedFact` 加 `display`、`language`、`unreviewed`，`label()` 在未审阅时追加「；en 文字未审阅」。新增 `ImportEstimator`。
- `tools`：`SecretFileRules`、`LocalDirectoryScanner`（JGit 只读的提交摘要、按目录层级读 `.gitignore`、Tika 算 pdf / docx 字数）、`GitHubPublicReader`（JDK `HttpClient`，不带 token，不跟随重定向）。
- `config`：`ImportProperties`（`jobapply.import.*`，默认值即「已确认」第 4 项）、`ImportConfiguration`。
- `api`：`SourceController`（`GET` / `POST` / `DELETE /api/sources`）、`ImportRunController`（`POST /api/import-runs`、`GET /api/import-runs/{id}`）；`ApiErrors` 加 GitHub 错误的映射：仓库不存在 / 不公开 / 空仓库 400，额度 429，其它 502。
- `frontend/`：「资料」页改成「原件」「来源」两个页签；新增 `src/pages/Sources.tsx`（登记、只给 owner 时选仓库、删除登记、勾选后估算、会读和跳过的文件、提交摘要、页面提示）；`src/api.ts` 补接口；`styles.css` 加页签和统计样式。
- 测试：新增 `EvidenceTierTest`、`LedgerFileTest`、`ProjectCardFileTest`、`SourceRegistryTest`、`EvidenceCardFileTest`、`GlossaryFileTest`、`ProfileSummaryFileTest`、`ImportRunStoreTest`、`SecretFileRulesTest`、`LocalDirectoryScannerTest`、`GitHubPublicReaderTest`、`ImportEstimatorTest`、`SourceControllerTest`、`ImportRunControllerTest`；扩展 `FactsFileTest`、`ProfileResolverTest`。辅助类 `FakeGitHubServer`。数据全部虚构。

**格式细节（计划没写、施工时定的）**
- 导入记录 id：`r-YYYY-MM-DD-NNN`，文件 `base/import-runs/<id>.json`；顶层字段 `id`、`status`、`created_at`、`updated_at`、`sources`、`estimate`，其它字段原样保留（给 C004-A-2 放进度和候选）。
- `ledger.md` 的 frontmatter `last_claim` 记用过的最大编号，删除后不复用；`sources.md` 的 frontmatter `deleted` 记删除过的来源键，键不复用，`SourceRegistry.status()` 能区分「还在 / 已删除 / 没有过」，给「出处标为来源已删除」用。
- 清单卡：frontmatter `source`、`source_version`、`generated_at`，正文自由 Markdown。术语表：`| 中文 | English | 类别 | 说明 |`，以中文为键。画像：frontmatter `generated_at`、`user_edited`（例如 `zh.highlights`）、`reviewed`，正文同项目卡的双语小节，小节 id 是 `directions`、`skills`、`highlights`、`positioning`。
- 模型调用估算：每个来源按 6 万字一批，多于一批时每批摘要一次，再加清单卡、拆候选各一次；有两个以上来源时再加一次合并（C004-A-2 施工时改为合并不调用模型，估算去掉这一次，见 C004-A-2「偏差」第 1 项）；没有可读内容的来源不算。GitHub：登记 2 次（仓库信息、固定 SHA），估算 3 次（仓库信息、固定 SHA、文件列表），整理时还要「语言 1 + 每个 README / 依赖清单 1 + 提交摘要 1」次。

**验证**
- `mvn -B clean verify` 退出码 0：surefire 535、failsafe 2，Failures / Errors / Skipped 均为 0。自动测试只连本机假服务端，没有访问外部网络、没有调用模型；本地目录都在 `@TempDir` 里现造；秘密文件在测试里设成不可读（`chmod 000`），扫描照常成功，证明没有被打开。
- `npm run build` 通过。
- 代理在会话临时目录的 storage 上启动打包后的后端（GitHub 接口地址指向一个不存在的本机端口），用 curl 登记了一个虚构目录并估算：`.env` 按名字跳过、`node_modules/` 整个跳过、源代码只算前 40 行；`sources.md` 记下了版本和文件数；登记 GitHub 时返回 `{"error":"连不上 GitHub：ConnectException"}`（502）。

**代码审核修复**（2026-10-22，用户找人审核代码；先用测试在旧代码上复现，再修复）
- 用 `facts.md` 覆盖项目卡的数值后，显示值仍是项目卡的旧数字（value=15000、display=12,000 QPS）。原因是格式化时按键去找项目卡数值。修复：`ProfileResolver` 改为按条目对象记录项目卡数值，只有取到的值确实来自项目卡时才带单位格式化。测试：`ProfileResolverTest.factsMdOverridingACardNumberAlsoOverridesItsDisplay`。
- 最后一个文件让累计字数超过上限时不报超限（上限 100 字、唯一文件 101 字，结果 `overLimit=false`、没有警告）。原因是只在读下一个文件之前检查累计量。修复：`LocalDirectoryScanner` 在计入当前文件时也检查，超出时这个文件跳过（原因「超过字符数上限」），同时报警告和 `overLimit`。测试：`LocalDirectoryScannerTest.theFileThatCrossesTheCharLimitIsReportedAndNotRead`。
- 另外两个问题属于 C004-A，记在 C004-A「代码审核修复」。
- 验证：5 个新用例在修复前的代码上都失败；修复后 `mvn -B clean verify` 退出码 0，surefire 541、failsafe 2，构建日志里没有测试 Key。

**手动检查**
- 第 1 项（2026-10-22，用户指定 `https://github.com/YigeWang1221/JobApply_agent`）：代理在会话临时目录的 storage 上启动打包后的后端，走真实流程。登记返回 201，位置是 `YigeWang1221/JobApply_agent`，SHA 固定为 `3243b3b849ac9f0da6a437c939641acd3fe47e48`。估算读到 `README.md`、`backend/pom.xml`、`frontend/package.json` 和文件列表，约 2.2 万字，模型 2 次；GitHub 已用 3 次、整理时还要 5 次；没有跳过的文件，也没有警告。另外用 curl 只读查看 `/languages`、`/contents/README.md?ref=`、`/commits?sha=&per_page=30` 的字段（只打印字段名，不打印内容）：结构和假服务端一致；`content` 是 base64、内容里带换行，`Base64.getMimeDecoder()` 能处理；`/commits` 的作者在 `commit.author.{name,email,date}`。全程不带 token；curl 看到的剩余额度是 59 → 57。
- 第 2 项（用户在「来源」页登记一个本地目录看估算）：**未运行**。

## 偏差

- 计划外的类：`domain` 的 `Language`、`SourceScan`、`ImportRun`；`persistence` 的 `MarkdownTable`、`BilingualSections`；`config` 的 `ImportConfiguration`；测试辅助类 `FakeGitHubServer`。职责都在计划列出的范围内，只是拆开放。C004-A 的 `AssetRegistry` 没有改用 `MarkdownTable`（表格代码暂时重复一份）。
- 比计划更严的几处：登记的目录也不能在 storage **里面**（计划只写了本身和上级）；名字像秘密文件的**目录**（例如 `secrets/`）整个跳过；文本类文件超过 2MB、pdf / docx 超过 20MB 记为「文件太大」跳过；跳过列表最多列 300 条（多的在警告里给总数）；提交摘要最多数 5 万个提交、列 5 条标题；GitHub 的 owner 只接受字母、数字和 `-`。
- GitHub：topics 从仓库信息里取，没有单独调 `/topics`（省一次额度）；估算阶段 README 和依赖清单的字数按文件大小估算，不读内容；只读仓库根目录的 README，依赖清单不限目录（跳过规则照用）。`content()`、`languages()`、`commitSummary()` 已实现并有测试，留给 C004-A-2 在整理时调用。
- 按语言取值：要的语言没有文字时用另一种语言代替，`language` 写实际用的语言，并标为未审阅（没有直接返回空）。纯数字不按语言格式化（电话尾号、邮编也是数字），只格式化 `YYYY-MM` / `YYYY-MM-DD` 日期和项目卡里带单位的数值。
- 项目卡：只写了一种语言时不检查小节对应（另一种还没起草）；正文里两个语言块以外的内容会被拒绝（画像文件则原样保留）。
- 默认用途：`discrepancy` 默认仍是 `forms_and_resume`（按「不自动禁用」理解），`needs_verification` 默认 `none`。
- 导入记录也在 storage git 里提交（和其它写入一致）。
- 导入记录 JSON 的顶层字段是 snake_case，`estimate` 里面是 camelCase（Jackson 按记录字段名输出），接口原样返回。

