# C006-fix 第二阶段 · 之前工作里同类的结构问题

- 依据：用户（2026-10-23）：「看看之前是不是也有这样的结构性问题；先研究参考项目、先 plan，再 multi-agent 修改」。一个代理只读审查了 C006-fix-runtime 以外的代码，对照 OpenMAIC dff201b、deepagents 3e7d5fa、deep-agents-ui f6a4f34、langgraphjs c6675bf、hermes-agent e5dad4ac5。下面每条都写了位置和参考出处。
- 已并进 C006-fix-runtime（代理 B 正在做，不在本计划里）：JobLedger / SessionRepository 多个实例各锁各的，导致并发写丢更新（审查第 3 条）；工具结果的状态从中文开头猜（审查第 9 条的后半）。
- 前提：C006-fix-runtime 完成（事件日志、子任务回报、用户事实优先）。下面多数条要发事件、要回报 Session。

## 要改的（按危害排）

### 1. 申请不排队，一个 Job 在等你就挡住所有别的申请（中）
- 现状：`ApplicationRunner.requireBrowserFree`（:490–505）扫描所有 run，只要有 Job 在 `waiting_user` 就 409；`start`、`reply` 都调用它。可提示词要模型「对每个调用 start_application」。结果：「投这三个」只能开始第一个；A 停在提交前、B 在等你时，A 不能要求修改。
- 参考：langgraphjs `libs/sdk/src/types.ts:16` 的 `MultitaskStrategy`（reject / interrupt / rollback / enqueue）；deepagents `async_subagents.py:120` 的 `update_strategy: "enqueue"`；OpenMAIC `lib/server/generation/run/store.ts:815` 先入队、worker 认领。
- 改法：`start` / `reply` 一律接受，状态 `queued`，单线程执行器按顺序跑；每个 Job 用自己的标签页，浏览器互斥交给 `BrowserSession` 的逐次调用；去掉扫描 `waiting_user`。排队、开始、结束都发事件并回报 Session。去掉的补丁：`requireBrowserFree` 和两条 409 文案。

### 2. 软边界按钮：「问过一次」就按名字永久放行（小）
- 现状：`SubmitGuard`（:108–135）看不出第几步时，「下一步 / 保存 / 继续」都要先问你，大多数表单没有步骤条，所以几乎每页都问；模型问过一次 `ask_user`（问什么都行），`askedButton` 就记下，此后所有页、所有轮的同名按钮都放行，包括最后一页上直接完成投递的「下一步」（`HarnessLoop.java:396、567`；`AgentRun` 一直带着不清）。调研那边同一个判断直接拒绝，列表的「下一页」永远点不了（`SessionLoop.java:464`）。模型给的 `final_step_suspected` 只进日志。
- 参考：Hermes `tools/approval.py:572、644`（用户明确选 once / session / always / deny）、`:723` `_smart_approve`（用模型判断风险，不靠正则）；deep-agents-ui `ToolApprovalInterrupt.tsx`（对一次具体动作批准）。
- 改法（**按你的原则：不增加你要点的次数**）：
  - 判断交给模型：守卫读模型给的 `final_step_suspected`；模型不怀疑是最后一步，就放行软按钮（硬边界词照旧永远拦）。
  - 只有模型怀疑是最后一步时才停下问你，而且批准只对**这一页、这一个按钮、这一次**有效（记页面地址 + ref + 名字），不按名字永久放行。
  - 调研里软按钮放行，只拦硬边界词。
  - 去掉的补丁：`askedButton`、`pendingButton`、「用户同意后再点它，系统就会放行」那句提示。

### 3. 申请线程重启后一直显示「在跑」；进度存了两份（中）
- 现状：运行中每轮存盘写着 `running`，重启后内存里没有，但界面照样显示「Agent 正在做」，停止按钮无效，`prep_state` 卡在 preparing。Session 有「文件写着在跑、内存没有 = 中断」的推导，导入有另一套，申请没有。Agent 的进度同时存在 `AgentRun.status` 和 `job.md` 的 `prep_state`，靠 `moveTo` 同步，计数一处看一个（`Views.java:230`）。
- 参考：OpenMAIC `lib/types/chat.ts:111` `interruptActiveChatSessions`（读回时把 active 记成 interrupted）；deepagents `async_subagents.py:170` `_THREAD_TO_TASK_STATUS`（任务状态读的时候从线程状态推出来，不另存一份）。
- 改法：「在跑」只看内存，读的时候推导（一个公共判断，Session、申请、导入三处共用）；`prep_state` 里属于 Agent 进度的部分（填写中、等你回答、等你登录、失败）改成从 `AgentRun` 推出来，`job.md` 只记你和真实世界的事实（选中、停在提交前、你在提交、已投递……）。

### D 施工计划（第 1、3 条 + 第 6 条「每次运行时再取模型」）

- 排队（第 1 条）：
  - `ApplicationRunner`：`start` / `reply` 校验通过就接受，交给原来的单线程执行器排队。内存里记「排着队的」（按顺序）和「正在跑的那一个」；状态读出来是 `queued` / `running`。删掉 `requireBrowserFree`（扫描 `waiting_user` 和「浏览器正在执行…请稍后再试」两条 409）。同一个 Job 在跑或在排队时，`start` / `reply` 仍然 409（不能同时两份）。
  - 排队时停止：轮到它时不跑，记成等你（「排队时按你的要求停下了…回复后开始」）。
  - 事件：入队发 `status: queued`，开始发 `status: running`，结束照旧（`finished`）。回报 Session 照旧只在一次运行结束时（不为排队另写 note，避免对话里多出很多条）。
  - `AgentRun` 只加两个常量 `QUEUED`、`INTERRUPTED`（不加字段、不改构造，避免和 E 的改动冲突）；文件里排队和在跑都写 `running`，「排队 / 在跑 / 中断」从内存推出来。
  - `SessionRunner` 的 `start_application` 回执：排上队时写「已经排上队…轮到它时自动开始，不用再调用」，不写「稍后再试」。
  - 前端：`status.tsx` 的 `APPLICATION` / 标签加 `queued`「排队中」、`interrupted`「中断」；`JobPage` 两处定点（`queued` 也算在跑：能停止、不能回复；`interrupted` 算在等你：能回复接着做）。
- 「在跑」只看内存（第 3 条）：
  - 新 `domain/LiveRuns`：这个进程里在跑的 id 集合 + 一个判断「记录写着在跑、内存里没有 = 中断」。`SessionRunner`、`ApplicationRunner`、`ImportRunner` 都用它（各自的文案和后续处理不变：Session 显示中断、导入记为失败「整理被中断」、申请显示 `interrupted`）。
  - 申请中断时：`status()` 报 `interrupted`，`error` 写「应用重启了，这次申请被中断…回复后接着做」；停止什么都不做（和等你时一样）；回复照常恢复（文件里是 `running`，不算 READY，不加轮次）。
- `prep_state` 镜像（第 3 条后半）——只做安全的一部分：
  - 做：`Views` 的计数 `waiting`、`ready` 都读申请线程的状态（从 `AgentRun` + 内存推出来，含 `interrupted`），再排除用户已经定了的（`JobLedger.userDecided`）；原来 `waiting` 看线程、`ready` 看 `prep_state`，用户「先放着」的也算成在等你。
  - 不做（推迟）：不改 `job.md` 里 Agent 写的 `prep_state`（`moveTo` / `advancePrep` 照旧写 preparing / needs_input / failed / awaiting_review）。原因：tracker、状态机和它的测试、`list_jobs`、前端 `phaseText` / `awaitingSubmit` / `Upcoming` 都读它，改成推导要同时动这些，风险大；而且现在写的时机正好是线程状态变化的时机，除了「中断」以外两份一致，中断由 `application` = `interrupted` 显示出来。
- 每次运行时再取模型（第 6 条）：去掉缓存的 `HarnessLoop`，每次运行 `new HarnessLoop(mainModel.get(), …)`（预置 skill 的 seed 只补缺的文件，每次跑一遍没有副作用）。

必须保持不变（括号里是覆盖它的已有测试；没有的写「无自动测试，读代码核对」）：
1. 同一时间只有一个申请在用浏览器（单线程执行器不变；无自动测试，读代码核对）。
2. 系统永远不点最终提交（不碰 `SubmitGuard` / 守卫；`SubmitGuardTest`、`BrowserToolPolicyTest`）。
3. 不能开始的 Job 仍拒绝开始：不是候选也不在 prep 可开始状态、没有岗位链接、Session / Project 不存在（无自动测试，读代码核对）。
4. 正在跑的 Job 回复仍 409；排队中的同样 409（读代码核对）。
5. 停止对正在跑的有效（在当前工具调用之后停，`HarnessLoop.STOPPED`）；没在跑时什么都不做（读代码核对）。
6. 停在提交前（READY）时回复 = 要求修改，轮次加一，记 `requestChanges`（读代码核对）。
7. 一次运行结束仍 `reportToSession`，用户已定状态时不报（读代码核对）。
8. 事件照发：`started` / `finished` / 循环观察者（读代码核对；`RuntimeEvents` 不改）。
9. 用户事实优先：所有 `prep_state` 推进仍只走 `advancePrep`（`JobLedgerTest`、`ApplicationStateMachineTest`）。
10. `ApplicationStatus` 的 JSON 字段不变（只多了两个 `status` 取值），前端照旧能读（`npm run build`）。
11. `SessionRunner` 的中断推导和文案不变（读代码核对）；导入的中断记为失败不变（`ImportRunnerTest` 里「中断和恢复」）。
12. 回复的校验（问题、C 级、文件、附件）都在写文件之前，被拒绝的请求不写东西（读代码核对）。

### 4. 两个 Agent 循环各写一套，已经开始分叉（中到大）
- 现状：`HarnessLoop` 和 `SessionLoop` 各自实现循环骨架、停止、上限总结、计时、浏览器工具、守卫。已经不一致：浏览器断开时一边等你、一边自动重连；软按钮一边问、一边拒绝。C006-fix-runtime 的事件和「补中断调用」也得写两遍。
- 参考：deepagents `graph.py:1008`、`middleware/subagents.py:268`（主 Agent 和子 Agent 是同一个 `create_agent`，区别只在中间件和工具）；Hermes `run_agent.py:264`（父子同一个循环，各自的预算）。
- 改法：抽一个 `AgentLoop`（调模型、分发工具、停止、上限、发事件、补中断调用），两个 Agent 只提供工具集、上下文和结束条件。**放在第 1–3 条之后**，单独一个代理做。

### G 施工计划（第 4 条）

现状核对（2026-10-23，读 `HarnessLoop` / `SessionLoop` 当前代码）：两个 `Round.go()` 的骨架几乎逐行相同——停止检查 → 上限检查 → 调模型（`observer.thinking` → `ModelCaller.call`）→ 只回文字就结束 → 记下带调用的回复（`observer.replied`）→ 逐个工具：已结束或已停止的写「没有执行：这一次运行已经结束。」（`REJECTED`，用时 0）、计数、`observer.tool`、计时、执行、`timed`、`observer.done`、有新页面就记成最新页面 → 一条 `tool` 对话 → 有结束就结束。上限总结也是同一个形状（加一条系统说明 → 再调一次模型、不执行工具 → 回复）。补中断调用已经在 `Conversation.messages` 里，两边共用，不用再动。`HarnessLoop`、`SessionLoop` 没有单元测试（C004-D 删了 `HarnessLoopTest`），只有手动的 `ApplicationLlmManualCheck`。

