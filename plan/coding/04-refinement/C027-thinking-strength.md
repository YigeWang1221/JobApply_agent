# C027 · DeepSeek 模型可选思考强度（含关闭）

- 对应：Master [ui.md](../../master/ui.md)「模型设置」→「用途分配」。相关：[C025](C025-settings-ui-polish.md)、[C025-fix](C025-fix-model-delete.md)（`x-opencode-session`）。
- 依据：用户（2026-11-05）：主要用 DeepSeek（OpenCode Go 预设 `opencode-go` 的 `deepseek-v4.1-flash`，可能也用 DeepSeek 官方 API），感觉比 Gemini Flash 慢很多；想在设置里给 DeepSeek 模型选思考强度，包括关掉。
- 状态：**已确认，已施工**（2026-11-05），等用户在本机用真实模型试。

## 目标

用途分配里，分给某个用途（主模型 / 辅助 / 交叉核验）的模型名含 `deepseek`、接口格式是 OpenAI 时，旁边多一个「思考」下拉：默认 / 关 / 低 / 高 / 最高。选了以后这个用途的每次请求带上对应参数，保存后马上生效（和现在一样 `ModelRegistry.reload()`）。

## 不做

- 不做通用的「每个模型 关/低/中/高」系统：只认 DeepSeek。以后要加别家，在同一个映射函数里加一个分支即可。
- 不改 Anthropic 格式（DeepSeek 的 Anthropic 端点、Claude 的 extended thinking 都不碰；`show_thinking` 的现状不变）。
- 不改「显示思考」、不改 `reasoning_content` 的发回逻辑。
- 不按任务自动切换强度（比如填表关、调研开），由用户在设置里选。

## 调研结论

