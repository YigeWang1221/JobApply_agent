# C015 · README 项目展示（2026-10-24）

## 用户指令与范围

按当前程序重写面向简历读者的 GitHub README：项目目的、架构、独特工程设计、技术选型演进、OpenMAIC / Deep Agents / Hermes 源码研究，以及无隐私的界面截图。用户已授权本次文档工作。

只改 README、展示图片与本次记录；不改运行时、依赖、产品设计，不连接真实招聘网站，不读取真实资料或秘密文件。截图采用已有 `plan/reference/ui-mockup.html` 的虚构数据，明确标注历史界面示意。

## 验证

核对代码与依赖、官方框架资料；检查本地链接与图片，渲染后目视检查。文档变更不重跑应用测试，未运行项如实记录。

## 实际结果

- README 重写为项目展示页：首屏定位、截图、业务能力、Mermaid 架构图、六项工程亮点、框架演进对照、源码研究版本、运行与代码入口。
- 核对当前代码与依赖；框架名称按官方文档纠正为 LangChain、LangChain4j、LangGraph、LangGraph4j、Spring AI Alibaba Graph。早期实际采用 LangGraph4j，Alibaba Graph 只记作选型研究，不编造使用经历。
- 从历史 HTML 渲染两张 PNG（Dashboard 1600×1120、申请总表 1900×1050），放在 `docs/images/`。独立临时浏览器上下文、阻止外部请求；没有连接日常 Chrome、真实网站或读取 storage。
- 目视检查两张截图：使用虚构数据，无个人隐私，无需打码；申请总表加宽后列完整。README 明确标注为历史界面原型，不冒充当前实跑截图。
- 本地链接与图片路径存在性检查、GFM 结构渲染、`git diff --check` 通过。局部 Markdown 预览用于文字和表格检查，不作为 GitHub 完整渲染证明；Mermaid 与 HTML 折叠块的 GitHub 实际渲染未运行。
- 核对代码发现 RuntimeEvents 实际为带启动纪元的内存缓冲，持久化的是对话、岗位与运行状态，README 按实际实现描述。
- `mvn verify`、`npm run build`、真实模型 / Chrome 流程：未运行（仅文档与图片变更）。
- `dev-plan.md` 增加本次索引，并删去与已实现业务矛盾的“业务行为尚未开始”。没有改 Master Plan 或产品边界。


## 第二次修改：语言、顺序与图片（2026-10-24）

用户要求参考 Agent 开源项目的 README，减少宣传式语言，按「目的 → 系统框架 → 技术 → 选型与借鉴」组织；深入代码核实参考关系并修正缺图。本次直接按用户授权继续 C015，不变更产品设计。

- 阅读 OpenMAIC、Deep Agents、Hermes README：采用具体用途开头、少量能力说明、实现入口和上手步骤；不复制其文案。
- 核对本地源码提交：OpenMAIC `dff201b49857918885cc5ebadb4a8af254b6ae9d`、Deep Agents `3e7d5faa9d275beb3d54f11266aac3c60e3d50b9`、Hermes `e5dad4ac57ad11d39fe18b1ac895c0e0ae5b6494`。对照 JobApply 的 AgentLoop、Conversation、RuntimeEvents、MemoryWriter、ApplicationRunner、BrowserSession 等；README 增加固定提交出处与落地模块链接。
- 重新组织内容：用途 → 系统框架 → 技术实现 → 选型与借鉴 → 本地运行。去掉口号堆叠和独立的六项亮点清单，保留当前能力限制。
- 图片排查：PNG 文件格式正常。上次 `/private/tmp/jobapply-readme-preview.html` 没有设置资源基址，相对地址错误地指向临时目录；上次脚本还预先把 HTML 图片转成 Markdown，未验证 README 原始写法。新预览直接渲染 README，并把资源基址设为仓库根目录。README 改标准 Markdown 图片、移除 HTML 折叠包装；架构改文本图，不依赖 Mermaid 支持。
- 验证：独立无头 Chromium（仅访问本地，阻止外网，不连接日常 Chrome）确认内嵌 PNG 加载成功、宽 1600；申请总表链接打开成功、宽 1900；目视检查首屏。本地文档链接、GFM 渲染、`git diff --check` 通过。
- 图片仍为未跟踪的新文件，发布时必须与 README 一起纳入提交。本次没有提交 / 推送；GitHub 在线渲染未运行。应用构建、模型和招聘网站流程未运行（只改文档）。


## 第三次修改：去掉开发汇报口吻（2026-10-24）

