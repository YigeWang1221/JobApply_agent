# C004-A · 模型接入、资料原件、设置页、提交守卫

- 对应 Master：[ui.md](../master/ui.md)「模型设置」「密钥存放」「隐私说明」；[memory.md](../master/memory.md)「storage 目录」（`base/assets.md`、`base/originals/`、`settings/`）「文档读取工具」；[workflow.md](../master/workflow.md)「提交保护」「Chrome MCP」；[architecture.md](../master/architecture.md)（`api`、`frontend/`）
- 总表：[C004](C004-harness-guard.md)（拆分方式、共用的事实、已定决定、回写 Master 清单）
- 状态：**已确认，待施工**（用户确认，2026-10-22：「待确认」全部按建议；总表的「回写 Master」已回写）。

## 目标

本段做完后，C004-B 需要的几样东西都已经备齐：可用的模型、登记好的资料原件、不会写文件的 MCP、写成纯函数的守卫。可观察行为（输入 → 状态变化 → 输出）：

1. **模型设置**：在设置页里添加一个提供方（选预设，或者自定义接口格式和 Base URL）→ 填 Key → 后端把 Key 写进 `storage/settings/.env`（权限 600），`settings/models.yaml` 里只写占位符 `${变量名}`；页面上只显示 Key 的末四位。
2. **模型列表**：点「拉取模型列表」→ 后端用这个提供方的 Key 请求它的模型列表接口 → 页面显示可选的模型，可以多选；拉不到时（有的网关没有这个接口）显示原因，并允许手填模型名。
3. **测试连接**：点「测试」→ 发一条最小请求，附带一个测试用的工具定义 → 显示延迟、模型名、**是否支持工具调用**，结果记进 `models.yaml`。
4. **用途分配**：主模型、辅助模型、交叉核验（可选）。没有通过工具调用测试的模型不能分配为主模型（ui.md）。保存后立即生效，不用重启（热替换）。
5. **资料原件**：在「资料」页把文件拖进来 → 存进 `base/originals/`，登记到 `base/assets.md`（文件名、类型、大小、SHA-256、上传时间、说明）→ 用 Tika 读出文字，交给辅助模型（没有配置就用主模型）写一句说明，例如「中文简历，2025 版，2 页」。同一个 SHA-256 已经登记过的文件不重复保存。`assets.md` 只登记用户上传的原件。
6. **MCP 不再写文件**：启动参数加上 `--snapshot-mode none --output-dir <storage>/cache/playwright-mcp --console-level error --image-responses omit --no-webmcp`；MCP 客户端声明 root 为 `storage/uploads/`；每次建立连接和关闭时清空输出目录。`storage/.gitignore` 加上 `cache/`。
7. **守卫（纯函数）**：输入最新快照和模型要做的调用，输出 `Allow`、`Reject`（hardline，附「不要重试」）、`NeedsConfirm`（软边界，要问用户）或 `Handoff`（要用户亲自操作）。本段只写规则和测试；接进循环是 C004-B 的事。

## 不做（留给 C004-B 或后续）

- Harness 循环、图、关卡 B、申请面板：C004-B。
- 前端的对话视图、Project / Session 侧栏、Dashboard：第 7 段。本段的前端只有「设置 · 模型」和「资料」两页。
- 由 Spring Boot 提供打包好的前端静态文件：第 7 段。本段用 Vite 开发服务器加代理。
- 从原件里提取事实写进 `facts.md`（「新建 Project」流程）：后续。本段的轻量分析只写一句说明。
- 本地 embedding、交叉核验的实际使用：后续。本段只能在设置里分配交叉核验模型。
- 用量统计、成本卡：后续。本段只在测试连接时显示一次 token 用量。

## 依赖（版本来源见总表「新依赖的版本」）

