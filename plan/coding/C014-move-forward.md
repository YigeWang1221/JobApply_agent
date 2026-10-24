# C014 · Move forward：面试轮次与复盘、Agent 自己更新 memory、Cover Letter、敏感信息占位符

- 依据：用户（2026-10-24）看完进度盘点后定下四件事：
  1. 面试轮次和复盘：轮次表、每轮复盘、题库、按公司汇总。**确定要做。**
  2. 申请完成后的 memory 更新：**LLM 自己更新本地 memory**，不走「候选 → 用户确认 → 写入」。
  3. Cover Letter：申请表里有 Cover Letter 的框时，按 memory、本地简历和 JD 写好填进去。
  4. C009 敏感信息：资料里用 `{KEY}` 占位符，真实值放在用户自己维护的本地 secret 文件里，LLM 填表时写占位符，系统发给浏览器时换成真实值。C009 并入本计划。
- 用户要求：计划写好以后直接分给多个 agent 并行施工。
- 不做：公司投递额度检查、首次使用引导页、面试准备清单（这次没点名）；Cover Letter 只做文本框，表单只能上传文件时照旧问用户；不让系统点最终提交。

## 已回写的设计（用户本次指令即确认）

- `plan/master/memory.md`：「字段分级」C 级可以用占位符存放；「Memory 写入」改为 Agent 直接写入。
- `dev-limit.md`：新增「C 级占位符」一条。
- `plan/master/tracking.md`：按原设计施工，不改设计。

## 工作包（三个 agent 各自在 worktree 里做，最后合并）

各包只改自己的文件。都要改的 `ResearchTools`、`SessionLoop`、`HarnessLoop`、`ApplyAgent`、`ResearchAgent`，各自只加新的工具定义、分支和提示词段落，不改别人的段落，合并时按段拼起来。

### A · 面试轮次与复盘（`tracking.md`「轮次」「投递之后」「面试复盘与常考题」）

- 对话 Agent 新工具 `record_round`：给一个 Job 追加或更新一轮（seq、name、type、scheduled_at / happened_at、format、interviewer_role、result、result_source）。`phase` 照 tracking.md 从轮次推出来（最新一轮是笔试就是 assessment，是面试就是 interview，出现 offer 轮就是 offer，failed 时 closed + rejected）。结果写进 `job.md` 的 `rounds` 摘要和事件日志。
- 对话 Agent 新工具 `save_review`：写 `jobs/<job-id>/rounds/<seq>-<type>.md`，格式照 tracking.md「轮次复盘文件格式」，题目带 topic 和 canonical。Agent 按用户的话直接写，写完在对话里给用户看，用户要改就再写一次。不用先起草、再等确认。
- 生成文件：每个 Project 的 `tracker/rounds.md` / `rounds.csv`；`storage/review/questions.md`（按 canonical 归并，按出现次数排序）；`storage/review/companies/<company>.md`。每次写轮次或复盘后，和别的台账一起重新生成。
- `ResearchAgent` 提示词：用户说面试、笔试情况时，用 `record_round` 加 `save_review`；只有阶段变化、没有轮次时，还是用 `record_status`。
- 前端：Job 页显示轮次列表和复盘链接；Dashboard「⑦ 复盘概览」显示常考主题 Top N 和在哪一轮挂的分布；申请总表的「进度」列照 tracking.md 显示轮次圆点。数据来自后端接口。
- unknown 规则（轮次过了 7 天还 pending 就记 unknown）：顺手在生成台账时算。

### B · Agent 自己更新 memory + Cover Letter

- 两个 Agent 都有新工具 `remember`，直接写本地 memory，不经过用户确认：
  - `kind: fact`：个人事实，写进当前 Project 的资料（`overrides.md` 或 FactWriter 已有的写法，A / B 级，`source: user-confirmed` 或 `site-observed`）。值是 C 级明文时拒绝，并告诉模型改成登记占位符（`{KEY}`，见 D）。
  - `kind: site`：站点观察，写 `playbook/sites/<host>.md`，复用 `SiteNotes`。
  - `kind: company`：公司记忆，写 `companies/<company>.md`（流程、在线简历状态、额度提示）。
  - `kind: lesson`：可复用的经验，追加到 `storage/review/lessons.md`。
