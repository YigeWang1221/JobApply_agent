# 开发日志

只记已经发生的事实。动机和未决问题写在 `dev-recoding.md`。每条使用下面的格式。

```text
日期：
指令：用户要求做什么
结果：实际落地了什么
验证：命令或检查；没做就写「未运行」
偏差：和指令不一致的地方；没有就写「无」
```

## 2026-10-22 · 流程文档与脚手架

- 日期：2026-10-22
- 指令：用 Spring AI + LangGraph4j 开发 JobApply，参考 `Eco_Agent` 的 md 文件和 `backend/`、`frontend/` 结构，先搭基础脚手架。中途要求 JDK 不要按项目单独配置，改为系统级默认。
- 结果：新增 `AGENTS.md`、`CLAUDE.md`、`dev-plan.md`、`dev-log.md`、`dev-limit.md`、`dev-recoding.md`、`.cursor/rules/dev-process.mdc`、`plan/`（`README.md`、`00-process.md`、`jobapply-base.md`、`jobapply-architecture.md`、`jobapply-scaffold.md`）、`.gitignore`。`backend/` 单模块 Spring Boot：`pom.xml`、`JobApplyApplication`、`application.yml`、上下文测试，`jobapply` 下 16 个只有 `package-info.java` 的包。`frontend/`、`storage/`、`docs/` 为空目录。本机经 Homebrew 安装 `openjdk@21`（21.0.13）与 `maven`（3.10.0），并在 `~/.zshrc` 设置 `JAVA_HOME` 与 `PATH`，作为全局默认 JDK。未提交。
- 验证：新 shell 中 `java -version` 为 21.0.13。在 `backend/` 执行 `mvn -B clean test` 退出码 0，`BUILD SUCCESS`；`jobapply.JobApplyApplicationTest`：Tests run: 1, Failures: 0, Errors: 0, Skipped: 0；日志含 `Spring Boot :: (v4.1.1)`。`mvn dependency:tree` 解析为 `spring-ai-client-chat:2.0.1`、`spring-ai-model:2.0.1`、`spring-ai-commons:2.0.1`、`langgraph4j-core:1.9.6`、`langgraph4j-spring-ai:1.9.6`。
- 偏差：相对 Eco 的目录去掉了 `execution/`、`python/`、`workflow/delegation`（见 `plan/jobapply-architecture.md`，待确认）。`/Library/Java/JavaVirtualMachines` 软链接需要 sudo，未做。

## 2026-10-22 · 主流程设计草案

- 日期：2026-10-22
- 指令：给出产品需求：按本地个人资料找匹配岗位；通过 Chrome MCP 点击填写申请表；提交前停下审核，最终提交交给用户；本地用 Excel 或 Markdown 这类 AI 易读格式跟踪投递了什么、处于什么阶段、上次查询时间。设计偏重 MCP。
- 结果：新增 `plan/jobapply-design.md`（主流程、关卡 A/B、三层提交保护、Chrome MCP 候选、本地数据布局与台账格式、状态机、六段施工顺序、待用户决定）。`plan/jobapply-base.md` 写入产品目标。`dev-plan.md`、`plan/README.md` 只改索引与停靠点。`dev-limit.md` 加入「系统永远不执行最终提交」。`.gitignore` 忽略 `storage/*`（保留 `.gitkeep`），防止个人资料进 Git。未写代码，未提交。
- 验证：只读查询：Maven Central 上 `org.springframework.ai:spring-ai-starter-mcp-client:2.0.1` 返回 200；npm `chrome-devtools-mcp` 1.12.0、`@playwright/mcp` 0.0.83；本机 Google Chrome 155.0.8059.85。`git check-ignore` 确认 `storage/profile/profile.md` 被忽略、`storage/.gitkeep` 不被忽略。
- 偏差：无。

## 2026-10-22 · 设计补充：登录状态与 Web Search

- 日期：2026-10-22
- 指令：设计中要考虑登录状态的维持；岗位搜索需要 Web Search 能力。
- 结果：`plan/jobapply-design.md` 新增「登录状态」「岗位搜索（Web Search）」两节；主流程加入登录检查节点；状态机加入 `needs_login`；本地数据加入 `storage/sessions/sites.md`；施工顺序第 2 段并入登录检查，搜索提前到第 4 段；「待用户决定」第 1 项改为 Search MCP 选型，新增第 7 项。未写代码，未提交。
- 验证：只读查询：npm `@brave/brave-search-mcp-server` 2.1.4、`exa-mcp-server` 3.4.2、`tavily-mcp` 0.2.22、`firecrawl-mcp` 3.34.0；npm `mcp-server-fetch` 为 `0.0.1-security` 占位包；PyPI `mcp-server-fetch` 2026.10.19（作者 Anthropic）。Greenhouse、Lever、Ashby 公开岗位接口 curl 均返回 200。
- 偏差：无。

## 2026-10-22 · 接入参考数据 example.data

- 日期：2026-10-22
- 指令：用户把此前用 Codex Agent 投递时的本地文件夹放到 `storage/example.data/`，要求加入 `.gitignore`；只允许用于流程研究，不允许作为训练数据上传。
- 结果：`.gitignore` 显式加入 `storage/example.data/`（原有的 `storage/*` 已覆盖它）。`dev-limit.md`「信息边界」写入使用规则；`AGENTS.md` 加一行指向。只列出了目录结构（约 1.0M：61 个 md、3 个 csv、2 个 xlsx、2 个 numbers、2 个 json、1 个 docx），未读取文件内容。
- 验证：`git check-ignore -v` 确认其中的文件被忽略；`git status --short` 中没有 `example.data` 条目。
- 偏差：无。

## 2026-10-22 · 旧工作区复盘与 Project / Session 设计

- 日期：2026-10-22
- 指令：用户授权本次一次性使用 `storage/example.data/` 的全部数据做规划，范围包括本地 Memory（个人信息、流程优化）与文件结构；规划完成后用户会删除该数据。中途补充：做成类似 Claude 的界面，Session 只存在于 Project 中；Project 对应不同侧重的本地 Memory，在时间线上有继承关系，但可以并行、相对独立；`storage` 下有一个 base 目录存放基础个人信息；一个 Session 基本对应投递一份简历。
- 结果：只读阅读了 example.data 的流程文档（README、AGENTS、WORKFLOW、WORK_BY_STEPS、DECISIONS、JOB_RESEARCH、LOG、PROFILE）、8 份申请记录与交接文件、CSV / JSON 台账的列结构，以及研究 xlsx 的 Target Jobs 表头、Scoring Model、Limit Audit、Strategy、Resume Audit 等工作表。新增 `plan/jobapply-research-codex.md`（参考，已抽象，不含个人信息、具体公司投递明细和岗位 ID）、`plan/jobapply-memory.md`（Project / Session、base 继承、storage 结构、字段分级、Memory 写入流程、Session 记录格式）。重写 `plan/jobapply-design.md`：关卡 A 改为选岗加材料包确认；加入集中问答、人工接力、上传后等解析再校正、保存后回读、只读核验与证据状态；提交守卫加入国内站点的提交类按钮文案和安全验证处理；浏览器 MCP 倾向改为 `@playwright/mcp`；搜索改为以招聘门户类型为单位，并考虑 SPA 页面；新增匹配评分、额度、新状态机，施工顺序改为 7 段。`plan/jobapply-architecture.md` 加入领域对象说明；`dev-plan.md`、`plan/README.md` 更新索引。未修改 example.data，未写代码，未提交。
- 验证：用 grep 扫描 `plan/`、`AGENTS.md`、`dev-*.md`，检查姓名、邮箱、手机号、学校、具体公司名与项目名，发现一处公司名并已改掉；其余命中都是技术名词（Spring AI Alibaba 等）。
- 偏差：读取了 4 份旧版研究 xlsx 中最新的 v4（各版本工作表结构一致）；两个 `.numbers` 文件和 `backups/` 下的历史副本没有逐个阅读，内容与主文件重复。

## 2026-10-22 · 追踪、Dashboard 与面试复盘设计

- 日期：2026-10-22
- 指令：重新设计台账的栏目，要考虑不同公司投递后的多轮面试；每个 Project 要有大 Dashboard（Linear 风格或其他图形），反映投递数、各阶段公司数、未追踪数；投递后的追踪与复盘（每轮面试问了什么、结果、经验）要通过 Session 对话更新进台账与 Memory，方便日后按公司、轮次、常考题复习；需要给出这些内容存放在哪里的方案。
- 结果：新增 `plan/jobapply-tracking.md`：固定阶段（7 个）+ 结局（6 种）+ 可变轮次的模型；阶段由轮次推导；公司流程模板；申请总表（13 列）与轮次表（9 列）；Dashboard 布局与 7 个组件的计算口径；Session 生命周期延伸到投递之后；轮次复盘文件格式；题库通过 `canonical` 归并并统计出现次数；`review/` 的存放位置与隐私规则；5 项待决定。`plan/jobapply-memory.md` 的 storage 树加入 `tracker/`、`rounds/`、`review/`，Session frontmatter 改为 `phase` / `prep_state` / `outcome` / `rounds`。`plan/jobapply-design.md` 的状态机改为「阶段 + prep 子状态」，施工顺序改为 8 段。`dev-plan.md`、`plan/README.md` 更新索引。未写代码，未提交。
- 验证：未运行。本步只有文档。
- 偏差：无。

## 2026-10-22 · 追踪决定与存储格式

- 日期：2026-10-22
- 指令：用户确认：7 个阶段加 6 种结局足够；投递后的状态由用户在 Session 里手动回复，用户没同步的记为 unknown；漏斗按公司数统计（各公司允许投的岗位数不同）；题库跨 Project 汇总。询问主流的本地 Memory 存储方式与本项目打算怎么存；讨论完后要一份 HTML 示意图。
- 结果：`plan/jobapply-tracking.md` 写入已确认 5 项，新增「unknown 规则」（全局单一阈值，默认 7 天；ghosted 只能由用户设置），轮次结果加入 `unknown`，申请总表的「静默」列改为「同步」，漏斗改为按公司统计，看板只读。`plan/jobapply-memory.md` 新增「存储格式」：7 类主流方式对比；本项目采用 Markdown + YAML frontmatter（事实来源）+ JSONL（事件与对话）+ 可重建的 SQLite 索引（FTS5）+ LangGraph4j sqlite-saver 存 Checkpoint；向量检索后置，且只用本地 embedding。storage 树和 architecture 同步修改。HTML 示意图尚未制作。
- 验证：只读查询 Maven Central：`org.bsc.langgraph4j:langgraph4j-sqlite-saver:1.9.6`、`langgraph4j-postgres-saver:1.9.6`、`langgraph4j-mysql-saver:1.9.6` 返回 200；`org.xerial:sqlite-jdbc` 最新版本 3.53.4.0；`org.springframework.ai:spring-ai-model-chat-memory-repository-jdbc:2.0.1` 返回 200。
- 偏差：无。

## 2026-10-22 · 存储方案确认、Obsidian 兼容、Dashboard 示意页

- 日期：2026-10-22
- 指令：同意「文件为事实来源 + 可重建 SQLite 索引」的存储方案；Memory 要能在 Obsidian 中浏览；做一个 HTML 示意图来看效果。中途补充：网络搜索等 tools、skills 可以参考 Hermes 这类 Agent 项目。
- 结果：`plan/jobapply-memory.md` 将存储方案标为已同意，并加入 Obsidian 兼容规则（相对链接、frontmatter 常用字段平铺、tags、`.obsidian/` 忽略、不依赖插件）。用虚构数据制作 Dashboard 示意页（Project / Session 侧栏、KPI、按公司 / 按申请切换的漏斗、接下来 7 天、待追踪 unknown、只读看板与进度点、时间线、每周节奏、复盘概览、申请总表、轮次表、题库、Session 详情与面试后对话示例），发布为私有 Artifact：https://claude.ai/artifact/4K6eE85i51GvrLUGz8cARD 。源文件在会话 scratchpad 中，不在仓库里。已开始只读梳理本机 `~/.hermes/hermes-agent`（Hermes Agent 0.12.0）的源码与 skills，未读取其配置、密钥和 memories。
- 验证：页面中的阶段、unknown、漏斗口径都由同一份示例数据按 `plan/jobapply-tracking.md` 的规则计算得出。未做渲染预览。
- 偏差：无。

## 2026-10-22 · Hermes Agent 参考

- 日期：2026-10-22
- 指令：网络搜索等 tools、skills 可以考虑使用和参考 Hermes 这类 Agent 项目。
- 结果：只读梳理本机 `~/.hermes/hermes-agent`（Nous Research Hermes Agent 0.12.0）的 web_tools、browser、approval、memory、skills、mcp 等模块，由一个只读子任务完成，并写明不读配置、密钥、memories。新增 `plan/jobapply-research-hermes.md`（参考）。`plan/jobapply-design.md` 加入自研 Web 层（URL 安全、域名策略、缓存限速、长页面摘要、大输出落盘）与守卫分层（hardline / 需确认 / 允许名单）、MCP include 白名单与环境变量隔离。`plan/jobapply-memory.md` 将站点 Playbook 改为 SKILL.md 格式，并加入字符预算、写入前注入检测、冻结快照、修补优先、FTS 历史检索。`dev-plan.md`、`plan/README.md` 更新索引。
- 验证：抽查子任务结论：`tools/web_tools.py` L129-145 的后端为 firecrawl / parallel / tavily / exa；`tools/browser_tool.py` 中没有 approval 调用；`tools/approval.py` L144 定义了 `HARDLINE_PATTERNS`；`tools/mcp_tool.py` 有 include / exclude 过滤。其余结论来自子任务报告，未逐条核对。
- 偏差：无。

## 2026-10-22 · Session 与岗位解耦、界面结构、模型设置

- 日期：2026-10-22
- 指令：同意看板。首页或全局时主区域显示看板；点进 Project 后要有「新建 Session」，在里面和 AI 聊下一家投什么、AI 搜到了什么。同一家公司可能投多个岗位，而且不一定符合 Project 名字的方向，Project 命名要考虑这一点。Web research 可能先把推荐公司和岗位写进本地列表，几个 Session 之后才投；一个 Session 可能只调研、调研加投递，或者调研很多只投一两个。模型要支持自定义：预留主流厂商接口，设置里可以选 OpenAI 兼容或 Anthropic 格式，自己填 URL 和 API key。
- 结果：`plan/jobapply-memory.md` 新增「修订：Session 与岗位解耦」：Project / Job / Session / Company 四个对象；Job 跨 Session，先进候选池（`lead_status`）再转为申请；Session 类型改为自动打标签，结束时写交接摘要；申请流程的 Checkpoint 按 job id 保存；Project 按资料版本命名。storage 树改为 `jobs/<job-id>/` 与 `sessions/<session-id>/` 并列，`sessions-index.md` 改名为 `jobs-index.md`，新增 `settings/models.yaml`。frontmatter 示例改为 `job.md`。`plan/jobapply-tracking.md`、`plan/jobapply-design.md` 中的旧写法同步修改。新增 `plan/jobapply-ui.md`（各视图分工、新建 Session 的开场、候选池按公司分组、模型预设表、用途分配、运行时创建 ChatModel、测试连接、API key 只存 macOS 钥匙串）。示意页更新到同一链接：全局 Dashboard 首页、Project 页（新建 Session、最近 Session）、候选池标签、Session 对话视图（可以输入，回复是示意）、岗位详情里的「涉及过它的 Session」、设置页（预设、两种接口格式、Base URL、key、测试连接、用途分配）。
- 验证：只读查询 Maven Central：`org.springframework.ai:spring-ai-openai`、`spring-ai-anthropic`、`spring-ai-ollama`、`spring-ai-starter-model-openai`、`spring-ai-starter-model-anthropic` 的 2.0.1 均返回 200。页面脚本 `node --check` 通过；用简易 DOM 桩依次运行了 Project 页、候选池、Session、发送消息、新建 Session、设置页、岗位详情的渲染，均没有报错。未在浏览器里做渲染预览。
- 偏差：示意页中各厂商的 Base URL 是按已知值填写的示例，施工时要按官方文档核对（已写进 `plan/jobapply-ui.md`）。

## 2026-10-22 · 确认界面、浏览器、流程、台账与 Memory 决定

- 日期：2026-10-22
- 指令：用户确认：前端 React + Vite；API key 保存在本地 `.env`；模型预设不变。浏览器 MCP 用 `@playwright/mcp`，直接使用本机 Chrome，第一次连接和断开后各由用户同意一次。每次申请：打开页面 → 交给用户登录 → 岗位核实 → 简历补全 → 提交前交给用户审阅 → 用户手动提交 → 审阅通过后做本地流程优化和内容更新；询问流程优化会不会对 Agent 造成不好的影响。台账用 md + csv，每个 Project 一组。Memory：新建 Project 用继承加上传文件或文字核实（需要 Word / Markdown / PDF 读取工具）；本地 git 只保留版本不上传；Agent 能填的统一填完再由用户审核；原件保存在 base，其它跟随 Project。询问 Search MCP、自己搜索、Hermes 网络搜索的区别。
- 结果：`plan/jobapply-design.md`：主流程改为用户确认的顺序（每次申请先交给用户登录）；新增「流程优化的安全边界」（6 类风险与防护）；Playwright MCP `--extension` 连接本机 Chrome，只操作 Agent 自己打开的标签页，去掉专用资料目录和登录预检；新增「三种接法的区别」，倾向后端直接调搜索 API，并按 Hermes 的设计实现 `SearchProvider`；关卡 A 不再逐项确认 B 级信息；台账规则为每个 Project 一组 md + csv；决定区更新为已确认 5 项、待定 2 项。`plan/jobapply-memory.md`：新增「新建 Project」流程和文档读取工具（Tika 4.1.0 / PDFBox 3.0.8 / POI 6.0.0，扫描件 OCR 后置）；原件存放改为 `base/originals/`；storage git 只在本地；`settings/.env` 存 key；B 级字段统一填完后审核；决定区全部已确认。`plan/jobapply-ui.md`：密钥改为本地 `.env`，决定区全部已确认。`plan/jobapply-tracking.md` 同步台账格式。
- 验证：只读查询 Maven Central：`tika-core` / `tika-parsers-standard-package` 4.1.0、`pdfbox` 3.0.8、`poi-ooxml` 6.0.0；查看 npm `@playwright/mcp` README 中的 `--extension` 与 `--profile-dir-name` 说明。
- 偏差：无。

## 2026-10-22 · 分层 Web 工具、允许名单、遗漏检查

- 日期：2026-10-22
- 指令：参考 Hermes 的分层 Web 工具与省钱做法（search / extract / browser / cache，免费来源优先；结果缓存；正文截断；文本化快照），不引入 Hermes 本身；免费额度用完后使用 Brave，Brave key 保留填写入口但不是必须的。允许名单：没登记过的站点首次遇到「下一步 / 保存」时暂停问用户（确认「需要」）。询问不涉及代码的 design 是否已经确认，以及 work case / flow 还有没有遗漏。
- 结果：`plan/jobapply-design.md` 用「已确认：分层 Web 工具」替换原来的建议段（四层职责、提供方链式切换、15,000 字符头尾截断加全文落盘、共享缓存、为什么不用 Java Playwright、不采纳 Browser Use 模式）；允许名单标为已确认；新增「遗漏检查」10 项及建议默认做法。`plan/jobapply-research-hermes.md` 新增「官方文档补充」，记录较新版本与本机 0.12.0 的差异。
- 验证：用 WebFetch 查阅 Hermes 官方文档的 web-search 与 browser 两页，并与本机源码对比。
- 偏差：用户转述的材料中说 Hermes 用的是「Browser Use CLI / browser-harness」，经官方文档核实属实；但该模式让 Agent 执行任意 Python，与提交守卫冲突，所以不采纳，已在 plan 中写明。

## 2026-10-22 · 放大预算与停止条件

- 日期：2026-10-22
- 指令：遗漏检查第 4 项「单个申请的预算和停止条件」要适当放大，因为 Playwright 调用次数会很多，岗位调研也是。
- 结果：`plan/jobapply-design.md` 新增「预算与停止条件」：改为「软上限 + 问是否继续」与「打转检测」两类。单个申请默认每页 120 次、整体 600 次工具调用、300 万 token；同一元素连续失败 5 次、相同操作相同结果 4 次、页面无变化 10 次就停下；关卡与接力不设超时。调研默认 search 150、extract 300、浏览器只读 80、新增候选 60、200 万 token、并行子任务 4 个。另外写明降低消耗的做法。遗漏检查第 4 项标为已确认。其余 9 项仍待用户确认。
- 验证：未运行。本步只有文档。
- 偏差：无。

## 2026-10-22 · 遗漏项确认、plan 目录改为 Master / Coding

- 日期：2026-10-22
- 指令：遗漏检查其余 9 项保持默认；plan 目录改为 Master Plan / Coding Plan 两类（Master：做什么、为什么、总体设计，相对稳定，1 对 N；Coding：一次可执行的开发任务，具体文件、步骤、测试、验收标准，持续更新），现有的都是 Master Plan。
- 结果：遗漏检查标为已确认，第 2、8 项写入 memory，第 5、7 项写入 ui，第 9、10 项写入 tracking。plan 目录重排：`plan/master/` 放 base、architecture、workflow（原 design）、memory、tracking、ui，状态标为已确认；`plan/coding/` 放 `C000-process.md`（原 00-process）、`C001-scaffold.md`（原 jobapply-scaffold），文首加「对应 Master」；两份调研移到 `plan/reference/`（research-codex、research-hermes）。所有文件内的相对链接已改到新路径。`plan/README.md` 重写为两类计划的定义、约定和索引；`dev-plan.md` 重写索引；`AGENTS.md` 的文档分工表、`.cursor/rules/dev-process.mdc`、`dev-limit.md` 同步。workflow 的「施工顺序」改名为「里程碑」，每段拆成 Coding Plan。未写代码，未提交。
- 验证：用 grep 检查 `plan/`、`AGENTS.md`、`dev-limit.md`、`.cursor/` 中的旧文件名引用，已清零（`dev-log.md` 和 `dev-recoding.md` 中的旧路径是历史记录，保留原样）。
- 偏差：用户只给了两类；另外设了 `plan/reference/` 放调研材料，因为它们既不是 Master 也不是 Coding。

## 2026-10-22 · 起草 C002（Memory 与台账）

- 日期：2026-10-22
- 指令：按 `dev-plan.md`「新会话从这里接手」起草 C002，先只写 Coding Plan 交给用户确认。
- 结果：新增 `plan/coding/01-foundation/C002-memory-ledger.md`（状态：待确认），内容包括目标、不做、依赖、要改的文件、关键规则、5 个切片、测试、验收标准，以及 12 项待确认。`dev-plan.md`、`plan/README.md` 的索引行指向该文件。未写代码，未改 Master。
- 验证：只读查询 Maven Central，JGit 最新 release 为 `7.8.0.202609011348-r`；离线执行 `mvn dependency:tree`，确认 `snakeyaml` 2.6、`tools.jackson.core:jackson-databind` 3.1.5 已经在传递依赖中。没有运行测试（本步只有文档）。
- 偏差：无。

## 2026-10-22 · C002 依赖确认，git 改为可选项

- 日期：2026-10-22
- 指令：C002 依赖表：git 是可选项，可以开也可以不开，不开时不用其他方法；YAML、JSON / Map、CSV、测试四项都同意。
- 结果：C002 的依赖表标为已确认。新增 git 开关的规则：`.git` 存在即视为开启，提供 `enableGit()`，不提供关闭方法；关闭 git 时 `base_pin` 只能是 `live`。测试表加入不开 git 的用例。按本次指令同步 Master：`memory.md`（storage 树、`base_pin`、新建 Project、Memory 写入、写入顺序、已确认第 3 项）、`workflow.md`（流程优化的回滚一行、里程碑 1），都改成「开启 git 时」。C002 待确认的第 3–12 项仍在等用户回复。
- 验证：未运行。本步只有文档。
- 偏差：无。

## 2026-10-22 · C002 实现：Memory 与台账

- 日期：2026-10-22
- 指令：C002 文末待确认的各项「按建议」处理。
- 结果：
  - 文档：第 5–12 项回写到 Master。`workflow.md`：里程碑 1 改为 `applied`，补充 prep_state 规则；`memory.md`：`.gitignore` 加 `example.data/`，写明父 Project 层和 base_pin 的取法、`unset` 写法和 ID 格式；`tracking.md`：新增表 0（候选池栏目），岗位链接改为指向 `job.md`，复盘路径改为 `jobs/<job-id>/rounds/`。第 4 项定为 5 片一次做完。
  - 代码：`backend/pom.xml` 新增 JGit `7.8.0.202609011348-r`，并显式声明 SnakeYAML 和 Jackson databind（不写版本号）。在 `domain`、`persistence`、`memory` 三个包里实现了 storage 初始化（git 可选）、frontmatter 与事实条目读写、候选池、状态机、事件日志、Session 记录、leads 和 applications 台账（md + csv）、`jobs-index.md`、`exports/applications.csv`、继承解析（`base_pin` 支持 live / commit / 日期，含循环检测）。清单见 C002「实际结果」。
- 验证：在 `backend/` 执行 `mvn -B clean test`，退出码 0；Tests run: 166, Failures: 0, Errors: 0, Skipped: 0。保留了一次测试的临时目录并人工查看生成的文件，看完后已删除。主仓库的 `storage/` 没有变化。没有运行任何会访问网络的测试；本步唯一的网络操作是 Maven 下载 JGit 及其依赖。
- 偏差：见 C002「偏差」（施工顺序、公司按码点排序、比计划多做的几处、已知限制）。未提交 git。

## 2026-10-22 · C002 测试整理，README

- 日期：2026-10-22
- 指令：（1）把三个 JobLedger 测试合并为一个，用 `@Nested` 分组；（2）增加一个端到端场景测试。另外按 `plan/master/` 简单写一下 README。
- 结果：
  - 先把追加范围写进 C002「追加：测试整理」，再动代码。
  - 新建 `JobLedgerTest`，删除 `JobLedgerLeadTest`、`JobLedgerAtomicityTest`、`JobLedgerNoGitTest`。
  - 新增 `jobapply.MemoryLedgerScenarioIT`：一个 Project、两个 Session，从调研走到申请以 rejected 结束，并检查文件、事件、台账、继承解析和 git 历史；最后断言工作区是干净的。
  - `backend/pom.xml` 加入 `maven-failsafe-plugin`（版本和执行配置由 Boot 父 POM 管理）。`TestStorage` 加了 `isClean()`。
  - `AGENTS.md`「本机构建」改为 `mvn -B clean verify`。
  - `README.md` 从一行标题改写为项目说明：核心原则、工作方式、技术栈、里程碑进度、目录、构建命令、设计文档链接。内容来自 6 份 Master，明确标注只完成了里程碑 1。
  - 主代码行为没有改动。
- 验证：在 `backend/` 执行 `mvn -B clean verify`，退出码 0；surefire Tests run: 166，failsafe Tests run: 1，Failures / Errors / Skipped 均为 0。README 没有可以执行的检查，属于人工撰写。
- 偏差：无。未提交 git。

## 2026-10-22 · C002 三方评审修复