**`AgentLoop<R>`**（新，`harness/runtime/`，包内可见）——deepagents 的 `create_agent`：同一个循环，主 / 子 Agent 只差工具和中间件。
- 它持有：模型、工具集（`List<ToolCallback>`，每次调模型时 `ModelFactory.withTools`）、时钟、这次运行的工具调用上限、停止信号、`LoopObserver`、对话（`turns`，从传入的历史复制）、新对话的去处（`Consumer<Turn>`：调研写 transcript；申请什么都不做，每轮由 `progress` 存盘）、最新页面（`Conversation.LatestPage`）、这次运行的调用次数。
- 它做：上面那段骨架（逐行搬过来，文字不改）、上限时的那次总结调用、工具计时和观察者事件、`Step` 及三个结果构造（`DONE` / `FAILED` / `REJECTED`，状态在产生结果处定下，C006-fix）。
- 每个 Agent 提供（`AgentLoop.Agent<R>`）：`messages(turns, latestPage)`（申请 = `FillContext`，调研 = 系统提示词 + 上下文 + 旧网页只留开头）、`responded(response)`（申请记用量和日志；调研不记）、`handle(call)`（各自的工具分发）、`sawPage(page)`（调研把页面存进 `SessionPages`、更新 `latest`；申请不做）、`answered(text, thinking)`（模型只回了文字）、`stopped()`、`limitTurn(calls)` + `summarized(text, thinking)`（上限说明和总结的文字各自的）、`roundDone()`（申请 = `progress.accept(current())`）。
- 结束：`Step` 的 `end` 从「申请 = `UnaryOperator<AgentRun>`、调研 = `boolean`」统一成 `Supplier<R>`（申请 `R = AgentRun`，在 tool 条记下、`roundDone` 之后取；调研 `R = Void`）。申请里 `current -> x(current)` 改成 `() -> x(current())`，取值的时刻和原来一样（tool 条之后）。
- 参数解析 `parse` / `text` 两边逐字相同，搬进新的 `ToolArgs`（包内可见）；`strings` 两边不同（调研接受单个字符串），不合并，各留各的；`maps` 只有申请用，留在申请。

**有意保持不同、写成参数或各自的钩子（不悄悄统一）**：
1. 调模型回来时用户已经按了停止：申请**丢掉**这条回复直接停（`waiting(STOPPED)`）；调研**记下**这条回复，后面的工具写「没有执行」，下一轮再停。`AgentLoop` 的构造参数 `dropReplyWhenStopped`（申请 true、调研 false）。
2. 停止时：申请不加对话、返回等你（`STOPPED` 是给用户的话）；调研加一条助手消息 `STOPPED`。各自的 `stopped()`。
3. 只回文字：申请记原文、给用户的话是 `strip` 后的（空时「我没有想好下一步…」）；调研记 `strip` 后的（空时「（没有想好怎么回答…）」）。各自的 `answered()`。
4. 上限：申请 `HarnessProperties.toolCallsPerRun`、说明是 `user` 条、先记 `budget` 日志；调研 40、说明是 `note` 条。总结的文字各自的。
5. **浏览器工具这次不合并**，留在各自的 Agent 里：两边的守卫（申请 `SubmitGuard.click` + 一次性批准 + C 级字段 + 重复检测 + 运行日志 + 报告；调研 `SubmitGuard.readOnly`、导航先过网址检查）、结果文字、`TabLost` 处理（申请列标签页 / 重开岗位链接；调研当成一般失败）和**断开策略**都不一样：申请断开 = 结束这次运行、等你说重连；调研断开 = 这次调用失败、清掉最新页面、告诉模型可以接着做不用浏览器的事，用户下一条消息时第一次用浏览器自动重连一次（`reconnectIfNeeded`）。`master/agent-runtime.md` 7.1 只规定了申请的「浏览器断开：等用户说重连」，没规定调研，所以两边照旧，并在两个类的说明里写明这条差别。合并浏览器分发要给每种异常、每段文字加参数，代码只会更多，风险大于收益，记进「实际结果」留待以后。

**必须保持不变**（括号里是怎么确认）：
1. 工具调用上限：申请 `toolCallsPerRun`、调研 40；到上限时的说明文字、总结调用、「回复「继续」我接着做。」结尾、空总结的兜底文字（逐字核对 + 下面的新旧对照）。
2. 停止：每次调模型前、每个工具前检查；工具之后才停；申请调模型后再查一次；已结束 / 已停止后的调用写「没有执行：这一次运行已经结束。」`REJECTED`、用时 0、照发 `observer.done`（新旧对照）。`HarnessLoop.STOPPED` / `SessionLoop.STOPPED` 文字不变（`ApplicationRunner` 引用前者）。
3. `ask_user` / `finish` 结束运行：申请的 `AgentRun` 结果（`waiting(message, questions, slots, false)`、`ready(message, submitted)`）、给模型的回执「已经交给用户，等用户回复。」「已结束，交给用户审阅。」；调研 `ask_user` 结束（新旧对照）。
4. 只回文字结束、兜底文字（新旧对照）。
5. `LoopDetector` 提醒：申请浏览器、find、拒绝、失败四处照旧（代码不动，读代码核对）。
6. 守卫：硬边界词、批准（`pendingApproval` → `ask_user` 时变成 `approval`、用一次就清、换网址就清）、调研 `readOnly`（代码不动；`SubmitGuardTest`、`BrowserToolPolicyTest`）。
7. `browser_tabs` 和 `TabLost` 处理（代码不动；`BrowserSessionTabsTest`、`BrowserToolsTest`）。
8. 快照：只有最新一份原样发、旧的省略（`Conversation` 不动；最新页面的 turn 序号和 resultId 取法不变，新旧对照）；调研旧网页正文只留开头 1500 字（代码搬动不改）。
9. 用量：申请 `modelCalls` 每次调模型（含总结那次）加一、`toolCalls` 每次执行工具加一、`tokens` 累加；运行日志 `model_call` 的 step 序号（新旧对照）。
10. 观察者 / 事件：`thinking` → `replied` → 每个工具 `tool` / `done` 的顺序和次数（新旧对照）；`RuntimeEvents`、`ApplicationRunner`、`SessionRunner` 不改。
11. 结构化的工具状态：每个结果的 `DONE` / `FAILED` / `REJECTED` 不变（新旧对照）。
12. 补中断调用：`Conversation.messages` 不改。
13. 调研：浏览器工具得到的新页面存进 `SessionPages`（时机不变：每个带页面的结果之后）；每条新对话都交给 `append`，顺序不变（新旧对照）。
14. 可重放的工具回执（`add_lead`、`update_lead`、`mark_submitted`、`record_status`、`start_application`）：代码不动。
15. 提示词、工具说明、上下文：`ApplyAgent`、`ResearchAgent`、`FillContext`、`ControlTools`、`ResearchTools`、`BrowserTools` 不改。
16. 公开接口：`HarnessLoop` / `SessionLoop` 的构造和 `run(...)` 签名不变，`ApplicationRunner`、`SessionRunner` 不改。

**怎么确认**：没有覆盖这两个循环的自动测试，按 `AGENTS.md` 不为它补测试。改之前在 scratchpad 里写一个**临时**的新旧对照程序（不进仓库）：用脚本化的假模型（按顺序回预先写好的回复，可以在某一步「按停止」）+ `FakeBrowser` 的表单夹具，跑申请（多页填写、`ask_user`、`finish`、上限、停止、调模型时停止、批准、C 级、模型只回文字）和调研（`web_fetch` 走浏览器、浏览器工具、`ask_user`、上限、停止、断开），把返回的 `AgentRun`、全部对话、观察者事件序列、运行日志、报告草稿、transcript、`SessionPages` 写成文本（时间戳、用时抹掉）。改之前跑一遍存基线，每一小步之后再跑、逐字比对；最后 `mvn -B clean verify`。

**施工顺序**（每步编译 + 对照）：① 加 `ToolArgs`，两边换用；② 加 `AgentLoop`，`SessionLoop` 换用；③ `HarnessLoop` 换用；④ 改两个类的说明、写「实际结果」。

### 5. 标签页归属靠「序号 + 网址」猜；模型不能切标签页（中）
- 现状：`BrowserSession.focus`（:177–206）三级猜法，第三级「数量没变少就信序号」会认错；归属只在内存里，重启后新开一页，做到一半的那页被丢下；模型不能用 `browser_tabs`，点「去投递」在新标签页打开 ATS 时，焦点被切回原页，模型看不到新页。**这一条是推断，没在真实 Chrome 上复现。**
- 参考：Hermes `tools/browser_tool.py:679`（每个任务一个独立浏览器会话）、`:810`（用记下的 `owner_pid` 认孤儿会话）。deepagents、OpenMAIC 不涉及。
- 改法：`AgentRun` 记最后的页面地址，接着做时按地址认回原页，认不出把标签页列表给模型选；给模型开放受限的 `browser_tabs list / select`（只能选别人没占用的）。去掉的补丁：「原来的标签页读不到了…重新打开岗位链接」分支、`focus` 的第三级猜测。

### E 施工计划（第 2、5 条 + 第 6 条「`finish` 的 `page_shows_submitted`」）

**第 2 条 · 软边界按钮**（`SubmitGuard`、`SubmitWords`、`GuardDecision`、`HarnessLoop` / `SessionLoop` 里调守卫的地方、`AgentRun`）
- `SubmitGuard.click(latest, ref, intent, approval)`，顺序：没有快照 / ref 不在 / 解析不了 / **硬边界词 → 拒绝（不变）**；软按钮：
  1. 有批准、而且是同一页（网址）+ 同一个 ref + 同一个名字 → 放行，循环随即清掉这条批准（只用一次）。
  2. 页面上看得出的硬信号：同一页还有提交类按钮，或者步骤条已经是最后一步（N/N）——只对「下一步 / 继续 / 下一页 / Next / Continue / Proceed」这类往前走的按钮，停下问用户（`AskUser`）。这两条很少见，夹具 `submitted-page`（「下一步」直接投递，同页有「提交申请」）靠它继续停在提交前。「保存 / 暂存 / 草稿」不看这两条，免得每次保存都问。
  3. 模型说 `final_step_suspected: true` → 问用户（`AskUser`，原因带模型的依据）。
  4. 模型说 `false` → 放行。
  5. 模型没给 `final_step_suspected` → 不点，结果交回模型（新的 `GuardDecision.Explain`）：「带上 purpose、final_step_suspected、evidence 再点」。不问用户。
  - 去掉「页面上看不出第几步就问」——这是几乎每页都问的来源。
