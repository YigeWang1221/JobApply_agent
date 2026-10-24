# 参考：Agent 项目怎么构建（2026-10-23 查阅）

> **参考，不是施工步骤。** 用户要求按 Agent 开发的方式重新设计 C004-B 的填写流程，而不是按 SaaS 或脚本的方式。下面是几个公开项目的做法和对 JobApply 的含义。

| 项目 | 做法 | 对 JobApply 的含义 |
| --- | --- | --- |
| Claude Agent SDK（Anthropic 工程博客「Building agents with the Claude Agent SDK」） | 一个循环：收集上下文 → 行动 → 核对 → 重复；工具是主要的行动方式；上下文快满时压缩 | 填写就是一个循环，不是一串节点 |
| Building Effective Agents（Anthropic） | Agent 是「LLM 在循环里根据环境反馈使用工具」；每一步从环境拿到真实结果；在检查点或遇到阻碍时停下来问人；停止条件用最大轮数这类上限；工具说明（ACI）和提示词同样重要；先用最简单的方案 | 停下来问用户由模型通过工具提出；代码只留预算上限；工具说明写清楚用法和边界 |
| Browser Use | 长而有结构的系统提示词：每一步先评估上一步是否成功、记一两句进度、写下一个目标；长任务用 todo 清单；先处理弹窗；自动补全要等候选出现再点；「同一个失败的操作不要重复超过 2–3 次，换办法」；用到 75% 的步数时重新评估；做不完就如实说，不要假装成功 | 卡住的处理写进提示词，系统只把「连续失败了几次」这类事实反馈给模型；进度和计划由模型自己维护 |
| Deep Agents（LangChain） | 规划工具（`write_todos`）、文件系统、子 Agent、skill 渐进加载；在指定工具上中断等人批准；「边界在工具或沙箱层强制，不要指望模型自己守规矩」 | 安全边界在工具层；其它交给模型 |
| Hermes（本机源码 `run_agent.py`、`agent/prompt_builder.py`） | 一个 while 循环加迭代预算（默认 90）；系统提示词按节拼：身份、memory 引导、skill 引导、工具使用要求（「每次回复要么调用工具推进，要么给出最终结果」） | 系统提示词按节组织 |
| OpenMAIC（THU-MAIC，第三方代码解读） | 导演节点：简单情况用规则，否则由 LLM 决定下一个发言者，可以是「用户」（`cue_user`）；编排状态很小，随请求传递 | 「问用户」是模型选的一个普通动作；Checkpoint 只放很小的状态 |

共同点：一个循环 + 足够的工具 + 有结构的提示词和 skill；人通过一个中断进入循环；硬性限制只放在工具层和预算上。

---

## 源码阅读（2026-10-23）

> 只读源码，没有运行。Hermes 读本机 `~/.hermes/hermes-agent`（不含配置、密钥、memories、日志）；Deep Agents（`langchain-ai/deepagents`，HEAD 708efd2）和 OpenMAIC（`THU-MAIC/OpenMAIC`）浅克隆到会话临时目录。路径相对各自仓库根目录。

**推翻上面表格的几处**：
- Deep Agents 现在默认**不带** `write_todos`（只有 Codex 配置加它），也没有内置的基础提示词：工具怎么用全写在工具说明里。
- Hermes **故意不做**「预算快用完」的提醒（注释：提醒会让模型提前放弃），和 Browser Use「75% 时重新评估」相反。
- OpenMAIC 的导演已经不是 LangGraph 图，而是一个**调用工具的 Agent**（`lib/chat/pi/`）；`cue_user` 是结束这一轮的工具，服务端完全无状态。

### Hermes（Python，`run_agent.py`）

