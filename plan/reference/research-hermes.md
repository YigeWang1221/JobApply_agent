# 参考：Hermes Agent 的 tools / skills / memory 设计

> **参考，不是施工步骤。** 来源：本机 `~/.hermes/hermes-agent`（Nous Research Hermes Agent 0.12.0，Python，MIT 协议，最近一次提交 2026-04-30）的源码与 skills 目录。只读阅读，没有读取其配置、密钥、memories 和日志。日期：2026-10-22。
>
> JobApply 是 Java + Spring AI 项目，只借鉴设计，不复制代码。下文中的路径都相对于 `hermes-agent/`。

## 官方文档补充（2026-10-22 查阅，比本机 0.12.0 新）

- **搜索来源**：多了 SearXNG（自建）、Brave（免费档 2,000 次/月）、DuckDuckGo（不需要 key）等。没有配 key 时，在 Exa、Parallel、Firecrawl、Keenable 的免费档之间轮换，被限流就切到下一家。
- **缓存**：搜索结果按进程缓存在内存里；抽取结果的全文存在磁盘，多个进程共用；示例 TTL 为 20 分钟；只缓存成功的结果。
- **正文长度**：给模型的正文默认上限 15,000 字符；超出时保留开头约 75%、结尾约 25%，不用模型摘要，全文存成文件，可以按段读取。（本机 0.12.0 是「超过 5,000 字符就用模型摘要」。）
- **浏览器**：默认改为 Browser Use 模式（`browser_exec`，Agent 自己写 Python 操作浏览器）；仍然支持本地 Chromium、CDP 连接用户的 Chrome、云浏览器、Lightpanda、Camofox。点击操作仍然没有审批。
- 官方建议：普通页面内容优先用 web_search / web_extract，需要交互时才用浏览器。

JobApply 采纳了这里的分层、缓存和正文截断做法，见 workflow「已确认：分层 Web 工具」；**不采纳** Browser Use 模式。

## 结论先行

- **Hermes 没有「最终提交」这类保护**：浏览器点击不经过审批（`tools/browser_tool.py` 里没有 approval 调用，已核对）。JobApply 的提交守卫只能自己实现，参考它终端命令审批的分层结构。
- **Hermes 的 memory 不经过用户确认**：Agent 自己写；每 10 轮还会派生一个后台 Agent 回顾对话，写 memory 和 skills。JobApply 保留自己的「候选 → 确认 → 写入」流程。它的格式约束和注入检测值得借鉴。

## 1. 网络搜索与抓取（`tools/web_tools.py`）

| 项 | Hermes 的做法 | JobApply 借鉴 |
| --- | --- | --- |
| 后端 | firecrawl / parallel / tavily / exa 四选一（已核对 L129-145）；配置指定，否则按「谁有 key 就用谁」；DuckDuckGo 作为无 key 的兜底 skill | Search MCP 候选加入 Parallel，以及 DuckDuckGo 兜底；Search 做成可替换的接口，配置选择 |
| 结果形状 | 统一成 `{title, url, description, position}`，默认 5 条，上限 100；搜索只返回元数据，正文另调 extract | 一样：搜索和抓取分成两步，搜索结果不带正文 |
| 长页面 | 超过 5,000 字符就用便宜的辅助模型摘要到 5,000 字符以内；超过 50 万字符分块摘要；超过 200 万字符直接拒绝；摘要失败时截断并注明 | JD 页面同样处理，但**JD 原文快照存本地**，模型只看摘要；资格条款保留原文，不摘要 |
| URL 安全 | DNS 解析后拦截内网、回环、云元数据地址，DNS 失败也拦截；拒绝带疑似密钥的 URL | Fetch 外面套一层自研的 URL 检查 |
| 域名策略 | 用户黑名单（`website_blocklist.enabled` 默认 false，没有预设站点），子域名匹配，重定向后再查一次；被拦时返回结构化原因 | 照做：`jobapply.web.blocklist` 默认关、没有预设（2026-10-23 更正：之前预设了 LinkedIn、BOSS 等，已删） |
| 缓存、限速 | 没有 | JobApply 自己加：同一 URL 短期缓存；按域名限速 |