| 用途 | 坐标 | 说明 |
| --- | --- | --- |
| OpenAI 兼容格式 | `org.springframework.ai:spring-ai-openai`（BOM，2.0.1） | 不用 starter，也不用自动配置；运行时由代码创建 |
| Anthropic 格式 | `org.springframework.ai:spring-ai-anthropic`（BOM，2.0.1） | 同上 |
| 文档读取 | `org.apache.tika:tika-core`、`tika-parsers-standard-package` 4.1.0 | memory.md 已经倾向「只引入 Tika 一个入口」 |
| 前端 | `react`、`react-dom` 19.3.1；`vite` 8.3.4；`@vitejs/plugin-react` 6.1.2；`typescript` 7.0.2；`@types/react`、`@types/react-dom` 19.3.0 | 版本写死，不用 `^`；不加 UI 库，也不加路由库 |

## 要改的文件

类名施工时可以微调，职责不变。**不新增子包**。

**`jobapply.config`**（「系统、模型与运行配置」）
- `ModelPresets`：ui.md 的预设表（OpenAI、Anthropic、Gemini、DeepSeek、通义千问、Moonshot、智谱、OpenRouter、Ollama / LM Studio、自定义），每个预设有接口格式和默认 Base URL。**Base URL 施工时按各厂商当时的官方文档逐个核对**，核对的日期和来源写进「实际结果」。
- `ModelFactory`：按 `format`、`base_url`、`model` 和解析好的 Key，创建 `OpenAiChatModel` 或 `AnthropicChatModel`（带超时）。不注册成 Spring Bean。
- `ModelRegistry`：持有每个用途当前对应的 `ChatModel`；设置保存后重新创建（热替换）。C004-B 的 Harness 从这里取主模型。没有配置时，明确报「还没有配置主模型」，不猜默认值。
- `ModelCatalogClient`：用 JDK `HttpClient` 请求模型列表。OpenAI 兼容格式请求 `GET {base}/models`，带 `Authorization: Bearer`；Anthropic 格式请求 `GET {base}/v1/models`，带 `x-api-key` 和 `anthropic-version`。超时 10 秒。返回模型 id 列表；失败时返回原因（HTTP 状态码和对方的错误信息），**错误信息里不带 Key**。
- `ConnectionTester`：用 `ModelFactory` 建一个临时的 `ChatModel`，发一条最小请求，附带一个只有定义、`call()` 会抛异常的测试工具，要求模型调用它。返回延迟、响应里的模型名、`supportsTools`（响应里有没有这个工具调用）、token 用量。
- `ApiProperties`：`jobapply.api.allowed-origins`（默认 `http://127.0.0.1:5173`、`http://localhost:5173`）。

**`jobapply.persistence`**
- `ModelSettingsFile`：读写 `settings/models.yaml`（格式见「关键规则 · 设置文件」），原子写入；开启 git 时提交（`.env` 已经被 `storage/.gitignore` 忽略，不会被提交）。
- `EnvFile`：读写 `settings/.env`，权限 600，原子写入；`${名字}` 占位符的解析。值不进日志、不进异常信息、不进 `toString()`。
- `AssetRegistry`：读写 `base/assets.md`（格式见「关键规则 · assets.md」）；保存原件，按 SHA-256 去重，文件名冲突时加后缀。
- `StorageInitializer`：`GITIGNORE_LINES` 加上 `cache/`；已有的 `storage/.gitignore` 缺这一行时**追加**（现在是 `writeIfAbsent`，已有的文件不会更新）。
- `StorageLayout`：加 `settingsDir()`、`modelsYaml()`、`envFile()`、`baseAssets()`、`uploadsDir()`、`mcpOutputDir()`。

**`jobapply.memory`**
- `AssetAnalyzer`：用 Tika 把原件转成文字（只在内存里用，不写文件），取开头 4,000 个字符交给辅助模型（没有就用主模型），要一句不超过 60 个字的说明。没有配置任何模型时跳过分析，说明留空，用户可以手填。扫描件 PDF 读不出文字时，说明写「没有文字层，可能是扫描件」。

