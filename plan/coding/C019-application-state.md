# C019 · 每次投递一份申请状态（字段清单 + 计划）

- 依据：用户（2026-10-25）：「我们在一次投递中，比如说打开工作台之后，我们就应该有一个类似于全局状态或者是 plan 加上全局状态的东西，然后每次填写的时候……就直接拿全局状态或者是他不断的补足这个全局状态，在上面做更新啊，在 plan 上面做实施目标啊之类的……这样的话就是一个比较通用的方式了呀。」
- 同日补充：进度清单放在工作台**侧栏**，不新开页面、不加大面板；先找已经有、但没用上的状态信息，复用，不另起一套。之后用户改为：计划写完直接一次施工，不停下审阅；计划里列的 master 改句算用户确认。
- 状态：已施工，等用户试（2026-10-25）。

## 现状与问题

证据：申请 2026-10-24-010 的 `run-log.jsonl`（只统计 model_call 行的数字）。
- 整个申请：96 次模型调用，335 万 token（输入 321 万，输出 14.4 万）。
- 主要那次运行（16:09 开始，网易 antd3 简历页）：61 次调用；每次输出平均 1969、中位 2496 token，40 次超过 1500；相邻两次调用中位间隔 16.5 秒；每次输入中位 3.3 万 token。
- 模型每步的私有说明多以「Now I have the full page. Let me plan…」开头：每一步都从整页快照里重新列字段、重新定计划。

已经有的状态信息（路径相对 `backend/src/main/java/jobapply/`）：

| 已有 | 在哪 | 用上了没有 |
| --- | --- | --- |
| 模型的计划 `update_plan(items)`，整表替换 | `tools/ControlTools.java:60`；`harness/runtime/HarnessLoop.java:795`；存在 `domain/AgentRun.java:33` 的 `plan` | 只有「一行字 + 状态」。C016 让模型把字段清单、选项、决定都塞进这几行字里；010 里 105 次工具调用只用了 4 次 |
| 计划进上下文 | `harness/context/FillContext.java:164`，在对话**前面**那条上下文消息里 | 离最新页面最远；计划一变，它后面整段对话的前缀缓存都失效 |
| 计划给界面 | `workflow/ApplicationStatus.java:36`、`workflow/ApplicationRunner.java:526`；`frontend/src/pages/JobPage.tsx:264、297`（右栏「计划」） | 只显示几行字 |
| 填写报告的字段行（页面、字段、值、来源层、语言、置信度、分级） | `HarnessLoop.java:173` 的 `Draft`、`recordFields`（:892），来自填写工具参数里的 `fact` / `confidence` / `language`（`harness/policies/FieldIntent.java`）；写 `jobs/<id>/fill-report.md`（`persistence/FillReportWriter.java`） | 这就是一份系统自动记的字段清单，但只写进报告文件：模型看不到，界面只在报告全文里看到 |
| 下拉 / 日期的读回（对得上 / 对不上）、选不中时的选项原文 | `tools/PlaywrightMcpClient.java:427、452、495` | 只是那一次工具结果里的一句话，没记在任何地方 |
| 只读框没填、C 级交给用户、日期 `need_day` | `HarnessLoop.java` 的 `cannotFill`、`classC`；`PlaywrightMcpClient.setDate` | 同上，只在那一次结果里说 |
| 本次答案 `ans-NNN` | `persistence/JobAnswers.java`；`ApplicationRunner.java:287`；上下文「本次答案」`FillContext.java:135` | 好用，保留；但答案和是哪个字段之间没有联系 |
| 等用户的问题、上传位置 | `AgentRun.questions` / `fileSlots` → 工作台右栏 | 保留 |
| 每步用量 | `persistence/RunLog.java:104`（每次调用的输入 / 输出 token） | 只在 run-log.jsonl 里；用来做下面的对比 |
| 推送 | `workflow/RuntimeEvents.java`：每次工具调用发 `step`，工作台收到就重读 | 每轮存盘后不发 `changed`，右栏可能晚一轮 |