用户明确 README 只介绍实现的功能、技术、实现方法与参考项目，不写验收、测试、开发状态或门禁话术。删除原型状态段、验收与进度链接、构建验证章节、待补齐与未实现功能说明；去掉守卫式表述，保留用户亲自提交的产品行为。运行步骤与架构、源码引用保留。检查本地链接与 `git diff --check`；应用测试未运行（仅文案调整）。


## 第四次修改：恢复架构图与陈述式介绍（2026-10-24）

按用户要求恢复 Mermaid 系统架构图，展示工作区、API、共用循环、Worker 池、模型、工具、浏览器、文件和事件流。架构说明改为用户操作、执行结果及实现设计；技术章节直接陈述 Worker 池并发、消息收件箱 Steer、文件化 Memory 和 SSE 续接。全文疑问式标题改为陈述式标题，移除下拉框等零碎页面举例。检查本地链接、标题、代码围栏和 git diff --check；Mermaid 实际渲染与应用测试未运行。


## 第五次修改：以 Harness 为主体的项目介绍（2026-10-24）

用户要求消除缺少主语的叙述，并补充 Agent 核心机制。正文采用 JobApply、模型、Harness 和具体模块作为主语；新增 Agent 设计章节，说明 Round Loop、Turn / ToolCall / ToolResult、Spring AI 消息转换、system prompt 与动态上下文、工具 Schema、Skills 索引和按需加载、Memory 四层与作用域优先级、Markdown 存储及不采用向量 RAG 的原因。新增循环 Mermaid 图，保留系统架构图。

依据：直接阅读 AgentLoop、HarnessLoop、SessionLoop、Conversation、FillContext、ApplyAgent、SkillCatalog、AgentRun、AgentRunStore、ProfileResolver、ModelFactory 与 memory.md。区分 Skills 索引所在的 user 上下文与 system prompt；区分运行 JSON 和 Session JSONL；区分 Markdown 存储与向量检索。删除 README 中与现行 storage 规则冲突的可选 JGit 表述，不把旧计划中的 SQLite / Checkpoint 写成已实现能力。

检查：本地链接、代码围栏、git diff --check 通过。应用测试、Mermaid 实际渲染未运行（仅文档修改）。


## 第六次修改：补回分层 Memory 的原始选型理由（2026-10-24）

用户补充并确认：扁平相似度检索可能割裂语义不相似但逻辑上同属一组的项目 / 岗位内容；分层保存原始材料、整理资料和运行记录，上层概要通过引用支持按需追溯。该思路来自用户早期对 SillyTavern 的研究。README 据此改写 Markdown / RAG 段落并补设计来源；同步 memory.md 和研究笔记，区分用户说明、代码能力与外部源码核验。无运行时代码修改。本地链接及 git diff --check 通过；应用测试未运行。


## 第七次修改：全文主语与语句衔接（2026-10-24）

按用户要求通读并润色 README：补齐省略主语，减少单句和短段内在用户、模型、Harness、系统之间反复切换；用户操作与实现说明分段，开源借鉴统一从 JobApply 采用的设计展开。保留技术内容、图、链接与运行命令。复读正文并检查本地链接、代码围栏、标题与 git diff --check；应用测试未运行（纯文案）。

- 2026-10-24：按用户要求，为 README 两处 SillyTavern（酒馆）添加官方 GitHub 仓库链接；页面可访问，git diff --check 通过。


## 第八次修改：按当前实现重写项目介绍（2026-10-28）

用户授权按十条写作要求更新 README。本次沿用 C015，仅修改 README 与开发记录，不改应用代码、Master 或产品边界。

- 主线调整为岗位调研、工作台简历匹配与填写、独立后续对话、跨工作台经验复用；补充 ApplicationState、Skills、本地 ONNX RAG 与桌面构建入口。
- 对照 AgentLoop、HarnessLoop、FollowupLoop、Conversation、StateView、ExperienceIndex / Sources、依赖文件与 C021 / C022 记录；修正“Session 推荐简历”“思考内容不回传”“不采用向量检索”等过时叙述。
- 采用项目和具体模块作主语，删除重复框架对比、无关选型与内部进度入口；用工作台归属、后续上下文分段和 ONNX 运行库下载解释实际取舍。
- 保留开源参考固定提交出处与本项目实现链接；SillyTavern 按作者提供的设计启发描述。现有图片仍注明历史原型，不作为当前界面截图。
- 验证：README 本地链接与图片路径存在性、代码围栏配对、git diff --check 通过；全文复读完成。外链在线复查、Markdown / Mermaid 实际渲染、应用构建与测试未运行（纯文档修改）。
