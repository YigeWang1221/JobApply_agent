# C006-fix · 运行时结构修正：事件日志、子 Agent 回报、用户事实优先、可重放的工具

- 依据：用户转来的 4 个问题（2026-10-23，已逐条核实属实），以及用户的要求：「参考 OpenMAIC 和 Deep Agents，从流程和结构上解决，不要只打补丁」。参考来源见 [research-agent-design.md](../../reference/research-agent-design.md)「运行时：事件流、重连、子 Agent 回报」（本次只读源码：OpenMAIC dff201b、deepagents 3e7d5fa、deep-agents-ui f6a4f34、@langchain/langgraph-sdk 1.12.7）。
- master：[agent-runtime.md](../../master/agent-runtime.md) 第 8 节「运行时的结构规则」（用户 2026-10-23 说开工，即确认；父 Session 不自动唤醒按默认）。
- 不做：多进程 / 多机（本机一个进程）；模型文字逐字流式；上下文压缩。

## 四个问题背后是同一类结构缺陷

| 问题 | 补丁式的修法（已做 / 原想做） | 结构上的根子 |
| --- | --- | --- |
| 1 先放着后无法登记提交 | 让用户能从任意准备状态进 `user_submitting`（已做） | 流程状态机把**用户陈述的事实**当成要按流程走的步骤；Agent 的进度状态和真实世界的事实混在一条状态线上 |
| 2 附件不能满足必填上传位置 | 有附件就放行（已做后端） | 两套文件来源（资料库原件、附件）分开校验；服务端替模型判断「这个位置填好了没有」 |
| 3 Session 页看不到申请结束 | 前端每 5 秒轮询（**没做**，用户否决） | 申请 Agent 是对话 Agent 启动的**子任务**，但它结束时不回报给父 Session；推送是内存里的、各管各的、不能续接 |
| 4 重新生成把失败的操作说成已生效 | 按结果分成功 / 失败（已做） | 有副作用的工具**不可重放**，所以要靠系统合成一句「已经生效，不要重复」去约束模型；这句话本身就可能是错的 |

## 1. 事件日志是唯一的真相，推送只是它的尾巴（OpenMAIC `sessions/[id]/events`，LangGraph `joinStream`）

参考做法：
- OpenMAIC：每个 Session 一条只追加的事件日志，事件带递增序号；SSE 路由是**纯读者**：先补发 `seq > Last-Event-ID` 的事件，再跟着新事件；补完发一个 `caught_up`；运行结束**不关流**；25 秒心跳；断开后浏览器原生 `EventSource` 带 `Last-Event-ID` 自动重连，走同一条补发路径；通知丢了只影响延迟，保底轮询兜底。另有一条按用户汇总的稀疏流（`owner-events`）给侧栏和列表用。
- LangGraph（Deep Agents 界面）：运行有 `run_id`，客户端记下正在进行的运行，页面打开时 `joinStream(runId, lastEventId)` 接上；运行结束后重新读一次线程状态。

本仓库改成：
- 一条**应用级事件日志**（内存环形缓冲 + 递增序号 + 启动纪元），每个事件带 `session`、`job`（有就带）。来源：两个循环的每一步、状态变化、台账写入（JobLedger 每次 transition / markSubmitted / recordStatus 都发一条）。真相仍在文件里（transcript、job.md、run state），事件只说「什么变了」。
- `GET /api/events?session=<id>` / `?job=<id>` / 不带参数（全局，给侧栏）：按 `Last-Event-ID` 补发、`caught_up`、心跳、不关流；纪元变了（应用重启）发 `reset`，客户端整页重读。替换现在的 `/sessions/{id}/events`、`/jobs/{id}/events` 和 `LiveFeed`。
- **Session 的过滤 = 这个 Session 的事件 + 它涉及的 Job 的事件**（Job 的 `session` 字段 / `job.md` 里记的 Session）。这一条直接解决问题 3。
- 前端 `useLiveUpdates`：不再「出错就关掉、永久轮询」；用原生 `EventSource` 自动重连；收到 `caught_up` / `reset` / 相关事件时重读视图。侧栏用全局流，去掉 5 秒轮询。
- 去掉的补丁：无（问题 3 原来的轮询方案不做）。

