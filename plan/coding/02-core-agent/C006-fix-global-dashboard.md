# C006-fix · 全局 Dashboard 按 Project 选择查看

- 对应：[C006-fix 版式](C006-fix-ui-mockup.md)（全局页 / Project 页三页签）；[C026](../04-refinement/C026-portal-status-check.md)（筛选条、查状态、查看间隔）。
- 依据：用户反馈（2026-11-07）：「这个界面应该是「全局 Dashboard」，在 project 下面……每个 project 对应的时间线状态都不一样，所以 dashboard 页面和其中的操作不应该受到 project 的干扰。可以在这个界面下添加下拉框，选择展示和追踪不同的 project 下的内容」。
- 状态：用户已确认（2026-11-07），已施工，等用户在浏览器里试；见文末「施工记录」。

## 现状

- 有两份 Dashboard，用同一套 `TabBody`（`frontend/src/dashboard/Dashboard.tsx:64-169`）：
  - 全局页 `pages/Global.tsx:7-46`：`GET /api/jobs` 拿**所有 Project 混在一起**的 Job（`Global.tsx:14`），标题写「所有 Project 汇总」（`:32`）；侧栏「全局」按钮（`App.tsx:334-336`、窄栏 `:219`），也是首页（`route.ts:21`）。
  - Project 主页 `pages/ProjectPage.tsx:10-68`：`GET /api/projects/{id}` 拿这个 Project 的 Job，同样三页签，外加新建 Session、最近 Session（`:57`、`:63-64`）。
- 混在一起时出问题的地方（全局页）：
  - KPI、漏斗、每周节奏、接下来 7 天、时间线、看板把各 Project 的申请加在一起（`Dashboard.tsx:147-166`）。
  - 筛选条的阶段 chip 是所有 Project 阶段的并集（`CheckBar.tsx:57-67`）；筛选和页签存在一个共用的 localStorage 键（`CheckBar.tsx:26`、`Dashboard.tsx:25`），在一个 Project 里选的筛选会带到别的 Project 和全局页。
  - 「每 N 天查看一次」在全局页改的是全局值（`CheckBar.tsx:217-235`），而每个 Project 可以单独设；「待追踪」显示的天数（`Dashboard.tsx:158`）在全局页上对设了单独值的 Project 是错的。
  - 查状态的进度和「停止」是整个后端一批（`FollowupRunner.java:188-232`、`StatusCheckController.java:36-49`）：在 A 的页面上看到 / 停掉的是所有 Project 的。
- 按单个 Job 的动作（核准 / 驳回、撤销、下线、标候选、开始申请、查状态排队）都按 jobId，本身不受 Project 影响。

## 目标

- 一个 Dashboard 页（侧栏原「全局」，首页），顶部一个 **Project 下拉框**；三页签（Dashboard / 候选池 / 申请总表）和上面所有动作都只针对下拉框选中的那个 Project。
- 选中的 Project 跟着 URL 和本机记住；侧栏点进别的 Project / Session / 工作台不改下拉框——只有在下拉框里换、或从 Project 页点「在 Dashboard 查看」才换。
- 不做：后端数据结构、台账、`dashboard.md` 快照的改动；新的统计口径。

## 改动

- `route.ts`：`{ kind: "global"; project?: string }`，hash `#/global/<projectId>`；`parseRoute` / `href` 跟着改。返回栈（C017-fix）照旧，`App.tsx` 的 `listedGone` 对已删 Project 的 `#/global/<id>` 当作 `#/global`。
- `pages/Global.tsx` → Dashboard 页：
  - 标题「Dashboard」，右边下拉框（`select`，列 `projects` 的标题），旁边三页签。
  - 选中：URL 里的 → 本机记住的上一次（localStorage `jobapply.dashboard.project`）→ 最近有 Session 活动的 Project → 第一个。选中的 Project 被删了就按这个顺序往下选。换选时写 URL（`replace`，不压返回栈）和 localStorage。
  - 数据改用已有的 `GET /api/projects/{id}`（和 Project 主页一样），把 `projectId` 传给 `TabBody`；不再传 `showProject`。最近 Session 放在 KPI 下面（同 Project 主页）。
  - 没有 Project 时保留现在的空提示。
