# 设计：求职投递主流程

状态：**已确认**（2026-10-22）；未施工。日期：2026-10-22（已按旧工作区复盘修订）。

产品目标见 [base.md](base.md)。Project / Session 与 storage 结构见 [memory.md](memory.md)。旧流程经验见 [research-codex.md](../reference/research-codex.md)。目录见 [architecture.md](architecture.md)。

## 一句话

在类似 Claude 的界面里，用户在某个 Project（一种求职侧重）下开 Session：系统按该 Project 的个人资料和偏好找到匹配岗位，通过 Chrome MCP 在浏览器里代填申请表，**在提交前停下来交给用户审核，最终提交由用户本人点击**，然后只读核验官网结果，写入本地台账，并跟踪进度和最近一次查询时间。

设计偏重 MCP：浏览器操作走 Playwright MCP；搜索和正文抽取由后端直接调 API；自研 Tool 负责 Memory、台账、材料和额度。

## 主流程（一个 Job Thread）

用户已确认的顺序（2026-10-22）：**打开申请页 → 交给用户登录 → 岗位核实 → 简历补全（填写） → 提交前交给用户审阅 → 用户手动提交 → 投递后按需复盘与本地流程优化。**

```text
[恢复状态] → [资格与额度（基于候选池里已有的 JD 快照）] → (关卡 A：选岗 + 确认材料包)
  → [打开申请页] → (接力：用户登录，每次申请都交给用户) → [身份核对]
  → [岗位核实：在已登录页面上核对岗位、ID、城市、额度提示、筛选题]
  → [上传主简历 → 等解析 → 校正字段] → [逐页填写 ⟲：Agent 能填的统一填完]
        ├─ 未知问题 → (集中问答：NEEDS_INPUT)
        └─ C 级字段 → 资料里有占位符 {KEY}、personal.env 有值：Agent 填占位符，系统发给浏览器时换成真实值（C014-D）；否则 (接力：用户亲填)
  → [保存并回读核验] → (关卡 B：AWAITING_REVIEW，用户审阅填写报告与页面，标签页保持打开)
  → 用户在 Chrome 里自己点提交（如果出现安全验证，也由用户完成）
  → [只读核验官网结果] → [写入 Job 记录与台账（md + csv）]
  → [C030 投递后优化：出现摩擦信号时派生 Depth-2 reflect 子 Agent 读取 run-log.jsonl 与 fill-report.md 直接优化 Memory / Skills]
  … 之后在工作台同一 Job Thread 切至 followup Mode 跟踪轮次与查状态
```

| 节点 / 环节 | 做什么 | 能力 |
| --- | --- | --- |
| 恢复状态 | 读取 Project 的有效资料（base + overrides 解析后）、相关 Playbook（站点记录）、公司记忆（`companies/<company>.md`）、通用复盘经验（`review/lessons.md`，C020）、本 Job 的统一线程记录与申请状态。只读当前任务需要的部分 | 自研 Tool |
| 岗位核实 | 完整读一次官方 JD：岗位名、ID、城市、类型/届别、资格原文（保留 AND/OR/equivalent）、截止日期、薪资（未披露就写未披露） | 已登录页面用浏览器只读；候选阶段的 JD 快照来自搜索层的抓取 |
| 资格与额度 | 硬门槛检查；评分；查跨 Project 的历史投递，避免重复；查公司额度（未核实就写未核实） | 自研 Tool + 模型 |
| 关卡 A | 一次性展示：岗位、链接、资格结论与风险、额度、拟用简历和附件。用户确认即授权本岗位（或明确列出的批次）的**填写**，不是授权提交。B 级个人信息不在这里逐项确认，统一填完后在关卡 B 审核 | 对话 / 工作台确认 |
| 用户登录（接力） | 打开申请页后**每次都交给用户**：用户在 Chrome 里登录（或确认已登录），然后发送 `logged_in` 或在对话里回复已登录，由模型读快照判断是否已登录（用户也可 override 继续） | Agent `ask_user` / 用户回复继续 |
| 身份核对 | 读取页面上可见的账户名和官方投递历史，核对是不是正确的账号（防止多个账号混淆）；不读取 Cookie | Chrome MCP |
| 上传与解析 | 遇到上传位置时，先列出 `base/assets.md` 里登记的原件问用户要上传哪些（或直接上传对话里已选定的简历），选定的写进 `job.md` 的 `materials`；先上传主简历，等解析完成，再按资料校正被覆盖或识别错的字段；按授权关闭「同步更新在线简历」类选项 | Chrome MCP |
| 逐页填写 | 快照 → 在申请状态里写下这一页的字段清单（C019）→ 照清单批量填 A/B 级字段 → 选择 / 上传 → 点「下一步」类按钮；页面变化后重新取快照 | Chrome MCP（Agent 循环） |
| 集中问答 | 把本页及已知的未知必填项合并成一张清单，保留原文和选项，一次问完 | Agent `ask_user` / 用户回复继续 |
| 接力 | Agent 停止写操作，用户在浏览器里操作；用户交回后，Agent 先重新取快照、只读核对，不覆盖用户填的内容 | Agent `ask_user` / 用户回复继续 |
| 保存并回读 | 保存后刷新或重新进入页面，回读关键字段、附件名与大小、已选城市标签 | Chrome MCP |
| 关卡 B | 从申请状态的字段清单生成填写报告（C019）：每个字段的值、**来源层**、置信度，C 级字段只标注「用户已填」或占位符状态，附件清单，未解决的问题 | Agent `finish` / 用户审阅 |
| 只读核验 | 用户说已提交（或点了确认）就记为已提交，用户的确认就是证据（`user-reported`，2026-10-23）；标签页上正好是成功页时顺手只读一次，记下官网原文和时间 | Chrome MCP（可选） |
| 写入台账 | 更新 `job.md` 和事件日志，重新生成本 Project 的 md 台账和 csv | 自研 Tool |
| 投递后优化（C030） | 若本次填表出现摩擦信号（重试、打转、人工接力、报错），投递后派生 Depth-2 `reflect` 子 Agent 读取 `run-log.jsonl` 与 `fill-report.md`，直接优化 Memory / Skills（边界见 `dev-limit.md`） | `reflect` 子 Agent |