## 2. 子 Agent 结束时回报父 Session（Deep Agents `AsyncSubAgent` + `notify_parent`）

参考做法：Deep Agents 的异步子 Agent：父 Agent 的状态里记着它启动的任务（id、状态、run）；子 Agent 结束、出错或**在等人**时回报父 Agent；父 Agent 空闲时可以被唤醒（`notify_parent`，默认开）。

本仓库改成：
- 申请线程在 **ready（停在提交前）/ waiting_user / failed / 结束** 时，往发起它的 Session 追加一条记录（transcript 里的 `note` + 事件），写明结果和要用户做什么（「澜川 · 后端开发 已经填好，停在提交前；提交后告诉我」）。
- 父 Session 空闲时**不自动唤醒模型**（不替用户花 token、不刷屏）；界面靠事件刷新，确认卡片照常出现；用户下一句话时，对话 Agent 从 transcript 里看得到这件事，能接着说。要不要唤醒做成一个开关以后再说。
- 这样 Session 页、对话 Agent、侧栏看到的是同一件事，不靠前端猜。

## 3. 用户说的事实优先于 Agent 的流程状态（OpenMAIC：用户的回答就是下一条消息；Deep Agents：状态是事实，不是流程图）

- 规则：**用户报告的真实世界事实**（已提交、岗位下线、主动撤回、不投了）在任何未结束的状态下都接受，直接记下；正在跑的申请线程先请它停下，它结束时**不再覆盖**用户已经定下的状态。Agent 自己的进度状态（填写中、等你回答……）仍按状态机走。
- 已做的补丁保留并按这条整理：状态机里「Actor 是用户、目标是 user_submitting」从任何准备状态都放行；`ApplicationRunner.moveTo` 遇到用户已定的状态不再改；`/submitted` 先停线程。`/posting-closed`、`not-submitted` 同样走这条。

## 4. 有副作用的工具可重放：同一个调用重复执行得到同一个结果（OpenMAIC：按工具调用 id 幂等、带回执）

参考做法：OpenMAIC 的写操作「按构造幂等：同一个工具调用（同一个 call id）重放时复用上次的结果」，回执存在被改的对象上（例如 `import_pptx` 的 receipts）。

本仓库改成：
- 有副作用的工具（`add_lead`、`update_lead`、`start_application`、`mark_submitted`、`record_status`、`choose_resume`）都**按业务键幂等**，重复调用返回「已经是这样了」的回执，不再做第二次：`add_lead` 已按公司 + 链接去重；`start_application` 对已有申请线程拒绝（已做）；`mark_submitted` 对已投递的返回已记下的时间；`update_lead` / `record_status` 值相同时不写、不记事件。
- 重新生成：保留上一次有结果的调用和结果原文（模型自己读得到成功还是失败），**去掉**系统合成的「已经生效，不要重复」那句话，只留一句「用户要求重新生成；上面是上一次已经执行过的调用和结果」。问题 4 的补丁（按成功 / 失败分两列）随之删掉——工具可重放以后，不需要靠这句话约束模型。

## 5. 文件只有一张清单，上传位置是模型的请求，不是服务端的关卡

- 这个申请能用的文件只有一张带编号的清单（`AgentRun.files`）：来源可以是资料库原件，也可以是这次附的文件。右栏的上传位置下拉框同时列出两种来源；也可以不选，直接附文件。
- 服务端只校验「选的东西存在」，不再校验「必填位置填了没有」——那是模型在页面上看到的事，由模型判断，缺了它会再问（`ask_user`）。问题 2 的补丁（有附件才放行）换成这条。

## 6. 对话里没有结果的工具调用：读回时补一条「中断了」（deepagents `middleware/patch_tool_calls.py`）

- 应用重启或线程中途停下，transcript 里会留下没有结果的工具调用，下一次发给模型会出错（C006-agent 遗留）。读回对话时，对每个没有结果的调用补一条结果「这次调用被中断了，没有执行完；需要的话重新做」。Session 和申请线程都适用。

## 施工分工（multi-agent，2026-10-23）

接口约定（三个代理共用）：

