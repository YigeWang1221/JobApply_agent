# C028 · 用 ChatGPT 登录：把 ChatGPT 订阅额度当成一个模型提供方

- 对应：Master [ui.md](../../master/ui.md)「模型设置」。相关：[C025](C025-settings-ui-polish.md)（预设、提供方卡片）、[C027](C027-thinking-strength.md)（思考下拉）。
- 依据：用户（2026-11-07）：模型设置里加一个「Sign in with ChatGPT / 用 ChatGPT 套餐额度」的提供方。用户在自己的浏览器里登录，JobApply 用他 ChatGPT 订阅里的 Work/Codex 额度调模型，和其它提供方一样能分配给主模型等用途。
- 调研（2026-11-07 读）：OpenAI 官方 [Sign in with ChatGPT](https://developers.openai.com/siwc) 及其子页 `token-sharing-open-source/`（sign-in、token-reference、profiles-and-sessions、models-and-inference、preview-limitations、errors-and-recovery）和 ui-ux-guidelines。这个流程面向开源、本机运行的个人应用，不用注册 client，目前是 **preview**。上线时间（2026-09-29 DevDay）只来自二手来源。支持哪些套餐：cookbook 写 Plus/Pro，帮助页片段写 Go/Plus/Pro（未确认）。参考实现：opencode 走的是官方流程（`sst/opencode` 的 `packages/core/src/plugin/provider/chatgpt.ts`）。
- 状态：**用户已确认（2026-11-07），施工中**。

## 目标

模型设置「添加提供方」里多一个预设「ChatGPT 套餐（用 ChatGPT 登录）」。提供方卡片上没有 Key 输入框，换成「Continue with ChatGPT」按钮。用户点按钮后在系统浏览器里登录、授权，回到 JobApply，卡片显示「Using ChatGPT plan」和登录邮箱。之后和其它提供方一样：拉取模型列表、测试（ok + 工具调用）、分配给主模型 / 辅助 / 交叉核验，Session 和申请都能用它跑，工具调用和图片附件都要能用。

## 不做

- 不用 Codex CLI 的 client id（`app_EMoamEEZ73f0CkXaXp7hrann`），不调 `chatgpt.com/backend-api`（包括 `/codex`）。Cline、Roo、LiteLLM 是这么做的，但 OpenAI 没有许可这种用法。
- 不做 device-code 登录，只做浏览器 + 本机回调。
- 这个提供方不走 Chat Completions，只走 Responses API。
- 不改其它提供方和格式的行为（OpenAI / Anthropic / OpenCode Go 等保持原样）。
- 不做多个 ChatGPT 账号（见待确认 3）。不做用量统计页，只放「Manage usage」链接。

## 设计

1. **预设和格式**：`ModelPresets.ALL` 加 `chatgpt`（label「ChatGPT 套餐（用 ChatGPT 登录）」，format `chatgpt`，baseUrl `https://api.openai.com/v1`，`keyRequired=false`，note 写「用 ChatGPT 订阅额度；preview；需 Plus/Pro 等套餐」）。`ModelSettingsFile.FORMATS` 加 `chatgpt`，`ModelFactory.Endpoint` 的格式校验跟着放开。这个提供方的 `api_key` 不写。`models.yaml` 只放不敏感的元数据：`signed_in`、`email`（走现有的 `Provider.extra`，或明确加字段，施工时定）。
2. **令牌文件**：`storage/settings/chatgpt-auth.json`，权限 0600，先写临时文件再原子改名，内容不进日志。里面放签发的 `client_id`（`oaiapp_...`）、`ext_agent_host_id`（第一次生成的 `urn:uuid:<v4>`，以后一直用它）、refresh_token、access_token 和过期时间。**它和 `.env` 一样算密钥文件：代理不读、不打印、不改**，验证时只看文件在不在、权限、退出码。页面和 API 都不返回令牌。
3. **登录接口**（`/api/settings/models/providers/{id}/chatgpt/...`，走现有的 `LocalRequestGuard`）：
   - `POST .../signin`：生成 PKCE(S256) verifier、state、nonce，启动本机回调监听，拼好授权 URL（`https://auth.openai.com/api/accounts/authorize`，参数 `response_type=code`、`scope=openid profile email offline_access resource.invoke chatgpt.tokens.use.direct`、`resource=https://api.openai.com/v1`、state、nonce、`ext_agent_host_id`、`redirect_uri`、code_challenge）后返回给前端。第一次登录发 `client_id=dynamic_agent_client` 加 `agent_name_hint=JobApply`；令牌文件里已有签发的 client_id 时用它，不带 hint。
   - `GET .../signin`：轮询状态（等待中 / 已登录 + 邮箱 / 失败 + 原因）。
   - `DELETE .../signin`：退出。用 openid-configuration 里的 `revocation_endpoint` 吊销 refresh_token，删掉令牌文件，清掉 `signed_in`。吊销失败也要删本地文件，并告诉用户吊销失败。
   - **打开浏览器**：前端拿到 URL 后用 `target=_blank` / `window.open` 打开。macOS 外壳（`appbuild/macos/JobApply/main.swift` 第 252–264 行）已经把这类链接交给系统默认浏览器，开发时在浏览器里就是开一个新标签页，所以后端不用调 `open`。
4. **本机回调**：独立的短期 JDK `HttpServer`，绑 `127.0.0.1` 的随机端口，路径 `/auth/callback`，`redirect_uri=http://127.0.0.1:<port>/auth/callback`。要写 `127.0.0.1`，不能写 localhost。授权和换令牌用同一个 URI。端口不在 8088/5173 上，所以不经过 `LocalRequestGuard`。同一时间只开一个监听，5 分钟没回调就关闭，并标记为超时。收到回调后：
   - 先核对 state。`error=access_denied` 直接停，显示「已取消授权」。
   - 第一次登录时，先把回调里签发的 `client_id` 写进令牌文件，再换令牌。重新授权时，回调里的 client_id 和已存的不一样就拒绝。
   - 换令牌：POST 表单到 `https://auth.openai.com/api/accounts/oauth/token`，带 `grant_type=authorization_code`、签发的 client_id、code、code_verifier、redirect_uri、resource。
   - 校验 id_token：JWKS 从 `https://auth.openai.com/.well-known/openid-configuration` 取，检查 iss=`https://auth.openai.com`、aud=签发的 client_id、是否过期、nonce。返回的 scope 里必须有 `chatgpt.tokens.use.direct`。全部通过才存令牌，并把邮箱写进 `models.yaml`。
   - 浏览器页面只显示一句「已登录，可以关掉这页回到 JobApply」或失败原因。
5. **每次请求时拿令牌**：`ModelRegistry.endpoint()` 现在是建模型时把 key 解析一次，access_token 只有 1 小时，所以这个格式改成在每次请求时取令牌。做法是新建一个令牌源（例如 `ChatGptAuth`），`token()` 在快过期（留一点余量）时刷新：POST 表单，带 `grant_type=refresh_token`、client_id、refresh_token、resource，不带 scope。刷新要串行（同一时间只有一个线程刷新，其它线程等它的结果）。refresh_token 每次都会换新，新令牌要原子写回。收到 `invalid_grant` / `refresh_token_reused` 时清掉登录状态，报「ChatGPT 登录已失效，请重新登录」，不重试。
6. **Responses 聊天模型**（新类，例如 `config/ChatGptResponsesChatModel`，实现 Spring AI `ChatModel`）：
   - 调用：只发 `POST https://api.openai.com/v1/responses`，带 `Authorization: Bearer <access_token>`。每次请求都要 `store:false`、`stream:true`。只有收到 `response.completed` 才算成功，流中途来的 `response.failed` 当失败处理。openai-java 4.49.0 有 `client.responses().createStreaming(...)`（jar 里有 `ResponseServiceImpl...createStreaming`、`models/responses/ResponseStreamEvent`），优先复用它和 OkHttp 拦截器链（`OpenCodeSession` 这类拦截器照常挂）。它用不了时（比如令牌注入、字段控制不方便），再自己写 OkHttp SSE 客户端，施工时定。
   - 消息映射：system 消息放进 `instructions`（`role:"system"` 会被拒绝）。用户 / 助手文本转成 input message。助手的工具调用转成 `function_call` 项，工具结果转成 `function_call_output`（call_id 对上）。图片 Media 转成 `input_image`（data URL）。没有 `previous_response_id`，所以每轮都把完整历史发过去（`Conversation` 本来就是这样）。
   - 不发这些字段：background、conversation、max_output_tokens、max_tool_calls、metadata、moderation、prompt、prompt_cache_retention、safety_identifier、temperature、top_logprobs、top_p、truncation、user。只用 function 工具，不用 hosted 工具。
   - 解析 SSE：`output_text` 增量拼成文本，`function_call` 项转成 Spring AI 的 `ToolCall`，reasoning summary 放进 `reasoningContent` metadata（`ModelCaller` 第 77–95 行读这个，用于显示思考）。`usage` 里的 input/output tokens 转成 Spring AI 的 Usage。store:false 时，reasoning 项要不要带 `include:["reasoning.encrypted_content"]` 发回去，施工时看文档决定，真实测试里确认多轮工具调用不报错。
   - 选项：新的选项类要实现 `ToolCallingChatOptions` 和 `StructuredOutputChatOptions`，`ModelFactory.withTools` / `withSchema`（调用方：`AgentLoop` 第 239 行、`memory/StructuredCall` 第 46 行）加 `chatgpt` 分支。结构化输出走 Responses 的 `text.format` json_schema。
   - `ModelFactory.create` 加 `chatgpt` 分支。
7. **模型列表**：`ModelCatalogClient` 对这个格式用令牌发 `GET https://api.openai.com/v1/models`，只保留 `visibility=="list"` 的模型，页面显示 `display_name`，存和发送用 slug。
8. **错误**：下面四种错误转成 `NonTransientAiException`（`ModelCaller.NON_TRANSIENT` 已经包含它，不会重试），把中文说明原样显示给用户：
   - `subscription_sharing_usage_limit_exceeded`（429）：ChatGPT 套餐额度用完了，附「Manage usage」链接。
   - `subscription_sharing_user_not_eligible`（403）：这个账号的套餐不能用。
   - `subscription_sharing_usage_unavailable`（503）：ChatGPT 额度暂时不可用。
   - `subscription_sharing_invalid_user`（401）：登录失效，请重新登录。

   普通 401 先刷新令牌重试一次，还不行再按「登录失效」处理。其它错误照常走现有的重试逻辑。
9. **思考强度**：模型名是 GPT 推理模型时，用途分配里显示「思考」下拉，映射成 Responses 的 `reasoning.effort`。档位暂定 默认 / 低 / 中 / 高（还可能有 `minimal`、`xhigh`），具体可用的值要在真实测试里确认；「默认」不发这个参数。复用 C027 的 `assignments.<用途>.thinking` 和 `ModelFactory` 里同一个映射函数（见待确认 2）。
10. **界面**（`ModelSettings.tsx` / `api.ts`）：
    - 添加提供方的预设列表里加这一项。
    - 提供方卡片里 Key 那一行（现在第 327–336 行）对 `chatgpt` 格式换成：未登录时显示「Continue with ChatGPT」按钮；等待时显示「请在浏览器里完成登录…」并每秒轮询，可以取消；登录后显示「Using ChatGPT plan · 邮箱」、「Manage usage」（新窗口打开 https://chatgpt.com/settings/usage）和「Sign out」。
    - Base URL 不能改。拉取模型列表、测试、用途分配和现在一样。

## dev-limit 核对

- 「不替用户注册或登录第三方账号（用户在本机 Chrome 里自己登录）」：用户自己点按钮，在自己的浏览器里输入账号、授权，系统只接收 OAuth 回调里的授权码，不碰密码，不替用户点授权。这和用户在 Chrome 里自己登录招聘网站是同一类事，不越线。
- 密钥：令牌按 `.env` 的规矩处理（第 2 条）：只放在本地 0600 文件里，不进日志、`models.yaml`、文档、提示词和提交；代理不读这个文件。
- 申请最终提交：不受影响，Harness 的守卫不变。
- 真实登录和真实调用都只在用户手动操作时发生，自动测试不访问网络。

## 要改的文件（大致）

- 后端：`persistence/ModelSettingsFile`（FORMATS、元数据）、`config/ModelPresets`、`config/ModelFactory`（Endpoint 校验、create、withTools、withSchema、思考映射）、`config/ModelRegistry`（chatgpt 格式不解析 key，改用令牌源）、`config/ModelCatalogClient`、`config/ConnectionTester`（走 create，大概不用改）、`config/ModelSettingsService` 和 `api/ModelSettingsController`（signin 三个接口）。新增 `config/ChatGptAuth`（OAuth、回调、令牌文件、刷新）、`config/ChatGptResponsesChatModel`（加上它的选项类）。
- 前端：`frontend/src/api.ts`、`frontend/src/pages/ModelSettings.tsx`。
- 测试：不主动加。`ModelFactoryTest` / `ModelSettingsServiceTest` 如果因为格式集合变化失败，就跟着改。

## 怎么确认能用

- `backend/`：`JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -B clean verify`。
- `frontend/`：`npm run build`。
- 真实测试（用户操作，代理只看界面和日志里的结果）：
  1. 添加「ChatGPT 套餐」，点 Continue with ChatGPT，在浏览器里登录、授权，回到页面看到 Using ChatGPT plan 和邮箱。`chatgpt-auth.json` 只检查文件在不在、权限是不是 600。
  2. 拉取模型列表，看到显示名。
  3. 测试按钮：ok 和工具调用都是成功。
  4. 分配为主模型，在 Session 里跑一轮带工具调用的对话，再发一张图片附件，确认模型能看到图。跑一段申请，确认多轮工具调用不报错。
  5. 过一小时以后再用一次（或等 access_token 过期），确认会自动刷新，不用重新登录。
  6. 额度或资格错误（能碰到时）：页面直接显示中文说明，日志里没有重试。
  7. Sign out 后卡片回到未登录，令牌文件被删掉。
  8. 运行 `appbuild/macos/build.sh` 重新打包 App，在 App 里点 Continue with ChatGPT，确认打开的是系统浏览器，回调能回到 App。

## 需回写 Master（待用户确认）

`plan/master/ui.md`「模型设置」→「目标」现在写的是「接口格式支持两种：OpenAI 兼容格式（Chat Completions），以及 Anthropic 格式（Messages）」。确认后要改成：

- 「目标」加第三种：**ChatGPT 套餐**（OpenAI Responses API，只能用「用 ChatGPT 登录」，不填 key）。
- 「预设」表加一行：「ChatGPT 套餐（用 ChatGPT 登录） | ChatGPT（Responses） | 用户在浏览器里登录，用订阅额度；官方 SIWC 流程（preview）；不用 Codex client id（C028）」。
- 「密钥存放」加一句：ChatGPT 的令牌放在 `storage/settings/chatgpt-auth.json`（权限 0600），按 `.env` 的规矩处理，`models.yaml` 只放登录状态和邮箱。
- 「实现」加一句：`chatgpt` 格式用自己写的 Responses ChatModel，不用 `OpenAiChatModel`。
- 如果待确认 2 选「要」，「用途分配」的思考下拉一段加上 GPT（`reasoning.effort`）。

## 用户确认（2026-11-07）

1. **用官方 SIWC 流程**，不借用 Codex CLI 的 client id。
2. **GPT 模型给「思考」下拉**（复用 C027，档位 默认 / 低 / 中 / 高，真实测试后按实际能用的值调整）。
3. **只做一个 ChatGPT 账号**：只允许一个 `chatgpt` 提供方，只有一份令牌文件。
4. **登录后自动拉取模型列表**；测试和分配仍由用户自己点。

## 风险

- 这个流程还是 **preview**，接口、字段限制和 scope 都可能变。上线时间和支持哪些套餐（Plus/Pro 还是 Go/Plus/Pro）只有二手或不一致的说法，未确认。
- 用户的套餐能不能用，只有真实登录、真实调用才知道。代码没法提前判断，只能把 `not_eligible` 错误清楚地显示出来。
- Responses API 限制了很多字段（不能用 system role、没有 previous_response_id、不能用 hosted 工具、不能发 max_output_tokens 和 temperature），新的 ChatModel 必须完全不发这些字段。现有调用方传过来的这类选项要直接丢掉，不能让请求报错。
- store:false 时，多轮工具调用里 reasoning 项怎么发回去，文档写得不细，要在真实测试里确认。
- 额度是 ChatGPT 套餐的 Work/Codex 额度，用完以后可能一段时间用不了。用户最好再配一个按 key 计费的提供方，在设置里手动切换（不做自动切换）。

## 施工记录 · 前端

- 改动文件：`frontend/src/api.ts`、`frontend/src/pages/ModelSettings.tsx`（沿用 C025 的现有样式类，没改 CSS）。
- `api.ts`：新增 `ModelFormat = "openai" | "anthropic" | "chatgpt"`（`Preset.format`、`Provider.format` 用它）；`Provider.account?: {signedIn, email} | null`；`ChatGptStatus`；`api.chatgptSignin`（POST `{}` → `{authorizeUrl}`）、`api.chatgptStatus`（GET `.../chatgpt/status`）、`api.chatgptSignout`（DELETE `.../chatgpt/signin`）。
- 添加提供方：已有 chatgpt 格式的提供方时，预设下拉里 ChatGPT 那项禁用并标「（已添加 ChatGPT 套餐）」，选中时「添加」禁用并提示「已添加 ChatGPT 套餐」；ChatGPT 预设不显示 Base URL 输入框。`FORMAT_LABEL.chatgpt` =「ChatGPT 套餐（Responses）」。
- 提供方卡片（chatgpt 格式）：不显示 Base URL、Key 行、自定义请求头。
  - 未登录：「Continue with ChatGPT」+ 说明一句；点后调 signin，`window.open(authorizeUrl, "_blank")`，进入等待态。
  - 等待：「等待浏览器里完成登录…」+「没打开浏览器？点这里」（`target=_blank` 链接，兜底弹窗被拦；WKWebView 没开 `javaScriptCanOpenWindowsAutomatically`，fetch 之后的 window.open 可能失去用户手势，这个链接走 main.swift 的系统浏览器分支）+「取消」（只停轮询）。每 1.5 秒查一次 status（上一次返回后才排下一次），最多 5 分钟；`signed_in` → 刷新设置并自动拉取模型列表；`error` → 显示后端的 error；`idle` → 当作没完成，提示重试；超时 → 提示重试。
  - 已登录：「Using ChatGPT plan · 邮箱」、「Manage usage」（https://chatgpt.com/settings/usage，新窗口）、「Sign out」（confirm 后 DELETE 并刷新）；下面是和其它提供方一样的拉取模型列表 / 搜索加入 / 测试 / 保存模型选择。未登录时隐藏拉取 / 搜索行，已选模型列表仍显示。
  - 删除提供方的确认文案对 chatgpt 不再提 .env 里的 Key。
- 用途分配：分配的提供方是 chatgpt 格式时，显示「思考」下拉：默认（不发）/ 低 / 中 / 高，存为 C027 的 `thinking`（`""` → 不带，`low` / `medium` / `high`）。
- 验证：`cd frontend && npm run build` 通过（只有原有的 chunk > 500 kB 提示）。真实登录流程未运行（等后端和用户实测）。

## 施工记录 · Responses 模型

- 新增：`config/ChatGptResponsesChatModel`（实现 Spring AI `ChatModel`）、`config/ChatGptResponsesOptions`（继承 `DefaultToolCallingChatOptions`、实现 `StructuredOutputChatOptions`，和 `OpenAiChatOptions` 同一结构，`ModelFactory.withTools(model)` / `withSchema` 的 `mutate()` 照常用）、`config/NonTransientAiException`（Spring AI 2.0.1 类路径上没有 `org.springframework.ai.retry.NonTransientAiException`，本仓库定义同名类；`ModelCaller` 按简单类名判断，不用改）、契约接口 `config/ChatGptAccessTokens`。测试：`ChatGptResponsesChatModelTest`（请求体映射、SSE 解析、推理项回放、401 刷新重试；本地 HttpServer，不连外网）。
- 改动：`ModelFactory`（`CHATGPT` 常量；`Endpoint` 加第 7 个字段 `chatGptTokens` 和 `withChatGptTokens(...)`，原来的 4/5/6 参构造照旧；格式校验放开 `chatgpt`；`create` / `withTools(endpoint)` 加分支）、`ModelRegistry`（新构造参数 `ChatGptAccessTokens`，`forUsage` 建模型前 `withChatGptTokens`）、`ConnectionTester`（新构造 `ConnectionTester(ChatGptAccessTokens)`，`test()` 给 chatgpt 端点填上令牌源，探测逻辑不变）、`SettingsConfiguration`（用 `ObjectProvider<ChatGptAccessTokens>` 包一层，第一次请求时才取 Bean；没有 Bean 时报「还没有用 ChatGPT 登录」）。
- 令牌：不在建模型时解析，每次 `call()` 时 `tokens.current()`；HTTP 401 → `invalidate()` 后换新令牌重试一次，再 401 → `NonTransientAiException`「ChatGPT 登录已失效，请在设置页重新登录」。
- 传输：自己用 `java.net.http` 发 `POST <baseUrl>/responses`（`stream:true`，整个 SSE 在超时内收完再解析），没用 openai-java：字段完全由我们控制，不会被 SDK 带上不允许的字段，SDK 也不会自己重试。
- 请求映射：system 全部合进 `instructions`（多条用空行连接）；用户文字 / 图片 → `input_text` / `input_image`（`Media` 的 byte[] 转 data URL）；助手文字 → assistant `output_text`；工具调用 → `function_call`（call_id、name、arguments）；工具结果 → `function_call_output`；工具 `{type:function, name, description, parameters, strict:false}`；结构化输出 `text.format={type:json_schema, name:json_schema, schema, strict:true}`（和 OpenAI 格式的 `response_format` 一致）。temperature、maxTokens、topP 等选项即使调用方设了也不发；不发 previous_response_id。
- 思考 / 推理：模型名像推理模型（`gpt-5*`、`o<数字>*`、含 `codex`）时发 `reasoning.summary=auto`；`thinking` 是 low/medium/high 时加 `reasoning.effort`，默认 / off / max 不发 effort。思考摘要放进 AssistantMessage metadata `reasoningContent`（`ModelCaller.thinking` 已经读它）。
- **encrypted_content 的决定**：SIWC 文档没写推理项怎么处理（preview-limitations 也没把 `include` 列为不支持）。store:false 时服务端不留推理项，按 Responses 通用做法（Codex 也这样），推理模型请求加 `include:["reasoning.encrypted_content"]`，把返回的 reasoning 项（去掉 id、status）按「工具调用 id → 推理项」记在有上限（2048）的内存表里，重建对话时插在对应 assistant 消息前面；只回放给同一个模型名，应用重启或换模型时不回放（只少了上一步的思路，不报错）。没有推理项时 function_call 不带 id 也能单独发，所以这不是能不能跑的前提，是多轮工具调用的质量。真实测试时如果 `include` 被 `subscription_sharing_unsupported_capability` 拒绝，删掉这一项即可。
- 错误：`response.failed` / `error` 事件 / HTTP 错误体里的 `error.code` 统一映射；`usage_limit_exceeded`、`user_not_eligible`、`usage_unavailable`（含 `user_unavailable`）、`invalid_user`、`unsupported_capability`、`chatpass_*` 和其它 4xx（408/409/429 除外）→ `NonTransientAiException`（中文说明，额度用完附 chatgpt.com/settings/usage）；5xx、超时、流在 `response.completed` 前断开 → 普通 RuntimeException，`ModelCaller` 照常重试。`response.incomplete` → 不重试。错误文字遮掉当前令牌、`Bearer xxx` 和 JWT 形状的串，截到 300 字。
- 验证：`mvn -B clean verify` 见 dev-log / 施工汇报。真实调用未运行。
- 未决：超时沿用 `ModelRegistry.TIMEOUT`（120 秒，整个流），高思考强度的长回复可能超时，真实测试后再看；`response.incomplete`（例如内容过滤）目前不重试。

## 施工记录 · 后端登录

- 新增：
  - `config/ChatGptAuth`（`@Component`，实现契约 `ChatGptAccessTokens`）：授权 URL、本机回调监听（JDK `HttpServer`，`127.0.0.1` 随机端口，`/auth/callback`，5 分钟超时，再点一次作废上一次）、换令牌、刷新、吊销、状态。
  - `config/ChatGptIdToken`：id_token 校验。JWKS 从 openid-configuration 的 `jwks_uri` 取（kid 找不到时重取一次，应对密钥轮换）；签名用 JDK 自带算法验 RS256 / ES256，**没有加依赖**（类路径上没有 nimbus / jjwt）；再查 iss=`https://auth.openai.com`、aud（字符串或数组）含签发的 client_id、exp（留 60 秒误差）、nonce；邮箱取 `email` claim，身份取 `sub`。
  - `persistence/ChatGptTokenStore`：两个文件，都是 600、临时文件 + 原子改名。`chatgpt-auth.json` 放令牌（client_id、access_token、refresh_token、过期时间、scope、email、sub），`Tokens.toString()` 遮掉令牌和邮箱。`chatgpt-client.json` 放签发的 `client_id` 和 `ext_agent_host_id`（第一次生成 `urn:uuid:<v4>`）：官方 profiles-and-sessions 要求退出再登录复用同一个 client 和 host id，而退出要删令牌文件，所以注册信息单独放，退出和删提供方都保留它。两个文件都在 `storage/*` 的 gitignore 下。`StorageLayout` 加 `chatGptAuthFile()` / `chatGptClientFile()`。
  - 契约接口 `config/ChatGptAccessTokens` 按约定内容重写（Responses 模型那边已先建了同名文件，方法不变）；异常用本仓库的 `config/NonTransientAiException`（Spring AI 2.0.1 没有 `org.springframework.ai.retry` 那个类）。
- 登录：第一次发 `client_id=dynamic_agent_client` + `agent_name_hint=JobApply`；已有签发的 client_id 时用它，不带 hint。回调先核对 state（不对就回一个错误页并继续等，不打断这次登录）；`error=access_denied` →「已取消授权」；第一次注册时回调里没有 `client_id` 算注册未完成，有就**先存再换令牌**；重新授权时回调里的 client_id 和已存的不一致就拒绝。换令牌带 grant_type、client_id、code、code_verifier、同一个 redirect_uri、resource。返回的 `scope`（没有时用回调里的 scope）必须含 `chatgpt.tokens.use.direct`。浏览器页面只显示「登录成功，可以关闭这个页面回到 JobApply」或失败原因（HTML 转义，`no-store`、`no-referrer`）。
- 令牌：`current()` 先看内存，离过期不到 2 分钟才刷新；刷新在一把锁里串行，进锁后再查一次，新的 refresh token 原子写回。`invalidate()` 把内存里的过期时间清零，下次强制刷新。刷新返回 `invalid_grant` / `refresh_token_reused` → 删令牌文件、状态记「ChatGPT 登录已失效，请在模型设置里重新登录」、抛 `NonTransientAiException`；其它 4xx → `NonTransientAiException`；5xx / 429 / 网络错误 → 普通 `IllegalStateException`（`ModelCaller` 照常重试）。锁顺序固定为 refreshLock → this，避免死锁。
- 退出：用 openid-configuration 的 `revocation_endpoint` 吊销 refresh token（`token`、`token_type_hint=refresh_token`、client_id），失败也照常删本地令牌，并在 status 的 `error` 里提示「已在本机退出，但吊销 ChatGPT 授权失败……可以在 ChatGPT 的设置里断开 JobApply」（此时 `state` 是 `idle`）。
- 设置：`ModelPresets` 加 `chatgpt`（「ChatGPT 套餐（Sign in with ChatGPT）」，format `chatgpt`，`https://api.openai.com/v1`，不要 Key）；`ModelSettingsFile.FORMATS` 加 `chatgpt`。`ModelSettingsService`：chatgpt 格式只能从这个预设添加，格式和 Base URL 固定；已有一个 chatgpt 提供方时再加 → 409「只能有一个 ChatGPT 套餐提供方（已有 …）」；chatgpt 和别的格式不能互改（409）；`setKey` 对 chatgpt 返回 400；删除 chatgpt 提供方先退出并删令牌文件；`ProviderView` 加 `account: {signedIn, email}`（只有 chatgpt 提供方有，其它为 null），从令牌文件推出，`models.yaml` 里不写登录状态和邮箱（计划第 1 条原说放 yaml，改成从令牌文件读，少一处要同步的状态）。
- 模型列表：`ModelCatalogClient` 的 chatgpt 分支用当前 access token 发 `GET https://api.openai.com/v1/models`，读 `models` 数组（没有时退回 `data`），只留 `visibility=="list"`，保持服务端顺序；id 用 `slug`，有 `display_name` 时放进 `labels`。`POST .../catalog` 的返回对 chatgpt 多一个 `labels`（slug → 显示名），其它格式不变。未登录时拉列表返回 409 和中文说明。
- 接口（`ModelSettingsController`，都走现有的 `LocalRequestGuard`）：`POST .../providers/{id}/chatgpt/signin`（`{}` → `{"authorizeUrl"}`）、`GET .../providers/{id}/chatgpt/status`（`{state, email, error}`）、`DELETE .../providers/{id}/chatgpt/signin`（返回提供方视图）。提供方不是 chatgpt 格式时 400。
- `SettingsConfiguration`：`modelSettingsService` 多传一个 `ChatGptAuth`（和 Responses 模型那边改的 `ObjectProvider` 包装并存）。
- 测试（只加两条便宜的）：`ChatGptTokenStoreTest`（600、toString 不带令牌、退出后注册信息保留）、`ChatGptIdTokenTest`（本地生成 RSA 密钥签一个 id_token，校验通过；改 nonce / aud / 签名都拒绝）。不连外网。
- 验证：并行施工时共用 `backend/target` 会被别人的 `clean` 删掉（第一次跑出现 NoClassDefFound），所以把 `backend/` 复制到临时目录单独跑 `mvn -B clean verify`：668 个单元测试 + 1 个 IT 全部通过，BUILD SUCCESS（包含当时 Responses 模型那边的代码）。真实登录、刷新、吊销**未运行**（等用户实测）。
- 未决：`id_token_hint` / `login_hint`（重新授权跳过选账号）没做；`earliest_refresh_at` 没用；吊销失败没有按文档退避重试（只提示用户）；前端目前还没用 `labels` 显示模型的显示名。
