# C024 · 投递结果：「投递失败 / 不投了」+ 手动写原因

- 对应 Master：[tracking.md](../../master/tracking.md)「阶段」「closed 的 outcome」「Dashboard」；[memory.md](../../master/memory.md)「Job 记录」；[ui.md](../../master/ui.md)「申请工作台」；[agent-runtime.md](../../master/agent-runtime.md)（工作台工具）。
- 依据：用户（2026-10-31）：「现在对于工作投递结果的处理太机械了。如果投递不成功应该是有很多情况，但是如果现在我们 case 写不完的话，应该使用 选择投递失败/不投递了 -> 输入框「手动输入原因」 -> 工作台，session 保留记录，dashboard 展示的这种情况」。
- 状态：**已施工，等用户在本机试**（2026-10-31）。7 个问题用户已决定（见文末「已确认」），下面的设计按决定改过。
- 施工前提：C023 的 `JobPage.tsx`、`styles.css` 改动在工作区里，C024 在它上面接着改（没有提交、没有回退）。

## 现状：为什么「机械」

- 结果只有固定枚举：候选 `lead_status`（new / shortlisted / skipped / closed / converted）；申请 `phase` + `prep_state`；结束 `outcome`（accepted / declined / rejected / withdrawn / ghosted / not_submitted / posting_closed）。每种结局有自己的按钮或接口（`/not-submitted`、`/posting-closed`），case 一个一个加。
- **没有「投递失败」**。`prep_state = failed` 是申请 Agent 跑出错（可重试），不是「这份申请投不成了」。站点报重复投递、名额满、系统不收、资格不符、账号受限……都没有地方记，只能挂在「等你 / 失败」里，或者硬套 `not_submitted`。
- **「不投了」入口窄、没有原因**：只在停在提交前（`awaiting_review`）的确认卡片里出现；按钮不带原因（`markNotSubmitted(id, true)` 没传 note，事件里记成「用户说不投了」）。候选池的「不投」（skipped）倒是要求原因，两边不一致。
- **原因只在事件日志里**：`Close` 的 reason 写进 `events.jsonl`，`job.md` frontmatter 没有，台账和 Dashboard 都不显示。
- **Session 不知道**：用户在工作台点按钮结束一份申请，不回报 Session；`reportToSession` 只在申请线程一次运行结束时报。
- **结束不可逆**：状态机里 `closed` 后不能再变；点错「不投了」就回不去。
- **模型接不住**：投递段只有 `mark_submitted`；后续段 `record_status` 的 outcome 枚举里没有「投不成」；C022 后 Session 也没有这些工具。
- 小问题：`FollowupRunner.open` 用「`phase != prep`」判断后续段，准备中就结束（not_submitted）的申请也会出现后续段。

## 目标

不再按 case 加结局。只加**两个用户可选的结果 + 一段自由文本原因**，模型和按钮走同一条路；原因到处可见（工作台、Session、台账、Dashboard），点错能撤销。

不做：自动识别失败原因、原因分类 / 标签统计、改面试被拒（rejected）的流程、改「岗位下线」按钮、Session Agent 加工具、Session 侧的任何操作（只留一条被动的系统通知）、系统通知中心。不写新测试（已有测试按需跟着改）。

## 行为（输入 → 状态 → 输出）

两个选择，原因必填（一句话就行，空的不让提交）。**两种分开存**（用户决定 1）：新加两个结局，不映射到已有的 `skipped / not_submitted / withdrawn`：

| 用户选 | 当前状态（任何还没结束的阶段，决定 2） | 记成 | 说明 |
| --- | --- | --- | --- |
| 投递失败 | 候选（new / shortlisted，先转为申请）、准备中（任何 prep_state）、投递以后（applied … offer） | `closed:apply_failed` | 想投、投不成：站点拒收、重复投递、名额满、资格系统卡住……原因写原话 |
| 不投了 | 同上 | `closed:gave_up` | 用户决定不投 / 不再继续 |

为什么不复用：`skipped` 是候选池的「不投」（没转申请、不可撤销的旧规则不动）；`withdrawn` 是「在网站上撤回了申请」这个动作（后续段 `record_status` 仍用）；`not_submitted` 语义和准备中的 `gave_up` 一样，但只覆盖 prep，所以只留给旧记录读取，新的「不投了」（包括停在提交前卡片里的）一律记 `gave_up`，界面上 `not_submitted` 也算「不投了」显示。