**`jobapply.tools`**
- `BrowserLaunch`：加 `roots`（`storage/uploads/`）和 `outputDir`。
- `PlaywrightMcpClient`：初始化时声明 roots；`REQUIRED_TOOLS` 加上 C004-B 要用的工具（click、type、fill_form、select_option、file_upload、press_key、wait_for、find）。
- `BrowserSession`：建立连接和关闭时清空 `mcpOutputDir()`。
- `SnapshotIndex`：把快照 YAML 解析成 ref → 角色、名字、祖先节点（用来找勾选框所在分组的标题，以及按钮所在的表单）。解析不了的行标为未知，不猜。

**`jobapply.harness.policies`**（守卫，规则写在代码里，不从配置或 Playbook 读取）
- `BrowserToolPolicy`：扩大白名单，见「关键规则 · 白名单」。
- `SubmitWords`：硬边界词表、软边界词表、疑似已提交页面的词表、安全验证词表。
- `SubmitGuard`：点击和按键守卫，见「关键规则 · 硬边界与软边界」。
- `FieldClassGuard`：C 级字段的识别，见「关键规则 · C 级字段」。
- `GuardDecision`：`Allow` / `Reject(原因)` / `NeedsConfirm(按钮, 模型给的用途和依据)` / `Handoff(原因)`。

**`jobapply.api`**
- `ModelSettingsController`、`AssetController`：接口见「关键规则 · HTTP」。
- `LocalRequestGuard`：`Origin` 从「一律拒绝」改成「只放行 `allowed-origins`」；不带 `Origin` 的请求（curl）照常放行。写接口仍然要求 JSON，上传原件的接口例外，允许 `multipart/form-data`。

**`application.yml`**：MCP 默认参数；`jobapply.api.allowed-origins`；multipart 大小上限 20MB。

**`frontend/`**（新建）
- `package.json`（版本写死）、`vite.config.ts`（开发服务器只监听 `127.0.0.1:5173`，把 `/api` 代理到 `http://127.0.0.1:8080`）、`tsconfig.json`、`index.html`、`src/main.tsx`、`src/App.tsx`（两个页签，用组件状态切换，不加路由库）、`src/api.ts`（对后端接口的薄封装，出错时把后端的 `error` 显示出来）、`src/pages/ModelSettings.tsx`、`src/pages/Assets.tsx`。
- `.gitignore`：`node_modules/`、`dist/`。

**测试**：见「测试」。

**文档**：完成后补本文件的「实际结果」和「偏差」，更新 `dev-plan.md`、`plan/README.md` 的索引行、`dev-log.md`、`dev-recoding.md`；README 加上前端怎么启动。

## 关键规则

### 设置文件

`storage/settings/models.yaml`：

```yaml
providers:
  - id: deepseek-main                 # 小写字母、数字、-
    preset: deepseek                  # 或 custom
    format: openai                    # openai | anthropic
    base_url: https://<按官方文档核对>
    api_key: ${DEEPSEEK_MAIN_API_KEY} # 占位符；值在 settings/.env
    models: [<模型名>]                 # 用户从列表里选中的
    tested:                           # 最近一次测试连接的结果
      - { model: <模型名>, ok: true, tools: true, latency_ms: 820, at: 2026-10-22T10:00:00+08:00 }
assignments:
  main: { provider: deepseek-main, model: <模型名> }
  auxiliary: null
  cross_check: null
```

- 变量名由提供方的 id 生成：大写，`-` 换成 `_`，加上 `_API_KEY`。本地模型（Ollama / LM Studio）可以不填 Key，这时 `api_key: null`。
- 占位符只按 `${名字}` 解析；先找 `settings/.env`，再找进程的环境变量（方便用户自己 `export`）；都找不到时报「变量 X 没有设置」，只报名字。
- `base_url` 也可以写占位符（例如公司内网网关的地址不想写进 yaml）。
- 删除提供方时，同时删掉 `.env` 里对应的那一行；如果它正被某个用途使用，先拒绝，要求用户先改分配。
- Key 的检查：去掉首尾空格后不能为空，不超过 512 个字符，不能含换行、`=`、`#`。

### assets.md