1. **DeepSeek 官方 API**（[Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode)、[Models & Pricing](https://api-docs.deepseek.com/quick_start/pricing)，2026-11-05 读）：
   - 思考靠**请求参数**控制，不靠模型 id。当前模型 id 是 `deepseek-flash`、`deepseek-v4-pro`；旧名 `deepseek-v4-flash` 仍可用，会转到 V4.1-Flash。文档没提 `deepseek-chat` / `deepseek-reasoner`（它们现在是什么：未确认）。
   - OpenAI 格式：开关是 `"thinking": {"type": "enabled" | "disabled"}`（顶层字段，用 OpenAI SDK 时走 `extra_body`）；强度是 `"reasoning_effort": "low" | "high" | "max"`。`minimal`/`low` → low，`medium`/`high`/`xhigh` → high，`max`/`ultra` → max，所以没有真正的「中」。
   - **默认开思考，强度 high**。这就是 JobApply 现在什么都不发时的状态，很可能就是慢的原因。
   - 带 `tools` 的请求：之前的 `reasoning_content` 要「在之后所有请求里完整发回」，否则 400；不带 tools 时可选，发了会被忽略。思考模式下 `temperature` 等参数不生效（JobApply 本来也不发）。
2. **OpenCode Go**（[docs/go](https://opencode.ai/docs/go/)）：DeepSeek 模型是 `deepseek-v4.1-flash`、`deepseek-v4-pro`，走 OpenAI Chat Completions。文档**没写** `thinking` / `reasoning_effort` 会不会原样转给 DeepSeek，也没有单独的「不思考」模型 id。**透传：未确认**，要靠实跑看（延迟是否下降、关闭后响应里还有没有 `reasoning_content`）。旁证：OpenCode 客户端对含 `deepseek` 的模型不给强度选项（[kilocode#9512](https://github.com/Kilo-Org/kilocode/issues/9512)、[opencode PR #23335](https://github.com/anomalyco/opencode/pull/23335)），用户在 provider 配置里写 `thinking: {type: disabled}` 来关（[opencode#24135](https://github.com/anomalyco/opencode/issues/24135) 是直连官方 API 的例子）。这些讲的是 OpenCode 客户端，不是 Go 网关。
3. **JobApply 怎么发请求**（Spring AI 2.0.1，底层 openai-java + OkHttp）：
   - `OpenAiChatOptions` 自带 `reasoningEffort`（发成顶层 `reasoning_effort`）和 `extraBody`（`OpenAiChatModel` 第 911–918 行把每一项作为顶层 body 字段发出）。`mutate()` 会复制 `extraBody`（`OpenAiChatOptions` 第 597–601 行），所以 `ModelFactory.withTools(model, tools)` 从创建时选项复制出来的单次请求也带着。**最小做法：在 `ModelFactory.create` 的 OpenAI 分支设 `extraBody`**，不用拦截器。
   - 两个字段都放进 `extraBody`（`{"thinking": {...}, "reasoning_effort": "high"}`），不用 `reasoningEffort(...)`：后者经 `ReasoningEffort.of()`，`max` 不是 OpenAI 的已知值，序列化行为未核实；`extraBody` 原样发，没有这个问题。
   - `reasoning_content` 发回：`Conversation` 只在这一轮有思考原文时放进 metadata，`OpenAiChatModel` 第 676–681 行只在非空时发。关闭思考后模型不返回思考，自然什么也不发，不用改。
   - 风险（未确认）：一次运行中途把「关」改成「开」，之前的工具轮没有 `reasoning_content`，按文档字面可能 400。现在的 `Conversation` 本来也只发回最后一条用户消息之后的思考（C016），一直能跑；中途改设置属少见情况，不专门处理，出错时照常显示给用户。

## 设计

- **放在哪**：用途分配（`models.yaml` 的 `assignments.<用途>.thinking`），和已有的 `show_thinking` 同一层。理由：同一个模型可以主模型开、辅助关；`ModelRegistry` 本来就按用途缓存模型。取值 `off | low | high | max`；不写 = 默认（什么都不发，等于现在）。
- **怎么认出 DeepSeek**：提供方格式是 `openai` 且模型名转小写后含 `deepseek`。不看 Base URL（OpenCode Go、官方 API、OpenRouter 的 `deepseek/...` 都能认出）。
- **映射**（只在一处，`ModelFactory` 里一个小函数）：
  - `off` → `extraBody = {"thinking": {"type": "disabled"}}`
  - `low|high|max` → `extraBody = {"thinking": {"type": "enabled"}, "reasoning_effort": <值>}`
  - 不是 DeepSeek 或没设 → 不加。
- **界面**：「用途分配」每行模型下拉旁，选中的模型是 DeepSeek 时才显示「思考」下拉：默认（模型自己的，现在是高）/ 关（最快）/ 低 / 高 / 最高。换成非 DeepSeek 模型时隐藏，保存时不带。
- **测试连接**不带这个参数（它不知道用途），保持原样。

## 要改的文件

- `backend/.../persistence/ModelSettingsFile.java`：`Assignment` 加 `thinking`（保留现有 2 参、3 参构造），读写 `thinking` 字段；非法值读成 null。
- `backend/.../config/ModelFactory.java`：`Endpoint` 加可选 `thinking`；`create` 的 OpenAI 分支按上面的映射设 `extraBody`。
- `backend/.../config/ModelRegistry.java`：`endpoint(settings, assignment, env)` 把 `assignment.thinking()` 传进 `Endpoint`。
- `backend/.../config/ModelSettingsService.java` / `api/ModelSettingsController.java`：`Assignment` 直接作请求体，多一个字段即可；保存时只接受四个值或空。
- `frontend/src/api.ts`：`Assignment` 加 `thinking?`。
- `frontend/src/pages/ModelSettings.tsx`：用途分配行加下拉（样式用 C025 的下拉）。
- `plan/master/ui.md`「模型设置」加一句（需用户确认）。

## 怎么确认能用

- `frontend/` `npm run build`；`backend/` `mvn -B clean verify`（JDK 25）。
- 真实跑（用户监控下）：主模型 `opencode-go` / `deepseek-v4.1-flash`，分别选「关」和「默认」，在 Session 里发同一条消息，对比延迟和过程块里有没有思考。关了以后还有思考、延迟没变 → OpenCode Go 没透传，记进本文件，再决定要不要改走 DeepSeek 官方 API。
- 「关」下跑一段申请，看带工具的多轮调用不报 400。

## 用户决定（2026-11-05，「进行开发」，按计划默认）

1. 放在**用途分配**（每个用途一个）。
2. 档位保留：默认 / 关 / 低 / 高 / 最高。
3. 新分配 DeepSeek 时是「默认」（不发参数）。

## 施工记录（2026-11-05）

- 后端：`ModelSettingsFile.Assignment` 加 `thinking`（`THINKING = off/low/high/max`，读到别的值当没写），写回 `models.yaml` 的 `assignments.<用途>.thinking`；`ModelSettingsService.put` 拒绝非法值；`ModelRegistry.endpoint(settings, assignment, env)` 把它放进 `Endpoint.thinking`；`ModelFactory.thinkingBody` 按映射给 OpenAI 客户端设 `extraBody`（不是 DeepSeek 或没设时为 null）。测试连接、拉模型列表不带。
- 前端（并行子代理）：`api.ts` 加 `Thinking` 类型和 `Assignment.thinking?`；`ModelSettings.tsx` 用途分配里模型是 DeepSeek 时在模型下拉下面显示「思考」下拉，保存时只在非默认且是 DeepSeek 时带 `thinking`。
- 验证：`frontend/` `npm run build` 退出码 0；`backend/` `mvn -B clean verify` 退出码 0（单元 662、IT 1，全部通过）。没加新测试。真实模型：未运行。
- 实跑怎么看：请求体不进日志。看 `models.yaml` 里该用途有 `thinking: off`；Session / 工作台的过程块里还有没有「思考」（对应 `transcript.jsonl` 里助手那一轮的思考原文）；同一条消息「关」和「默认」的耗时差。关了仍有思考 → OpenCode Go 没透传。
- 已知：设置页保存用途分配时本来就不带 `showThinking`（C006-agent 留下的，与本次无关，未改）。

## 真实测试（2026-11-05，用户授权代理测试）

OpenCode Go · `deepseek-v4.1-flash`，临时 Project 里 3 个 Session，同一类消息（调一次本地工具 `list_jobs` 再回答），按整轮耗时计：

| 设置 | Session 1 | Session 2 | Session 3（新开） |
| --- | --- | --- | --- |
| 默认 | 6.1 / 6.0 / 6.1 / 4.0 s | 46.4 / 12.1 s | — |
| 关 | 6.1 / 4.0 s | 66.4 / 8.1 / 96.6 / 50.4 s | 4.0 / 4.0 / 4.0 s |
| 低 | — | 58.5 / 16.1 / 16.1 s | — |

- 参数被 OpenCode Go 转发了：「默认」有思考步骤，「关」「低」没有思考步骤。之前「未确认」的这条已确认。
- 同一 Session 里在关 / 低 / 默认之间来回切换，没有 400。
- 慢跟思考档位无关，跟会话有关：Session 2 无论哪一档都慢（12–97 s），Session 1、3 稳定 4–6 s，日志无重试、无报错。推测是 OpenCode 按 `x-opencode-session` 把同一会话固定到同一上游，Session 2 落到了慢的那一路（未确认）。
- 简单任务里「默认」的思考很短，关掉省不了多少；复杂任务（真实申请）里的差别没测。真实 Chrome 下的申请（关思考 + 多轮工具调用）：未运行。
- 测完已恢复用途分配（thinking 默认、showThinking false），临时 Project 已删到 `.trash/`。