- `GET /api/events`（全局）、`?session=<id>`（这个 Session + 它涉及的 Job）、`?job=<id>`。SSE：`id: <纪元>:<序号>`；事件名 `activity`（data：`{session?, job?, activity}`，activity 为 null 表示空闲）、`step`、`message`、`status`、`changed`（台账或视图变了：`{session?, job?}`）、`caught_up`（`{replayed}`）、`reset`（纪元不对：整页重读）；每 25 秒一行注释心跳。旧的 `/api/sessions/{id}/events`、`/api/jobs/{id}/events` 删掉。
- 上传位置的选择：`files` 数组里每项是原件键，或者 `attachment:<附件 id>`；空字符串 = 不选。服务端不再拒绝必填位置空着。
- A（后端运行时）：第 1、2、6 节。B（后端工具与状态）：第 3、4、5 节的后端。C（前端）：第 1 节的客户端、第 5 节的界面。

## 怎么确认能用

- `mvn -B clean verify`、`npm run build` 通过；被这次结构改动改坏的测试按新规则改（状态机、重新生成）。
- 你在本机：
  1. 对话里开始一个申请，留在 Session 页；申请停在提交前时，Session 页不刷新就出现确认卡片，对话里有一条记录。
  2. 申请时点「先放着」，再在对话里说「投了」，记成已投递、有下次查看时间。
  3. Agent 要求必填上传位置时，只附一个 PDF 就能发出去，Agent 把它传上去。
  4. 在做过 add_lead 的回复上点重新生成，候选池没有重复；上一次失败的调用，这次模型会重试。
  5. 关掉后端再打开，页面自动重新接上，不用手动刷新。

## 实际结果

（施工后填写）

### A（运行时）

- `workflow/RuntimeEvents`（新，替换 `LiveFeed`，一个 Bean，两个 Runner、台账、`/api/events` 共用）：事件带 `session`、`job` 和递增序号，纪元是启动时刻；内存环形缓冲最近 2000 条。也记着每个 Session / Job 现在在做什么和还没写进对话的工具结果（视图用）。Session 过滤 = `session` 相同，或者 Job 在订阅时查到的集合里（session.md 的 jobs、在这里发现的、申请线程属于这里的），或者之后和这个 Session 一起出现过。
- `api/EventController`（新，替换 `EventStream` 和两个旧的 `/events`）：`GET /api/events[?session=|?job=]`。`Last-Event-ID` 同纪元且缓冲里还有：补发；纪元不对、太旧、比现在还新：先发 `reset`（data `{}`）。之后**每条新连接**都发 `caught_up`（`{"replayed":n}`；`reset` 和 `caught_up` 带 id = 当前位置，浏览器下次重连就用新纪元）。补发期间来的新事件先排队，补完按顺序发。每 25 秒 `: ping`；12 小时超时，断开时取消订阅；运行结束不关流。
- 事件 data：`activity` `{session,job,activity}`（null = 空闲）、`step` `{session,job,step}`（和视图里的 ProcessStep 一样，思考也是一步）、`message` `{session,job,text}`、`status` `{session,job,status}`、`changed` `{session,job}`。对话 Agent 的事件 `job` 为 null；申请线程的事件带 `job` 和它所在的 `session`。
- `changed` 的来源：`JobLedger` 每次写完（新增 `JobLedger.Listener`，在 `afterChange` 里通知；幂等「没写」时不发）、Session 改名、两种附件上传、申请线程回报、Session 一次运行结束。
- 第 2 节：`ApplicationRunner` 一次运行结束（ready / waiting_user / failed）调 `Reporter`，`SessionRunner` 在发起它的 Session 的 transcript 记一条以 `SessionRunner.REPORT` 开头的 `note`，发 `changed`；不唤醒模型。Session 正在跑时先放着，跑完再接在对话后面（不插进一轮工具调用中间）。用户已经定了状态（user_submitting 之后、已投递、结束）时不报。`Views` 把这种 note 显示成一条 Agent 消息（去掉开头），前端不用改。
- 第 6 节：`Conversation.messages`（两个循环共用）对没有结果的工具调用补一条结果「这次调用被中断了，没有执行完；需要的话重新做。」；后面紧跟着结果条时并进那一条，否则单独插一条。存着的对话不改。
- 参考（只读）：OpenMAIC `app/api/agent/sessions/[id]/events/route.ts`、`owner-events/route.ts`（纯读者、按 `Last-Event-ID` 补发、具名 `caught_up`、25 秒心跳、不关流、游标太旧要求重同步）；deepagents `middleware/async_subagents.py`、`_parent_notifier.py`（结束 / 出错 / 等人时回报父 Agent，标明「不是用户的话」；本仓库不唤醒）、`middleware/patch_tool_calls.py`（补没有结果的调用）。
- 验证：`mvn -B clean verify` 通过（surefire 611、failsafe 1）。启动应用连 `/api/events` 手动看推送、重启后 `reset`：**未运行**。

