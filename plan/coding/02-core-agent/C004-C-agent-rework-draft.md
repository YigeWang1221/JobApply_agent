# C004-C · 按 Agent 方式重做：填写循环、资料层、界面（草案，待用户确认）

- 状态：**作废**（2026-10-23）：核心还是 Java 的图驱动流程，没有真正参考三个项目的源码，界面排在最后。由 [agent-runtime.md](../../master/agent-runtime.md) 取代；这里的内容只作参考。
- 起因：用户在 C004-B 冒烟时指出四个问题（见「问题」）。参考：[research-agent-design.md](../../reference/research-agent-design.md)（Claude Agent SDK、Building Effective Agents、Browser Use、Deep Agents、Hermes、OpenMAIC）；`AGENTS.md`「Agent 开发原则」。
- 会改的 Master：workflow.md（主流程、里程碑第 3 段）、memory.md（资料导入与画像、事实条目格式）、ui.md（界面结构的落地顺序）、architecture.md（模块）。每一处改动在下面标出，确认后再回写。

## 问题（用户指出，2026-10-23）

1. **流程写死了**：C004-B 是「LLM 驱动的定时任务脚本」。9 个固定等待点；单独的登录核对 Agent 和它的两个等待点；打转检测命中就强制转交；每个字段必须带 `fact` / `confidence` / `language`；页面语言由系统按 40% 判断。冒烟时卡住的两处都出在这里：登录核对反复判「未登录」，转交以后测试流程没人告诉用户该做什么。
2. **提示词没有引导**：填写的提示词十几行，没讲项目和经历条目、日期、下拉框、级联、上传后解析覆盖怎么处理；skill 有，但模型只看到名字。
3. **资料层把用户写好的简历压扁了**：导入时把简历里的项目、经历抽成一句话的事实，填表只用这些事实，用户自己写的原文没被用上（上下文里还用 `oneLine` 把多行文字压成一行）。
4. **没有真正的界面**：前端只有设置、资料和一个调试用的「申请」页；花在测试上的工作量够做一个界面了。

另外：测试文件越来越多，流程没有推进（`AGENTS.md` 已写明：出了问题先找根因、改产品代码）。

## 一、填写循环改成 Agent（改 workflow.md「主流程」「提交保护」不变）

**图**（参考 Hermes 的一个循环 + OpenMAIC 的「下一个是用户」）
```text
START → open_page → agent ⟲ ─┬→ [wait_user] ─ 用户答复 ─→ agent（选了文件时先复制原件）
                              ├→ review（报告定稿）→ [wait_review] ─ request_changes ─→ agent
                              └→ [wait_reconnect] ─ reconnected ─→ open_page
```
- 去掉登录核对 Agent 和 `check_page` / `wait_login` / `wait_identity` / `finish`：能看到申请表就直接填；要登录时模型用 `ask_user(kind=login)`。
- 五个填写等待点合成一个 `wait_user`，等什么由 `kind` 决定（login / input / materials / handoff / confirm / budget）。前端的 `waitingFor` 和信号名不变。

**交给模型的工具**（工具说明按 Anthropic 的 ACI 建议写清用法和边界）
- 浏览器工具（不变）；字段上的 `fact` / `confidence` / `language` 改成可选，写了就进报告。
- `ask_user(kind, message, questions?, slots?)`：要用户的地方都用它。
- `update_plan(plan)`：模型自己的计划和进度清单（Deep Agents 的 `write_todos`、Browser Use 的 todo），每一步放回上下文。
- `load_skill`、`finish_filling`。

**代码里只留安全边界**：硬边界按钮和回车、C 级字段不发不填（快照遮值）、工具白名单、上传路径、出现安全验证 / 网页弹窗 / 疑似已提交时停止写操作、软边界按钮拿不准时问用户、预算上限（75% 时提醒模型，到上限问用户）。

