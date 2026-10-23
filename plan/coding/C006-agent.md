# C006-agent · 让 Agent 的真实执行看得见、跟得上（给 C006-front 的数据）

- 依据：[C006-front.md](C006-front.md)「和 C006-agent 的接口约定」；用户要求（2026-10-23）：思考只在供应商真的给出时显示，不编造、不假设所有模型格式相同；重新生成要考虑有副作用的工具调用，不能重复动作；实现简单，不加不必要的抽象和测试流程。
- 目标：对话 Agent 和申请 Agent 跑的时候，前端能看到每一步真实做了什么（参数、结果、用时、成功没有）、模型真实给出的思考、现在正在做什么，并且不用等 2 秒轮询。
- 状态：计划已写，**未经用户同意不改后端**。
- 不做：逐字流式输出模型文字（先做到按步推送）；上下文压缩；改名、删除对话；新的测试流程（只修被改坏的测试）。

## 1. 思考：只显示供应商真的给出的

- Anthropic 格式：extended thinking 要在请求里打开才有。在模型设置的用途分配上加一个开关「显示思考」（默认关）；打开时请求带 thinking，回复里的 thinking 块原样记下。带工具调用时，Anthropic 要求把上一轮的 thinking 块（含签名）原样发回，施工时先核实 Spring AI 2.0.1 是否保留；不保留就不开这个开关，并在设置页写明。
- OpenAI 兼容格式：不开任何开关，只读回复里实际存在的推理字段（例如 DeepSeek 的 `reasoning_content`），施工时看 Spring AI 把它放在 `AssistantMessage` 的哪里（文本外的字段或 metadata）。没有就是没有。OpenAI 官方 Chat Completions 不返回推理原文，不显示。
- 记录：存进那一轮 assistant Turn 的 `thinking` 字段，只给界面看，**不发回给模型**（Anthropic 要求发回的情况除外，见上）。
- 没有思考的模型：`steps` 里就没有 thinking，界面不显示。

## 2. 完整的步骤

- `AgentRun.ToolResult` 加 `status`（done / failed / rejected）、`startedAt`、`ms`。两个循环在执行工具前后填上；被守卫拒绝的是 rejected。
- 视图（`api/Views`）返回 `steps`：按时间排列的 thinking 和 tool 步骤，tool 带 `args`（JSON 原样）、`result`（截到约 4,000 字；网页全文本来就存在 `cache/web/`，结果里写着路径）、`status`、时间。保留 `tools` 字段给旧界面。
- 不新增记录类：两个循环各自在已有的工具执行处加几行；重复明显了再合。

## 3. 正在做什么

- `SessionRunner`、`ApplicationRunner` 在内存里记每个 Session / Job 的当前活动：`{ kind: thinking | tool, name, summary, since }`，调模型前设成 thinking，执行工具前设成那个工具。视图里返回 `activity`。
- `ProjectSummary.sessions[]` 加 `status`（running / idle），侧栏用。

## 4. 按步推送（SSE）

- `GET /api/sessions/{id}/events`、`GET /api/jobs/{id}/events`：`text/event-stream`。事件：`activity`（当前活动变了）、`step`（一步完成，带完整步骤）、`message`（模型回了文字）、`status`（running / idle / waiting_user / ready / failed）。
- 实现：每个 Session / Job 一个订阅者列表，循环在上面 3 处调用发布；断开时移除。前端连不上就退回轮询。
- 不做模型文字的逐字流式：工具循环里大部分时间在跑工具，按步推送已经能跟上；逐字流式要把 `ModelCaller` 改成 stream 并自己拼工具调用，以后再看。

## 5. 重新生成：不重复有副作用的动作

- 只能重新生成**最后一条** Agent 回复，而且 Session 不在运行。
- 有副作用的工具：`add_lead`、`update_lead`、`start_application`（和申请 Agent 的所有浏览器写操作——申请工作台不提供重新生成）。
- 做法：不撤销、不重放。重新生成时，丢掉上一次回复的**文字**，但那一轮里已经生效的工具调用和结果留在对话里；再追加一条 note：「上一次回答已经做了这些，已经生效，不要重复：加了候选 X、开始了申请 Y……」，然后重新跑。没有副作用的调用（搜索、读网页）也留着，模型可以直接用。
- 代码层的兜底（真实损失才加）：`start_application` 对已经有申请线程的 Job 拒绝并告诉模型（现在的 `ApplicationRunner.start` 会丢掉上一次的对话重新开始，重复调用会毁掉填了一半的申请）；`add_lead` 已经按公司 + 链接去重。
- 界面：重新生成按钮旁边写「已经做过的操作不会撤销」。
- 接口：`POST /api/sessions/{id}/regenerate`。

## 6. 小项

- Session 标题：第一条用户消息的前 30 个字，不调模型。
- Session 的运行状态落盘（`session.md` 的 frontmatter 记 `last_status`、`last_error`），应用重启后不会全显示空闲；重启时正在跑的记为「中断」。

## 怎么确认能用

- `mvn -B clean verify`、`npm run build` 通过。
- 用户在场时用真实模型试：一个会给思考的配置（Anthropic 打开开关，或 DeepSeek 推理模型）能看到思考；一个不给的配置看不到；对话页能实时看到每一步；在加过候选的回复上点重新生成，候选池里没有出现重复，申请没有被重新开始。

## 实际结果

（施工后填写）