1. **循环**：`run_conversation()`（`run_agent.py:10165`）里一个 `while`：检查中断 → 扣一次迭代预算 → 拼消息 → 调模型 → 执行工具 → 需要时压缩 → 存会话 → 继续。模型回复里**没有工具调用就是最终答案**，循环结束。防打转：迭代上限默认 90；同一轮里完全相同的调用去重；同一段文件连读 3 次给 `_warning`、4 次返回 `BLOCKED: …STOP re-reading`（`tools/file_tools.py:636`）；工具名写错、参数 JSON 坏各重试 3 次。预算用完给一次「宽限调用」，注入「请总结已经做了什么，不要再调用工具」（`run_agent.py:9985`）。
2. **工具**：每个 `tools/*.py` 在模块里 `registry.register(name, toolset, schema, handler, check_fn)`，按 toolset 组合。说明写「什么时候用、什么时候不用、优先用什么」：`clarify` 写明「低风险的决定自己选一个合理的默认值」；`browser_type` 写明「先 navigate 和 snapshot」。系统提示词只放已加载工具的引导。
3. **skill**：`SKILL.md`（frontmatter `name`、`description`，可带 `references/`）。系统提示词里只放索引（`agent/prompt_builder.py:712`，「## Skills (mandatory)：只要部分相关就必须先 `skill_view` 读」），正文用 `skill_view(name)` 按需读。完成复杂任务后提示模型把做法存成 skill；每 10 轮有后台复盘更新 memory 和 skill。
4. **提示词**：按节拼（`run_agent.py:4790`）：身份 → 工具引导 → 「每次回复要么调用工具推进，要么给出最终结果」→ memory 快照 → skill 索引 → 项目说明文件 → 时间和环境。一次会话只拼一次，逐字缓存，压缩后才重建。每步看到的是 `role:tool` 结果；超大结果换成预览加文件路径。
5. **人在回路里**：`clarify` 是**阻塞的工具调用**（最多 4 个选项 + 「其他」，超时 120 秒后返回「用户没回答，自己判断」）。危险命令在终端工具里阻塞等批准。用户发新消息会中断：未开始的工具返回「跳过」，新消息开启新的一轮。`steer()` 不打断地插一句话。看板 worker 用另一种：`kanban_block(reason)` 结束运行，人解决后重新拉起。
6. **代码管 / 模型管**：代码管迭代预算、重试上限、重复调用的 BLOCKED、`HARDLINE_PATTERNS`（无条件禁止）和 `DANGEROUS_PATTERNS`（要批准）、结果大小、压缩时机、上下文文件的注入扫描。模型管：调什么、什么时候结束、要不要问人、要不要读 skill、记什么。
7. **状态和上下文**：提示 token 到窗口 50% 时压缩（`agent/context_compressor.py:379`）：旧工具结果先压成一行（`[terminal] ran npm test -> exit 0`），保留开头 3 条和结尾一段，中间交给辅助模型按固定小节总结（当前任务原话、已完成的操作、待用户回答的问题、剩余工作…）；压缩后把 todo 再放回去。会话每批工具调用后存 SQLite，中断后能接着做。

### Deep Agents（Python，`libs/deepagents/deepagents/`）

