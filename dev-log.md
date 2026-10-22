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
- 结果：新增 `plan/coding/C002-memory-ledger.md`（状态：待确认），内容包括目标、不做、依赖、要改的文件、关键规则、5 个切片、测试、验收标准，以及 12 项待确认。`dev-plan.md`、`plan/README.md` 的索引行指向该文件。未写代码，未改 Master。
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