## 2. 浏览器（`tools/browser_tool.py` 等）

- **操作方式**：无障碍树文本快照加元素 ref（`@e1…`），click / type 作用在 ref 上，和 Playwright MCP 的思路一致。
- **快照过长**：超过 8,000 字符时，按行截断，或者用「保留 ref」的提示词让模型摘要；发给辅助模型之前先脱敏。→ **借鉴**：表单页快照可能很长，摘要时必须保留 ref，否则后续无法点击。
- **连接用户自己的 Chrome**：通过 CDP（默认 9222 端口）连接；文档建议用单独的 `--user-data-dir`，因为已经在运行的 Chrome 不会打开调试端口。→ 这和 design 里「专用 Chrome 资料目录」的结论一致。
- **弹窗监督**（`browser_supervisor.py`）：每个任务保持一个 CDP 连接，拦截 alert / confirm / prompt，把待处理的弹窗写进快照，由 Agent 明确选择接受或取消；默认策略是「必须回应」，超时 300 秒。→ **借鉴**：确认框（例如「确定投递吗？」）属于提交边界，一律交给用户。
- **原始 CDP 透传工具不设白名单**。→ **反例**：JobApply 不开放这类工具。
- **没有文件上传工具**。JobApply 用 Playwright MCP 的上传功能。

## 3. 审批与安全（`tools/approval.py` 等）

- **分层**：先规范化命令（去 ANSI、去空字节、NFKC）→ `HARDLINE_PATTERNS`（已核对 L144）无条件拦截，任何模式都无法放行 → 约 47 条危险模式需要审批 → 会话级和永久允许名单。
- **审批选项**：本次 / 本会话 / 永久 / 拒绝，60 秒超时，交互失败就拒绝。被拒时告诉模型「不要重试」。
- **无人值守**：定时任务默认拒绝；子 Agent 和后台 Agent 自动拒绝。
- **通用拦截点**：`pre_tool_call` hook 可以拦截任何工具，格式是 `{"decision":"block","reason"}`。
- 全部在代码里强制执行，不依赖提示词。

→ **借鉴**：JobApply 的提交守卫做成同样的分层：
- **hardline**：最终提交、安全验证、执行脚本。没有任何模式或允许名单可以放行。
- **需确认**：「下一步 / 保存」在未登记站点上首次出现时。
- **允许名单**：已登记站点上的安全按钮。

被拒的消息要告诉模型「不要重试，转关卡 B 或人工接力」。子 Agent（例如搜索子任务）自动拒绝一切浏览器写操作。

## 4. Memory（`tools/memory_tool.py`、`hermes_state.py`）

- **文件**：两个 Markdown 文件，`MEMORY.md`（Agent 笔记）和 `USER.md`（用户画像），条目之间用 `§` 分隔，**有硬性字符上限**（2,200 / 1,375），每次写入都返回占用比例。
- **写入**：加文件锁并原子写入；写入前检测注入、外传指令和不可见 Unicode。
- **注入方式**：会话开始时把 memory 冻结成快照放进系统提示，会话中途写入不改变提示（保持提示缓存稳定）。
- **写作规则**：写陈述性事实，不写命令句；流程性内容放进 skills。
- **历史会话检索**：SQLite（WAL 模式）的 `sessions` / `messages` 表，加 FTS5 和三元组索引，用触发器同步；搜索时取命中最多的 1-5 个会话，各自截取约 10 万字符，并行摘要。

→ **借鉴**（不改变 JobApply「文件是事实来源」的方案）：
- `base/facts.md` 等注入上下文的文件设**字符预算**，超出时提示整理，防止旧工作区那种越写越长；
- Memory 候选在写入前做**注入和不可见字符检测**（JD 和网页内容进入候选时尤其需要）；
- Session 开始时**冻结**有效资料快照，中途确认的新事实从下一轮生效；
- `index.sqlite` 用 FTS5 支持「搜历史 Session」；
- 「事实写进 Memory，流程写进 Playbook / Skill」，与 memory 设计里的分层一致。

