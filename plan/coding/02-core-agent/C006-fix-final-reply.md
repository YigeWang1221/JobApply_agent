# C006-fix · 每轮结束留一句结果；申请回报不再把 Session 的回复折起来

- 对应 Master：[ui.md](../../master/ui.md)「对话视图」「申请工作台」；[agent-runtime.md](../../master/agent-runtime.md)（循环怎么结束、子 Agent 回报）。
- 修的是：[C006-front](C006-front-chat-ui.md)（一轮 = 用户一句 + 过程 + 最后的回复）、[C006-fix 运行时](C006-fix-runtime.md) 第 2 节（申请线程回报 Session）、[C024](../04-refinement/C024-manual-outcome-reason.md)（`noteToSession`）。
- 依据：用户反馈（2026-11-04）：
  1. 一轮对话结束后，思考和工具调用折成一行，这没问题；但下面没有留下最后的文字或状态，看不出停在什么状态（做完了？在等我？卡住了？加了什么？）。右栏有状态，但对话里也要写一句，**不算重复**。Session 和工作台都一样。
  2. 在 Session 里让 Agent 搜岗位，它在对话里写了岗位和要求、加进了候选池；去那个岗位的工作台点开始投递，回到 Session，前面写岗位详情的回复都被折起来了，要看还得点开。
- 状态：**已确认，已施工，等用户在本机试**（2026-11-04）。第 5 节的问题用户已答（答案写在每条后面）；和第 2 节原设计不同的地方以第 5 节和第 6 节施工记录为准。

## 1. 根因

### 1.1 一轮结束时没有可见的结果

前端把一轮里**最后一条有文字的** Agent 消息当「回复」显示在折叠块下面，其它有文字的消息都变成折叠块里的 `note`（`frontend/src/chat/process.ts:50-61`）；折叠块不在跑时默认收起（`ProcessBlock.tsx:11-12`），`note` 只在展开后出现（`ProcessBlock.tsx:26-27`）。所以「结果」能不能看见，全看最后一条消息有没有文字、文字是不是结果。下面几种情况都会落空：

| 情况 | 证据 | 用户看到的 |
| --- | --- | --- |
| **工作台：模型调 `finish` 结束** | `finish` 的 `summary` + 「请在 Chrome 里核对……」只进 `AgentRun.message`（`HarnessLoop.java:1187-1214`），不进对话；`Views` 只把 `ask_user` 的 `message` 拼进文字（`Views.java:414`），`finish` 没有 | 折叠块下面是某一步的旁白（「现在点下一步」），或什么都没有；「停在提交前」只在右栏 `StatusNote`（`JobPage.tsx:440`） |
| 工作台：用户按停止 | `stopped()` 只返回 `waiting(STOPPED)`，不往对话里写（`HarnessLoop.java:370-372`）；Session 是写的（`SessionLoop.java:222-224`） | 没有「已停下」 |
| 工作台：浏览器断开、出错 | 断开时工具结果「失败：浏览器连接断开了」后直接结束（`HarnessLoop.java:1396`）；出错只写 `result.failed(...)`（`ApplicationRunner.java:816, 833`）；工作台的 `MessageList` 没传 `error`（`JobPage.tsx:325`），Session 传了（`SessionPage.tsx:143`） | 对话里没有「出错了 / 断开了」，只在右栏 |
| 工作台：模型只回了空文字 | 对话里记的是空文字，给用户的话「我没有想好下一步……」只进 `waiting(...)`（`HarnessLoop.java:363-366`）；Session 会补一句（`SessionLoop.java:215-218`） | 什么都没有 |
| **Session：结果写在调用工具之前** | 模型常在同一条消息里先写岗位清单和要求、再调一串 `add_lead`，最后一条只说「已加入候选池」。提示词只有一句「最后告诉用户找到了什么……」（`ResearchAgent.java:34`），没说最后一条要自成一体 | 详情在折叠块的 `note` 里，下面只剩一句短话 |
| Session / 工作台：出错后刷新或换了一轮 | `error` 只是 Session 当前状态（`SessionPage.tsx:117`），不进对话；下一轮一开始就没了 | 历史里看不出那一轮是出错停的 |

结论：**不是前端把最后的文字当成步骤折起来**（最后一条文字一直在外面），而是三件事叠在一起：结束的原因和结果有一半不进对话（只进运行状态，界面只在右栏显示）；模型可以在工具调用中途就把要说的说完；「回复 = 最后一条文字」这个规则对上面两种情况没有兜底。