```markdown
# 原件索引

| 键 | 文件名 | 类型 | 大小 | SHA-256 | 上传时间 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| a-2026-10-22-001 | resume-cn.pdf | application/pdf | 182 KB | 3f2a… | 2026-10-22T10:00:00+08:00 | 中文简历，2025 版，2 页 |
```

- 键是 `a-YYYY-MM-DD-NNN`，和 job id 的编号方式一样。C004-B 的 `materials` 引用这个键。
- 文件原样保存在 `base/originals/<文件名>`；同名但内容不同时，文件名加 `-2`、`-3`。
- 读的时候保留未识别的列（用户以后可能手动加「适用方向」之类的列）。
- 说明可以由用户修改；轻量分析只在第一次上传时做。

### 白名单（`BrowserToolPolicy`，发出之前检查）

| 工具 | 谁能调用 | 参数限制 |
| --- | --- | --- |
| `browser_tabs` | 只有系统 | `new` 必须带 http / https 的 `url`；`list` |
| `browser_snapshot` | 系统、模型 | 不允许 `filename` |
| `browser_find` | 模型 | 不允许 `filename` |
| `browser_navigate` | 模型 | 只允许 http / https；主机必须和岗位链接或当前页面同站点（registrable domain 相同） |
| `browser_click` | 模型 | `target` 必须是最新快照里的 ref；不允许 `button` 以外的鼠标键和 `modifiers`；先过点击守卫 |
| `browser_type` | 模型 | `target` 是 ref；**不允许 `submit: true`**；先过 C 级字段守卫 |
| `browser_fill_form` | 模型 | 每个字段的 `target` 都是 ref，角色必须是 textbox / combobox / checkbox / radio / slider / spinbutton / searchbox；逐个过 C 级字段守卫 |
| `browser_select_option` | 模型 | `target` 是 ref，角色是 combobox / listbox |
| `browser_file_upload` | 模型 | 路径必须在 `storage/uploads/<job-id>/` 下面（C004-B 由系统把文件编号换成路径，模型看不到路径） |
| `browser_press_key` | 模型 | 只允许 Tab、Shift+Tab、方向键、Escape、Backspace、Delete、Home、End、PageUp、PageDown。**回车和空格一律拒绝**（焦点在按钮上时，空格等于点击） |
| `browser_wait_for` | 模型 | `time` 不超过 10 秒 |

不开放：`evaluate`、`run_code_unsafe`、`handle_dialog`（弹窗一律交给用户）、`hover`、`drag`、`drop`、`navigate_back`、`close`、`console_messages`、`network_*`、`take_screenshot`、`tabs select/close`，以及只有加 `--caps` 才出现的工具（启动参数不加 `--caps`）。ref 的格式按 0.0.83 的快照核对（例如 `e12`、`f1e3`），选择器一律拒绝。被拒绝时，信息里写明「不要重试」。

### 硬边界与软边界（`SubmitGuard`，决定 5）

每次点击之前，用**最新一次快照**查出目标 ref 的角色和名字，按下面的顺序判断，命中一条就停。名字先做规整（去掉首尾空格，全角转半角，忽略大小写）。

1. **页面状态（hardline）**：快照里有安全验证、网页弹窗（`Modal state` 里的 dialog），或者疑似已提交的页面（「投递成功」「申请已提交」「Application submitted」「Thank you for applying」等）→ `Handoff`，停止一切写操作。
2. **判断不了（hardline）**：ref 不在最新快照里，或者那一行解析不出角色和名字 → `Reject`。
3. **硬边界（hardline）**：名字包含提交类词：提交、投递、申请、确认、立即申请、预览并提交、确认意向、Submit、Apply、Send、Finish、Confirm 等 → `Reject`，原因写「这个按钮可能完成提交，由用户自己点」。名字里同时有软边界词也一样（例如「保存并提交」）。
   - 注意：岗位详情页上进入申请表的「立即申请 / Apply」也会被拦。因为旧流程里观察到，有些站点的这个按钮会直接完成提交。这时由用户自己点进申请表，再把控制权交回来（C004-B 的接力）。
