# C024-fix · 状态用词分清领域、Failures、时间线一份一条

- 对应 Master：[tracking.md](../../master/tracking.md)「阶段」「Dashboard 组件」；[ui.md](../../master/ui.md)「申请工作台」「Dashboard」；[agent-runtime.md](../../master/agent-runtime.md) 7.1（申请线程状态）。
- 依据：用户（2026-10-31）：「在投递工作台结束投递之后，在 dashboard 上也会将这个工作列为『结束』状态，而不是投递结束/完成 等待下一步的状态……投递失败/不投了 应该有个专门的失败栏……timeline 有些太挤了……应该用一天天圆角长条显示 title，其中的具体内容点击后才能查看」。
- 状态：**已施工，等用户在本机试**（2026-10-31）。第 10 节的问题用户已确认（答案写在每条后面）；和原设计不同的地方以第 4、5 节的「已确认」说明为准。施工记录见第 11 节。

## 1. 报告的 bug：根因

一份申请有两条线，但界面把它们的「结束」写成了同一个词：

- **申请线程（投递段的 Agent 循环）的运行状态**。`ApplicationRunner.status()`（`backend/.../workflow/ApplicationRunner.java:635-639`）在「用户已经定下」（`JobLedger.userDecided`，`JobLedger.java:149`：`phase != prep` 或 prep 是 `user_submitting / verification_pending / unconfirmed`）且不在跑时，对外返回 `done`（`AgentRun.java:54`）。含义是「**投递段结束了，申请 Agent 不再接着做**」。
- **申请本身的生命周期**（`phase` + `prep_state` + `outcome`）：标记已投递后是 `applied`，申请远没结束。

前端 `APPLICATION.done = "已结束"`（`frontend/src/status.tsx:23`），`ApplicationChip` 原样显示（`status.tsx:205`）。看板卡片（`Board.tsx:32`）和申请总表（`AppsTable.tsx:51`）在阶段文字后面无条件挂这个 chip，于是「已投递」列里的卡片带着「已结束」。同一个词也是 `PHASE.closed = "已结束"`（`status.tsx:47`、`metrics.ts:15` 看板最后一列），用户看到的就是「这份工作已结束」。

只有工作台头部和 Session 右栏的 `JobState compact`（`status.tsx:230`）把 `done` 过滤掉了；其余地方都没过滤。之前的 mock（`mock-c.mjs`、`mock-d.mjs`）给已投递的 Job 写的是 `application: null`，所以没看出来。

## 2. 审计：同一个词、不同领域