### 1.2 回到 Session 时前面的回复被折起来

调用链：

1. 工作台「开始投递」→ `ApplicationRunner.start`；C021 起第一轮在工作台里排简历、用 `ask_user` 等用户确认 → 运行结束为 `waiting_user`。
2. 运行结束 → `events.finished` → `reportToSession(result)`（`ApplicationRunner.java:864-867`）；`WAITING_USER` 写「申请 X 在等你：…… 去申请工作台回复。」（`ApplicationRunner.java:875-897`）。C024 的 `noteToSession` 走同一条路（`ApplicationRunner.java:245-250`；`JobController.java:202, 215`）。
3. `SessionRunner.report` 把它作为一条 **note** 记进 Session 的对话，Session 在跑时先排队、跑完再接在后面（`SessionRunner.java:113-125, 413-420`）。
4. `Views` 把 `REPORT` 开头的 note 变成**一条普通的 assistant 消息**，没有任何标记（`Views.java:444-450`）。
5. 前端 `toTurns` 只在用户消息处断开一轮；没有用户消息挡着的 assistant 消息都并进上一轮（`process.ts:35-43`）。于是回报成了上一轮「最后一条有文字的消息」= 新的回复，原来写岗位和要求的那条回复被挤进折叠块的 `note`（`process.ts:50-61`），折叠块默认收起（`ProcessBlock.tsx:11-12`）。
6. 如果上一轮是以 `ask_user` 结束的（「这几个要投哪个？」），`turn.asked` 为真、回报的结尾又不是那句提问，整条回报会被画成「需要你回复」的提问块（`MessageList.tsx:130-136`）。

**判断：是结构问题，不是并发问题，也不只是显示规则。** 数据没有被覆盖：回报追加在对话末尾，原来的回复原样在日志里；没有重放、没有重置流、没有把那一轮标成作废，`TurnView` 的 key 也没变（`MessageList.tsx` 里 `turn.key` = 序号 + 用户消息时间）。根因是回报在 `Views` 那一层丢了身份，变成和模型回复一样的 assistant 消息，前端的分组规则（只按用户消息分轮、最后一条文字是回复）就把它算进了上一轮。只改前端也能遮住，但前端认不出哪条是回报（只能靠文字开头去猜），所以要从数据上区分。

## 2. 怎么改

参考：deep-agents-ui（f6a4f34）`ChatMessage.tsx` 每条 AI 消息的文字都常显、工具调用是各自可展开的框；Claude.ai 同样是文字常显、工具调用折起来；ChatGPT 的「思考」折起来、最后的回答在下面。共同点：**结果是一段独立、常显的文字**，系统通知不和模型的回复混在一起。

### 2.1 每轮结束都留下一句结果（后端写进对话）

- 一次运行结束时，如果对话里最后一条 Agent 消息没有给用户看的文字，或者结束原因不在文字里，循环在结束处补一条「结束说明」写进对话（持久化，刷新、换轮都还在）。按结束原因：
  - `finish`：`summary` + 现在的提示（填好了停在提交前 / 页面像已经投了请确认），即现在的 `AgentRun.message`；
  - 停止：「已按你的要求停下……」（工作台补上，和 Session 一致）；
  - 浏览器断开、出错：一句原因 + 要用户做什么（「准备好后回复，会先重新连接」「回复让它再试」）；
  - 模型只回了空文字：现在给 `waiting(...)` 的那句话。
- 「结束说明」用一种自己的对话条目（例如 `AgentRun.Turn` 新增 `status`，或 note 加固定开头），给模型看时标明是系统写的；`Views` 输出成带类型的消息（见 2.3）。`ask_user` 结束的不用补（`message` 已经在文字里）。
- 出错写进对话后，Session 的 `error` 行照旧（给「重试」按钮用）。

### 2.2 提示词：最后一条消息自成一体

- `ResearchAgent` 第 4 条改成：结束这一轮时，**最后一条消息**要单独看得懂：找到了哪些岗位（公司 · 岗位 · 关键要求 · 招聘状态）、放进候选池的、拿不准的、现在要用户做什么。不要只在调用工具之前写，最后一句只说「已加入」。
- `ApplyAgent`、`FollowupAgent` 加同样一句：结束（`ask_user` / `finish` / 只回文字）时，写清这次做到哪、现在的状态、要用户做什么。

### 2.3 前端：回报单独成块；结果常显