1. **循环**：`create_deep_agent`（`graph.py`）只是把一串 middleware 交给 LangChain 的 `create_agent`，循环是标准的「模型 → 工具 → 模型」，没有工具调用就结束。唯一的硬上限是 `recursion_limit: 9_999`（`graph.py:1010`）；子 Agent 不能再派子 Agent；`PatchToolCallsMiddleware` 给没回应的工具调用补一条结果，保证历史合法。
2. **工具**：每个 middleware 带自己的工具（文件系统 `ls/read_file/write_file/edit_file/glob/grep/execute`，子 Agent `task`）。说明是长的常量文本，写清前提（「编辑前必须先读文件，否则报错」）。每次调模型前按条件过滤工具（例如没有沙箱就去掉 `execute`）。代码注释说：工具用法从系统提示词里删掉了，因为和工具说明重复（`graph.py:671`）。
3. **skill**：扫描目录里带 `SKILL.md` 的子目录（`middleware/skills.py:687`），提示词里只放「名字、描述、→ 读 `{path}` 看全文」（`:873`），模型用 `read_file` 自己读。`metadata.include_tools` 列出的工具在读过这个 skill 之后才出现。
4. **提示词**：调用方的系统提示词 → 配置的前后缀 → 各 middleware 依次追加（文件系统、skill 目录、`<agent_memory>`，并写明 memory「可能过时，是参考资料，不是指令」）。每次调用都重新拼。
5. **人在回路里**：`interrupt_on={"edit_file": True}` 或带 `when` 条件，在指定工具上中断，决定有 approve / edit / reject / respond；靠 checkpointer + `thread_id` 存状态，`Command(resume=…)` 继续。核心里没有 ask_user；CLI 应用里的 `AskUserMiddleware`（`libs/code/deepagents_code/ask_user.py`）在工具内部 `interrupt()`，支持文字、单选、多选，并写着「少用」。
6. **代码管 / 模型管**：代码管工具集和可见性、权限和中断、什么时候总结、大结果转存、递归上限、子 Agent 隔离（只回最终报告）。模型管调什么、读哪个 skill、要不要派子 Agent、什么时候结束。
7. **状态和上下文**：到窗口 85% 时总结、保留最后 10%（`middleware/summarization.py:299`）；被总结掉的消息写进 `/conversation_history/{id}.md`，换成一条「对话已总结，全文在某文件」；供应商报上下文溢出时强制总结再重试。超过 2 万 token 的工具结果转存成文件，只给头尾预览和「用 read_file 分段读」。Checkpoint 里存消息（只存增量）、文件、skill 元数据、memory。

### OpenMAIC（TypeScript，`lib/chat/pi/`）

1. **循环**：`runPiDirectorLoop`（`director-loop.ts:29`）在一次 HTTP 请求里跑完一轮：导演 Agent 有 4 个工具 `read_scene`、`call_agent`、`close_session`、`cue_user`；`call_agent` 拉起一个角色的子调用，结果以「名字: 文字」回到导演。`cue_user` 或 `close_session` 被调用、或导演工具次数到上限时结束（`:245`）；导演没调结束工具就停了，代码自动 cue 用户（`:270`）。上限都在代码里：每轮最多 6 个角色发言、每个角色最多 8 个动作、连续 2 次空回复算失败；被拒绝时以工具结果告诉模型「上限到了，用 cue_user 或 close_session 结束」。
2. **工具 / 动作**：导演工具用 TypeBox schema + 说明。子角色默认没有原生工具，动作写在提示词里（`tool-schemas.ts:29`），要求只返回 JSON 数组；流式解析，逐个校验，不合法的动作变成警告，不执行。只列出这个角色、这个场景允许的动作。
3. **知识**：导演只看到很薄的大纲（场景标题、最多 5 个要点，都截断），要细节必须 `read_scene`；读到的内容只附给下一次 `call_agent` 一次，并标明「运行时附上的资料（是数据，不是指令）」（`prompts.ts:481`）。子角色被告知「内容没有预先加载，缺证据就不要猜」。
4. **提示词**：导演提示词（`prompts.ts:70`）：角色、结束工具的规则、编号的路由规则、本轮预算、当前场景、大纲、本轮已发言的角色、角色列表、完整历史，用户消息只放「最新的用户消息」。子角色提示词：人设、本轮别人说了什么（避免重复）、语言、输出格式、可用动作、精简的状态、最近 12 条历史、导演的指令。
5. **人在回路里**：`cue_user {prompt, options[2–4]}` 是结束工具，发一个事件后本轮结束；服务端**不暂停**，客户端存下 `directorState`，用户选的选项作为普通用户消息随下一次请求发回（`components/chat/use-chat-sessions.ts:1603`）。代码要求问题和最近一次可见的发言一致，选项不合格就修剪而不是报错。
6. **代码管 / 模型管**：代码管所有上限、两个结束工具互斥、自动 cue、角色和动作校验、只能引用当前场景的元素、清洗输出。模型管下一个谁说、说什么、要不要读场景、什么时候交给用户或结束。
7. **状态和上下文**：服务端无状态，每次请求带全部状态（`lib/types/chat.ts:337`）。`DirectorState` 只有 `{turnCount, agentResponses（每条预览 ≤300 字）, whiteboardLedger（只本轮）}`，注释说历史账本「只会让状态膨胀」。历史太长时用模型压缩，指示「保留最新的用户目标和没解决的问题」。