- 日期：2026-10-22
- 指令：三方评审 6 条中修复第 1–5 条，第 6 条（CRLF 换行的 frontmatter）不处理。
- 结果：
  - 先把范围写进 C002「追加：三方评审修复」。每条都先补一个复现测试，确认它失败后再修。
  - 第 2 条：`JobRepository` 写回 `evidence` 时在已有内容上合并，`evidence` 下其它子字段和 `submitted` 里多出来的字段都保留。
  - 第 3 条：新增 `domain.Ids`，`StorageLayout` 拼路径前校验 project / job / session id 的格式；列目录时跳过名字不合规的目录；`ProjectSpec` 改用 `Ids`。
  - 第 1 条：`StorageGit.commit` 只检查本次路径有没有变化，并用 `setOnly` 提交，用户事先暂存的文件不会被带进来，提交后仍在暂存区。
  - 第 4 条：`JobLedger` 在写任何文件之前先检查 actor、posting、transition 不为空，并先构造好事件；`JobEvent` 要求 actor 和 type 不为空。
  - 第 5 条：`FactsFile.render` 在渲染前检查重复 key，有重复就报错，原文件不动。
- 验证：修复前，5 个新测试按预期失败（`StorageGitTest`、`FactsFileTest`、`JobLedgerTest$Leads` 各 1 个，`JobLedgerTest$InputValidation` 2 个）。修复后在 `backend/` 执行 `mvn -B clean verify`，退出码 0；surefire Tests run: 172，failsafe Tests run: 1，Failures / Errors / Skipped 均为 0。
- 偏差：无。未提交 git。

## 2026-10-22 · C002 验收

- 日期：2026-10-22
- 指令：C002 验收通过。
- 结果：C002、`plan/README.md`、`dev-plan.md` 的状态改为「完成」；`dev-plan.md` 的停靠点改为等待 C003 起草前的 4 项决定。
- 验证：未运行（本步只改文档状态）。最近一次 `mvn -B clean verify` 的结果见上一条。
- 偏差：无。

## 2026-10-22 · 起草 C003（Playwright MCP 接通）

- 日期：2026-10-22
- 指令：开发 C003。用户已定 4 项：（1）用最小的本地 HTTP 接口告诉系统「登录好了」；（2）现在就接 LangGraph4j 的中断与 Checkpoint；（3）真实连接只做手动冒烟，由用户装扩展、选测试网站；（4）本机有的环境就用本机的，没有的再装，`@playwright/mcp` 版本起草时重查。
- 结果：按 `AGENTS.md` 先起草 `plan/coding/01-foundation/C003-playwright-mcp.md`（状态：待确认），内容包括目标、不做、环境、依赖、要改的文件、关键规则（图、台账衔接、登录页识别、身份核对、`sites.md`、HTTP、子进程）、5 个切片、测试、手动冒烟步骤、验收标准、3 项回写 Master、8 项待确认。`dev-plan.md`、`plan/README.md` 加了索引行。未写代码，未改 Master。
- 验证：只读查询了本机环境和公开仓库。本机 Node `v22.13.1`、npm `10.9.2`（nvm），npx 可用；npm 上 `@playwright/mcp` 的 `latest` 仍是 `0.0.83`（2026-09-28 发布）。Maven Central 上有 `spring-ai-starter-mcp-client` 2.0.1（依赖 MCP Java SDK 2.0.0）、`langgraph4j-sqlite-saver` 1.9.6（依赖 `sqlite-jdbc` 3.53.2.0）、`spring-boot-starter-webmvc` 4.1.1。用 `javap` 确认了 LangGraph4j 1.9.6 的 `interruptBefore`、`updateState`、`GraphInput.resume()`。把 `@playwright/mcp` 0.0.83 和 `playwright-core` 1.64.0-alpha 下载到会话临时目录，读源码确认了扩展连接超时为 30 秒、返回文本的小节格式；MCP SDK 的 stdio 传输会继承全部环境变量。没有运行测试（本步只有文档）。
- 偏差：无。

## 2026-10-22 · C003 第 5 项：账号标识改为可选

- 日期：2026-10-22
- 指令：账号标识是可选项。各平台登录方式不同（邮箱、账号密码、公司自家的社交 app），所以可以填 UID、用户名、邮箱、手机号，也可以留空。
- 结果：C003「关键规则 · 身份核对」改为：标识可选，不区分是哪一种；只校验去掉首尾空格后不超过 128 个字符、不含换行或 `|`（避免写坏 `sites.md` 的表格）；留空或页面上没有显示时，交给用户确认；`sites.md` 原样保存，标识不写进日志。`sites.md` 示例、目标第 1 条、Controller 测试、待确认第 5 项都已同步。按本次指令回写 `plan/master/workflow.md`「登录状态」：删掉「只写邮箱或手机号末四位」，改为「可选，由用户自己填写」。
- 验证：未运行。本步只有文档。
- 偏差：原草案的上限是 32 个字符，因为当时假定只填打码形式；现在允许填完整邮箱，所以放宽到 128 个字符。

## 2026-10-22 · C003 确认、Java 升到 25、C003 实现

- 日期：2026-10-22
- 指令：C003「待确认」其余各项和「回写 Master」3 项「按照建议」。施工中又追加两条：本机 Java 也升级（选了 Java 25 LTS）；卸载本地旧的 JDK。
- 结果：
  - 文档：C003 状态改为进行中；回写 workflow.md（主流程「用户登录」一行改为本地 HTTP 信号加 override；Chrome MCP 版本复查；扩展只等 30 秒）、architecture.md（`api` 只监听 127.0.0.1、拒绝 Origin）。
  - Java：`brew install openjdk@25`（25.0.5）；`pom.xml` 的 `java.version` 改为 25；base.md、C001 备注、`AGENTS.md`「本机构建」、README 同步。`brew uninstall openjdk@21`；Homebrew 顺带自动清掉了它判断已经没人用的 `fdk-aac`、`jpeg-xl`、`highway`（输出被截断，前面可能还有别的包）。`openjdk` 27 是 Maven 的依赖，保留。Oracle JDK 19 需要 sudo，没有删。`~/.zshrc` 没有改。
  - 代码：清单见 C003「实际结果」。
- 验证：升级后、写 C003 图之前，JDK 25 下跑 `mvn -B clean verify`，退出码 0（surefire 227，failsafe 1）。C003 完成后再跑 `mvn -B clean verify`，退出码 0：surefire Tests run: 251，failsafe Tests run: 2，Failures / Errors / Skipped 均为 0。构建日志里的 MCP 启动全部是假服务端，没有调用 npx，没有访问网络。手动冒烟：未运行。
- 偏差：见 C003「偏差」。没有提交 git。

## 2026-10-22 · C003 收拢文件

- 日期：2026-10-22
- 指令：撤回 `package-info.java` 的一行说明；合并 4 个异常类。
- 结果：`git checkout` 撤回了 8 个 `package-info.java`（之前报告说是 9 个，这个数字是错的）。`BrowserDisconnectedException` 和 `BrowserToolException` 合并为 `tools.BrowserException`，原来的两个类改成嵌套子类 `Disconnected`、`ToolFailed`；`LoginHandoffConflictException` 和 `LoginHandoffNotFoundException` 合并为 `workflow.graphs.LoginHandoffException`，嵌套子类为 `Conflict`、`NotFound`。行为不变。`ToolNotAllowedException` 属于 `harness.policies` 这一层，没有并进来。
- 验证：在 `backend/` 用 JDK 25 执行 `mvn -B clean verify`，退出码 0；surefire Tests run: 251，failsafe Tests run: 2，Failures / Errors / Skipped 均为 0。
- 偏差：无。没有提交 git。

## 2026-10-22 · 边界：允许在用户监控下实体机调试

- 日期：2026-10-22
- 指令：项目开发时，允许在用户监控下的实体机器上调试。
- 结果：`dev-limit.md`「范围边界」新增「实体机调试」一条：在用户监控下，代理可以在本机真实环境里调试和冒烟（连接本机 Chrome、打开用户指定的测试网址、只读取快照）。登录、注册、填写、提交仍由用户完成；只在用户当次指定的网址上操作；登录信号只在用户告知后才代发。自动测试的边界不变。
- 验证：未运行。本步只改文档。
- 偏差：无。

## 2026-10-22 · C003 远程调试模式、新开标签页、真实 Chrome 冒烟

- 日期：2026-10-22
- 指令：用户打开了 Chrome 的远程调试开关，要求用 `https://careers.oppo.com/university/oppo/campus` 测试；选择「改成新开标签页再测」；允许在用户监控下用本机真实 Chrome 调试。
- 结果：
  - C003 加了「追加：远程调试模式与新开标签页」，然后改代码：白名单允许 `browser_tabs new`（只允许 http / https）；`open_page` 改为新开标签页；重连后直接重新新开岗位页；`BrowserSession` 记住当前连接上有没有自己的标签页，没有就拒绝取快照；`check_page` 遇到新连接时回到 `open_page`；删掉 `resume_to`。
  - 第一次冒烟暴露了配置问题：`storage.root` 的相对路径不能绑定成 `Path`。已修复，并把 `JobApplyApplicationTest` 改成用真实端口启动。冒烟类改用命令行参数传配置。
  - 第二次冒烟通过，过程和发现见 C003「手动冒烟结果」。断开时的提示改成扩展模式和远程调试模式都适用的说法。
- 验证：改成新开标签页后，`mvn -B clean verify` 退出码 0（surefire 252，failsafe 2）。修复 storage 配置后，`JobApplyApplicationTest` 和 `LoginHandoffControllerTest` 都通过（9 个）。真实 Chrome 冒烟 `BUILD SUCCESS`。改提示文字之后的全量 `mvn -B clean verify` 由用户转到后台执行，结果见下一条。
- 偏差：冒烟时代理结束了自己启动的 MCP 子进程，用来模拟崩溃（用户说「你可能要重新启动一下 mcp 然后测试」）。没有提交 git。

## 2026-10-22 · C003 冒烟后的全量回归

- 日期：2026-10-22
- 指令：（接上一条）改完断开提示的文字后跑全量回归。
- 结果：没有新的改动。
- 验证：在 `backend/` 用 JDK 25 执行 `mvn -B clean verify`，退出码 0；surefire Tests run: 253（加了 `JobApplyApplicationTest` 的根目录断言），failsafe Tests run: 2，Failures / Errors / Skipped 均为 0。28 次 MCP 启动全部是假服务端，没有调用 npx。
- 偏差：无。

## 2026-10-22 · 远程调试模式写进 master；清理

- 日期：2026-10-22
- 指令：「允许，然后 clean，update 文档」。
- 结果：
  - workflow.md「Chrome MCP」新增「两种连接方式」对照表（扩展模式是默认，远程调试模式要打开 `chrome://inspect` 的开关；确认次数、能看到哪些标签页、风险各不相同）；「登录状态」改为两种模式下 Agent 都只操作自己新开的标签页。C003 和 `dev-plan.md` 同步。
  - 清理：执行 `mvn clean`，删掉了 `backend/target`（包括冒烟时建的 `target/smoke-*` storage）；删掉会话临时目录里的小样和下载的 npm 包。确认没有残留的 MCP 进程，8765 端口也没有在监听。npx 缓存（`~/.npm/_npx`）保留，之后冒烟还要用。用户 Chrome 里系统新开的 3 个 OPPO 标签页要用户自己关（白名单里没有关闭标签页的工具）。
- 验证：未运行测试（本步只改文档、做清理）。最近一次全量回归见前一条。
- 偏差：「允许」理解为回答「远程调试模式要不要写进 master」。C003 仍然等用户明确验收，没有标为完成。

## 2026-10-22 · C003 验收；C004 要点

- 日期：2026-10-22
- 指令：用户同意对设计的解释，验收 C003（现在这版，带测试脚本）。C004 把「是否已登录」「登录的是不是正确的账号」交给模型判断，关键词识别只作提示；很多网站登录后不跳转，只是右上角多出头像或名字。一个 session 只做一份 Coding Plan，所以本次只在文档里写下 C004 要注意和更新的东西。
- 结果：C003、`dev-plan.md`、`plan/README.md`、README 的状态改为完成。workflow.md「登录状态」新增「登录与身份由模型判断」（从里程碑第 3 段起），并写明登录后不跳转的情况。新增 `plan/coding/02-core-agent/C004-harness-guard.md`（状态：要点），写了已说定的方向（图要粗、循环交给模型；系统只管模型不能绕过的东西；登录判断交给模型；上传走 `browser_file_upload`）、起草时要处理的事、C003 留下的已知问题。`dev-plan.md` 加了索引行，并重写了停靠点。没有写代码。
- 验证：未运行（本步只改文档）。最近一次全量回归是 surefire 253 / failsafe 2 全部通过。
- 偏差：无。没有提交 git。

## 2026-10-22 · 清理 Playwright MCP 写出的文件

- 日期：2026-10-22
- 指令：（接「clean」）
- 结果：冒烟后 `git status` 里出现了未跟踪的 `backend/.playwright-mcp/`：11 个文件，4 个 `page-*.yml`（其中 2 个是空的）和 7 个 `console-*.log`，是 Playwright MCP 在它的工作目录里自动写出的。代理只列了文件名和大小，没有打开内容，然后删掉了整个目录。在仓库 `.gitignore` 里加了 `.playwright-mcp/`。读 0.0.83 源码确认了原因（除 `browser_snapshot` 之外的工具默认会把附带的快照写成文件；console 消息也会写成文件）。根本的修法记进了 C004 要点「C003 留下的已知问题」第一条。
- 验证：`git status` 里不再出现这个目录。没有运行测试（代码没有改动）。
- 偏差：这是 C003 验收后才发现的问题。按「一个 session 一份 Coding Plan」的约定，本次只做了止损（删除文件、加忽略规则），修法留给 C004。

## 2026-10-22 · C003 评审修复（3 条）

- 日期：2026-10-22
- 指令：评审提出 3 条问题（P1：重启后跨 Job 读取页面；P2：身份确认绕过了暂停状态；P2：override 缺少审计事件），用户要求现在就修。
- 结果：先把范围写进 C003「追加：评审修复」，再动代码。标签页记住属于哪个 Job，`check_page` 遇到不属于本 Job 的标签页就重新新开；`signal()` 遇到另一个 Job 正在等待、或本 Job 已经不在 preparing / needs_login 时返回 409，不写任何东西；`finish` 在写 `sites.md` 之前再检查一次；新增 `login_override` 事件类型，用了 override 就记一条。
- 验证：修复前，3 个新的复现测试加上补了断言的原有 override 测试共 4 个，按预期失败。修复后在 `backend/` 用 JDK 25 执行 `mvn -B clean verify`，退出码 0；surefire Tests run: 256，failsafe Tests run: 2，Failures / Errors / Skipped 均为 0；没有调用 npx。没有在真实 Chrome 上重新冒烟。
- 偏差：对 C002 的领域代码做了一处小的扩展（`JobEvent.Type` 新增 `login_override`，`JobLedger` 新增 `recordLoginOverride`）。没有提交 git。

## 2026-10-22 · C004 起草

- 日期：2026-10-22
- 指令：开始 C004；先输出 plan，由用户确认和修正。
- 结果：把 `plan/coding/02-core-agent/C004-harness-guard.md` 从要点扩写成完整草案（目标、不做、依赖、要改的文件、关键规则、7 个切片、测试与夹具、手动冒烟、验收、回写 Master 5 项、待确认 9 项）。起草前只读查看了本机 npx 缓存里的 `@playwright/mcp` 0.0.83（README 与 `playwright-core` 源码）和 `~/.m2` 里的 Spring AI 2.0.1（javap），核实的事实写在草案「起草时核实的事实」。`dev-plan.md`、`plan/README.md` 的索引行改为「草案」。没有写代码。
- 验证：未运行（本步只改文档）。
- 偏差：无。没有提交 git。

## 2026-10-22 · C004 第一轮答复

- 日期：2026-10-22
- 指令：用户逐项回答了 C004 的 9 项「待确认」。
- 结果：答复记进 C004「用户答复（第一轮）」。已定的是第 1、4、9 项，以及第 7 项的方向；第 2 项扩大了范围（模型接入加设置页）；第 3、5、6、8 项还在讨论中。没有写代码。
- 验证：未运行（本步只改文档）。
- 偏差：无。

## 2026-10-22 · C004 第二轮答复

- 日期：2026-10-22
- 指令：用户回答了第 3、5、6、7、8、9 项。
- 结果：答复记进 C004「用户答复（第二轮）」。第 3、5 项已定；第 6、7 项还在讨论范围；第 9 项「测试时直接调用 LLM」和 `dev-limit.md` 有冲突，等用户确认。没有写代码。
- 验证：未运行（本步只改文档）。
- 偏差：无。

## 2026-10-22 · 边界：允许在监控下运行真实 LLM 测试

- 日期：2026-10-22
- 指令：在用户监控下可以运行真实 LLM 测试；程序运行时读取 `.env`、按占位符 `${…}` 把密钥填进请求，是软件的正常流程，不算泄露。
- 结果：`dev-limit.md`「范围边界」新增「真实 LLM 测试」一条。原来的「代理不读取、不修改秘密文件」不变：代理自己不打开、不打印、不修改 `.env`，输出里不能出现密钥值。C004「用户答复（第二轮）」第 9 项改为已定。
- 验证：未运行（本步只改文档）。
- 偏差：无。

## 2026-10-22 · C004 第三轮答复

- 日期：2026-10-22
- 指令：第 7 项同意；前端选 React + Vite；第 6 项追问由谁来演进 skill。
- 结果：记进 C004「用户答复（第三轮）」。没有写代码。
- 验证：未运行（本步只改文档）。
- 偏差：无。

## 2026-10-22 · C004 拆分为 A / B

- 日期：2026-10-22
- 指令：同意 skill 的分工建议；把 C004 拆成 A、B 两份；不写代码，计划写好就结束。
- 结果：`C004-harness-guard.md` 改成总表（拆分方式、两份共用的事实、9 项已定决定、回写 Master 9 项）；新增 `C004-A-model-guard.md`（模型接入、资料原件、设置页、守卫；待确认 7 项）和 `C004-B-harness-review.md`（Harness 循环、skill 体系、申请线程、关卡 B、过程日志、申请面板；待确认 6 项）。起草时查了 Maven Central 和 npm 的版本（Spring AI 模块 2.0.1、Tika 4.1.0、React 19.3.1、Vite 8.3.4 等），写在总表里。`dev-plan.md`、`plan/README.md` 的索引行已更新。Master 没有修改（回写要等用户确认）。没有写代码。
- 验证：未运行（本步只改文档）。
- 偏差：无。没有提交 git。

## 2026-10-22 · C004-A / B 确认，回写 Master

- 日期：2026-10-22
- 指令：「都同意，只更新 plan 和其他文件，不 coding」。
- 结果：
  - Master 按 C004 总表的 9 项回写：workflow.md（主流程「用户登录」「上传与解析」两行；流程优化的「改坏安全规则」「以偏概全」两行，新增「谁来改什么」；提交保护第 2、3 条改为硬边界加软边界，分层说明，Spring AI 2.0.1 的实现方式；Chrome MCP 默认启动参数和 roots；「待用户决定」里旧的允许名单一条划掉；顺带改了「数据源登记」的旧路径）；memory.md（storage 目录树：`.gitignore`、`assets.md`、`run-log.jsonl`、`playbook/` 新结构、`cache/`；「Memory 写入」第 4 条；字符预算一条）；ui.md（占位符 `${…}`，运行时读写 `.env`）；architecture.md（`api` 的 Origin 允许名单，`frontend/` 的 React + Vite）；base.md（模型供应商改为已定）。
  - C004 总表、C004-A、C004-B 的状态改为已确认；`dev-plan.md`、`plan/README.md` 的索引行和停靠点已更新。
- 验证：未运行（本步只改文档）。改完后用 grep 查过，Master 里没有残留的 `api_key_env`、`general.md`、`sites/<site>` 旧写法。
- 偏差：C004-B「待确认」第 6 项（真实公司冒烟用哪个 Project 的资料）仍然留到冒烟前由用户决定。没有提交 git。

## 2026-10-22 · C004-A 施工

- 日期：2026-10-22
- 指令：执行 C004-A 的开发工作。
- 结果：按 7 个切片做完。小样核实（会话临时目录）：Spring AI 2.0.1 直接调用 `ChatModel` 时工具调用原样返回、不执行；OpenAI 格式的 Base URL 带 `/v1`，Anthropic 格式不带；两个 SDK 在 Key / Base URL 为 null 时会读进程环境变量，所以一律显式传值。守卫（`SnapshotIndex`、`SubmitWords`、`SubmitGuard`、`FieldClassGuard`、重写的 `BrowserToolPolicy`）；MCP 启动参数、roots、输出目录清理、`storage/.gitignore` 追加 `cache/`；`EnvFile`、`ModelSettingsFile`、`ModelPresets`（Base URL 按官方文档核对）、`ModelFactory`、`ModelRegistry`、`ModelCatalogClient`、`ConnectionTester`；`AssetRegistry`、`AssetAnalyzer`（Tika 4.1.0）；`ModelSettingsController`、`AssetController`、Origin 允许名单、20MB 上限；`frontend/`（React 19.3.1 + Vite 8.3.4 + TypeScript 7.0.2）的两页。细节和偏差写在 C004-A「实际结果」「偏差」。
- 验证：`mvn -B clean verify` 退出码 0，surefire 486、failsafe 2，Failures / Errors / Skipped 均为 0；自动测试没有访问外部网络、没有调用 npx 和真实模型；构建日志里没有测试 Key。`npm run build` 通过。代理在会话临时目录的 storage 上把后端和 Vite 开发服务器串起来，用 curl 走了一遍主要接口（结果见 C004-A「实际结果」）。用户的手动检查：未运行。`ModelConnectionManualCheck`（真实 LLM）：未运行。
- 偏差：`tika-parsers-standard-package` 要写 `<type>pom</type>`；计划外加了 `ModelSettingsService`、`SettingsException`、`SettingsConfiguration` 和测试辅助类 `FakeModelServer`；白名单和词表比计划更严的几处，见 C004-A「偏差」。没有读取或修改真实的 `storage/`；没有提交 git。

## 2026-10-22 · 资料导入与画像：改计划

- 日期：2026-10-22
- 指令：用户问基础画像怎么构建；给出 `~/Desktop/remote_drop` 作为分层参考；决定只读 GitHub 公开仓库，页面上传和本地目录都支持；保留四层，但只有经历、技能、项目成果、量化指标走证据链，基础信息用户确认即可；中英双语共用一份结构化事实；改计划，在 C004-B 之前开发。
- 结果：
  - 只读查看了 `remote_drop/personal_portfolio`（content 模型、ARCHITECTURE、DECISIONS、AGENTS 的证据规则，`profile.yaml` / `meta.yaml` 只看了键名）和 `remote_drop/ApplyOnAi`（`PROFILE.md` 只看了标题结构）。没有读 `.env*`、压缩包和 `macair`；没有把个人内容写进任何文件。
  - Master：memory.md 新增「资料导入与画像」（四层、证据分级、中英双语、来源与扫描规则、流程），storage 目录树加了 `projects/`、`profile.md`、`glossary.md`、`ledger.md`、`evidence/`、`sources.md`、`import-runs/`，「事实条目格式」加 `zh` / `en`、`claim`，「新建 Project」第 3 步指向新流程，「待用户决定」记下第 6 项。workflow.md 里程碑第 3 段写明前置。
  - 新增 C005（待确认 6 项）；C004-B 的前置加上 C005；`dev-plan.md`、`plan/README.md` 的索引行和停靠点已更新；`dev-limit.md`「信息边界」加了 `remote_drop` 一条。
- 验证：未运行（本步只改文档）。
- 偏差：Master 的改动按用户这次给出的决定写；起草时补的细节（键前缀划分、默认用途、读取范围和上限、画像小节、是否拆分）都放在 C005「待确认」，等用户确认。没有写代码，没有提交 git。

## 2026-10-22 · 资料导入的计划改名为 C004-A-1

- 日期：2026-10-22
- 指令：上一条里的 C005 应该叫 C004A-1；现在只改文档，不开发。
- 结果：`plan/coding/C005-profile-import.md` 改名为 `C004-A-1-profile-import.md`；memory.md、workflow.md、C004-B、`dev-plan.md`、`plan/README.md`、`dev-limit.md` 里的引用一起改了；拆分建议里的两段改叫 C004-A-1 / C004-A-2；`plan/README.md` 的命名约定补了一句。上一条日志和 `dev-recoding.md` 里的「C005」是当时的记录，没有改写。
- 验证：未运行（本步只改文档）。改完后用 grep 查过，`plan/` 和索引文件里没有残留的 C005 引用。
- 偏差：无。没有写代码，没有提交 git。

## 2026-10-22 · C004-A-1 拆分并确认

- 日期：2026-10-22
- 指令：同意拆分，其他的也同意；只改文档，不开发。
- 结果：C004-A-1 拆成两份。`C004-A-1-profile-import.md`：第 1–4 片（事实格式与各文件的校验、按语言取值、来源扫描、GitHub 读取、估算）加「来源」页签，不调用模型。新增 `C004-A-2-profile-review.md`：整理、确认写入、译文起草、画像、「整理与确认」「事实与画像」两个页签、真实 LLM 测试入口。两份的状态都改为「已确认，待施工」，6 项写进各自的「已确认」：GitHub 接口在用户监控下用用户指定的公开仓库真实调用一次；手动检查的材料到时由用户当次决定（C004-A-1 的手动检查不调用模型）。memory.md「待用户决定」第 6 项、workflow.md 里程碑第 3 段、C004-B 的前置、`dev-plan.md`、`plan/README.md`、`dev-limit.md` 的引用已同步；顺带改正了 `dev-plan.md` 停靠点里上次改名留下的「C004-A-1-A / C004-A-1-B」。
- 验证：未运行（本步只改文档）。用 grep 查过，没有残留「待确认」和错误的编号。
- 偏差：C004-A-1 多了一个计划里没写的类 `ImportEstimator`（估算单独成类，C004-A-2 的「开始整理」直接用它的结果）；导入记录 `ImportRunStore` 放在 C004-A-1 建好。没有写代码，没有提交 git。

## 2026-10-22 · 修订求职表达与资料使用规则

- 日期：2026-10-22
- 指令：删除或修改强制数据可验证、事实与包装分离及相关限制，按用户需求优化个人求职表达。
- 结果：base.md 新增求职表达原则；memory.md 与 C004-A-1、C004-A-2 同步改为用户确认优先，来源、测试条件和论断引用可选，取消数字直接写入和团队贡献措辞拦截；本人确认的指标可用于简历和填表；同步计划中的测试及验收标准。dev-plan.md 更新对应索引，dev-recoding.md 标明历史取舍已被取代并记录本次变化。未修改代码。
- 验证：检索开发与设计 Markdown 文档中的关联规则并逐项对照；本次改动的差异检查通过。应用构建与测试未运行（仅文档变更）。
- 偏差：当前仓库未出现用户引用的两条原句；本次修改对应含义的规则及其测试、验收要求。

## 2026-10-22 · C004-A 手动检查：修复「测试」用错模型名