- `ChatMessage` 加 `kind`（`reply` 默认 / `report` 申请回报与 C024 通知 / `status` 结束说明）。旧数据没有 `kind` 时按原样处理。
- `toTurns`：`report` **不并进上一轮**，单独渲染成一张通知卡（图标 + 原文 + 「打开工作台」），不参与「最后一条文字 = 回复」，不会被画成「需要你回复」。上一轮的回复留在原处、常显。
- `status`：接在那一轮的回复下面，一行小字 + 状态色（填好了 / 在等你 / 出错了 / 已停下），常显，不进折叠块。
- 折叠块摘要里写出它装着几段说明（「……，2 段说明 · 用时 40 秒」），让人知道里面还有文字。
- 工作台 `MessageList` 也传 `error`（和 Session 一样），出错在对话里看得到。

不改：折叠本身、运行中展开、右栏状态、回报的触发条件和文字（`reportToSession`）、Session 不被回报唤醒。

## 3. 要改的地方（大致）

- 后端：`HarnessLoop`（`finish` / `stopped` / 断开 / 空文字的结束处补结束说明）、`ApplicationRunner`（出错结束时补一条）、`SessionLoop` / `FollowupLoop`（出错时补一条）、`AgentRun.Turn`（结束说明的类型）、`Views`（`ChatMessage.kind`，回报和结束说明带类型）、`ResearchAgent` / `ApplyAgent` / `FollowupAgent`（提示词一句）。
- 前端：`api.ts`（`kind`）、`chat/process.ts`（分组）、`chat/MessageList.tsx`（通知卡、结果行）、`chat/ProcessBlock.tsx`（摘要计数）、`pages/JobPage.tsx`（传 `error`）、`styles.css`。

## 4. 怎么确认

- 用户在本机试：
  1. Session 里让它搜岗位 → 这一轮折叠块下面是完整的岗位清单 + 加了几个 + 要你做什么。
  2. 在右栏点「开始投递」→ 工作台排完简历在等你 → 回到 Session：前面那条岗位回复还在、常显；下面多一张「申请 X 在等你」的通知卡，点得进工作台。
  3. 工作台填到提交前（`finish`）→ 对话最后有「填好了，停在提交前……」一段；按停止 → 有「已停下」；拔掉浏览器连接 / 模型出错 → 对话里有一句原因和下一步。
  4. 刷新页面后以上都还在。
- 构建：`mvn -B clean verify`、`npm run build` 退出码 0。

## 5. 待用户确认（已确认，2026-11-04）

> 用户答：1 选 **B**：Agent 的每段文字都常显，只折思考和工具调用，Session 和工作台都这样，去掉「最后一段文字才是回复」的规则（2.3 里「折叠块摘要写几段说明」因此不做）。2 **系统按结束原因写**；正常情况靠提示词让模型自己写清状态，`finish` 的 `summary` 显示在对话里；停止、断开、出错、空回复由系统补一条，存盘。3 回报是**按到达时间排的通知卡**，绝不并进上一轮，绝不显示成提问块。4 C024 通知**用同一种卡**。

1. **结果只看最后一段，还是像 Claude.ai 那样每段文字都常显？** 推荐：最后一段 + 结束说明常显，中间的旁白留在折叠块里（工作台一次运行有几十段旁白，全显示会很长）。另一种是 deep-agents-ui / Claude.ai 式：文字都常显，只有连续的思考和工具调用折起来；Session 里更不会漏，但工作台会变长。也可以 Session 用后者、工作台用前者。
2. **结束说明由系统按结束原因写（推荐，固定、可靠），还是在结束时再让模型说一句**（多一次模型调用，措辞更自然，但模型出错 / 断开时说不了）？
3. **申请回报的通知卡放在对话里的时间位置（推荐），还是集中在对话顶部 / 右栏？** 推荐放在对话里，按到达时间排。
4. C024 的「投递失败 / 不投了」通知也用同一种通知卡？推荐是。

## 6. 施工记录（2026-11-04）

后端：

