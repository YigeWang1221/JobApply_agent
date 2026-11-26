# 归档：2026-10-23 Agent 运行时改造交接笔记

说明：本文件归档自 [agent-runtime.md](../master/agent-runtime.md) 的历史交接章节（`§0–6`、`§7.7–7.8`、`附`），仅作历史记录参考；当前生效设计见 `agent-runtime.md`。

- 对象：下一个 Claude session（交接日期 2026-10-23）。
- 先读：`AGENTS.md`「Agent 开发原则」→ 本文件 → `dev-limit.md`。其余文档需要时再查。
- 用户已定：Agent 运行时用 **Java**（Spring AI，现有技术栈）；代码已恢复到最近一次构建通过的版本（`mvn -B clean verify`、`npm run build` 都通过）。

---

## 0. 背景：为什么要改

C004-B 做出来的是「Java 写死的流程，中间加了 LLM」：
- LangGraph4j 固定的图，9 个等待点；
- 单独的登录核对 Agent；
- 打转规则一命中就强制转交；
- 每个字段必填的元数据；
- 系统替模型判断页面语言。

提示词却很薄：没讲项目经历、日期、下拉框这些情况怎么处理。冒烟时卡住的两处（登录反复判「未登录」、转交后没人告诉用户要做什么）都出在写死的流程上。还花了大量时间写测试、加守卫和确认，流程本身没有跑通。

用户要的是：一个特化的投递 Agent，**由 LLM 主持，靠提示词 + 工具 + skill 在循环里一次次调用，遇到什么页面就随机应变**，加速投递。

---

## 1. 这个 session 要交付什么

1. **研究笔记**：三个项目是怎么用简单的方式让 LLM 驱动任务的（见第 2 节）。
2. **新设计**：简短的 Master 级设计，说清 JobApply 的申请流程怎么改（见第 3 节），和用户对齐方向。
3. **对旧工作的处理清单**：每一块旧代码、旧计划是保留、改造还是删掉（见第 4 节）。
4. **一个能跑通的流程**：在夹具和用户的 Chrome 上，模型自己把一份申请从打开页面走到提交前（见第 5 节）。

不在这个 session 做：资料层的原文引用改造、完整的界面（见第 6 节，先记下，流程跑通后再做）。

---

## 2. 研究：怎么研究三个项目

### 2.1 拿到源码
- **Hermes**：本机已有 `~/.hermes/hermes-agent`（Python）。只读，不改，不读它的配置、密钥、memories、日志。
- **Deep Agents**：`git clone --depth 1 https://github.com/langchain-ai/deepagents` 到会话临时目录。
- **OpenMAIC**：`git clone --depth 1 https://github.com/THU-MAIC/OpenMAIC` 到会话临时目录。
- 只读源码，**不运行**它们的代码，不整段复制进本仓库。

### 2.2 读哪里（入口，读到需要的地方为止）
| 项目 | 入口 |
| --- | --- |
| Hermes | `run_agent.py`（主循环、迭代预算、工具执行）、`agent/prompt_builder.py`（系统提示词怎么拼）、`tools/` 下的工具注册和说明、`tools/skills_tool.py`、`agent/context_compressor.py` |
| Deep Agents | `libs/deepagents/` 下创建 Agent 的入口、`middleware/`（规划 / todo、文件系统、子 Agent、摘要、人工中断）、内置的系统提示词和工具说明 |
| OpenMAIC | `lib/orchestration/`（导演怎么决定下一个行动者、`cue_user` 怎么把控制交给用户）、Agent 提示词的构建、Action Engine |

### 2.3 每个项目都回答这几个问题
1. **循环**：一轮做什么？什么时候结束？怎么防止无限循环？
2. **工具**：工具怎么注册、说明怎么写？模型怎么知道该用哪个？
3. **skill / 知识**：经验怎么交给模型、怎么按需加载？
4. **提示词**：系统提示词由哪几部分组成？每一步给模型看什么？
5. **人在回路里**：什么时候停下来问人？怎么暂停、怎么接着做？
6. **代码管什么、模型管什么**：哪些事写死在代码里，哪些完全交给模型？
7. **状态和上下文**：对话变长以后怎么处理？跨中断保留什么？