| 位置 | 现在显示 / 判断 | 实际含义 | 应该是 |
| --- | --- | --- | --- |
| `status.tsx:23` `APPLICATION.done` | 「已结束」 | 投递段结束，申请线程不再接着做 | 「投递段已结束」，只在工作台里出现；卡片和表格不显示 |
| `Board.tsx:32` `<ApplicationChip>` | 已投递 / 筛选 / 面试列的卡片挂「已结束」 | 同上（报告的 bug） | 卡片只显示申请阶段；运行状态 chip 只在「在跑或要你做事」时显示 |
| `AppsTable.tsx:51` | 「已投递 已结束」 | 同上 | 同上 |
| `ProcessBlock.tsx:93` → `JobState` 非 compact（`status.tsx:236-241`） | Session 对话里的岗位卡「已投递」+「已结束」 | 同上 | 同上（改 `JobState` 一处） |
| `JobPage.tsx:433` `StatusNote` | 已投递后右栏写「状态：已结束」 | 投递段结束 | 「投递段已结束（已投递，后续在左边对话里）」或干脆不显示这一行 |
| `JobLedger.userDecided` → `done`，prep 为 `user_submitting / verification_pending / unconfirmed` | 准备中列卡片「你在提交」+「已结束」 | 用户在提交 / 待核实，申请没结束 | 同上：不挂运行 chip |
| `status.tsx:47` `PHASE.closed`、`metrics.ts:15` 看板列 | 「已结束」装着 rejected / ghosted / withdrawn / accepted / declined / posting_closed **和** apply_failed / gave_up | 正常流程的终点 vs 没投成 | 「已结束」只放正常结局；apply_failed / gave_up 进失败栏 |
| `Timeline.tsx:30-33, 67-73` | 投递后又记 apply_failed / gave_up 的申请照样一行，灰点「其它结束」 | 没投成 | 不进时间线 |
| `Weekly.tsx:16` | 按 `appliedAt` 数，含 apply_failed | 「投递」口径（KPI 已排除 apply_failed） | 用 `metrics.submitted` 同一口径 |
| `status.tsx:20` `APPLICATION.failed`、`status.tsx:33` `PREP.failed` | 都是「失败」；看板准备中卡片会出现「失败」+「失败」 | 申请 Agent 跑出错，可重试（`ApplicationRunner.java:947` 写 prep failed） | 「出错了」（可重试）；「失败」只留给「投递失败」 |
| `status.tsx:21` `ready`「停在提交前」与 `PREP.awaiting_review`「等你审阅」 | 同一状态两个说法，非 compact 时两个 chip 都出 | 同一件事 | 卡片只出阶段文字（「等你审阅」）；运行 chip 不重复 |
| `status.tsx:19` `waiting_user`「等你」与 `PREP.needs_input`「等你回答」 | 两个 chip 重复 | 同一件事 | 同上 |
| `status.tsx:57` `OUTCOME.not_submitted`「最终未投」 | `metrics.markedOutcome` 把它当「不投了」 | 旧记录的不投了 | 标签改「不投了」 |
| `status.tsx:11` `LEAD.skipped`「不投」 vs `OUTCOME.gave_up`「不投了」 | 一字之差 | 候选阶段没转申请就跳过 vs 转了申请后放弃 | 见问题 4 |
| `App.tsx:165-179` `wsDotClass` | 后续段在跑时侧栏点是绿色「已投递」，不闪；已结束 / 没投成和从没开始一样没有点 | 后续段的运行状态没有传出来 | 后续段在跑 → running；closed → 灰点；失败栏的 → 红点 |
| `App.tsx:329` 侧栏 title、`Views.java:105` `WorkspaceSummary.label` | 悬停显示 `closed:apply_failed`、`prep:needs_input` 原值 | 机器值（注释写「用户看到的阶段名称」） | 前端用同一个 `labelText(label)` 转中文 |
| `HarnessLoop.java:1213` | finish 的工具结果「已结束，交给用户审阅。」（给模型看） | 这一轮填写结束 | 「这轮填写结束，交给用户审阅。」（小，可选） |
| `ApplicationStatus.java:11-12`、`AgentRun.java:53` 注释 | 「done 是用户已经定下了（已提交、结束了）」 | 投递段结束 | 注释写清「是线程结束，不是申请结束」 |

没问题、不改的：台账 md / csv（`TrackerWriter` 用 `label` 原值和「结束原因」列，口径对）；`JobBucket`（`applied` 组本来就是「投递以后、没结束」）；Session 的 `running / idle / interrupted`；工作台头部 `endnote`（`JobPage.tsx:264-279`，只在 `phase=closed` 时出现）；`FillSegment` 分隔线「已投递 · 后续」。

## 3. 用词：三个领域，各管各的

| 领域 | 值 | 给用户看的字 | 在哪显示 |
| --- | --- | --- | --- |
| **运行状态**（一个 Agent 循环：投递段、后续段、Session） | 投递段 `queued / running / waiting_user / ready / failed / interrupted / done`；后续段、Session `running / idle / interrupted` | 排队中 / 正在做 / 等你 / 填好了 / 出错了 / 中断了 / 投递段已结束；空闲不显示 | 工作台、侧栏的点；卡片和表格上只在「在跑或要你做事」（queued / running / waiting_user / failed / interrupted）时出一个小 chip |
| **申请生命周期**（`phase` + `prep_state` + `outcome`） | prep 的子状态；applied…offer；closed:outcome | prep 用子状态的字；applied「已投递 · 等下一步」；closed「已结束 · 被拒」等；apply_failed / gave_up「投递失败」「不投了」 | 卡片、表格、看板列、时间线、台账的主文字 |
| **候选**（`lead_status`） | new / shortlisted / skipped / closed / converted | 新发现 / 想投 / 不投（见问题 4）/ 已下线 / 已转申请 | 候选池 |