- `domain/AgentRun.java`：`Turn.STATUS`（固定开头「系统说明（这次运行怎么停下的，不是用户说的话）：」）、`Turn.status(text)`（存成 note）；`AgentRun.noted(turn)`（对话末尾追加一条）。
- `harness/runtime/HarnessLoop.java`：`told(run, text)` 在结束的 `AgentRun` 上追加结束说明。用到的地方：只回了空文字（`BLANK_REPLY`）、停止（`STOPPED`）、开场读不到页面（`ToolFailed`）、浏览器断开（`disconnectedRun`，各处断开都走它）、`finish`（有 summary 时只补现在的提示那句，没有时补整句 message）。`ask_user`、上限总结不补（话已经在对话里）。
- `workflow/ApplicationRunner.java`：`crashed(run, message)` = `failed` + 结束说明「出错停下了：…… 在下面回复，它会再试。」；用在申请线程抛错、结束时更新台账出错两处。
- `workflow/SessionRunner.java`：`noteCrash(error, append)`，对话 Agent 出错时往 Session 对话写一条；`FollowupRunner` 后续段出错时用同一个。
- `api/Views.java`：`ChatMessage` 加 `kind`（`report` / `status` / null，保留 6 个参数的构造）；note 按开头分成回报（`SessionRunner.REPORT`）和结束说明（`AgentRun.Turn.STATUS`）；`finish` 的 `summary` 像 `ask_user` 的 `message` 一样拼进这条消息的文字。
- 提示词：`ResearchAgent`（身份一句「每次停下都写清做了什么、现在什么状态、要用户做什么」；第 4 条「这一轮的最后一条消息要单独看得懂……不要只在调用工具之前写」）、`ApplyAgent`、`FollowupAgent` 各一句。

前端：

- `api.ts`：assistant 消息加 `kind`。
- `chat/process.ts`：`Turn` 改成有序的 `blocks`（`text` / `ask` / `steps` / `status`）。一条消息按「思考 → 文字 → 工具调用」放，相邻的 `steps` 合成一块；`ask_user` 的原话在文字末尾时单独成 `ask` 块，拿不到原话时整段是提问（只看这条消息自己的 `ask_user`，不再按整轮判断）。`report` 消息先结束当前一轮，再单独成一个 `notice` 轮。`summarize(steps, live)` 按一块的步骤计时。去掉 `note` 条目和 `reply` / `ask` / `asked` 字段。
- `chat/ProcessBlock.tsx`：只收 `steps`；运行中只有最后一块展开。
- `chat/MessageList.tsx`：按 `blocks` 依次渲染；结束说明是 `.runend` 一行；`NoticeCard`（「申请通知」+ 时间 + 原文 + 能从文字里认出岗位编号时的「打开工作台」）；复制按钮复制这一轮全部文字；最后一轮以结束说明收尾时，出错行只留「重试」，不再重复原因。
- `styles.css`：`.runend`、`.noticecard`；删掉 `.process .note`。

和第 2 节的出入：工作台 `MessageList` 没有另传 `error`，出错原因已经作为结束说明写进对话；折叠块摘要不写「几段说明」（选 B 以后文字不进折叠块）。

验证：`backend/` 里 `JAVA_HOME=…openjdk@25… JOBAPPLY_APP_CONFIG=/tmp/jobapply-nonexistent-app.yaml mvn -B clean verify` 退出码 0（Tests run: 662 + IT 1，Failures 0、Errors 0，没有测试要改）；`frontend/` 里 `npm run build` 退出码 0。真实模型、真实 Chrome、用户本机试：未运行。

## 7. 用户改动（2026-11-07）：Session 不再显示回报通知卡

用户定：对话里不再显示申请回报的通知卡（「申请 … 在等你：…… 去申请工作台回复。」、「已经填好，停在提交前…」、「出错停下了…」，以及 C024 `noteToSession` 的通知）。状态看侧栏每个工作台前的彩色圆点，卡片重复。

- 只改界面。后端不变：`ApplicationRunner.reportToSession`、`SessionRunner.report` / `noteToSession` 照样把 `SessionRunner.REPORT` 开头的 note 写进 Session 对话，Session 的 Agent 仍拿它当上下文；`Views` 仍把它标成 `kind: "report"`。
- `chat/process.ts`：`toTurns` 遇到 `report` 只结束当前一轮（前后消息不并成一轮），不再生成通知轮；`Turn` 去掉 `notice`。
- `chat/MessageList.tsx`：删 `NoticeCard` 和 `turn.notice` 分支（及不再用的 `go` 引入）；`styles.css` 删 `.noticecard`、`.noticecard-h`。
- 工作台（`JobPage`）用同一个 `MessageList` / `TranscriptTurns`，若出现 `report` 也一样不显示；一次运行的结束说明（`status`，`.runend`）不变。
- 验证：`frontend/` 里 `npm run build` 退出码 0；后端没改，未跑 Maven。用户本机看：未运行。