问题：计划、报告字段行、读回结果是三份互不相干的记录。模型每一步只拿到一份很薄的计划和一整页快照，只好每一步重新分析整页。

## 参考

都是只读源码，没有运行。[源码] = 源码里核实过，[推断] = 推断。

| 项目 | 提交 | 怎么做 | 我们用什么 |
| --- | --- | --- | --- |
| browser-use | `3df5806`（2026-10-22）`agent/views.py`、`agent/message_manager/service.py`、`system_prompts/system_prompt.md` | 每一步重建一条状态消息放在最后：任务、每步的 memory / next_goal / 结果摘要、plan、当前页面；旧页面不留 [源码]。plan 整表替换 + 索引推进 [源码]。工具结果自带一行长期摘要；下拉全部选项只给一次 [源码] | 状态每步重写、放在最后；工具结果由系统记一行进状态 |
| deepagents | `3e7d5fa`（2026-10-23）`graph.py`、`middleware/filesystem.py`、`middleware/memory.py` | todo 整表替换（没有 reducer），工具回复固定一句 [源码]；默认已不带 todo [源码]；`files` 按键合并（同键覆盖，None 删除）[源码] | 长清单按 id 合并，短计划整表替换；工具回复只一句 |
| deep-agents-ui | `f6a4f34`（2026-05-14）`useChat.ts`、`TasksFilesSidebar.tsx` | 侧栏直接读线程状态里的 todos，按状态分组 [源码] | 侧栏读同一份状态 |
| OpenMAIC | `dff201b`（2026-10-23）`lib/orchestration/summarizers/state-context.ts`、`whiteboard-ledger.ts` | 状态由系统按工具结果追加记录，每次请求按预算渲染成几行摘要（截断、只列前 N 项）[源码] | 系统从工具结果更新；渲染有预算 |
| langgraphjs | `c6675bf`（2026-10-22）`channels/last_value.ts`、`binop.ts`、`constants.ts` | 每个键自己的合并规则（覆盖 / 二元合并），工具可以返回部分更新 [源码]；只看概念，不引入 | 部分更新、按键合并 |
| Skyvern | `647b920`（2026-10-24）`batch-form-fill-plan.j2`、`extract-action.j2` | 先抽出整页字段（标签、格式、必填、选项），一次规划所有值；动作历史只带 1 步 [源码] | 字段清单先写下、再照着填 |

## 设计

### 1. 一份申请状态，替换 `AgentRun.plan`

- 开始申请时建一份空的（`AgentRun.start`），存在运行记录 `storage/runs/<job-id>.json` 的 `state` 里，和对话、用量同一个文件、同一次存盘。不另开文件：恢复本来就读这个文件；`jobs/<id>/fill-report.md` 从它生成（第 7 节）。
- 目标不另存：由运行记录里的公司、岗位拼出来（「填好 X · Y 的申请，停在最终提交前」）。
- 问题和答案不另存：等回答的问题照旧在 `AgentRun.questions`，答案照旧在 `ans-NNN`；状态里只是字段的 `status: needs_user` 和答了之后的 `source: ans-NNN`。
- 形状（假数据）：

```yaml
stage: 第 2/4 步 教育经历          # 现在在网站流程的哪一步（模型写）
steps:                              # 计划（原 update_plan，整表替换）
  - {text: 基本信息, status: done}
  - {text: 教育经历 2 段, status: doing}
  - {text: 项目经历, status: todo}
fields:                             # 字段清单，按 id 合并
  - id: edu1.school
    section: 教育经历 1
    label: 学校名称
    widget: select
    value: 示例大学
    source: education.0.school      # 资料键 / ans-NNN / resume / cover_letter / none
    status: verified                # todo / filled / verified / needs_user / skipped
    note: 控件显示的值和选中的项对得上
  - id: edu1.start
    section: 教育经历 1
    label: 入学时间
    widget: date
    value: 2021-09
    status: needs_user
    note: need_day：要精确到日，问用户
  - id: idno
    label: 证件号码
    value: "{ID_NUMBER}"            # C 级只记占位符
    status: filled
  - id: edu1.degree
    label: 学历
    status: todo
    options: [专科, 本科, 硕士研究生, 博士研究生]   # 见过的选项原文
notes:                              # 决定和站点规律
  - 学历要先选，学校框才会出现
```

