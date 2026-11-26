# 全部改用 TypeScript（去掉 Java 后台）的工作量与维护评估（G）

日期：2026-11-25。分支 `C30-plugins`（基于 `e255069`）。只读调研，除本文件外没有改仓库文件，没有读密钥文件。
路径相对 `backend/src/main/java/jobapply/`，除非另行注明。行数用 `wc -l` 统计（含注释和空行，Java 注释较多，实际代码量约为其 60–70%）。

背景：[B-our-agent.md](B-our-agent.md)（现有运行时审计）、[plan-B-opencode.md](plan-B-opencode.md)（B2：opencode 内核 + Java 领域服务）、[C-opencode.md](C-opencode.md)、[D-other-agents.md](D-other-agents.md)（pi-mono）。

用户的问题：如果把一切都放进一个 TypeScript 插件或 Agent 里（不要 Java 后台），比如做成 opencode 插件，或者基于 pi（TS），把业务逻辑全部用 TS 重写有多难？以后改起来会不会更难？

---

## 0. 直接回答

- **难度：中到大，不是做不到。** Java main 约 39.2k 行（157 个文件），测试 13.3k 行（94 个文件）。其中约 **1/3 换成基座后可以直接删掉**（循环、消息拼装、provider 适配、线程池），约 **1/2 是可以机械翻译的业务和存储逻辑**，剩下约 1/6 需要重新设计（Java 线程/锁换成 Node 单线程异步、HarnessLoop 拆成工具 + 钩子、文件解析库换掉）。
- **估算**：一个人加 AI 辅助，写代码约 **55–80 个工作日**，再加 **2–4 周**在真实招聘网站上把行为调回现在的水平。也就是日历上约 **3–4 个月**；这段时间新功能基本要停。
- **会退化的地方**：简历/原件解析（Apache Tika → JS 解析库，老 `.doc`、复杂 PDF 版式更差）；多申请并发下的浏览器状态机（Java 锁 → Promise 队列，最容易出新 bug）；现有 `storage/` 文件格式必须逐字节兼容，否则老用户的数据读不回来。嵌入模型的内存**不会**变差，反而可能更好（见 2.2）。
- **以后改起来**：做成**我们自己的 TS 应用（以 pi 这类库为循环内核）**，比现在更容易改——前后端一种语言、类型共用、浏览器层和 `@playwright/mcp` 同语言；做成 **opencode 插件**，业务改动本身容易，但要跟着 opencode V2 的插件 API 走（2.5 个月 51 个版本、OpenAPI 标着 Experimental），而且 opencode 插件**装不下**我们的 React 界面、REST、SSE 补发和上传，最后仍要一个自己的 TS 服务进程（见第 3 节）。
- **打包**：自己的 TS 应用约 **170–220MB**（dmg 约 80–110MB），比现在 489MB / dmg 370MB 小一半以上；加上 opencode CLI（单文件 240MB）就回到 **约 420–470MB**。注意：现在的体积大头是可以单独瘦身的（第 4 节），体积本身不足以构成重写的理由。

**建议**：如果真要去掉 Java，选「自己的 TS 应用 + pi-agent-core（或同类小库）做循环」，不要选「整个产品塞进 opencode 插件」。是否值得为此停 3–4 个月功能开发，由用户决定；本文件只给事实和估算。

---

## 1. Java 代码清单（按领域）

### 1.1 行数