规则：**主文字永远来自申请生命周期**；运行状态只是附加的小 chip，`done` 和与阶段重复的 `ready` 永远不上卡片。实现上是 `status.tsx` 一个 `runChip(job)`（返回要不要挂、挂什么），`ApplicationChip` / `JobState` / Board / AppsTable 都走它。后端值 `done` 不改名（API、工具、测试都在用），只改注释和前端的字。

Dashboard 的分区在 `metrics.ts` 一个函数里算（前端只读 `phase`、`label`，不新增状态机）：

- `lane(job)`：`flow`（prep…offer）、`ended`（closed 的正常结局：accepted / declined / rejected / withdrawn / ghosted / posting_closed）、`dropped`（apply_failed / gave_up / 旧 not_submitted，即现有 `markedOutcome`）。
- 看板、时间线只吃 `flow + ended`；失败栏只吃 `dropped`；KPI、漏斗口径不变（已经按 C024 排除 apply_failed）；每周节奏改用 `submitted`。

## 4. 失败栏（Failures）

> **已确认（用户，2026-10-31，问题 4）**：候选池的「跳过」（`lead_status: skipped`）和投递失败 / 不投了进**同一个池子，叫「Failures」**。每行类型 chip（跳过 / 投递失败 / 不投了）+ 原因 + 日期 + 链接（工作台 / 详情；用户记的两种有撤销；跳过的没有恢复路径，只给「详情」「岗位」链接）。跳过的不再出现在候选池列表里，候选池标题旁给「Failures n」入口。存的值（`skipped` 等）不变，只改显示和分组。位置在看板下面，替换 C024 面板，不重复；看板「已结束」列、时间线、每周节奏都不含它们。下面原文里的「失败栏」都按这个理解。

- **用现有的 C024「没投成 / 不投了」面板当失败栏**，不在看板上加第 8 列，避免同一批数据出现两次。标题改「失败栏 · 没投成 / 不投了」，挪到看板正下方（现在在漏斗那两列和看板之间），行内容不变（类型 chip、公司 · 岗位、原因、日期、工作台、撤销）。
- 看板「已结束」列、时间线、每周节奏不再出现 `dropped`。
- 申请总表：`dropped` 从「已结束」组拆出来，放在最后单独一组「没投成 / 不投了」，阶段格后面照旧跟原因。
- `posting_closed`（岗位下线）不是用户记的失败，留在「已结束」（见问题 3）。

## 5. 时间线：一天一条圆角长条

> **已确认（用户，2026-10-31），替换本节原设计**：不按天，而是**每份申请（投递）压成一条圆角长条**：一行写公司 · 岗位、走到哪一步、当前阶段 chip、投递日到最近动静。点一条展开详情：岗位信息（城市、链接、核验）、用的简历（`resumeChoice`）和匹配理由、阶段经过、轮次、结束原因、工作台链接。按最近动静排，先显示最近 8 份，其余「更早 N 份」。Failures 里的不进时间线。下面原文保留作记录。

现在是 SVG 甘特图：每个已投递申请一行（26px），横轴是日期，`min-width: 760`；申请一多就很长，还要横向滚。

改成按天的列表（替换 `Timeline.tsx` 的 SVG，不用图表库）：

- **一天一条**：只列有事件的日子，新的在上。条是圆角长条，左边日期（`10-31 周五`，今天标「今天」），右边是当天每件事的紧凑标题：`公司 · 岗位` + 一个很小的事件标记（投递 / 筛选 / 笔试 / 面试 / Offer / 被拒…），一行放不下就省略，末尾「+N」。
- **点开看详情**：点一条展开在下面，每件事一行：事件、阶段 / 结局、轮次进度（`RoundProgress`）、结束原因、「工作台」链接；再点收起。一次可以展开多条，不记状态。
- **事件来源**：`events` 里的 `converted` 以外的 `phase_changed`、`closed`（正常结局），加 `appliedAt`；`dropped` 的申请整份不进。
- 默认显示最近 14 个有事件的日子，底部「更早的 N 天」按一下再展开。高度大约每天 36px，10 份申请也就几百像素。
- 图例删掉；配色沿用现在的 `--good`（Offer）、`--crit`（被拒）、`--ink-2`（推进）。