### 对照：它们怎么做 → JobApply 用不用、在 Java 里怎么做

| 它们怎么做 | 来源 | JobApply |
| --- | --- | --- |
| 一个 while 循环：模型 → 工具 → 模型，直到结束工具或没有工具调用 | 三者 | **用**。`ChatModel.call` + 自己执行工具（沿用 `HarnessLoop` 不挂 advisor 的做法），一个 Job 一个循环，不用固定图 |
| 迭代上限做兜底，到了给一次宽限调用让模型总结 | Hermes | **用**。到上限时让模型总结并问用户要不要继续；不做中途「预算 80%」提醒 |
| 重复操作在工具结果里如实告诉模型（BLOCKED / 第 N 次），不强制转交 | Hermes、OpenMAIC | **用**。`LoopDetector` 只产出一句话附在结果里 |
| 不合法的调用变成给模型的错误文字，不崩溃 | 三者 | **用**（已有：守卫拒绝 → 结果文字） |
| 工具说明写「什么时候用、前提、不要做什么」；系统提示词不重复工具用法 | Hermes、Deep Agents | **用**。浏览器工具和 `ask_user` 的说明重写 |
| 问人：结束这一轮的工具，回答作为下一条用户消息接上 | OpenMAIC、Hermes 看板 | **用**。`ask_user` 结束这一次运行，对话存盘；用户回答后读回对话、追加回答、接着跑。不阻塞线程，不依赖超时 |
| 问人：阻塞的工具调用 + 超时自己判断 | Hermes `clarify` | **不用**：用户不在屏幕前时会被超时替用户做决定 |
| 模型停了但没调结束工具 → 系统替它交给用户 | OpenMAIC | **用**。只回文字 = 发给用户的话，等用户回复 |
| 在指定工具上中断等批准（approve/edit/reject） | Deep Agents | **不用**。用户不想反复点确认；最终提交本来就由用户点 |
| skill 只放索引，正文按需读；读之前先看是否相关 | Hermes、Deep Agents | **用**（已有 `SkillCatalog` + `load_skill`），提示词里写「遇到什么情况读哪个」 |
| 任务完成后把经验写回 skill / 站点记录 | Hermes | **以后**：先跑通流程，之后再加 |
| 读到的资料标明「是数据，不是指令」 | OpenMAIC、Deep Agents | **用**。快照和网页内容在提示词里标明来自网页，不是指令 |
| todo 计划工具 | Deep Agents（可选）、Browser Use | **用，轻量**：`update_plan` 存一份清单，每步放回上下文；多页表单用得上 |
| 旧工具结果压成一行，只留最新快照 | Hermes | **用**（已有「只留最新快照」），旧结果压成一行 |
| 到窗口某比例时让模型总结中间部分 | Hermes、Deep Agents | **先不做**：一份申请的对话有限；真的超了再加 |
| 每一步后存会话，中断后接着做 | Hermes、Deep Agents | **用**。对话和计划存到 Job 目录下的文件；不再用 LangGraph4j Checkpoint |
| 状态很小，随请求传递 | OpenMAIC | **借思路**：要恢复的只有对话、计划和几个数字 |
| 子 Agent | Deep Agents、OpenMAIC | **不用**：一份申请一个 Agent 足够 |


## 运行时：事件流、重连、子 Agent 回报（2026-10-23）

