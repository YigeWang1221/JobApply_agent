# C025 · 设置页打磨：模型、资料、敏感信息；敏感信息能输入中文

- 对应 Master：[ui.md](../../master/ui.md)「模型设置」「密钥存放」「隐私说明」（不改结构和接口契约，只改控件和样子）；C014-D 敏感信息、C012 同时申请、C018 工具调用上限。
- 依据：用户（2026-11-05）：「UI 美化。上一轮打磨了操作界面，模型设置和基本信息界面没打磨，很多该是下拉框的都不是。隐私信息界面竟然不支持中文等非 ASCII 字符输入，离谱。」
- 状态：**已施工，等用户在本机试**（2026-11-05；用户回答了待定 1–5，见文末「已确认」）。
- 设计语言沿用 C023（暖灰底、白卡片、`--fs-*` 字号、`.chip`、三级按钮、`.seg`、`.subtabs`），不加 UI 库。

## 目标

1. 敏感信息的值能输入中文和任何 Unicode（IME 能用）。
2. 设置「模型」页和「资料 · 事实与画像」里该选的字段换成下拉 / 可输可选的组合框 / 分段开关；外观和 C023 的操作界面一致。

## 不做

- 不改后端接口、`models.yaml` / `workers.yaml` / `personal.env` 的格式，不加新设置项（例如推理强度：现在没有这个设置，不新增）。
- 不改 `.env` 里 API Key 的输入（Key 只有 ASCII，保留 `type="password"`）。
- 占位符键名仍是 ASCII（见下）；不改 C 级字段的处理流程。
- 不加 UI 库、组合框库；组合框用原生 `<input list>` + `<datalist>`。不写测试。

## 非 ASCII 根因与修法

**根因（前端）**：敏感信息的值输入框都是 `type="password"`：
- `frontend/src/pages/PersonalSettings.tsx:83`（每一行「新的值」）
- `frontend/src/pages/PersonalSettings.tsx:131`（「自己加一项」的「真实值」）

macOS 上 Chrome / Safari / WKWebView（C017 的 App 外壳）给密码框开「安全输入」，**输入法被强制切回英文**，拼音等 IME 根本出不来——不是字被过滤，而是打不进去。这里没有 IME 组合事件处理、正则过滤或受控输入重置的问题（`onChange` 直接 `setValue`，没有清洗）。

**后端没有编码问题**（已逐行看过）：`EnvFile` 读写都是 UTF-8（`backend/src/main/java/jobapply/persistence/EnvFile.java:208`、`:220`），不是 Properties 的 ISO-8859-1；请求是 JSON；`PersonalEnv.set` 只禁换行、`=`、`#`、NUL，中文能过（`PersonalEnv.java:56-58`）；替换和遮回（`SnapshotRedactor`、`BrowserSession`）按字符串处理。

**修法**：
1. 两个值输入框改成 `type="text"` + CSS `-webkit-text-security: disc`（看起来仍是圆点，但不是密码框，IME 可用）+ 行尾一个「显示 / 隐藏」眼睛按钮；`autoComplete="off"`、`spellCheck={false}`、`autoCapitalize="off"`。保存后照旧清空、页面不回显。
2. 顺手放宽值的限制：`=` 和 `#` 其实不会破坏 `KEY=值` 这一行（`EnvFile.valueOf` 按 `KEY=` 前缀切，没有行内注释），改成只禁换行和 NUL（`PersonalEnv.java:56-58`，提示文字同步）。`.env` 的 `EnvFile.checkValue` 不动。
3. **键名保持 ASCII**：`{KEY}` 是 `personal.env` 的变量名，也要被 `Placeholder` 精确匹配和被模型原样抄进填表调用，ASCII 标识符最稳；中文名放在「名字」（label）里，已经支持中文。「自己加一项」改成：先填名字（可中文）；KEY 留空时按名字自动生成（拼不出 ASCII 时用 `CUSTOM_<n>`），输入时实时显示大写后的 KEY。

## 每页改什么

**设置页结构**：「模型」标签现在一页叠 6 张卡片。改成标签内一排 `.subtabs`：**模型** / **运行**（同时申请 + 工具调用上限）/ **敏感信息** / **经验检索与数据位置**（见待定 1）。每张卡片的保存按钮用 `primary`，成功用 toast（现在有的用行内「已保存」、有的用 toast，统一成 toast）。

