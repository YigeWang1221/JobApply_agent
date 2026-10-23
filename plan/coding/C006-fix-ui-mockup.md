# C006-fix · 照最早的示意页改 Project / 全局页的版式

- 依据：用户（2026-10-23）：「UI 没有最早给我展示的那个好看」，指商讨 Dashboard 时的静态示意页 https://claude.ai/artifact/4K6eE85i51GvrLUGz8cARD ，副本在 [plan/reference/ui-mockup.html](../reference/ui-mockup.html)（虚构数据，只当样子参考）。
- 现状：颜色变量、字体（IBM Plex）、侧栏和卡片的基础样式在 C005 已经照它做了；差的是**主区域的版式和图**：示意页的全局 / Project 页是一整块 Dashboard，现在只有一行 KPI 和几张列表。
- 不做：示意页里要轮次和复盘数据的部分（轮次表、题库、复盘概览、看板上的进度点），这些数据属于里程碑第 6 段，现在没有；不改对话页和申请工作台（C006 刚做过）。不加图表库，图用内联 SVG。

## 照示意页做的（数据现在都有）

| 示意页 | 做法 | 数据来源 |
| --- | --- | --- |
| 顶部标题 + 右上角页签「Dashboard / 候选池 / 申请总表」 | 全局页和 Project 页都分成这三页签；「轮次表」「题库」等第 6 段 | — |
| 8 格 KPI（投递、公司、准备中、筛选中、笔试 / OA、面试中、Offer、待追踪） | 按示意页的格子样式，数值按阶段统计 | JobView 的 phase、prepState、nextCheckAt |
| 漏斗：多少家公司走到了哪一步（按公司 / 按申请切换） | 层级用阶段：已投递 → 简历筛选 → 笔试 / OA → 面试中 → Offer | phase |
| 接下来 7 天 | 7 天内到期的「下次查看」和截止 | nextCheckAt |
| 待追踪 | 现在的「该查看了」，换成示意页的列表样式 | checkDue |
| 看板（按阶段分列的卡片） | 7 列：准备中 / 已投递 / 筛选 / 笔试 / 面试 / Offer / 已结束；卡片点开工作台。没有轮次进度点 | phase |
| 时间线（每个已投递的申请一行，竖线是今天） | 投递日、阶段变化（事件日志）、已结束 | appliedAt + 事件 |
| 每周节奏 | 只画每周投递数（面试数等第 6 段） | appliedAt |
| 候选池页签 | 示意页的行样式：岗位、城市 · 类型、发现于哪个 Session、状态标签；按公司分组折叠（已做）。匹配分、资格结论列等 C008 / 第 5 段有了再填 | JobView |
| 申请总表页签 | 一张表：公司、岗位、城市、类型、投递日、阶段、最近查看、下次查看、链接 | JobView |
| 最近的 Session | 示意页的行样式：日期、标题、标签、交接摘要 | SessionSummary |
| 侧栏底部的说明文字 | 「Project = 一份资料版本 + 偏好…」一段说明 | — |

## 要用的接口

- 时间线要每个申请的阶段变化：`GET /api/projects/{id}` 的 jobs 加 `events`（只要时间、类型、到哪一步），或者前端逐个读 Job 详情。施工时选简单的那个；倾向前者（一次请求）。
- 全局页要所有 Project 的 jobs：`GET /api/projects` 现在只给计数，加一个 `GET /api/jobs`（全部 Job 的 JobView）。

## 怎么确认能用

- `npm run build`、`mvn -B clean verify` 通过。
- 你打开全局页和 Project 页，和示意页并排看：版式、KPI、漏斗、看板、时间线、候选池行样式一致；数据是你自己的。

## 实际结果

- 前端：新目录 `frontend/src/dashboard/`（Dashboard 页签与组合、Kpis、Funnel、Upcoming、FollowUps、Board、Timeline、Weekly、AppsTable、LeadPool、RecentSessions、metrics 口径）。`pages/Global.tsx`、`pages/ProjectPage.tsx` 改成「标题 + 右上角页签」，三页签共用一套组件；全局页显示 Project 标签。页签记在本机浏览器（localStorage），URL 不变。
- 口径：KPI 8 格按 phase 计（投递 = 投递以后的阶段，含投递后才结束的；筛选中 = applied + screening；待追踪 = checkDue）；漏斗按「曾经走到过」，取当前阶段和事件日志里出现过的阶段的最大值，接受 / 拒绝 Offer 算到 Offer；接下来 7 天 = 7 天内的 nextCheckAt，加上准备中在等你的申请；时间线的点来自 phase_changed / closed 事件；每周节奏只画投递数（最多 16 周）。
- 原「该查看了」改名「待追踪」，换成示意页的列表样式，「我看了」行为不变（`status.tsx` 里的 DueChecks、旧 Kpis、sumCounts 移走）。候选池行为不变，行样式照示意页，多了「发现于「Session 标题」」。全局页不再有每个 Project 一张卡片（侧栏已能进入）。
- 侧栏底部加了示意页的说明文字（窄屏隐藏）。CSS 补了图表色 `--s1/--s2/--f1…f6`（浅色 / 深色各一套）。
- 后端：`GET /api/jobs` 返回 `{jobs: JobView[]}`（全部 Project）；JobView 末尾加 `events: [{at, type, to}]`（只含 converted / phase_changed / closed），只有 `GET /api/projects/{id}` 和 `GET /api/jobs` 带，其它接口为 null。`ProjectController` 去掉类级路径，各方法写全路径。
- 验证：`npm run build` 通过；`mvn -B clean verify` 通过（611 个单元测试 + IT，0 失败）。浏览器里和示意页并排对照：未运行。