上面是**一个 Job 的申请流程**（统一 Job Thread，thread id = job id），可在同一线程经 `SegmentBoundary` 从 `apply` Mode 切到 `followup` Mode。调研由 Session 与 `web-research` 子 Agent 完成。

## 提交保护（硬边界）

「Agent 不替用户点最终提交」只保留 `dev-limit.md` 定下的硬边界，其余按钮与页面异常由模型自行判断：

- **工具白名单**：只开放导航、快照、填写、选择、上传、点击、标签页管理，不开放执行任意 JS 或坐标点击；上传只限 `storage/uploads/` 编号文件。
- **硬提交词与按键拦截**：每次点击按最新快照的 ref 解析目标（拒绝任意选择器），名字命中硬提交词表（提交、投递、申请、Submit、Apply、Send 等及其组合，或所在对话框文字含提交词的确认按钮）或目标无法识别时直接拒绝；文本框里回车、`type` 的 `submit: true`、空格键提交同样拦截。
- **C 级字段与 `{KEY}` 占位符**：未登记占位符的 C 级字段不填；已登记 `{KEY}` 且 `personal.env` 有值时模型填占位符，仅在发给 Playwright MCP 那一刻替换为真实值，快照、日志与报告保持遮值。
- **非硬边界均由模型判断**：已移除软边界按钮程序审批、登录预检和异常状态强制停机；遇到验证码、弹窗或疑似已提交页面时只在工具结果中如实告知模型，由模型换办法或调 `ask_user` 交给用户。
- **Spring AI 接入方式**：Harness 直接调用 `ChatModel`（不用 `ChatClient` / `ToolCallingAdvisor` 自动执行工具），每次工具调用经由 `BrowserFill` / Harness 统一校验。
- **MCP 隔离与只读子任务**：MCP 使用 include 白名单与最小环境变量；子任务按需只赋只读能力（如 `portal-check`、`reflect` 不带填表与写浏览器工具）。

## Chrome MCP

| 候选 | 版本 | 说明 |
| --- | --- | --- |
| `@playwright/mcp` | 0.0.83 | **已确认**：连接用户本机正在运行的 Chrome，负责快照、批量填写、文件上传与多标签页管理 |

**远程调试连接（`--cdp-endpoint chrome` / `127.0.0.1:9222`）**：用户在 `chrome://inspect/#remote-debugging` 开启开关后，首次连接同意一次即可，不用 Playwright 扩展，重连无需重复确认。