| 领域 | 主要文件 | 行数 | 文件数(约) |
| --- | --- | ---: | ---: |
| 浏览器层 | `tools/BrowserSession`(759)、`PlaywrightMcpClient`(873)、`PageScripts`(900)、`OwnerBrowser`(625)、`BrowserTools`(327)、`SnapshotIndex`(251)、`PageSnapshot`、`BrowserLaunch`、`BrowserState`、`harness/runtime/ReadOnlyTab`(206)、`config/OpenBrowser`、`BrowserMcpProperties` | **4,289** | 13 |
| 守卫 / 脱敏 | `harness/policies/*`：`BrowserToolPolicy`(398)、`SubmitGuard`(255)、`FieldClassGuard`(175)、`SnapshotRedactor`(167)、`WebUrlPolicy`、`SubmitWords`、`FieldIntent`… | **1,323** | 9 |
| Agent 运行时 | `harness/runtime/*`（不含 ReadOnlyTab）：`HarnessLoop`(1805)、`SessionLoop`(675)、`FollowupLoop`(531)、`ResumeMatch`(373)、`AgentLoop`(275)、`ModelCaller`、`LoopDetector`… | **4,058** | 12 |
| 上下文 / skill | `harness/context/*`：`Conversation`、`FillContext`、`SkillCatalog`、`StateView`、`PopupView` | **1,011** | 5 |
| 提示词 / 工具定义 | `agents/*`(351)、`tools/ControlTools`、`ResearchTools`、`ExperienceTools`、`FollowupTools`(736) | **1,087** | 8 |
| 线程 / 事件 | `workflow/*`：`ApplicationRunner`(1173)、`FollowupRunner`(605)、`SessionRunner`(510)、`RuntimeEvents`(413)… | **2,867** | 7 |
| 调研工具 | `tools/WebFetch`、`WebSearch`、`GitHubPublicReader`(374)、`LocalDirectoryScanner`(445，用 JGit + Tika)、`DirectoryBrowser`、`SecretFileRules` | **1,409** | 6 |
| 模型 / provider | `config/ModelFactory`(214)、`ChatGptAuth`(646，ChatGPT 套餐 OAuth)、`ChatGptResponsesChatModel`(511)、`ChatGptIdToken`、`ChatGptAccessTokens`、`GeminiThoughtSignatures`、`ModelSettingsService`(429)、`ModelRegistry`、`ModelCatalogClient`、`ModelPresets`、`ConnectionTester`、`OpenCodeSession`；`persistence/ModelSettingsFile`(389)、`ChatGptTokenStore` | **3,336** | 16 |
| 记忆 / RAG | `memory/E5Embedding`（ONNX Runtime + HF tokenizers）、`ExperienceIndex`(405，Spring AI `SimpleVectorStore`)、`ExperienceModel`(333，运行时下载模型、空闲 5 分钟卸载)、`ExperienceSources`、`config/RagConfiguration` | **1,248** | 5 |
| 资料导入 / 原件分析 | `memory/AssetAnalyzer`（Tika）、`CandidateExtractor`、`CandidateMerger`(474)、`ImportRunner`(405)、`FactWriter`(576)、`SourceInventory`、`ProfileResolver`、`ProfileSummaryBuilder`、`TranslationDrafter`、`MemoryWriter`、`LeadGroups`、`StructuredCall`… | **3,440** | 15 |
| 持久化 / 存储 | `persistence/*`（除上面两个）：`JobLedger`(1086)、`FillReportWriter`(525)、`AssetRegistry`(503)、`StorageLayout`(478)、`TrackerWriter`(449)、`SessionRepository`(376)、`JobAnswers`、`RunLog`、`EnvFile`、`MemoryNotes`、`SiteNotes`、`CsvWriter`、`FrontmatterFile`、`AtomicFiles`、`StorageLock`、`PlaybookSeeder`… | **~8,500** | 50 |
| 领域模型 | `domain/*`：`AgentRun`、`ApplicationState(Machine)`、`HiringRound`、`ProjectCard`、`Fact`、`Job`、`Inbox`… 多为 record / enum | **2,524** | 36 |
| Web API / SSE | `api/*`：`Views`(607)、`JobController`、`EventController`(SSE + Last-Event-ID 补发)、`ProfileController`、各设置页 Controller、`LocalRequestGuard` | **3,053** | 25 |
| 其它设置 / 配置 | `config/` 余下（Properties、`PersonalSettingsService`、`StorageLocation`、`ShellWatch`…） | **~430** | 15 |
| **合计** | | **≈39.2k** | 157 |

调度：没有独立的调度框架（没有 `@Scheduled`）；后续提醒和查状态由 `FollowupRunner` / `FollowupLoop` 自己驱动，`ExperienceIndex`、`ExperienceModel` 用单线程 `ScheduledExecutorService` 做后台建索引和空闲卸载。

测试：94 个文件、13.3k 行；最多的是 `tools`(3.6k)、`persistence`(3.0k)、`memory`(1.7k)、`harness`(1.4k)、`api`(1.3k)。前端 `frontend/src` 约 10.2k 行 TS/TSX，不随本问题变化。

### 1.2 Java 依赖与 TS 对应