- `dashboard/CheckBar.tsx`：
  - 筛选存的键加 Project：`jobapply.dashboard.checkFilter.<projectId>`。
  - 「每 N 天」永远改选中 Project 的值（已有 `projectCheckDays` 接口），「用全局」照旧；全局默认值只在设置页「运行」里改（`pages/CheckSettings.tsx` 已有）。
  - 进度只数这个 Project 的：`queued` / `running` / `done` 与这个 Project 的 jobId 取交集；查到 / 要手动登录 / 没找到按这些 Job 的 `portalCheck` 数，不用整批的总数。别的 Project 在查时，这里不显示进度、也不挡按钮。
  - 「停止」只停这个 Project 的：`POST /api/status-checks/stop` 加可选 `{ jobIds }`，带了只移出 / 停下这些（`FollowupRunner.stopChecks(List<String>)`）；不带时行为不变（工作台等别处还在用）。这是唯一的后端改动。
- `pages/ProjectPage.tsx`：去掉三页签，留标题、新建 Session、最近 Session，加一个按钮「在 Dashboard 查看」→ `#/global/<id>`（见待确认 1）。
- `App.tsx`：侧栏「全局」改名「Dashboard」（图标不变）；跳到 `{ kind: "global" }` 的地方（删除后回首页等）不带 Project，由页面按上面的顺序选。
- `dashboard/Dashboard.tsx`：`showProject` / `projectTitle` 只剩全局混看用，没有调用方后删掉；候选池分组回到「公司 → 地点」。

## Master Plan 要改的措辞（待用户确认后回写）

- `plan/master/ui.md` 第 13–14 行表格改成：
  - 「什么都没选（首页），或选「Dashboard」 | **Dashboard**：顶部下拉框选一个 Project，下面是这个 Project 的 Dashboard / 候选池 / 申请总表；不把多个 Project 的申请混在一起看（每个 Project 的投递时间和状态不同）。选中的 Project 记在 URL 和本机，侧栏切换不影响它」
  - 「一个 Project | **Project 主页**：「新建 Session」、最近的 Session 和交接摘要、「在 Dashboard 查看」」
- `ui.md` 第 56–57 行：「Dashboard（全局页、Project 主页）」改「Dashboard」；「每 [N] 天」改「改的是下拉框选中的 Project 的值；全局默认值在设置页「运行」」。
- `ui.md` 第 37 行「全局页按 Project → 公司 → 地点」删去（只剩按公司 → 地点）。
- `plan/master/tracking.md` 第 150 行标题「Dashboard（每个 Project 一个，另有全局一个）」改「Dashboard（一个页面，按 Project 选择查看）」；布局示意里的「全局 ⇄ Project」改「Project ▾」。

## 验证

- `frontend/` `npm run build` 退出码 0；后端改了 stop 接口，`backend/` `mvn -B clean verify` 退出码 0。
- 用户在浏览器里：
  1. 打开首页，下拉框选 A：KPI、漏斗、时间线、待追踪、候选池、申请总表只有 A 的申请；阶段 chip 只有 A 的阶段。
  2. 换到 B，筛选是 B 自己的；改「每 N 天」只改 B，换回 A 数字不变。
  3. 侧栏点进 A 的 Session 再点「Dashboard」，仍是上次选的 Project；刷新、返回后也一样。
  4. 在 A 点「查状态」，切到 B 不显示 A 的进度；在 B 点「停止」不影响 A 在查的。
  5. Project 页「在 Dashboard 查看」打开选中这个 Project 的 Dashboard。

## 待用户确认（答案会改变施工）