看板是否也要压扁见问题 1。

## 6. 要改的地方

前端：

- `frontend/src/status.tsx`：字（`done`、两个 `failed`、`not_submitted`、`applied` 的卡片说法）；`runChip(job)`；`JobState` 非 compact 走它；`labelText(label)`（侧栏 title、`FollowUps` 的兜底用）。
- `frontend/src/dashboard/metrics.ts`：`lane(job)`；看板列说明。
- `Board.tsx`：只吃 `flow + ended`，chip 走 `runChip`。
- `AppsTable.tsx`：chip 走 `runChip`；`dropped` 单独一组。
- `Timeline.tsx`：重写成按天长条。
- `Outcomes.tsx`、`Dashboard.tsx`：标题、位置（看板下面）。
- `Weekly.tsx`：用 `submitted`。
- `pages/JobPage.tsx`：`StatusNote` 不再写「状态：已结束」。
- `App.tsx`：`wsDotClass` 加后续段在跑、已结束、失败栏三种；title 用 `labelText`。
- `styles.css`：日条、展开行、两个新的点颜色。

后端（小）：

- `api/Views.java`：`JobView`、`WorkspaceSummary` 带后续段运行状态 `followup`（`FollowupRunner` 已有 `running / idle / interrupted`），给侧栏和看板 chip 用（见问题 2）；`frontend/src/api.ts` 对应字段。
- `workflow/ApplicationStatus.java`、`domain/AgentRun.java`：注释写清 `done` 是投递段结束。
- `harness/runtime/HarnessLoop.java:1213`：给模型的那句话（可选）。

## 7. 怎么确认能用

- `npm run build` 退出码 0；碰了后端时 `JAVA_HOME=… mvn -B clean verify` 退出码 0。
- scratchpad mock 补齐这几种 Job（这次的 bug 就是 mock 里没有）：已投递 + `application: done`；prep `user_submitting` + `done`；后续段在跑；apply_failed（投递后才记）；gave_up；rejected；posting_closed；20 多天里分散的事件。无头 Chromium 截图核对：
  - 看板「已投递」卡片没有「已结束」；准备中卡片没有两个一样的 chip。
  - apply_failed / gave_up 只出现在失败栏和总表最后一组，看板、时间线里没有。
  - 时间线一天一条，点开有详情；整块高度比原来短。
  - 侧栏：后续段在跑时点在闪，失败栏的是红点，悬停是中文。
- 真实模型、真实 Chrome：未运行（这一步不需要）。

## 8. 不做

- 不新建状态机、不改 `phase / prep_state / outcome / lead_status` 的取值，不改后端 `done` 这个值。
- 不改台账 md / csv 栏目。
- 不改 KPI、漏斗口径（C024 已经对）。
- 不改工作台两段的结构（C022）。

## 9. 建议回写的 Master（未改，等用户确认）

- `tracking.md`：在「阶段」后加一小节「三个领域的状态」（第 3 节那张表，四行以内）；「Dashboard 组件」③ 看板「已结束列不含 apply_failed / gave_up」；⑤ 时间线改成「按天的圆角长条，点开看当天详情；不含没投成 / 不投了」；⑧ 改名「失败栏」，位置在看板下面，看板、时间线、每周节奏不重复显示。
- `ui.md:48`：同上的失败栏位置与时间线说法。
- `agent-runtime.md` 7.1：一句话「对外的 `done` 是投递段结束，不是申请结束」。
- 问题 4 选了改字时，`memory.md:148` 候选 `skipped` 的中文说法。

## 10. 待用户决定（每条带建议）