- 输入：工作台头部「更多」里两项「投递失败…」「不投了…」，点开一个小框：一个输入框「原因（手动写）」+ 确定 / 取消。停在提交前的确认卡片里「不投了」也改成弹这个框。
- 状态：`job.md` 写 `outcome` + 新字段 `outcome_reason`（原话）+ `closed_at` + `reopen_to`（记之前的状态标签，撤销用）；`events.jsonl` 一条 `closed`（`actor=user`，reason=原话；候选还多一条 `converted`）；申请线程在跑时先停下、关掉它的标签页（同 `/not-submitted`）。所有其它结束（被拒、撤回、岗位下线……）有原因时也写 `outcome_reason`、`closed_at`。
- 输出：
  - **工作台**：头部状态显示「已结束 · 投递失败」，下面一行原因；投递段只读。头部多一个「撤销」（见下）。后续段只在投递过才出现（`applied_at` 有值），准备中结束的不出现。
  - **Session**：往该 Job 的 Session（申请线程的 Session，没有时 `discovered_in`）写一条系统通知 note：「X · 岗位：记为投递失败。原因：……（工作台里可撤销）」。复用 `SessionRunner.report`，不唤醒模型。
  - **Dashboard / 台账**：见下。
- **撤销**（决定 3：两种都能撤，任何阶段）：`closed:apply_failed`、`closed:gave_up` → 回到 `reopen_to` 记的状态（`lead:new / shortlisted`、`prep:<任何>`、`applied … offer`），证据原样保留；回到投递以后的阶段时重新排 `next_check_at`。只有用户能做，记一条 `reopened` 事件（reason 写「撤销「投递失败」（原因：…）」），清掉 `outcome_reason`、`closed_at`、`reopen_to`，Session 再写一条 note。`withdrawn`、`rejected` 等别的结局、没有 `reopen_to` 的旧记录不撤销。

### 模型也能记（同一条路）

- 工作台两段都加一个工具 `record_outcome(kind: apply_failed | gave_up, reason)`，执行就是上面的同一个 `JobLedger.recordOutcome`，记下后写 Session 通知。
- 决定 5：是不是失败由用户决定。提示词：用户在对话里**明确**说「这个投不了 / 不投了 / 投递失败了，因为……」时用它，reason 用用户的原话（可略写）。决定 4：用户没说原因时先 `ask_user` 问原因，答了再记（工具也拒绝空原因）。页面上**自己看到**像是投不成（重复投递、名额已满、系统拒收）时，不自己记，用 `ask_user` 把页面原文给用户，由用户决定。
- 后续段的 `record_status` 不动（rejected / withdrawn / ghosted 还走它）。

## Dashboard 与台账

- `submitted()`（`metrics.ts`）排除 `closed:apply_failed`：投不成不算「投递」，也不进漏斗，即使之前记过投递。投递以后才 `gave_up` 的照算投递（确实投出去过），漏斗里按它走到过的最远一层算。
- 决定 7：KPI 加一格「没投成 / 不投了」= `apply_failed` + `gave_up`（含旧的 `not_submitted`）个数。
- 新面板「没投成 / 不投了」（`dashboard/Outcomes.tsx`）：两种放在一起（候选阶段的 skipped 已在候选池显示「不投原因」，不重复）：类型 chip（投递失败 / 不投了）· 公司 · 岗位 · 原因 · 日期 · 「工作台」「撤销」。
- 申请总表「已结束」组：阶段后面跟原因（截断，悬停看全文）；看板卡片悬停显示原因；时间线结局标签加 `apply_failed`「投递失败」。
- 台账：`applications.md` 加「结束原因」列，csv 加 `outcome_reason`、`closed_at`；`leads.md` 的「不投原因」不变。`dashboard.md` 快照（如已生成）带「没投成」个数。

## 和已有状态的关系（不新增重复概念）

- `prep_state = failed`：Agent 跑错，可重试，不变；用户判断「投不成了」才变 `apply_failed`。
- `posting_closed`：岗位下线仍是自己的按钮和结局（它对候选也有意义）；用户选「投递失败」写「岗位下线了」也可以，不强制归并。
- `hard_gap`：只是资格结论，不改状态；用户因此不投时原因里写。
- 面试被拒：仍是 `rejected`（记挂在哪一轮），不走 C024。
- `not_submitted`、`withdrawn`、`skipped`：继续用，只是补上原因输入和统一入口。

## 要改的地方