### 2.4 写下来
- 补进 `plan/reference/research-agent-design.md` 新的一节「源码阅读（2026-10-xx）」：每个项目按上面 7 个问题各写几句，带文件路径。
- 最后一张对照表：「它们怎么做」→「JobApply 用不用、在 Java 里怎么做」。
- 那份文件里已有的初步结论（只读过文档和二手介绍）可以推翻。

---

## 3. 研究之后：重新设计流程

- 根据研究结果，写一份简短的设计，追加到本文件第 7 节，说清：
  - 循环怎么跑；
  - 给模型哪些工具；
  - 提示词怎么组织（工作方法和按情形的引导：登录、个人信息、教育、项目和经历、日期、下拉和级联、上传、开放问题、卡住时、完成时）；
  - skill 怎么用；
  - 什么时候问用户；
  - 用不用 LangGraph4j 做中断和恢复。
- **写方向，不写死细节**：不列每个类、每条规则、每个测试。
- 和用户对齐方向（一次简短的确认），然后动手。
- 如果决定不再用 LangGraph4j 的固定图，同时改 `dev-limit.md`「范围边界」里那条（要用户同意）。

---

## 4. 对旧工作的处理

研究之后按新设计逐项判断。下面是交接时的初步判断，**研究后可以改**；删之前把清单给用户看一眼。

| 旧工作 | 初步判断 | 说明 |
| --- | --- | --- |
| 模型接入、设置页（C004-A） | 保留 | `ModelRegistry`、`ModelFactory.withTools(ChatModel, …)` 直接用 |
| Playwright MCP 连接 `BrowserSession`、`PlaywrightMcpClient` | 保留 | 连接属于应用：同意一次以后一直挂着，~~断开后等用户说重连~~（2026-10-23 起：断开后下一次使用自动重连一次，连不上再等用户点「重新连接」或发消息，见 workflow.md「Chrome MCP」）。多个 Job 各用一个标签页（`browser_tabs select`）做过一版，已撤回，需要时重做 |
| 浏览器工具定义 `BrowserTools`、白名单 `BrowserToolPolicy` | 保留，可能放宽 | 按新设计调整参数（例如字段上的 `fact` 改成可选） |
| 提交守卫 `SubmitGuard`、C 级字段 `FieldClassGuard`、快照遮值 `SnapshotRedactor` | 保留最必要的 | 用户定下的线：系统不替用户点最终提交。其余守卫和确认能少就少 |
| skill：`SkillCatalog`、`PlaybookSeeder`、`SiteNotes`、预置的 6 个 skill | 保留 | 提示词里要告诉模型什么时候读哪个 |
| 填写报告 `FillReportWriter`、过程日志 `RunLog`、本次答案 `JobAnswers`、上传 `UploadArea`、`JobMaterials` | 保留 | |
| 填写循环 `HarnessLoop`、上下文 `FillContext`、控制工具 `ControlTools`、`FillAgent` 提示词 | 改造 | 变成新的 Agent 循环和提示词 |
| `LoopDetector`、`Budget` | 改造 | 打转不再强制转交，改成告诉模型；预算只当兜底 |
| 图和节点（`ApplicationGraph`、`ApplicationRunner`、`workflow/nodes/*`）、等待点和信号 | 改造或删 | 按新设计 |
| `LoginCheckAgent`、`CheckPageNode`、`WaitIdentityNode`、`FinishNode`、`PageLanguage` | 删 | 登录和语言交给模型判断 |
| 固定旧流程的测试（`HarnessLoopTest`、`ApplicationRunnerTest`、`LoginCheckAgentTest`、`PageLanguageTest`、`LoopDetectorTest`、`ApplicationScenarioIT`、`ApplicationControllerTest` 的旧用例等） | 跟着对应代码删 | 不为新流程补写同类测试 |
| 表单夹具 `backend/src/test/resources/fixtures/forms/`、假浏览器 `FakePlaywrightMcpServer` / `FakeFormScript` | 保留 | 用来让真实模型试跑，不是写断言 |
| `ApplicationLlmManualCheck`、`ApplicationManualSmoke` | 改造 | 改成新流程的试跑入口；冒烟时在每个要用户的地方直接告诉用户要做什么 |
| 前端「申请」页 | 并入以后的界面 | |
| 计划：C004-B 的流程部分、草案 [C004-C](../coding/02-core-agent/C004-C-agent-rework-draft.md) | 作废，只作参考 | C004-C 里可以参考的想法：一个 `ask_user` 管所有要用户的地方、模型自己维护计划和进度、上下文里放用户的原文、主模型网络失败时等一会儿再试 |
| C004-B 的安全边界、存储格式、skill、报告、日志部分 | 保留 | |