- 日期：2026-10-22
- 指令：用户手动检查时，在自己的 OpenAI 兼容网关上「测试」得到 404，指出是我们的问题；同意修改。
- 结果：带工具的单次请求选项没有写模型名，SDK 填了默认值（OpenAI 格式 `gpt-5-mini`，Anthropic 格式 `claude-haiku-4-5`），盖掉了选中的模型。`ModelFactory.withTools` 改为接收端点并显式写模型名；`ModelFactoryTest`、`ConnectionTesterTest` 加了请求体模型名的断言。排查时只读看过用户的 `~/Desktop/AI_website/Google_AI`（README、`src/` 的请求转换部分），没有读 `.env`，没有修改那个项目；之后用本机假服务抓包确认了原因。细节写在 C004-A「实际结果」。
- 验证：新断言先在旧代码上跑，确认失败；修复后 `mvn -B clean verify` 退出码 0，surefire 486、failsafe 2；构建日志里没有测试 Key。抓包复核请求体模型名正确。用户重新点「测试」：未运行（等用户重启后端后操作）。
- 偏差：排查中我先误判用户用的是 `Google_AI` 那个本地代理，后来按用户贴出的网关模型表改正。没有提交 git。

## 2026-10-22 · C004-A 手动检查：修复资料说明的提示词，手动检查做完

- 日期：2026-10-22
- 指令：用户问自动说明为什么写成「2025 版」，要求修改；反馈其余手动检查项都通过。
- 结果：`AssetAnalyzer` 的提示词抽成常量 `SYSTEM_PROMPT`，示例里不再放具体年份，写明只有原文明确写了版本号或更新日期才写、不从经历日期推断；`AssetAnalyzerTest` 加了两条断言。C004-A「实际结果」记下手动检查的过程和两个缺陷，状态改为「手动检查已做完，等用户确认验收」；`dev-plan.md`、`plan/README.md` 同步。代理没有看用户的简历内容。
- 验证：`mvn -B clean verify` 退出码 0，surefire 488、failsafe 2。用户的手动检查：已做完（用户反馈）。`ModelConnectionManualCheck`：未运行（用户还没答复是否同意）。
- 偏差：已经登记的那份简历不会自动重新分析，说明以用户手改的为准。没有提交 git。

## 2026-10-22 · C004-A 验收

- 日期：2026-10-22
- 指令：用户说明之前已经告诉过所有测试都通过，不要反复确认。
- 结果：按用户验收处理。C004-A 状态改为「完成」；`dev-plan.md`、`plan/README.md` 同步；C004-A-1 可以施工。`ModelConnectionManualCheck` 不再单独运行（用户已在页面上用真实模型测过连接），记为未运行。
- 验证：未运行（本步只改文档）。
- 偏差：无。没有提交 git。


## 2026-10-22 · C004-A-1 施工

- 日期：2026-10-22
- 指令：「开始做 C004-A-1」。
- 结果：按 5 个切片做完。小样核实（会话临时目录）：JGit 只读打开仓库、空仓库先判断 HEAD、标题取第一行；GitHub REST 按官方文档写成假服务端。代码：双语事实格式和 `facts.md` 兼容；论断台账、项目卡、来源登记、清单卡、术语表、画像、导入记录的读写与校验；`ProfileResolver` 按语言取值（含项目卡字段，按 `base_pin` 取版本）；秘密文件规则、本地目录扫描、GitHub 公开仓库读取、估算；`/api/sources`、`/api/import-runs`；「资料」页的「来源」页签。细节写在 C004-A-1「实际结果」「偏差」。
- 验证：`mvn -B clean verify` 退出码 0，surefire 535、failsafe 2，Failures / Errors / Skipped 均为 0；自动测试没有访问外部网络、没有调用模型、没有读测试目录以外的本地目录；秘密文件在测试里设成不可读，扫描照常成功。`npm run build` 通过。代理在会话临时目录的 storage 上用 curl 登记并估算了一个虚构目录，结果符合预期。GitHub 真实调用：未运行（等用户指定公开仓库）。用户的手动检查：未运行。
- 偏差：计划外加了 `Language`、`SourceScan`、`ImportRun`、`MarkdownTable`、`BilingualSections`、`ImportConfiguration` 和测试辅助类 `FakeGitHubServer`；几处比计划更严的规则和格式细节见 C004-A-1「偏差」。没有读取或修改真实的 `storage/`、`storage/example.data/`、`~/Desktop/remote_drop/`；没有提交 git。

## 2026-10-22 · C004-A-1 两处偏差确认

- 日期：2026-10-22
- 指令：用户问过「storage 里面的目录不能登记」和「估算 GitHub 只用 3 次调用」的原因后，表示没有问题，接下来先找人审核代码，再手动测试。
- 结果：两处按现状保留，写进 C004-A-1「已确认」第 7 项；C004-A-1 和 `dev-plan.md` 的状态改为等代码审核和手动检查。
- 验证：未运行（本步只改文档）。
- 偏差：无。没有提交 git。

## 2026-10-22 · 代码审核的 4 个问题修复

- 日期：2026-10-22
- 指令：用户贴来代码审核的结果：C004-A 两个（原件分析把 C 级内容发给模型；修改端点后旧的工具调用测试仍有效），C004-A-1 两个（覆盖项目卡数值后显示值仍是旧数字；最后一个文件突破字符上限时不报超限）。
- 结果：四个都修了。`FieldClassGuard.withoutClassC` 在发给模型之前按 C 级词表和证件号码格式去掉整行，`AssetAnalyzer` 使用它；`ModelSettingsService` 在接口格式或 Base URL 变化时作废测试记录，提供方正被用作主模型时拒绝修改端点；`ProfileResolver` 只有取到的值来自项目卡时才按项目卡数值格式化；`LocalDirectoryScanner` 在计入当前文件时检查字符上限。细节写在 C004-A「代码审核修复」和 C004-A-1「实际结果」。
- 验证：5 个新用例先在修复前的代码上跑，全部失败；修复后 `mvn -B clean verify` 退出码 0，surefire 541、failsafe 2；构建日志里没有测试 Key。用户的手动检查：未运行。
- 偏差：第 2 个问题的修复比审核意见多了一步：主模型正在使用这个提供方时，拒绝修改端点（409）。没有提交 git。

## 2026-10-22 · C004-A-1 手动检查第 1 项：GitHub 真实调用

- 日期：2026-10-22
- 指令：用户指定公开仓库 `https://github.com/YigeWang1221/JobApply_agent`。
- 结果：在会话临时目录的 storage 上用打包后的后端登记（201，SHA 固定）并估算（读 README、两个依赖清单和文件列表，约 2.2 万字，模型 2 次，GitHub 已用 3 次、整理时还要 5 次）；curl 只读核对 `/languages`、`/contents`、`/commits` 的字段，和假服务端一致。细节写在 C004-A-1「手动检查」。
- 验证：真实网络调用，只读、未认证，没有打印文件内容。第 2 项（「来源」页登记本地目录）：未运行。
- 偏差：无。没有提交 git。

## 2026-10-22 · 放宽两处审核修复

- 日期：2026-10-22
- 指令：用户认为「主模型使用中拒绝修改端点（409）」过于严格，「按整行过滤 C 级词」太敏感；端点比较选「只看实际变化」。
- 结果：C 级过滤改为只去掉值（字段名命中时去掉冒号后的值、证件号码、「证件名 + 含数字号码」里的号码），普通文字不处理；去掉 409，端点只在真的变化时作废测试，写法差异不算。细节写在 C004-A「代码审核修复 · 按用户指令放宽」。
- 验证：`mvn -B clean verify` 退出码 0，surefire 541、failsafe 2；构建日志里没有测试 Key。
- 偏差：放宽以后，已经分配为主模型的提供方换了端点，会在没测过的新端点上继续运行，直到用户重新测试（按用户这次的决定）。没有提交 git。

## 2026-10-22 · C004-A-2 施工

- 日期：2026-10-22
- 指令：「进行 C004A-2 的开发」。
- 结果：按 5 个切片做完。小样核实（会话临时目录）：两种接口格式按 JSON Schema 返回的请求写法，单次选项从模型选项复制、模型名不丢。代码：清单卡（`SourceInventory`）、抽取（`CandidateExtractor`）、确定性的合并和对比（`CandidateMerger`）、后台整理和逐条决定（`ImportRunner`）、确认写入（`FactWriter`，每个决定提交一次）、译文起草（`TranslationDrafter`）、画像（`ProfileSummaryBuilder`）；接口 `start`、`decisions`、导入记录列表、`/api/profile`；「资料」页加「整理与确认」「事实与画像」两个页签。细节写在 C004-A-2「实际结果」「偏差」。
- 验证：`mvn -B clean verify` 退出码 0，surefire 584、failsafe 2，Failures / Errors / Skipped 均为 0；自动测试只连本机的假模型和假 GitHub，没有访问外部网络、没有调用真实模型；秘密文件的内容不在任何模型请求和 storage 文件里；构建日志里没有测试 Key。`npm run build` 通过。`ProfileImportManualCheck`：未运行。用户的手动检查：未运行。没有启动打包后的应用做冒烟（接口在 SpringBootTest 里用真实 HTTP 跑过）。
- 偏差：多个来源的合并改为不调用模型，`ImportEstimator` 去掉了那一次（C004-A-1 的估算口径随之改）；材料多时按 6 万字一批摘要；GitHub 整理时不重读文件列表；计划外的 `ImportCandidate`、`StructuredCall`、`ConflictsFile` 和 `GET /api/import-runs`；其余见 C004-A-2「偏差」。没有读取或修改真实的 `storage/`、`storage/example.data/`、`~/Desktop/remote_drop/`；没有提交 git。

## 2026-10-22 · C004-A-2 追加目录选择框，真实模型检查 GitHub 仓库

- 日期：2026-10-22
- 指令：用户要求用现有的模型接入测试 `https://github.com/YigeWang1221/JobApply_agent`；把「选择本地目录」（选择框，或者「文件来自于「path」」这种说法）加进 C004-A-2 并实现。
- 结果：C004-A-2 加「追加：选择本地目录」一节并实现（`DirectoryBrowser`、`GET /api/sources/browse`、从句子里取路径、「来源」页签的浏览面板）。`ProfileImportManualCheck` 支持 GitHub 仓库和「确认全部再生成画像」；对这个仓库跑了三次（临时 storage，跑完删除），整理、确认、起草译文都正常；第一次画像有三节是空的，加了空小节的提示。细节写在 C004-A-2「实际结果」。
- 验证：`mvn -B clean verify` 退出码 0，surefire 589、failsafe 2；构建日志里没有测试 Key。`npm run build` 通过。真实模型检查：已运行（代理运行，输出里没有 Key 和内容）。用户在页面上的检查：未运行。
- 偏差：画像空小节的原因没有确定，只加了提示。没有读 `.env`；没有提交 git。

## 2026-10-22 · 抽取与画像提示词重新放宽

- 日期：2026-10-22
- 指令：针对 C004-A-2 审阅中发现的表达规则回退，重新放宽提示词并修改相应 plan。
- 结果：CandidateExtractor 与 ProfileSummaryBuilder 均允许提出估算、量化口径、技能定位和措辞候选，由用户确认采用；缺少原文数字、出处或测试条件不阻止起草。同步 C004-A-2、base.md、memory.md 的规则，更新 dev-plan.md 索引；扩展 ImportRunnerTest 和 ProfileSummaryBuilderTest，检查本机假模型实际收到的提示词。未处理审阅中的其他缺陷。
- 验证：临时副本中使用 JDK 25 执行 `mvn -o -B clean verify`，退出码 0，surefire 589、failsafe 2，Failures / Errors / Skipped 均为 0。相关文件差异检查通过。真实模型检查、前端构建未运行（本次未改前端）。临时副本已删除。
- 偏差：无。未提交 git。

## 2026-10-22 · C004-A-2 代码审核的 4 个问题修复

- 日期：2026-10-22
- 指令：用户贴来代码审核结果（直接改项目卡绕过 C 级检查；切换导入记录可能串用草稿；「相同」判断漏掉技术栈、时间和别的来源的写法；确认失败后台账已改）并要求修改。
- 结果：四个都修了，细节写在 C004-A-2「实际结果 · 代码审核修复」。
- 验证：新增 5 个后端用例，在修复前的代码上都失败；修复后 `mvn -B clean verify` 退出码 0，surefire 594、failsafe 2；`npm run build` 通过。第 2 个问题只改了组件身份，页面上的复现：未运行。
- 偏差：第 4 个问题除了「先检查再写」，还加了失败时恢复文件（审核意见两种做法二选一，这里都做了）。没有提交 git。


## 2026-10-23 · C004-B 施工前核对

- 日期：2026-10-23
- 指令：对照已实现的 C004-A、A-1、A-2 查看 C004-B 是否需要改动；用户确认 9 项和可选项都按建议，先只更新文档。
- 结果：C004-B 加「施工前核对」一节和「关键规则 · 资料与语言」；改「要改的文件」（`FieldIntent`、`SnapshotRedactor`、`ModelFactory.withTools(ChatModel, …)`）、「隐私」、「不做」、夹具表（`verification`、`c-class` 补情况，新增 `bilingual`）、`HarnessLoopTest`、实现步骤第 2 片。C004 总表决定 3 改为「B 写夹具」。`dev-plan.md` 索引行更新。
- 验证：未运行（本步只改文档）。
- 偏差：无。没有提交 git。

## 2026-10-23 · C004-B 施工

- 日期：2026-10-23
- 指令：「开始开发，multi-agent 模式」；页面语言阈值定为 40% 中文字符；真实公司冒烟用哪个 Project，等真实 Project 的流程走完再定。
- 结果：7 个切片做完（切片 2、3、6 和前端面板由并行代理在 worktree 里做，主代理合并；4、5、7 依次做）。细节写在 C004-B「实际结果」「偏差」。
- 验证：`mvn -B clean verify` 退出码 0，surefire 715、failsafe 3，Failures / Errors / Skipped 均为 0；测试假 Key 在构建日志和测试报告里出现 0 次；自动测试没有访问外部网络、没有调用 npx、没有连真实浏览器、没有调用真实模型；夹具里的提交类按钮没有被点到。`npm run build` 通过。`ApplicationLlmManualCheck`：未运行。`ApplicationManualSmoke`（真实 Chrome）：未运行。真实公司冒烟：未运行。用户的手动检查：未运行。
- 偏差：见 C004-B「偏差」（17 条）；其中 `materials` 的形状和 memory.md 不一致，等用户确认后再回写 Master。没有读取或修改真实的 `storage/`、`storage/example.data/`；没有读 `.env`；没有提交 git。

## 2026-10-23 · 重构方向、代码恢复、交接

- 日期：2026-10-23
- 指令：用户指出 C004-B 的架构是「Java 驱动的脚本加 LLM」，要参考 Hermes、Deep Agents、OpenMAIC 按 Agent 方式重构；界面要做真的；不要遇到问题就写测试（写进 `AGENTS.md`）；资料层要用用户写好的原文。用户决定 Agent 运行时用 Java，代码恢复到最近一次全部通过的构建，重构和研究计划写进 master plan，交接给下一个 Claude session。
- 结果：`AGENTS.md` 加「Agent 开发原则」；新增 `plan/reference/research-agent-design.md`、`plan/master/agent-runtime.md`（交接文档：问题、目标架构方向、资料层、界面、研究计划、清理、顺序、代码现状、待确认）；草案 C004-C 作废。浏览器连接改成属于应用（连接失败后不自动重连、`reconnected` 在连接正常时沿用、连不上 Chrome 算断开）并保留。之后的改动（多标签页、草案 C004-C 的一半）已撤回：被删的 4 个类从当时的代理记录里还原。
- 验证：恢复后 `mvn -B clean verify` 退出码 0，surefire 718、failsafe 3。真实模型检查（`ApplicationLlmManualCheck`）：13 个夹具都停在该停的地方，构建日志里没有 Key。真实 Chrome 冒烟：`multi-page`、`submit-labels`、`verification` 通过，`file-slots` 未运行。
- 偏差：无。没有提交 git。

## 2026-10-23 · 研究三个 Agent 项目、新设计、处理清单

- 日期：2026-10-23
- 指令：只做 agent-runtime.md 第 2、3 节和第 4 节的清单：读 Hermes、Deep Agents、OpenMAIC 源码，写研究结论、新设计和旧代码处理清单，等用户确认方向后再写代码；不写测试，不加额外守卫和确认。
- 结果：`plan/reference/research-agent-design.md` 加「源码阅读（2026-10-23）」（每个项目 7 个问题 + 对照表，推翻了原表的 3 处）；`plan/master/agent-runtime.md` 第 7 节写入新设计（循环、工具、提示词、skill、问用户、代码里留的线、处理清单、施工顺序）。Deep Agents、OpenMAIC 浅克隆到会话临时目录，只读。
- 验证：未运行（本步只改文档，没有改代码）。
- 偏差：无。`dev-limit.md` 里 LangGraph4j 那条没改，等用户同意。没有提交 git。

## 2026-10-23 · 用户确认新设计

- 指令：用户同意第 7 节的方向、不再用 LangGraph4j、去掉软边界按钮确认、7.7 删除清单。
- 结果：`dev-limit.md`「范围边界」里 LangGraph4j 那条改成 Agent 循环 + 对话存盘；agent-runtime.md 第 7 节标为已确认。
- 验证：未运行（只改文档）。

## 2026-10-23 · C004-D Agent 循环施工

- 指令：用户同意第 7 节方向后，做 7.8 第 1 步：删旧流程，接上新循环和工具，在夹具上用真实模型跑。
- 结果：见 `plan/coding/02-core-agent/C004-D-agent-loop.md`「实际结果」。
- 验证：`mvn -B clean verify` 退出码 0（surefire 624、failsafe 1）；`npm run build` 通过。`ApplicationLlmManualCheck` 13 个夹具：12 个 ready、`verification` 停在等用户完成滑块；提交类按钮没被点到。真实 Chrome 冒烟：未运行。
- 偏差：`submitted-page` 里伪装成「下一步」的按钮被模型点了，页面显示投递成功；`dialog` 里模型说已翻页但实际没有。都等用户决定。没有提交 git。

## 2026-10-23 · 方案 A：拿不准的「下一步」先问用户

- 指令：用户选 A。
- 结果：`SubmitGuard` 新增 `AskUser`（看不出第几步、最后一步、同页有提交按钮时）；用户回复后同一个按钮放行；提示词加一条说明。细节见 C004-D「实际结果」。
- 验证：`mvn -B clean verify` 退出码 0（surefire 623、failsafe 1）；`ApplicationLlmManualCheck` 重跑 3 个夹具都先问后点或直接翻页，停在提交前或投递成功后请用户核对。真实 Chrome：未运行。
- 偏差：无。没有提交 git。

## 2026-10-23 · storage 去掉版本控制、清理旧 worktree

- 指令：memory / storage 不再用 git、不做任何版本控制；有的 git 删掉；旧的多代理 worktree 有用的合并、没用的删掉；然后在当前分支提交一次。
- 结果：删 `StorageGit` 和所有自动提交调用（21 个源文件）、`StorageInitializer` 不再建 `.git` 和 `.gitignore`、`StorageLayout` 去掉 git 路径；`base_pin` 只能是 `live`。jgit 依赖保留：只用于资料导入时只读扫描用户自己代码仓库的提交摘要（`LocalDirectoryScanner`），不是 storage 的版本控制。plan 里 memory.md、workflow.md、architecture.md、ui.md 同步改。`storage/` 下原本就没有 `.git`。5 个 `.claude/worktrees/agent-*`（C004-B 并行代理留下的）逐个比对：缺的文件都是已删的旧图流程，不同的文件主目录都是更新的版本，没有要合并的内容，已删除 worktree 和对应分支；主仓库 `.gitignore` 加 `.claude/`。
- 验证：`mvn -B clean verify` 退出码 0（surefire 611、failsafe 1）；`npm run build` 通过。

## 2026-10-23 · C005 调研 Agent + 界面（两个代理并行）

- 指令：同时开发 C005 界面和调研 Agent 两份计划，用多个代理并行。
- 结果：先定前后端共用的接口形状，再由两个代理分别改 `backend/`、`frontend/`。后端：对话 Agent（`ResearchAgent`、`ResearchTools`、`SessionLoop`、`SessionRunner`、`WebFetch`）、Project / Session / Job 只读接口、`POST /api/projects`、候选「开始申请」先转为申请、每个 Job / Session 一个标签页、两个调研 skill。前端：侧栏、全局、Project 主页（候选池）、对话视图、申请工作台、设置；旧「申请」调试页删除。细节见两份 C005 计划的「实际结果」。
- 验证：`mvn -B clean verify` 退出码 0（surefire 611、failsafe 1）；`npm run build` 通过。后端代理在临时 storage 上启动应用调了一遍 Project / Session 接口，形状和约定一致；没配主模型时 Session 报「还没有配置主模型」。真实模型 + 真实搜索、官网核验、真实 Chrome、前端在浏览器里走一遍：未运行。
- 偏差：没有提交 git。

## 2026-10-23 · 去掉站点黑名单，搜索来源交给模型

- 指令：代码只保护真正的安全边界，LLM 尽量自主；能合规读到的公开招聘信息直接读；搜索工具和来源由模型选；不因为网址是 LinkedIn、Google 就拦。
- 结果：`WebUrlPolicy` 删掉站点黑名单，只留 http/https、疑似凭证的网址、内网/回环/云元数据；`WebSearch` 加 `available()` 和按名字选来源，`web_search` 加可选 `provider`（不写时仍是免费优先、自动换）；调研提示词和 broad-search skill 去掉「不许访问」的说法，改成模型自己选来源、碰到验证码或登录再请用户处理。workflow.md「安全检查」、C005 计划同步。
- 追加（用户同一轮指令）：不要固定黑名单，下载、付款这类倾向用提示词交给模型判断。调研提示词加「只读：不下载、不安装、不付款、不开会员；广告和无关跳转不点」，申请提示词加「下载和付款」一条（遇到报名费、要下载 App 时问用户）。代码里没有新增任何拦截。
- 验证：`mvn -B clean verify` 退出码 0（surefire 611、failsafe 1）。真实模型下的效果：未运行。没有提交 git。

## 2026-10-23 · 网址检查照 Hermes

- 指令：参考 Hermes，它怎么做就怎么做（黑名单默认关、用户自己配；SSRF 默认开；凭证防泄漏单独做；多个搜索来源）。
- 结果：新增 `config/WebProperties`（`jobapply.web.blocklist.enabled` 默认 false、`domains` 默认空）；`WebUrlPolicy.check(url, blocklist)` 按用户名单拦，子域名也算；`WebFetch.checkUrl` 带上名单，重定向后再查；调研 Agent 的 `web_fetch` 和浏览器导航都走它。SSRF 和带凭证网址照旧一直拦。`research-hermes.md` 里「增加禁止自动化站点名单」那一行是我当时的偏差，已更正；workflow.md 同步。
- 验证：`mvn -B clean verify` 退出码 0（surefire 611、failsafe 1）。没有提交 git。

## 2026-10-23 · 修评审发现的 5 个问题

- 指令：用户贴了一份评审结果（标签页串任务、URL 账号密码、初次连接失败反复重试、核验参数不对却已改状态、DuckDuckGo 摘要丢失）。
- 结果：
  1. `BrowserSession`：每个使用者记标签页序号和最近一次的网址；每次操作前列一次标签页核对（序号 + 网址对得上就用；对不上时按网址唯一匹配找回；标签页没变少时信序号；都不行就不操作、去掉这个使用者的标签页，下次运行重新打开）。当前标签页被关掉的情况也会发现。
  2. `WebUrlPolicy`：`user:password@host` 形式的网址拒绝。
  3. `BrowserSession.connect`：连接失败也记为 `needsUser`，之后不再自己去连。
  4. `SessionLoop.updateLead`：先校验 status、verified、official_url，全部通过再写。
  5. `WebSearch`：先找每条结果的标题，再在它和下一条标题之间找摘要（`a` / `div` / `td` 都认）。
- 验证：`mvn -B clean verify` 退出码 0（surefire 611、failsafe 1）；改了 `BrowserSessionActTest`、`PlaywrightMcpClientTest` 里的调用序列（每次操作前多一次 `browser_tabs`）。用编译后的类手动核对：账号密码网址被拒绝；连接器抛错时连续调用两次只连 1 次、`needsUser=true`；DuckDuckGo 样例 HTML 第 1 条取到摘要、第 2 条没有摘要时为空。第 1 条（多标签页关掉前面的一个）和第 4 条：未运行（假浏览器只有一个标签页；第 4 条要搭 storage）。没有提交 git。

## 2026-10-23 · C005 真实冒烟（用户执行）

- 用户报告：真实模型 + 真实搜索、真实 Chrome 已经跑过，没有问题；`mvn -B clean verify`（surefire 611、failsafe 1）、`npm run build` 通过。代理没有在场观察，细节（用了哪个模型、哪些站点）未记录。

## 2026-10-23 · 前期实机操作（用户执行）

- 用户报告：前期各段的实机操作（真实 Chrome 连接、申请填写等）都手动测试过，没有问题。代理没有在场观察，细节未记录。
- 计划：新增 C007-after-submit（提交后记已投递、3–5 天提醒查看、对话里反馈），workflow.md 里程碑第 8 段按用户指令改写。没有改代码。
- 决定（用户，2026-10-23）：C007 证据规则选 A，用户的确认算有效证据（`user-reported`），直接进入 `applied`。C007 计划、workflow.md「状态」已改。没有改代码。

## 2026-10-23 · C006-front + C006-agent 并行施工

- 指令：用户「multi-agent 开发 c006-front/agent」。两个代理并行：一个只改 `frontend/`，一个只改 `backend/`；接口约定照 C006-front「和 C006-agent 的接口约定」写死后再分工。
- 结果：细节见 [C006-front](plan/coding/02-core-agent/C006-front-chat-ui.md)、[C006-agent](plan/coding/02-core-agent/C006-agent-live-steps.md)「实际结果」。合并时把前端的「显示思考」开关去掉，改成设置页一段说明（后端核实 Spring AI 2.0.1 下 Anthropic 思考不能发回，`showThinking` 只存不生效）。
- 验证：`npm run build` 通过；`mvn -B clean verify` 退出码 0（surefire 611、failsafe 1）。浏览器里看界面、真实模型试思考 / 推送 / 重新生成：未运行。没有提交 git。

## 2026-10-23 · 用户测试：「Playwright Extension not found」

- 现象：申请开始时 `browser_tabs` 返回「Playwright Extension not found in …/Google/Chrome」，界面提示「确认 Chrome 里这个页面开着」。
- 根因：两个 Chrome 资料（Default、Profile 1）里都没有装 Playwright 扩展；Default 里只有 Claude 扩展（已停用），`@playwright/mcp --extension` 不认它。只看了扩展目录和 Preferences 里的扩展名，没读别的。
- 改动：`PlaywrightMcpClient`：结果里有「extension not found」时按连接不可用处理，提示安装扩展的链接和 `profile-dir-name`。`HarnessLoop`：接着做申请时原来的标签页读不到，系统在新标签页重新打开岗位链接，不再先问用户。
- 验证：`mvn -B clean verify` 退出码 0（surefire 611、failsafe 1）。装扩展后的真实 Chrome 冒烟：未运行，等用户。