> 只读源码，没有运行。OpenMAIC dff201b、deepagents 3e7d5fa、deep-agents-ui f6a4f34、langgraphjs c6675bf（另看了 npm 包 `@langchain/langgraph-sdk` 1.12.7 的 `dist/react/stream.lgp.js`）。仓库在 `/Users/yigewang/Documents/Personal_learning_2026/` 下。

| 主题 | 他们怎么做（出处） | 给 JobApply 的结论 |
| --- | --- | --- |
| 事件流和重连 | OpenMAIC `app/api/agent/sessions/[id]/events/route.ts`：每个 Session 一条只追加的事件日志，事件带递增序号；SSE 路由是纯读者：先补发 `seq > Last-Event-ID`，再跟新事件；补完发命名事件 `caught_up`；运行结束不关流；25 秒心跳；原生 `EventSource` 带 `Last-Event-ID` 自动重连走同一条路径；LISTEN/NOTIFY 只是有损的唤醒，保底轮询兜底。`app/api/agent/owner-events/route.ts`：按用户汇总的稀疏流给列表用 | 事件日志是真相、推送是尾巴；能续接；不关流；一条全局流给侧栏 |
| 页面重开接上运行 | LangGraph SDK `useStream({ reconnectOnMount })`：`sessionStorage` 记 `lg:stream:<threadId>` → `run_id`；挂载时 `client.runs.joinStream(threadId, runId, { lastEventId })`；结束后 `history.mutate` 重读线程状态。deep-agents-ui `src/app/hooks/useChat.ts` 打开了它 | 运行有身份；客户端接上的是同一次运行；结束后以服务端状态为准 |
| 子 Agent 结束回报父 Agent | deepagents `middleware/async_subagents.py`、`_parent_notifier.py`：父 Agent 状态里记着启动的任务（id、状态、run）；子 Agent 结束 / 出错 / 等人时 `_notify_parent`，父 Agent 空闲时可被唤醒（`notify_parent` 默认开） | 申请线程是对话 Agent 的子任务：结束、等人时回报发起它的 Session |
| 没有结果的工具调用 | deepagents `middleware/patch_tool_calls.py` `PatchToolCallsMiddleware.before_agent`：运行前给每个没有结果的工具调用补一条结果 | 读回对话时补「中断了」，不让下一次请求出错 |
| 重放有副作用的调用 | OpenMAIC `lib/server/agent-runtime/curriculum-tools.ts`、`import-pptx.ts`：写操作按工具调用 id 幂等，回执存在被改的对象上，重放复用上次结果 | 有副作用的工具按业务键幂等、返回回执；重新生成不靠「不要重复」这类提示 |
| 运行和读者分开 | OpenMAIC `lib/server/agent-runtime/config.ts`：后台 runner 用租约 + 心跳，过期的会话可以被接着跑；SSE 断开只关读者，runner 照跑 | 本机单进程不需要租约；但「断开不影响运行」「重启后标中断」要保留 |

## 浏览器点击与上传控件（2026-10-23）

起因：C006-fix-real-run 第 10 条，小米校招（飞书招聘）页面点「选择文件」报 `Timeout 5000ms exceeded`，之后 `browser_file_upload` 报没有文件选择框，模型编成「浏览器安全限制」。

看的版本：@playwright/mcp 0.0.83（`~/.npm/_npx/381cec31605a419a/node_modules/playwright-core/lib/coreBundle.js` 和 `@playwright/mcp/README.md`）；Hermes `e5dad4ac5`（`tools/browser_tool.py`、`browser_cdp_tool.py`、`browser_dialog_tool.py`）；deepagents `3e7d5fa`；deep-agents-ui `f6a4f34`；OpenMAIC `dff201b`。

**Playwright MCP 自己**