- 批准：守卫要问时，循环记下这次的「页面网址 + ref + 名字」；模型接着 `ask_user` 时把它存进 `AgentRun.lastPage.approved`。回复后模型点同一页同一个按钮放行一次，用过就清；翻到别的网址（运行开始取的页、任何操作后的页）也清。去掉 `askedButton`、`pendingButton`、`withAskedButton`，以及「用户同意后再点它，系统就会放行」那句。
- 调研（`SessionLoop`）：`SubmitGuard.readOnly(latest, ref)` 只拦硬边界词和判断不了的元素，软按钮（「下一页」「加载更多」）放行。
- 这和 `workflow.md`「提交保护」第 2 条有出入：原文「拿不准、像是最后一步、或者没给出这些说明 → 暂停问用户；用户确认过的按钮记进 `playbook/sites/<host>.md`，以后在这个站点上直接放行」。改成：没给说明 → 交回模型补上（不问用户）；用户的批准只对这一页这个按钮一次（不记站点、不按名字放行）。硬边界一条不改。施工后把这段回写进 `workflow.md`（本计划已由用户批准）。

**第 5 条 · 标签页归属**（`BrowserSession`、`BrowserToolPolicy`、`BrowserTools`、`ApplyAgent`、`HarnessLoop` 开头取页和浏览器工具）
- 认标签页只看网址：记下的序号 + 网址都对上，或者网址在标签页列表里只出现一次。去掉第三级「数量没变少就信序号」。认不出时这个使用者不再有标签页（`IllegalStateException` 照旧，不在别的标签页上操作）。
- 使用者的标签页：「当前那一页」+「它点开的别的页」。点击之后看 MCP 返回的标签页列表（Playwright MCP 有新标签页时会附「### Open tabs」，不多调一次），多出来的新标签页记给这个使用者，系统切过去、取快照：模型点「去投递」后直接看到新页，原来那页还记在它名下。
- 模型的 `browser_tabs`（只给申请 Agent）：`list` 只列这个 Job 能选的标签页——它自己的，加上没人占用、和岗位或它的页面同一站点的（重启后认回原页用）；别的 Job / Session 占着的、用户自己开的别的站点的标签页一律不列、不能选。`select` 只接受 `list` 里能选的序号，切过去后取快照。模型给的参数不直接发给 MCP，由 `BrowserSession` 自己以系统身份发 `list` / `select`（白名单里 `browser_tabs` 仍然只能由系统调用）。
- `AgentRun.lastPage.url`：每轮存最新页面的网址。运行开始：自己的标签页还在 → 用它；不在 → 按 `lastPage.url` 在没人占用的标签页里认（唯一匹配才认）→ 认不出就新开岗位链接，同时把能选的标签页列给模型（「原来做到一半的页面没认出来，可能是下面这些，用 browser_tabs select 切过去」）。去掉「原来的标签页读不到了…重新打开」那个分支（取快照出错照旧走「读不到当前页面」等用户）。
- 运行中标签页丢了（被关掉、网址认不出）：不再当成「浏览器断开」结束运行，而是结果里告诉模型，把能选的标签页列给它；一个都没有时系统重新打开岗位链接。

**第 6 条 · `finish` 的 `page_shows_submitted`**
- `finish` 加布尔参数 `page_shows_submitted`（模型看最新快照判断）；存进 `AgentRun.lastPage.showsSubmitted`，接着做时清掉。模型没给时退回原来的关键词判断。给用户的话照旧（`SUBMITTED_NOTICE` 那句只是文字）；`Views` 的 `pageLooksSubmitted` 改读 `AgentRun`，前端字段名不变。

**必须保持不变**（对应已有测试）
1. 硬边界词在申请和调研里都永远拒绝，任何批准、模型说明都放不过 —— `SubmitGuardTest.submitButtonsAreRejected`、`applyOnTheJobDetailPageIsRejectedToo`、`unnamedButtonsAreJudgedByTheirText`（不改）；新增批准不放硬边界的用例。
2. ref 不在最新快照 / 解析不了 / 没有快照 → 拒绝 —— `unknownRefsAndUnparsableLinesAreRejected`（不改）。
3. 夹具 `submitted-page`：「下一步」同页有「提交申请」，模型说不是最后一步也要先问用户 —— `nextButtonsNeedTheUser...` 改写后的用例 + `FakeFormScriptTest`（夹具不改）。
4. 其它元素（上传简历、添加经历、链接）照旧放行 —— `everythingElseIsAllowed`（不改）。
5. 页面提醒（验证码、弹窗、像已提交）照旧 —— `verificationOnThePageIsAWarning` 等三条（不改）。
6. C 级字段不填 —— `FieldClassGuardTest`（不改）；`HarnessLoop.classC` 不动。
7. 白名单：模型不能直接调 MCP 的 `browser_tabs`、不能执行脚本、回车 —— `BrowserToolPolicyTest`（不改）、`BrowserToolsTest.definesExactlyTheModelWhitelist...`（不改：`browser_tabs` 另外定义，不在 `definitions()` 里）。
8. 只对自己的标签页取快照 / 操作；别的 Job 的标签页拒绝 —— `BrowserSessionActTest.refusesWhenTheTabIsNotThisJobs...`（不改）。
9. 每次操作前先列标签页核对，调用顺序不变（点击后不多调 MCP）—— `BrowserSessionActTest.writesThenReturns...` 的调用序列（不改）。
10. 断开 → 丢掉归属、等用户 `reconnected`，不自动重连；一条连接给所有 Job —— `disconnectDuringActDropsTheTabOwnership`、`BrowserSessionConnectionTest`（不改）。
11. 申请 Agent 已有的工具（填写、上传、查找、导航、ask_user、update_plan、read_skill、finish）参数和行为不变 —— `BrowserToolsTest`（不改）。
12. 编译和全部测试 —— `mvn -B clean verify`。

### 6. 小项（各自独立，一起做）
- 读过的 JD 只存在一次运行的内存里：上一条消息读的 JD，下一条消息加候选时存不下快照（`SessionLoop.java:152、354`）。改：读到的页面统一存进 Session 目录，`add_lead` 按网址或路径取（参考 deepagents `_message_eviction.py`：大结果落盘、按路径引用）。
- 「页面像已投递」从给人看的文字里解析（`HarnessLoop.java:605` 拼一句话、`Views.java:161` 再查子串）。改：`finish` 加参数 `page_shows_submitted`，模型判断，存进 `AgentRun`。
- 「该查看了」和状态分组在四处各算一遍（服务器时钟、浏览器日期各一套）。改：`JobView` 里只算一次 `due`、`bucket`，`list_jobs`、前端都读它；`canStart` 同理。
- 申请线程一直用第一次取到的模型，改了设置不生效（`ApplicationRunner.java:391`）。改：每次运行时再取模型。
- 前端必填问题没答完不让发送（`JobPage.tsx`）。改：只提示，不挡（和上传位置同一条规则）。
- 导入进度页每 1.5 秒轮询（`ImportReview.tsx:63`）：改成用事件日志。

### F 施工计划（第 6 条：读过的页面落盘、`due` / `bucket` / `canStart`、必填问题只提示、导入页用事件）

- a 读过的页面存进 Session 目录：
  - 新 `persistence/SessionPages`：`sessions/<id>/pages/<hash>.txt` 存一页的开头（和原来的 JD 快照一样，最多 12000 字），`pages/index.tsv` 一行一条「规范化网址 → 文件」。规范化：scheme、host 小写，去掉默认端口、`#…`、`utm_*` 参数、结尾的 `/`。重定向时请求的网址和最后的网址都记一行，指向同一个文件。同一网址再读时覆盖文件（最新的为准）。`StorageLayout.sessionPagesDir`（新）。
  - `SessionLoop`：去掉内存里的 `fetched`；`web_fetch`（直接读、浏览器兜底两条路）照旧取开头存进 `SessionPages`；浏览器工具每次得到新页面（`browser_navigate`、点进详情页等，循环里 `step.page()` 那一处）也存一份（原来不存）。
  - `add_lead`：先按 `source`（可选，新参数：读详情用的那一页的网址，或者存下的文件名 `pages/<hash>.txt`）找，再按 `url`（规范化）找；都没有时和原来一样不带快照。回执文字不变。工具说明里加一句 `source`。
  - 不改 `WebFetch`（`cache/web` 的长页面全文照旧）。
- b `due` / `bucket` / `canStart` 只在服务器算一次：
  - `Job.checkDue(LocalDate today)`（domain）：已投递以后的阶段（applied…offer）、`next_check_at` 的日期到了今天（含）。`SessionLoop` 的 `due`、`Views.checkDue` 都改用它。
  - `domain/JobBucket`（新）：`lead`（候选 new / shortlisted）、`preparing`（prep 里其余的）、`waiting`（prep，申请线程在等你 / 失败 / 中断，或者 `prep_state` 是 needs_input / needs_login / handoff_user / failed）、`ready`（prep:awaiting_review，停在提交前；优先于 waiting）、`applied`（applied…offer）、`closed`（候选 skipped / closed，或者申请已结束）。和现在前端「接下来 7 天 · 待你」、`awaitingSubmit`、`list_jobs` 的分组一一对应。
  - `JobStatus.canStartApplication()`（domain）：候选 new / shortlisted，或者 prep 里可以开始的状态（就是原来 `ApplicationRunner.STARTABLE`）。`ApplicationRunner.start` 改调它（D 的文件，只换这一处判断）。`JobView.canStart` = 还没有申请线程 + 状态允许 + 有岗位链接。
  - `JobView` 加 `due`、`bucket`、`canStart` 三个字段（只加，不改已有字段）。`list_jobs` 的 lead / applying / applied / check_due 改读 `JobBucket` / `checkDue`。计数归 D：只把 `dueChecks` 那一行改成读 `due`，D 已经改过那一块就不动。
  - 前端：`api.ts` 的 `JobView` 加三个字段，删掉 `checkDue`、`AFTER_APPLIED`；`awaitingSubmit` 读 `bucket === "ready"`；`metrics.ts` 的 `submitted`、`Upcoming`（下次查看、待你）、`Kpis` / `Board` / `AppsTable` / `FollowUps` 的「该查看了」读 `bucket` / `due`；`JobPage` 的 `canStart` 读 `job.canStart`。
- c 必填问题只提示：`JobPage` 的 `missingAnswers` 从挡住发送的 `hints` 移到 `softHints`：「还有 N 个必填问题没答，Agent 会再问」。发出去的内容不变（只发填了的回答）。「要求修改要写说明」仍挡住。
- d 导入页用事件：`ImportRunner` 加 `onChange(Runnable)`（不改构造，测试照旧），开始、每次写进度、整理完、失败、确认一条候选之后调一次；`ImportConfiguration` 把它接到 `RuntimeEvents.changed(null, null)`。`ImportReview` 去掉 1.5 秒轮询，用 `useGlobalChanges` 重读当前记录和列表；整理中另有 15 秒保底重读。