后端：
- `domain/Outcome`（加 `APPLY_FAILED`、`GAVE_UP`）；`domain/Transition`（加 `Reopen`）；`domain/ApplicationStateMachine`（两种允许任何未结束阶段结束、只许用户；`Reopen` 只许用户、只许这两种）；`domain/JobEvent.Type`（加 `REOPENED`）。
- `persistence/JobLedger`（`recordOutcome(outcome, reason)`、`reopen()`，写 / 清 `outcome_reason`、`closed_at`、`reopen_to`；所有 Close 都写 `outcome_reason`、`closed_at`）；`persistence/TrackerWriter`（新列）。
- `api/JobController`（`POST /api/jobs/{id}/outcome` `{kind, reason}`、`POST /api/jobs/{id}/reopen`；`/not-submitted` 的 giveUp 改走 `outcome`）；`api/Views`（`JobView.outcomeReason`、`closedAt`、`canReopen`）。
- 回报 Session：`ApplicationRunner.noteToSession`（内部用现有 `reporter`），`JobController` 和两段的工具在 outcome / reopen 后调用。
- 工具：`tools/FollowupTools` 加 `record_outcome` 定义；`harness/runtime/WorkbenchTools`（两段共用工具）执行；`HarnessLoop`、`FollowupLoop` 带上 `SessionNote`；`agents/ApplyAgent`、`agents/FollowupAgent` 提示词各一段。
- `workflow/FollowupRunner.open`：改成「投递过才有后续段」（`applied_at` 有值或阶段在 applied 之后）。

前端：
- `api.ts`（`recordOutcome`、`reopen`，`JobView` 加字段）；`status.tsx`（`OUTCOME.apply_failed`；新的 `OutcomeDialog`；`SubmitConfirm` 的「不投了」改用它）。
- `pages/JobPage.tsx`（「更多」里两项 + 撤销 + 原因行）。
- `dashboard/metrics.ts`、`Kpis.tsx`、`AppsTable.tsx`、`Timeline.tsx`、`Board.tsx`（悬停原因）、新 `dashboard/Outcomes.tsx`、`Dashboard.tsx` 挂面板；`styles.css` 少量。

## 怎么确认能用

- `mvn -B clean verify`、`npm run build` 退出码 0。
- 用户在本机：
  1. 一个准备中的申请，工作台「更多 → 投递失败」，写「系统提示已投过同类岗位」→ 头部显示「已结束 · 投递失败」和原因；发现它的 Session 里多一条通知；Dashboard KPI「没投成」+1、面板里有这一行、投递数和漏斗不变；`tracker/applications.md` 有原因。
  2. 停在提交前的卡片点「不投了…」→ 弹框写原因 → `closed:gave_up`，面板里显示「不投了」+ 原因。
  3. 面板里点「撤销」→ 回到记之前的状态（例子 2 回到「等你审阅」），工作台能接着做，Session 再多一条通知。
  4. 在工作台对话里说「这个不投了，地点不合适」→ 模型调 `record_outcome`，结果同 2；只说「这个不投了」→ 模型先问原因。

## 已确认（用户，2026-10-31）

1. **区分**：「投递失败」「不投了」是两个结局，分开存：`apply_failed`、`gave_up`。不映射到 `skipped / not_submitted / withdrawn`（为什么见「行为」）。
2. **都标记**：两种在任何还没结束的阶段都能记（候选、准备中、投递以后）。
3. **能够撤销**：两种都能撤销，任何阶段；撤销回到记之前的状态（`reopen_to`），记 `reopened`。
4. **必须填写**：原因必填，按钮和模型两条路都是；用户让 Agent 记但没说原因时，Agent 先问原因再记。
5. **是不是失败我来决定**：Agent 不自己判断失败 / 放弃。用户也可以在工作台对话里明确告诉 Agent 记（两段都有 `record_outcome`，只在用户明确说了时用）；Agent 在页面上看到失败迹象时用 `ask_user` 告诉用户，不记。
6. **不和 session 说，我手动操作**：Session Agent 不加工具、Session 侧不做处理，用户在工作台里手动操作。「session 保留记录」保留为一条被动的系统通知写进 Session 的对话（不唤醒模型）。
7. **两个放在一起**：Dashboard 一格 KPI 数两种，一个面板列两种（chip 区分类型 + 原因 + 日期 + 工作台 / 撤销）。口径：投递失败不算投递（即使之前记过投递）；投递以后才不投了的照算投递。

## 回写的 Master（用户确认施工，2026-10-31，已改）

- `tracking.md`：outcome 表加 `apply_failed`、`gave_up`（`not_submitted` 标为旧记录、补上 `posting_closed`）；新小节「投递失败 / 不投了」（谁决定、何时能记、原因必填、分开存、撤销、Session 留记录、和其它状态的关系）；Dashboard KPI 口径和「没投成 / 不投了」面板；申请总表 / 看板 / 台账的原因。
- `memory.md`：`job.md` frontmatter 加 `outcome_reason`、`closed_at`、`reopen_to`；`outcome` 注释补两种；`events.jsonl` 注释加 `reopened`。
- `workflow.md`「状态」：`closed` 终态的例外（这两种可由用户撤销，回到 `reopen_to`）。
- `ui.md`：工作台「更多」里的投递失败… / 不投了…、原因框、结束行与撤销、Dashboard 面板；后续段只在投递过之后出现。
- `agent-runtime.md` 7.9：两段加 `record_outcome`（只按用户明确的话记、没原因先问、看到失败迹象用 ask_user）；后续段只在投递过之后。