### B（事实、工具、文件）

- 第 3 节（用户事实优先）：
  - 状态机：Actor 是用户时，准备中的任何状态都能进 `user_submitting`（已有）和 `paused`（新：「还没提交 / 先放着」）。`not_submitted`、`posting_closed` 本来就能从任何准备状态结束；`withdrawn` 仍只在投递之后（准备中的「不投了」就是 `not_submitted`）。
  - `JobLedger.advancePrep`（新）：Agent 推进 `prep_state` 在一把锁里读、判断、写；用户已定的状态（`JobLedger.userDecided`：结束、已投递、user_submitting / verification_pending / unconfirmed、paused）不改，用户自己（回复、重新开始）可以接着做 paused 的。`ApplicationRunner.moveTo` 只剩这一行调用，原来的先查后写删掉。ready / waiting_user / failed 三种 settle 都走它。
  - 用户已定状态的申请线程停下时在等回复（waiting_user），不再占着浏览器（`requireBrowserFree` 跳过它）。
  - `JobLedger.markSubmitted`：还在候选池（new / shortlisted）的先转为申请再记投递；已经投递过的原样返回（不写、不记事件、`applied_at` 不变）。
  - `JobLedger.recordStatus`：准备中的申请可以直接结束（`outcome` = `posting_closed` / `not_submitted`）；工具 `record_status` 的 enum 加了 `not_submitted`。
  - `JobController`：`/not-submitted` 先停正在跑的线程；已经是 paused / closed:not_submitted 时原样返回。`/posting-closed` 已经下线的原样返回。`/submitted` 不变（先停线程，已投递的由台账原样返回）。
  - 对话 Agent 的 `mark_submitted`、准备中用 `record_status` 结束：先请申请线程停下（`SessionLoop.Applications` 加了 `stop`）。
- 第 4 节（可重放）：结果文字照实写成回执，状态都是 done。
  - `add_lead` 重复：「已经在候选池里了：<id>（公司 · 岗位，状态），没有再加一条。要改信息用 update_lead。」
  - `update_lead`：和现在一样的状态 / 字段不写、不记事件；全都一样时「已经是这样了：<id> 的 状态 x、lead_note 和现在一样，没有改。」，部分一样时在结果后面补一句。
  - `start_application` 已有线程：「已经在申请了：<id> 已经有申请线程（<状态>），没有重新开始，填了一半的也还在。之后在申请工作台里继续；要从头重来请用户在工作台里操作。」（原来是「被拒绝」，改成回执：模型要的状态已经成立。）
  - `mark_submitted` 已投递：「已经记为已投递了：<id>（<状态>），投递时间 <applied_at>，下次 <next_check_at> 提醒。没有再记一次。」
  - `record_status`：已经以同一个 outcome 结束、或者已经在这个 phase 且上一条事件就是同一句话时不写，结果「已经是这样了：<id> 现在是 …，上一次已经记过，没有再记一次。」；只是看过了（没有 phase / outcome）每次都记 `last_checked_at`。
  - 重新生成：保留上一次执行过的调用和结果原文，去掉合成的「已经生效 / 没有生效」两列（`SIDE_EFFECTS`、`shorten` 删掉），只追加 `SessionRunner.REGENERATE`：「系统：用户要求重新生成上一次的回答。上面保留了上一次已经执行的调用和结果（成功还是失败看结果原文）；有副作用的工具重复调用是安全的，会返回已有的结果。请重新回答用户最后一条消息。」