| 用途 | 现在（`backend/pom.xml`） | TS 对应 | 差距 / 质量 |
| --- | --- | --- | --- |
| Web 服务、SSE | Spring Boot 4.1.1 webmvc（Tomcat） | Hono / Fastify（Node 或 Bun） | 无差距，代码更少 |
| 模型调用 | Spring AI 2.0.1（`spring-ai-openai`、`spring-ai-anthropic`）+ 自写 ChatGPT Responses 模型 | pi-ai（pi-mono 的多 provider 层）或 Vercel AI SDK；opencode 自带 provider 层 | TS 生态更好：流式、思考块、Gemini 签名都由库处理；自写的 `ChatGptResponsesChatModel` 和 `GeminiThoughtSignatures` 大概率可删。ChatGPT 套餐 OAuth：pi 和 opencode 都带 Codex/ChatGPT 登录（据各自文档，**本次未逐行核对**），否则 `ChatGptAuth` 646 行需照搬（PKCE 流程，机械翻译） |
| MCP 客户端 | `spring-ai-starter-mcp-client`（MCP Java SDK） | `@modelcontextprotocol/sdk`；或者**不用 MCP**，直接 `playwright.chromium.connectOverCDP()` | TS 更直接：`@playwright/mcp` 本身是 TS，可以进程内引用，省掉一个 node 子进程和 stdio 往返 |
| 本地嵌入 | `spring-ai-transformers`（带 `onnxruntime` 1.21.1、DJL `tokenizers`），E5 池化/归一化自写 | `onnxruntime-node` + `@huggingface/transformers`（transformers.js，自带 JS 分词器，`Xenova/multilingual-e5-small` 有现成 ONNX） | 能力等同；模型仍按现在的方式运行时下载 |
| 向量存储 | Spring AI `SimpleVectorStore`（内存 + JSON，暴力余弦） | 自写约 100 行（同样暴力余弦）；需要时用 `sqlite-vec` | 无差距；要保持 `files.json` 和向量文件格式兼容，或者首次启动重建索引 |
| 文件解析 | Apache Tika 4.1.0 standard package（PDFBox、POI…） | PDF：`pdfjs-dist` / `unpdf`；DOCX：`mammoth`；PPTX/XLSX：`officeparser` / `xlsx`；老 `.doc`：`word-extractor`；HTML：`turndown` / `@mozilla/readability` | **最大的质量差距**。Tika 一个调用覆盖上千种格式；JS 要拼 4–6 个库，老 Office 格式、RTF、扫描/版式复杂 PDF 的文字顺序会更差 |
| Git | JGit 7.8（`LocalDirectoryScanner` 读仓库、`.gitignore`） | `isomorphic-git` 或直接调 `git` 命令；`.gitignore` 用 `ignore` 包 | 小差距，可接受 |
| YAML / JSON | SnakeYAML（skill frontmatter、存储）、Jackson | `yaml`、`gray-matter`、原生 JSON | 无差距；注意 YAML 输出格式（引号、换行）要和现有文件一致 |
| CSV / Markdown 表 | 自写 `CsvWriter`、`MarkdownTable` | 照搬或 `csv-stringify` | 无差距 |
| 原子写、文件锁 | 自写 `AtomicFiles`、`StorageLock`（JVM 内锁 + 文件锁） | `write-file-atomic`、`proper-lockfile` + 进程内 Promise 互斥 | 需重新设计（Node 没有线程锁的语义） |
| Token 计数 | `jtokkit` | `js-tiktoken` / `gpt-tokenizer` | 无差距 |
| 数据库 | 无（全部 md / csv / jsonl） | 不需要 | — |

---

## 2. 分领域工作量

估算口径：一个开发者 + AI 辅助，「天」= 能交付且对着真实页面跑过的工作日。S ≤ 3 天，M 4–8 天，L > 8 天。