## 施工记录（2026-10-31）

- 后端：`Outcome` 加 `APPLY_FAILED`、`GAVE_UP`（`userMarked()`、`userLabel()`）；`Transition.Reopen(to, reason)`；`JobEvent.Type.REOPENED`；状态机：两种在 `phase != closed` 时都能结束、只许用户，`Reopen` 在「已结束」检查之前处理，只许用户、只许这两种，按 `reopen_to` 标签还原（`lead:new / shortlisted`、`prep:<state>`、`applied … offer`），证据原样保留。`JobLedger.recordOutcome`（候选先 Convert，写 `outcome_reason`、`closed_at`、`reopen_to`，清 `next_check_at`，同一结果再记原样返回）、`reopen`（还原、清三个字段、回到投递以后时重排 `next_check_at`）、`canReopen`、`outcomeNote` / `reopenNote`；`transition`、`recordStatus`、`recordRound` 的结束都写 `outcome_reason`、`closed_at`。`TrackerWriter`：`applications.md` 加「结束原因」列，csv 加 `outcome_reason`、`closed_at`。
- 接口：`POST /api/jobs/{id}/outcome {kind, reason}`（停申请线程 → 记 → 关页 → Session 通知）、`POST /api/jobs/{id}/reopen`（记 → Session 通知）；`/not-submitted` 的 `giveUp=true` 改走 `outcome(gave_up)`，原因必填。`JobView` 加 `outcomeReason`、`closedAt`、`canReopen`。`ApplicationRunner.noteToSession` 复用运行结束回报的 `reporter`（`SessionRunner.report`：记进对话，不唤醒模型）；申请线程结束时，这次运行里结束了的（`record_outcome`）也释放页面。
- 工具：`FollowupTools.RECORD_OUTCOME` 定义；`WorkbenchTools` 共用工具里执行（空原因拒绝并提示先 `ask_user`；同一结果不重复记、不重复通知），`Where` 加 `session`；新 `harness/runtime/SessionNote` 接口（harness 不依赖 workflow），`HarnessLoop`、`FollowupLoop` 构造时带上，`ApplicationRunner` / `FollowupRunner` 传 `noteToSession`。`ApplyAgent`、`FollowupAgent` 提示词各加一段（只在用户明确说了时记、没原因先问、看到失败迹象 ask_user）。
- 顺手的 bug：`FollowupRunner.open` 改成「投递过才有后续段」：在投递以后的阶段，或者已结束且有 `applied_at` / 投递证据。准备中就结束的（不投了、投递失败、岗位下线）不再出现后续段。
- 前端：`api.ts`（`recordOutcome`、`reopen`、`OutcomeKind`、`JobView` 新字段）；`status.tsx`（`OUTCOME` 加两种、`OUTCOME_KIND`、`OutcomeDialog` 原因框，`SubmitConfirm` 的「不投了…」改弹它）；`JobPage.tsx`（「更多」里「投递失败…」「不投了…」、标题下的结束行：结局 + 原因 + 时间 + 撤销；投递段锁定提示改用中文阶段名）；`dashboard/metrics.ts`（`submitted` 排除 `apply_failed`、`markedOutcome`）、`Kpis.tsx`（第 10 格）、新 `Outcomes.tsx`、`Dashboard.tsx` 挂面板、`AppsTable.tsx`（已结束行跟原因）、`Board.tsx`（悬停原因）；`styles.css`（KPI 10 列、原因框、结束行、面板）。时间线结局标签跟着 `OUTCOME` 自动有了。
- 已有测试：`ViewsWorkspacesTest` 跟着 `JobView` 加三个参数；状态机测试不用改（`not_submitted` 仍合法）。
- 验证：`mvn -B clean verify` 退出码 0（单元 662、IT 1，全过）；`npm run build` 退出码 0。施工时用一次性测试（跑完删掉）走过一遍台账：候选记投递失败 → 撤销回 `lead:new`；投递后不投了 → 后续段在 → 撤销回 `applied` 并重排查看；空原因被拒。截图用本地 mock（虚构数据）+ 无头 Chromium：原因框、停在提交前的「不投了…」、结束的工作台头部、撤销后、Dashboard KPI 与面板（亮 / 暗 / 手机）、申请总表原因，存在 scratchpad `shots-c024/`。真实模型跑 `record_outcome`：未运行。
