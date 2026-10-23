# C004-A-1 · 资料导入（一）：事实格式、按语言取值、来源扫描

- 对应 Master：[memory.md](../master/memory.md)「资料导入与画像」「事实条目格式」「字段分级」「继承与解析」；[workflow.md](../master/workflow.md)「里程碑」第 3 段的前置
- 前置：[C004-A](C004-A-model-guard.md)（原件登记、「资料」页、C 级词表）。
- 后续：[C004-A-2](C004-A-2-profile-review.md)（整理、确认、画像），然后 [C004-B](C004-B-harness-review.md)。
- 状态：**已确认，待施工**（用户确认（2026-10-22）：「同意拆分，其他的也同意」。）。C004-A 已完成，可以施工。

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