## 2026-10-23 · 用户反馈：候选池没有折叠、不能改名

- 结果：候选池按公司折叠；Project、Session 支持改名（新增两个 PATCH 接口、`RenameTitle` 组件）。细节见 C006-front「追加」。
- 验证：`npm run build` 通过；`mvn -B clean verify` 退出码 0（surefire 611、failsafe 1）。浏览器里看：未运行。没有提交 git。

## 2026-10-23 · C006-fix（附件、工作台整屏）+ C007（提交以后）并行施工

- 指令：用户反馈工作台太小、不能附文件和图片（选定：图片发给模型、附件只放 Job / Session、两边都做）；随后「再开一个 agent 把 C007 做掉」。三个代理：C006-fix 前端、C006-fix 后端、C007（前后端）；C007 的工作台按钮和对话确认卡等 C006-fix 前端完成后再做。
- 编号更正：附件计划先误编成 C008，用户指出是 C006 的修补，改名为 `C006-fix-attachments.md`。
- 结果：细节见 C006-fix、C007「实际结果」。C007 没做：成功页快照（会碰申请的浏览器队列）、7 天 unknown 规则（代码里原本就没有）。另：侧栏加「资料与简历」直达入口，子标签「原件」改名「简历与原件」（用户找不到上传简历的入口）。
- 验证：合并后 `npm run build` 通过；`mvn -B clean verify` 退出码 0（surefire 611、failsafe 1）。新接口（上传 / 下载附件、submitted / not-submitted）没有测试调用；真实模型看图、附件上传到表单、确认投递、提醒：未运行。没有提交 git。
- 计划：新增 C008-resume-match（简历排名与修改建议），等用户确认。

## 2026-10-23 · 浏览器默认改为远程调试模式

- 指令：用户指出扩展模式「每次连接、重连都要点同意」违反不过度设计确认的原则（AGENTS.md「Agent 开发原则」）。
- 结果：`application.yml` 的 `jobapply.browser.mcp.args` 从 `--extension` 改为 `--cdp-endpoint chrome`；workflow.md「两种连接方式」标明默认是远程调试模式。扩展模式仍可通过配置切回。代码没改。
- 验证：未运行（要用户打开 Chrome 远程调试开关后重启后端实测）。
- 追加（同日，用户：「使用 MCP 不要用 Playwright 扩展，之前有问题的 plan 也做修正」）：
  - master：workflow.md「Chrome MCP」改成只用远程调试模式，扩展模式写明已作废及原因；「登录状态」「Web 工具」「为什么用 Playwright MCP」、里程碑第 2 段、首次引导同步；ui.md 首次引导、agent-runtime.md 第 5 节同步。C003 顶部加更正说明，施工记录保留。
  - 后端：去掉「没装扩展」的专门提示和商店链接；四处断线提示改成指向 `chrome://inspect/#remote-debugging` 的开关；`application.yml` 去掉只对扩展模式有用的 `profile-dir-name`；注释同步。
  - 验证：`mvn -B clean verify` 退出码 0（surefire 611、failsafe 1）。真实 Chrome 连接：未运行。

## 2026-10-23 · 用户反馈：岗位下线没有状态、搜索要进详情页、看不到改名

- 结果：见 [C005-fix](plan/coding/02-core-agent/C005-fix-posting-status.md)；改名入口改成一直可见（C006-front 追加）。示意页（https://claude.ai/artifact/4K6eE85i51GvrLUGz8cARD）存进 `plan/reference/ui-mockup.html`（虚构数据），照它改版的计划另写。
- 验证：`mvn -B clean verify` 退出码 0（surefire 611、failsafe 1）；`npm run build` 通过。浏览器里看、真实调研：未运行。

## 2026-10-23 · C006-fix 版式：照示意页改全局 / Project 页