- 每个字段系统还记一行报告用的格子（页面、填进去的值、来源层、语言、置信度、分级），不给模型看，只用来生成填写报告。

### 2. 谁改、什么时候改

- **模型**：用 `update_state`（第 3 节）写阶段、计划、字段清单、选项、决定。刚开始和翻到新的一页 / 新的区域时写一次这一页的字段清单，之后只改有变化的。
- **系统**（工具结果出来的那一刻，`HarnessLoop`）：
  - 填写成功（`fill_form` / `type` / `select_option` / `set_date`）→ `filled`；新快照里输入框的文字和填的值一样、下拉 / 日期读回对得上 → `verified`；同时写报告格子。
  - 只读框没填 → 留 `todo`，`note` 写原因。
  - 选不中 → `note` 写原因，`options` 记下结果里的选项原文。
  - 日期 `need_day` → `needs_user`。
  - C 级交给用户 → `needs_user`，报告记「用户亲填」。
  - 上传成功 → 一个 `widget: file` 的字段，`filled`（页面上看到文件名时 `verified`）。
  - 用户回答了问题（`ApplicationRunner.reply`）→ 标签和问题一样的 `needs_user` 字段改成 `todo`，`source` 写答案编号，`value` 写回答。
- **对上是哪个字段**：填写工具的字段参数加一个可选的 `field`（状态里的 id，和 `fact` 一样在发给 MCP 之前去掉）。没写时按标签找（先找还没填的）；都找不到就新加一个字段（id `f1`、`f2`…）。

### 3. 模型用的工具：`update_state` 替换 `update_plan`

- 参数（都可选，只写变了的）：
  - `stage`：字符串，替换。
  - `steps`：`[{text, status: todo|doing|done|blocked}]`，整表替换（同 `update_plan`）。
  - `fields`：`[{id, section, label, widget, value, source, status, note, options, remove}]`，按 `id` 合并：给了的键覆盖，没给的不变；`remove: true` 删掉；新 id 要有 `label`。
  - `notes`：要加的几行；`drop_notes`：要删的几行（原文）。
- 回复只一句：「申请状态已更新：字段 N 个（待填 a、已填 b、要问用户 c）。」状态全文下一步会重新给。
- 不对时（缺 id、新字段没有标签、状态写错）返回「失败：……」，不改。
- C 级标签的字段（`FieldClassGuard.isClassC`）值不是占位符时不记值，回复里说一句。这只是存数据的规矩（报告本来就这样），不拦任何操作。
- `update_plan` 不再给模型；旧对话里、或者模型照习惯调了，照旧接受，写进 `steps`。

### 4. 每一步怎么放进上下文

- 现在：系统提示词 → 上下文（资料、本次答案、文件、skill、站点记录、**计划**）→ 对话（只有最新一份快照原样给）。
- 改成：
  - 前面那条上下文消息去掉计划，一次运行里基本不变，前缀缓存能一直命中。
  - **申请状态接在最后一条消息的末尾**：大多数时候最后一条就是带着最新页面的那个工具结果，所以顺序是「上一步结果 → 当前页面 → 申请状态」。不新加一条用户消息（不改变 C016「最后一条用户消息之后发回思考」的范围）。
  - 渲染要短：每个字段一行（id、标签、控件、状态、值截到 40 字、来源、说明、选项最多 40 个）；整个区域都好了的只写一行「基本信息：6 个都好了」；状态是空的时只写一句「先用 update_state 写下……」。
