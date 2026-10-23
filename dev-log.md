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

## 2026-10-22 · C002 验收

- 日期：2026-10-22
- 指令：C002 验收通过。
- 结果：C002、`plan/README.md`、`dev-plan.md` 的状态改为「完成」；`dev-plan.md` 的停靠点改为等待 C003 起草前的 4 项决定。
- 验证：未运行（本步只改文档状态）。最近一次 `mvn -B clean verify` 的结果见上一条。
- 偏差：无。

## 2026-10-22 · 起草 C003（Playwright MCP 接通）

- 日期：2026-10-22
- 指令：开发 C003。用户已定 4 项：（1）用最小的本地 HTTP 接口告诉系统「登录好了」；（2）现在就接 LangGraph4j 的中断与 Checkpoint；（3）真实连接只做手动冒烟，由用户装扩展、选测试网站；（4）本机有的环境就用本机的，没有的再装，`@playwright/mcp` 版本起草时重查。
- 结果：按 `AGENTS.md` 先起草 `plan/coding/C003-playwright-mcp.md`（状态：待确认），内容包括目标、不做、环境、依赖、要改的文件、关键规则（图、台账衔接、登录页识别、身份核对、`sites.md`、HTTP、子进程）、5 个切片、测试、手动冒烟步骤、验收标准、3 项回写 Master、8 项待确认。`dev-plan.md`、`plan/README.md` 加了索引行。未写代码，未改 Master。
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
- 结果：C003、`dev-plan.md`、`plan/README.md`、README 的状态改为完成。workflow.md「登录状态」新增「登录与身份由模型判断」（从里程碑第 3 段起），并写明登录后不跳转的情况。新增 `plan/coding/C004-harness-guard.md`（状态：要点），写了已说定的方向（图要粗、循环交给模型；系统只管模型不能绕过的东西；登录判断交给模型；上传走 `browser_file_upload`）、起草时要处理的事、C003 留下的已知问题。`dev-plan.md` 加了索引行，并重写了停靠点。没有写代码。
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
- 结果：把 `plan/coding/C004-harness-guard.md` 从要点扩写成完整草案（目标、不做、依赖、要改的文件、关键规则、7 个切片、测试与夹具、手动冒烟、验收、回写 Master 5 项、待确认 9 项）。起草前只读查看了本机 npx 缓存里的 `@playwright/mcp` 0.0.83（README 与 `playwright-core` 源码）和 `~/.m2` 里的 Spring AI 2.0.1（javap），核实的事实写在草案「起草时核实的事实」。`dev-plan.md`、`plan/README.md` 的索引行改为「草案」。没有写代码。
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