必须保持不变（括号里是怎么确认）：
1. `add_lead` 去重和 B 的回执（「已经在候选池里了…」）不变（`JobLedgerTest` 的去重；读代码核对：只换了快照来源）。
2. 同一次运行里先 `web_fetch` 再 `add_lead` 仍存 JD 快照，内容同样是开头 12000 字（读代码核对）。
3. `web_fetch` / 浏览器工具给模型的结果文字不变；长页面全文仍在 `cache/web`（读代码核对）。
4. 同样的数据，Dashboard 的数字不变：投递数、漏斗、看板、待追踪、接下来 7 天（逐处对照新旧判断式；`npm run build`）。
5. 「该查看了」照旧出现在 `list_jobs`、侧栏角标、Dashboard（读代码核对；`dueChecks` 仍是同一个判断）。
6. `list_jobs` 各个筛选返回同样的岗位（逐条对照）。
7. 不能开始的 Job 后端仍拒绝（同一个判断，换了位置）；工作台「开始申请」按钮只在能开始时可点。
8. 必填问题答了的照旧发送、存进「本次答案」；上传位置的软提示不变（读代码核对）。
9. 导入：开始、进度、确认候选、中断记为失败都不变（`ImportRunnerTest` 等导入测试）。
10. 全部单测和 IT 通过（`mvn -B clean verify`）、`npm run build` 通过。

## 回归审查后的修正（2026-10-23，只读审查找出 8 条）

一个代理对照 182c7a6 只读审查了第一阶段和第二阶段第一批的改动。修法（H、I 两个代理，先在这里补施工细节和「必须保持不变」，再改）：

- H（`ApplicationRunner`、`JobLedger`、`RuntimeEvents`、`Views`、`SessionLoop` 的 `list_jobs`、前端 `JobPage` 锁输入框）：
  1. 用户已定下的状态（applied、closed、user_submitting / verification_pending / unconfirmed）下 `reply` 仍会让申请 Agent 重跑：开头校验，409；前端同步锁输入框。用户说「投了」时，对应的 AgentRun 也要落到不再运行的状态。
  4. 持有全局写锁时同步往 SSE 发送，慢连接会让所有写入停顿：`changed` 放到锁外发，或 RuntimeEvents 只入队、单独线程发送。
  5. `list_jobs` 读文件里的线程状态，排队 / 中断显示成 running：改读 `ApplicationRunner.status`；`JobBucket` 同步。
  6. 回报 Session：用户按停止、排队时停止不回报；`paused` 不回报；`page_shows_submitted` 为真时改用「页面像已经投递了」那句，不说「请提交」。
  7. 旧记录没有 `lastPage` 时，「页面像已投递」退回原来按消息判断。
  8. 排队中按停止：立刻移出队列、记成等你、发事件，不等轮到它。
- I（`BrowserSession`、`HarnessLoop.startPage`、`SessionLoop` 的标签页丢失处理）：
  2. 用户在标签页里登录 / 操作后网址变了，标签页被判丢失、又新开一页：恢复一个窄的认法（记下的序号还在、没被别人认领、和岗位同一站点就还是它），startPage 对「可能是它的」那一页直接认回，不新开。调研循环同理。
  3. `open()` 先刷新所有人的序号，再作废同序号的旧记录，不误删别人的。

### H 施工细节

- 第 1 条：`ApplicationRunner.reply` 在 `find` / 在跑校验之后、第一次写之前读 `job.md`，`JobLedger.userDecided(status, USER)` 为真（不在 prep：applied 以后、closed；或 prep 是 user_submitting / verification_pending / unconfirmed）就 409，什么都不写。`paused` 不拦（用户自己回复就是接着做）。用户说「投了 / 下线了 / 不投了」以后的 AgentRun：不改文件，`status()` 里推导——不在跑、不在排队、`userDecided(…, USER)` 为真时对外显示新值 `done`（`AgentRun.DONE`，和 `queued` / `interrupted` 一样只在对外状态里，不写文件）。这样不管停止和用户标记谁先落盘，工作台都不再给回复；`ApplicationStatus` 字段不变，只多一个取值。前端 `status.tsx` 加 `done`「已结束」；`JobPage.lockedReason` 对 prep 的 user_submitting / verification_pending / unconfirmed 也锁（读 `job.prepState`）。
- 第 4 条：`JobLedger` 的 `changed` 挪到 `StorageLock.WRITES` 外面：锁里只记下「要通知谁」（线程本地列表，嵌套调用照样只记），最外层出锁后再逐条发。`RuntimeEvents.publish` 只在自己的锁里编号、进环形缓冲、按过滤放进每个订阅的队列；每个订阅一个投递任务在单独的执行器（虚拟线程）上按顺序取队列、调 `sink`，`sink` 不在任何锁里被调用（`EventController.Connection` 自己的锁只挡同一连接）。`sink` 抛异常照旧取消这次订阅。
- 第 5 条：`SessionLoop.Applications` 加 `status(jobId)`（没有申请线程时 null），`SessionRunner` 用 `ApplicationRunner.statusIfAny` 实现；`list_jobs` 的分组和「（申请线程：…）」都读它，不再读 `AgentRunStore`。`JobBucket` 本来就由 `Views` 按 `statusIfAny` 传入，不用改。
- 第 6 条：`reportToSession` 不报：用户按停止导致的结果（停止请求还在、结果是等你，包括排队时停止）；prep 是 `paused` 的。`ready` 且 `pageShowsSubmitted()` 时改说「页面像已经投递了，确认一下」（以 `SubmitWords.SUBMITTED_NOTICE` 开头），不说「请检查并提交」。
- 第 7 条：`Views` 的 `pageLooksSubmitted`：`lastPage` 为 null（旧记录）时退回 `ready` 且消息含 `SUBMITTED_NOTICE`。
- 第 8 条：`stop` 对排队的：记下它的 `Future`，`cancel(false)` 成功（还没开始）就立刻移出队列、存成等你（`STOPPED_IN_QUEUE`）、`settle`、清掉 `live`、发 `finished`，不回报。取消不掉（执行器已经开始取它、正在等锁）时照旧走「轮到时不跑」那条路。`awaitIdle` 遇到被取消的任务当作空闲。

必须保持不变：
1. 队列顺序：其余排队的仍按交给执行器的顺序轮到；单线程执行器不变。
2. 一次只有一个浏览器动作（只有一个申请线程在跑）。
3. 用户说的事实优先：`advancePrep` / `userDecided` 规则不变。
4. 真的停在提交前 / 等你 / 失败，`reportToSession` 照常回报（文案只改「页面像已投递」那一种）。
5. 事件照常发布、编号、可按 `Last-Event-ID` 补发；同一连接按序号顺序收到。
6. READY 时回复仍是轮次加一、`requestChanges`。
7. 对在跑的按停止：仍是 `stopRequested` → `HarnessLoop.STOPPED`，结果存盘、发 `finished`。

### I 施工细节（第 2、3 条）

- 认页规则（`BrowserSession.claim`，第 2 条）。按顺序：① 序号 + 网址都对上；② 网址在剩下的标签页里只出现一次；③ **新加的窄规则**：记录没认出，但记下的序号还在列表里、没被别人认领（①②之后、按记录顺序，正在用的那页先于别的页），而且那一页的站点（`BrowserToolPolicy.siteOf`）和这条记录的网址同一站点，或者和这个使用者的「主站点」同一站点 → 还是它，记录的网址更新成现在的；④ 其余照旧：序号还在的算「可能是它的」，不在的去掉。
- 主站点：`open(url, owner)` 时记下要打开的网址和落地网址的站点；`act` 里 `browser_navigate` 成功后换成导航去的站点（调研「最近导航到的站点」、申请 Agent 导航时同理）。断开时清掉。SSO 跳到别的域名再回来时靠它认出来；停在 SSO 页上时认不出（窄，不猜）。
- `HarnessLoop.startPage`：自己的标签页认不出、按 `lastPage.url` `adopt` 也认不出、而且有 `lastPage.url` 时，先（新开之前）列能选的标签页；**正好一个**就直接 `select` 认回，写「系统认回了上次的标签页」；零个或多个照旧新开岗位链接，多个时把列表写进页面说明。没有 `lastPage.url`（第一次开始）时不多列，直接新开，调用序列不变。
- 调研循环（`SessionLoop`）：靠同一条认页规则，不另写代码（它的主站点是最近导航到的站点）。
- `open()`（第 3 条）：拿到新标签页的列表后，先用**除新标签页以外**的列表 `claim` 一次，刷新所有人的序号（存在与否按列表里的序号判断，不按个数），再 `release(新序号)` 只去掉真正过时、序号就是新标签页的记录，然后记给打开它的使用者。
- 测试：假浏览器（非表单模式）加多标签页场景（`FakeBrowser.tabs(current, urls...)`，测试在两次调用之间改标签页列表 = 用户在 Chrome 里操作）。`BrowserSessionTabsTest` 两条：同一标签页里网址变了（同站点）还是它的、换到别的站点就认不出；用户关掉前面的标签页后别人 `open`，不删掉另一个使用者的记录。

必须保持不变：
1. 别的使用者认领的标签页（确定的或可能的）从不被读、被操作、被快照；新规则只在「没被别人认领」时成立。
2. `BrowserSessionActTest`、`BrowserSessionConnectionTest`、`FakeFormScriptTest`、`PlaywrightMcpClientTest` 里断言的 MCP 调用序列不变（单标签页时规则③不多调 MCP）。
3. 断开连接清空归属（`disconnectDuringActDropsTheTabOwnership`），主站点一起清。
4. 点击打开的新标签页照旧自动记给这个使用者并切过去。
5. `browser_tabs` 仍只能由系统调用（`BrowserToolPolicyTest`、`BrowserToolsTest` 不变）；`tabs` / `select` 能选的范围不变。
6. 硬边界、守卫不碰。

## 最终审查后的修正（2026-10-23，只读审查找出 4 条）

- J（`BrowserSession`、`HarnessLoop.startPage`）：
  1. 接着做时「唯一候选」可能是用户自己的、别的站点的标签页（规则 ④「可能是它的」不看站点），系统直接认回并快照：自动认回只认和岗位链接或上次网址同站点的候选；其余只列给模型，系统不替它猜，也不快照。
  2. 规则 ③ 在序号挪位后会把另一个使用者（同一个 ATS 站点）的标签页认成「确定是我的」：标签页个数比上次少时（有页被关、序号可能整体挪了）这一次跳过 ③（先保存旧的个数）；或者只有一条认不出的记录可能对上这一页时才成立。必须保持：别的使用者的标签页永远不被读、不被操作。
- K（`ApplicationRunner`、`RuntimeEvents`）：
  3. 排队时按停止：任务已被执行器取走、正在等锁时 `cancel(false)` 仍返回成功，申请照跑：任务开头在锁内判断「已经被停止移出队列」就直接返回，不跑。
  4. 每个订阅的投递队列没有上限：超过缓冲上限就取消这次订阅，客户端带 `Last-Event-ID` 重连，走补发或 `reset`。

### J 施工细节