1. **Project 主页还要不要自己的 Dashboard 页签？** 建议：不要，只留新建 Session、最近 Session 和「在 Dashboard 查看」；Dashboard 只此一处，避免两份不一样。另一种：Project 页照旧，只改全局页。
2. **下拉框里要不要「全部 Project」一项？** 建议：不要。混看正是这次要去掉的；要看总数时一个个切。另一种：保留「全部」但只看不动（不能查状态、不能改间隔）。
3. **默认选哪个 Project？** 建议：URL 里的 → 上次在 Dashboard 选的（本机记住）→ 最近有 Session 活动的 → 第一个。另一种：每次都默认最近有活动的，不记上次。
4. **侧栏「全局」改名「Dashboard」？** 建议：改。不改的话页面标题也保持「全局 Dashboard」。

## 用户决定（2026-11-07，最终）

1. Project 主页去掉三页签，只留新建 Session、最近 Session 和「在 Dashboard 查看」。
2. 下拉框里没有「全部 Project」。
3. 默认：URL 里的 Project（还在的话）→ 最近有 Session 活动的 → 第一个。不再用本机记住的上一次。
4. 侧栏「全局」改名「Dashboard」。

Master Plan 措辞已按上面的决定回写 `plan/master/ui.md`、`plan/master/tracking.md`。

## 施工记录（2026-11-07）

- 状态：已施工，等用户在浏览器里试。`frontend/` `npm run build` 退出码 0；后端 `mvn -B clean verify` 退出码 0（协调者报告）。未提交。
- `route.ts`：`global` 带可选 `project`，hash `#/global/<id>`；新加 `replaceRoute()`（改栈顶 + `location.replace`，不压返回栈）；`lastDashboardProject()` / `rememberDashboardProject()` 只在内存里记这次打开窗口里最后选的 Project。
- `pages/Global.tsx`：标题「Dashboard」，右上下拉框 + 三页签。选中：URL 里的还在就用，否则按各 Project 最新 Session 的 `createdAt` 选（后端没有别的「活动时间」字段），都没有 Session 时第一个；选出的与 URL 不同就 `replaceRoute` 写回。数据用 `GET /api/projects/{id}`；`TabBody` 以 Project id 为 key 重新挂载（筛选、查状态条跟着换）；KPI 下面放最近的 Session。
- `App.tsx`：侧栏和窄栏按钮改名「Dashboard」，点它回 `#/global/<内存里最后选的>`（没选过就 `#/global`，页面自己选），高亮看 `route.kind === "global"`。删除后回首页的地方照旧 `go({ kind: "global" })`。已删 Project 的 `#/global/<id>` 由页面按默认顺序换掉，`listedGone` 不用改。
- `dashboard/CheckBar.tsx`：筛选键 `jobapply.dashboard.checkFilter.<projectId>`；进度只数这个 Project 的（`queued` / `running` / `done` 与这个 Project 的 jobId 取交集，查到 / 要手动登录 / 没找到按这些 Job 的 `portalCheck.result` 数，不用后端整批的 total / found 等）；别的 Project 在查时这里不显示进度、不轮询；「停止」发 `{ jobIds: 这个 Project 的全部 Job }`（空列表后端等于停整批，所以没有 Job 时不发）。「每 N 天」只改选中 Project 的（`projectCheckDays`），「用全局」照旧，去掉了改全局值的分支。
- `api.ts`：`stopStatusChecks(jobIds?)`。
- `pages/ProjectPage.tsx`：去掉三页签和 Job 读取，留标题改名、「在 Dashboard 查看」、「＋ 新建 Session」、最近的 Session。
- `dashboard/Dashboard.tsx` 和 `Approvals` / `AppsTable` / `Board` / `Failures` / `FollowUps` / `Upcoming` / `LeadPool`：删掉 `showProject` / `projectTitle`；候选池只按公司 → 地点分组。`RecentSessions` 空提示改成两处都适用的说法。`styles.css` 加下拉框宽度。
- 后端（另一个代理）：`POST /api/status-checks/stop` 可选 `{"jobIds":[...]}`，不带或空列表停整批，不认识的 id 忽略（`FollowupRunner.stopChecks(List<String>)`、`StatusCheckController`）。
- 偏离计划：侧栏「Dashboard」回到上次选的 Project 靠内存（只在这次窗口里），不写 localStorage——决定 3 去掉了本机记住；窗口重新加载后按「最近有 Session 活动的」选。