- 快照和状态的关系：快照照旧每步给最新的一份（不做按变化给，见「不做」）；提示词要模型先看状态挑下一批 `todo` 字段，只在快照里找它们的 ref，不再每步列整页。
- 对话 Agent 不用申请状态，`Conversation` 的新参数传 null。
- C016 里发回 `reasoning_content` 的做法不动（DeepSeek 带工具时的接口要求）；本计划不依赖它，换别的模型也一样有效。

### 5. 恢复

- 状态和对话在同一个运行记录里，每轮存一次。应用重启、用户回复、重新连接后读回来，第一步就照常渲染，模型从状态接着做，不用重新分析整页。
- 旧的运行记录（只有 `plan`，没有 `state`）：读的时候把 `plan` 换成 `state.steps`；报告里已有的字段行放进字段清单（`filled`）。
- 对同一个 Job 重新「开始申请」：状态是新的；不再接上一次的报告（第一次写报告时整份换成新的）。

### 6. 工作台侧栏

- 右栏原来的「计划」换成「进度」，位置不变（`JobPage.tsx` 的 `Plan`）：
  - 一行「现在在：第 2/4 步 教育经历」和「字段 9/14 好了 · 要问你 1」；
  - 计划几行（同原来）；
  - 按区域折叠的清单：每行一个状态小标签（待填 / 已填 / 已核对 / 要问你 / 跳过）+ 标签 + 说明；还有没做完的区域默认展开；不显示值；
  - 「决定和站点规律」折叠。
- 每轮存盘后发一次 `changed`，侧栏跟着刷新。

### 7. 填写报告从状态生成

- `fill-report.md` 的格式、读回（`FillReportWriter.render / parse`）不变。
- 字段行：字段清单里有报告格子的每个字段一行（C 级没占位符的写「用户亲填」）。不再单独累积一份字段行。
- 附件、未解决、守卫记录照旧（`Draft` 只剩这三样）。
- `finish` 时，还是 `needs_user` / `todo` 的字段、模型的 `unresolved` 里没提到的，自动补进「未解决」。

### 8. 去掉 / 合并

| 原来 | 改成 |
| --- | --- |
| `AgentRun.plan`、`AgentRun.PlanItem` | `AgentRun.state`（`ApplicationState`），计划是 `state.steps` |
| `update_plan` | `update_state`（`update_plan` 只作旧名接受） |
| `FillContext` 里的「你的计划」 | 删；状态接在最后一条消息末尾 |
| `Draft.fields`（报告字段行） | 状态字段的报告格子 |
| `ApplicationStatus.plan` | `ApplicationStatus.state` |
| 侧栏「计划」 | 侧栏「进度」 |

## 和现有设计的关系

用户已确认（同日指令），施工时照下面改 master：

`plan/master/agent-runtime.md`
- 7.1「恢复」：「每轮之后把对话、计划和用量存到这个 Job 的目录。」→「每轮之后把对话、申请状态（字段清单和计划，C019）和用量存到这个 Job 的运行记录。」
- 7.2：「**`update_plan(items)`**：模型自己的清单（这份表有几页、每页的状态、还缺什么），每轮放回上下文，也给界面显示进度。」→「**`update_state(...)`**（C019，替换 `update_plan`）：模型维护这份申请的申请状态：现在在哪一步、计划、字段清单（标签、区域、要填的值和来源、状态、见过的选项）、决定和站点规律；按字段 id 合并，只写变了的。填写结果由系统自动记进去。每轮放在最后一条消息末尾，也给工作台侧栏显示进度。」
- 7.3 开头：「整段对一个 Job 不变，只有「当前页面」和「计划」每轮更新」→「……只有「当前页面」和「申请状态」每轮更新」；第 2 条「更新计划」→「更新申请状态」；第 5 条「……skill 目录（名字 + 一句话）、这个站点的记录、计划。」→「……skill 目录（名字 + 一句话）、这个站点的记录。」；第 6 条末尾加：「申请状态（C019）接在最后一条消息的末尾，紧跟最新页面：模型照状态挑下一批字段，只在快照里找它们。」
- 7.7 `FillReportWriter…` 一行的说明加：「报告的字段行从申请状态的字段清单生成（C019）」。