---

## 5. 做出一个能跑通的流程

- 先在夹具上用真实模型试跑（假浏览器）。再在用户的 Chrome 上跑一次：
  - 用远程调试模式（2026-10-23 起是默认）：打开 `chrome://inspect/#remote-debugging` 的开关，MCP 参数用默认的 `--cdp-endpoint chrome`；
  - 一个应用、一条连接跑多个夹具（`-Djobapply.smoke.form=a,b,c`），只需要用户同意一次连接。
- **算跑通**：
  - 模型自己打开页面、自己判断要不要登录、把能填的填好；
  - 遇到上传、不知道的必填项、验证码时问用户，用户回答后接着做；
  - 停在最终提交之前。
- 试跑时在每个要用户的地方，直接告诉用户该做什么，做完就继续；不要让用户盯着屏幕等。
- 改动要小、能 review：分几次做，每次说清改了什么、跑出来的结果。

---

## 6. 记下来、流程跑通后再做

- **资料用用户写好的原文**：现在导入简历时把项目和经历压成一句话，填表用的是摘要。要改成用用户自己写的原文（用引用或占位符），不改写；长度超了只裁剪。涉及 memory.md「资料导入与画像」和 C004-A-2。
- **真正的界面**：按 ui.md「界面结构」，优先做 Job 详情页作为「申请工作台」：左边是 Agent 每一步做了什么，右边是现在需要用户做什么、停止按钮。

---

### 7.7 旧代码处理清单（用户已同意）