- 第 1 条：「接着做时系统自己认回」挪进 `BrowserSession.reclaim(owner, sites)`（`HarnessLoop.startPage` 只调它），规则：能选的标签页（不算正在用的）正好一个，**而且**它是这个使用者的记录（`own`，不是「没人占用、同一站点」的空闲页），**而且** `siteOf(它的网址)` 等于岗位链接或上次网址的站点，才切过去、取快照；否则返回 null，不切、不快照。`startPage` 照旧新开岗位链接，把候选（只有序号、标题、网址）列给模型，让模型决定要不要 `browser_tabs select`。空闲的同站点页不自动认回：它可能是同一个 ATS 上另一个申请、记录已经丢了的标签页。
- 列标签页（`tabs`、`claim`、`choosable`）只调 `browser_tabs list`，不切换、不快照，和原来一样。
- 第 2 条：
  - `list()` 覆盖 `tabCount` 之前先比一下：这次个数比上次少 → 这一次是「缩了」（`shrank`）。`open()` 用「除新开那页以外的个数」和上次比。
  - 缩了的那一次 `claim`：只用 ①（序号 + 网址都对上）和 ②（网址在没认领的页里只出现一次）；认不出的记录**直接去掉**（不走 ③，也不留作 ④「可能是它的」），因为序号可能整体挪了，留着下一次个数不变时 ③ 还会把挪过来的别人的页认成它的。
  - 个数没少时 ③ 照旧，但只有一条认不出的记录写着这个序号时才成立（两条记录争同一序号就都不认，留作 ④）。
  - 已知剩下的空子：用户在同一次两次列表之间既关一页又开一页（个数不变）、两个申请又在同一个 ATS 上、被挪的那页网址也变了——系统分不出来，③ 仍可能认错。列标签页看不到更多信息，这次不处理。
- 必须保持不变：
  1. I 的情形：个数不变、用户在 Job 自己的标签页里登录 / 点下一步、网址变了但还在同一站点 → 仍是它的（`theSameTabWithAChangedUrlOnTheSameSiteIsStillThisJobs`）。
  2. 点击打开新标签页 → 系统记给这个使用者并切过去（`act` 的 CLICK 分支不动）。
  3. `open()` 不删别的使用者的记录（`openingANewTabKeepsAnotherOwnersRecordAfterTheUserClosedAnEarlierTab`）。
  4. 别的使用者的标签页永远不被读、不被操作；认不出时 `TabLost`，只调了 `browser_tabs`。
  5. 现有测试里的 MCP 调用序列不变（`BrowserSessionConnectionTest`、`writesThenReturns...` 等）。
  6. 申请重启后按上次网址认回（`adopt`）不变。

### J 实际结果

- `BrowserSession`：
  - 新字段 `shrank`：`list()` 在覆盖 `tabCount` 前比较（这次个数 < 上次）；`open()` 用新开那页以外的个数比较；断开时清掉。
  - `claim`：`shrank` 时跳过 ③、也不留 ④，①② 认不出的记录去掉；没缩时 ③ 只在只有一条认不出的记录写着这个序号时成立。顺手修了一个只在新规则下才会出现的顺序问题：先删掉认不出的记录，再把认出来的换成新序号（原来先换后删，换出来的新实例会被误删）。
  - 新 `reclaim(owner, sites)` → `Reclaimed(page, candidates)`：只列一次；候选（不算正在用的）正好一个、是自己的记录、和 `sites` 同一站点才 `switchTo`（切 + 快照），否则只返回候选，不切不快照。
- `HarnessLoop.startPage`：原来「`tabs` 列出来正好一个就 `select`」换成 `reclaim`；没认回时把 `reclaim` 返回的候选列给模型（MCP 调用次数和原来一样：adopt 列一次、reclaim 列一次、再新开）。提示文字「系统认回了上次的标签页（上次记下的是 …，现在是 …）」。
- 测试（`BrowserSessionTabsTest` 加 3 条）：`resumingNeverReadsTheUsersOwnTabThatSlidIntoTheRecordedIndex`（邮箱占了 Job 原来的序号：不认回、只调了 `browser_tabs`、没有 select、邮箱只作为候选）；`resumingReclaimsTheJobsOwnTabAfterALoginDetour`（第三方登录后回到同一站点的下一步：系统认回）；`closingATabNeverHandsAnotherApplicationsFormOnTheSameSiteToThisJob`（同一 ATS 上 A、B，关掉 A、B 挪位且网址变了：A 两次都 `TabLost`、`reclaim` 不认回、只调了 `browser_tabs`）。
- 验证：`mvn -B clean verify` 通过（surefire 620、failsafe 1；在 scratchpad 里的副本上跑，代码与工作区一致，含 K 当时的改动）。真实 Chrome：**未运行**。
- 「必须保持不变」：1 ✓ `theSameTabWithAChangedUrlOnTheSameSiteIsStillThisJobs` 未改通过，新的登录绕行用例也通过；2 ✓ `act` 的 CLICK 分支没动；3 ✓ `openingANewTabKeepsAnotherOwnersRecord...` 未改通过；4 ✓ 两条新用例断言只调了 `browser_tabs`；5 ✓ 其余测试未改通过；6 ✓ `adopt` 没动。
- 剩下的空子（见施工细节）：个数不变的「关一页又开一页」+ 同一 ATS + 网址也变了，③ 仍可能认错；另外，没人占用的同站点标签页（可能是另一个申请丢了记录的页）仍会列给模型，由模型决定选不选。

### K 施工细节

- 第 3 条（`ApplicationRunner`）：`queued` 从 `Set<String>` 换成 `LinkedHashMap<String, Object>`（Job → 这次排队的凭证，仍按交给执行器的顺序）。`submit` 排队时放一个新凭证，任务开头在锁里 `queued.remove(jobId, 凭证)`：排过队、但凭证已经不在（被排队时的停止移出了，或者停止后又重新排了一次、换了新凭证）就直接返回，不设 `runningJob`、不跑、不存盘、不发事件（停止那边已经存好、发过了）。用凭证而不是只看 `queued.contains`，是因为停止后用户马上回复会重新排队：旧任务拿到锁时看到同一个 Job 又在队里，会拿旧的 run 去跑。`stop` 的「排队」分支不变（`cancel(false)` 失败也不影响：旧任务开头自己认出来）；`stoppedInQueue` 那条路（排着队、`stopRequested` 里有）留着，现在只在取消没成功时才会走到。
- 第 4 条（`RuntimeEvents`）：`Subscriber.offer` 在队列已有 `BUFFER`（2000）条时不再入队：标成取消、清空队列，返回「丢掉了」；`publish` 用迭代器把它移出订阅列表，再把订阅时给的 `dropped` 回调交给投递执行器（虚拟线程）去跑，发布方不等它。`subscribe` 多一个参数 `Runnable dropped`；`EventController` 传 `emitter::complete`（完成时照旧走 `onCompletion` → 取消心跳和订阅，重复取消没关系）。卡住的那个投递任务：`send` 返回（或出错）后看到已取消就退出；队列已清空，不再占内存。浏览器 `EventSource` 断开后自己带 `Last-Event-ID` 重连，缓冲里还有就补发，没有就 `reset`。
- 必须保持不变：
  1. 排队顺序：按交给执行器的顺序轮到（`LinkedHashMap` 保持插入顺序，执行器仍是单线程）。
  2. 同一时间只有一个申请在操作浏览器（单线程执行器；被认出的旧任务直接返回，不碰浏览器）。
  3. 在跑时停止不变：`stopRequested` → `HarnessLoop` 在当前工具调用后停下。
  4. 排队时停止立刻在界面上反映：`STOPPED_IN_QUEUE` 存盘、`live` 去掉、发 `finished`，都在 `stop` 里同步做完。
  5. `STOPPED_IN_QUEUE` 不回报给 Session（`stop` 那条路本来不回报；旧任务直接返回，也不回报）。
  6. 事件在每个订阅里按序号顺序；补发、`caught_up`、`reset` 不变。
  7. 发布方永不阻塞：丢订阅只改内存，完成连接交给投递线程。
- 测试：第 3 条不加。要稳定复现，得让执行器线程正好卡在 `synchronized (this)` 上、同时测试线程持有 runner 的锁去 `stop`，只能轮询线程状态（BLOCKED）或加只为测试的钩子，还要搭整套存储和假模型，靠读代码核对。第 4 条加一条小测试（`RuntimeEventsTest`）：`sink` 卡在一个门闩上，发 `BUFFER + 2` 条（投递任务最多取走一条，其余必然积满），等 `dropped` 的门闩；不睡眠、不依赖时序。

## 记下来、这次不做（要你决定）

- **对话压缩**：对话只增不减，太长时模型报错、这次运行记为失败（`Conversation.java:85`、`SessionLoop.java:253`）。参考：deepagents `middleware/summarization.py`（到阈值压缩、完整历史存文件）、Hermes `agent/context_compressor.py`、OpenMAIC `lib/chat/pi/director-compaction.ts`。C006 写过「不做」，现在是已知缺口；长的调研对话最先碰到。
- **资料导入是固定流水线、每个候选要你点一次**（`ImportRunner.java:38`，C004-A 时的设计）。参考：Hermes `tools/memory_tool.py`、deepagents `middleware/memory.py`（Agent 直接维护记忆文件）。要改 `memory.md`（master），规模大，另写计划。

## 施工分工（用户 2026-10-23 说开工；对话压缩、资料导入不在这次）

每个代理先在本文件自己那一节写施工计划（要改什么、**必须保持不变的已有行为**清单），再改；做完由一个只读代理审查回归。文件归属：
- D：第 1、3 条，加第 6 条里「每次运行时再取模型」。归 D：`ApplicationRunner`、`ApplicationStatus`、`Views` 的计数、`ImportRunner` 的状态读取、`SessionRunner` 的「中断」推导。
- E：第 2、5 条，加第 6 条里「`finish` 的 `page_shows_submitted`」。归 E：`SubmitGuard`、`BrowserSession`、`BrowserToolPolicy`、`HarnessLoop` / `SessionLoop` 里守卫和标签页的调用、`AgentRun` 的 `askedButton` 和页面地址字段。
- F：第 6 条其余小项。归 F：`SessionLoop` 的读页缓存和 `add_lead` 取快照、`JobView` 的 `due` / `bucket`、前端（必填问题只提示、导入页改用事件、各处读 `due` / `bucket`）。
- 共享文件（`AgentRun`、`HarnessLoop`、`SessionLoop`、`Views`）只做定点修改。

## 原来的分工草稿

- 第一批（C006-fix-runtime 完成后，并行）：代理 D 做第 1、3 条（ApplicationRunner、AgentRun、Views 计数）；代理 E 做第 2、5 条（SubmitGuard、BrowserSession、两个循环里守卫和标签页的调用）；代理 F 做第 6 条小项（前后端）。D 和 E 都碰 `HarnessLoop` / `AgentRun`，先划好归属，共享文件只做定点修改。
- 第二批：代理 G 做第 4 条（合并循环），等第一批和回归修正完成（2026-10-23 开工）。

## 怎么确认能用

- `mvn -B clean verify`、`npm run build` 通过；被改坏的测试按新规则改。
- 你在本机：一次说「投这三个」，三个都排上队并依次开始；一个申请在等你时，另一个停在提交前的能要求修改；重启后端，正在跑的申请显示「中断」，回复后接着做；多页表单里「下一步」不再每页都问。