`plan/master/workflow.md`
- 主流程表「关卡 B」：「生成填写报告：每个字段的值、**来源层**、置信度……」→ 前面加「从申请状态的字段清单生成填写报告（C019）：」。
- 主流程表「逐页填写」：「快照 → 批量填 A/B 级字段 → ……」→「快照 → 在申请状态里写下这一页的字段清单（C019）→ 照清单批量填 A/B 级字段 → ……」。

## 不做

- 不按变化给快照（只给变化的部分、旧快照摘要）：先看状态本身能省多少。
- 不改对话 Agent。
- 不做上下文压缩、不改 C016 的思考发回。
- 不让用户在侧栏里编辑状态。
- 不加新的守卫、确认、上限；不改提交守卫和 C 级的线。
- 不引入 LangGraph 或别的状态框架。

## 风险

- 模型不写状态、或者写得太细：提示词要求翻页时写一次、之后只改变化；系统自动记的那部分不依赖模型。
- 按标签对字段可能对错（几段经历都有「开始时间」）：提示词要模型在填写参数里写 `field`；对错了只影响清单和报告的那一行，不影响页面。
- 状态变长：每个字段一行、做完的区域合成一行、值和选项截断；60 个字段大约 2–3 千 token，比每步 3 万多 token 的输入小得多。
- 状态和页面不一致（用户手动改了页面）：提示词说以最新快照为准，发现不对用 `update_state` 改。
- 旧运行记录：读的时候换成新格式；没有 `state` 的旧文件照样能接着跑。

## 怎么确认能用

- `backend/`：`JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -B -q clean verify` 退出码 0；`frontend/`：`npm run build` 退出码 0。
- 只补会造成实际损失的测试：旧运行记录（只有 `plan`）读得回来；状态合并不丢字段；C 级值不进状态和报告。
- 用户在真实网站跑一份同类申请（最好还是网易 antd3 那类长表单），和 010 主要那次运行比（`run-log.jsonl` 的 model_call 行、`storage/runs/<job>.json` 的 usage）：
  - 模型调用次数（010：61 次，整个申请 96 次）；
  - 每次输出 token（010：平均 1969、中位 2496，40/61 超过 1500）；
  - 相邻调用间隔（010：中位 16.5 秒）；
  - 总 token（010：整个申请 335 万）。
  - 同时看：侧栏「进度」是不是跟着每一轮变；模型回复还是不是每步「重新列整页」；报告的字段行和侧栏清单对得上。

## 实际结果

2026-10-25，按上面一次施工（用户改为写完直接施工，master 改句算确认）。路径相对 `backend/src/main/java/jobapply/`。

**申请状态**
- `domain/ApplicationState.java`（新）：`stage`、`steps`、`fields`、`notes`；字段 `id / section / label / widget / value / source / status / note / options / report`。合并（按 id，只覆盖给了的键；新字段要有标签）、删除、计划替换、加减决定、`locate`（id → 还没填的同名字段 → 新加 `fN`）、`answered`（用户回答后等它的字段回到待填）。
- `domain/AgentRun.java`：`plan`（`PlanItem`）换成 `state`；加 `withState`。
- `persistence/AgentRunStore.java`：旧记录只有 `plan` 时读成 `state.steps`。

**模型的工具**
- `tools/ControlTools.java`：`update_state` 替换 `update_plan`（定义里写清：每步最后给你看、照它挑下一批字段、只写变了的、填写时写 `field`）；`update_plan` 不再给模型，调了照旧写进计划。
- `harness/policies/FieldIntent.java`、`tools/BrowserTools.java`：填写工具的字段参数多一个可选 `field`（申请状态的 id），发给 MCP 前去掉。

