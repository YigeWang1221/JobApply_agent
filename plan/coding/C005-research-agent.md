# C005 · 对话 Agent：调研岗位、管理候选池、开始申请（workflow 里程碑第 4 段）

- 依据：[agent-runtime.md 第 7 节](../master/agent-runtime.md)（循环、工具、提示词的做法）、[workflow.md「岗位搜索」「已确认：分层 Web 工具」](../master/workflow.md)、[ui.md「新建 Session」「候选池」](../master/ui.md)。界面见 [C005-ui.md](C005-ui.md)。
- 目标：Session 就是和一个 Agent 的对话。用户说「帮我找上海的后端校招」，Agent 自己搜索、读 JD、把合适的岗位放进候选池；用户说「投这两个」，Agent 把它们转为申请并交给申请 Agent（C004-D 的循环）。
- 做法和申请 Agent 一样：一个循环（模型 → 工具 → 模型），提示词 + 工具 + skill，由模型决定做什么；代码只管工具本身和安全检查。
- 不做：匹配评分和额度（第 5 段）、投递后追踪（第 6 段）、SearXNG 自建、上下文压缩。不写测试。

## 循环

- 用户每发一条消息，跑一次：拼消息 → 调主模型 → 执行工具 → 直到模型只回文字（就是给用户的回复）。兜底上限每条消息 40 次工具调用。
- 对话存在 `sessions/<id>/transcript.jsonl`（已有），工具调用也记进去，下一条消息接着同一段对话。
- 新建 Session 时，Agent 先读 Project 状态，给出开场（候选池有几个、哪些申请在等用户、哪些停在提交前）。
- 循环和申请 Agent 共用同一套写法；能抽成公共部分的抽出来（消息拼装、调模型重试、工具调用记录），不复制一份。

## 两种搜索（用户确认，2026-10-23）

1. **撒网调研**：没有具体目标，「按我现在的简历和条件，能找到哪些公司的什么岗位」。Agent 读画像和偏好，自己想搜索词，大范围搜，读 JD 原文初步核对条件，把合适的放进候选池，标「未核验」。
2. **官网核验**：针对候选池里撒网搜到的岗位，到公司官网的岗位列表（用户已经在 Chrome 里登录的站点）上查：这个岗位还在不在、官方链接是什么、要求有没有变。用浏览器在岗位列表页搜索、翻页、打开详情，只读，不点申请。核验后在候选里记「已核验 / 官网没找到」和官方链接。

两种都由模型按提示词和 skill 决定怎么做；代码只提供工具。

## 工具

| 工具 | 做什么 |
| --- | --- |
| `web_search(query)` | 按 workflow.md「分层 Web 工具」：多级、免费优先。按顺序试：DuckDuckGo（不要 key）→ 本机 SearXNG（配了才用）→ 配了 key 的付费档（Brave 等，用它们的免费额度）；被限流、没结果就换下一级；都不行时如实告诉模型，改用下面的抓取方式直接去招聘站、ATS 列表找。返回 5 条 `{title, url, description}`，同一查询短期缓存 |
| `web_fetch(url)` | 读一个页面，转成文本：先由后端直接抓取（快）；内容太少（国内校招站多是 SPA）时，默认在用户的 Chrome 里打开这个链接读取快照（用户确认，2026-10-23）。超过约 15,000 字只给开头和结尾，全文存到 `storage/cache/web/`。也用来读 Greenhouse / Lever / Ashby 的公开接口 |
| 浏览器工具 | 和申请 Agent 同一套（快照、查找、导航、点击、输入、等待），用于官网核验：在岗位列表里搜索、翻页、打开详情。提交类按钮（含「立即申请」）照样一律拒绝；不填申请表 |
| `list_jobs(status?)` | 本 Project 的候选和申请（公司、岗位、状态、是否核验） |
| `add_lead(company, title, url, city?, type?, note?)` | 放进候选池（`lead_status: new`，未核验）；按「公司 + 岗位链接」去重 |
| `update_lead(job, status?, verified?, official_url?, note?)` | 想投 / 不投；记核验结果和官方链接 |
| `start_application(job)` | 转为申请，交给申请 Agent 开始填写；之后的事在申请工作台里进行 |
| `ask_user(message)` | 要用户做的事（例如在 Chrome 里登录某家公司的招聘站），结束这一次运行，用户回复后接着做 |
| `read_skill(name)` | 读 skill（「撒网调研怎么想搜索词」「国内校招站怎么核验」等，施工时先写这两份） |