**改成反馈、不再强制转交**：被拒绝后又试、同一元素连续失败、相同操作相同结果、页面一直不变、只回文字不调工具：系统在结果里如实告诉模型「第 N 次」，由模型换办法或 `ask_user`（Browser Use：「不要重复同一个失败操作超过 2–3 次」）。

**提示词**（参考 Hermes 按节拼、Browser Use 的每步自检）：身份和目标 → 工作方法（核对上一步 → 看清这一页 → 计划 → 批量填 → 回读 → 下一步）→ 按情形的引导（登录、个人信息、教育、项目和经历、日期、下拉和级联、上传和解析覆盖、开放问题、卡住时、完成时，每节写明对应的 skill）→ 系统强制的边界。资料给中英两种文字，由模型按页面选。

**主模型失败**（用户确认，2026-10-23）：没配置 → 线程直接失败；网络原因 → 最多重试 2 分钟，期间用户可以「停止」；还不行 → 失败并给出原因。

**多个 Job**（已改，待验证）：一条浏览器连接，每个 Job 一个标签页，操作前切过去；一个 Job 等用户时，别的 Job 照样能用浏览器。

## 二、资料层：用户的原文是填写内容的来源（改 memory.md「资料导入与画像」「事实条目格式」）

**原则**：用户写好的简历和材料是原文，系统不改写、不压缩。导入只做「拆段 + 建索引 + 抽取短字段」，长文本字段**引用原文**，填表时按引用取出原文照填。

| 内容 | 现在 | 改成 |
| --- | --- | --- |
| 姓名、联系方式、学校、学位、专业、起止时间、技能名 | 抽成事实 | 不变：短字段仍然抽成事实，用户确认 |
| 项目、实习、工作经历的描述、职责、成果 | 模型抽成一句话，存进事实 / 项目卡 | 存**引用**：`{{src:<原件键>#<段落锚点>}}`，指向原件里这一段的原文；导入时把原文按段切好存一份（`base/sources/<原件键>.md`，按段落锚点分节，逐字保存），不改写 |
| 英文版本 | 模型起草译文 | 用户有英文简历时，引用英文简历的对应段落；没有时才由模型起草，标为未审阅，用户确认后才用于填表 |
| 画像（`profile.md`） | 模型生成 | 不变，只用于定位和选岗位，**不作为填表的内容** |

- 填表时：事实里的引用展开成原文；页面有字数限制时，模型只能裁剪（并在报告里标「已裁剪」），不能改写。上下文里多行文字原样保留，不压成一行。
- 用户在「资料」页能看到每个引用对应的原文段落，可以改引用、改原文（改原文就是改原件，会生成新版本）。
- 导入流程变化：清单卡 → 「按段切分 + 每段的类型（教育 / 项目 / 经历 / 技能 / 其它）和锚点」→ 用户确认切分和对应关系 → 写入。不再让模型「抽取并概括」长文本。

## 三、界面（落地 ui.md 已确认的「界面结构」）

ui.md 的结构不变（左侧 Project / Session 侧栏；全局 Dashboard、Project 主页、Session 对话视图、Job 详情、设置）。这一段先做和填写直接相关的部分，用真实接口，不是测试页：

1. **应用外壳**：左侧侧栏（Project 列表、全局、设置），主区域按选中的内容切换。
2. **Project 主页**：Dashboard（台账里每个 Job 的阶段和 prep 状态）+ 候选池（已有的 leads，先只读）。
3. **Job 详情 = 申请工作台**：左边是这条申请线程的时间线（模型每一步做了什么、计划和进度、守卫的判断，取自 `run-log.jsonl` 和 `update_plan`），右边是「现在需要你做什么」（按 `waitingFor` 显示：登录、回答问题、选文件、亲自处理、确认按钮、继续、审阅报告 / 要求修改），以及「停止」按钮。
4. **资料页**：原件列表 + 每份原件的分段原文和引用关系（配合第二部分）。
5. 现有的设置页保留；调试用的「申请」页并入 Job 详情。