- 指令：用户「拉起一个 subagent 直接进行 UI 重构」。一个代理施工，只改全局页、Project 页、侧栏说明和两处后端（`GET /api/jobs`、JobView 加 `events`）；对话页、工作台、chat/* 没动。细节见 [C006-fix 版式](plan/coding/02-core-agent/C006-fix-ui-mockup.md)「实际结果」。
- 核对：`ProjectController` 的路径和原来一致；改名、新建 Session、已下线 / 重新开放、「我看了」都还在。
- 验证：`npm run build` 通过；`mvn -B clean verify` 退出码 0（surefire 611、failsafe 1）。浏览器里和示意页对照：未运行。没有提交 git。

## 2026-10-23 · 4 个问题核实；改成结构方案

- 用户转来 4 个问题，逐条读代码核实，都属实。先做了补丁：用户能从任意准备状态登记提交（状态机按 Actor 放行，Agent 仍按边表）、申请线程结束时不覆盖用户定的状态、`/submitted` 先停线程；有附件时必填上传位置放行（后端）；重新生成按结果分成功 / 未生效。第 3 条的前端轮询没有做（用户否决）。`mvn -B clean verify` 退出码 0（surefire 611、failsafe 1）。
- 用户要求参考 OpenMAIC、Deep Agents 从结构上解决：只读源码后写了 [C006-fix 运行时](plan/coding/02-core-agent/C006-fix-runtime.md)，结论写进 research-agent-design.md「运行时」一节。等用户确认后施工；上面的补丁里，附件放行和重新生成的分类会被结构方案替换。
- 参考仓库按用户要求放到 `/Users/yigewang/Documents/Personal_learning_2026/`：OpenMAIC、deepagents、deep-agents-ui、langgraphjs（浅克隆）；AGENTS.md、dev-limit.md 登记为只读参考。

## 2026-10-23 · 只读审查之前的结构问题

- 指令：用户「看看之前是不是也有这样的结构性问题；先 plan 再 multi-agent 修改」。一个代理只读审查（没改文件），对照 OpenMAIC、deepagents、deep-agents-ui、langgraphjs、hermes-agent，列出 13 条和查过没问题的地方。
- 结果：JobLedger 多实例各锁各的（并发写丢更新）、工具结果状态从中文开头猜，这两条并进 C006-fix-runtime（追加给代理 B）。其余写成 [C006-fix 第二阶段](plan/coding/02-core-agent/C006-fix-runtime-2.md)；对话压缩、资料导入改成 Agent 记为待决定。第 2 条（软按钮）没照审查原建议「每次点击都批准」，改成交给模型判断，只在模型怀疑是最后一步时问用户（避免增加用户点击）。

## 2026-10-23 · C006-fix-runtime 施工完成（三个代理）

- A：应用级事件日志 `RuntimeEvents`（环形缓冲、纪元:序号、按 Session 过滤含它的 Job）、`GET /api/events`（补发、caught_up、reset、25 秒心跳、不关流），删掉 LiveFeed / EventStream 和旧推送地址；申请线程结束回报 Session（note，不唤醒模型）；读回对话时补中断的调用。B：用户事实优先（`advancePrep` 原子操作）、有副作用工具可重放（回执）、重新生成只留中性说明、上传位置一张清单（`attachment:<id>`，不拦必填）、全局写锁 `StorageLock.WRITES`、工具结果状态在产生处设好。C：前端共享 EventSource、自动重连、去掉 4–5 秒轮询、上传位置可选附件、必填位置只提示。细节见 C006-fix-runtime「实际结果」。
- 验证：合并后 `npm run build` 通过；`mvn -B clean verify` 退出码 0（surefire 611、failsafe 1）。B 有四处用 sed / python 做了精确替换（报告里写明，合并后核对过）。启动应用看推送、补发、重启后 reset，以及「先放着后说投了」「只附 PDF 交必填位置」「重新生成后不重复」：未运行。没有提交 git。

## 2026-10-23 · C006-fix 第二阶段第一批（D、E、F）

- 中断与重复：用户合上 MacBook 后，原会话没有退出，它的 D / E / F 和本会话恢复的 D / E / F 同时在改同一批文件；发现后先停本会话的 F、问原会话。原会话 17:54 退出后，本会话的 E、F 接着做，重读自己改过的文件，确认每件事只有一份实现。
- D：申请排队（去掉 `requireBrowserFree` 和两条 409，`queued` 状态、按顺序跑）；「在跑」只看内存（`LiveRuns`，Session / 申请 / 导入共用），重启后的申请显示「中断」；每次运行重新取模型。推迟：`job.md` 里 Agent 写的 prep_state 仍由 `advancePrep` 写（理由见计划）。
- E：软边界按钮交给模型判断（`final_step_suspected`；只在模型怀疑最后一步或页面有提交按钮 / 末步时问用户；批准只对这一页、这个 ref、这个按钮、一次；调研里软按钮放行，硬边界词照旧拦）；去掉 `askedButton`；标签页只按序号 + 网址认，模型有受限的 `tabs`（list / select），点击后新开的标签页自动交给这个 Job；`finish` 带 `page_shows_submitted`。workflow.md「提交保护」加注。改了 `SubmitGuardTest` 的软按钮用例（三条变五条），硬边界和最终提交的测试没动。
- F：读过的页面存进 Session 的 `pages/`，`add_lead` 按网址或 `source` 取快照；`due` / `bucket` / `canStart` 在服务端算一次（`JobBucket`），list_jobs 和前端都读它；必填问题只提示；导入页改用事件。
- 验证：所有代理停下后单独跑：`npm run build` 通过；`mvn -B clean verify` 连跑两次都退出码 0（surefire 614、failsafe 1）。之前 F 碰到的 `ImportRunControllerTest` 失败是几个代理同时在 `target/` 构建造成的，单独跑没有复现。真实 Chrome、真实模型：未运行。没有提交 git。

## 2026-10-23 · 回归审查与修正（H、I）

- 只读审查对照 182c7a6 找出 8 条回归（6 条确定），写进 C006-fix-runtime-2「回归审查后的修正」，H、I 两个代理先写施工细节和「必须保持不变」再改。
- H：用户已定下状态后 `reply` 返回 409、界面锁输入框，线程状态显示 `done`（只推导不落盘）；台账写入在锁外发事件，`RuntimeEvents` 改成入队 + 虚拟线程逐个发送，慢连接不再卡住写入；`list_jobs` 读实时线程状态；回报 Session 不报停止和暂停，页面像已投递时换说法；旧记录的「页面像已投递」退回按消息判断；排队中停止立即出队。
- I：同一标签页网址变了、还在同站点仍算这个岗位的（第三步认法）；接着做时只有一个候选就直接认回，不新开页；`open()` 先刷新所有人的序号再清旧记录。新增 `BrowserSessionTabsTest`（丢掉填了一半的标签页是实际损失）。
- 验证：两个代理停下后单独跑：`npm run build` 通过；`mvn -B clean verify` 退出码 0（surefire 616 = 614 + I 新增 2、failsafe 1）。I 报告里的 603 是复制代码时 H 改到一半所致，单独跑核对过。真实 Chrome、真实模型：未运行。没有提交 git。

## 2026-10-23 · 合并两个 Agent 循环（G，C006-fix-runtime-2 第 4 条）

- 新 `AgentLoop<R>`：停止、上限和总结、调模型、工具计时、观察者事件、最新页面，两个 Agent 共用；`HarnessLoop`、`SessionLoop` 各自只留工具、上下文和结束条件（`AgentLoop.Agent`）。新 `ToolArgs`（`parse` / `text`）。浏览器工具分发、守卫、断开策略留在各自的 Agent，差别写进类说明；调模型时停止的不同写成参数 `dropReplyWhenStopped`。
- 验证：scratchpad 里的临时新旧对照（假模型 + 假浏览器，14 个场景，发给模型的消息、事件、返回的 AgentRun、transcript、运行日志、报告全部记下）改前改后逐字节相同；`mvn -B clean verify` 退出码 0（surefire 616、failsafe 1）。测试没改。真实 Chrome、真实模型：未运行。没有提交 git。


## 2026-10-23 · 合并循环（G）与最终审查修正（J、K）

- G：两个 Agent 循环合成 `harness/runtime/AgentLoop`（+ `ToolArgs`），刻意保留的差异写成参数（停止时丢不丢回复、文案和上限、浏览器工具和守卫、断开策略）。用临时对照程序跑 14 个场景（申请 8、调研 6），改前改后逐字节一致；没有改测试。
- 最终只读审查找出 4 条，写进 C006-fix-runtime-2「最终审查后的修正」：
  - J：接着做时只有「这个岗位自己的记录 + 和岗位链接或上次网址同站点 + 唯一」才自动认回并快照，其余只列标题和网址给模型；标签页个数变少的那一轮只用「序号 + 网址」认，不再把同一 ATS 上别的申请的页认成自己的。`BrowserSessionTabsTest` 加 3 条（读到用户自己的页、在别的申请表上填答案都是实际损失）。
  - K：排队时停止用一次性票据，已被停止的任务开头就退出，不会照跑；每个订阅的待发队列超过 2000 条就断开，浏览器带 Last-Event-ID 重连补发。新增 `RuntimeEventsTest`（不靠 sleep）。
- 验证：所有代理停下后在工作区单独跑：`npm run build` 通过；`mvn -B clean verify` 退出码 0（surefire 620 = 616 + J 3 + K 1、failsafe 1）。真实 Chrome、真实模型：未运行。
- 暂存区：有一份中途的快照（15 个文件和工作区不一致，`AgentLoop`、`ToolArgs`、两个新测试没有暂存）。提交前要 `git add -A`。没有提交 git。

## 2026-10-23 · 收尾与交接（停止来回修）

- 用户指出：一下午都在审查、补安全、来回修，没有一次真实跑通申请；标签页规则本该靠提示词和 skill，却做成了代码里的硬约束，违反 AGENTS.md「Agent 开发原则」。已停掉 N（整表对齐）、O（find 清洗等小项），两者都没做完；停下后单独跑：`mvn -B clean verify` 退出码 0（surefire 625、failsafe 1），前端 `tsc -b` 通过。本会话不再改代码。
- 交接给下一个会话（按顺序）：
  1. **标签页规则改回提示词 / skill 约束**：「只操作自己打开的、自己点出来的、用户明确交给你的标签页；用户之前开的不碰」写进 ApplyAgent / ResearchAgent 提示词和 apply-form skill；代码里只保留系统自己需要的记账（谁开的哪个标签页、转交），去掉 C006-fix-browser 第 2 节和「审查后的结构修正」里为安全加的判断（逐条猜归属的拒绝、整表对齐的半成品、find 清洗）。代码硬线只留：不点最终提交、密钥不外泄。
  2. **真实跑通一次**：真实 Chrome（远程调试开关）+ 真实模型，打开岗位页 → 登录 → 填写 → 停在提交前 → 用户提交 → 记已投递；遇到什么修什么。
  3. 然后做 C008（简历排名）。
- 已知风险（只记下，不修，交用户决定）：C006-fix-browser 最后一次审查列出的 5 条标签页路径；对话压缩没做；资料导入还是固定流水线。
- 没有提交 git。暂存区是中途快照，提交前要 `git add -A`。

## 2026-10-23 · 标签页改回提示词约束（C006-fix-browser 第 3 节）

- 「只操作自己打开的、点击弹出的、用户明确交给你的标签页；用户之前开的不碰」写进 `ApplyAgent`、`ResearchAgent`、`browser_tabs` 工具说明、skill apply-form。`BrowserSession` 只留记账（正在用的页、记在名下的页、转交、点击弹出的新页）；`browser_tabs list` 列出所有标签页并标出「正在用」「你的」，`select` 能切到任何一个；认不出正在用的页时把原话交给模型。删掉规则 ③④、`shrank`、`homes`、`sameSite`、`claim`、`findResult` 等；`HarnessLoop.tabLost` 简化。细节见 [C006-fix 浏览器](plan/coding/02-core-agent/C006-fix-browser.md) 第 3 节。
- 测试：`BrowserSessionTabsTest` 8 → 5 条，`BrowserSessionActTest` 删 1 条（`find` 清洗），没有新增。
- 验证：`mvn -B -q clean verify` 退出码 0（surefire 621、failsafe 1）。前端没改，`npm run build` 未运行。真实 Chrome、真实模型：未运行。

## 2026-10-23 · C008 简历排名

- 对话 Agent 新工具 `list_resumes` / `read_resume` / `choose_resume`（`ResumeMatch`）；`job.md` 新字段 `resume_choice`，事件 `resume_chosen`；`start_application` 没有本次排名时提示先排（`skip_ranking` 放行并记下）；申请开场写「拟用简历」；`JobView.resumeChoice`；候选池和 Job 页的「开始申请」改为给 Session 发消息；Job 页右栏「简历排名」、头部和候选卡「拟用简历」、上传位置默认选中。细节见 C008「实际结果」。
- 验证：`mvn -B -q clean verify` 退出码 0（surefire 612，没有改测试）；`npm run build` 通过。真实模型、真实 Chrome：未运行。

## 2026-10-23 · 第一次真实跑通的修复（C006-fix-real-run 1–4、6、8；C008-fix）

- 申请 Agent 新控制工具 `read_resume(file)`：读「可上传的文件」里一份的文字（`ResumeMatch.fileText`，和对话 Agent 的 `read_resume` 共用，C 级的值已去掉）。`HarnessLoop.control` 分发；`Views.summary` 显示「文件 N」。
- `ApplicationRunner.start`：`resume_choice` 的原件在开始时放进上传区成为文件 1，开场写明直接上传、填经历前先 `read_resume`。`choose_resume` 加可选 `attachment`（Session 附件 → `AssetRegistry.register` 登记成新原件并选中，`ranking` 可省）；结果文字和工具说明改为「用户定了才调用」。
- 提示词：`ResearchAgent` 简历排名改为「排名 → 表和建议 → 问用哪份 → 用户确认后 choose_resume → start_application」；`ApplyAgent` 新增折叠区域、简历原文、经历逐条填全、原文措辞不翻译、拿不准用 ask_user、值的来源几条，上传改为已有合适文件直接上传、自动解析值逐个核对，「项目和经历：不改写、不扩写」改为「资料 / 简历原文优先，只裁剪，不编造」。skill apply-form 新增两节，resume-parse 加两条。
- 报告：`fact: resume` 的字段来源写「简历原文」（`FillReportWriter.RESUME_SOURCE`，用 `Recorded` 行）；`BrowserTools` 字段说明加 `resume`。
- 新计划 C009（敏感信息占位符），待用户确认，没有改代码和 `dev-limit.md`。
- 验证：`mvn -B -q clean verify` 退出码 0（surefire 621、failsafe 1，没有改测试）。前端没改，`npm run build` 未运行。真实 Chrome、真实模型：未运行。本机已有的预置 skill 不会被覆盖（PlaybookSeeder 只补缺）。

## 2026-10-23 · 上传不靠点按钮（C006-fix-real-run 第 10、13 条）

- 调研写进 `plan/reference/research-agent-design.md`「浏览器点击与上传控件」：点击超时是 Playwright 的可操作性检查（遮罩、透明层挡住命中检查），点击没发生；`browser_drop` 同样被挡；`setInputFiles` 不需要点击和文件选择框。
- `browser_file_upload` 加可选 `target`：`BrowserToolPolicy` 检查 ref 在最新快照里、带 target 时必须有文件；`PlaywrightMcpClient` 过完白名单后换成固定模板的 `browser_run_code_unsafe`（`setInputFiles`，从 ref 往外找最近的 `input[type=file]`）。`browser_run_code_unsafe`、`browser_drop` 仍不在白名单里。工作区里半成品的 `browser_drop` 方案撤掉。
- `PlaywrightMcpClient.hint`：动作超时、没有文件选择框、文件选择框挡着别的操作时，错误后面补一句实情和下一步。
- `application.yml`：`--timeout-action 15000`。
- 提示词 `ApplyAgent`「上传」加一句（带 target、照错误说明做、不推测成安全限制）；skill file-upload 流程改写；`storage/playbook` 的副本同步覆盖（覆盖前 = git 里的种子 + 主会话加的一行，没有用户改动）。
- 夹具：`file-slots` 的作品集按钮改成被遮罩挡住（场景脚本 `blocked`，HTML 加透明层）；`FakeFormScript` 认固定模板的 run_code；`BrowserSessionActTest` 上传用例加几行（点击超时 → 带 target 上传成功）。
- 验证：`mvn -B -q clean verify` 退出码 0（surefire 621、failsafe 1）。用 MCP 同版本的 playwright-core 在无头 Chrome 里打开 `file-slots.html`：点被盖住的按钮超时，模板设上文件、页面显示文件名。真实小米页面、真实模型：未运行。
## 2026-10-23 · 可重复条目「添加」与简历超链接（C006-fix-real-run 14、15）

- 日期：2026-10-23
- 指令：小米校招表（飞书招聘）上点「添加」没加出第二个项目，模型编了「需要真实用户触发」的原因；简历里的项目超链接没有填进链接字段。通过 skill 和提示词解决，读简历时保留超链接网址。
- 结果：新 skill `playbook/skills/components/repeatable-entries/SKILL.md`（先数段数、先保存当前卡片、点添加后看快照确认新卡片、加不上的排查顺序、不编原因、链接字段取简历超链接），`PlaybookSeeder.BUNDLED` 加上它；`ApplyAgent`「教育、实习、工作、项目」补几句并新增「链接」一条；skill apply-form「其它」加一句指向新 skill。`AssetAnalyzer.text` 用 `BodyContentHandler` + `LinkContentHandler` 解析，在正文前列出「文件里的超链接（链接文字：网址）」，只列 http(s)、去重、最多 50 条，整段仍去掉 C 级的值。
- 验证：`mvn -B -q clean verify` 退出码 0（没有改测试）。自制的 docx（3 个 http 超链接 + 1 个 mailto）跑 `AssetAnalyzer.text`：3 个网址按链接文字列出，mailto 不列；字数上限 60 时不报错，已读到的链接照常列出。PDF 超链接：未运行。真实 Chrome、真实模型：未运行。
- 偏差：本机 `storage/playbook/skills/` 里已有的 apply-form 不会被覆盖（PlaybookSeeder 只补缺），新 skill repeatable-entries 下次启动会自动补上。

## 2026-10-23 · 真实跑通（小米 Agent工程师）与修补；暂停交接

- 本会话：多代理做完 ① 标签页改回提示词 / skill（2ad608e）② C008 简历排名（228b38c）；之后真实 Chrome + 真实模型跑小米 Agent工程师：调研 → 排简历 → 开始申请 → 登录 → 上传 → 填写 → 停在提交前（第一次跑到 `ready`，用户没有提交）。跑出的问题逐条记在 [C006-fix-real-run](plan/coding/02-core-agent/C006-fix-real-run.md)（1–19），按用户意见修了：申请 Agent 读简历原文、简历在对话里定好直接上传（C008-fix，867b4a6）；接着做时原件放回上传区（临时补丁 c460958，结构修正待做）；上传带 target 直接 setInputFiles（dc88033）；添加条目 skill、简历超链接（5a7a9d3，合并 7613292）。C009（敏感信息占位符）只写了设计，待用户确认。
- 验证：合并后 `mvn -B -q clean verify` 退出码 0；前端本轮没改。第 10、14、15 条的修法在真实页面上：未运行（第三次跑卡在 Chrome 30 秒允许超时）。
- 后端和 Vite 已停。申请线程 2026-10-23-001 停在「浏览器断开」等你，Chrome 小米页签上表单填了一部分（第一个项目、基本信息）。
- 明天（按顺序）：
  1. 启动后端（`backend/` 下 `java -jar target/jobapply-0.0.1-SNAPSHOT.jar`）和前端（`frontend/` 下 `npm run dev`），刷新工作台页面；点「重新连接」并在 Chrome 30 秒内点允许；在工作台回复让申请 Agent 接着做，看上传（target）、3 个项目（保存 → 添加 → 快照确认）、超链接、时间（NEU 2026 Spring 起每学期一个、各 3 个月）。
  2. 用户决定第 18 条（卡片内「确定 / 完成」）和 C009。
  3. 修第 9 条结构（上传时才复制原件）、12、16、17；token（5、11、19）。

## 2026-10-24 · 运行中插话（C010）

- 日期：2026-10-24
- 指令：参考 deep agents / OpenMAIC，Agent 运行中用户可以打字插话、改变流程（steer）。
- 结果：调研写进 `plan/reference/research-agent-design.md`「运行中插话（steer）」。新增 `domain/Inbox`；共用循环 `AgentLoop` 每次调模型之前取走插话、作为用户消息加进对话（不打断正在执行的工具）。`SessionRunner.send`、`ApplicationRunner.reply` 在跑时不再 409，放进收件箱返回 202（申请工作台运行中只收文字）；跑完还有的写进对话并接着跑。`SessionView`、`ApplicationStatus` 多 `inserted`。ResearchAgent、ApplyAgent 提示词各加一句「插话优先」。前端两个输入框运行中可发，显示「已插入，Agent 下一步会看到」，保留停止，加「打断并发送」（= 停止 + 发送）。细节见 [C010](plan/coding/03-features/C010-steer.md)。
- 验证：`mvn -B -q clean verify` 退出码 0（没改测试、没新增测试）；`npm run build` 通过。真实模型、真实 Chrome：未运行。
- 待用户确认：C010「待用户确认回写 master」那两句（`plan/master/agent-runtime.md` 未改）。

## 2026-10-24 · C006-fix-real-run 第 20 条：点击超时后检查原因，被盖住时系统直接点一次

- 改动：`BrowserSession.act` 在 `browser_click` / `browser_type` / `browser_select_option` 动作超时时调 `afterTimeout`：系统用固定模板（`PlaywrightMcpClient.DIAGNOSE`，经 `browser_run_code_unsafe`，只读，只滚动）检查同一个 ref 可不可见、在不在屏幕里、被哪个元素 / fixed 层盖住、还有哪些浮层开着，写进错误；点击目标可见、只是被盖住时，用 `CLICK_THROUGH` 在元素上直接派发一次点击，成功就取新快照并留说明，失败照实报。连接记账抽成 `BrowserSession.system`。`ApplyAgent` 提示词加「点不动（动作超时）」；skill apply-form、components/repeatable-entries 各加一两条。夹具 `file-slots`（场景 + HTML）加被盖住的「添加」，`FakeFormScript` 认得两个新模板，`BrowserSessionActTest` 加几行。
- 验证：`mvn -B -q clean verify` 退出码 0（surefire 621、failsafe 1）。MCP 同版本 playwright-core + 无头 Chrome 打开 `file-slots.html`：普通点击超时；检查报出盖住它的遮罩；直接点击后页面变成「项目 1（待填写）」；注入的 fixed 底栏和 listbox 浮层都被报出。真实小米页面、真实模型：未运行。
- 预置 skill 改了 `apply-form/SKILL.md`、`components/repeatable-entries/SKILL.md`；`storage/playbook/skills/` 里已有的副本不会被覆盖（本步没碰 `storage/`）。

## 2026-10-24 · C011 Worker 池

- 日期：2026-10-24
- 指令：按已批准的 C011 方案 A 做调度这一半：`ApplicationRunner` 单线程执行器换成 N 个 Worker（默认 3），超出的排队；保留排队时停止、插话；停止检查和 `status()` 读文件移出实例锁；同站点限速（默认 2）；`start_application` 的排队文案只在真的排队时出现；加一个并发测试。不碰浏览器层。
- 结果：`ApplicationRunner` 改成固定线程池 + `queued`（Job → 待跑的运行）/ `running`（Job → 网站 + 任务）+ `dispatch()`；排队凭证去掉（排队的不再提前交给执行器）；`stopRequested` 换成并发集合；`status()` 锁里只读运行记录和运行表；`awaitIdle` 改成 `wait/notifyAll`，新增按 Job 等。`HarnessProperties` / `application.yml` 加 `application-workers: 3`、`applications-per-site: 2`。`SessionRunner` 排队回执文案、`ResearchTools` 的 `start_application` 说明改掉「一次一个」。新增 `backend/src/test/java/jobapply/workflow/ApplicationRunnerPoolTest.java`。前端、提示词没改。细节见 C011「实际结果 · Worker 池」。
- 验证：`backend/` 下 `mvn -B -q clean verify` 退出码 0；`ApplicationRunnerPoolTest` 单独连跑 3 次都通过（3 个同时在模型调用里、第 4 个排队并在一个结束后开始、排队时停止不跑、一个出错不影响别的、同站点上限）。真实 Chrome 冒烟：未运行（浏览器层还在另一个分支改）。前端没改，`npm run build` 未运行。
- 偏差：C011 原文说「保留排队凭证」，实际去掉了，原因见 `dev-recoding.md`；行为（排队时停止、重新排队不跑旧的）不变。

## 2026-10-24 · C011 方案 A 浏览器层：一条共享连接、每个使用者一个 MCP 会话

- 日期：2026-10-24
- 指令：用户批准 C011 方案 A，施工浏览器层：一个 `@playwright/mcp@0.0.83 --port --host 127.0.0.1 --shared-browser-context` 进程、Streamable HTTP、保活会话、每个使用者（Job / Session）一个会话和一把锁、焦点模拟模板、同站对话框卡住的兜底、测试和真实浏览器手动检查。不动 `ApplicationRunner` 的调度。
- 结果：`PlaywrightMcpClient` 换成 HTTP 传输并新增 `Server`（随机端口、只听 127.0.0.1）和三个系统模板（焦点模拟、弹窗 opener 检查、切前台）；`BrowserSession` 只管共享连接（状态、服务进程、保活会话、重连），新 `OwnerBrowser` 管每个使用者的会话、标签页、弹窗、说明、转交，锁按使用者，动作前不再 select；给模型的标签页列表只列自己的页。`HarnessLoop` 两句话、`ApplyAgent` 提示词两条、skill apply-form 一句、`application.yml` 注释、`plan/master/workflow.md` 一句。假 MCP 服务端改成本机回环地址上的 HTTP（Tomcat），新增 `BrowserSessionConcurrencyTest`、手动检查 `ConcurrentBrowserManualCheck`。细节和矩阵见 [C011「实际结果 · 浏览器层」](plan/coding/03-features/C011-concurrent-workers.md)。
- 验证：`JAVA_HOME=… mvn -B -q clean verify` 退出码 0（surefire 609、failsafe 1）；并发测试里两次填写的时间区间重叠约 308 ms、各落在自己的页。手动检查（自己启动的 Chrome for Testing，有头，非 9222，本机夹具页）跑了一次完整矩阵：同时开页 / 填写 / 快照 / 点击区间重叠，重定向、弹窗（含 noopener）、后台页点击、文件选择框和对话框隔离、抛异常、关页、新 Worker、CDP 连接 1 条且连接代数一直是 1，都通过；同站弹窗开着时的 alert 卡住复现，切前台兜底无效（不通过）。用户自己的 Chrome 上冒烟：未运行。
- 偏差：弹窗只按 opener 认（第一次手动跑发现「多出一页就收」会收走别人的弹窗，已去掉）；每次动作前仍在自己会话里列一次标签页（不切换）用来发现自己的页被关；切前台兜底保留但对复现出来的卡住无效。

## 2026-10-24 · C011 合并后池测试时好时坏：假服务端单页模式不分会话

- 日期：2026-10-24
- 指令：Worker 池和浏览器层合并后 `ApplicationRunnerPoolTest` 偶发失败（某家公司「进入模型调用」超时）；稳定复现、找根因（产品的并发问题就改产品，测试假设就改测试）、检查两半之间别的交互、验证并记录。
- 结果：根因在测试：`FakePlaywrightMcpServer` 单页模式只有一个全局当前网址，三个会话同时 `browser_tabs new` 时互相覆盖，别人的页面进了提示词；`GatedModel` 按固定顺序取第一个公司名，于是认错申请、等在别人的门闩上。改成单页模式按会话记当前网址；假模型提示词里公司名不是正好一个时直接报错。产品代码只改 `BrowserSession.close()` 的顺序（先关使用者会话、再关服务进程），去掉收尾时的 `Error during asynchronous close` 警告。锁顺序、并发 `retryOnNextUse`、第一次并发连接都检查过，没有问题。原因、证据见 [C011「合并后的竞争问题」](plan/coding/03-features/C011-concurrent-workers.md)。
- 验证：修改前池测试单独连跑 20 次失败 17 次；改了假模型、保留旧假服务端时 3 次全失败，报错列出提示词里的两个公司名；卡住时 `jstack` 看到 Worker 停在 `GatedModel.call` 的门闩上，没有 BLOCKED 线程、没有死锁。修改后池测试连跑 20 次全部通过；`JAVA_HOME=… mvn -B -q clean verify` 连跑 3 次退出码都是 0。完整测试里剩下的 8 条异步关闭警告来自故意杀掉服务端 / 超时的断开测试，属预期。真实浏览器：未运行（真的 Playwright MCP 每个会话自己的当前页，不受影响）。
- 偏差：无。

## 2026-10-24 · C010 回写 master（用户批准）

- 按代码（`Inbox`、`AgentLoop.heard`、`SessionRunner.send`、`ApplicationRunner.reply/insert/resumeWithInserted/stop`、前端「打断并发送」）在 `plan/master/agent-runtime.md` 7.1 加一条「运行中插话」、7.7 `ApplicationRunner` 的「回复」补「在跑或排队时只收文字，带答案、文件或附件时 409」；和 C010 原拟文字的差别（排队时也收、排队时停下也接着跑、用户已定下不再跑）记在 C010；只改文档，未运行构建。

## 2026-10-24 · C011 冒烟清单和观察脚本

- 指令：写用户自己 Chrome 上的 C011 冒烟清单（严格不提交；证明两个 Worker 重叠动作、各在自己的标签页；只弹一次允许、MCP 会话隔离；排队中和运行中插话；K1 同站点卡住单独列为已知问题，出现即记失败）和一个只读观察脚本。
- 结果：新增 `plan/coding/03-features/C011-smoke-test.md`（推荐 `--jobapply.harness.application-workers=2` + 3 个不同网站的岗位让第三个排队）和 `plan/coding/03-features/c011_smoke_observe.py`（`watch` 记事件流和状态轮询的结构字段，`report` 打印重叠、页面地址、连接代数、插话、状态五张表；不打印填写的值）；C011 加一行指向清单。
- 验证：脚本 `report` 对现有 3 个 Job 的文件跑通（应用没开，接口部分按读不到处理；输出只核对了结构，没看内容）；`watch` 和应用日志解析用 scratchpad 里的合成数据跑通。对真实运行中的应用订阅事件流：未运行。冒烟本身：未运行。

## 2026-10-24 · C012 设置页改 Worker 数量

- 日期：2026-10-24
- 指令：用户「修改为支持前端改 worker 数量」：先写计划再施工；运行中调大马上放排队的、调小不停在跑的；存在 `storage/settings/` 的非密钥文件，`application.yml` / 命令行仍是默认值；范围 Worker 1–6、每站 1 到 Worker 数，超出 400；设置页加「同时申请」。
- 结果：`ApplicationRunner` 两个上限改为锁里可变、执行器换成按需建线程的池、新增 `concurrency()` / `setConcurrency()`；新增 `WorkerSettingsFile`（`settings/workers.yaml`）、`WorkerSettingsController`（`GET` / `PUT /api/settings/workers`）；前端设置 →「模型」最下面加「同时申请」卡片。细节见 [C012](plan/coding/03-features/C012-worker-settings.md)。
- 验证：`JAVA_HOME=… mvn -B -q clean verify` 退出码 0（surefire 612、failsafe 1）；`ApplicationRunnerPoolTest` 新增一条（调小不停在跑的且不放新的、调大马上放、超出范围不写文件），池测试单独连跑 5 次都通过；`frontend/` 下 `npm run build` 通过。真实应用、真实模型、真实 Chrome：未运行。
- 偏差：无。

## 2026-10-24 · C013 删除 Session / Project

- 日期：2026-10-24
- 指令：用户「好像还是做不到 delete session/project。参考那两个 repo 设计然后执行」：先查 deep-agents-ui、OpenMAIC（及 langgraphjs）怎么删线程 / 项目，写进参考文档，再写 C013 并施工；软删进回收站、一个确认框；删 Session 时 Job 留下；删 Project 时在跑 409、连 runs / uploads 一起挪、全局台账去掉它；不碰 base、settings、playbook、别的 Project、example.data。
- 结果：参考结论写进 `plan/reference/research-agent-design.md`「删除会话 / 项目」（deep-agents-ui `f6a4f34` 没有删除；OpenMAIC `dff201b` 软删 + 侧栏 ⋯ 先问一句 + 删文件夹留课程；langgraphjs `c6675bf` 硬删级联）。新增 `Trash`（挪进 `storage/.trash/<时间>-<kind>-<id>/`，照原路径摆放，写 `RESTORE.md`）、`DELETE /api/sessions/{id}`（在跑时请停、停下后挪走，202）、`DELETE /api/projects/{id}`（在跑 / 被继承 409）。台账写入和回复申请线程接受已删除的 Session（否则删掉发起申请的 Session 后申请推进不了）；回收站里的编号不再分配。侧栏 Project / Session 行加垃圾桶和确认框；已删除的 Session 在 Session 页、Job 页、候选池显示「会话已删除」。细节见 [C013](plan/coding/03-features/C013-delete-session-project.md)。
- 验证：新增 `DeleteSessionProjectTest`（临时 storage、虚构数据：删 Session 只挪这一个目录、其余文件逐字节不变、Job 还能更新、编号不复用；删 Project 只挪它和它的 Job 的 runs / uploads，base、settings、playbook、另一个 Project 逐字节不变，`jobs-index.md` 没有它了；有申请在跑时删 Project 409）通过。`JAVA_HOME=… mvn -B -q clean verify` 退出码 0；`frontend/` 下 `npm ci` 后 `npm run build` 通过。真实应用、真实浏览器：未运行（用户的应用在 8080 上）。
- 偏差：没做 ⋯ 菜单，垃圾桶和改名笔并排；没做恢复接口（手动挪回）；`plan/master/memory.md` 目录树加 `.trash/` 等用户确认。

## 2026-10-24 · C013 删除确认框修正（用户反馈）

- 无头 Chromium 复现：删除请求没回来时「取消」/ Esc / 点框外都关不掉，只能刷新。改成任何时候都能关、请求 20 秒超时、默认焦点在「取消」；框加宽加间距、按钮靠右、「删除」实心红；侧栏删除图标改成红色、悬停红底。`npm run build` 通过，无头实测 6 项都过（没有真的删任何东西）。

## 2026-10-24 · C011 用户冒烟结果改成通过

- 指令：用户说明已在本机真实测试，都正常投递；记录里不再写「用户口述、非本会话证据」这类话。
- 结果：C011「验收」表里的只弹一次允许、运行中插话、填表阶段后台点击改为通过（用户本机测试），结论改为用户真实 Chrome 冒烟通过；C011 状态和 `dev-plan.md` 索引行改为完成。K1 照旧记为已知问题。
- 验证：只改文档。

## 2026-10-24 · C014-A 面试轮次与复盘

- 日期：2026-10-24
- 指令：按 C014 工作包 A 施工：对话 Agent 新工具 `record_round`、`save_review`（阶段由轮次推出，复盘直接写），生成轮次表、题库、按公司汇总，unknown 在生成台账时算；前端 Job 页轮次和复盘、Dashboard ⑦、申请总表「进度」。
- 结果：新增 `domain/HiringRound`、`persistence/RoundReviews`、`persistence/ReviewWriter`、`dashboard/Reviews.tsx`；`JobLedger` 加 `recordRound`、`saveReview`；`TrackerWriter` 写 `tracker/rounds.md` / `rounds.csv`，申请总表「进度」「同步」两列填上；全局生成时写 `review/questions.md`、`review/companies/<company>.md`；事件加 `round_recorded`、`review_saved`，Session 标签加 `review`；`ResearchTools` / `SessionLoop` 加两个工具，`ResearchAgent` 加一段提示词；`JobView` 加 `rounds`，新接口 `GET /api/jobs/{id}/rounds/{seq}/review`；工作台右栏「轮次」、申请总表「进度」列、Dashboard「复盘概览」。细节见 C014「实际结果 · A」。
- 验证：`backend/` 下 `mvn -B -q clean verify` 退出码 0；`frontend/` 下 `npm run build` 通过。临时场景（临时 storage、虚构公司，跑完删除，未提交）：一面 → 重复调用不多记 → 写复盘 → 二面 → 一面 passed → 12 天后二面 failed，阶段 applied → interview → closed:rejected，轮次表、进度圆点、⚠ unknown、题库、按公司汇总、Session 标签都和预期一致。真实对话里模型调用这两个工具：未运行。
- 偏差：unknown 阈值固定 7 天，没有做 Project 偏好里可改；公司流程模板的空心圆、看板卡片进度点没做（计划没点名）。

## 2026-10-24 · C014-B Agent 自己更新 memory + Cover Letter

- 日期：2026-10-24
- 指令：按 C014 工作包 B 施工：两个 Agent 都有 `remember`，直接写本地 memory（个人事实、站点、公司、经验），C 级明文拒绝、只收 `{KEY}` 占位符；申请 Agent 遇到 Cover Letter 文本框时按 JD、简历、资料起草填进去。施工中用户改设计：`remember` 已有的更新、新的才加，照 Hermes 的 memory / skill_manage；再加改 skill 的入口和预置 skill `memory-update`。
- 结果：新增 `memory/MemoryWriter.java`、`persistence/MemoryNotes.java`、skill `components/cover-letter`、`memory-update`（进 `PlaybookSeeder.BUNDLED`）。`ControlTools` 加 `read_jd`、`remember`（定义两个 Agent 共用），`ResearchTools` 加 `remember`；`SessionLoop`、`HarnessLoop` 各加执行分支，对话 Agent 上下文加「已有的资料键」。`remember`：`action add / replace / remove`；fact 按键 upsert 到 Project 的 `overrides.md`；site / company / lesson 是 md 里的一行条目，`old_text` 定位、去重、每文件 4000 字上限；skill 只做唯一匹配的局部替换。`JobRepository.jdSnapshot`、`StorageLayout.companyFile / reviewLessons / baseWriting`、`FieldIntent.COVER_LETTER` + `FillReportWriter.COVER_LETTER_SOURCE`（报告来源写「Cover Letter：Agent 起草」）。两个 Agent 的提示词各加一段。`plan/reference/research-agent-design.md` 加「Hermes 的 memory 写法」，C014 写「实际结果 · B」。
- 验证：`backend/` 下 `JAVA_HOME=… mvn -B -q clean verify` 退出码 0。jshell 在临时 storage 上用虚构数据手动调 `MemoryWriter`：新加 / 按键更新 / C 级明文拒绝 / 条目去重、替换、对不上报错 / 超上限拒绝 / skill old_string 对不上报错，都符合预期。前端没改。真实应用、真实浏览器：未运行。
- 偏差：`{KEY}` 占位符在本分支写不进去（`Fact` 还不允许 C 级），等 D 合并。经验文件只写不读，还没放进 Agent 上下文。没写测试。

## 2026-10-24 · C005-fix 新建 Session 不再自动写开场

- 日期：2026-10-24
- 指令：用户：每开一个新 Session，Agent 都先写一大段项目状态；新建 Session 改成空白对话，用户第一条消息前不调模型，项目状态放在 Project 主页 / Dashboard。
- 结果：`SessionRunner` 去掉 `OPENING` 和新建时的自动运行（`create` 只建 Session、发 `changed`，新建 `POST` 返回 201）；Session 页空白时一行固定提示 + 输入框，去掉三个建议按钮；Project 主页的等审阅（「待你」）、「该查看了」（「待追踪」）已有，缺的候选池待投数加成 KPI 第 9 格「候选池待投」。`ui.md`「新建 Session」、`tracking.md`「匹配反馈与提醒」、`workflow.md` 遗漏检查第 10 项改成空白对话 / Project 主页里提醒。已有 Session 里的开场 note 不动。细节见 [C005-fix 开场](plan/coding/02-core-agent/C005-fix-session-opening.md)。
- 验证：`JAVA_HOME=… mvn -B -q clean verify` 退出码 0（没有测试依赖开场，测试未改）；`frontend/` 下 `npm run build` 通过。用户本机新建 Session、看 Project 主页：未运行。
- 偏差：建议按钮去掉（计划写的是一行提示 + 输入框）；新建接口 202 改 201。

## 2026-10-24 · C014-D 敏感信息占位符

- 日期：2026-10-24
- 指令：按 C014 D 包施工：C 级资料写成占位符 `{KEY}`，真实值在 `storage/settings/personal.env`，只在填表调用发给 Playwright MCP 的那一刻替换；施工中用户补充：用户不手写文件、不猜键名，设置页「敏感信息」填值，预设键表加自定义键，保存时自动登记占位符事实。
- 结果：新增 `domain/Placeholder`、`domain/PersonalKey`（15 个预设键）、`persistence/PersonalEnv`、`config/PersonalSettingsService`、`api/PersonalSettingsController`（`GET /api/settings/personal`，`PUT` / `DELETE /api/settings/personal/{KEY}`，不返回值）、前端 `pages/PersonalSettings.tsx`、`backend/src/main/resources/personal.env.example`（只有说明文字）。`Fact` / `FactsFile` / `FillReportWriter` 允许值是占位符的 C 级，明文仍拒绝；`FieldClassGuard` 认登记过且有值的占位符，没值时原因写「`{KEY}` 在 personal.env 里没有值」；`HarnessLoop` 只在 `browser.act` 那一刻替换；`BrowserSession` 交出快照、查找结果和报错前用 `SnapshotRedactor.withoutValues` 把真实值换回占位符；`FillContext`、`ApplyAgent` 提示词改成「有占位符就原样填」。`plan/master/workflow.md`、`agent-runtime.md` 里「C 级一律亲填」的句子补上占位符例外。`SecretFileRules` 把 `*.env` 也当秘密文件（扫描目录、附件时按名字跳过）。代理没有读、建、改任何真实的 `personal.env` 或 `.env`。
- 验证：`backend/` 下 `JAVA_HOME=… mvn -B -q clean verify` 退出码 0（617 个测试，0 失败），新增 `PersonalPlaceholderTest`（真实值只出现在发给假 MCP 的请求里，模型请求、对话、报告、run log、storage 其它文件都没有；明文 C 级和没值的占位符被拒绝）、`PersonalSettingsControllerTest`（响应不含值、权限 600、自动登记事实）、`FactsFileTest` 占位符读写。`frontend/` 下 `npm ci && npm run build` 通过。真实浏览器手动试用：未运行。
- 偏差：资料导入（候选抽取 / 合并）没有改成放行占位符，占位符只由设置页登记；`remember` 的占位符入口归 B 包。

## 2026-10-24 · C014 A / B / D 与 C005-fix 开场合并

- 结果：四个分支合进 C013。代码冲突只有两边各加一段的地方（`ResearchAgent` 提示词、`ResearchTools` 工具名单、`HarnessLoop` 的字段和构造），都保留两边。B 里「把真实值写进 personal.env」的说法改成「在设置 → 敏感信息里填」（提示词、skill memory-update、`remember` 说明、`MemoryWriter` 报错）。
- 验证：合并后 `backend/` 下 `JAVA_HOME=… mvn -B -q clean verify` 退出码 0；`frontend/` 下 `npm run build` 通过。


## 2026-10-24 · C015 README 项目展示

- 指令：按当前程序重写面向简历读者的 GitHub README，包含项目目的、架构、技术亮点、框架取舍、开源源码研究及无隐私截图。
- 结果：重写 README，补 Mermaid 架构、六项工程亮点、技术演进对照与代码入口；新增两张原型虚构数据截图。明确早期 LangGraph4j 的使用和移除、Alibaba Graph 的调研身份，以及当前版本的真实能力和验证限制。增加 C015 与 dev-plan 索引，移除索引中“业务行为尚未开始”的过时句子。
- 验证：核对代码 / 依赖及官方框架文档；本地链接与图片路径检查、GFM 结构渲染、截图目视检查、git diff --check 通过。未读取真实资料、秘密文件，截图无个人隐私，无需打码。GitHub 在线渲染、mvn verify、npm run build、真实模型 / 浏览器流程：未运行。
- 细节：[C015](plan/coding/03-features/C015-readme.md)。


## 2026-10-24 · C015 README 再修改

- 指令：减少 AI 式表达，参考 Agent 开源 README，按目的、系统框架、技术、选型与借鉴重排；排查图片显示，并对照源码写清参考关系。
- 结果：重写 README；OpenMAIC / Deep Agents / Hermes 的借鉴附固定提交源码与本项目模块链接。图片改标准 Markdown，架构改无需插件的文本图。发现旧临时预览缺少资源基址，已在临时验证脚本修正；PNG 未损坏。两张图片仍需随 README 一起提交。
- 验证：本地链接、原文 GFM 渲染、独立无头 Chromium 图片加载（1600 / 1900 像素宽）、首屏目视检查、git diff --check 通过。浏览器首次被沙箱阻止，授权后验证成功。GitHub 在线渲染、应用构建、真实模型 / 招聘网站流程未运行；未提交或推送。
- 细节：[C015 第二次修改](plan/coding/03-features/C015-readme.md)。


## 2026-10-24 · C015 去掉 README 开发汇报措辞

- 按用户要求删除验收、测试、开发进度及待办说明，简化守卫与选型状态措辞；保留功能、技术、实现、参考来源和启动方法。
- 本地链接与 `git diff --check` 通过；应用测试未运行（纯文案）。


## 2026-10-24 · C015 架构图与陈述式表达

- 恢复 Mermaid 架构图，替换文本线条图；重写架构与技术正文，所有疑问式标题改为陈述式，直接说明设计和实现效果。
- 本地链接、标题、代码围栏、git diff --check 通过；Mermaid 实际渲染与应用测试未运行（本次仅文档）。


## 2026-10-24 · C015 补充 Agent 核心设计

- 按用户指令将 README 主体扩展为 Harness、Round Loop、底层消息、提示词与 Skills 组装、Memory 分层和 Markdown / 向量 RAG 取舍；叙述补上明确主语。
- 对照现有代码确认上下文角色、调用 ID 关联、最新快照、记忆覆盖顺序及文件格式，删除过时的可选 JGit 存储说明。
- 本地链接、代码围栏、git diff --check 通过；应用测试及 Mermaid 实际渲染未运行。


## 2026-10-24 · C015 分层 Memory 选型补充

按用户明确说明重写 README 的 Markdown / RAG 取舍，补 SillyTavern 设计借鉴；同步 Master Memory 和研究笔记。核心理由为逻辑归属、资料分层及引用追溯，而非只因数据量小。不新增运行时能力。本地链接与 git diff --check 通过；应用测试未运行。


## 2026-10-24 · C015 README 语言终审

- 全文调整主语与句间衔接，分开用户操作和系统实现，统一开源借鉴的叙述角度；未新增技术内容。
- 正文复读、本地链接、代码围栏、标题检查及 git diff --check 通过；应用测试未运行（纯文案）。

- 2026-10-24 · C015：README 两处 SillyTavern 添加官方仓库链接，核对页面可访问及 git diff --check；应用测试未运行（仅链接）。


## 2026-10-24 · C011-fix 方案 B：JobApply 自己的 Chrome 窗口；卡住和断开分开

- 指令：用户选 C011-fix 方案 B（MCP 不再连用户正在用的 Chrome，改为自己启动带持久 profile 的 Chrome 窗口），加两条路共同的部分（MCP 请求超时不当断开、「重新连接」先问原连接、普通调用超时缩短、导航超时更短）；用户的指令同时确认 master 里相应说法的修改。
- 结果：
  - 启动参数改成 `--browser chrome`，程序追加 `--user-data-dir storage/cache/chrome-profile`（被 Git 忽略，不随输出目录清空），`--timeout-navigation 25000`；`call-timeout` 60s → 30s，新增 `connect-timeout: 60s`（保活会话和等端口用）。
  - 新增 `BrowserException.Stalled` 和状态 `stalled`：超时不关进程、不重连、不清记录；有调用答话就回到已连接；「重新连接」先问保活会话，答不了才重建；服务进程已退出时的超时仍算断开。申请 Agent 卡住时停下等你，对话 Agent 这一步失败接着做；`/api/browser/tabs` 卡住时 503。
  - 资料目录被占、没装 Chrome、启动失败算断开并说明怎么办。`Server.close()` 最多等 5 秒让 Chrome 正常退出。
  - 面向用户和模型的「chrome://inspect 远程调试开关」说法去掉，登录提示改成「在 JobApply 的 Chrome 窗口里登录一次，之后会记住」；前端顶部状态条显示「浏览器卡住了」。
  - 文档：C011-fix「实际结果」；master `base.md`、`workflow.md`、`agent-runtime.md`、`memory.md`、`ui.md`；README 里变成不对的几句。`architecture.md` 没有相关内容，未改。
- 验证：
  - `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -B -q clean verify`（`backend/`）：第一次退出码 1，`DeleteSessionProjectTest` 的存储快照碰上 playbook 写入中的临时文件（与本次改动无关的竞态）；第二次退出码 0。
  - `npm run build`（`frontend/`）：退出码 0。
  - 本机冒烟（代理执行；临时资料目录；只开 127.0.0.1 本地页面；不访问外网、不登录、不提交）：Chrome 有窗口启动、开页、列标签页正常；重启后资料目录还在、照常启动。本地死循环页面让渲染进程卡死 → 调用报 `Stalled`、状态 `stalled`、没有重启；仍卡着时「重新连接」→ 重建成功（第 2 次连接）。结束 Chrome 主进程 → MCP 下一次调用自己重开 Chrome，旧标签页报 `TabLost`。结束后无残留 Chrome 进程。
  - 用户本机真实招聘网站、闲置后再用：未运行。
- 细节：[C011-fix 实际结果](plan/coding/03-features/C011-fix-stuck-tab.md)。


## 2026-10-24 · C011-fix 撤回方案 B，保留卡住 / 断开分开

- 指令：用户试用方案 B 后要求改回在用户正在用的 Chrome 上工作，并把这条写进 AGENTS.md。
- 结果：停掉了运行中的方案 B 版本应用、它的 MCP 和它启动的 Chrome（用户自己的 Chrome 没动）。撤回 `--browser chrome` / `--user-data-dir` 及相关代码、提示词、skill、界面文字、master plan 和 README 的改动；保留 `Stalled` / `stalled`、超时 30 / 60 秒、导航 25 秒。AGENTS.md「Agent 开发原则」加两条：浏览器就是用户正在用的 Chrome；不为安全把系统改坏。
- 验证：`backend/` 下 `JAVA_HOME=… mvn -B -q clean verify` 退出码 0；`frontend/` 下 `npm run build` 通过。用户真实 Chrome 上的试用：未运行。

## 2026-10-24 · C011-fix 标签页全部可见；删除方案 B 的资料目录

- 指令：删掉 `storage/cache/chrome-profile/`；让 Agent 看到 Chrome 里所有的标签页，用哪一页交给提示词。
- 结果：删除了方案 B 留下的资料目录（53 MB，被 Git 忽略）。`browser_tabs list` 列出全部标签页并标出「你的」「别的任务在用」，`select` 能切到任何一页；提示词、skill、工具说明、`workflow.md` 相应改写。细节见 C011-fix「标签页全部可见」。
- 验证：`backend/` 下 `JAVA_HOME=… mvn -B -q clean verify` 退出码 0。用户真实 Chrome：未运行。

## 2026-10-24 · C016 下拉、级联、日期（A–G 一次施工）

- 指令：用户确认 C016：沿用 `browser_select_option`；`PlaybookSeeder` 按 skill 的 version 覆盖旧副本；随后改为 A–G 同一次施工（日期工具、打开弹出层后只给弹出层、来回提醒都做），不提交。
- 结果：
  - A：超时检查报只读 / 禁用 / 原生 select，弹出层只算真有选项或对话框的浮层；`browser_type` / `browser_fill_form` 发出前一次只读检查，只读框、禁用框、非输入框、fill_form 里的自定义下拉不发，结果写明哪些没填和原因，其余照常填。
  - B：`browser_select_option` 能选原生 select、自定义下拉、可搜索 / 远程搜索下拉、级联（values 每层一个，可选 `search`）；选不中、有歧义时不点，返回这一层的选项原文；不回显值。target 是 button / generic 时先过点击守卫。
  - C：`browser_type` 的 schema 给出 `slowly`。
  - D：`ApplyAgent` 下拉 / 日期 / 点不动三条重写；skill `date-picker`、`readonly-select`、`cascader-city`、`apply-form` 改写并 version 1 → 2；`PlaybookSeeder` 在预置 version 更高时覆盖本机副本，相同 / 更高 / 没有 version 时不动。
  - E：新工具 `browser_set_date`（原生框设 ISO；能键入的按占位符格式键入 + Tab；只读的点开面板、经年份表 / 月份表跳过去再点日期；要「确定」时返回给模型，不点）。
  - F：新文件 `harness/context/PopupView.java`：点击 / 键入后打开了弹出层时，给模型的快照只留弹出层和一行说明；判断不了时给整页。
  - G：`LoopDetector` 加来回提醒（最近 8 步同一元素 ≥3 次且中间有 Escape 或失败），只提醒。
  - 固定脚本都在新文件 `tools/PageScripts.java`；每项的文件清单见 C016「实际结果」。
- 验证：
  - `backend/` 下 `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -B -q clean verify`：第一次退出码 1（3 个测试编码旧行为：工具清单、select 角色、选原生下拉的调用序列），改测试后第二次退出码 0。之后只改了 HarnessLoop 里 target 守卫的角色条件，再跑第三、四次：同一工作区里另一项任务（C011-fix：去掉切前台 / 焦点模拟、标签页 close）正在改 `OwnerBrowser` / `PlaywrightMcpClient` / 白名单，第三次编译失败（`focusEmulation` / `bringToFront` 找不到），第四次退出码 1，失败在 `BrowserSessionActTest`（调用序列少了最后一次 `browser_tabs`）、`PlaywrightMcpClientTest` 两项、`BrowserToolPolicyTest.tabsMayNotCloseTabs`，都属于那项进行中的改动，C016 的部分在失败输出里符合预期。C016 改动本身在第二次的全量验证里通过。
  - 新增测试一个：`PlaywrightMcpClientTest.scriptArgumentsSurviveQuotesBackslashesAndNewlines`。
  - 本地脚本检查（代理执行，headless Chromium，只开 scratchpad 里的本地 HTML，未连用户的 Chrome，进程已退出）：原生 select、ARIA listbox、li 长列表滚动、两列级联、远程搜索、antd3 式只读日期面板（近月箭头、跨年、跨十年）、原生 month / date、按占位符键入，结果都符合预期；Java 里的脚本文本和检查过的 JS 逐字相同。
  - 真实招聘网站：未运行。

## 2026-10-24 · C011-fix 浏览器越用越卡、抢焦点

- 指令：用户批准一次做完：停下时关 Agent 自己开的标签页、释放 MCP 会话；焦点模拟只留在正在用的页；去掉抢焦点（含动作超时后的切前台）；少列一次标签页；接口里客户端断开的 Broken pipe 不再记 ERROR。不提交。
- 结果：
  - 关页：`browser_tabs close` 只由系统按序号发；`BrowserSession.leave / release`（`Leave`：KEEP_ALL / KEEP_CURRENT / CLOSE_ALL）。申请停在提交前只留填好的那页，等你 / 出错页都留着，已投递 / 不投了 / 岗位下线 / Project 删除时全部关掉；调研每次运行结束关掉；用户转交的页、用户自己的页不关；停下时都关掉焦点模拟和 MCP 会话。
  - 焦点模拟：每页一个缓存的 CDP 会话，新增关掉的模板，切走、停下时关。
  - 抢焦点：新资源 `browser/no-bring-to-front.cjs`，启动时写到 `storage/cache/playwright-init/`，追加 `--init-page`（0.0.83 源码核对过加载方式和作用范围）；去掉我们的 `BRING_TO_FRONT`。
  - 少列：只读操作（快照、查找、等待、填写前检查）不先列，从返回核对；写操作前的列表保留（不列会写到别人的页上）。
  - `ApiErrors`：异常链上有 `AsyncRequestNotUsableException` 时记 DEBUG、不写响应。
  - 细节、文件清单、源码依据见 C011-fix「浏览器越用越卡、抢焦点」；`C011-concurrent-workers.md` 5.1 / K1、`workflow.md` 两处补了说明。
- 验证：
  - `backend/` 下 `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -B -q clean verify`：第一次退出码 1（测试编译：AssertJ 没有 `doesNotStartWith`），第二次退出码 1（`BrowserToolPolicyTest.tabsMayNotCloseTabs` 编码旧行为），改测试后第三次退出码 0（合并了 C016 之后的整棵树）；改完 application.yml 注释后第四次退出码 0。
  - 新增测试 `BrowserSessionTabsTest.stoppingClosesOnlyTabsTheOwnerOpenedAndKeepsTheFormForReview`。
  - 用户真实 Chrome：未运行。前端：未改，`npm run build` 未运行。

## 2026-10-25 · C011-fix 卡住不再停下申请

- 指令：用户否决「浏览器卡住就停下申请」（多一个断点，违反 AGENTS.md）。
- 结果（协调代理改的代码）：`HarnessLoop.stalled` 返回普通的失败工具结果，由模型决定重试、取快照、重新打开还是问用户；`startPage` 卡住时补一句叫模型再 `browser_navigate`；删掉 `stalledRun`。`SessionLoop.stalled` 去掉「先别再用浏览器工具」。记在 C011-fix「卡住不再停下申请」。
- 验证：`backend/` 下 `mvn -B -q clean verify` 退出码 0（协调代理报告）。

## 2026-10-25 · C011-fix 连不上：休眠标签页卡住连接

- 指令：界面显示「60 秒内没有连上 Chrome（MCP 没有回话）…还是连不上」；Chrome 开着、9222 在听、大部分标签页在睡。要在用户自己的 Chrome 里、不让用户点 / 关标签页 / 改设置就能连上并用；连接失败后下一次使用自动再连；话写实情；`Stalled` 仍是普通工具失败。不提交。
- 结果：
  - 原因（读 0.0.83 源码 + 本地复现，见 C011-fix 同名一节）：连接时 `CRBrowser.connect` 等每一页初始化（受 `--cdp-timeout` 30 秒限制，超时报 `initializeServer`）；连上以后每次调用对每一页 `page.title()`，没有上限，一页不答所有调用都不回话（用户的 60 秒属于这一类，推断）。
  - 新资源 `browser/skip-unresponsive-tabs.cjs`（Node preload，写到 `storage/cache/playwright-init/`，经 `NODE_OPTIONS=--require` 交给 MCP）：连接时每页最多等 5 秒、没答的先跳过；取标题最多 2 秒、没答用上次的。打不上或文件不在时记日志、照原样运行。
  - `BrowserSession`：第一次建立连接失败后下一次使用自动再连一次。连接失败、卡住的话改成实情；顶部状态条文字跟着改。
  - 文件：`BrowserMcpProperties`、`BrowserLaunch`（加 `preload`）、`PlaywrightMcpClient`、`BrowserSession`、`application.yml` 注释、`frontend/src/BrowserBanner.tsx`；测试 `BrowserSessionConnectionTest`（旧行为那条改写）、`PlaywrightMcpClientTest`（加 preload 断言）。
- 验证：
  - 本地复现（代理执行，scratchpad 里自己起的 headless Chrome for Testing 153，只开 127.0.0.1 本地页面；`npx -y @playwright/mcp@0.0.83 --cdp-endpoint http://127.0.0.1:9333 --shared-browser-context`；未连用户的 Chrome，进程都已结束）：不带补丁时，没加载 / alert / 死循环 / 崩溃的后台页让每次调用 30 秒报 `initializeServer: Timeout 30000ms exceeded`，连上后一页死循环让所有调用 45 秒无回应；带补丁时第一次调用约 5.1 秒（或 2.0 秒），之后几毫秒到几十毫秒，开新页、导航、快照正常，睡着的页醒后出现在列表里；按 Java 的 `env -i` + `NODE_OPTIONS` 方式启动也生效。
  - `backend/` 下 `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -B -q clean verify` 退出码 0（改完代码一次，改 preload 缺失时的处理和 yml 注释后再一次，都是 0）。
  - `frontend/` 下 `npm run build` 退出码 0。
  - 用户真实 Chrome：未运行。

## 2026-10-25 · 申请线程 NullPointerException（PopupView）

- 日期：2026-10-25
- 指令：申请 2026-10-24-011 显示「出错：NullPointerException」；找根因并修，出错信息要能看出是什么、在哪里。不提交。
- 结果：
  - 现象：`storage/runs/2026-10-24-011.json` 的 `error` 只有 `NullPointerException`，没有存栈。三次出错（16:30:32、16:33:09、16:34:58）都紧跟在个人中心简历页上 `browser_click`「Add」之后：工具调用已记成 ok，下一次模型调用没有发生。
  - 根因（单元测试复现确认）：`harness/context/PopupView.options` 对每行的角色调 `STRONG.contains(role)`；`STRONG` 是 `Set.of(...)`（6 个元素），不可变集合的 `contains(null)` 直接抛不带消息的 `NullPointerException`。点「Add」打开的新表单块里有不带角色的行（`- /url:`、`- /placeholder:`、多行文字），role 为 null，于是在点击之后、给模型看页面之前抛出。修法：角色为 null 的行跳过。
  - 出错信息：`ApplicationRunner.work` 的意外异常改用 `crashMessage`：根因消息；根因是 JDK 异常（程序错误）时带上类名和本项目代码里最近一帧的位置，例如 `NullPointerException（位置：PopupView.options:119）`；日志 `log.warn` 带完整栈；Job 事件 `prep_state_changed → failed` 的 reason 改为「申请线程出错：<error>」。
  - 同类检查：最近改动里其余对 `Set.of` / `Map.of` 的 `contains` 都先判空或参数不会为 null，未改。
  - 文件：`harness/context/PopupView.java`、`workflow/ApplicationRunner.java`；新测试 `harness/context/PopupViewTest.java`（新块含无角色行不抛异常；下拉块含无角色行照常只给弹出层）。
- 验证：
  - 修之前 `mvn -B -q test -Dtest=PopupViewTest` 两条都报 `java.lang.NullPointerException at PopupView.options(PopupView.java:119)`（消息为空，和线上一致）。
  - `backend/` 下 `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -B -q clean verify`：第一次退出码 1（新测试自己的断言写错，与修复无关），改断言后退出码 0，635 个测试。
  - 正在运行的应用里重跑这份申请：未运行（需要用户重启应用）。
- 偏差：无。

## 2026-10-25 · C018 工具调用上限在设置页由用户自己定

- 指令：「是我这边每轮消息调工具的次数有上限（这次大约 40 次） 还有工具调用上限我需要能在前端自主设计」。照 C012 的写法做，一次做完，不提交。编号：交代写的是 C017，已被 `C017-macos-app.md` 占用，改用 C018。
- 结果：
  - 找到的上限：对话 Agent 每条消息 40 次（`SessionLoop.TOOL_CALLS_PER_MESSAGE`，写死，就是「大约 40 次」）；申请 Agent 每次运行 150 次（`jobapply.harness.tool-calls-per-run`）。都在 `AgentLoop` 里判断，到了让模型总结、回复末尾「回复「继续」我接着做」，用户回复后新的一轮从 0 算。重复提醒、模型重试、`max_tokens` 不是工具次数上限；Spring AI 2.0.1 的两个 ChatModel 只取工具定义、不自己执行工具，没有框架层的循环上限。
  - 改法：默认值留在 `application.yml`（新增 `tool-calls-per-message: 40`，两项 0 = 不限）；设置页改过存 `storage/settings/tool-calls.yaml`；`workflow/ToolCallLimits` 两个 Runner 共用；`AgentLoop` 上限改成每步读一次的 `IntSupplier`，在跑的下一步就用新值；到上限的回复加一句可以去设置页调大或设为不限；接口 `GET/PUT /api/settings/tool-calls`；前端设置 →「模型」加卡片「工具调用上限」。
  - 文件：`config/HarnessProperties.java`、`application.yml`、`persistence/StorageLayout.java`、`persistence/ToolCallsFile.java`（新）、`workflow/ToolCallLimits.java`（新）、`workflow/ApplicationRunner.java`、`workflow/SessionRunner.java`、`harness/runtime/AgentLoop.java`、`harness/runtime/HarnessLoop.java`、`harness/runtime/SessionLoop.java`、`api/ToolCallSettingsController.java`（新）；`frontend/src/pages/ToolCallSettings.tsx`（新）、`frontend/src/api.ts`、`frontend/src/pages/Settings.tsx`；测试只改 `ApplicationRunnerPoolTest` 的构造参数。文档：`plan/coding/03-features/C018-tool-call-limits.md`（新）、`dev-plan.md` 一行、`plan/master/agent-runtime.md` 迭代上限一句。
- 验证：
  - `backend/` 下 `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -B -q clean verify`：第一次退出码 1（`PlaywrightMcpClientTest`、`BrowserSessionActTest` 报 `NoClassDefFound StorageLayout`、找不到夹具，当时另一个代理的 Maven 同时在 clean 同一个 `target/`）；等它跑完再跑一次，退出码 0。
  - `frontend/` 下 `npm run build`：退出码 0（只有原来就有的包体积提示）。
  - 真实应用、真实模型：未运行（应用在 8080 上跑着，没有重启）。
- 偏差：编号 C018（见上）；`plan/master/ui.md` 没有逐项列设置页的卡片，未改。

## 2026-10-25 · C016 实跑后修正（申请 2026-10-24-010 的证据）

- 指令：按用户 2026-10-25 的六条决定一次做完，不提交：下拉脚本不看透明度、打开后轮询、按 aria-expanded 判断开关并照实报 closed；弹出层视图时保留上一份整页快照；同一次运行里把思考发回给支持的模型，提示词要模型把字段清单和决定记在 update_plan；整个去掉来回提醒（G）；要到日的日期框只给年月时翻到那个月返回 need_day；第 18 条放行确认类按钮，最终提交仍硬拦。
- 结果：
  - `tools/PageScripts.java`：可见性去掉 opacity；`expandedOf` / `op: 'open'` 先看 aria-expanded；`waitItems`、`viewNow` 每 100 毫秒轮询最多约 1.2 秒；`close()` 聚焦触发元素、按一次 Escape、照实返回；日期 `need_day`。
  - `tools/PlaywrightMcpClient.java`：`need_day` 的话改清楚（作为失败结果）；`needs_confirm` 让模型自己点「确定」。
  - `harness/context/Conversation.java`：`LatestPage.base` 留住弹出层之前的整页；最后一条用户消息之后的模型回复带 metadata `reasoningContent`（Spring AI 发成 `reasoning_content`）。`harness/runtime/AgentLoop.java` 一行改用 `LatestPage.next`。`harness/context/PopupView.java` 说明改准确、加 `isView`。
  - `harness/runtime/LoopDetector.java`：恢复到 C016 之前（与 HEAD 一致）。
  - `harness/policies/SubmitWords.java`、`SubmitGuard.java`：确认类改软边界；问提交 / 投递的对话框里的确认类按钮仍硬拦。`tools/BrowserTools.java` click 说明和 purpose。
  - `agents/ApplyAgent.java`、skill `date-picker`（v3）、`apply-form`（v3）、`repeatable-entries`（v2）；`domain/AgentRun.java`、`persistence/ModelSettingsFile.java` 注释。
  - 测试：`SubmitGuardTest`（改提交类那组，加两条：确认类交给模型、提交对话框里的确定仍拒绝）、`FakeFormScriptTest`（Confirm 在夹具页上仍拒绝）。
  - 文档：`plan/coding/03-features/C016-dropdowns.md`「实跑后修正（2026-10-25）」、`plan/coding/02-core-agent/C006-fix-real-run.md` 第 18 条、`plan/master/workflow.md` 守卫说法、`plan/master/agent-runtime.md` 7.3 第 6、7 条。
- 验证：
  - `backend/` 下 `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -B -q clean verify`：退出码 0（一次）。
  - DeepSeek 思考模式的发回规则：查官方文档 `https://api-docs.deepseek.com/guides/thinking_mode`（2026-10-25）；Spring AI 2.0.1 发 `reasoning_content` 的行为：读本机 Maven 仓库里的源码 jar 核实。
  - 改过的页面脚本在本地 headless 页面上的检查：未运行。
  - 真实 Chrome、真实模型：未运行。
- 偏差：`need_day` 作为失败结果返回（报告不把它记成填好）；确认类多算了「OK」。

## 2026-10-25 · C016 实跑后修正：补做本地页面脚本检查

- 指令：核对上一条 C016 实跑后修正的六项，只修真问题；补做「改过的脚本在本地 headless 页面上的检查」（上一条记为未运行）。不提交。
- 结果：
  - 核对：下拉脚本（不看透明度、轮询、aria-expanded、关不掉照实说、输入后按弹出层自己的可见性重找）、`LoopDetector` 与 HEAD 一致、要到日给年月返回 `need_day`、确认类按钮软边界 / 提交对话框里仍硬拦（「确 定」+「确认提交申请？」、全角经 NFKC）：与说明一致。
  - 修 1：`need_day` 后 antd3 面板还开着、盖住日期框，问完用户再设完整日期时脚本点不到日期框（Timeout 3000ms，`ant-calendar-input` intercepts pointer events）。`tools/PageScripts.java` `DATE` 加 `closePanel()`（返回 `need_day` 前按一次 Escape，结果带 `closed`）；`tools/PlaywrightMcpClient.java` 话改成「之后按 Escape 关上了」；skill `components/date-picker` 同步。
  - 修 2：弹出层视图说明「页面除了这个弹出层没有变」不准（连续弹出层、新键入的字、不带选项的新部分都看不到）。`harness/context/PopupView.java` `NOTE`、`agents/ApplyAgent.java`、skill `apply-form` 改成：整页快照还在、ref 能用，之后别的变化没列出，要看用 `browser_snapshot`。
  - 文档：`plan/coding/03-features/C016-dropdowns.md`「实跑后修正」验证一段换成实际结果和这两处修正。
- 验证：
  - 本地页面检查（scratchpad，代理执行）：`chrome-headless-shell`（Playwright 缓存 153）`--remote-debugging-port=9333 --user-data-dir=<scratchpad>/profile`；`python3 -m http.server 8765 --bind 127.0.0.1`；`node drive.mjs`（stdio 起 `@playwright/mcp@0.0.83 --cdp-endpoint http://127.0.0.1:9333 --snapshot-mode none --timeout-action 15000 …`，`browser_snapshot` 取 ref，`browser_run_code_unsafe` 跑 `PageScripts.code(...)` 原样生成的代码）。修之前 11 个用例 10 个符合预期，「need_day 之后同一页设完整日期」报错；修之后 11 个都符合预期：antd3 淡入下拉一次选中且关了；没找到 / 有歧义返回选项且关了；Element 两层级联选中；重画容器的搜索下拉（search true / false）选中；antd3 要到日给 `2026-12` 翻到 12 月不点日、`need_day`、面板关上；随后同一页和新页面给 `2026-12-15` 都 `set`；原生 date 给 `2026-12` → `need_day`、给 `2026-12-15` → `set`。没有连用户的 Chrome（9222）；自己起的 Chrome、HTTP 服务、MCP 进程都已结束。
  - `backend/` 下 `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -B -q clean verify`：退出码 0（一次，635 个测试）。
  - 真实 Chrome、真实模型：未运行。
- 偏差：`need_day` 时面板最后是关上的（决定里只说翻到那个月、不点日）；原因见上面修 1。

## 2026-10-25 · C019 申请状态（字段清单 + 计划）

- 指令：用户（2026-10-25）要每次投递一份「全局状态 + plan」，模型每步在上面读和更新；进度清单放工作台侧栏；先找已有但没用上的状态信息复用。先写计划 `plan/coding/03-features/C019-application-state.md`，之后用户改为写完直接一次施工，计划里列的 master 改句算确认。不连用户的 Chrome，不重启 8080 上的应用，不提交。
- 结果：
  - 新 `domain/ApplicationState.java`（阶段、计划、字段清单、决定）替换 `AgentRun.plan`；`AgentRunStore` 把旧记录的 `plan` 读成 `state.steps`。
  - `update_state` 替换 `update_plan`（旧名照旧接受）；填写工具字段参数加可选 `field`（`FieldIntent`、`BrowserTools`）。
  - `HarnessLoop`：填写成功 / 读回对得上 / 只读框没填 / 选不中的选项 / `need_day` / C 级交给用户 / 上传，自动记进字段；报告字段行从字段清单生成（`FillReportWriter.asRecorded`），`Draft` 只剩附件、未解决、守卫记录；`finish` 补「申请状态：」开头的未解决。
  - 上下文：新 `harness/context/StateView.java`；`Conversation` 把申请状态接在最后一条消息末尾；`FillContext` 前面的上下文去掉计划；`ApplyAgent` 工作方法改写。
  - `ApplicationRunner`：回答问题后等它的字段回到待填；每轮存盘后发 `changed`。`ApplicationStatus.plan` → `state`；`Views` 步骤说明。
  - 前端：`JobPage.tsx` 右栏「计划」换成「进度」清单；`api.ts`、`chat/process.ts`、`styles.css`。
  - 测试：新 `ApplicationStateTest`、`AgentRunStoreTest`、`StateValueTest`，`FillReportWriterTest` 加一条；`ApplicationLlmManualCheck` 一行。
  - 文档：`plan/coding/03-features/C019-application-state.md`（新，含实际结果）、`dev-plan.md` 一行、`plan/master/agent-runtime.md`（7.1 恢复、7.2 工具、7.3 开头和第 2、5、6 条、7.7 报告一行）、`plan/master/workflow.md`（逐页填写、关卡 B 两行）。
- 验证：
  - `backend/` 下 `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -B -q clean verify`：退出码 0（一次；surefire 654、failsafe 1，都通过）。
  - `frontend/` 下 `npm run build`：退出码 0（只有原来的包体积提示）。
  - 基线（申请 2026-10-24-010 的 `run-log.jsonl`，只统计数字）：整个申请 96 次模型调用、335 万 token；主要那次运行 61 次调用，每次输出平均 1969、中位 2496 token，40 次超过 1500，相邻调用中位间隔 16.5 秒。
  - 真实 Chrome、真实模型：未运行。
- 偏差：申请状态存在运行记录里，没有另开文件；新开始的申请会换掉上一次的填写报告（以前会留着旧的字段行）。

## 2026-10-27 · C017 macOS App + 数据位置由用户选

- 指令：用户同意 C017 计划，写回开发文档后施工；Dock 图标用仓库根目录 `icon.png` 裁出的图案。不提交。
- 结果：master 回写 `base.md`（部署只做本机 App）、`memory.md`（storage 位置由用户选、取值顺序）、`ui.md`（首次引导第 0 步、设置页「数据位置」）。代码见 `plan/coding/03-features/C017-macos-app.md`「实际结果」。
- 验证：
  - `backend/` 下 `mvn -B -q clean verify`：退出码 0。`frontend/` 下 `npm run build`：退出码 0。
  - `macos/build-app.sh`：退出码 0，产出 `macos/build/JobApply.app`（335MB），jar 内含 `application-macapp.yml`，`AppIcon.icns` 已生成；`codesign -v --deep` 通过；自带 `java -version` 25.0.5、`cli.js --version` 0.0.83。
  - 用 App 自带运行时和 jar 在 18080 起后端（临时 `JOBAPPLY_APP_CONFIG`）：`/` 200 text/html；`GET /api/app/storage` 返回 `needsChoice: true`、`source: default`；启动后没有建出 `~/Library/Application Support/JobApply`。另有子代理在 18080 上验证了 check（空 / 已有数据 / 相对路径）、PUT 写 `app.yaml` 后重启 `source: app-config`、环境变量覆盖。
  - 从 Finder 双击 App（窗口、桥、选文件夹、重启、退出无残留进程）：未运行（8080 可能被开发版占用）。409（运行中切换）：未运行。真实 Chrome、真实模型：未运行。

## 2026-10-27 · C017 App 端口改 8088

- 指令：本机 8080 被 Docker 占着，App 端口改 8088 或 8089。
- 结果：`application-macapp.yml`（port、allowed-origins）、`macos/JobApply/main.swift`、C017 计划改成 8088；开发版仍 8080。
- 验证：`macos/build-app.sh` 退出码 0。双击 App：未运行。

## 2026-10-27 · C017 App 产物挪到 appBuild/

- 指令：App 产物放仓库根目录 `appBuild/`、默认不上传，删掉原来的 `macos/build/`。
- 结果：`build-app.sh` 输出改为 `appBuild/JobApply.app`；`.gitignore` 的 `macos/build/` 换成 `appBuild/`；删除 `macos/build/`；C017 计划同步。
- 验证：`macos/build-app.sh` 退出码 0；`git check-ignore appBuild/JobApply.app` 命中。

## 2026-10-27 · C017 打包时产出 dmg

- 指令：发布用 dmg，加进打包脚本。
- 结果：`macos/build-app.sh` 加第 7 段，产出 `appBuild/JobApply-macos-arm64.dmg`（App + 「应用程序」快捷方式）；C017「不做」去掉 dmg、给别人分发，加 Intel Mac。
- 验证：`macos/build-app.sh` 退出码 0，dmg 215MB；`hdiutil verify` 退出码 0；`git check-ignore` 命中。挂载后拖装、双击：未运行。

## 2026-10-27 · C017-fix App 连不上 Chrome

- 指令：用户在 MacBook Air 上用打包 App 测试，远程调试开着仍报连不上，Chrome 无弹窗。
- 结果：根因是 macOS 拦下 App 读 Chrome 的 `DevToolsActivePort`（Air 上 TCC 日志 `kTCCServiceSystemPolicyAppDataDetailed`）。`--cdp-endpoint` 改成 `ws://127.0.0.1:9222/devtools/browser`（两个 yml）；`PlaywrightMcpClient` 认 `ECONNREFUSED`。见 `plan/coding/03-features/C017-fix-chrome-port.md`。
- 验证：`mvn -B -q clean verify` 退出码 0；`macos/build-app.sh` 退出码 0。Air 上真实连接：未运行。

## 2026-10-27 · C017-fix 关闭 App 卡住

- 指令：用户反映关闭 / 退出 / 强制退出 App 时卡一会儿；先定位，之后同意保留已做的修改。
- 结果：根因是 Spring Boot graceful shutdown 等满 30 秒（`/api/events` 一直开着）；强制退出会留下后端进程。`server.shutdown: immediate`；外壳管道 + `config/ShellWatch.java`。见 `plan/coding/03-features/C017-fix-chrome-port.md`「追加」。
- 验证：`mvn -B -q clean verify` 退出码 0；`macos/build-app.sh` 退出码 0；18080 上实测关闭约 0 秒（改前 30 秒）、外壳管道关闭后后端立即退出。双击 App 后关闭：未运行。

## 2026-10-28 · C020 整体精炼计划（A–E 拆分）与工作包 A（提示词与 Skills 升级）

- 指令：把 C020 完整设计写入 `plan/coding/04-refinement/C020-holistic-refinement.md`（拆分为 A–E 五个工作包，同步更新 `plan/master/` 和 `dev-plan.md`），本步只由 Gemini 4 执行工作包 A（系统提示词、Skills 正文设计与升级），工作包 B–E（`PageScripts`、`PlaywrightMcpClient`、`MemoryNotes`、`FillContext`、`SessionLoop`、`HarnessLoop`、`ApplicationRunner`、`UploadArea`、`ResearchTools`、`JobPage.tsx`）留给其他编码代理施工后回来统一验收。
- 结果：
  - 计划与文档：新建 `plan/coding/04-refinement/C020-holistic-refinement.md`（明确 A–E 五个工作包的边界、文件与步骤）；更新 `plan/master/agent-runtime.md` §7.3（扁平资料客观上下级归属由模型按常识补全、快捷工具诚实返回下一层选项或面板内容、上下文含 `companies/<company>.md` 与 `review/lessons.md`）、`plan/master/workflow.md`（恢复状态表）、`dev-plan.md` 与 `plan/README.md` 索引。
  - 工作包 A（系统提示词与 7 个预置 Skill 升级）：
    - `backend/src/main/java/jobapply/agents/ApplyAgent.java`：`METHOD` 增加「资料与表单的映射（扁平资料 → 层级与分类推理，客观归属自己按常识补全，主观意愿或未知更细层级才用 `ask_user`）」与「批量填写与快捷工具（未完或未找到时按返回选项/面板调整或分步点完）」；精简 `SITUATIONS` 中与组件 skill 重复的细碎操作，保留所有核心边界并指向对应 skill。
    - `backend/src/main/java/jobapply/agents/ResearchAgent.java`：`SITUATIONS` 的「标签页」与 `browser_tabs`（`list` / `select`）对齐。
    - `backend/src/main/resources/playbook/skills/apply-form/SKILL.md`（v3 → v4）：去掉已作废的「确认过的按钮会记进站点记录以后直接放行」，改为仅对当前页该按钮生效一次；增加扁平资料到层级/分类控件的客观推理原则。
    - `backend/src/main/resources/playbook/skills/components/cascader-city/SKILL.md`（v2 → v3）：增加「扁平资料补全上级路径」「选不中或还有下一列时」「虚拟滚动与分组城市」。
    - `backend/src/main/resources/playbook/skills/components/readonly-select/SKILL.md`（v2 → v3）：重构 `## 选不中时` 为三类（列表是上级分类时直接选归属项、同级异名选语义一致项、确无一致或归属才 `ask_user`）。
    - `backend/src/main/resources/playbook/skills/components/date-picker/SKILL.md`（v3 → v4）：覆盖原生框、可键入框、日历网格面板（含头部下拉与纯箭头翻页）、滚轮式多列面板四种形态与分步回退。
    - `backend/src/main/resources/playbook/skills/components/file-upload/SKILL.md`（v1 → v2）：去掉已作废的「先请用户选文件」，对齐 C008-fix（已有合适文件如文件 1 直接带 `target` 上传）。
    - `backend/src/main/resources/playbook/skills/components/repeatable-entries/SKILL.md`（v2 → v3）与 `resume-parse/SKILL.md`（v1 → v2）：对齐 `update_state`（申请状态）并精炼正文。
- 验证：
  - `backend/` 下全量单元测试（654 个测试）：653 个直接通过，唯一 1 个 `JobApplyApplicationTest` 因本机 `~/Library/Application Support/JobApply/app.yaml` 指向用户桌面目录而与默认 `../storage` 断言不同；加上 `JOBAPPLY_APP_CONFIG=/tmp/jobapply-nonexistent-app.yaml` 隔离本机 `app.yaml` 后跑 `PlaybookSeederTest,SkillCatalogTest,JobApplyApplicationTest` 退出码 0（注：当前机器 `/opt/homebrew/opt/openjdk` 为 24.0.2，测试以 `-Djava.version=24` 运行通过）。
  - 真实 Chrome、真实模型及工作包 B–E：未运行（待其他编码代理完成 B–E 后统一验收）。
- 偏差：无。

## 2026-10-28 · C020 工作包 C、D（Memory 闭环、按需 stage、对话 browser_tabs）

- 日期：2026-10-28
- 指令：在分支 `C20_upgrade` 实现 C020 工作包 C 与 D（一起改 `SessionLoop`）；不改 `.cursor/plans/`、`.env`、工作包 A/B/E 文件。
- 结果：
  - C：`MemoryNotes.lessons()`；`FillContext` 注入公司记忆与 lessons（保留 9 参数构造）；`HarnessLoop` 每步注入并让 `read_resume` 读 `base/originals/`、`stagedPaths` 缺副本时 `UploadArea.stage`；`SessionLoop` 上下文附 lessons。`UploadArea` / `ApplicationRunner` 未改。
  - D：`ResearchTools` 开放 `BrowserTools.tabs()`；`SessionLoop` 处理 `browser_tabs` list/select，委托 `BrowserSession.tabs` / `select`。
- 验证：本机无 `openjdk@25`。用 `JAVA_HOME=/opt/homebrew/opt/openjdk@24/...` 在 `backend/` 执行 `mvn -B -q -DskipTests compile` 退出码 0。`mvn -Dtest=BrowserToolsTest,BrowserToolPolicyTest test` 因 `release version 25 not supported` 未能跑测试。全量 `mvn clean verify`、真实 Chrome：未运行。
- 偏差：对话 Agent 上下文只注入 `lessons.md`，没有按公司注入 `companies/<company>.md`（Session 没有当前岗位公司）。

## 2026-10-28 · C020 合龙验证（verify-all）与 `PageScripts.java` 修复

- 日期：2026-10-28
- 指令：在 `C20_upgrade` 上对已落地的 C020 A–E 做合龙验证：本机 JDK 25 下 `mvn -B clean verify`、无头 Chromium 5 项本地 DOM 脚本测试、`frontend` `npm run build`；修复无头 Chromium 验证抓到的 `PageScripts.java` 问题并更新文档（更正此前别的机器写的「本机无 Java 25」记录）。不读不写 `.env` / `personal.env`。
- 结果：
  - 核对 A–E 全部改动均已落地；
  - 修复 `backend/src/main/java/jobapply/tools/PageScripts.java` 在无头 Chromium 实测中抓到的两处问题：
    1. `DATE` 的 `selectHeader`：`h.evaluate((s, {want, part}) => ..., {want, part})`（Playwright `evaluate` 要求多参数包装成单对象；同时仅在选项值变化时才调用 `selectOption`、分别判断年/月避免 `||` 短路漏选月份，并从可见 `<select>` 选中项兜底读取 `hy` / `hm`）；
    2. `SELECT`：当 `need_more_levels` 保持浮层开启后，下一轮再调 `browser_select_option` 时，`if (info.expanded)` 以及点击后 `!popup` 回退 `pre: []` 认回已开着的浮层。
- 验证：
  - 本机 JDK 25（`25.0.5`，`JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`）下在 `backend/` 执行 `mvn -B clean verify`（带 `JOBAPPLY_APP_CONFIG=/tmp/jobapply-nonexistent-app.yaml` 隔离本机桌面 `app.yaml`）：**退出码 0**（surefire 655、failsafe 1 全部通过）；修改 `PageScripts.java` 后复跑 `mvn -B -q test-compile` 与 `mvn -B -q test -Dtest=PlaywrightMcpClientTest,BrowserToolsTest,PlaybookSeederTest,SkillCatalogTest` **退出码 0**。
  - 无头 Chromium（`/Users/yigewang/Library/Caches/ms-playwright/chromium_headless_shell-1243/chrome-headless-shell-mac-arm64/chrome-headless-shell`）跑 `PageScripts.SELECT` 与 `PageScripts.DATE` 5 项本地 DOM 测试（① 虚拟滚动 `wheel` 下拉；② 3 层级联先传 2 层返回 `need_more_levels` 保持浮层不关 → 紧接着再传 3 层选中并关闭；③ 多列滚轮日期面板 `need_day` 与完整年月日选择；④ 日历头部原生 `<select>` 选择年月；⑤ 日历头部不可点 `labelDead` 改用左右箭头连续翻页）：**5 项全部通过**（临时测试脚本跑完已删除）。
  - `frontend/` 下 `npm run build`：**退出码 0**。
  - 真实网站与真实 Chrome 端到端冒烟：**未运行**。
- 偏差：无。未提交 git。

## 2026-10-28 · C021 工作包 A（Prompts、Skills、工作台简历匹配闭环与三级 Memory 贯通）

- 日期：2026-10-28
- 指令：由当前模型（Gemini 4）亲自完成 C021 中涉及 System Prompts、Skills 具体内容、以及工作台 Agent Loop / 三级 Memory 闭环（`ApplyAgent`、`ResearchAgent`、`apply-form/SKILL.md`、`ControlTools`、`ResumeMatch`、`HarnessLoop`、`FillContext`、`ApplicationRunner`、`SessionLoop`、`ResearchTools`）的工作包 A；将剩余的后端侧栏视图（工作包 B：`Views.java`）与前端三级导航栏及按钮改造（工作包 C：`frontend/`）拆分为清晰的待执行工作包更新到侧边栏 Plan 文件与仓库计划文档。
- 结果：
  - `ControlTools` 新增 `list_resumes`、`choose_resume`，扩展 `read_resume` 同时支持 `key`（原件键）与 `file`（上传区编号）；
  - `ResumeMatch` 新增面向工作台单 Job 的 `chooseInWorkspace`（支持 `asset`、工作台 `attachment` 登记新原件、`skip_ranking` 未给 `asset` 时自动选最新原件，`ranking` 可选）；
  - `FillContext` 增加 `profileSummary`（`base/profile.md`）与 `jobNote`（`job.md` 的 `lead_note`），打通三级 Memory 到工作台上下文；
  - `HarnessLoop` 实现工作台两阶段懒打开浏览器（`shouldDeferBrowser` + `ensurePage`）：启动时若未选定简历先不打开 Chrome 页面，让模型在工作台内调 `read_jd`、`list_resumes`、`read_resume` 输出排名表与修改建议并 `ask_user`；用户确认后调 `choose_resume` 当场将简历 `stage` 为当前工作台「文件 1」，随后首次调用浏览器工具时自动打开岗位页填表；
  - `ApplicationRunner.start` 按是否已有 `resume_choice` 生成两阶段或直接填表的开场提示词；`SessionLoop.startApplication` 去掉未排简历的拦截阻断；
  - `ApplyAgent`、`ResearchAgent`、`ResearchTools` 与 `apply-form/SKILL.md`（v4 → v5）全面对齐工作台两阶段闭环；
  - 新建 `plan/coding/04-refinement/C021-workspace-flow-and-sidebar.md`，并更新侧边栏 Plan 文件 `/Users/yigewang/.cursor/plans/c021_工作台内闭环投递与三级侧栏_1b03f3ef.plan.md`，标明工作包 A 已完成，给出工作包 B（后端 `Views.java`）与工作包 C（前端 `frontend/`）的精确并行施工契约。
- 验证：
  - 在 `backend/` 下使用 JDK 25（`JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`）执行 `mvn -B clean test`（带 `JOBAPPLY_APP_CONFIG=/tmp/jobapply-nonexistent-app.yaml`）：**退出码 0，Tests run: 655, Failures: 0, Errors: 0, Skipped: 0**。
  - 工作包 B、工作包 C 及真实浏览器端到端冒烟：**未运行**（待工作包 B、C 并行施工后合龙验证）。
- 偏差：无。未提交 git。




## 2026-10-28 · C021 合龙验证（工作包 A / B / C）

- 日期：2026-10-28
- 指令：C021 的三个工作包（A 后端提示词/循环/三级 Memory 由 Gemini 4 做、B 后端 `Views.workspaces`、C 前端三级侧栏与三处直连工作台）已由不同代理分别完成，做合龙验证 + 文档收尾：① 只读核对 `Views.WorkspaceSummary` 与 `frontend/src/api.ts` 的 `WorkspaceSummary` 字段名与可空性，有差异只在前端对齐后端；② 只读核对 `HarnessLoop` 工作包 A 的改动是否自洽（可变 `files` 与 `current()` 存盘、`recordUploads` / `stagedPaths()`、`progress.accept(current())` 是否带上 `agentrun.files()`），能清楚定位的小问题就修；③ 后端 `mvn -B clean verify` 与前端 `npm run build` 都要退出码 0；④ 把结果写进 `plan/coding/C021-*`、`dev-plan.md`、`plan/README.md`、`dev-log.md`、`dev-recoding.md`。不改 `.cursor/plans/`，不读不写 `.env` / `personal.env`，不提交 git。
- 结果：
  - **契约核对**：六个字段 `id` / `company` / `title` / `application` / `bucket` / `label` 名称、顺序、可空性完全一致（`application` 两侧都可空，`bucket` 前端收窄成 `JobBucket` 联合类型，不影响 JSON）。无需改动。
  - **工作包 A 自洽性核对**：`Round.files` 是 `new ArrayList<>(run.files())`；`readResume`、`recordUploads`、`stagedPaths()`、`inputs()` 四处都已改用实例 `files`，`rg` 核对后 `run.files()` 只剩构造处一处；`current()` 为 `run.withFiles(files).withProgress(...)`，`ApplicationRunner` 的 progress 消费者每轮 `runs.save(progressed)`，所以 `choose_resume` stage 出的文件 1 下一轮即落盘。`shouldDeferBrowser()` 四个条件与 `ensurePage()` 懒打开一致。
  - **修了两处**：
    1. `HarnessLoop.chooseResume`：`uploads.stage(...)` / `JobMaterials.write(...)` 抛出的 `RuntimeException` 原来会冒到 `AgentLoop` 把整次运行记成失败；改成只让这次工具调用返回「失败：…」，并把 `files` 的修改挪到复制和写 `materials` 都成功之后（原来先改 `files` 再写盘，写盘失败会留下不一致的清单）。顺带去掉不再使用的 `UncheckedIOException` import。
    2. `frontend/src/pages/SessionPage.tsx`：工作包 C 把右侧岗位栏原来的「详情」入口整个替换掉了，`canStart === false` 的岗位（没有岗位链接、在提交中等）没有任何入口；恢复「详情」链接，与「开始投递」并列。
  - **工作包 B 实际落地**：`Views.java` 里 `record WorkspaceSummary`、`SessionSummary` 末尾的 `workspaces`、`session(s, runner)` 默认空列表的重载、`static workspaces(sessions, jobs)` 分组函数；`Views.project(...)` 先算一次分组表再塞进去；新增 `backend/src/test/java/jobapply/api/ViewsWorkspacesTest.java`（5 项）。
  - **工作包 C 实际落地**：`api.ts` 的 `WorkspaceSummary` + 可选 `workspaces`；`App.tsx` 三级列表与 `projectFor()` 反查；`SessionPage` 两个按钮 + 保留「详情」；`JobPage` 删掉「发消息给 Session 再跳回」改成原地 `startApplication`（多一个 `onChanged` prop）；`Dashboard.tsx` 候选池直接 `startApplication` 后 `go`；`styles.css` 新增 `.side-workspaces` / `.side-ws-dot` / `button.btn-xs`。
- 验证：
  - 后端：`backend/` 下 `JOBAPPLY_APP_CONFIG=/tmp/jobapply-nonexistent-app.yaml JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -B clean verify` → **退出码 0**；`Tests run: 660, Failures: 0, Errors: 0, Skipped: 0`（surefire，含新增 `ViewsWorkspacesTest` 5 项）+ `MemoryLedgerScenarioIT` **1** 项通过。
  - 过程中出现过一次 `DeleteSessionProjectTest.deletesExactlyTheSessionOrProjectAndRefusesWhileAnApplicationRuns` 失败：快照里多出 `playbook/skills/research/verify-official/.SKILL.md*.tmp`，是 `AtomicFiles` 的 `Files.createTempFile` 临时文件被 `TestStorage.snapshot()` 的 `Files.walk` 撞见。该测试文件自 C013（`a62e5a6`）起未改动；单独连跑 10 次全通过，改完代码后重跑全量也通过 → 判为**既有 flaky，不是 C021 引入**，本步不改。
  - 前端：`frontend/` 下 `npm run build` → **退出码 0**（`tsc -b && vite build`，只有 Vite 的 chunk > 500 kB 提示）；`node_modules` 已在，未重跑 `npm ci`。
  - 真实 Chrome、真实招聘站端到端冒烟与侧栏观感：**未运行**。
- 偏差：无（两处修复都是这次核对中定位到的、会影响流程的小问题）。未提交 git。

## 2026-10-28 · 任务 3：打包目录重组与 Windows/Linux 构建脚本

- 日期：2026-10-28
- 指令：完成打包目录重组与各平台构建脚本：① 目录顺序迁移（`appBuild/` → `releases/`，`macos/` → `appbuild/` 规整为 `appbuild/macos/`）；② `.gitignore` 忽略 `appbuild/` 与 `releases/`；③ 新增 Spring 就绪自动打开浏览器组件 `OpenBrowser.java` 及桌面配置文件 `application-desktop.yml`；④ 编写跨平台打包调度脚本 `appbuild/build.sh`、`appbuild/README.md`、`appbuild/linux/build.sh`、`appbuild/windows/build.ps1`；⑤ 脚本语法用 `bash -n` 检查；不跑真实打包、不改 `frontend/`、不改 `api/` 或 `workflow/`、不改 `.env`/`personal.env`、不 `git commit`。
- 结果：
  - 目录重整：`appBuild/` 安全重命名为 `releases/`（内含已有的 `JobApply.app` 和 dmg）；`macos/` 下的内容整体规整到 `appbuild/macos/`，原 `build-app.sh` 重命名为 `build.sh` 并修正上层目录与产物输出路径为 `../../releases/`。
  - `.gitignore`：明确添加并忽略 `appbuild/` 与 `releases/`。
  - 后端 Bean 与配置：
    - 新建 `backend/src/main/java/jobapply/config/OpenBrowser.java`，受 `@ConditionalOnProperty(name = "jobapply.app.open-browser", havingValue = "true")` 条件控制，监听 `ApplicationReadyEvent`，具备 Headless 和 Desktop API 双重防护，异常仅记录日志不阻断启动；
    - 新建 `backend/src/main/resources/application-desktop.yml`，固定端口 8088，映射 `static-locations: file:${JOBAPPLY_RESOURCES:...}/web/`，允许 8088 跨域，MCP command 指定为 `${JOBAPPLY_RESOURCES:...}/node/node`。
  - 打包脚本：
    - `appbuild/build.sh`：自动识别操作系统（Darwin/Linux/Windows）并转调对应构建脚本；
    - `appbuild/linux/build.sh`：使用 jpackage 打包 `--type app-image`，主类配置 `org.springframework.boot.loader.launch.JarLauncher`，传递 `-Dspring.profiles.active=desktop` 与 `-Djobapply.desktop.resources`，安装 MCP，Node 命名为 `node`；
    - `appbuild/windows/build.ps1`：PowerShell 构建脚本，执行 jpackage `--type app-image` 并完成资源部署与 MCP 安装；
    - `appbuild/README.md`：详细记录各平台架构方案、依赖要求与构建命令。
- 验证：
  - 脚本语法：`bash -n appbuild/build.sh`、`bash -n appbuild/macos/build.sh`、`bash -n appbuild/linux/build.sh` **全部退出码 0**；
  - 后端编译与单元测试：`JOBAPPLY_APP_CONFIG=/tmp/jobapply-nonexistent-app.yaml JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -B -q test` **退出码 0**，全部通过；
  - 真实打包与真实平台构建：**未运行**（按指令只改目录和脚本，不执行打包命令）。
- 偏差：无。未提交 git。

## 2026-10-28 · 任务 2 后端：工作台可删除接口与调度

- 日期：2026-10-28
- 指令：实现任务 2 的后端部分（工作台可删除接口与调度）：
  ① `ApplicationRunner` 加 `discard(jobId, deleteJob)` 方法与 `discarded` 防护集合（非阻塞强制停止、清理申请 memory、`deleteJob: true` 时挪入回收站并重建台账、发送事件）；
  ② `AgentRunStore` 加 `delete(jobId)`、`FillReportWriter` 加 `delete(projectId, jobId)`、`JobAnswers` 加 `clear(projectId, jobId)`、`Trash` 加 `moveJob(projectId, jobId)`；
  ③ `ApplicationController` 加 `DELETE /api/jobs/{jobId}/workspace` 接口与 `DiscardRequest`（处理 body 为空与缺省 `deleteJob: false`）；
  ④ 新建 `DiscardWorkspaceTest.java` 覆盖两种模式并运行指定验证命令；
  ⑤ 不碰 `frontend/`，不改 `Views.java`，不读写 `.env` / `personal.env`，不提交 git。
- 结果：
  - `AgentRunStore.java`：新增 `delete(String jobId)`，原子删除 `layout.agentRun(jobId)`；
  - `FillReportWriter.java`：新增 `delete(String projectId, String jobId)`，删除 `layout.fillReport(projectId, jobId)`；
  - `JobAnswers.java`：新增 `clear(String projectId, String jobId)` 与 `removeAnswersSection(String body)`，移除 `job.md` 正文中的 `## 本次答案` 一节并完整保留其余各节与 frontmatter；
  - `Trash.java`：新增 `moveJob(String projectId, String jobId)`，将 `projects/<p>/jobs/<id>` 移动到 `.trash/<时间>-job-<id>/`，并调用 `new TrackerWriter(layout, clock).writeProject(projectId)` 与 `writeGlobal()` 重新生成项目台账与全局台账；
  - `ApplicationRunner.java`：
    - 新增 `Discarded(String id, String mode)` record；
    - 新增 `discarded` 并发集合；在 `discard(...)` 时标记，在 `execute` 的 progress 消费者中阻断存盘与 changed 通知，在 `work(...)` 中检查并在退出时跳过 `settle`、`writeFinal`、`runs.save` 与 `reportToSession`，在 `start(...)` 时清理；
    - `discard(jobId, deleteJob)` 实现非阻塞出队、标记停止、移出 live、触发调度、通知等待线程、释放浏览器标签与会话（`browser.release(jobId)`），清理 runs、fillReport、JobAnswers、递归删除 `layout.uploadsDir(jobId)`，可选移入回收站，并发送 `events.changed(null, jobId)`；
  - `ApplicationController.java`：类路径调整为 `@RequestMapping("/api/jobs/{jobId}")`，子接口平滑适配；新增 `DiscardRequest(Boolean deleteJob)` 与 `DELETE /api/jobs/{jobId}/workspace` 接口；
  - `DiscardWorkspaceTest.java`：验证 `deleteJob: false`（只删工作台，`job.md` 与台账保留，运行记录/报告/答案/上传删除）与 `deleteJob: true`（移入回收站，台账移除，全局台账更新）两种模式，并验证空 body 与默认值处理。
- 验证：
  - 在 `backend/` 下运行：
    `JOBAPPLY_APP_CONFIG=/tmp/jobapply-nonexistent-app.yaml JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -B test -Dtest=DiscardWorkspaceTest`
    **退出码 0，Tests run: 2, Failures: 0, Errors: 0, Skipped: 0**。
  - 全量单元测试 `mvn -B clean test` 全部通过（退出码 0）。
- 偏差：无。未提交 git。

## 2026-10-28 · C022 工作台后续与 RAG

- 指令：投递之后工作台接着开「后续」段（新的一段对话，界面上仍是同一个工作台），后续工具从 Session 搬进工作台；加入本地 RAG（ONNX，第一次用时下载到 `storage/model/RAG/`）；用 multi-agent 开发。
- 结果：两个子代理各在自己的 worktree 里施工（A：RAG，B：后续段），合并进 C22 分支后，由主代理在 `WorkbenchTools` 接上 `search_experience`。四份 Master（tracking、memory、agent-runtime、ui）已回写。细节见 `plan/coding/04-refinement/C022-workspace-followup-and-rag.md`「施工记录」。
- 验证：
  - 合龙后，JDK 25 下 `mvn -B clean verify` 退出码 0，`npm run build` 退出码 0。
  - 工作包 A 用一次性检查跑过真实模型下载和检索（检查代码未提交）。
  - 真实模型下的后续段对话、真实 Chrome 只读查看、设置页在浏览器里的显示：未运行。


## 2026-10-28 · C015 README 更新

- 按用户十条写作要求重写项目介绍，补齐工作台内简历匹配、申请状态、后续对话、本地 RAG 和桌面构建入口；删除过时行为说明与重复框架比较。
- 只修改 README、C015 和开发索引/记录；未修改应用代码、依赖、Master 或边界。
- 验证：对照当前源码与依赖；README 本地链接、图片路径、代码围栏配对与 git diff --check 通过。外链在线复查、Markdown / Mermaid 渲染、应用构建与测试未运行。


## 2026-10-28 · C015 压缩 C022 介绍

- 将 README 后续对话与 RAG 各缩为一段，删去实现枝节，使篇幅与其他能力相称。
- 本地链接、围栏配对与 git diff --check 通过；应用测试和渲染未运行（纯文案）。


## 2026-10-31 · C023 UI 视觉打磨与左右栏拖拽

- 指令：按已确认的 C023 一次做完 A（视觉打磨）和 B（左右栏可拖拽）；用户决定：主按钮保持黑色、底部说明折成「?」、左右栏都能自由拉伸（只设小下限，上限按窗口推，主区至少 400px）。
- 结果：只改 `frontend/`：`App.tsx`、`styles.css`、`pages/SessionPage.tsx`、`pages/JobPage.tsx`、`status.tsx`、`chat/icons.tsx`、`dashboard/Dashboard.tsx`、`dashboard/Weekly.tsx`、`dashboard/Board.tsx`、`dashboard/RecentSessions.tsx`，新增 `ResizeHandle.tsx`。没加依赖，没碰后端。细节见 `plan/coding/04-refinement/C023-ui-polish-resizable.md`「施工记录」。
- 验证：
  - `npm run build` 退出码 0。
  - 无头 Chromium + 草稿目录 mock 接口（虚构数据，不进仓库）截图，用 CDP 鼠标 / 键盘事件拖动手柄：拖宽、拖到上下限、同页刷新后保留、工作台右栏单独记、双击复位、键盘调宽、窗口变窄重新夹住，量到的宽度都符合计划。
  - 用户在真实浏览器 / macOS App 里看：未运行。


## 2026-10-31 · C023-C 候选池先公司再地点、模型归并公司名

- 指令：用户「按照建议开发」：待定 1–3 取建议（集团 / 事业群 / 工作室并成一家、行内留原文；打开候选池时懒触发；不做编辑界面，映射在 `cache/lead-groups.json` 可删重建）；待定 4 已确认（分组只在 Project 内，全局页 Project → 公司 → 地点）。三处 Master 改动用户确认。
- 结果：后端新增 `memory/LeadGroups.java`、`api/LeadGroupController.java`（`POST /api/lead-groups`），改 `StorageLayout`、`SettingsConfiguration`；前端改 `api.ts`、`dashboard/LeadPool.tsx`、`dashboard/Dashboard.tsx`、`pages/Global.tsx`、`styles.css`。Master：`ui.md` 候选池第 1 条与辅助模型用途、`memory.md` 的 `cache/`。细节见 `plan/coding/04-refinement/C023-ui-polish-resizable.md`「施工记录（C）」。
- 验证：
  - `backend/` 里 JDK 25 `mvn -B clean verify`（`JOBAPPLY_APP_CONFIG=/tmp/jobapply-nonexistent-app.yaml`）退出码 0（单元 662 + 场景 1）。
  - `frontend/` 里 `npm run build` 退出码 0。
  - 无头 Chromium + 草稿目录 mock（虚构数据、mock 的归并接口，含失败情形）截图：Project 分层、公司组、地点小节、失败提示和「重试」都按计划显示。
  - 真实模型归并、用户在浏览器里看：未运行。


## 2026-10-31 · C023-D 表格横向滚动、工作台对话样式与 Session 一致

- 指令：用户要 ChatGPT / Claude 那样的 Markdown 表格（列有合理最小宽度、放不下时在自己的外框里横向滚动），以及工作台对话和 Session 一个样子（现在像微信气泡）。
- 结果：只改 `frontend/src/styles.css`（`.mdtable`、`.md table / th / td`、`.askblock`），不改 JSX、不加依赖、不碰后端。原因：`.md` 的 `overflow-wrap: anywhere` 让表格列最小宽度变成一个字，所以表格从不滚动、被挤成一字一行；工作台本来就和 Session 共用 `MessageList` 等组件，像微信是因为几乎每轮的 `ask_user` 提问都是填色黄框，和右边的用户气泡交替。细节见 `plan/coding/04-refinement/C023-ui-polish-resizable.md`「D」。
- 验证：
  - `frontend/` 里 `npm run build` 退出码 0。
  - 无头 Chromium + 草稿目录 mock（虚构消息，不进仓库）前后对照 20 张（Session、工作台投递段、后续段；默认栏宽和拖宽后约 460px 消息列；浅色、深色）：9 列表之后在外框里横向滚动（796px 内容 / 458–678px 外框），每列至少 84px；窄表不拉满；提问不再是填色框。
  - 用户在浏览器里看：未运行。

## 2026-10-31 · plan/coding 按开发阶段分文件夹

- 指令：用户「把本地的 coding 下的 plan 根据开发进度节点分级到 3-4 个文件夹内，命名根据 Cxx+<具体开发实现内容> 改名（如有需要）」。
- 结果：`git mv` 把 `plan/coding/` 分进 `01-foundation/`（C000–C003）、`02-core-agent/`（C004–C006，含各 fix 和 C004-B / C004-C 历史稿）、`03-features/`（C007–C019，含 `c011_smoke_observe.py`）、`04-refinement/`（C020–C023）。改名 6 份：`C000-process` → `C000-dev-process`、`C004-B-harness-review` → `C004-B-fixed-graph-harness`、`C004-C-agent-rework` → `C004-C-agent-rework-draft`、`C006-agent` → `C006-agent-live-steps`、`C006-front` → `C006-front-chat-ui`、`C014-move-forward` → `C014-interviews-memory-cover-letter`。仓库内所有引用（`dev-plan.md`、`dev-log.md` 的路径、`plan/README.md`、`plan/master/*`、各 Coding Plan 的相对链接、后端 / appbuild 注释里的路径）改到新位置；计划正文没改。`plan/README.md` 加文件夹说明，`dev-plan.md` 加一行。
- 验证：草稿目录脚本检查全部 Markdown 相对链接：整理前 0 条断链，整理后 0 条；`grep` 不再有指向 `plan/coding/C0..` 旧位置的路径（只剩 dev-log 里一条早已不存在的历史文件名 `C005-profile-import.md`，原样保留）。未提交。

## 2026-10-31 · C023-E 工作台右栏：进度与报告分层折叠

- 指令：用户「现在是 plan/report 的形式，能不能拆分层次折叠的方式处理」（右栏「进度」和填写报告）。
- 改动：`frontend/src/pages/JobPage.tsx`（新增 `Fold` / `FieldMark` / `FieldNote` / `splitReport` / `countItems`；右栏各块用同一折叠头；进度分「当前阶段 / 计划 / 字段（按区域第二层）/ 决定和站点规律」；报告按 `## ` 拆成第二层折叠）、`frontend/src/styles.css`（折叠、步骤点、字段行样式；`pre.report` 不折行、横向滚动；删 `.sidesec`、`.progress p`、`ul.checklist`）。计划 `plan/coding/04-refinement/C023-ui-polish-resizable.md` 加 E 节和施工记录；`dev-plan.md` C023 行改为 A–E。不碰后端。
- 验证：`frontend/` 里 `npm run build` 退出码 0。无头 Chromium + 草稿目录 mock（虚构数据）前后截图：右栏 340 / 540px、浅 / 深、全部展开、原文模式；原文 `pre` 在 340 栏 `scrollWidth` 1064 > `clientWidth` 305（横向滚动）。用户在浏览器里看：未运行。未提交。

## 2026-10-31 · C024 投递失败 / 不投了 + 手动写原因

- 指令：用户确认 C024 的 7 个问题（两种分开存、任何未结束阶段都能记、都能撤销、原因必填、是不是失败由用户决定、Session 不加工具只留记录、Dashboard 两种放一起），要求一次建完，含 `FollowupRunner.open` 的小 bug。
- 结果：结局加 `apply_failed`、`gave_up`；`job.md` 加 `outcome_reason`、`closed_at`、`reopen_to`；事件加 `reopened`。`JobLedger.recordOutcome` / `reopen`；`POST /api/jobs/{id}/outcome`、`POST /api/jobs/{id}/reopen`，`/not-submitted` 的不投了改走同一条路；记下 / 撤销都往 Session 写一条系统通知（`ApplicationRunner.noteToSession`，不唤醒模型）。工作台两段加 `record_outcome`（`WorkbenchTools`，新 `harness/runtime/SessionNote`），`ApplyAgent`、`FollowupAgent` 提示词各一段。`FollowupRunner.open` 改为投递过才有后续段。台账 `applications.md` 加「结束原因」，csv 加 `outcome_reason`、`closed_at`。前端：原因框 `OutcomeDialog`（工作台「更多」两项、停在提交前的「不投了…」共用）、工作台结束行（结局 + 原因 + 时间 + 撤销）、Dashboard KPI「没投成 / 不投了」和新面板 `Outcomes.tsx`、申请总表 / 看板显示原因，「投递」不含 `apply_failed`。Master 回写：`tracking.md`、`memory.md`、`workflow.md`、`ui.md`、`agent-runtime.md`。计划 `plan/coding/04-refinement/C024-manual-outcome-reason.md` 标已确认并写施工记录；`dev-plan.md` C024 行改状态。已有测试 `ViewsWorkspacesTest` 跟着 `JobView` 改参数。
- 验证：`backend/` 里 `JAVA_HOME=…openjdk@25… JOBAPPLY_APP_CONFIG=/tmp/jobapply-nonexistent-app.yaml mvn -B clean verify` 退出码 0（Tests run: 662 + IT 1，Failures 0、Errors 0）；`frontend/` 里 `npm run build` 退出码 0。一次性台账测试（跑完已删）：候选记投递失败 → 撤销回 `lead:new`；投递后不投了 → 后续段仍在 → 撤销回 `applied`、重排查看；空原因被拒。草稿目录 mock（虚构数据）+ 无头 Chromium 截图 12 张（`shots-c024/`）。没启动连真实 storage 的 Spring，没调真实模型，没碰用户的 Chrome。真实模型调用 `record_outcome`：未运行。用户本机试：未运行。未提交。
- 偏差：计划原建议的「不投了按阶段映射到 skipped / not_submitted / withdrawn」按用户决定改为单独的 `gave_up`；`not_submitted` 只留给旧记录（界面上也算「不投了」，但没有 `reopen_to` 不能撤销）。所有结束（不只是这两种）都写 `outcome_reason`、`closed_at`。工作台投递段锁定提示顺手改用中文阶段名。

## 2026-10-31 · C024-fix 状态用词分领域、Failures、时间线一份一条

- 指令：用户确认 C024-fix 计划并给出 5 个问题的答案（Q4：候选跳过与投递失败 / 不投了合成一个「Failures」；Q5：已投递卡片仍写「已投递」；时间线改为每份申请一条圆角长条、点开看详情；其余按计划建议），要求一次建完、不提交。
- 结果：
  - 后端：`FollowupRunner.statusOf(Job)`；`Views.JobView`、`WorkspaceSummary` 加 `followup`（`ProjectController`、`JobController` 传入）；Dashboard 事件另带 `lead:skipped`；`AgentRun.DONE` / `ApplicationStatus` 注释写清是投递段结束；`HarnessLoop` finish 结果改「这轮填写结束，交给用户审阅。」；`ViewsWorkspacesTest` 跟着构造参数改。
  - 前端：`status.tsx`（字、`labelText`、`runChip` / `RunChip` 替换 `ApplicationChip`、`DROPPED_OUTCOMES`、`FOLLOWUP`）、`metrics.ts`（`lane`、`skippedAt`）、`Board.tsx`（一行卡片、>6 折起、不含 dropped）、`Failures.tsx`（替换 `Outcomes.tsx`，放看板下面，三类合一）、`Timeline.tsx`（重写）、`AppsTable.tsx`（RunChip、dropped 最后一组）、`Weekly.tsx`（`submitted`）、`FollowUps.tsx`、`LeadPool.tsx`（不投→跳过）、`Dashboard.tsx`（候选池不列跳过、「Failures n」入口、`onTab`）、`Global.tsx`、`ProjectPage.tsx`、`App.tsx`（侧栏点 + 中文悬停）、`JobPage.tsx`（`done` 时右栏状态行）、`api.ts`、`styles.css`。
  - Master 回写：`tracking.md`（「三个领域的状态」、看板 / 时间线 / 每周节奏 / Failures）、`ui.md`、`agent-runtime.md` 7.1、`memory.md`（skipped 的说法）。计划标已确认并写施工记录；`dev-plan.md` C024-fix 行改状态。
- 验证：
  - `backend/` 里 `JAVA_HOME=…openjdk@25… JOBAPPLY_APP_CONFIG=/tmp/jobapply-nonexistent-app.yaml mvn -B clean verify`：第一次退出码 1（`DeleteSessionProjectTest` 1 个失败，有申请在跑时删 Project 的快照比较，时序相关、未碰相关代码）；原样重跑退出码 0（Tests run: 662 + IT 1，Failures 0、Errors 0）。
  - `frontend/` 里 `npm run build` 退出码 0。
  - 草稿目录 mock（虚构数据：已投递 + done、你在提交 + done、后续段在跑、投递后投递失败、不投了、旧 not_submitted、3 个跳过的候选、被拒、岗位下线、20 多天的事件、简历选择、多轮）+ 无头 Chromium 截图 22 张（`shots-c024fix/`，浅 / 深）：看板已投递卡片无「已结束」，准备中卡片不再两个同义 chip；投递失败 / 不投了 / 跳过只在 Failures 和总表最后一组；时间线一份一条、展开有详情；侧栏后续段在跑闪蓝、Failures 红点、正常结束灰点，悬停是中文。
  - 没启动连真实 storage 的 Spring，没调真实模型，没碰用户的 Chrome；mock 和无头浏览器已停。用户本机试：未运行。未提交。

## 2026-10-31 · 打包 App 显示旧界面（C024-fix 第 12 节）

- 现象：重新打包（14:46）后 App 里 Dashboard 仍是 C023 之前的样子。
- 查到：打包里的 `web/`（`index-OiAtFJ6m.js`）是新的；jar 不含 `static/`；无其它 App 副本；外壳端口被占时报错不复用。起打包后端在 18088 curl：`/` 返回新 `index.html`，只带 `Last-Modified`、无 `Cache-Control`。判断是 WKWebView 启发式缓存了旧 `index.html`（本机 `~/Library/Caches/local.jobapply`、`~/Library/Logs/JobApply` 都不存在，用户运行 App 的那台机器 / 账号未直接查看）。
- 改：`application-macapp.yml`、`application-desktop.yml` 加 `spring.web.resources.cache.cachecontrol.no-cache: true`；`appbuild/macos/JobApply/main.swift` 新增 `loadUI()`，用 `.reloadIgnoringLocalCacheData` 加载界面（启动、「重新加载」）。`appbuild/` 被 `.gitignore` 忽略。
- 验证：`appbuild/macos/build.sh` 退出码 0（14:56，App + DMG）；起新包后端（18088，跑完已停）：`/` 与 `/assets/*.js` 都带 `Cache-Control: no-cache`，`/` 引用 `index-OiAtFJ6m.js`，JS 里有 Failures。在 App 窗口里实际打开：未运行。未提交。

## 2026-10-31 · C024-fix 旧记录显示核对（真实 storage）

- 指令：用户问 C024-fix 会不会让以前的记录在全局页 / 看板上显示错或被当成失败。
- 做法：storage 复制到草稿目录（排除密钥文件）；HEAD 后端在草稿目录单独构建、起在 18080（`--jobapply.storage.root` 指向副本），Vite 在 5199（临时配置，用完已删）；内置浏览器看全局 / Project Dashboard、申请总表、候选池、侧栏、7 个工作台、3 个 Session，对照 `GET /api/jobs`、`GET /api/projects/2026-season3`。
- 结果：38 个 Job 全部解析，后端日志无解析错误；19 个申请：看板准备中 5、已投递 11、已结束 2（岗位下线），Failures 2（旧 `not_submitted` 1、跳过候选 1），候选池 18，没有漏掉或重复的。已投递处处只写「已投递」，没有「已结束」；岗位下线不进 Failures；时间线 11、每周 6 + 5、待追踪 8 都对。没有后续段运行记录。读完后副本与原 storage 无差异。没发现要修的错位，未改代码。
- 验证：`frontend/` 里 `npx tsc --noEmit` 退出码 0；没改代码，`mvn -B clean verify`、`npm run build`：未运行（另一路在用 `backend/target`）。服务器已停。未提交。

## 2026-10-31 · C025 评测施工

- 指令：用户确认 C025 计划，「现在开始开发」，一次做完；新增决定：预算每轮 20M、总 200M token（跨命令累计）；固定站点由用户测试时发来，现在不跑真实站点；19 次真实运行的对错由代理标注。在 `C25-benchmark-cases` 分支上改，不提交。
- 结果：
  - 后端：`config/EvalProperties`、`persistence/EvalSettingsFile`（`settings/eval.yaml`）、`workflow/EvalMode`、`api/EvalSettingsController`、`observability/EvalOptions`、`observability/EvalRecorder`；`StorageLayout` 加 `evalYaml()`、`evalDir()`；`application.yml` 加 `jobapply.eval.enabled: false`；`ApplicationConfiguration` 传 `EvalMode`；`ApplicationRunner` 开始申请时定下评测设置、`HarnessLoop.eval(...)`；`HarnessLoop` 每步记录、装拦截、结束写 meta，消融 1 / 2a；`PlaywrightMcpClient` 加拦截模板、`OwnerBrowser` / `BrowserSession` 加 `interceptSubmits` 和 `diagnoseTimeouts`（消融 2b）；`ExperienceIndex.keywordOnly`（消融 3）；`ApplyAgent.systemPrompt(boolean)` / `tools(boolean)`、`FillContext.messages(..., skills)`；`observability/package-info` 改说明。
  - 前端：`pages/EvalSettings.tsx`（设置 →「模型」页签的「评测」卡片）、`Settings.tsx`、`JobPage.tsx`（评测提醒一行）、`api.ts`、`styles.css`。
  - 命令行：`backend/src/test/java/jobapply/harness/runtime/BenchmarkManualCheck.java`（annotate / run / rescore；组名 retrieval 是实验 3；预算记在 `storage/eval/budget.json`；虚构资料目录第一次 run 时生成）。不新增测试类，不进 verify。
  - 本地（被 git 忽略）：`storage/eval/baseline/` 19 张标注单（标注者：代理）和 `summary.md`、`storage/eval/cases/` 26 张题卡、`storage/eval/report.md`。
  - 文档：C025 写回施工记录和三条新决定；新 `plan/reference/benchmark-and-ablation.md`（只有聚合数字和归因）；`dev-plan.md` C025 行。
- 验证：
  - `backend/` 里 `JAVA_HOME=…openjdk@25… mvn -B clean verify` 退出码 0（Tests run: 662 + IT 1，Failures 0、Errors 0）。
  - `frontend/` 里 `npm run build` 退出码 0。
  - `BenchmarkManualCheck` 的 `annotate`、`rescore` 跑过：19 张标注单、`baseline/summary.md`、`report.md` 产出。基线聚合：19 次运行（13 次有字段），字段 504 条（对 364 / 错 7 / 漏填 42 / 不确定 91），正确率 98.1%、召回 88.1%，ask_user 精确率 96.2%（51 / 53），漏问 4，token 合计 73,801,227。
  - 拦截脚本用 node 加假的 page 对象跑过逻辑；`EvalRecorder` 用虚构值在 jshell 里跑过一次。真实 Playwright 上的拦截：未运行。
  - 开关关着时不写 `storage/eval/`：读代码路径确认（`HarnessLoop.eval` 为 null 时不建记录、不调拦截）；施工后 `storage/eval/` 下没有 `app/`。
  - 虚构资料目录已生成：`run` 跑到选题卡就停（没有「能复现：是」的题卡），没有连浏览器、没有调模型；虚构简历用 `AssetAnalyzer.text` 读回，3 个超链接都在。
  - `run`（真实模型、真实站点）、三组消融、13 个夹具安全底线、用户本机试设置页开关：未运行。未提交。

## 2026-10-31 · C025 纠正：评审模型 = 用户配置的模型

- 指令：用户纠正，benchmark 里需要模型判断的地方都用用户在 JobApply 里配置的模型，不由 Claude 判；19 次运行重新标注，计入预算，旧标注单直接覆盖。
- 查到：`storage/settings/models.yaml` 的主模型是 provider `my-model-gate`、模型 `open-deepseek-v4.1-flash`（只看了这两个字段，没读 `.env` / `personal.env`）。`run` 模式本来就用 `ModelRegistry.main()`，没有写死的模型。
- 改：`BenchmarkManualCheck` 的 `annotate` 加模型评审（`Judge`、`JUDGE_PROMPT`、证据拼装、JSON 解析、每批 20 个字段、每批前查预算并记进 `budget.json` 的 `annotate` 组；参数 `judge`、`runs`；模型出错时标注单写「评审没判完」并带原因）；字段清单带上值（只发给评审模型，不写进标注单）。
- 验证：`annotate -Djobapply.eval.overwrite=true` 跑完 19 张，2 次运行模型请求失败（Request failed、timeout），把每批从 40 改成 20 后用 `-Djobapply.eval.runs=` 重判，全部判完，没有空格。评审共 243,980 token。`rescore` 新基线：字段 504（对 337 / 错 39 / 漏填 41 / 不确定 87），精确率 89.6%、召回 80.8%，ask_user 精确率 71.2%（37 / 52），漏问 34。`mvn -B clean verify` 退出码 0（662 + IT 1，Failures 0、Errors 0）。未提交。

## 2026-11-01 · C025 再纠正：做题 = 用户配置的主模型，判题 = Claude Code

- 指令：用户再次纠正（以此为准）：做题是 JobApply 用设置页配置的主模型跑投递；判题是 Claude Code 读证据逐格判，不在 app 里调用模型判。上一条（用户配置的模型当评审）是转达错误，改回来。
- 改：`BenchmarkManualCheck` 撤掉 `Judge`、`JUDGE_PROMPT`、证据拼装、JSON 解析、评审计预算和 `judge` / `runs` 参数；`annotate` 回到只生成标注单；预算逻辑只留给 `run`。
- 判题：重新生成 19 张空标注单，由 Claude Code 逐格判（标注者：Claude（Claude Code）），覆盖上一轮模型评审的标注。`rescore` 新基线：字段 504（对 364 / 错 7 / 漏填 42 / 不确定 91），精确率 98.1%、召回 88.1%，ask_user 精确率 96.2%（51 / 53），漏问 4。
- 做题模型：19 次运行的记录里没有模型名（`runs/*.json`、`run-log.jsonl`、`events.jsonl` 都没有这个字段），记「记录中无模型名」。
- 预算：上一轮错误评审用掉的 243,980 token 留在 `storage/eval/budget.json` 的累计里（`annotate` 组），是一次错误评审的消耗，不扣除。
- 验证：`mvn -B clean verify` 退出码 0（662 + IT 1，Failures 0、Errors 0）。未提交。

## 2026-11-01 · C025 固定站点：跑之前的只读检查（停在连接）

- 指令：用户给了固定测试站点（一个招聘站的简历档案页，用户已在自己的 Chrome 里打开），要求接手这个标签页开始测试；档案页的保存类请求必须全部拦下，起点不干净或拦不住时先停下报告。
- 改：`BenchmarkManualCheck` 加 `probe` 用法（只读）：连上用户正在运行的 Chrome，按网址找到已开着的标签页、交给评测会话、取一次快照，只报有值 / 空的输入框个数和字段名、保存 / 提交类按钮，不点、不填、不装拦截。
- 结果：`probe` 连接 30 秒超时（`Disconnected`：Chrome 在等用户允许这次远程调试连接，或没有响应）；9222 端口在监听。没有读到页面，没有对站点发出任何操作。题卡和 run 都没动，等用户在 Chrome 里点允许后重跑 `probe`。
- 验证：`mvn -B test-compile` 通过；`mvn -B clean verify` 这一步未运行（只加了测试目录里的手动检查）。未提交。
- 补记（同日）：用户回复 continue 后又跑了两次 `probe`，仍是 `Disconnected`（MCP 的 `initializeServer` 等 Chrome 超时，`--cdp-timeout` 默认 30 秒，多半是 Chrome 在等这一次新连接的「允许」）。期间顺手修了命令行读 `application.yml` 时时长读不成 `Duration`（Binder 补上 Boot 的转换服务），但连接卡在 MCP 自己的 30 秒，不受它影响。没有读到页面、没有对站点做任何操作。每次命令行启动都是一条新连接，要用户在 30 秒内点允许。

## 2026-11-01 · C025 整套评测只连一次 Chrome

- 指令：不接受每次运行都让用户点一次「允许」；整条命令（只读检查 + baseline + 4 个消融组）共用一条 Chrome 远程调试连接，首次连接等待放宽到约 120 秒。
- 改：`BenchmarkManualCheck` 加 `suite` 用法：一个进程里一个 `BrowserSession`（和 app 一样一个 MCP 服务进程、一条 CDP 连接，各次运行各开一个会话），先只读检查站点，再依次跑 baseline、full-snapshots、no-skills、no-diagnose、keyword-only（`-Djobapply.eval.groups=` 可只跑其中几组），每组 `times` 次，每次运行核对全部「能复现：是」的题卡（成绩表加每张题卡一栏）。开跑前和每次运行前都只读检查一次，档案页有值就停；跑完一次后遇到验证码 / 限流、要登录、拦截没装上就停；每组 20M、累计 200M。`run` 也改用同一套 `runGroup`。命令行的 MCP 启动参数追加 `--cdp-timeout 120000`（MCP 0.0.83 支持，默认 30000），我们这边等连接放到 150 秒；app 的 `application.yml` 没改。
- 验证：`mvn -B clean verify` 退出码 0（662 + IT 1，Failures 0、Errors 0）。`suite` 未运行（等用户守在 Chrome 前再启动）。未提交。

## 2026-11-01 · C025 固定站点第一次跑整套（停在第 1 次运行）

- 指令：用户守在 Chrome 前说「开始」；跑 `suite`（只读检查 → baseline → 4 个消融组各 3 次），拦下该标签页所有非 GET 请求（`submit-pattern=.`）。
- 改：`suite` 只读检查后把页面结构写到 `storage/eval/probe.md`，连接留着等题卡标好（`storage/eval/cases-ready`），免得再连一次；组名 baseline 改成 `all-on`（`storage/eval/baseline/` 是历史标注单，这次跑出的一次运行已挪到 `storage/eval/all-on/`）。
- 只读检查：用户开着的档案页，4 个文本框都空、没有勾选，起点干净。按页面只标了 3 张通用题卡「能复现：是」（K24 结束时无待填、K25 不误提交、K26 C 级不泄露），其余页面上看不到对应区域，保持原样。
- 跑：一条连接，用户只点了一次允许。baseline 第 1 次运行 86 次模型调用、91 次工具调用、4,499,146 token，停在等用户：页面报「网络异常」、下拉 / 日期 / 勾选点了就回退、上传不显示文件，Agent 请用户确认登录，整套按「要登录」停下，没有往下跑。
- 拦截：拦下 449 个请求，全是这页自己的非 GET：日志上报 281 次、档案逐字段自动保存（`…/my/resume/info/update`）56 次、简历上传到对象存储 2 次（另有部分是同一路径的重复）；没有拦截装不上的情况，只有一步因为文件选择框开着、取拦截记录失败一次（拦截本身已装上）。C 级出现 0 次。从页面路由看没有保存请求到达站点；WebSocket 和 Service Worker 发的请求不在 `page.route` 里，看不到。
- 结论：这张档案页每改一个字段就自动保存，保存失败页面就回退；拦下所有非 GET 时表单填不下去，放行保存又会写进用户真实账号。按规则停下，等用户定。
- 预算：`budget.json` 累计 4,743,126（含之前错误评审的 243,980）。
- 验证：`mvn -B test-compile` 通过；`clean verify` 这一步未运行（只改了命令行评测类）。未提交。