**模型（`ModelSettings.tsx`）**
- 添加提供方：「预设」保留下拉（或改成一排可点的预设卡，见待定 2）；「接口格式」改 `.seg` 两段（OpenAI 兼容 / Anthropic），只在「自定义」时可选，其它预设只显示为 chip；「id」自动生成，收进「高级」小折叠里。
- 提供方卡：Key 状态改 chip（`good` 已设置 ····1234 / 中性 未设置 / 本地不需要）；格式、预设也用 chip。
- 模型名（用户定，2026-11-05：两种都要；真正的问题是拉到的列表平铺在页面上、收不起来）：
  - **搜索框**：手填框换成带 `<datalist>` 的组合框（拉到的列表做建议，边打边筛），回车或选中即勾上；没拉到时照样手填。
  - **勾选列表放进可折叠的下拉面板**：默认收起，面板头显示「已选 N 个 · 共 M 个」；展开后顶部一个筛选框，下面是可滚动（限高）的复选框列表，已选的排在前面。点面板外或再点面板头收起。不加库，用 `<details>` 或一个小的受控 popover。
  - 已选模型在面板外显示成一行行：模型名 + 测试按钮 + 结果 chip（支持工具 / 不支持 / 失败）+ 移除 ×。
  - 有未保存改动时「保存模型选择」用 primary。
