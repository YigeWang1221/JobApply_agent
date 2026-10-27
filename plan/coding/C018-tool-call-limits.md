# C018 · 工具调用上限由用户在设置页自己定

- 依据：用户（2026-10-25）「是我这边每轮消息调工具的次数有上限（这次大约 40 次） 还有工具调用上限我需要能在前端自主设计」。
- 编号：交代时写的是 C017，但 `C017-macos-app.md` 已经占用，改用 C018。
- 状态：已施工，等用户试（2026-10-25）。

## 现状（改之前）

| 上限 | 在哪 | 值 | 到了会怎样 |
| --- | --- | --- | --- |
| 对话 Agent 每条消息的工具调用 | `SessionLoop.TOOL_CALLS_PER_MESSAGE`，写死在代码里 | 40 | **「大约 40 次」就是它。** `AgentLoop` 每次调模型前查：用满 40 次就往对话里加一条系统说明「不要再调用工具，告诉用户做到哪了」，再调一次模型拿总结，回复末尾加「回复「继续」我接着做。」。这次运行结束，用户发「继续」开始新的一轮，计数从 0 算。 |
| 申请 Agent 每次运行的工具调用 | `jobapply.harness.tool-calls-per-run`（`application.yml`、`HarnessProperties`） | 150 | 同一套逻辑（`AgentLoop`），总结后申请停在「等你回复」，回复「继续」接着跑新的一次运行；run log 记一条 `budget / run_limit`。 |

不是上限、不改的：

- 重复提醒（`same-element-failures` 3、`same-result-repeats` 3、`unchanged-actions` 6、`LoopDetector` 来回切换）：只在工具结果里提醒模型，不停。
- `ModelCaller` 模型暂时出错时最多重试 2 分钟；`ModelFactory` SDK 重试 1 次；Anthropic `max_tokens` 16000：都是单次调用，不限工具次数。
- Spring AI 2.0.1：我们直接调 `ChatModel.call`，不用 `ChatClient` 和工具 advisor。`AnthropicChatModel`、`OpenAiChatModel` 里只用 `ToolCallingManager.resolveToolDefinitions` 拿工具定义，不自己执行工具（看过 jar 的字节码），所以框架里没有工具循环次数上限。模型供应商一次回复里给几个工具调用，我们全部执行，也没有上限。

## 改法

照 C012 的写法：存进 `storage/settings/` 下的文件，保存后马上生效；默认值仍在 `application.yml`。

1. 默认值：`application.yml` 加 `tool-calls-per-message: 40`；两个工具调用上限允许 0 = 不限（`HarnessProperties` 只拒绝负数）。
2. 存盘：新文件 `storage/settings/tool-calls.yaml`（两项 `tool_calls_per_run`、`tool_calls_per_message`）。没有和 `workers.yaml` 合并：那个文件的读法要求恰好两项整数，合并会改坏 C012。
3. `workflow/ToolCallLimits`：两个 `volatile` 当前值；启动时有文件用文件，读不了或是负数就日志警告、用默认值；`set` 先校验（不能是负数）、写文件、再换值。由 `ApplicationRunner` 持有一份，`SessionRunner` 用同一份。
4. 循环：`AgentLoop` 的上限从 `int` 改成 `IntSupplier`，每次调模型之前读一次，`<= 0` 不限。所以在跑的申请或对话下一步就用新值。`HarnessLoop`、`SessionLoop` 各加一个带上限参数的构造方法，旧的构造方法用默认值。
5. 到了上限的回复末尾加一句「上限可以在 设置 → 模型 →「工具调用上限」里调大，或者设为不限。」，其它行为不变。
6. 接口：`GET /api/settings/tool-calls` → `{perRun, perMessage, defaultPerRun, defaultPerMessage}`；`PUT` 同一路径，体 `{perRun, perMessage}`，缺项或 0 = 不限，负数 400。
7. 前端：设置 →「模型」标签，「同时申请」下面一张卡片「工具调用上限」：两个输入框（申请每次运行、对话每条消息），标签里写默认值，留空 = 不限；一句说明；「现在：申请 N 次 / 不限，对话 …」；保存、恢复默认。没有隐藏的最小值和最大值。

## 实际结果

2026-10-25，按上面施工。路径相对 `backend/src/main/java/jobapply/`。

- `config/HarnessProperties.java`：加 `toolCallsPerMessage`（默认 40）；两个工具调用上限 0 = 不限。`application.yml` 加一项和注释。
- `persistence/ToolCallsFile.java`（新）、`StorageLayout.toolCallsYaml()`。
- `workflow/ToolCallLimits.java`（新）；`ApplicationRunner` 持有、`toolCallLimits()` 交出，建 `HarnessLoop` 时传 `toolCalls::perRun`；`SessionRunner` 建 `SessionLoop` 时传 `applications.toolCallLimits()::perMessage`。
- `harness/runtime/AgentLoop.java`：上限改 `IntSupplier`，加 `RAISE_LIMIT`；`HarnessLoop`、`SessionLoop`：新构造方法和到上限时的提示；`SessionLoop.TOOL_CALLS_PER_MESSAGE` 删掉。
- `api/ToolCallSettingsController.java`（新）。
- 前端：`frontend/src/pages/ToolCallSettings.tsx`（新）、`api.ts` 两个调用和类型、`pages/Settings.tsx` 挂上。
- 测试：只改 `ApplicationRunnerPoolTest` 里 `HarnessProperties` 的构造（多一个参数）。没有加新测试：上限的判断只有一行，改坏也不会丢数据。

## 怎么确认能用

- `backend/`：`mvn -B -q clean verify` 退出码 0；`frontend/`：`npm run build` 退出码 0（结果见 `dev-log.md`）。
- 用户重新构建、重启应用后：设置 → 模型 →「工具调用上限」看到默认 150 / 40；把对话改成留空（不限）保存，对话里做一件要很多步的事，不会在 40 次停；改成很小的数（比如 3），下一步就停下总结，回复末尾有「设置」那句话；`storage/settings/tool-calls.yaml` 里是保存的值，重启后还在。
- 真实应用、真实模型：未运行（应用在 8080 上跑着，不重启）。