## 实际结果

（施工后填写）

### D 实际结果

- 新 `domain/LiveRuns`：进程内在跑的 id 集合 + `interrupted(id, recordedRunning)`。`SessionRunner.running`、`ImportRunner.active` 换成它（判断和文案不变）；`ApplicationRunner` 用它推出 `queued` / `running` / `interrupted`。
- `ApplicationRunner`：
  - 删掉 `requireBrowserFree`（扫描 `waiting_user`、两条 409）。`start` / `reply` 校验通过就交给单线程执行器；浏览器空着直接 `running`，否则进 `queued`（`LinkedHashSet`，按顺序），轮到时发 `status: running`。入队发 `status: queued`；结束照旧 `finished` + `reportToSession`（排队不另写 note）。
  - 同一个 Job 在跑或在排队时 `start` / `reply` 409：「Job x 正在运行 / 正在排队，等它停下来再…（或者先停止）」。
  - `stop`：在跑的照旧；排队的轮到时不跑，记成等你（`STOPPED_IN_QUEUE`），照常 settle、回报；中断的和等你一样什么都不做。
  - `status()`：文件写 `running`、内存里没有 → `interrupted`，`error` =「应用重启了，这次申请被中断，填了一半的表可能还在 Chrome 里。回复后接着做。」回复照常恢复（不是 READY，不加轮次）。
  - 每次运行 `new HarnessLoop(mainModel.get(), …)`，去掉缓存的 `loop`。
  - 运行结束存盘失败时也清掉 `runningJob` / 内存记录（`finally`），不会把队列永远卡住。
- `AgentRun`：只加常量 `QUEUED`、`INTERRUPTED`（不写进文件）。`ApplicationStatus`：只改注释（字段不变）。
- `SessionRunner` 的 `start_application` 回执：排上队时「已经排上队：<id>。浏览器现在在做别的申请，轮到它时自动开始，不用再调用 start_application。之后在申请工作台里看进度。」
- `Views` 计数：`waiting`（等你 / 失败 / 中断）、`ready`（停在提交前）都读申请线程的状态，且只算用户还没定下的（`JobLedger.userDecided`）。前端现在没读这两个计数（只读 `dueChecks`）。F 的 `JobView.bucket` 落地后，计数可以改成直接数 `bucket`，这次没动，免得和 F 冲突。
- 前端：`status.tsx` 加 `queued`「排队中」、`interrupted`「中断」（标签和颜色）；`JobPage` 两处定点：`queued` 算在跑（能停止、不能回复），`interrupted` 算在等你（能回复）。
- 推迟：`job.md` 里 Agent 写的 `prep_state` 仍由 `advancePrep` 写（理由见「D 施工计划」）。中断时 `prep_state` 仍是 preparing，界面靠 `application = interrupted` 显示。
- 测试：没有改，也没有新加。原有测试里没有断言 409 / `requireBrowserFree` 的（`ApplicationRunner`、`SessionRunner` 没有单测），`ImportRunnerTest` 的中断用例照旧覆盖导入那一处。
- 验证：`mvn -B clean verify` 通过（surefire 614、failsafe 1，和 E、F 的改动一起）；`npm run build` 通过。启动应用手动走「投这三个 / 重启后中断再回复」：**未运行**。
- 「必须保持不变」逐条核对（读最终代码）：1 ✓ 执行器仍是单线程，只是不再在入口 409；2 ✓ 没碰守卫，`SubmitGuardTest` 等通过；3 ✓ `start` 的校验顺序和文案没动，只把 `requireBrowserFree` 换成「同一个 Job 已经在跑 / 排队」；4 ✓ `reply` 对 `live` 里的 Job 仍 409；5 ✓ 在跑的停止仍走 `stopRequested` → `HarnessLoop.STOPPED`，没在跑的什么都不做；6 ✓ READY 分支（轮次加一、`requestChanges`）没动；7 ✓ 结束时仍 `events.finished` + `reportToSession`，用户已定状态不报；8 ✓ `started` / `finished` / `observer` 照发，多了 `queued`；9 ✓ `prep_state` 仍只经 `moveTo` → `advancePrep`，`JobLedgerTest`、`ApplicationStateMachineTest` 通过；10 ✓ `ApplicationStatus` 字段没变，前端构建通过；11 ✓ Session 判断等价（先看内存，再看文件写着 running），文案不变，`ImportRunnerTest` 中断用例通过；12 ✓ `reply` 的校验段落没动，仍在第一次写之前。

### E 实际结果

- 第 2 条（软边界按钮）：
  - `SubmitGuard.click(latest, ref, intent, approval)`：硬边界和「判断不了」照旧拒绝；软按钮依次看：同一页同一 ref 同一名字的批准 → 放行（循环随即清掉）；「下一步 / 继续 / Next」类而页面上看得出可能直接提交（同页有提交类按钮、步骤条 N/N）→ `AskUser`；`final_step_suspected: true` → `AskUser`（原因带模型的依据）；`false` → 放行；没给 → 新的 `GuardDecision.Explain`，结果交回模型「带上 purpose、final_step_suspected 和 evidence 再点」，不问用户。去掉了「看不出第几步就问」。
  - `SubmitWords`：软词表拆成 `NEXT_*`（下一步、下一页、继续、next、continue、proceed）和 `SAVE_*`（保存、暂存、存草稿、草稿、save、draft）；`isSoft` 不变，新增 `isNext`。硬词表、已提交、验证词表没动。
  - 批准：`SubmitGuard.Approval(url, ref, button)`；守卫要问时 `HarnessLoop` 记 `pendingApproval`，模型接着 `ask_user` 时变成 `approval`，存进 `AgentRun.lastPage.approved`。点了那个按钮就清；任何新页面（开始取的页、操作后的页、切标签页）网址不同也清。
  - 去掉：`AgentRun.askedButton` / `withAskedButton`、`HarnessLoop.pendingButton`、「用户同意后再点它，系统就会放行」。
  - 调研：`SessionLoop` 改用 `SubmitGuard.readOnly`，只拦硬边界词和判断不了的元素，「下一页」类放行。
- 第 5 条（标签页）：
  - `BrowserSession`：使用者有「正在用的那页」和「别的页」（它点开的、它切走的）。认页只按网址（`claim`）：序号 + 网址都对上，或者网址在没认领的标签页里只出现一次；第三级「数量没变少就信序号」删了。认不出的记录留作「可能是它的」（别人不能选，它自己在列表里能看到），序号已经不在列表里的去掉；新开或切到一页时，别的记录里写着同一序号的作废（`release`）。认不出时抛 `BrowserSession.TabLost`（是 `IllegalStateException` 的子类，调研那边原有的 catch 照旧接住）。
  - 点击之后读 MCP 返回里的「### Open tabs」（Playwright MCP 有新标签页时会附上，不多调用一次）：多出来的新标签页记给这个使用者、系统切过去取快照，`takeNotice` 在结果里说一句。
  - `tabs(owner, sites)` / `select(owner, index, sites)`：只列 / 只切到它自己的、可能是它的、以及没人占用而且和岗位链接或它的页面同一站点的标签页。`adopt(owner, url)`：没人占用的标签页里网址唯一匹配才认回。MCP 的 `browser_tabs` 仍然只能由系统调用（`BrowserToolPolicy` 没放开），新增的只有公开的 `siteOf(url)`。
  - `BrowserTools.TABS` + `BrowserTools.tabs()`：申请 Agent 的 `browser_tabs`（list / select），不在 `definitions()` 里，所以调研拿不到，`BrowserToolsTest` 的白名单断言不变。`ApplyAgent.tools()` 加上它。
  - `HarnessLoop`：开头 `startPage()`：自己的标签页认得出就取快照；否则按 `AgentRun.lastPage.url` `adopt`；还不行就新开岗位链接，有可能是原页的标签页时把列表写进页面说明让模型 `browser_tabs select`。「原来的标签页读不到了…重新打开」分支删了（取快照出错照旧走「读不到当前页面」等你回复）。运行中 `TabLost` 不再当成浏览器断开结束运行：结果交回模型并列出能选的标签页；一个都没有时系统新开岗位链接。`current()` 每轮存 `lastPage`（最新网址 + 批准）。
- 第 6 条：`finish` 加必填布尔 `page_shows_submitted`（`ControlTools`）；`HarnessLoop.finish` 用它（模型没给时退回原来的关键词判断），存成 `AgentRun.lastPage.showsSubmitted`（`ready(message, shows)`），`resumed()` 清掉。`Views` 的 `pageLooksSubmitted` 读 `AgentRun.pageShowsSubmitted()`，字段名不变，不再查 `SUBMITTED_NOTICE` 子串（那句话仍写在给你的话里）。
- `AgentRun`：`askedButton` 换成 `LastPage lastPage`（`url`、`ApprovedClick approved`、`showsSubmitted`）；旧文件里没有这个键，读出来是 null（`AgentRunStore` 本来就忽略未知键，旧的 `askedButton` 被忽略）。
- 提示词：`ApplyAgent.LINES` 那句改成「写明 final_step_suspected 和依据；不是最后一步就直接点；怀疑是时系统要你先问这一个按钮」；`SITUATIONS` 加「新标签页」一条、像已提交时 `page_shows_submitted` 写 true。`browser_click` 的说明改成同样的规则。
- `workflow.md`「提交保护」第 2 条和两条标签页说明后面各加了一段 C006-fix-runtime-2 的改法（原文保留）。
- 测试（只改了行为有意改变的）：`SubmitGuardTest` 里三条软按钮用例换成五条：模型说不是最后一步放行（含没有步骤条的页）；没给说明交回模型（`Explain`）；模型怀疑 / 页面显示最后一步 / 同页有提交按钮时问用户（保存类不看页面信号）；批准只对同一页同一按钮，别的按钮、别的页面不算，提交类按钮带批准也拒绝；调研 `readOnly` 放行「下一页」、拒绝「立即申请」。硬边界、判断不了、页面提醒、其它元素、参数剥离的用例一行没改。
- 「必须保持不变」逐条核对：1 ✓ 硬边界在 `readOnly` 里最先判，申请和调研都走它，批准用例证明放不过（`SubmitGuardTest`）；2 ✓ `unknownRefsAndUnparsableLinesAreRejected` 未改通过；3 ✓ `submitted-page` 的情形（第 1/2 步、同页「提交申请」）模型说 false 仍 `AskUser`（新用例逐字断言），`FakeFormScriptTest` 通过；4 ✓ `everythingElseIsAllowed` 未改通过；5 ✓ 页面提醒三条未改通过；6 ✓ `FieldClassGuardTest` 未改通过，`classC` 没动；7 ✓ `BrowserToolPolicyTest`、`BrowserToolsTest` 未改通过；8 ✓ `refusesWhenTheTabIsNotThisJobs...` 未改通过；9 ✓ `writesThenReturns...` 的调用序列未改通过（点击后没有多调 MCP）；10 ✓ `disconnectDuringActDropsTheTabOwnership`、`BrowserSessionConnectionTest` 未改通过（`oneConnectionServesEveryJobInTurn` 靠 `release` 处理假浏览器只有一个标签页）；11 ✓ `BrowserToolsTest` 通过、其它工具的 schema 没动，`finish` 只多一个参数；12 ✓ 见下。
- 验证：`mvn -B clean verify` 通过（surefire 614、failsafe 1，含 D、F 的改动）。因为同时有别的代理在同一个 `target/` 里构建（中途一次全是 `NoClassDefFoundError`），这次是把 `backend/` 复制到 scratchpad 里跑的，代码与工作区一致。真实 Chrome 里新标签页切换、重启后认回原页、多页表单「下一步」不再每页都问：**未运行**（第 5 条原本就是推断，见上）。