- **新预设「OpenCode Go」**（用户要，2026-11-05；OpenCode 的订阅，$10/月，每个模型有 5 小时 / 每周 / 每月额度）：
  - 放在哪：只改后端 `backend/src/main/java/jobapply/config/ModelPresets.java` 的 `ALL`（前端预设下拉来自 `GET /api/settings/models` 的 `presets`，不用改前端）。插在 OpenRouter 后面。
  - 事实（来源 [opencode.ai/docs/go](https://opencode.ai/docs/go)，2026-11-05 读）：同一个 Key（在 OpenCode Console 订阅 Go 后复制）、**接口格式按模型不同**：
    - OpenAI Chat Completions：`https://opencode.ai/inference/go/openai/v1/chat/completions`——GLM、Kimi、DeepSeek、MiMo、MiniMax M3.1 Flash、LongCat、Hy、Step、免费模型等。
    - Anthropic Messages：`https://opencode.ai/inference/go/anthropic/v1/messages`——Claude Haiku 5.5、MiniMax M3、Qwen3.8 Max / Flash、Qwen3.7 Plus。
    - OpenAI Responses（GPT、Grok、Muse Spark）和 Google（Gemini）端点：本项目两种格式都不支持，这些模型用不了。
  - 所以加**两个预设**：`opencode-go`（openai，Base URL `https://opencode.ai/inference/go/openai/v1`）和 `opencode-go-anthropic`（anthropic，Base URL `https://opencode.ai/inference/go/anthropic`，SDK 自己拼 `/v1/messages`，和现有 Anthropic 预设同一写法）。note 写明「同一个 Key；哪个模型用哪种格式看 opencode.ai/docs/go」。见待定 5。
  - 模型列表：代码按 `{base}/models` 拉（`ModelCatalogClient.java:58`）；`https://opencode.ai/inference/go/openai/v1/models` 和文档里的 `https://opencode.ai/inference/go/v1/models` 都返回 OpenAI 风格的 `{object:"list", data:[{id}]}`（2026-11-05 不带 Key 取到，后者列出 29 个 id，例如 `glm-5.3`、`kimi-k3`、`deepseek-v4-pro`、`qwen3.8-max`、`minimax-m3`）。Anthropic 预设按 `{base}/v1/models` 拉（`/inference/go/anthropic/v1/models`）：**未确认**，拉不到时手填。
  - Key：`.env` 变量名由提供方 id 自动生成（`ModelSettingsFile.java:181`，例如 `opencode-go-main` → `OPENCODE_GO_MAIN_API_KEY`）；两个提供方各一个变量，要把同一个 Key 填两次。直接调 API 时的认证头（openai 端点 `Authorization: Bearer`、anthropic 端点 `x-api-key`）文档没写，**未确认**，以「测试」按钮的结果为准。
  - 旧的第三方资料写的是 `https://opencode.ai/zen/go/v1`（[docs.docker.com](https://docs.docker.com/ai/docker-agent/providers/opencode-go/)、[docs.rs adk-model](https://docs.rs/crate/adk-model/2.3.0/source/src/opencode/service.rs)），和现在的官方文档不一致，按官方文档 `/inference/go/...`。
- 用途分配：三个下拉保留；没通过工具测试的选项照旧禁用；思考过程说明和隐私说明收成卡片底部一行 muted 小字。

**运行（`WorkerSettings.tsx`、`ToolCallSettings.tsx`）**
- 同时申请数：`number` 输入改下拉 1…maxWorkers；同站上限：下拉 1…当前同时申请数。
- 工具调用上限：每项改组合框：下拉「不限 / 50 / 100 / 200 / 默认（N）」+ 可手填整数（`<input list>`），保存校验不变。

**敏感信息（`PersonalSettings.tsx`）**
- 表格 5 列挤一行 → 每项一行卡片式：左边中文名 + English + `{KEY}`（mono 小字），状态 chip，右边值输入 + 保存 / 清除。
- 值有固定选项的预设键给可输可选的组合框（仍可手填，选项是建议不是限制）：
  - `ID_TYPE`：居民身份证 / 护照 / 港澳居民来往内地通行证 / 台湾居民来往大陆通行证 / 外国人永久居留身份证
  - `NEEDS_SPONSORSHIP`：是 / 否；`CRIMINAL_RECORD`：无 / 有
  - `DISABILITY_STATUS`、`VETERAN_STATUS`：否 / 是 / 不愿透露
  - `HEALTH_STATUS`：健康 / 良好 / 一般
  - `ETHNICITY`：56 个民族（汉族在前）+ 不愿透露；`RELIGION`：无 / 不愿透露
  - `PASSPORT_EXPIRY`：`type="date"`
  - 其余（证件号、薪资、签证、工作授权）：普通文本
  - 组合框的字段选中的值在输入时可见（选项本身不敏感），保存后照样不回显。
- 选项清单写在 `PersonalSettings.tsx` 里一张小表（前端常量），后端 `PersonalKey` 不动。

**资料 · 事实与画像（`Profile.tsx`，「基础信息」「教育」等组）**
- 事实键是模型导入时起的（自由命名），所以按**键名末段**认几类常见字段，给组合框建议（仍可手填）：`gender` 性别、`degree` 学历（大专 / 本科 / 硕士 / 博士）、`political_status` 政治面貌（群众 / 共青团员 / 中共预备党员 / 中共党员）、`study_mode` 全日制 / 非全日制、`marital_status`；认不出的照旧文本框。认的键名清单见待定 3。
- 行头：键名 mono 小字 + 分级 chip（A / B）+ 「未审阅」改 `chip wait`；来源、确认日期一行 muted。
- 「保存 / 标为已审阅」用 primary / secondary 两级；项目卡、画像的按钮同样分级。
- 数据位置（`StorageLocation.tsx`）：两个 radio 改 `.seg`。来源页（`Sources.tsx`）已经是下拉，不动。

## 要改的文件

- `frontend/src/pages/PersonalSettings.tsx`（IME 根因修复、选项、布局）
- `frontend/src/pages/ModelSettings.tsx`、`WorkerSettings.tsx`、`ToolCallSettings.tsx`、`StorageLocation.tsx`、`Profile.tsx`
- `frontend/src/pages/Settings.tsx`（模型标签内的子页签）
- `frontend/src/styles.css`（遮罩输入、设置行、chip 复用；新颜色在浅 / 深两处各写一份）
- `backend/src/main/java/jobapply/persistence/PersonalEnv.java`（只放宽 `=` / `#`，第 56–58 行）
- `backend/src/main/java/jobapply/config/ModelPresets.java`（加 OpenCode Go 两个预设）

## 怎么确认能用

- `frontend/` 里 `npm run build`、`backend/` 里 `mvn -B clean verify` 退出码 0。
- 用户在本机（浏览器和 macOS App 各一次）：敏感信息里切到拼音输入法，给「民族」「户口所在地（自己加一项）」填中文，能打出来、保存显示「已设置」；一次真实填表时占位符换成中文值。
- 用户看设置各子页、资料「事实与画像」，浅色 / 深色各一遍：下拉、组合框、分段开关可用，按钮层级和 chip 与操作界面一致。
- 代理不读 `personal.env` 来验证；只看接口返回的「已设置」。

## 待用户决定（已由下面「已确认」回答）

1. 设置「模型」标签拆成 4 个子页签（模型 / 运行 / 敏感信息 / 经验检索与数据位置），还是保持一页长卷只做视觉打磨？建议拆。
2. 添加提供方的「预设」：保留下拉，还是改成一排预设卡片（图标 + 名字，点一下即选）？建议保留下拉（少改）。
3. 事实与画像按键名认的字段（性别、学历、政治面貌、学习形式、婚姻）够不够，要不要加（例如籍贯 / 意向城市给城市建议、日期字段用月份选择器）？日期默认不改，因为导入的写法不统一，强制 `YYYY-MM` 会改动已有值。
4. 敏感信息值框：用「圆点遮罩 + 眼睛按钮」（建议），还是干脆明文输入（只在自己电脑上，保存后清空）？
5. OpenCode Go：加两个预设（OpenAI 兼容 + Anthropic，Key 填两次，建议），还是只加 OpenAI 兼容一个（Qwen、MiniMax M3、Claude Haiku 用不了）？

## 已确认（用户，2026-11-05）

1. 「模型」标签拆成 4 个子页签：同意。
2. 添加提供方的「预设」保留下拉。
3. 事实与画像：已有字段不动，只打磨界面；只做上面计划的 5 个下拉建议（性别、学历、政治面貌、学习形式、婚姻），不再加别的。
4. 敏感信息值框：圆点遮罩 + 眼睛按钮。
5. OpenCode Go：**只加 OpenAI 格式一个预设**（`opencode-go`）。另外：提供方可以带**自定义请求头**（「高级」里一张键 / 值小表）；值可以写 `${变量名}` 占位符，和 API Key 一样运行时解析；勾「密钥」的值写进 `.env`，yaml 里只留占位符；值不进 yaml（密钥）、文档、日志。保持小。

上面「每页改什么」里 OpenCode Go 的「两个预设」以本节为准：只有 `opencode-go`。

## 施工记录（2026-11-05）

分四块并行施工（前端三块各自一份文件、`styles.css` 各追加一节），后端和合龙由主线做。

- **设置页结构**（`Settings.tsx`）：「模型」标签下 `.subtabs`：模型 / 运行 / 敏感信息 / 经验检索与数据位置；选中的子页记在 localStorage `jobapply.settings.sub`（读写包 try/catch）。「运行」子页是 `RunSettings`（同时申请 + 工具调用上限），新的运行类卡片加在那里（C026 的查看间隔会加在这）。
- **非 ASCII**（`PersonalSettings.tsx`）：值框改 `type="text"` + `.masked`（`-webkit-text-security: disc`）+ 眼睛按钮；关掉自动填充 / 拼写 / 大写。表格改每项一行；预设键的选项（证件类型、56 个民族等）用 `<datalist>`，护照有效期 `type="date"`；「自己加一项」先填名字，KEY 可留空自动生成（ASCII 片段转大写，没有时 `CUSTOM_<n>`）。后端 `PersonalEnv.set` 只禁换行和 NUL，经新的 `EnvFile.setUnchecked` 写入（`EnvFile.set` / `.env` 的 Key 检查不变）。
- **模型**（`ModelSettings.tsx`、`api.ts`）：接口格式 `.seg`（只在自定义时可选），id 收进「高级」；Key / 预设 / 格式用 chip；模型名搜索框（`<datalist>`）+ 默认收起的勾选面板（「已选 N 个 · 共 M 个」、筛选框、已选在前）；已选模型一行一个（测试、结果 chip、×）；用途分配保存用 toast。
- **自定义请求头**：`models.yaml` 每个提供方可有 `headers:`（名字 → 值）。`PUT /api/settings/models/providers/{id}/headers`，body `{headers:[{name,value,secret}]}`，整张替换；密钥行的值写进 `.env` 的 `<ID>_HEADER_<NAME>`，yaml 只留占位符，留空 = 沿用；去掉的密钥行连 `.env` 那一行一起删；删提供方时也删。`GET` 的提供方多 `headers:[{name,value|null,secret,set}]`，密钥值不返回。运行时 `ModelRegistry.headers` 解析占位符，经 Spring AI 的 `customHeaders` 发给 OpenAI / Anthropic 两种模型；拉模型列表也带上，错误信息里遮掉请求头的值。`ModelFactory.Endpoint` 多一个 `headers`（旧的 4 参构造保留），`toString` 只列名字。
- **OpenCode Go**：`ModelPresets` 加 `opencode-go`（openai，`https://opencode.ai/inference/go/openai/v1`），note 写明哪些模型能用。
- **运行 / 资料**：同时申请、同站上限改下拉；工具调用上限改组合框（不限 / 50 / 100 / 200 / 默认）；数据位置首次选择的 radio 改 `.seg`；事实与画像按键名末段给 5 个下拉建议（gender、degree、political_status、study_mode、marital_status），行头 chip，按钮分级。

**验证**：`frontend/` 里 `npm run build` 退出码 0。`backend/` 工作区里 `mvn -B clean verify` 退出码 1：C026 正在施工的 `FollowupLoop.java` 引用还没写的 `JobLedger.Proposal` / `PortalCheck`（不是本步的文件）。把 backend 复制到草稿目录、那 5 个 C026 文件还原成 HEAD 后跑 `mvn -B clean verify` 退出码 0（Tests run: 662 + IT 1，Failures 0）。浏览器里看、IME 输入中文、真实模型带请求头调用：未运行。

## 偏差

- 「自己加一项」的 KEY 自动生成按 ASCII 片段，不做拼音转换（不加依赖）。
- 子代理额外加了几处 toast（添加提供方、保存 Key、拉列表、保存模型、保存请求头）和数据位置「确定」按钮的 primary；B 级事实用 `chip inp`，免得和「未审阅」的 `chip wait` 混。
- Anthropic 格式下 `customHeaders` 是否真的随请求发出：编译通过，没对真实端点核对（未确认）。
