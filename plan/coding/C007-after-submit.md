# C007 · 提交以后：记下「已投递」、定期提醒查看、在对话里反馈（里程碑第 8 段改版）

- 依据：用户给出（2026-10-23）：
  1. 用户在 Chrome 里亲自点了提交以后，JobApply 不知道。由用户在对话里主动说，或者对话里出现一个点击确认。
  2. 状态查询（里程碑第 8 段）不让 Chrome MCP 去登录官网查；改成维护一张时间表，大约每 3–5 天提醒用户去看邮箱或官网，用户在 Session 对话里反馈。
- 目标：投递之后，台账里有「投了哪些、现在在哪一步、最近一次查看是什么时候」，而且不用 Agent 去登录任何站点。
- 不做：Agent 自己登录官网或读邮箱；系统通知（tracking.md 定的后置）；轮次表、复盘、题库（第 6 段）；匹配和额度（第 5 段）。不写测试。

## 1. 记下「已投递」

两种入口，结果一样：

- **对话里说**：用户说「澜川那个投了」「刚提交了」。对话 Agent 用新工具 `mark_submitted(job, note?)` 记下；同一家公司有多个岗位时先问是哪一个。
- **点击确认**：一个申请停在提交前（`prep_state = awaiting_review`）时，
  - 申请工作台右侧显示「我已经在 Chrome 里提交了」「还没提交 / 不投了」两个按钮；
  - 这个申请所在 Session 的对话里出现同样的确认卡片（「澜川 · 后端开发 已经填好，提交了吗？」）。
  - 申请 Agent 结束时页面已经像投递成功（现在的 `SubmitWords.looksSubmitted`）的，卡片写「页面像是已经投递成功了，确认一下」。
- 接口：`POST /api/jobs/{id}/submitted`（`{ note? }`），对话工具和按钮都走它。
- 状态：`awaiting_review → user_submitting → applied`，`applied_at` 记用户确认的时间。「还没提交 / 不投了」走现有的 `paused` 或 `closed:not_submitted`。

**证据（用户选 A，2026-10-23）**：用户的确认就算有效证据。`Evidence` 加一种 `user-reported`（原文是用户的话或「点了确认」，时间是确认时间），直接进入 `applied`。这时如果申请或调研标签页上正好是成功页，系统顺手只读一次快照，作为 `success-page` 证据一起记下；读不到也不影响。`unconfirmed` 只留给用户自己说「不确定提交成功了没有」的情况。

## 2. 查看时间表

- 每个 `applied` 之后、没有结束的申请有一个 `next_check_at`：确认投递时设为 4 天后；每次用户反馈后再设为 4 天后（3–5 天里取中间，Project 的 `preferences.md` 可以改）。`last_checked_at` 是用户最近一次反馈「看过了」的时间。
- 到了 `next_check_at` 的申请是「该看看了」：
  - 新建 Session 的开场里列出来（「这 3 个申请 4 天没看了：…，去邮箱或官网看看有没有新消息」）；
  - Project 主页和全局页有一栏「该查看了」，点一下打开一个 Session，第一条消息已经填好（「我看了 X 的状态：」）；
  - 侧栏的 Project 名字旁显示个数。
- 和 tracking.md 的 unknown 规则衔接：4 天提醒，7 天没有任何反馈照旧记为 `unknown`。
- 提醒只在打开应用时显示，不发系统通知、不发邮件。

## 3. 在对话里反馈

- 用户说「X 还没消息」「Y 收到笔试邀请，周五截止」「Z 挂了」。对话 Agent 用新工具 `record_status(job, phase?, outcome?, note?, next_check_in_days?)`：
  - 只看过、没变化：更新 `last_checked_at`，`next_check_at` 往后推；
  - 有变化：`phase` 走现有状态机（screening / assessment / interview / offer），结束用 `Close`（rejected / withdrawn 等）；
  - 有截止或面试时间时，`next_check_at` 设到那个日子前一天。