4. **软边界**：名字是「下一步、继续、下一页、保存、暂存、Next、Continue、Save」之类，不含硬边界词 → 看模型在这次点击里给的用途：
   - 模型必须给出 `purpose`（`next_step` / `save_draft` / `unsure`）、`final_step_suspected`（是否像是最后一步）和 `evidence`（依据，例如「进度条显示第 2/4 步」）。这三个参数由系统去掉后再发给 MCP。
   - `purpose` 是 `next_step` 或 `save_draft`，并且 `final_step_suspected` 为 false → `Allow`。
   - 其它情况（`unsure`、像是最后一步、缺少参数）→ `NeedsConfirm`，把按钮名字、用途和依据交给用户。
   - 用户对这个站点、这个按钮名字确认过「安全」以后，再遇到就直接 `Allow`（记录在 C004-B 的 `sites/<host>.md`）。确认记录只能放行软边界的按钮，**不能放行硬边界的按钮**。
5. **其它**：名字不碰硬边界词的任何元素（「上传简历」「添加经历」、链接、选项、页签）→ `Allow`。兜底：每次写操作之后，C004-B 都重新取快照，按第 1 条检查。

按键：回车、空格已经被白名单拒绝；其它允许的按键不经过第 3–5 条。词表写在代码里，以后只能由开发代理修改；站点记录不能删减词表（决定 6）。

### C 级字段（`FieldClassGuard`）

字段的名字，以及它所在分组的标题，命中 C 级词表时（身份证、证件、护照、签证、工作授权、sponsorship、work authorization、期望薪资、薪资、salary、民族、宗教、残障、disability、veteran、健康、犯罪记录、声明、承诺、I certify、I attest 等）→ `Handoff`，原因写「C 级字段由用户亲自填写」。宁可多拦：误判的代价只是多问用户一次。表格驱动的测试里要逐条写明不该误拦的例子（例如「学历证明」「期望城市」）。

### HTTP（只监听 127.0.0.1；`Origin` 必须在允许名单里）

| 方法 | 路径 | 请求体 | 返回 |
| --- | --- | --- | --- |
| `GET` | `/api/settings/models` | — | 提供方（每个带 `keySet`、`keyLast4`，**没有 Key 本身**）、分配、预设表 |
| `PUT` | `/api/settings/models/providers/{id}` | `{preset, format, baseUrl, models[]}` | 新建或修改 |
| `DELETE` | `/api/settings/models/providers/{id}` | — | 正在被使用时返回 409 |
| `PUT` | `/api/settings/models/providers/{id}/key` | `{key}` | 只返回 `keyLast4` |
| `POST` | `/api/settings/models/providers/{id}/catalog` | — | 模型 id 列表；失败时返回 502 和原因 |
| `POST` | `/api/settings/models/providers/{id}/test` | `{model}` | 延迟、模型名、`supportsTools`、token 用量 |
| `PUT` | `/api/settings/models/assignments` | `{main, auxiliary, crossCheck}` | 主模型没有通过工具调用测试时返回 409 |
| `GET` | `/api/assets` | — | `assets.md` 的内容 |
| `POST` | `/api/assets` | `multipart/form-data`，字段 `file` | 新登记的条目；SHA-256 已存在时返回已有的条目和 `duplicate: true` |
| `PATCH` | `/api/assets/{key}` | `{description}` | 修改说明 |

- 任何返回、错误、日志里都不出现 Key 的值（测试里断言）。
- 错误统一返回 `{"error": "<原因>"}`，沿用 C003。

### 前端

- 「设置 · 模型」页：
  - 提供方列表；「添加」表单：选预设（自动填好格式和 Base URL）或自定义；Key 输入框用 `type=password`，保存后清空，只显示末四位。
  - 「拉取模型列表」→ 勾选模型；拉不到时允许手填。
  - 每个模型一个「测试」按钮，显示延迟和「支持 / 不支持工具调用」。
  - 用途分配的下拉框：不支持工具调用的模型在「主模型」里不能选。
  - 页面底部是 ui.md 的隐私说明原文。