- **唯一连接管理**：全局连接状态由 `BrowserSession.ensureConnected()` 管理（未连接 / 连接中 / 已连接 / 断开）。断开后下一次使用浏览器时自动重连一次，连不上就停下并推送状态，等待用户点击「重新连接」或回复消息。
- **并发与标签页管理**：多个申请 Worker（默认 3 个）与调研 / 子 Agent 共享同一 Chrome 和登录态，各自拥有独立的 Playwright MCP 会话与 `TabOwner`（Subagent 使用 `"sub:" + subRunId` 并在 `finally` 中执行 `CLOSE_ALL`）。`browser_tabs list` 列出 Chrome 中全部标签页并标注归属；只操作自己打开的、点击弹出的或用户明确转交的标签页由提示词与 skill 约束，代码不做归属拦截。停在提交前只保留填好的页面，结束/关闭/调研完成时关掉自己打开的页，从不关闭用户的页面。

**默认启动参数（C004-A）**：`--snapshot-mode none`、`--output-dir <storage>/cache/playwright-mcp`（连接建立与关闭时清空）、`--console-level error`、`--image-responses omit`、`--no-webmcp`。MCP 客户端声明 root 为 `storage/uploads/`。不做「Computer Use 单步回退」，受阻步骤交由 `ask_user` 人工接力。

## 登录状态

原则：**系统不保存、不读取、不输入密码，也不读取、导出 Cookie 或 Token。**

- 直接复用本机 Chrome 里用户的日常登录状态，不另建专用资料目录；不替用户注册账号，遇到注册或登录页由模型调 `ask_user` 转人工接力。
- 登录后是否成功及当前是否为正确账号，**由模型读快照判断**（关键词只作为提示，不拦流程）；切换账号或关闭标签页后丢弃旧 ref 并重新核对。
- `browser/sites.md` 可选记录站点、用户自填的账号标识与最近一次确认已登录的时间（不写密码）。

## 岗位搜索（Web Search）

旧流程主要投国内校招和外企中国区，所以搜索要以**招聘门户类型**为单位来设计，不能假定都是 Greenhouse / Lever。

1. **数据源登记**（按招聘系统写在 `playbook/skills/platforms/<name>/SKILL.md`，公司自建站写在 `playbook/sites/<host>.md`，同时写明搜索方式；2026-10-22 随 Playbook 结构调整）：
   - 公司自建校招站、Moka 系、飞书招聘系、外企 Job Cart 站：用 `site:` 搜索，加上公司官网的入口。
   - 学校就业平台转载的官方简章：只作为线索，最后要回到官网核实。
   - Greenhouse / Lever / Ashby：直接调公开接口（已验证可访问）。
2. **搜索**：搜索层根据 Project 偏好生成搜索词。**国内站点在 Brave、Exa 等搜索引擎里的覆盖情况未知，施工时要先实测。**
3. **核实**：搜索层抓取页面。国内招聘站多是 SPA，抓取可能拿不到内容，这时改为浏览器只读读取（进入浏览器队列）。
4. **入库**：按「公司 + 招聘项目 + 岗位 ID」去重，写入 Project 的候选池（`jobs/<job-id>/job.md`，`lead_status: new`）。区分「岗位族」和「具体在招岗位」：只找到岗位族的，标为「监控」。

### 三种接法的区别（回答用户的问题，2026-10-22）

「Search MCP」「我们自己搜」「Hermes 的网络搜索」**底层都一样**：都是调用第三方搜索服务（Brave、Tavily、Exa、Firecrawl、Parallel 等）的 API，都需要那家的 API key。区别只在**调用代码放在哪里、谁能控制它**：

| 接法 | 是什么 | 优点 | 缺点 |
| --- | --- | --- | --- |
| **A. Search MCP** | 厂商或社区提供的 MCP 服务器（Node 子进程），后端通过 MCP 协议调用它 | 不用写调用代码；换一家只需要换一个 MCP | 多一个子进程；返回什么、怎么截断由别人决定；我们的 URL 检查、限速、缓存只能套在外面 |
| **B. 后端直接调 API（自研）** | Java 后端用 HTTP 直接调搜索 API | 只多一个 HTTP 调用，没有额外进程；结果格式、截断、缓存、限速、URL 安全全部在我们手里；方便写确定性测试 | 每接一家，要写一个小适配器（一般几十行） |
| **C. Hermes 的 `web_tools`** | Hermes（Python）内部的函数，用的就是 B 的做法：直接调 firecrawl / tavily / exa / parallel 的 API，再用辅助模型摘要长页面 | 设计已经验证过，可以照着做 | 是 Python，不能直接嵌进 Java 后端；要用的话得把 Hermes 当成一个独立服务来跑，太重 |