### F 实际结果

- a 读过的页面落盘：新 `persistence/SessionPages`（`sessions/<id>/pages/<hash>.txt` 存开头 12000 字 + `pages/index.tsv`「规范化网址 → 文件」；规范化 = scheme / host 小写、去默认端口、`#…`、`utm_*`、结尾 `/`）；`StorageLayout.sessionPagesDir`。`SessionLoop` 去掉内存 `fetched`：`web_fetch` 两条路都存（请求的网址和重定向后的网址指向同一文件），浏览器工具每次得到新页面（循环里 `step.page()` 那一处）也存。`add_lead` 先按新的可选参数 `source`（网址或 `pages/<hash>.txt`），再按 `url` 找快照；回执文字不变。`ResearchTools` 的 add_lead 说明和 schema 加了 `source`。`WebFetch` / `cache/web` 不变。
- b 只算一次：`Job.checkDue(today)`、`JobStatus.canStartApplication()`（就是原来的 `STARTABLE`，`ApplicationRunner.start` 改调它，`STARTABLE` 删掉）、新 `domain/JobBucket`（定义见施工计划）。`JobView` 加 `due`、`bucket`、`canStart`；`Views.job` 多一个 `today` 参数（Job / Project / Session 三个控制器按 `LocalDate.now(clock)` 传）。`Views.checkDue` 改为读 `job.due()`（计数块归 D，没动）。`list_jobs` 的 lead / applying / applied / check_due 读 `JobBucket` / `checkDue`。前端：`api.ts` 删 `checkDue`、`AFTER_APPLIED`，`awaitingSubmit` 读 `bucket === "ready"`；`metrics.submitted`、`Upcoming`、`Kpis`、`Board`、`AppsTable`、`FollowUps` 读 `bucket` / `due`；`JobPage` 读 `job.canStart`。
- c `JobPage`：必填问题没答移到软提示「还有 N 个必填问题没答，Agent 会再问」，只有「要求修改要写说明」挡住发送；发出去的内容不变。
- d `ImportRunner.onChange(Runnable)`（构造不变）：开始、每次进度、确认候选、整理结束（成功或失败，`finally`）后通知；`ImportConfiguration` 接到 `RuntimeEvents.changed(null, null)`。`ImportReview` 去掉 1.5 秒轮询，用 `useGlobalChanges` 重读当前记录和列表，整理中另有 15 秒保底。
- 有意的小差别：`bucket = waiting` 把申请线程 `interrupted`（D 新加）也算在等你，所以「接下来 7 天 · 待你」会列出中断的申请；没有中断时和原来一样。`canStart` 多了「有岗位链接」（后端原本就拒绝没有链接的）。
- 检查清单：1 ✓ 去重和回执的代码没动，只换了快照来源；2 ✓ 同一次运行里 `web_fetch` 之后马上落盘，`add_lead` 按 url 取到（jshell 对 `SessionPages` 做了存、按规范化网址 / 重定向网址 / 文件名取、取不到的检查）；3 ✓ 给模型的结果文字没改；4 ✓ 逐处对照：`submitted`、`Upcoming.soon`、`awaitingSubmit`、待追踪的判断式和原来等价（只多了上面说的中断）；5 ✓ `dueChecks` 仍数同一个判断（`JobView.due`），`list_jobs` 的「该查看了」用 `Job.checkDue`；6 ✓ `list_jobs` 各个筛选逐条对照等价；7 ✓ 后端同一个判断换了位置，按钮读 `canStart`；8 ✓ 发送体没改（读代码核对）；9 ✓ 导入只加了通知，`ImportRunnerTest` 用构造器不受影响；10 见下。
- 测试：没有改，也没有新加。
- 验证：`npm run build` 通过。`mvn -B clean verify` 通过（surefire 614、failsafe 1；E 修好 `SubmitGuardTest` 之后跑的）。之前有一次 `ImportRunControllerTest` 两条失败（409 / 201 预期），紧接着重跑全部通过，多半是另一个代理同时在同一个 `target/` 里构建，没再出现。启动应用手动看导入进度推送、跨消息 add_lead 存快照：**未运行**。

### I 实际结果

- `BrowserSession`：
  - 认页规则加第 ③ 条（`claim`）：记录没按网址认出，记下的序号还在、没被别人认领、那一页和记录的网址或使用者的主站点同一站点（`BrowserToolPolicy.siteOf`）→ 还是它，记录的网址更新。先对所有记录跑 ③，再算「可能是它的」，所以别人的「可能」记录不挡住它自己的那页；别人确定认领的那页 ③ 不成立。序号在不在改成按列表里的序号判断（原来按个数，全列表时等价）。
  - 主站点 `homes`：`open` 记要打开的网址和落地网址的站点；`act` 里 `browser_navigate` 之后换成导航去的网址和落地网址（调用序列不变：原来导航后本来就取一次快照）；`disconnect` 清掉。
  - `open()`：新标签页之外的列表先 `claim` 一次刷新所有人的序号，再 `release(新序号)`，再记给打开者（第 3 条）。
- `HarnessLoop.startPage`：有 `lastPage.url` 时，新开之前先列能选的标签页，正好一个就 `select` 认回（「系统认回了上次的标签页 N（上次记下的是…，现在是…）」）；切换时那页刚好没了（`IllegalArgumentException`）就照常新开；零个、多个照旧。没有 `lastPage.url` 时不多列。
- `SessionLoop`：没改，调研靠同一条认页规则（主站点 = 最近导航到的站点）。
- 测试：新加 `BrowserSessionTabsTest` 两条（丢掉填了一半的申请标签页、误删别人的标签页记录都是实际损失）：同站点换网址还是它的；跳到别的站点认不出且只列了标签页、没取快照；Job 的页关掉后另一个 Job 挪到同一序号仍归另一个 Job；用户关掉前面的标签页后 C 新开，B 的记录还在、A/B/C 各自取快照是自己的网址。为此假浏览器加了多标签页模式（`FakeBrowser.tabs`，场景里有 `tabs` 才启用，原有场景不受影响）。原有测试一条没改。
- 「必须保持不变」逐条核对：1 ✓ ③ 要求「没被别人认领」，`focus` 仍要求确定且是自己的才操作，测试里另一个 Job 的页不被认走、`tabs` 也不列给 Job；2 ✓ `BrowserSessionActTest`、`BrowserSessionConnectionTest`、`FakeFormScriptTest`、`PlaywrightMcpClientTest` 未改通过（单标签页时 `open` 的 `claim` 用空列表，效果和原来的 `release(0)` 一样）；3 ✓ `disconnect` 清 `homes`，`disconnectDuringActDropsTheTabOwnership` 通过；4 ✓ 点击后新标签页的分支没动；5 ✓ `BrowserToolPolicy` 没动，`BrowserToolPolicyTest`、`BrowserToolsTest` 通过，`choosable` 没动；6 ✓ 守卫没碰。
- 已知窄处：用户关掉了 Job 的标签页、而另一个没人认领的同站点标签页正好挪到那个序号时，③ 会把它认成 Job 的（照计划接受）；停在别的域名的 SSO 页上时认不出，回复时 `startPage` 若只有一个能选的页就认回，否则新开并列出。
- 验证：`mvn -B clean verify` 在 scratchpad 的副本里通过（surefire 603、failsafe 1，含 H 当时进行中的改动）。真实 Chrome 里登录后网址变了再回复：**未运行**。

### H 实际结果

- 第 1 条：`ApplicationRunner.reply` 在在跑 / 排队的 409 之后、第一次写之前读 `job.md`，`JobLedger.userDecided(status, USER)` 为真就 409「Job x 现在是 …，你已经定下了，申请 Agent 不再接着做…」（`paused` 不拦）。`status()` 推导新取值 `done`（`AgentRun.DONE`，不写文件）：不在跑、不在排队、`userDecided(…, USER)` 为真时显示；文件写着 running 但已经定下的也显示 `done`，不显示「中断」。`ApplicationStatus` 字段不变（注释加了 `done`）。前端 `status.tsx` 加 `done`「已结束」；`JobPage.lockedReason` 对 `prepState` 为 user_submitting / verification_pending / unconfirmed 也锁。
- 第 4 条：`JobLedger` 七个写方法都是 `try { synchronized (WRITES) {…} } finally { publish(); }`；`afterChange` 只把 (session, job) 放进线程本地列表，`publish()` 在当前线程已经不持有 `WRITES` 时按顺序通知（嵌套的 `advancePrep → transition` 只在最外层发一次批）。`RuntimeEvents.publish` 在自己的锁里编号、进环形缓冲、按过滤放进每个订阅的队列；每个订阅最多一个投递任务（虚拟线程执行器）按顺序调 `sink`，不持有 `RuntimeEvents` 的锁；`sink` 抛异常时取消（清队列、移出订阅）。`EventController.Connection` 没改逻辑（`offer` 现在在投递线程里跑，补发 / `pending` 照旧）。
- 第 5 条：`SessionLoop.Applications` 加 `status(jobId)`，`SessionRunner` 用 `statusIfAny` 实现；`list_jobs` 的 `JobBucket` 和「（申请线程：…）」都读它（排队显示 queued、中断显示 interrupted）。`SessionLoop` 里只改了 `list_jobs` 和接口（`runs` 字段留着没删）。`JobBucket` 不用改（`Views` 本来就传 `statusIfAny`）。
- 第 6 条：运行结束时停止请求还在、结果是等你 → 不回报（含排队时停止的两条路）；`reportToSession` 改用 `userDecided(…, AGENT)`，多排除了 `paused`；`ready` 且 `pageShowsSubmitted()` 时说「申请 …：页面显示已经投递了。确认一下：在 Chrome 里看投递状态，投了就告诉我；没投的话在申请工作台里说。」
- 第 7 条：`Views.pageLooksSubmitted`：`lastPage` 为 null 时退回原来的判断（状态的 `message` 含 `SUBMITTED_NOTICE`）。
- 第 8 条：排队时 `submit` 记下它的 `Future`（`waitingInLine`）。`stop` 对排队的：`cancel(false)` 成功就立刻移出 `queued`、存成等你（`STOPPED_IN_QUEUE`）、`settle`（→ needs_input）、清 `live`、发 `finished`，不回报；取消不掉（执行器刚取到它）照旧加 `stopRequested`，轮到时不跑。`awaitIdle` 把 `CancellationException` 当作空闲。
- 「必须保持不变」逐条核对（读最终代码）：1 ✓ 执行器仍单线程，取消的任务不占位，其余 `queued` 顺序不变；2 ✓ 同一时间只有 `runningJob` 一个；3 ✓ `advancePrep` / `userDecided` 没改；4 ✓ ready / 等你 / 失败没被停止时照常回报，只有「页面像已投递」换了文案；5 ✓ 事件照常编号进缓冲，`subscribe` 的补发逻辑没动，同一订阅只有一个投递任务、FIFO；6 ✓ READY 分支（轮次加一、`requestChanges`）没动，只是在它之前多一条 409；7 ✓ 在跑的停止仍是 `stopRequested` → `HarnessLoop.STOPPED`、存盘、`finished`（只是不再回报 Session）。
- 测试：没有改，也没有新加。
- 验证：`mvn -B clean verify` 在 scratchpad 的副本里通过（surefire 616、failsafe 1，含 I 当时的改动）；`npm run build` 通过。启动应用手动走「排队时停止 / 标记已提交后回复 409 / 慢 SSE 连接」：**未运行**。