| 领域 | 方式 | 规模 | 天数 | 说明 / 会退化的地方 |
| --- | --- | --- | ---: | --- |
| 浏览器层 4.3k | **部分重设计** | L | 7–10 | `PageScripts` 本来大多是 JS 字符串，几乎原样搬；`SnapshotIndex`、快照解析是机械翻译。难点是 `BrowserSession` / `OwnerBrowser` 的全局状态机 + 多申请并发：Java 用线程和锁，Node 里要改成每个使用者一条 Promise 队列。**这是最容易引入新 bug 的部分**，需要用多个申请同时跑真实网站来验 |
| 守卫 / 脱敏 1.3k | 机械翻译 | S | 2–3 | 纯逻辑，现有测试可以当对照用例一起翻 |
| Agent 运行时 4.1k + 上下文 1.0k + 提示词/工具定义 1.1k | **重设计**（换基座） | L | 8–12 | `AgentLoop`、`ModelCaller`、`Conversation` 的通用部分由 pi-agent-core 或 opencode 提供，删掉；`HarnessLoop.browser()` 里的守卫、占位符换值、只读检查、重复检测要拆成工具 + `tool_call` / `context` 钩子；提示词从 Java 文本块搬成 md 文件（顺便解决「改提示词要重新编译」）；skill 用基座自带的 Agent Skills 加载，`SkillCatalog` 大部分删掉 |
| 线程 / 事件 2.9k | **重设计** | M | 5–7 | 三个 Runner 的线程池、停止、收件箱变成异步任务 + `AbortController`；`RuntimeEvents` 的事件日志和补发逻辑可以照搬 |
| 模型 / provider 3.3k | 大部分删除 | S–M | 3–5 | 换成 pi-ai 后只剩设置页、模型目录、连接测试的胶水；若基座的 ChatGPT 登录不满足需求，`ChatGptAuth` 再加 2–3 天 |
| 记忆 / RAG 1.2k | 机械翻译 + 换库 | M | 3–5 | transformers.js 跑 E5；池化/前缀照搬；向量存储自写。**内存不会变差**：ONNX Runtime 在两边都是原生库，模型权重同样在原生内存里；Node 进程本体比 JVM（堆 + 元空间 + JIT）小。主要风险是 transformers.js 在 Node 下首次加载慢、以及要裁掉非 darwin-arm64 的原生二进制 |
| 资料导入 / 原件分析 3.4k | 机械翻译 + **换解析库** | M | 5–7 | 业务逻辑（候选抽取、合并、FactWriter）是机械翻译；`AssetAnalyzer` 换成多个 JS 解析库，**解析质量会退化**，需要拿用户真实的简历、作品集样本对比一遍 |
| 调研工具 1.4k | 机械翻译 | S | 2–3 | `WebFetch`/`WebSearch`/GitHub 读取直译；JGit 换 `isomorphic-git` 或 `git` 命令 |
| 持久化 / 存储 8.5k + 领域模型 2.5k | 机械翻译 | **L** | 10–14 | 量最大但最机械。硬要求：读写现有 `storage/` 的 md / csv / jsonl / frontmatter **逐字节兼容**（表格对齐、YAML 引号、换行、时间格式），否则老数据会被改写或读不回。建议把现有 Java 测试的样例文件当成金标准对照 |
| Web API / SSE 3.1k | 机械翻译 | M | 4–6 | REST 路由和 `Views` 直译；SSE 补发照搬。好处：前后端共享类型，`frontend/src/api.ts`（966 行）里的手写类型可以直接复用 |
| 设置 / 配置 / 启动 0.4k + macOS 外壳对接 | 小改 | S | 2–3 | `appbuild/macos` 改成拉起 node/bun 进程而不是 jar |
| 测试 13.3k | 选择性翻译 | M | 5–8 | 按 AGENTS.md「不主动写测试」，只翻存储格式、守卫、脱敏这类改坏会造成实际损失的 |
| **合计** | | | **≈56–83** | 再加 2–4 周真实网站回归 |

**机械翻译约占 60%，重设计约 25%，可删除约 15%（按工作量，不是行数）**。按行数看，模型/provider、循环通用部分、线程池这约 1/3 的代码在 TS 里会消失或大幅缩小，最终 TS 后端估计在 **18–25k 行**。

---

## 3. 重写以后的维护

### 3.1 一种语言 vs 两种语言

现在：Java（后端）+ TS（前端 + `@playwright/mcp` 子进程）。重写后只有 TS：

- 前后端类型共用，接口改一个字段不用两边各改一次；
- 浏览器层和 Playwright 同语言，排查 MCP / CDP 问题不用跨进程看两份日志；
- 模型生态（新 provider、思考参数、流式、提示词缓存）TS 库跟进最快，`ModelFactory.thinkingBody`「按模型名加分支」这类代码基本消失；
- 代价：失去 Java 的强类型并发和成熟的文档解析（Tika）；Node 单线程下，CPU 密集的事（嵌入、PDF 解析）要放 worker 线程，否则会卡住事件推送。

### 3.2 做成 opencode 插件 vs 自己的 TS 应用（以 pi 为库）