### 已确认：分层 Web 工具（Web Tool Router，用户确认，2026-10-22）

采用 B（后端直接调 API），并按 Hermes 官方文档描述的分层与省钱做法来设计（[文档：Web Search & Extract](https://hermes-agent.nousresearch.com/docs/user-guide/features/web-search)、[Browser Automation](https://hermes-agent.nousresearch.com/docs/user-guide/features/browser)，2026-10-22 查阅；本机 0.12.0 源码与文档有差异，以文档描述的较新做法为参考）。**不引入 Hermes 本身。**

```text
Agent（Spring AI Tool Calling）
   └─ Web Tool Router（自研，Java）
        ├─ search   发现信息   → 免费来源优先 → 额度用完或被限流时切到 Brave（填了 key 才启用）
        ├─ extract  读取正文   → 后端直接抓取 + HTML 转 Markdown（如 Jsoup），不启动浏览器
        ├─ browser  登录、点击、填表、SPA 页面 → Playwright MCP（连接本机 Chrome）
        └─ cache    结果复用   → 搜索结果、正文抽取结果
   所有请求先过同一层检查：URL 安全、域名策略、限速
```

**原则：能用 search / extract 解决的，不启动浏览器。** 浏览器只用于需要登录、页面交互，或者 extract 拿不到内容的 SPA 页面。

| 层 | 设计 | 成本控制 |
| --- | --- | --- |
| search | `SearchProvider` 接口，结果统一为 `{title, url, description, position}`，默认 5 条。**提供方按链式顺序尝试**：先用免费来源，被限流、额度用完或没有结果时，切到下一个；最后是 Brave。Brave 的 key 在设置页里**可填可不填**；不填时，免费来源都失败就告诉你，不硬撑 | 不花钱优先；结果缓存 |
| extract | 后端抓取，转成 Markdown。返回给模型的正文**默认上限约 15,000 字符**；超出时保留开头约 75%、结尾约 25%，加上「已截断」说明；**全文存成文件**，模型需要时再按段读取。默认**不调用模型做摘要**（省 token）。JD 的资格条款从全文里定向读取，保留原文 | 少送 token；不启动浏览器 |
| browser | Playwright MCP，远程调试模式（`--cdp-endpoint chrome`）连接本机 Chrome。快照用文本化的无障碍树加 ref，**不在每一步都传截图** | 少传图片；不用付费云浏览器 |
| cache | 搜索：同一查询（忽略大小写和多余空格）短期缓存。抽取：全文存在 `storage/cache/web/`，**所有 Session 和子任务共用**（多个子任务查同一家公司时，不重复付费、不重复抓取）。只缓存成功的结果；TTL 可配置（默认 20 分钟，范围 1 分钟到 1 天）；投递状态页这类实时页面不缓存 | 减少重复搜索和抓取 |

**免费来源**（具体用哪些，施工第 4 段实测后再定，尤其要测国内招聘站的覆盖）：
- **SearXNG**：本机用 Docker 自建（本机 Docker 已可用），免费、没有额度限制，可以聚合多个搜索引擎；
- **DuckDuckGo**：不需要 key，但稳定性一般；
- 各家的免费额度：例如 Brave 免费档每月 2,000 次（Hermes 文档所载），以及 Exa、Firecrawl、Tavily 的免费档。是否轮换使用，施工时按各家条款决定。

**为什么浏览器还是用 Playwright MCP，而不是 Java Playwright**：
- 浏览器操作要做成模型能调用的工具（快照、ref、填表、上传），Playwright MCP 现成就是这样，自己用 Java Playwright 包一层等于重写它。
- 连接方式同样是 Chrome 的远程调试（`chrome://inspect` 的开关），不用另建资料目录。

**不借鉴**：Hermes 较新版本默认的 Browser Use 模式是让 Agent 自己写 Python 代码去操作浏览器。JobApply 不允许这样做，因为能执行任意代码，就能绕过「不点最终提交」的守卫。

**安全检查**（所有 search / extract / browser 导航都要经过）：拦截内网、回环、云元数据地址（含 DNS 解析失败及重定向后复查）和带疑似凭证的 URL，按域名礼貌限速。
不预设站点黑白名单（域名黑名单默认关闭，可在 `jobapply.web.blocklist` 自配）；碰到验证码、登录或限流时由模型换来源或请用户在 Chrome 处理。

## 匹配与评分

- **硬门槛先行**，由代码确定性判断：工作年限、职级、核心语言、在读身份 / 毕业窗口 / 届别、地区批次、排除名单。
- **资格逻辑保留原文**，结论分三档：`meets`（明确符合）、`equivalent_pending`（方向匹配，等效经验待招聘方判断）、`hard_gap`（明确缺少硬条件）。
- **评分**沿用旧研究表的形状，权重放在 Project 的 `preferences.md` 里，可以调整：
  `30% 方向 + 40% 核心技能 + 18% 级别 + 12% 资格 − 硬门槛罚分`，并附置信度。只按现有证据打分。
- 高级岗位自动去找同公司的降级替代岗（Junior / Graduate / Intern）。
- JD 和实际筛选题分开核实；筛选题在填写阶段读到时，再做一次资格复核。
- 可选：用第二个模型交叉核验评分（旧流程用过，发现过漏判）。

## 额度

- `companies/<company>.md` 记录每个招聘项目的额度规则、证据（官网原文和日期）、已用次数（来自官方历史），以及被拒记录是否占用额度（未实测就写未核实）。
- 额度跨 Project 共享。
- 不从「已投 N 个」反推上限，不把一家公司的规则套到另一家，不另建账号绕过额度。

## 状态：阶段 + prep 子状态 + 轮次

投递之后的流程因公司而异，所以状态拆成固定的**阶段**（`prep → applied → screening → assessment → interview → offer → closed`）和可变的**轮次**列表。详见 [tracking.md](tracking.md)。

`prep` 阶段内部使用 C030 精简后的 5 个子状态（`PrepState`）：

```text
PREPARING（准备/填写中） ⇄ NEEDS_INPUT（待用户补充/登录/处理验证码）
        │
        ↓
AWAITING_REVIEW（填完待用户审阅） ──→ 有提交证据 → phase = applied
        │
  任意未提交态 ⇄ PAUSED（暂停） / FAILED（出错，可重试）
```

补充规则：
- 候选池在转为申请前只用 `lead_status`（`new / shortlisted / skipped / converted / closed`）；转为申请后进入 `phase: prep`，初始 `prep_state` 为 `PREPARING`。
- 兼容读取旧状态值：持久化层通过 `PrepState.fromStorage` 将旧版细粒度值（如 `selected`、`needs_login`、`handoff_user`、`user_submitting`、`verification_pending`、`unconfirmed`）平滑映射到这 5 个标准状态。
- `AWAITING_REVIEW` 可回到 `PREPARING`（关卡 B 用户要求修改）；`PAUSED` 恢复后先只读核对页面。
- 从 `prep` 进入 `applied` 需要提交证据（用户确认 `user-reported`、成功页或官方历史记录）。
- `closed` 支持用户主动记录与撤销 `apply_failed` / `gave_up`（规则见 [tracking.md](tracking.md)「投递失败 / 不投了」）。
- 官网的「暂不匹配」等原文保留在结果里；投递之后各轮结果标注 `result_source`。

## 台账与 Dashboard

两张表（申请总表、轮次表）和每个 Project 的 Dashboard，都由后端从 `job.md` 和 `rounds/` 生成。栏目、漏斗、看板、待追踪、时间线与复盘题库，见 [tracking.md](tracking.md)。每个 Project 的 `tracker/` 下有一组同名的 md 和 csv：`leads`、`applications`、`rounds`（用户已确认，2026-10-22）。每次状态变化时两种格式一起重新生成；md 给人和 AI 读，csv 展开全部字段，可以直接用 Excel 打开。

## 里程碑（每段拆成一个或多个 Coding Plan，放在 `plan/coding/`）

1. **Memory 与台账**：storage 布局，base / Project 继承解析（含循环检测），Job 与 Session 记录读写，候选池，状态机，台账生成。storage 不做版本控制（2026-10-23 用户确认）。
2. **Playwright MCP 接通**：连接本机 Chrome（默认远程调试模式，见「Chrome MCP」）；打开 URL，交给用户登录，取快照，做身份核对；断开后自动重连。
3. **提交守卫与关卡 B**（前置：**资料导入与画像**）：Harness 循环加硬提交守卫与 C 级 `{KEY}` 占位符替换，在夹具和本机 Chrome 上跑通多页表单、上传、下拉、日期，停在提交前并输出填写报告。
4. **岗位核实与搜索**：搜索层（`SearchProvider` 与抓取）、ATS 公开接口；SPA 页面回退到浏览器只读。
5. **匹配、额度与关卡 A**。
6. **投递后追踪与复盘**：在工作台更新轮次、起草复盘、生成轮次表、题库、按公司汇总、`dashboard.md` 快照。
7. **前端**：类 Claude 界面，包括 Project / Session / 已打开工作台侧栏、对话区、状态和报告面板、Dashboard。
8. **官网状态查询（C026 / C030）**：用户在 Dashboard 或工作台点「查状态」时，由只读 **`portal-check` 子 Agent** 在本机 Chrome 里查官网门户；遇到要登录的记 `needs_login` 并在整批结束后按站点在 Chrome 打开；查到的状态作为建议进入「核准列表」，用户核准后更新阶段。查看间隔由全局与 Project 天数配置。施工见 [C026-portal-status-check.md](../coding/04-refinement/C026-portal-status-check.md)。
9. **运行时与架构演进（C030）**：统一为单 Base Agent（`AgentLoopEngine` + 6 个 Capability Plugins + 外置 `Mode`）+ 统一 Job Thread（`apply` → `SegmentBoundary` → `followup`）+ Depth=2/3 单向 Subagent 树（含 `web-research`、投递后 `reflect` 优化、`portal-check`、`resume-match` / `interview-prep`）。

## 已确认事项

- 浏览器 MCP 用 `@playwright/mcp`，直接连接本机 Chrome 远程调试模式（首次连接确认一次）。
- 每次申请：打开页面 → 交给用户登录 → 岗位核实 → 简历补全 → 提交前交给用户审阅 → 用户手动提交 → 投递后按需复盘与优化。
- 台账：每个 Project 一组 md 和 csv，每次状态变化时一起更新。
- 前端：React + Vite；模型：用户自配（见 [ui.md](ui.md)）。
- 搜索：分层 Web 工具（search / extract / browser / cache），免费来源优先，额度用完后切到 Brave（key 可选），见「已确认：分层 Web 工具」。
- 硬边界由程序拦截，其余按钮与页面状态均由模型自行判断。

## 遗漏检查（2026-10-22，已确认）

| # | 事项 | 默认做法 |
| --- | --- | --- |
| 1 | **申请材料准备** | Agent 按 `writing.md` 起草 Cover Letter；简历给出修改建议，由用户更新上传版本；按站点语言选中文或英文简历 |
| 2 | **站点在线简历** | 在 `companies/<company>.md` 记录在线简历核对状态，同一账号再投时先只读核对并只补差异 |
| 3 | **面试准备** | 由 `interview-prep` 子 Agent / 对话结合 JD、公司历次复盘与题库生成准备清单，不自动触发 |
| 4 | **迭代上限** | 采用工具调用上限兜底 + 宽限总结，不因硬限制中断长表单（见下方「迭代上限与降低消耗」） |
| 5 | **模型数据说明** | C 级真实值永不发给模型（仅以 `{KEY}` 占位符参与填表）；设置页说明 A / B 级信息会发往所选模型供应商 |
| 6 | **故障恢复** | 线程按每轮落盘的对话与状态文件恢复；重连后先只读核对页面真实状态再继续，绝不重放可能已触发提交的操作 |
| 7 | **首次使用引导** | 引导四步：选数据位置与建 base → 配模型 → 开启 Chrome 远程调试并同意首次连接 → 可选填 Brave key |
| 8 | **本地数据备份** | `storage/` 只在本地、不上传，建议用户开启 Time Machine 备份 |
| 9 | **匹配质量反馈** | 记录候选池「想投 / 不投」及原因用于后续调整权重 |
| 10 | **提醒** | 在 Dashboard「接下来 7 天」和侧栏/摘要里提醒 |

## 迭代上限与降低消耗

- **迭代上限兜底（C018）**：默认申请每次运行 150 次、对话每条消息 40 次工具调用，可在设置页调整或设为不限；达到上限时给模型一次宽限调用总结做到哪一步，再交由用户决定是否继续。打转检测仅在工具结果中向模型提示「这是第 N 次」，不强制停机转交。
- **降低消耗**：
  - 页面未变化时不重复取快照；长快照写盘仅给模型预览；
  - 已确定的字段通过 `fill_form` 批量填写，不逐个字段调用；
  - 调研阶段的 JD 正文返回截断版本，全文按需读取并跨任务共用 `storage/cache/web/` 缓存；
  - 用量按运行、Session 与 Project 汇总统计。