- 有歧义（同一家公司多个申请、说不清是哪一轮）先问用户，不猜。
- 记下来的原话进事件日志的 `reason`，`result_source` 记为 `user`。
- 对话 Agent 的开场和 `list_jobs` 带上 `last_checked_at`、`next_check_at`。

## 要改的地方（大致）

- 后端：`Evidence`（加 `user-reported`）、`JobLedger`（`markSubmitted`、`recordStatus`，写 `applied_at`、`last_checked_at`、`next_check_at`）、`ResearchTools` / `SessionLoop`（两个新工具）、`ResearchAgent` 提示词（投递确认、反馈怎么记、提醒怎么说）、`JobController`（`/submitted`）、`Views`（JobView 加三个时间、ProjectSummary 加「该查看了」个数）、台账生成（加 `last_checked_at`、`next_check_at` 两列）。
- 前端：工作台的确认按钮、对话里的确认卡片、Project / 全局的「该查看了」、侧栏个数、点了打开 Session 并填好第一句。
- master：workflow.md 里程碑第 8 段改写成这一版（已按用户指令改）；「状态」一节已按用户选的 A 改。

## 怎么确认能用

- `mvn -B clean verify`、`npm run build` 通过。
- 用户在本机：一个停在提交前的申请，在工作台点「我已经提交了」，台账里变成已投递；另一个在对话里说「投了」，同样变成已投递。把一个申请的 `next_check_at` 改到今天，新建 Session，开场里提醒；在对话里说「收到笔试邀请」，阶段变成笔试、下一次查看时间跟着变。

## 实际结果

2026-10-23 施工：

- 后端：`Evidence` 加 `user-reported`；`JobEvent` 加 `status_checked`（只看过、没变化）。`JobLedger.markSubmitted` 走 `awaiting_review → user_submitting → applied`（两步先过状态机再写），写 `applied_at`、`next_check_at`（日期，默认 4 天后，Project `preferences.md` frontmatter 的 `check_every_days` 可改）；`JobLedger.recordStatus` 写 `last_checked_at`、推后 `next_check_at`（结束时清空），阶段走 `ChangePhase`，结束走 `Close`，事件 `actor=user`、原话进 `reason`。
- 接口：`POST /api/jobs/{id}/submitted`（`{note?}`）；`POST /api/jobs/{id}/not-submitted`（`{giveUp, note?}`，false → `paused`，true → `closed:not_submitted`）。Session 取申请线程的 Session，没有时用发现它的 Session。
- 对话 Agent：新工具 `mark_submitted`、`record_status`；`list_jobs` 带投递 / 最近查看 / 下次查看，到期的标「该查看了」，新增筛选 `applied`、`check_due`；提示词加「投递以后」一段；新建 Session 的开场提示到期的申请。两个工具算有副作用（重新生成时不重做）。
- 视图：`JobView` 加 `appliedAt`、`lastCheckedAt`、`nextCheckAt`、`pageLooksSubmitted`（申请 Agent 结束语以 `SubmitWords.SUBMITTED_NOTICE` 开头）；`Counts` 加 `dueChecks`。台账 md 加「下次查看」列，csv 加 `applied_at`、`next_check_at`；投递日优先用 `applied_at`。
- 前端：工作台右栏和 Session 输入框上方的确认卡片（「我已经在 Chrome 里提交了」/「还没提交 / 不投了」→ 先放着 / 不投了）；Project 主页、全局页「该查看了」，点「我看了」在该 Project 新建 Session 并预填「我看了 X 的状态：」；侧栏 Project 名旁的个数；KPI 加「该查看了」。草稿读写移到 `frontend/src/chat/draft.ts`。
- 没做：成功页只读快照（`success-page` 补充证据）——调研标签页不是申请标签页，取申请页快照要动申请线程的浏览器队列，先不做；7 天 `unknown` 规则代码里还没有（台账「同步」列仍是 —），没有新加。
- 验证：`mvn -B clean verify` 通过（611 个单元测试 + IT）；`npm run build` 通过。本机手动检查（点确认、对话里说「投了」、改 `next_check_at` 看开场）未运行。