- 安全检查（所有 `web_fetch` 和导航都要过）：只允许 http / https；拦截内网、回环、云元数据地址；带疑似凭证的 URL 拒绝；按域名限速。不按站点名单拦（2026-10-23 用户改）：去哪个站、用哪个搜索来源由模型决定，`web_search` 可带 `provider`。
- 标签页（施工时补，2026-10-23）：`BrowserSession` 给每个 Job / Session 记一个标签页；轮到谁用就先切到它的标签页（`browser_tabs select`，只有系统能调）。调研 Agent 可以打开任意公开网址（过上面的安全检查），申请 Agent 仍只能在岗位站点内导航。
- 不替用户登录：要登录的站点用 `ask_user` 请用户登录。浏览器同一时间只给一个任务用；申请 Agent 停在某页等用户时，调研 Agent 用新标签页，不动申请那一页。

## 提示词

身份（用户的求职助手）→ 工作方法（撒网：按画像和偏好想搜索词 → 搜索 → 读 JD 原文 → 放进候选池并说明理由；核验：到官网岗位列表查 → 记结果和官方链接；不编造岗位，链接必须是读到过的）→ 按情形（国内校招站多是 SPA、读不到时告诉用户贴链接；同一家公司多个岗位按公司说明；用户要投时确认是哪几个）→ 资料摘要（画像 `profile.md`、偏好）。

## 接口

- `POST /api/projects/{id}/sessions`：新建 Session（返回开场）。
- `GET /api/sessions/{id}`：对话（用户和 Agent 的话，工具调用只给名字和一句说明）、状态（running / idle）、涉及的岗位。
- `POST /api/sessions/{id}/messages`：发一条消息，后台跑，前端轮询。
- `POST /api/sessions/{id}/stop`。
- `PATCH /api/jobs/{id}/lead`：界面上直接改「想投 / 不投」；`POST /api/jobs/{id}/application` 已有（开始申请）。

## 怎么确认能用

- `mvn -B clean verify`、`npm run build` 通过。
- 真实模型 + 真实搜索：在界面里新建 Session，撒网找 3 个岗位，候选池里出现带链接的结果，链接打得开；再对其中一个做官网核验（用户在 Chrome 里登录那家站点）。
- 选一个夹具岗位开始申请，在工作台里看到申请 Agent 在跑。

## 实际结果

- 施工（2026-10-23，并行代理）：新增 `agents/ResearchAgent`、`tools/ResearchTools`、`tools/WebFetch`、`harness/runtime/SessionLoop`、`workflow/SessionRunner`、`config/SearchProperties`（`jobapply.search.searxng-url`；Brave key 运行时从 `.env` 的 `BRAVE_API_KEY` 读）、skill `research/broad-search`、`research/verify-official`。改：`BrowserSession`（每个 Job / Session 一个标签页）、`BrowserToolPolicy`（系统可 `select`）、`ApplicationRunner.start`（候选先转为申请）、`JobLedger.annotate`（核验结果、官方链接、备注写进 job.md）、`SessionRepository`（`list`、`appendTurn`、`readTurns`）、`AgentRun.Turn.NOTE`。
- 和计划的出入：`web_fetch` 内容太少或抓取失败时用 Session 自己的标签页读；旧网页正文再发给模型时截到 1,500 字。
- 验证：`mvn -B clean verify` 通过（surefire 611、failsafe 1）。真实模型 + 真实搜索、真实 Chrome：用户 2026-10-23 执行，报告没有问题。
- 已知问题：用户关掉 Chrome 标签页后标签页位置可能错位；Session 状态只在内存；调研 skill 也出现在申请 Agent 的 skill 目录里。