### G 实际结果

- 新 `harness/runtime/AgentLoop<R>`（包内可见，约 250 行）：持有模型、工具集、时钟、上限、停止信号、观察者、对话、最新页面、本次调用次数；`run(agent)` 是两边原来的骨架，逐行搬过来，文字不变（「没有执行：这一次运行已经结束。」成了常量 `SKIPPED`）。上限时的总结调用、计时、`thinking` / `replied` / `tool` / `done` 事件、`LatestPage` 的取法都在这里。`Step<R>(result, page, Supplier<R> end)` 带 `done` / `failed` / `rejected` / `withPage` / `ending`。构造参数 `dropReplyWhenStopped` 记下两边调模型时停止的处理不同（申请 true、调研 false）。
- `AgentLoop.Agent<R>`：`messages`、`responded`、`handle`、`sawPage`、`answered`、`stopped`、`limitTurn`、`summarized`、`roundDone`。
  - `HarnessLoop.Round implements Agent<AgentRun>`：`messages` = `FillContext`；`responded` = 原来的 `recordUsage`（`modelCalls`、`tokens`、`model_call` 日志）；`limitTurn` 先记 `budget` 日志；`roundDone` = `progress.accept(current())`；`toolCalls` 改成「之前的 + `loop.calls()`」（值和原来每次加一一样）。开头取页（`startPage`、断开 / 读不到时直接结束）留在 `go()`，取到后 `loop.showPage` 再 `loop.run(this)`。两个 `ended(...)` 的签名不变，里面换成 `() -> end.apply(current())`，所有调用处没动。
  - `SessionLoop.Round implements Agent<Void>`：`messages` = 系统提示词 + 上下文 + 旧网页只留开头；`sawPage` = 存进 `SessionPages` + 更新 `latest`；断开时 `loop.forgetPage()`；`ask_user` 的结束 = `ending(() -> null)`。
- 新 `harness/runtime/ToolArgs`：两边逐字相同的 `parse` / `text`（静态导入）。`strings` 两边不同（调研接受单个字符串），各留各的；`maps` 只有申请用。
- 每个 Agent 还留着的（有意不合并）：浏览器工具的分发和守卫、`TabLost`、断开策略（申请结束运行等你重连；调研这次调用失败、清最新页面、下一条消息里第一次用浏览器时自动重连一次）、各自的停止 / 只回文字 / 上限文字。两个类的说明和 `package-info` 写明了这些差别。浏览器分发没合并的原因：两边每种异常的处理和结果文字都不同（申请还有 C 级字段、重复检测、运行日志、报告、批准），合并要给每处加参数，代码只会更多；以后真要统一断开策略，先改 master 7.1 再动。
- 没改：`Conversation`（补中断调用）、`ModelCaller`、`LoopObserver`、`LoopDetector`、守卫、`BrowserSession`、提示词和工具说明（`ApplyAgent`、`ResearchAgent`、`FillContext`、`ControlTools`、`ResearchTools`、`BrowserTools`）、`ApplicationRunner`、`SessionRunner`、`HarnessLoop` / `SessionLoop` 的公开构造和 `run(...)`。
- 新旧对照（临时程序，在 scratchpad，不进仓库）：脚本化假模型（`OpenAiChatOptions` 当选项，按顺序回预先写好的回复，可以在被调用时按停止）+ `FakeBrowser` 表单夹具 + `TestStorage`。14 个场景——申请：多页填写 / `update_plan` / `read_skill` 失败 / 点击带说明放行 / 没给说明交回模型 / `find` / 提交按钮拒绝 / `finish` 后的调用写「没有执行」（`multi-page`）；同页有提交按钮问用户 → `ask_user` 记批准 → 接着做放行一次 → `finish` 不给 `page_shows_submitted`（`submitted-page`，两次运行）；C 级字段 / 未知工具 / `browser_tabs list`、`select` 缺参数和正常切换 / 参数不是 JSON / `update_plan` 空 / 只回空白文字（`c-class`）；上限 3 次的总结和「继续」后再到上限、空总结兜底；工具后停止、再回复；调模型时停止；运行中浏览器断开；重复失败和重复写的提醒。调研：没开页时的浏览器工具、导航被网址检查拒绝、`readOnly` 放行「下一步」、`find`、`web_fetch` 内网拒绝、`web_search` 没有来源、`add_lead`（含 note）和重复、`list_jobs`、`update_lead` 错误、`read_skill` 失败、未知工具、坏参数、`ask_user` 后的调用写「没有执行」；40 次上限和空总结；调模型时停止；工具后停止；断开后接着回文字；只回 null 文字。每个场景记下：每次发给模型的全部消息和工具名、观察者事件序列（状态、开始时间、有没有用时）、每轮 `progress`（对话条数、用量、`lastPage`）、返回的 `AgentRun`（状态、给用户的话、问题、上传位置、计划、用量、`lastPage`、全部对话和结果）、transcript（`append`）、假浏览器收到的调用、storage 里的运行日志 / 报告草稿 / `job.md` / `pages/`。改之前连跑两次输出一致（57 万字），换用 `ToolArgs` 后、`SessionLoop` 换用后、`HarnessLoop` 换用后、最后 `clean verify` 之后各跑一次，**和改之前逐字节相同**。
- 「必须保持不变」逐条核对：1 ✓ 上限、说明、总结、「继续」结尾、空总结兜底（对照 h4、s2 逐字相同）；2 ✓ 停止的三处检查、「没有执行」、`REJECTED`、用时 0、`done` 照发（h5、h6、s3、s4）；`STOPPED` 常量没动；3 ✓ `ask_user` / `finish` 的 `AgentRun` 和回执（h1、h2、s1）；4 ✓ 只回文字和兜底（h3、h5、s5、s6）；5 ✓ `LoopDetector` 四处没动，提醒文字相同（h8）；6 ✓ 守卫代码没动，批准用一次就清（h2 第二次运行 `approved=null`），`readOnly`（s1），`SubmitGuardTest`、`BrowserToolPolicyTest` 通过；7 ✓ `browser_tabs` / `TabLost` 代码没动（h3），`BrowserSessionTabsTest`、`BrowserToolsTest` 通过；8 ✓ 只有最新快照原样发、旧的省略（对照里每次发给模型的消息逐字相同），调研旧网页只留开头的代码只是改成传参；9 ✓ 用量和 `model_call` 日志的 step（各场景 `usage`、运行日志相同）；10 ✓ 观察者事件顺序和次数相同，`RuntimeEvents` / 两个 Runner 没改；11 ✓ 每个结果的状态相同；12 ✓ `Conversation` 没改；13 ✓ `SessionPages` 内容和 transcript 顺序相同（s1、s5）；14 ✓ 可重放工具的代码没动（s1 的 `add_lead` 重复回执相同）；15 ✓ 提示词、工具说明类没改（对照里系统提示词和工具名相同）；16 ✓ 公开签名没改，`ApplicationRunner`、`SessionRunner` 没改，编译通过。
- 测试：没有改，也没有新加（两个循环本来没有自动测试，新旧对照是临时的，不进仓库）。
- 验证：`mvn -B clean verify` 退出码 0（surefire 616、failsafe 1）。真实 Chrome、真实模型：未运行。

### K 实际结果

- 第 3 条：`ApplicationRunner.queued` 换成 `LinkedHashMap<String, Object>`（Job → 排队凭证）。`submit` 排队时放新凭证；任务开头在锁里 `queued.remove(jobId, 凭证)`，排过队而凭证已经不在（被排队时的停止移出，或者停止后又重新排了一次）就直接返回：不设 `runningJob`、不跑、不存盘、不发事件、不回报。`waitingInLine.remove` 挪到这个判断之后（重新排队时不会删掉新任务的 Future）。`stop`、`status` 只是 `contains` → `containsKey`，注释改了一句。
- 第 4 条：`RuntimeEvents.Subscriber.offer` 在队列已有 `BUFFER` 条时取消订阅、清空队列、返回 false；`publish` 用迭代器移出它，把 `dropped` 回调交给投递执行器（虚拟线程）跑，发布方不等。`subscribe` 多参数 `Runnable dropped`；`EventController` 传 `emitter::complete`（`ResponseBodyEmitter.complete` 和卡住的 `send` 抢同一把写锁，所以放在投递线程里，不在发布方）。卡住的投递任务在 `send` 返回或出错后看到已取消就退出。`EventController` 类注释加一句。
- 新测试 `workflow/RuntimeEventsTest`（1 条，门闩，不睡眠）：卡住的订阅积压满后被丢掉、`dropped` 被调用，发布方没有被挡住（循环跑完）。第 3 条没加测试，理由见「K 施工细节」。
- 「必须保持不变」逐条核对（读最终代码）：1 ✓ `LinkedHashMap` 按插入顺序，执行器仍单线程；2 ✓ 旧任务在锁里就返回，不碰浏览器、不占 `runningJob`；3 ✓ 在跑的停止仍只 `stopRequested.add`，`HarnessLoop` 那边没动；4 ✓ `stop` 的排队分支没改：同步存 `STOPPED_IN_QUEUE`、`live.remove`、发 `finished`；5 ✓ 那条路不调 `reportToSession`，旧任务返回得更早；`stoppedInQueue` 那条路的「用户按停止不回报」没动；6 ✓ 同一订阅仍只有一个投递任务按队列顺序发，`subscribe` 的补发 / `reset` 判断和 `EventController` 的 `caught_up` 没动；7 ✓ 发布方只改内存（取消、清队列、移出列表），关连接交给 `delivery.execute`。
- 验证：`mvn -B clean verify` 退出码 0（surefire 602、failsafe 1）。因为 J 同时在改 `BrowserSession`，工作区里 J 还没做完的 `BrowserSessionTabsTest` 有两条失败（和这次改动无关），所以是在 scratchpad 的副本里跑的：`BrowserSession.java` 用暂存区的版本、去掉 `BrowserSessionTabsTest`，其余与工作区一致（含本次改动）。在工作区副本原样跑时（含 J 的未完成改动）只有那两条失败，`RuntimeEventsTest` 通过。真实浏览器里排队时按停止、卡住的 SSE 连接：未运行。