- `browser_click` = `locator.click({timeout: --timeout-action})`，默认 5000ms。超时花在可操作性检查上：可见、稳定、可用、**命中检查**（点的位置最上层的元素必须是目标或它的子元素）。透明遮罩、加载层、盖在按钮上的 `opacity:0` 的 `<input type=file>` 都会让它一直重试到超时，点击没有发生。错误前缀是 `browserBackend.callTool:`（API 名按调用栈推出来的），没有调用日志，看不出原因。
- 文件选择框是拦截的：`filechooser` 事件 → 标签页上记一个 `fileChooser` modal state。`browser_file_upload` 只能在这个状态下用（否则 `can only be used when there is related modal state present`）；状态存在时别的工具都报 `does not handle the modal state`。
- `browser_drop`：在元素上派发 dragenter / dragover / drop（带 DataTransfer）。走的也是指针动作的可操作性检查（`_retryPointerAction`），按钮被盖住时一样超时；目标还必须有接受拖放的 dragover 处理。**解决不了遮罩问题**。
- Playwright 处理隐藏文件输入框的正路是 `setInputFiles`：只要求元素是 `<input type=file>`（或关联它的 `<label>`），**不查可见性、不点击、不需要文件选择框**，设完派发 input / change 事件。MCP 没有单独的工具暴露它，只能经 `browser_run_code_unsafe`（core 能力，默认就有；RCE 等价）。`target` 参数也接受选择器，但 `aria-ref=eN` 才能对上快照里的 ref。
- 命令行有 `--timeout-action <ms>`（环境变量 `PLAYWRIGHT_MCP_TIMEOUT_ACTION`）。

**参考仓库**

- Hermes：浏览器工具走 agent-browser CLI，每条命令默认 30 秒超时（`browser.command_timeout`），没有上传工具；原生对话框交给 CDP supervisor 记成 `pending_dialogs`，模型用单独的 `browser_dialog` 回应；兜底是原样转发任意 CDP 命令的 `browser_cdp`（理论上能 `DOM.setFileInputFiles`，没有专门写）。
- deepagents、deep-agents-ui、OpenMAIC：没有浏览器自动化，没有上传控件的处理（只搜到测试里的无关文件）。

**结论（JobApply 怎么做）**

1. 上传不靠点按钮：`browser_file_upload` 加可选 `target`（上传按钮 / 上传区域的 ref）。带 target 时，系统在过完白名单（ref 在最新快照里、文件都在 `storage/uploads/<job>/`）之后，用**固定模板**经 `browser_run_code_unsafe` 调 `setInputFiles`：从 ref 指的元素往外找最近的 `input[type=file]`。模型不能提交代码，模板只嵌 ref 和路径两个 JSON 字符串。不带 target 时照旧交给已经打开的文件选择框。
2. 不开 `browser_drop`：和点击一样会被遮罩挡住，多一个工具没用。
3. 动作超时从 5 秒放到 15 秒（`--timeout-action 15000`），给加载遮罩留时间；比 Hermes 的 30 秒短，点不到时不至于每次等太久。
4. MCP 的错误补一句实情（`PlaywrightMcpClient.hint`）：动作超时 = 元素点不到、操作没发生、不是安全限制；没有文件选择框 / 文件选择框挡着别的操作时下一步做什么。模型照错误里的说明做，不再自己编原因。
5. 本机验证：用 MCP 同版本的 playwright-core 在无头 Chrome 里打开夹具 `file-slots.html`（作品集按钮上盖了透明层），点击 2 秒超时；模板 `setInputFiles` 设上文件，页面的 change 处理写出了文件名。

## 运行中插话（steer）（2026-10-24）

起因：用户要求「参考 deep agents / OpenMAIC：Agent 执行输出时，用户可以打字打断或改变流程」。当时两个 Agent 运行中发消息都返回 409，只能先停止。

看的版本：langgraphjs `c6675bf`；deepagents `3e7d5fa`；deep-agents-ui `f6a4f34`；OpenMAIC `dff201b`；Hermes `e5dad4ac5`（只读源码，没读配置、密钥、memories、日志）。