| | opencode 插件（「整个产品塞进插件」） | 自己的 TS 应用 + pi-agent-core |
| --- | --- | --- |
| 能不能装下全部产品 | **装不下**。插件能加工具、钩子、`/api/rpc/...` 方法、私有 KV；但我们的 React 界面、REST、带 `Last-Event-ID` 补发的 SSE、文件上传、Dashboard 数据都不是插件的形状。要么把前端整个改成调 opencode RPC + 订阅 opencode 事件（plan-B 2.1 已列出它的 SSE 不补发、慢了会断），要么另起一个自己的 TS 服务——那就又是两个进程 | 装得下：一个 Node 进程里同时跑 HTTP/SSE、循环、浏览器、存储；pi 只是一个 npm 依赖 |
| 上游变化的影响面 | 大：插件 API、服务端 HTTP API、事件名、配置格式在 V2 里都还在变（2.0.0→2.4.1 共 51 个版本，约 2.5 个月；V1 插件在 V2 不能运行；OpenAPI 标 Experimental）。我们依赖的是一整个应用的内部行为（会话、压缩、权限、skill 发现、MCP 连接池） | 中：pi 的 API 也变得快（包名刚从 `@mariozechner/*` 迁到 `@earendil-works/*`，外部 PR 默认关闭），但我们只用到循环、provider、压缩几个接口，面小 |
| 锁版本的代价 | 可以锁 2.4.1，但安全修复、新模型支持都跟着 opencode 发版走；跳版本要重新核对钩子、事件、配置 | 锁 npm 版本即可；最坏情况把用到的那部分 vendor 进来（核心循环本身不大） |
| 出问题时 | 要读 opencode 的 Effect/Bun 代码；行为（压缩时机、重试、权限）不在我们手里 | 循环在我们进程里，可单步调试，可改 |
| 白拿的东西 | 最多：压缩、子会话、权限、skill、provider、用量、revert | 少一些：循环、provider、压缩、Agent Skills；子 Agent、权限要自己写（我们本来就只需要「不点最终提交」这一条线） |
| 体积 | +240MB（opencode CLI 单文件） | +几 MB |

结论：opencode 更适合当「另一个进程里的内核」（plan-B 的 B2，Java 或 TS 服务在前面挡一层），不适合当「整个产品的容器」。若去掉 Java，**自己的 TS 应用 + pi（或同类小循环库）后续维护最省**；改业务逻辑会比现在快（一种语言、提示词变文件、工具注册不再是 switch），上游变化的影响面也最小。

---

## 4. 打包与体积

现在的 `releases/JobApply.app`（2026-11-14 构建）：

| 部分 | 大小 | 说明 |
| --- | ---: | --- |
| `jobapply.jar` | 305MB | 其中 `onnxruntime-1.21.1.jar` **137MB**（带全部平台原生库）、`openai-java-core` 53MB、`anthropic-java-core` 19MB、DJL `tokenizers` 19MB、`bcprov` 7MB、Tika/POI/PDFBox 约 15MB |
| jlink 运行时 | 49MB | |
| node + `@playwright/mcp@0.0.83` | 134MB | node 本体 116MB，MCP 18MB |
| 前端 + 图标 | 1MB | |
| **合计** | **489MB**（dmg 约 370MB） | E5 模型不在包里，运行时下载 |

TS 重写后的预估（**估算，未实际构建**）：

| 部分 | 自己的 TS 应用 | opencode 插件方案 |
| --- | ---: | ---: |
| JS 运行时 | node 116MB（或 Bun 单文件约 60MB；但 `onnxruntime-node` 等原生模块在 Bun 下要验证） | node 116MB（浏览器层仍要 node 或 opencode 内置 Bun） |
| 应用代码 + 依赖（打包后） | 10–25MB（pi-ai、Hono、pdfjs、mammoth…） | 同左 + 一个 TS 服务 |
| `onnxruntime-node`（只留 darwin-arm64） | 约 30–40MB | 同左 |
| `@playwright/mcp`（或直接用 `playwright-core`） | 18MB（`playwright-core` 约 8–10MB） | 18MB |
| opencode CLI | — | 240MB |
| 前端 | 1MB | 1MB |
| **合计** | **约 170–220MB**，dmg 约 80–110MB | **约 420–470MB** |

注意：不重写也能把现在的包瘦下来——只保留 onnxruntime 的 darwin-arm64 原生库（约省 100MB）、去掉用不到的 Tika 解析器、`openai-java`/`anthropic-java` 若被替换也能省 70MB。所以**体积不是重写的主要理由**；真正的收益是一种语言和更灵活的循环内核，真正的代价是 3–4 个月和解析质量、并发浏览器层的回归风险。

---

## 5. 未核对 / 推测

- pi 和 opencode 对 ChatGPT 套餐（Codex）OAuth 的支持程度：只看了文档描述，未运行。
- `onnxruntime-node` darwin-arm64 原生库大小、transformers.js 在 Bun 下是否可用：凭经验估算，未安装验证。
- opencode 插件能否注册任意 HTTP 路由：按 C-opencode.md 的记录只有 `/api/rpc/{rpcID}/{method}`，没有找到任意路由的入口（未逐行确认）。
- 工作量天数是按行数和复杂度的估算，不是实测。