- **有的更新、没有的新增，不乱追加**（用户补充，2026-10-24，参考 Hermes `tools/memory_tool.py`、`tools/skill_manager_tool.py`，提交 e5dad4ac5）：`remember` 带 `action: add | replace | remove`。个人事实按资料键（例如 `education.masters.program_name`）写：键已经有就更新，没有才新增，写之前要先看已有的键。站点、公司、经验这几个 md 文件：`add` 拒绝完全重复的条目，`replace` / `remove` 用一段唯一的原文 `old_text` 找到要改的条目，找不到或者找到多条时报错；每个文件有大小上限，超了就要求先合并或删掉旧条目。结果里带上文件现在的条目。playbook skill 用 old_string / new_string 打补丁，先 `read_skill` 再改。新增 skill `memory-update`，写清楚什么时候记、先查后写、同一件事用 replace，两个 Agent 的提示词都指向它。
- 提示词：申请 Agent 用户在工作台里回答了问题、填表时发现站点规律、申请停在提交前时，把值得留下的写进 memory。对话 Agent 用户在对话里说了新事实或纠正、投递之后有反馈时，写进 memory。写了什么在回复里提一句。
- Cover Letter（申请 Agent）：表单里有 Cover Letter / 求职信 / 自我推荐 / 附言这类文本框时，Agent 先读 JD 快照（job.md 已存）、`read_resume` 读选定简历、看资料，再按资料里 `writing.md` 的要求写（没有要求时英文 200–300 词，中文 300–500 字，按表单语言），然后填进去。只写简历和资料里有的事，不编。填写报告写明「Cover Letter：Agent 起草」。有现成可用的工具时不加新工具，只改提示词，再加一个 skill `components/cover-letter`（放进 `PlaybookSeeder.BUNDLED`）。JD 快照读不到时给申请 Agent 补一个读快照的入口。

### D · 敏感信息占位符（原 C009）

- 本地 secret 文件 `storage/settings/personal.env`：用户自己写，一行一个 `ID_NUMBER=...`，权限 600，和 `.env` 同一种格式（复用 `EnvFile` 的解析）。代理不读、不改、不建这个文件。程序运行时读，这是软件正常运行流程。
- 资料里 C 级事实的值写成 `{ID_NUMBER}`（键名 `[A-Z_][A-Z0-9_]*`），分级仍标 C。资料解析、导入、`remember` 遇到这种值时放行，遇到 C 级明文照旧拒绝。
- 申请 Agent 上下文里给它看有哪些 C 级占位符（字段名 → `{KEY}`），不给真实值。
- `FieldClassGuard`：C 级字段的值正好是已登记的占位符 `{KEY}`，并且 `personal.env` 里有这个键时放行；其它情况照旧交给用户亲填。占位符没有值时，结果写「`{KEY}` 在 personal.env 里没有值」。
- 替换只发生在发给 Playwright MCP 的那一刻（`browser_fill_form` / `browser_type` / `browser_select_option` 的值）。工具结果、发给模型的消息、run log、事件、填写报告里只有占位符。快照继续由 `SnapshotRedactor` 遮值，遮的时候也认 `personal.env` 里的值，页面回显出真实值时也遮掉。
- 因为泄露会造成实际损失，这一包写测试：用临时 storage 和虚构的值，确认替换后的真实值只出现在发给假 MCP 的请求里，不出现在模型请求、run log、报告里；没登记的明文 C 级值仍被拒绝。
- **在前端填，不手写文件**（用户补充，2026-10-24）：设置页加「敏感信息」。表里列出预设的键（中文名 / English / KEY，例如 ID_TYPE 证件类型、ID_NUMBER 身份证号、PASSPORT_NUMBER 护照号、PASSPORT_EXPIRY 护照有效期、ETHNICITY 民族、RELIGION 宗教信仰、WORK_AUTHORIZATION 工作授权、VISA_STATUS 签证状态、NEEDS_SPONSORSHIP 是否需要签证担保、EXPECTED_SALARY 期望薪资、CURRENT_SALARY 当前薪资、HEALTH_STATUS 健康状况、DISABILITY_STATUS 残障状态、VETERAN_STATUS 退伍军人身份、CRIMINAL_RECORD 犯罪记录），每行一个只能写入的输入框，页面只显示「已设置 / 未设置」，从不把值发回浏览器；也可以加自定义的键。保存时由程序写进 `personal.env`，并在全局资料里自动登记对应的 C 级事实，值为 `{KEY}`，用户不用改资料文件。声明 / 承诺类照旧由用户亲填。
- 接口：`GET /api/settings/personal`（只返回键、名称、是否已设置）、`PUT` / `DELETE /api/settings/personal/{KEY}`。另附 `backend/src/main/resources/personal.env.example`，只有键名和 `<你的身份证号>` 这类说明，没有真实值。

## 怎么确认能用