- 第 5 节（一张文件清单）：`reply.files[i]` 是原件键、`attachment:<id>`（`ApplicationRunner.ATTACHMENT`）或空字符串；只校验选的东西存在，必填空着不拒绝。按位置顺序排编号：原件照旧写进 `materials`、复制到上传区；附件记成带位置名的 `StagedFile`（`attachment` = id，从附件目录上传）。同一个附件又出现在 `attachments` 里时不重复排号。发给模型的消息逐个位置写「「简历」：文件 3 x.pdf（资料库原件 / 用户附的文件）」或「（必填）：没有选」「（选填）：跳过了」；有必填没选时加一句「先看页面上是不是真的必填、用户这次附的文件能不能用；还缺就用 ask_user 再问用户」。`ApplicationStatus.attachments`（新）：`[{id, name, contentType, image, url}]`，url 为 `/api/jobs/<id>/application/attachments/<附件 id>`；`AttachmentStore.list()`（新，按时间排）。
- 一把写锁（协调补充）：`persistence/StorageLock`（新，进程内一个对象）。`JobLedger` 全部写操作、`SessionRepository` 的 create / appendTurn / rewriteTurns / writeStatus / rename / writeHandoff、`JobMaterials.write`、`JobAnswers.append` 都在这把锁里（原来各实例各锁各的；各处 new 的实例不变）。`addLead` 的去重和写入因此也是原子的。
- 工具结果的状态在产生处定下（协调补充）：`AgentRun.ToolResult` 加了带 status 的构造；`timed()` 保留已定的状态，`statusOf`（按开头猜）只给旧记录兜底（`Views` 读旧对话时用）。`SessionLoop`、`HarnessLoop` 用 `result` / `failed` / `rejected`（HarnessLoop 里叫 `failedResult` / `rejectedResult`）三个出口；字符串工具的参数错误改成抛 `IllegalArgumentException`，由 `handle` 记为 failed。修正：`web_fetch`「直接读取失败…浏览器也没打开 / 浏览器断开」现在是 failed（文字前加了「失败：」）；被跳过的「没有执行」是 rejected。前端 `process.ts` 可以只看 `step.status`。
- 测试：没有改测试，也没有新加（状态机测试、全部单测和 IT 按新规则仍通过）。
- 验证：`mvn -B clean verify` 通过（surefire 611、failsafe 1）。中途一次 `PlaywrightMcpClientTest` 因为本机启动浏览器 MCP 失败报错，重跑通过，和这次改动无关。启动应用手动走「怎么确认能用」2–4 条：**未运行**。

### C（前端）

- `chat/eventStream.ts`（新）：按地址共用一条 `EventSource`，订阅的人都走了才关。出错不关，交给浏览器带 `Last-Event-ID` 自动重连；只有连接被判死（`readyState` 为 CLOSED，比如后端没起来时代理回 5xx）才自己过 2 秒重建，之后间隔翻倍，最长 15 秒。新连接收到 `caught_up` 时整页重读，补上漏掉的内容。
- `chat/useLiveUpdates.ts`：
  - `useLiveUpdates({session}|{job}, running, reload)` 订阅 `/api/events?session=` 或 `?job=`。`activity` 只认本页的：Session 页认 `session` 是自己、`job` 为空的事件，工作台认 `job` 是自己的事件。`step`、`message`、`status`、`changed`、`caught_up`、`reset` 合并成一次重读（250 ms 内只读一次）。断开超过 3 秒，标题行显示淡色「重新连接中…」。还有东西在跑时每 30 秒保底重读一次，原来的 2 秒轮询删掉。
  - `useGlobalChanges(reload)`：侧栏（App）、全局 Dashboard 和 Project 页共用全局 `/api/events`，收到 `changed`、`status`、`caught_up`、`reset` 就重读，另每 60 秒保底重读一次。原来的 4 秒、5 秒轮询删掉。
- `api.ts`：删掉 `sessionEvents`、`jobEvents`；`ApplicationStatus` 加可选的 `attachments`。
- 申请工作台的上传位置：下拉框分两组，「这次附的文件」（`status.attachments` 加上输入框里已经传好的，值为 `attachment:<id>`）和「资料库原件」。可以不选。必填位置空着不再挡住发送，只提示「还有 N 个必填上传位置没选，Agent 会再问」；必填问题没答、要求修改没写说明仍然挡住发送。
- 验证：`npm run build` 通过。浏览器检查**未运行**，因为后端 `/api/events` 由 A 同时在写。