- 「资料」页：拖拽区加文件选择按钮；列表显示键、文件名、大小、说明（可以修改）；重复的文件提示「已登记」。页面上提示：「分析时，文件开头的文字会发给辅助模型」。
- 不在浏览器里保存 Key（不用 localStorage）。

## 实现步骤（7 个垂直切片，每片结束时 `mvn -B clean verify` 全绿）

1. **小样核实**（会话临时目录，不进仓库）：
   - 用 Spring AI 2.0.1 的 `OpenAiChatModel` 和 `AnthropicChatModel` 连本地的假 HTTP 服务端，确认直接调用 `ChatModel`、不挂 advisor 时，工具调用会原样返回、不会被执行。
   - 两个 `ChatModel` 的 Base URL 约定（是否自己加 `/v1`）。
   - MCP Java SDK 声明 roots 之后，假 MCP 服务端能收到 roots 列表。
   - 结果写进「实际结果」。
2. **守卫**：`SnapshotIndex`、`SubmitWords`、`SubmitGuard`、`FieldClassGuard`、扩大后的 `BrowserToolPolicy`，用表格驱动测试。
3. **MCP 不写文件**：启动参数、roots、输出目录清理、`storage/.gitignore` 追加 `cache/`。
4. **设置文件与模型**：`EnvFile`、`ModelSettingsFile`、`ModelPresets`、`ModelFactory`、`ModelRegistry`、`ModelCatalogClient`、`ConnectionTester`。
5. **资料原件**：`AssetRegistry`、`AssetAnalyzer`（加 Tika 依赖）。
6. **HTTP**：`ModelSettingsController`、`AssetController`、`LocalRequestGuard` 的允许名单、`application.yml`。
7. **前端**：`frontend/` 的两页；`npm run build` 通过；启动后端和 `npm run dev`，用户在页面上走一遍。

## 测试

`mvn -B clean verify` 里的自动测试**不访问外部网络、不调用真实模型、不连接真实浏览器、不调用 npx**。模型提供方一律是本地的假 HTTP 服务端（JDK `HttpServer`，监听 127.0.0.1 的随机端口），模拟 OpenAI 兼容格式和 Anthropic 格式的「对话」和「模型列表」接口。`.env` 一律写在 `@TempDir` 里，是测试自己生成的假 Key。

| 测试类 | 固定的行为 |
| --- | --- |
| `EnvFileTest` | 权限 600；原子写入；更新已有变量、删除变量；`${名字}` 解析（先找 `.env`，再找进程环境变量）；找不到时只报名字；**异常信息和 `toString()` 里没有值** |
| `ModelSettingsFileTest` | 读写往返；保留未识别的字段；id 校验；删除正在使用的提供方时被拒绝 |
| `ModelFactoryTest` | 两种格式都能对假服务端完成一次对话；请求里带了 Key（假服务端断言请求头）；**模型返回工具调用时，工具没有被执行**（测试工具的 `call()` 会抛异常，测试断言没有抛） |
| `ModelCatalogClientTest` | 两种格式的模型列表；401、404、超时时返回原因，原因里没有 Key |
| `ConnectionTesterTest` | 支持和不支持工具调用两种情况；延迟和用量 |
| `ModelRegistryTest` | 分配以后能取到主模型；修改设置后热替换；没有配置时明确报错 |
| `AssetRegistryTest`、`AssetAnalyzerTest` | 登记、按 SHA-256 去重、文件名冲突、保留未识别的列；用 Tika 读 PDF / docx / txt（测试资源是仓库里**虚构**的小文件）；没有配置模型时跳过分析；扫描件 |
| `SnapshotIndexTest` | ref、角色、名字、祖先节点；解析不了的行 |
| `SubmitGuardTest` | 五条规则逐条测（中英文、全角、大小写、首尾空格）；「保存并提交」走硬边界；软边界缺少 `purpose` 时要确认；用户确认过的按钮只放行软边界；岗位详情页的「立即申请」被拦 |
| `FieldClassGuardTest` | C 级词表的命中；不该误拦的例子 |
| `BrowserToolPolicyTest` | 白名单表的每一行：允许和拒绝；`type submit:true`、回车、空格、选择器形式的 target、跨站 navigate、`filename`、uploads 以外的路径一律拒绝；信息里有「不要重试」 |
| `PlaywrightMcpClientTest` | 新的启动参数；声明了 roots；输出目录在建立连接和关闭时被清空；被拒绝的调用没有到达服务端（沿用 C003） |
| `StorageInitializerTest` | 新建 storage 有 `cache/`；已有的 `.gitignore` 缺这一行时被追加，其它内容不变 |
| `ModelSettingsControllerTest`、`AssetControllerTest` | 各接口的 200 / 400 / 404 / 409 / 502；**任何响应体里都没有 Key**；`Origin` 在允许名单里时放行，不在时返回 403，不带时放行；multipart 上传；超过 20MB 返回 413 |