- 每个包：`JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -B -q clean verify` 退出码 0；改了前端就在 `frontend/` 下跑 `npm run build`。
- 合并后再跑一次全部构建。
- 真实使用由用户在本机试：在对话里说一次面试情况，看轮次表、复盘、题库；申请一个有 Cover Letter 框的岗位；资料里登记一个 `{KEY}`，在 personal.env 写值，看页面上填进去的是真实值。

## 实际结果

### 实际结果 · B

- 施工中用户改了设计（2026-10-24，经协调代理转达）：`remember` 已有的就更新、新的才加，照 Hermes（`~/.hermes/hermes-agent` `e5dad4ac5`）的 memory / skill_manage 写法。对照写在 `plan/reference/research-agent-design.md`「Hermes 的 memory 写法」。
- **`remember` 工具**（定义一份在 `ControlTools.REMEMBER_DEFINITION`，`ResearchTools` 也用它）：`action: add | replace | remove`，`kind: fact | site | company | lesson | skill`。执行在新的 `memory/MemoryWriter`（分派、C 级检查、事实按键写）和 `persistence/MemoryNotes`（md 条目文件和 skill 局部修改），对话 Agent 在 `SessionLoop`、申请 Agent 在 `HarnessLoop` 各加一个分支。不经过用户确认。
  - `fact`：按键 upsert 到当前 Project 的 `overrides.md`（键已有就更新、结果里给原来的值；没有才加），remove 删 overrides 里的这个键。A / B 级；C 级明文（键名或值命中 C 级词表）拒绝，结果里叫模型改成登记 `{KEY}` 占位符、告诉用户写 personal.env。值正好是 `{KEY}` 时按 C 级写入——**要等 D 合并后才能真正写进去**：现在 `Fact` 不允许构造 C 级，本分支里这一步返回「C 级字段不允许存储」，不写文件。
  - `site` / `company` / `lesson`：`playbook/sites/<host>.md`（新文件带 SiteNotes 的 frontmatter）、`companies/<company>.md`、`review/lessons.md` 里一行 `- ...` 一条；add 一模一样的不重复加；replace / remove 用 `old_text` 找唯一的一条（没对上、对上几条都报错）；每个文件条目合计 4000 字上限，满了报「先合并或删掉旧条目」；结果里列出现在的条目。申请 Agent 不写 host / company 时默认当前页面、这个岗位的公司。note 里有 C 级的值拒绝。
  - `skill`：old_string（正文里正好一次）换成 new_string，不动 frontmatter，改完不超过 8000 字；不做整篇重写。
  - 对话 Agent 的上下文新加一行「已有的资料键」（只有键和分级）；申请 Agent 本来就在「有效资料」里看到键。
  - `StorageLayout` 加 `companyFile`、`reviewLessons`、`baseWriting`。
- **新 skill**：`components/cover-letter`、`memory-update`（什么值得记、先看已有的、同一件事 replace、条目要短、满了先合并、改 skill 只改一段），都放进 `PlaybookSeeder.BUNDLED`。
- **Cover Letter**：申请 Agent 原来读不到 JD 快照，加了控制工具 `read_jd`（`job.md` 的「JD 快照」一节、候选备注、公司记忆、`base/writing.md`；没有写作偏好时给默认字数）。提示词新段「Cover Letter 和 memory」：文本框由 Agent 起草，读 JD / 简历 / 资料 / skill，只写真实的事，fact 写 `cover_letter`；`FieldIntent.COVER_LETTER` 让报告里这一行的来源写「Cover Letter：Agent 起草」。只能上传文件时照旧问用户。
- **提示词**：申请 Agent 在用户回答了问题、发现站点规律、停在提交前时 remember；对话 Agent 在用户说了新事实或纠正、投递后有反馈时 remember；都说已有的 replace、新的才 add，写了什么在回复里提一句，并指向 skill memory-update。
- 验证：`JAVA_HOME=… mvn -B -q clean verify` 退出码 0。用 jshell 在临时 storage 上手动调了一遍 `MemoryWriter`（虚构数据）：事实新加、按键更新（给出原值）、C 级明文被拒、站点条目 add / 重复 add / replace / 对不上的 remove、公司记忆、经验超过 4000 字被拒、skill 对不上 old_string 报错，结果都和上面一致。没写测试。真实应用、真实浏览器：未运行。
- 没做：经验文件（`review/lessons.md`）还没有放进任何 Agent 的上下文，只有写入；对话 Agent 看不到公司记忆（申请 Agent 通过 `read_jd` 看得到）。