| 项目 | 做法 | 模型什么时候看到 | 前端 |
| --- | --- | --- | --- |
| langgraphjs | 每次运行带 `multitask_strategy`：reject（409）/ enqueue（排队等这次跑完）/ interrupt / rollback（用 AbortSignal 中止当前运行再开新运行，rollback 丢掉旧运行）。`libs/langgraph-api/src/api/runs.mts`、`storage/ops.mts` `cancel()`；客户端 `libs/sdk/src/stream/submit-coordinator.ts`（默认 rollback） | 不往正在跑的循环里插；要么等跑完，要么中止后作为新一轮 | SDK 有 `useSubmissionQueue` 显示排队的消息 |
| deep-agents-ui | 运行中发送键变成停止，`handleSubmit` 直接返回（`ChatInterface.tsx`），等于在界面上拒绝 | — | 输入框能打字，不能发 |
| deepagents CLI | 运行中消息进 `_pending_messages` 队列，跑完按顺序发（`libs/code/deepagents_code/app.py`）；Esc 先撤回最后一条排队的，没有排队的才中止 | 跑完以后作为新一轮 | 「N messages queued」 |
| OpenMAIC | `POST /api/agent/sessions/[id]/messages` 先落库、返回 202；空闲时开新运行，在跑时 `runner.ts` `drainMessages()` 把新消息经 `agent.steer()` 插进正在跑的循环；`ask_user` 发出后不再插；停止是单独的 `/cancel` | 下一轮调模型之前（pi-agent-core 的 steer；依赖没装，具体时机按其惯例推断，未确认） | 输入框一直可用，运行中 Enter 就是插话，旁边有停止 |
| Hermes | `busy_input_mode` 三选一：interrupt（默认，打断正在执行的工具，剩下的写「被用户打断跳过」，新消息作为下一轮）/ queue（跑完再发）/ steer（`run_agent.py` `steer()` 存进 `_pending_steer`，一批工具执行完后附在最后一条工具结果后面，或者每次调模型之前取走；不打断工具；带图片时退回 queue） | steer：下一次调模型之前 | 输入框从不禁用，状态写「queued for next turn」 |

**结论（JobApply 怎么做）**

1. 默认用 OpenMAIC / Hermes 的 steer：运行中的消息放进这次运行的收件箱，共用循环 `AgentLoop` 每次调模型之前（也就是上一批工具都执行完之后）取走，作为用户消息加进对话。不中止正在执行的工具：浏览器写操作做一半最难收拾，Hermes 的 steer 和 OpenMAIC 都不打断工具。不做 langgraph 的 rollback（要回滚浏览器里已经填的东西，做不到）。
2. 跑完时收件箱里还有的（最后一步正好到的），写进对话、接着开一次运行（等于 deepagents 的队列兜底），不丢。
3. 「打断并发送」= 现有的停止 + 发送（Hermes 的 interrupt 模式），不另造中止机制：循环先查停止再取收件箱，这条留给下一次运行。
4. 前端照 OpenMAIC：输入框一直可用，运行中发送就是插话，停止按钮保留。
5. 插话只收文字（Session 可带附件，因为它本来就是对话里的一条用户消息）；申请工作台的回答问题、选上传文件要改上传区和文件编号，运行中不收，和 Hermes「带图片退回 queue」同理。

**补充：点击超时后的检查和直接点击（2026-10-24，C006-fix-real-run 第 20 条）**

