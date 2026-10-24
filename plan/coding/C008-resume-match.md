# C008 · 按岗位给简历排名，顺带给修改建议（里程碑第 5 段的一部分）

- 依据：用户（2026-10-23）：「需要 LLM 根据岗位匹配度列举简历的排名，然后有需要修改的顺便给出修改建议」。workflow.md「遗漏检查」第 1 项（已确认）：按 JD 选简历版本；简历**只给修改建议**，用户自己改好后上传，作为 `base/originals/` 里的新版本。
- 目标：对一个岗位，Agent 读 JD 和你登记的几份简历，给出排名（每份一句理由、缺什么），推荐一份；需要改的给具体建议。选定的那份记进这个 Job，申请填到上传位置时默认就是它。
- 定位（用户，2026-10-23）：C008 是这一轮开发的最后一步，做完基本结束开发，之后以用户真实使用和修补为主。
- 不做：系统自动改简历或生成新简历文件；匹配评分权重、额度、关卡 A 的其它内容（第 5 段其余部分）；Cover Letter。不写测试。

## 怎么做（LLM 主持，代码只给工具）

- **对话 Agent 的三个新工具**：
  - `list_resumes()`：列出原件里的简历类文件（键、文件名、说明、语言、上传时间）。
  - `read_resume(key)`：原件转成的文字（沿用 `AssetAnalyzer` 的 Tika 转换，去掉 C 级的值），长的截断并写明。
  - `choose_resume(job, asset, ranking, suggestions?)`：把排名（每份：键、名次、一句理由、缺口）、推荐的那份和修改建议写进 `job.md`（`resume_choice`），事件日志记一条。
- **什么时候做（用户定，2026-10-23）**：**每个 Job 开始申请前都匹配一次**，即使之前排过（简历可能已经更新）。对话 Agent 调 `start_application` 前先排名、在对话里给出结果；用户也可以随时要求重排。`start_application` 在这个 Job 还没有本次的 `resume_choice` 时返回提示，让模型先排名（提示，不是硬拦：用户明确说「不用排，直接投」时模型照做并记下）。从候选池点「开始申请」时同样先排：开一个 Session（或用发现它的 Session）并发出「给 X 排一下简历，然后开始申请」。模型在对话里用一张小表回给用户（名次、简历、理由、缺口），后面是修改建议；用户说「用第二份」就改选。
- **修改建议**：只针对这个 JD，写「哪一段、怎么改、为什么」，不编造经历（只能用 facts.md 里确认过的事实，提示词写明）。用户改好后在「资料与简历」里上传成新原件，再让 Agent 重排。
- **申请时**：`ask_user` 开出上传位置时，工作台的下拉框默认选中 `resume_choice` 里的那份，你仍可改选。申请 Agent 的开场写上「拟用简历：X」。
- **界面**：Job 页右栏显示「简历排名」（名次、理由、修改建议）；候选卡和工作台头部显示拟用简历。对话里的排名就是普通 Markdown 回复，不另做卡片。

## 已定

1. 什么时候排：每个 Job 申请前都匹配（见上）。
2. 哪些原件是简历：让模型按文件名和说明判断（用户没另选，按推荐；要改成手动标记时只动 `list_resumes`）。

## 怎么确认能用

- `mvn -B clean verify`、`npm run build` 通过。
- 你在本机：登记 2–3 份简历，在对话里说「给 X 岗位排一下简历」，看到排名和修改建议；说「投 X」，先看到排名再开始申请；申请到上传位置时默认选中推荐的那份；从候选池点「开始申请」同样先排名。

## 实际结果

2026-10-23 施工：

- 工具：`list_resumes`、`read_resume`（`AssetAnalyzer.text`：Tika 读前 12,000 字、去掉 C 级的值，超出写明）、`choose_resume`，实现在 `harness/runtime/ResumeMatch`，`SessionLoop` 只加分发。`job.md` 的 `resume_choice`：`asset`、`file`、`ranking[]`（key、file、rank、reason、gap）、`suggestions[]`、`at`、`session`；用户说不用排时是 `skipped`（原话）+ `at`。每次写都覆盖上一次，事件日志记一条 `resume_chosen`（`JobLedger.chooseResume`）。
- **「本次」的定义**：`resume_choice.at` 晚于原件清单里最新一份的上传时间。一个 Job 只开始一次申请线程，所以这就是「这次开始前排过」；上传了新原件就要重排。`start_application` 在没有本次排名、也没有申请线程时返回提示（不开始），带 `skip_ranking`（用户原话）时照做并记下。
- 提示词：ResearchAgent 加「简历排名」一节（每个 Job 申请前排一次、小表 + 建议、建议只用 facts.md 确认过的事实和简历原文、用户改选就再调 `choose_resume`），「开始申请」改为先排。
- 开场：`ApplicationRunner.start` 的开场消息加「拟用简历：文件名（键）」。没有改 `HarnessLoop` / `FillContext`。
- 界面：候选池「开始申请」和 Job 页「开始申请」都改成往发现它的 Session（候选池没有时新开一个）发「给 X（job id）排一下简历，然后开始申请」并跳到那个 Session；`JobView.resumeChoice`；Job 页头部和候选卡写「拟用简历」，右栏「简历排名」；上传位置标签含「简历 / resume / CV」的（或只有一个上传位置时）默认选中拟用的那份。
- 验证：`mvn -B -q clean verify` 退出码 0；`npm run build` 通过。真实模型、真实 Chrome：未运行。
