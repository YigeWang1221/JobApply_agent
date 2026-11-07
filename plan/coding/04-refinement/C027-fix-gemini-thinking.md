# C027-fix · Gemini 模型也可选思考强度

- 对应：Master [ui.md](../../master/ui.md)「模型设置」→「用途分配」。在 [C027](C027-thinking-strength.md)（DeepSeek）上加一个分支。
- 依据：用户（2026-11-05）：拿 Gemini Flash（如 `gemini-3.8-flash`）和 DeepSeek 比速度，想给 Gemini 也选思考强度。
- 状态：**已确认，已施工**（2026-11-05，用户「直接施工、边做边测」，按下面待决项的默认），等真实 Gemini 测试。

## 目标

用途分配里，模型名含 `gemini`、接口格式是 OpenAI 时，也显示「思考」下拉；选了以后这个用途的每次请求带上 Gemini 认的参数，保存后马上生效（和 C027 一样）。

## 不做

- 不接 Gemini 原生接口 / Interactions API，只用现有的 OpenAI 兼容端点（预设 `gemini`）。
- 不做「显示 Gemini 思考摘要」（`include_thoughts`），不改 `reasoning_content` 的发回逻辑。
- 不处理思考签名（thought signature），见「风险」；真跑出问题再单开一份 fix。
- 不改测试连接、不改 DeepSeek 分支的行为。
- OpenRouter 上的 Gemini 先不管（见待用户决定 2）。

## 调研结论（2026-11-05 读）

