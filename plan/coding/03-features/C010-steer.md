# C010 · 运行中插话（steer）

- 依据：用户（2026-10-24）：「参考 deep agents / OpenMAIC：Agent 执行输出时，用户可以打字打断或改变流程（steer）。」
- 现状：Session 对话在跑时发消息 409「Agent 还在回上一条消息……」；申请工作台在跑时回复也 409。只能先停止再发。
- 调研：`plan/reference/research-agent-design.md`「运行中插话（steer）」。

## 目标

运行中随时能打字：补充信息、纠正方向、「先别做这个，改做那个」。消息不拒绝，Agent 下一步看到并自己决定怎么调整。

## 不做

- 不中止正在执行的工具（浏览器写操作不做一半）；不做 rollback。
- 不加确认、预算或新状态；不改提交守卫、白名单、标签页记账、C009。
- 申请工作台运行中不收「回答问题、选上传文件、附件」（要改上传区和文件编号），只收一句话。

## 做法

1. **收件箱**（`domain/Inbox`）：每个 Session / Job 一份。运行中到的消息放进去，返回 202。
2. **共用循环**（`AgentLoop`）：每次调模型之前（上一批工具都执行完、结果放进对话以后）取走全部，作为用户消息加进对话；先查停止再取。申请 Agent 取到后立刻存盘；推送一条 `changed`，界面重读。
3. **跑完还有的**：Runner 在结束的那段锁里取走，写进对话并开始下一次运行（申请线程：停在提交前时算一轮修改，和「要求修改」同一个口径；用户已定下的不再跑，记日志）。这次不回报、不发 finished。
4. **打断并发送**：前端先「停止」再「发送」。循环先查停止，这条留在收件箱，结束时按第 3 条接着跑。不新增后端接口。
5. **申请线程的插话**在对话里以「用户插话：」开头；Session 里就是一条普通用户消息（可带附件）。
6. **提示词**：ResearchAgent、ApplyAgent 各加一句「中途插话优先，和计划冲突时按插话改」（ApplyAgent 用 `update_plan` 改计划）。
7. **前端**：两个输入框运行中都能发；发出后在消息流里显示「已插入，Agent 下一步会看到」（后端 `inserted` 字段，取走后变成对话里的一条）；停止按钮保留；有内容时多一个「打断并发送」。

## 回写 master（已完成，用户批准 2026-10-24）

已按代码写进 `plan/master/agent-runtime.md` 7.1（Worker 池那条之后）和 7.7 `ApplicationRunner` 一行。和下面原拟文字的差别：代码里申请线程**排队时**发的话也进收件箱（C011 合并后），排队时被停下也会接着跑；申请线程用户已定下时不再跑；Session 插话可带附件；工作台带答案 / 文件 / 附件时 409。原拟文字留作记录：

原拟：`plan/master/agent-runtime.md` 第 146 行后加一句：

> - 运行中用户可以随时发消息（C010）：放进这次运行的收件箱，下一次调模型之前作为用户消息加进对话，不打断正在执行的工具；运行结束时还没看到的写进对话并接着跑。要马上改方向时先停止再发。

第 213 行 `ApplicationRunner` 的「回复」补一句「运行中的回复是插话，只收文字」。

## 怎么确认能用

- `mvn -B clean verify`、`npm run build` 通过。
- 本机真实跑（用户）：Session 对话里让 Agent 撒网调研，跑的过程中发「只要上海的」→ 输入框下出现「已插入」，下一步 Agent 按上海调整；申请工作台填表时发「教育经历先空着，先填实习」→ Agent 下一步改计划；按「打断并发送」→ 当前运行停下，马上按这条重新跑。

## 实际结果

2026-10-24 施工：

- 新增 `domain/Inbox`（put / take / peek）。`AgentLoop` 构造多一个 `inbox`（取走函数），`run()` 在停止检查之后、上限检查之前调 `heard()`：加进对话 → `Agent.heard`（申请 Agent 存盘）→ `LoopObserver.heard`（`RuntimeEvents` 发 `changed`）。`SessionLoop.run`、`HarnessLoop.run` 各加一个带 `inbox` 的重载，原有重载不变。
- `SessionRunner.send`：在跑时放进收件箱、发 `changed`、返回（控制器本来就回 202）；第一条消息改标题的判断也看收件箱。结束段锁里：申请回报之后取走收件箱，写进 transcript 并 `submit`，这时不发 `finished`。`SessionView` 多 `inserted`。
- `ApplicationRunner.reply`：在跑或排队时走 `insert`：带答案 / 上传位置 / 附件时 409「运行中只能插一句话……」，空文字 400，否则放进收件箱（「用户插话：」开头）。结束段锁里和排队时停止的分支都调 `resumeWithInserted`：合成一条用户消息，停在提交前时轮数加一、记 `requestChanges`，否则记 `userSignal`；放回上传区原件、`resumed`、转 PREPARING、存盘、`submit`；这时不回报 Session、不发 `finished`。`ApplicationStatus` 多 `inserted`。
- 提示词各加一句。前端：`Composer` 运行中发送键和停止键并列，有内容时显示「打断并发送」；`MessageList` 显示 `inserted`；Session 页、申请工作台接上（工作台运行中只发文字，附件留在输入框）。
- 验证：`mvn -B -q clean verify` 退出码 0（没有测试断言过这两处 409，没改测试、没新增测试）；`npm run build` 通过。真实模型、真实 Chrome：未运行。