- 同一个 playwright-core（1.64.0-alpha，@playwright/mcp 0.0.83 的 npx 缓存）：命中检查失败时 Playwright 会换几种滚动对齐（end / center / start）重试，所以单纯在页面底部、被固定底栏挡住的按钮多半能自己滚开；一直超时多半是盖在按钮上的透明层或没关掉的浮层遮罩。超时错误不带调用日志，MCP 返回给模型的只有 `Timeout … exceeded`。
- `click({force:true})` 只跳过可操作性检查，仍在元素中心的屏幕坐标上发鼠标事件，被盖住时点到的是上层元素，解决不了遮挡。直接在元素上派发事件（`dispatchEvent` / `element.click()`）不经过坐标，盖在上面的层收不到。
- 结论：超时后系统用固定模板只读检查一次（滚到中间、`elementFromPoint`、fixed / sticky 层、还开着的浮层），写进错误；点击目标只是被盖住时系统直接点一次。点击守卫在这之前已经放行这个 ref。见 `PlaywrightMcpClient.DIAGNOSE`、`CLICK_THROUGH`。

## 删除会话 / 项目（2026-10-24）

起因：用户「好像还是做不到 delete session/project，参考那两个 repo 设计然后执行」。

看的版本：deep-agents-ui `f6a4f34`；OpenMAIC `dff201b`；langgraphjs `c6675bf`（deepagents 没有删除线程的界面，没细看）。

| 项目 | 界面 | 后端语义 | 正在跑的 |
| --- | --- | --- | --- |
| deep-agents-ui | 没有删除：`ThreadList.tsx` 只列线程（`useThreads` 调 `threads.search`），全仓没有 delete / archive | — | — |
| langgraphjs | （服务端）`DELETE /threads/:id` → 204，没有这个线程 404（`libs/langgraph-api/src/api/threads.mts`） | **硬删**，级联删掉这个线程的 runs 和 checkpoint（`storage/ops.mts` `threads.delete`）；删 assistant 默认**不**删它的线程，要显式 `delete_threads=true` | 不检查，直接连 run 一起删 |
| OpenMAIC · 对话 | 侧栏每行悬停出现 ⋯，里面「删除」先问一句（`components/workbench/workspace/WorkspaceRail.tsx` 文件头说明）；确认后行**乐观地**先消失，服务端失败再放回并提示；成功后若删的是当前打开的对话，外壳关掉它的面板和 URL 参数 | **软删**：`deleteWorkspaceSession` → `store.softDeleteSession(id, owner)`（`lib/workbench/workspace-actions.ts`） | 不单独处理 |
| OpenMAIC · 课程 / 文件夹 | 课程卡片「删除」→ 卡片上就地确认 / 取消（`app/page.tsx` `pendingDeleteId`），没有二次确认 | 删课程会结束它的生成任务；删文件夹默认 `mode=ungroup`：**文件夹没了，课程留下**回到顶层；连课程一起删的模式故意不放进侧栏（「容器的 ⋯ 不能毁掉作品」）；删除有防重入和「删除代」防止在途保存把刚删的写回来（`lib/utils/deleted-stages.ts`） | 删课程 = 结束任务 |

**结论（JobApply 怎么做，C013）**

1. 照 OpenMAIC 的软删，而且更简单：本地文件，直接把目录挪进 `storage/.trash/<时间>-<session|project>-<id>/`，里面照原相对路径摆放，挪回去就恢复。不做恢复接口和回收站页面。
2. 照 OpenMAIC「容器删掉、作品留下」：删 Session 只删对话，它发现的 Job 留在 Project 里，事件里的 Session 当历史；删 Project 才连 Job 一起挪走（Job 属于 Project）。
3. 界面照 OpenMAIC 侧栏：行上悬停出现的按钮（和已有的改名笔并排，不另做 ⋯ 菜单），一个确认框写清楚挪走什么、留下什么，只问一次；删的是当前页时回到全局。
4. 正在跑的：Session 照 OpenMAIC「删除 = 结束运行」，在跑时请它停下、停下后挪走，不让用户先停再删；Project 范围大、牵涉申请线程和浏览器标签页，有对话或申请在跑 / 排队时 409，说是哪几个。
5. 不学 langgraph 的硬删：删错了没有回头路；也不学它不检查在跑的运行（我们的运行在写同一批文件）。