1. **JobApply 现在怎么接 Gemini**：`ModelPresets` 有预设 `gemini`，OpenAI 格式，Base URL `https://generativelanguage.googleapis.com/v1beta/openai/`（Google AI Studio 的 OpenAI 兼容端点）。还有 `openrouter` 预设。OpenCode Go 不提供 Gemini（预设说明里写了）。当前 `models.yaml` 没有 Gemini 提供方，git 历史和 dev-log 里也没有在 JobApply 里用 Gemini 跑过的记录（dev-log 里的「Gemini 4」是写代码的代理，不是 JobApply 的模型）。**JobApply + Gemini 带工具多轮能不能跑：未确认。**
2. **Gemini OpenAI 兼容端点**（[OpenAI compatibility](https://ai.google.dev/gemini-api/docs/openai)，现跳转到 aistudio.google.com/docs/openai）：
   - 顶层 `reasoning_effort`：`minimal | low | medium | high`。Gemini 3.x 映射成 `thinking_level`（同名；Gemini 4 Argon 的 `minimal` → `low`），Gemini 2.5 映射成 `thinking_budget`（1024 / 1024 / 8192 / 24576）。不发 = 模型默认。
   - 关思考：「`reasoning_effort: "none"` 只对 2.5 模型；Gemini 2.5 Pro 和 Gemini 3 模型不能关」。
   - 也可以用 `extra_body.google.thinking_config`（`thinking_level` / `include_thoughts`），但**不能和 `reasoning_effort` 同时用**。
   - 文档表格里没列 `gemini-3.8-flash`（只有 Gemini 3 Flash、3.1 Flash-Lite、4 Argon、2.5），3.8 Flash 的 `minimal` 怎么映射：未确认。
   - 思考摘要在响应里是什么字段（`reasoning_content`？）：文档没写，未确认。
3. **各模型支持的档位**（[Thinking](https://ai.google.dev/gemini-api/docs/thinking)，现跳转到 aistudio.google.com/docs/thinking）：`gemini-3.8-flash` 默认开（medium），支持 low / medium / high；`gemini-3.6-flash`、`gemini-3-flash-preview` 多一个 minimal；`gemini-3.5-flash-lite` 默认 minimal；`gemini-3.1-pro-preview` low/medium/high（默认 high）；`gemini-2.5-flash-lite` 默认关；`gemini-2.5-pro` / `2.5-flash` 默认开。没有任何 3.x 模型能设成关。
4. **OpenRouter**（[Reasoning tokens](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens)）：用嵌套的 `reasoning: {effort: ...}`（`none`…`max`），Gemini 3 直接映射到 `thinkingLevel`；文档没写接不接受顶层 `reasoning_effort`（未确认）。思考在 `message.reasoning` / `reasoning_details` 里返回。
5. **风险：思考签名**。社区报告（[discuss.ai.google.dev #109823](https://discuss.ai.google.dev/t/openai-api-compatibility-broken-due-to-thought-signature-on-gemini-3-pro-preview/109823)、[#119360](https://discuss.ai.google.dev/t/gemini-3-thought-signature-is-not-valid-cant-do-multi-turn-tool-calling/119360)、[helix.ml](https://www.helix.ml/blog/adding-support-for-gemini-thought-signatures)）：Gemini 3 在 OpenAI 兼容端点把签名放在每个工具调用的 `extra_content.google.thought_signature`，下一轮不原样发回就 400；2.5 只是变差不报错。JobApply 代码里没有处理这个字段，Spring AI 2.0.1 会不会保留它：未确认。Gemini 3 关不掉思考，所以这跟本步的档位无关，但会决定 Gemini 能不能当主模型（填表多轮工具调用）。官方文档对兼容端点的签名细节：未确认。

## 设计

- **认出 Gemini**：OpenAI 格式，且模型名转小写后含 `gemini`（和 C027 认 DeepSeek 的方式一样，不看 Base URL）。
- **用哪个参数**：只发顶层 `reasoning_effort`（放进 `extraBody`，和 C027 一样）。理由：一个字段同时管 2.5 和 3.x，由 Google 映射到 budget / level；不发 `thinking_config`，避免两个一起发报错。
- **存的值**：沿用 `assignments.<用途>.thinking`。`THINKING` 加一个 `medium`（「中」）：Gemini 3.8 Flash 的默认就是 medium，有意义。其它值不变（`off | low | medium | high | max`），C027 已存的值照常读。`minimal` 不加（3.8 Flash 不支持，见待用户决定 1）。
- **映射**（`ModelFactory.thinkingBody` 加一个分支）：
  - Gemini：`off` → `{"reasoning_effort": "none"}`；`low|medium|high` → `{"reasoning_effort": <值>}`；`max` → 不发（null）。
  - DeepSeek：不变；如果存了 `medium` 就按 C027 的写法发 `reasoning_effort: medium`（DeepSeek 文档说映射到 high）。界面上 DeepSeek 不给「中」。
- **界面档位**（`ModelSettings.tsx`，把 `isDeepSeek` 换成「按模型给出档位列表，没有则不显示」）：
  - DeepSeek：默认（模型自己的，现在是高）/ 关 / 低 / 高 / 最高（不变）。
  - `gemini-2.5-flash*`（含 flash-lite）：默认 / 关 / 低 / 中 / 高。
  - 其它 Gemini（3.x、2.5 Pro 等）：默认（模型自己的）/ 低（最快）/ 中 / 高。没有「关」，因为文档说关不掉。
  - 当前存的值不在新模型的列表里（比如从 DeepSeek「最高」换到 Gemini）→ 显示成默认，保存时不带。

## 要改的文件

- `backend/.../persistence/ModelSettingsFile.java`：`THINKING` 加 `medium`。
- `backend/.../config/ModelSettingsService.java`：错误提示里加上 `medium`。
- `backend/.../config/ModelFactory.java`：`thinkingBody` 加 Gemini 分支；注释和 `Endpoint` 的说明改成 DeepSeek / Gemini。
- `frontend/src/api.ts`：`Thinking` 加 `"medium"`。
- `frontend/src/pages/ModelSettings.tsx`：档位按模型给；页脚说明提一句 Gemini。
- `plan/master/ui.md` 第 97 行那段加 Gemini 一句（需用户确认）。
- `ModelRegistry.java` 不用改（已经把 `thinking` 传进 `Endpoint`）。

## 怎么确认能用

- `frontend/` `npm run build`；`backend/` `mvn -B clean verify`（JDK 25）退出码 0。
- 真实跑（需要用户在设置页加 Gemini 提供方并自己填 Key）：`gemini` 预设 + `gemini-3.8-flash`，测试连接通过；主模型分别选「默认」「低」「高」，Session 里发同一条要调本地工具的消息，比耗时，看有没有 400。
- 同一 Session 连续两轮以上工具调用：不报 400 → 记「签名问题在 Spring AI 下不出现」；报 400（signature 相关）→ 记进本文件，另开 fix 处理签名，不在本步硬补。
- 有条件的话再试一个 `gemini-2.5-flash` 选「关」，看不报错、变快。

## 用户决定（2026-11-05，按默认）

不加「最低」；只做 AI Studio；签名 400 真出现再单开 fix。下面是原来的待决项。


1. 档位用 默认 / 低 / 中 / 高（Gemini 3.x）和 默认 / 关 / 低 / 中 / 高（2.5 Flash）可以吗？要不要给 3.6 Flash、3.5 Flash-Lite 这类支持 `minimal` 的模型加「最低」（新值 `minimal`）？默认不加。
2. 用户是通过 Google AI Studio（`gemini` 预设）还是 OpenRouter 用 Gemini？默认只做 AI Studio；如果用 OpenRouter，Base URL 是 `openrouter.ai` 时改发 `reasoning: {effort}`，多一个小分支。
3. 思考签名的风险：先按本计划做档位、实跑看；如果 Gemini 3 带工具多轮 400，再单开一份 fix。可以吗？

## 施工记录（2026-11-05）

- 后端：`ModelSettingsFile.THINKING` 加 `medium`；`ModelSettingsService` 错误提示加 `medium`；`ModelFactory.thinkingBody` 先判 OpenAI 格式，再分 DeepSeek（不变，`medium` 也按 enabled + `reasoning_effort` 发）和 Gemini（模型名含 `gemini`：`off` → `reasoning_effort: none`，`low|medium|high` → 同名值，`max` 不发）。`ModelRegistry` 没改。
- 前端：`api.ts` 的 `Thinking` 加 `"medium"`；`ModelSettings.tsx` 把 `isDeepSeek` 换成 `thinkingOptions(value)`（DeepSeek / Gemini 2.5 Flash / 其它 Gemini 三套档位，没有就不显示）和 `thinkingValue(usage)`（存的值不在当前档位里就显示默认、保存不带）。页脚说明没改（Gemini 3.x 的「低」标成「低（最快）」）。
- 验证：`frontend/` `npm run build` 退出码 0；`backend/` `mvn -B clean verify`（JDK 25）退出码 0，单元 662、IT 1，全部通过。没加新测试。真实 Gemini：未运行（等协调者重启后端后测）。

## thought_signature（2026-11-05，用户确认修法）

### 现象

用户真实测试：提供方用 `gemini` 预设（`https://generativelanguage.googleapis.com/v1beta/openai/`），模型 `models/gemini-3.7-flash`、`models/gemini-3.8-flash`。Session 里只要一轮需要第二次工具调用就失败：

`400: Function call is missing a thought_signature in functionCall parts. This is required for tools to work correctly...`

### 根因

- Gemini 3 每次带工具调用的响应都在工具调用上带签名，下一次请求必须在**同一个工具调用**上原样发回；只校验当前轮（从最近一条带文字的 user 消息往后）的工具调用。并行调用时只有第一个工具调用带签名。
- Spring AI 2.0.1 其实会把它读出来：`OpenAiChatModel` 把工具调用的额外字段放进 `AssistantMessage` 的 metadata（`openai.tool_calls.additional_properties`），下一次请求按 id 放回。但 JobApply 不留 `AssistantMessage`：`AgentLoop` 只存 id / 名字 / 参数，`Conversation` 每一轮从这份记录重建 `AssistantMessage`，metadata 没了，签名丢失。
- id 是一致的：`AgentLoop` 存的是模型返回的 `call.id()`，`Conversation` 重建时原样用它，Spring AI 发请求时也原样写回 `id`。所以按 id 补签名可行。
- 调用方式：JobApply 只用 `ChatModel.call()`（`ModelCaller`、`StructuredCall` 等），不用流式。

### 修法

新类 `config/GeminiThoughtSignatures`，和 `OpenCodeSession` 一样是 OkHttp 拦截器，`ModelFactory` 在 OpenAI 格式的模型上挂上（排在 OpenCode 之后）。只对 host `generativelanguage.googleapis.com`、路径以 `/chat/completions` 结尾的请求生效，别的提供方原样放过。

- 响应：成功、JSON 时用 `peekBody` 读一份（SDK 照常读原响应体），把 `choices[].message.tool_calls[]` 里的 `id → extra_content.google.thought_signature` 记进内存表（最多 4096 条，按最近使用淘汰，`synchronized`）。读不出来不影响这次调用。
- 请求：读出请求体，对每条 `role=assistant` 且有 `tool_calls` 的消息，没有签名的工具调用按 id 补上记下的值；整条消息一个签名都没有（应用重启后、旧记录里的调用）时，给第一个工具调用补文档给的占位值 `skip_thought_signature_validator`。没改动就原样发同样的字节。
- 不改 `Conversation` 的存储格式（存签名进记录是另一种修法，没选：拦截器改动小，重启后靠占位值也能跑）。不处理流式。

### 依据

- [Thought signatures](https://ai.google.dev/gemini-api/docs/thought-signatures)（现跳转 aistudio.google.com/docs/thought-signatures，页面是前端渲染；内容按 web.archive.org 2026-03-05 快照读）：「Signatures for OpenAI compatibility」的例子里，响应和发回的 assistant 消息都是 `tool_calls[].extra_content.google.thought_signature`；并行调用只有第一个带签名；「Strict validation is enforced for all function calls within the current turn」；FAQ：没有签名的调用可以填 `"context_engineering_is_the_way_to_go"` 或 `"skip_thought_signature_validator"` 跳过校验；流式时不带工具调用的回复签名可能在空文字的 part 里。
- [OpenAI compatibility](https://ai.google.dev/gemini-api/docs/openai)：「Gemini 3 supports OpenAI compatibility for thought signatures in chat completion APIs」，细节指向上一页。

### 验证

- `backend/` `mvn -B clean verify`（JDK 25）退出码 0，单元 662、IT 1，全部通过。没加仓库测试。
- 临时离线检查（scratchpad 里的小程序，不进仓库，不联网）：响应体被记下后 SDK 读到的内容不变；第二次请求里 `function-call-1` 补上了真签名，同一条消息的 `function-call-2` 不补，另一条无记录的消息第一个调用补占位值；发往别的 host 的请求体不变。
- 真实 Gemini 多轮工具调用：未运行（等用户重启后端后测）。

## 真实测试（2026-11-05，用户授权代理测试）

Google AI Studio · `models/gemini-3.7-flash`、`models/gemini-3.8-flash`，临时 Project，每组一个新 Session，消息要求分两轮调 `list_jobs` 再回答：

- 修 thought_signature 之前：8 组（2 模型 × 默认/低/中/高）全部在第二轮报 400 `Function call is missing a thought_signature`。
- 修之后：8 组全部两轮工具调用完成，无报错。整轮耗时：3.7 默认 10.2 / 低 5.1 / 中 10.1 / 高 12.2 s；3.8 默认 7.1 / 低 7.1 / 中 9.1 / 高 8.1 s（每组一次，样本小）。
- 后端重启后（内存里的签名清空）在旧 Session 里接着发：3.8 默认、3.7 低都通过，包括之前报过 400 的 Session。
- 思考摘要没有返回（thinking 步骤为 0），各档位只能从耗时区分；是否要开 `include_thoughts` 未定。
- 真实 Chrome 下的申请：未运行。测完恢复用途分配（main 3.7、auxiliary 3.8、cross_check DeepSeek，thinking 默认），临时 Project 删到 `.trash/`。