**前端**：`npm run build`（`tsc -b` 加 `vite build`）通过。本段不加前端单元测试框架。

**真实 LLM 测试**（`dev-limit.md`「真实 LLM 测试」，默认不运行，在用户监控下由代理运行）：`ModelConnectionManualCheck`（名字不以 Test / IT 结尾）。它读取用户真实的 `storage/settings/models.yaml`，由程序解析 `.env` 里的 Key，对主模型和辅助模型各做一次「测试连接」，只打印延迟、模型名、是否支持工具调用和用量。

## 手动检查（用户操作，代理在旁边看结果）

1. 用户启动后端和 `npm run dev`，在浏览器里打开 `http://127.0.0.1:5173`。
2. 添加一个提供方，**由用户自己输入 Key**，拉取模型列表，选模型，测试，分配主模型和辅助模型。
3. 在「资料」页拖入一份简历，看登记结果和一句说明。
4. 代理在用户同意后运行 `ModelConnectionManualCheck`。
5. 结果写进「实际结果」；没做就在 `dev-log.md` 写「未运行」。

## 验收标准

- `mvn -B clean verify` 全绿（新测试，加上原有的 surefire 256 + failsafe 2）。
- 自动测试过程中没有访问外部网络，没有调用 npx，没有调用真实模型。
- 任何响应、日志、异常信息里都没有 Key 的值；`.env` 的权限是 600。
- 守卫的测试覆盖「关键规则」里的每一条；被拒绝的调用没有到达 MCP 服务端。
- MCP 的输出目录在 `storage/cache/` 下，并且被清空；仓库里不再出现 `.playwright-mcp/`。
- `npm run build` 通过；用户完成手动检查，或者明确决定暂缓。

## 待确认（用户确认，2026-10-22：全部按建议）

1. **依赖**：两种格式的 Spring AI 模块都加（2.0.1，跟着 BOM），Tika 用 `tika-parsers-standard-package`（包比较大，但能处理大多数格式；另一种做法是只加 PDF 和 docx 的解析器）。前端的版本按上表。**建议：同意。**
2. **前端用 TypeScript**，不加 UI 库，不加路由库；本段只用 Vite 开发服务器，由 Spring Boot 提供静态文件留到第 7 段。**建议：同意。**
3. **Key 的来源**：先找 `settings/.env`，再找进程的环境变量。只认 `.env` 会更简单，但用户就不能自己 `export`。**建议：两者都找，`.env` 优先。**
4. **轻量分析**：取原件开头 4,000 个字符交给辅助模型（没有就用主模型），要一句说明。没有配置模型时跳过。**建议：同意。**
5. **「立即申请 / Apply」也属于硬边界**：岗位详情页上的这个按钮，由用户自己点进申请表。好处是绝不误提交；坏处是每次申请开头多一次接力。**建议：同意。**
6. **软边界的词表和参数**：词表按「关键规则」第 4 条；模型点击软边界按钮时必须给出 `purpose`、`final_step_suspected`、`evidence`。**建议：同意。**
7. **Origin 允许名单**默认只放行 `127.0.0.1:5173` 和 `localhost:5173`。**建议：同意。**