1. **已确认：按建议**（看板卡片压一行，一列超过 6 张折起）。**「方块」指的是看板还是时间线？** 现在的时间线是一行一条的横向图，看板才是一列列堆起来的卡片。建议：时间线按第 5 节改；看板卡片也压成一行（`公司 · 岗位` + 一个状态字），每列超过 6 张折起「还有 N 张」。
2. **已确认：要**（后端 `JobView`、`WorkspaceSummary` 带 `followup`）。**后续段的运行状态要不要传到侧栏和看板？** 建议要：后端只多带一个字段，侧栏能看到「后续段 Agent 正在做」。
3. **已确认：留在「已结束」**。**岗位下线（posting_closed）放哪？** 建议留在「已结束」：不是用户记的失败，也不能撤销。
4. **已确认：跳过的候选和投递失败 / 不投了放进同一个「Failures」**（见第 4 节）；候选 `skipped` 的字按建议改「跳过」，便于在同一个池子里分开。**候选的「不投」和申请的「不投了」要不要分开说？** 建议候选 `skipped` 改叫「跳过」，「不投了」只留给转了申请以后的放弃。
5. **已确认：卡片仍写「已投递」，不加「· 等下一步」**；bug 靠不挂运行状态的「已结束」chip 修。**「已投递」卡片上写什么？** 建议看板列名仍是「已投递」，卡片和表格的阶段字写「已投递 · 等下一步」，到了「该查看了」照旧挂黄 chip。

## 11. 施工记录（2026-10-31）

后端：

- `FollowupRunner.statusOf(Job)`：不按 id 重新找 Job，直接给列表里每个 Job 的后续段状态（没有后续段为 null）；`status(jobId)` 共用同一段判断。
- `Views.JobView`、`WorkspaceSummary` 多一个 `followup`；`ProjectController`（Project 主页、全局页、侧栏）和 `JobController` 传进去，`SessionController` 不传（null）。
- `Views` 的事件里另带候选「跳过」那一条（`lead_status_changed`，`to = lead:skipped`），Failures 显示日期。
- `AgentRun.DONE`、`ApplicationStatus` 注释写清是投递段结束；`HarnessLoop` finish 的工具结果改「这轮填写结束，交给用户审阅。」。
- `ViewsWorkspacesTest` 的构造参数跟着多一个。

前端：

- `status.tsx`：`APPLICATION` 的字（填好了 / 出错了 / 中断了 / 投递段已结束）、`PREP.failed` 出错了、`OUTCOME.not_submitted` 不投了、`LEAD.skipped` 跳过；新增 `FOLLOWUP`、`DROPPED_OUTCOMES`、`labelText(label)`、`runChip(job)` / `RunChip`（替换 `ApplicationChip`）；`phaseText` 的 closed 走 `labelText`（投递失败 / 不投了不叫「已结束」）；`JobState` 非 compact 走 `RunChip`。
- `metrics.ts`：`lane(job)`（flow / ended / dropped）、`skippedAt(job)`。
- `Board.tsx`：只吃 flow + ended，卡片一行，超过 6 张折起；`AppsTable.tsx`：`RunChip`，dropped 单独最后一组「Failures · 投递失败 / 不投了」。
- `Outcomes.tsx` → `Failures.tsx`：三类合一，先显示 8 行，放在看板下面；候选池不列跳过的，标题旁「Failures n」跳回 Dashboard 的 Failures（`TabBody` 多一个 `onTab`）；`LeadPool` 的「不投」改「跳过」。
- `Timeline.tsx` 重写：一份一条圆角长条 + 展开详情；`Weekly.tsx` 用 `submitted`；`FollowUps.tsx` 兜底用 `labelText`。
- `App.tsx`：侧栏点加后续段在跑（闪）/ 中断（黄）、正常结束灰点、Failures 红点；悬停用 `labelText`。`JobPage.tsx`：`done` 时右栏写「投递段已结束 · 申请现在是「…」」。
- `styles.css`：一行卡片、折叠按钮、时间线长条和详情、两个新点色；看板列宽从 168 降到 132（卡片变窄后不用横向滚）。

验证：见 `dev-log.md` 同日条目。真实模型、真实 Chrome：未运行（这一步不需要）。