| 旧代码 | 处理 | 说明 |
| --- | --- | --- |
| 模型接入、设置页（`ModelRegistry`、`ModelFactory` 等，C004-A） | 保留 | |
| `BrowserSession`、`PlaywrightMcpClient`、`PageSnapshot`、`SnapshotIndex` | 保留 | 连接属于应用的做法不变 |
| `BrowserTools` | 改造 | 额外参数改成可选，说明重写 |
| `BrowserToolPolicy`、`SubmitWords`、`FieldClassGuard`、`SnapshotRedactor` | 保留 | 用户定下的线 |
| `SubmitGuard`、`GuardDecision`、`FieldIntent` | 改造 | 只剩「放行 / 拒绝」；去掉软边界确认和页面状态强制停；`FieldIntent` 跟着参数变成可选 |
| `SkillCatalog`、`PlaybookSeeder`、`SiteNotes`、6 个预置 skill | 保留 | `apply-form` 的经验搬进提示词，skill 变短；去掉自动塞正文 |
| `FillReportWriter`、`RunLog`、`JobAnswers`、`UploadArea`、`JobMaterials` | 保留 | 报告的置信度等列跟着变成可选；报告的字段行从申请状态的字段清单生成（C019） |
| `HarnessLoop`、`FillContext`、`ControlTools`、`FillAgent` | 改造 | 合成新的 Agent 循环、上下文和工具（7.1–7.3）；对话存盘 |
| `LoopDetector` | 改造 | 只产出给模型的一句话 |
| `Budget`、`LoopOutcome`、`LoopState`、`HarnessProperties` | 改造 | 只剩迭代上限和用量数字；结束原因只剩几种 |
| `ApplicationRunner`、`ApplicationController`、`ApplicationStatus`、`ApplicationSignal`、`ApplicationConfiguration` | 改造 | 接口变成：开始、回复（文字 / 答案 / 文件 / 重连 / 要求修改；在跑或排队时是插话，只收文字，带答案、文件或附件时 409）、停止、状态、报告；状态只剩 running / waiting_user / ready / failed + 给用户的话 |
| `ApplicationGraph`、`ApplicationState`、`CheckpointStore`、`workflow/nodes/*`（含 `OpenPageNode`、`FillNode`、`ResumeNode`、`StageFilesNode`、`ReviewNode`、`ReconnectNode`、`FillHarness`、`PrepStateSync`、`Nodes`、`NodeIds`） | 删 | 打开页面、复制文件、报告定稿、更新 prep 状态这几件事搬进新的循环 / 运行器 |
| `LoginCheckAgent`、`CheckPageNode`、`WaitIdentityNode`、`FinishNode`、`PageLanguage`、`LoginPageDetector` | 删 | 登录、语言交给模型 |
| LangGraph4j 三个依赖（core、spring-ai、sqlite-saver） | 删 | 申请流程是唯一用到它的地方；**要用户同意改 `dev-limit.md`** |
| `ApplicationStateMachine`、`PrepState`、台账 | 保留 | 申请状态写台账照旧 |
| 测试：`HarnessLoopTest`、`ApplicationRunnerTest`、`LoginCheckAgentTest`、`PageLanguageTest`、`LoopDetectorTest`、`BudgetTest`、`FillContextTest`、`LoginPageDetectorTest`、`ApplicationScenarioIT`、`ApplicationControllerTest`、`ScriptedChatModel` | 删 | 不为新流程补写同类测试 |
| 测试：`SubmitGuardTest`、`FieldIntentTest`、`BrowserToolsTest` | 改造 | 只删掉被去掉的行为（软边界确认等）对应的用例 |
| 测试：其余守卫、持久化格式、`FakeFormScriptTest` | 保留 | |
| 夹具 `fixtures/forms/`、`FakePlaywrightMcpServer`、`FakeFormScript` | 保留 | 让真实模型试跑用 |
| `ApplicationLlmManualCheck`、`ApplicationManualSmoke` | 改造 | 新流程的试跑入口；遇到 `ask_user` 时在终端打印给用户的话，等用户回复后接着跑 |
| 前端「申请」页 | 改造（最小） | 只跟上新接口：显示给用户的话、回答框、选文件、停止；完整界面以后做 |
| C004-B 流程部分、C004-C | 作废，只作参考 | C004-B 的安全边界、存储格式、skill、报告、日志部分保留 |

### 7.8 施工顺序（方向确认后）

1. 删和改流程（7.7）+ 新循环和工具，在夹具上用真实模型跑。
2. 改提示词，按夹具结果调。
3. 用户的 Chrome 上跑一次（第 5 节）。

每一步一次小改动，说清改了什么和跑的结果。

## 附：交接时的状态

- 构建：恢复后 `mvn -B clean verify` 通过（surefire 718、failsafe 3），`npm run build` 通过。没有提交 git。
- 真实模型 + 假浏览器：13 个夹具都停在该停的地方。
- 真实 Chrome：`multi-page`、`submit-labels`、`verification` 通过；`file-slots` 没跑。
- 以前每个夹具单独起一个应用，所以每次都要在 Chrome 里同意一次连接。应用里的连接本身是一直挂着的。