示意页沿用 dev-plan 里的界面示意（虚构数据）。Session 对话视图不在这一段（workflow 里程碑第 7 段）。

## 四、清理

- **删除的类**：`LoginCheckAgent`、`CheckPageNode`、`WaitIdentityNode`、`FinishNode`、`PageLanguage`（模型自己选语言）；`ControlTools` 的 `request_files` / `hand_to_user` / `report_login` 并入 `ask_user`。
- **删除的测试**：固定旧流程的 `LoginCheckAgentTest`、`PageLanguageTest`、`HarnessLoopTest`、`ApplicationRunnerTest`、`ApplicationScenarioIT`、`ApplicationControllerTest` 里旧等待点的用例、`BrowserSessionConnectionTest`（这一次刚加的）；`FakeFormScript` 的多标签页模拟如果不再有测试用就一起删。
- **保留的测试**：只固定安全边界和存储格式的：`SubmitGuardTest`、`FieldClassGuardTest`、`BrowserToolPolicyTest`、`SnapshotRedactorTest`、各个持久化类的格式测试、`FakeFormScriptTest`（夹具自检）。
- **验证方式**：真实模型在夹具上跑（`ApplicationLlmManualCheck`，看每个夹具停在哪、用了几步）+ 一次真实 Chrome 冒烟；冒烟脚本在每个要用户的地方停下来，告诉用户要做什么。

## 五、顺序（每一步：确认计划 → 写代码 → 一次验证 → 报告）

1. 回写文档：本草案确认后，改 C004-B（标为被 C004-C 取代的部分）、workflow.md、memory.md、ui.md、architecture.md。
2. 填写循环（第一部分）+ 清理（第四部分）→ 在夹具上用真实模型跑一遍。
3. 资料层（第二部分）→ 用用户指定的一份简历走一遍导入和填表，核对长文本是不是原文。
4. 界面（第三部分）→ 用户在页面上走一遍。

## 现在代码的状态（用户叫停时，2026-10-23）

上一次全部通过的构建：浏览器连接改成「属于应用、不自动重连」之后（surefire 718、failsafe 3）。之后改了、**还没编译通过**的：
- 多标签页：`BrowserSession`（每个 Job 一页、操作前切换）、`BrowserToolPolicy`（允许 `select`）、`FakeFormScript`（模拟多标签页）、`ApplicationRunner`（等用户时不占浏览器）。
- 登录提示词：`LoginCheckAgent`（已删除）。
- 第一部分的一半：`NodeIds`、`ApplicationState`（`wait_kind`）、`ApplicationGraph`、`OpenPageNode`、`ResumeNode`、`StageFilesNode`、`FillNode`、`LoopOutcome`、`ApplicationRunner`、`ApplicationSignal`、`ControlTools`、`FillAgent`（新提示词）、`FillContext`（中英两种文字、原件原文、计划）、`FieldIntent` / `BrowserTools`（`fact` 可选）、`FillReportWriter`（置信度可选）、`AssetAnalyzer.readText`；删除了 `CheckPageNode`、`FinishNode`、`WaitIdentityNode`、`LoginCheckAgent`。`HarnessLoop` 还没改。

## 待用户确认

1. 第一部分（填写循环）的图、工具和「代码里只留安全边界」的范围。
2. 第二部分（资料层）：长文本存引用、按段逐字保存原文、填表只裁剪不改写；英文优先引用用户自己的英文简历。
3. 第三部分（界面）：这一段先做外壳、Project 主页、Job 详情（申请工作台）和资料页；对话视图留到第 7 段。
4. 第四部分（清理）：删除的类和测试清单。
5. 已经改了一半的代码：**A** 先退回到上一次通过的构建（改动存成补丁留在会话目录），等计划确认后按第五部分的顺序重新做；**B** 保留，确认后在它的基础上做。建议 A，出错时好定位。