## 5. Skills（`tools/skills_tool.py` 等）

- **格式**：`SKILL.md` 带 frontmatter：`name`、`description`、`version`、`platforms`、`prerequisites`、`metadata.hermes.{tags, related_skills, category, requires_tools, requires_toolsets, fallback_for_tools, fallback_for_toolsets}`。目录下可以有 `references/`、`templates/`、`scripts/`、`assets/`。
- **渐进加载**：系统提示里只列名称和描述；需要时用 `skill_view` 加载正文或单个参考文件。依赖的工具不可用时，skill 自动隐藏。
- **自我改进**：后台回顾时，优先修补已加载的 skill，其次修补上层 skill，再其次加参考文件，最后才新建 skill。
- **使用追踪**：`.usage.json` 记录 active / stale / archived / pinned；整理时只归档，不删除。
- **和 JobApply 相关的 skill**：`note-taking/obsidian`、`research/llm-wiki`、`optional/research/duckduckgo-search`、`optional/research/parallel-cli`、`optional/research/qmd`（本地笔记的混合检索）、`productivity/ocr-and-documents`（PDF 文本提取，可用于读简历）、`mcp/native-mcp`。

→ **借鉴**：把 memory 设计里的 `playbook/sites/<site>.md` **直接写成 SKILL.md 格式**，一个站点类型一个 skill（Moka、飞书、外企 Job Cart、Workday……）：
- frontmatter 写明 `requires_tools: [browser]`、适用域名、`observed_count`、`last_verified`；
- 正文写流程、控件坑、提交类按钮文案；
- 填表时按当前域名渐进加载对应的 skill；
- 复盘时**修补**已有 skill，不新增。但修补仍然要你确认（Hermes 不经过确认，这一点不照搬）。
- 和 Hermes 的 skill 格式兼容，以后可以互相复用。

## 6. MCP（`tools/mcp_tool.py`）

- **配置**：`mcp_servers.<name>`，可以是 stdio（`command`/`args`/`env`）或 StreamableHTTP（`url`/`headers`），带超时和启用开关。
- **工具过滤**：`tools.include` 白名单优先于 `exclude`；工具名加前缀注册为 `mcp_<server>_<tool>`。
- **加固**：stdio 子进程只拿到过滤后的环境变量；报错里去掉凭据；检测工具描述中的注入（只记警告）；断线重连。

→ **借鉴**：
- Playwright MCP 用 include 白名单（不开放 evaluate / run_code 一类工具）；
- 工具名加服务器前缀；
- MCP 子进程**只传它需要的环境变量**（Search key 不传给浏览器 MCP）；
- 工具描述的注入检测要拦截，不只是警告。

## 7. 其它

| Hermes | JobApply 怎么用 |
| --- | --- |
| `clarify_tool`：一个问题 + 最多 4 个选项，界面自动加「其它」 | 关卡 A / B、集中问答、Memory 候选确认都用这种「带选项提问」 |
| `todo_tool`：上下文压缩后重新注入待办 | Session 内的待办（未回答的问题、待上传的附件） |
| `delegate_tool`：子 Agent 用全新上下文，只返回摘要，禁止写 memory、禁止联系用户 | discover Session 的并行搜索子任务：只读，不碰浏览器写操作，不写 memory |
| cron：`jobs.json` 加按次输出的 Markdown | 可选的定时提醒，只做提醒，状态仍由你回复（符合 unknown 规则） |
| 大输出落盘：超过 10 万字符写临时文件，只给模型 1,500 字符预览和路径；每轮总预算 20 万字符 | 长 JD、长快照、长历史页同样处理 |
| `context_compressor`：摘要中间轮次，加「仅供参考」前缀 | Session 很长时（面试追踪会跨几周）压缩旧轮次，原文仍在 `transcript.jsonl` |

## 不借鉴

- 后台自动写 memory 和 skill（违反「确认后写入」）。
- 不设白名单的原始 CDP 工具；Camofox 这类反检测浏览器（与「不绕过验证」的边界冲突）。
- `optional/research/scrapling` 一类的隐身抓取。