**系统自动记**（`harness/runtime/HarnessLoop.java`）
- 填写成功：`recordFields` 把值、来源、控件、状态和报告那一行记进字段；新快照里输入框文字和值一样、或选下拉 / 设日期读回对得上（`PlaywrightMcpClient.readBackMatched`，几句结果话改成常量）→ `verified`，否则 `filled`。
- 只读框没填：`note` 写原因；工具失败：`note` 写第一行原因，选不中时 `options` 记结果里的选项原文，`need_day` → `needs_user`；C 级交给用户 → `needs_user`，报告「用户亲填」；上传 → `widget: file` 的字段。
- `update_state` 的处理：哪一项不对整个不改；C 级标签的字段值不是占位符时不记（`storable`，只是存数据的规矩）。
- `workflow/ApplicationRunner.java`：回复里答了问题 → `state.answered`；每轮存盘后发 `changed`。

**上下文**
- `harness/context/StateView.java`（新）：一段「申请状态」：目标、现在在、计划、字段统计、按区域一行一个字段（值截 40 字、选项最多 40 个），都好了的区域一行；空的时只一句提示。
- `harness/context/Conversation.java`：新参数 `state`，接在最后一条消息末尾（工具结果接在最后一个结果后面，用户消息保留图片）；对话 Agent 传 null。
- `harness/context/FillContext.java`：前面那条上下文去掉计划；`Inputs` 带目标和申请状态。
- `agents/ApplyAgent.java`：工作方法改成照申请状态做（翻页时写一次字段清单和计划，之后挑下一批待填字段、只在快照里找它们，填写时写 `field`，选填项没有资料记成跳过）。

**填写报告**
- `persistence/FillReportWriter.java`：`asRecorded(Filled)`，单元格和原来写出的一字不差。
- `HarnessLoop.Draft` 只剩附件、未解决、守卫记录；`writeDraft` 的字段行从申请状态生成。旧运行记录没有字段清单时，报告里的字段行放进字段清单。新开始的申请第一次运行先写一份空草稿，换掉上一次的报告。`finish` 时还没好的字段（模型没提到的）补进「未解决」，以「申请状态：」开头，下次定稿前先去掉。

**界面**
- `workflow/ApplicationStatus.java`：`plan` 换成 `state`；`api/Views.java`：步骤说明「更新申请状态：<stage>」。
- `frontend/src/pages/JobPage.tsx`：右栏「计划」换成「进度」（现在在、字段 x/y 好了、要问你、计划、按区域折叠的清单、决定和站点规律），不显示值；`frontend/src/api.ts` 类型、`chat/process.ts` 标签、`styles.css` 几行。

**测试**（只补改坏了会丢东西的）：`domain/ApplicationStateTest`（合并不丢字段、报告那一行留着、按标签对字段、回答回到字段）、`persistence/AgentRunStoreTest`（旧记录的 plan 读得回来）、`harness/runtime/StateValueTest`（C 级只记占位符）、`FillReportWriterTest.filledRowsKeptAsCellsRenderTheSame`（报告格子一字不差）。`ApplicationLlmManualCheck` 一行跟着改。

**验证**
- `backend/`：`JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -B -q clean verify` 退出码 0（surefire 654、failsafe 1，都通过）。
- `frontend/`：`npm run build` 退出码 0（只有原来的包体积提示）。
- 真实 Chrome、真实模型：未运行（没连用户的 Chrome，没重启 8080 上的应用）。

**和计划不同的地方**
- 没有另开文件：申请状态在运行记录 `storage/runs/<job-id>.json` 里（architecture.md 本来就这么写运行状态），报告文件从它生成。
- 类名 `ApplicationState` 和 agent-runtime.md 7.7 里删掉的 LangGraph4j 状态类同名，是不同的东西。
