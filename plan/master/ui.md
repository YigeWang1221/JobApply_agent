# 设计：界面结构与模型设置

状态：**已确认**（2026-10-22）；未施工。日期：2026-10-22。

数据模型见 [memory.md](memory.md)。Dashboard 的组件与口径见 [tracking.md](tracking.md)。示意页（虚构数据）：https://claude.ai/artifact/4K6eE85i51GvrLUGz8cARD

## 界面结构（用户给出，2026-10-22）

左侧始终是 Project / Session 侧栏，主区域按「当前选中了什么」切换内容：

| 选中 | 主区域显示 |
| --- | --- |
| 什么都没选（首页），或选「全局」 | **全局 Dashboard**：所有 Project 的看板、漏斗、待追踪等 |
| 一个 Project | **Project 主页**：顶部是「新建 Session」按钮；下面依次是本 Project 的 Dashboard、**候选池**（调研出来、按公司分组的公司和岗位）、最近的 Session 和它们的交接摘要 |
| 一个 Session | **对话视图**（类似 Claude）：和 AI 聊下一家投什么、看调研结果、推进申请、回来更新面试情况。右侧面板显示这个 Session 涉及的 Job 和它们的状态 |
| 一个 Job（从看板、候选池或表格点进去） | **Job 详情**：岗位信息、轮次、复盘、事件时间线，以及「涉及过它的 Session」列表 |
| 设置 | **设置页**：模型供应商、用途分配、浏览器、搜索等 |

### 新建 Session

点「新建 Session」后进入空白对话。Agent 先读本 Project 的状态，主动给出开场：

> 「候选池里有 10 个待投岗位，其中澜川智能有 3 个都比较匹配；远岫 AI 的 2 个申请已经 6 天没有更新；曜石网络有 1 个申请停在关卡 B，等你审核。今天想先做哪件事？」

然后你可以：
- 让它继续调研，新结果进入候选池；
- 选几个岗位开始投递（候选转为申请）；
- 更新某个申请的面试情况；
- 也可以几件事都做。

Session 标签（research / apply / review）根据实际发生的事情自动生成。

### 候选池（同一家公司的多个岗位）

- 按公司分组。每家公司一行，显示公司名、额度规则（未核实就写未核实）、已投和在投的数量，下面展开这家公司的各个岗位。
- 每个岗位显示：岗位名、城市、类型、匹配分、资格结论（meets / equivalent_pending / hard_gap）、发现于哪个 Session、状态（新发现 / 想投 / 不投 / 已转申请）。
- 岗位不需要和 Project 名字所指的方向一致：Project 代表资料版本和偏好，不代表岗位类别。
- 生成 `tracker/leads.md` 和 `tracker/leads.csv`：md 在 Obsidian 里直接看，csv 可以用 Excel 打开。

## 模型设置

### 目标

- 支持**自定义模型**：在设置页里自己选接口格式，填 Base URL、API key、模型名。
- 接口格式支持两种：**OpenAI 兼容格式**（Chat Completions），以及 **Anthropic 格式**（Messages）。
- 为主流厂商预留**预设**：选择预设后自动填好 Base URL 和接口格式，只需要填 key 和模型名。

### 预设（可增删；Base URL 施工时按各厂商当时的官方文档再核对）

| 预设 | 接口格式 | 说明 |
| --- | --- | --- |
| OpenAI | OpenAI | |
| Anthropic | Anthropic | |
| Google Gemini | OpenAI 兼容 | 使用其 OpenAI 兼容端点 |
| DeepSeek | OpenAI 兼容 | |
| 通义千问（DashScope） | OpenAI 兼容 | |
| Moonshot / Kimi | OpenAI 兼容 | |
| 智谱 GLM | OpenAI 兼容 | |
| OpenRouter | OpenAI 兼容 | 一个 key 接很多模型 |
| Ollama / LM Studio（本地） | OpenAI 兼容 | 不需要 key，数据不出本机 |
| 自定义 | 两种格式任选 | 公司内网网关、自建代理等 |

### 用途分配

不同的任务可以用不同的模型（参考 Hermes 的「辅助模型」做法）：

| 用途 | 说明 | 要求 |
| --- | --- | --- |
| 主模型 | 对话、规划、填表循环 | 必须支持工具调用 |
| 辅助模型 | 长页面摘要、快照压缩、Session 交接摘要 | 便宜、快 |
| 交叉核验（可选） | 用第二个模型复核匹配评分 | 最好和主模型不是同一家 |
| 本地 embedding（后置） | 以后做相似题检索 | 只允许本地模型 |

### 实现（Spring AI 2.0.1）

- 依赖：`spring-ai-openai`、`spring-ai-anthropic`，可选 `spring-ai-ollama`（Maven Central 2.0.1 均已确认存在）。
- **不使用 Spring Boot 的自动配置**来固定模型：后端根据 `settings/models.yaml` 在运行时创建 `OpenAiChatModel` 或 `AnthropicChatModel`（设置 baseUrl、key、模型名、超时）；修改设置后热替换，不需要重启。
- 设置页提供「测试连接」按钮：发一条最小请求，显示延迟、模型名、是否支持工具调用。不支持工具调用的模型，不能被分配为主模型。
- 记录每次调用的 token 用量，按用途、按 Project 汇总（Dashboard 可以加一张成本卡，后置）。

### 密钥存放（用户已确认，2026-10-22：本地 `.env`）

- API key 保存在本地 `.env` 文件：`storage/settings/.env`，文件权限 600。`settings/models.yaml` 里只写变量名。设置页保存 key 时由后端写入 `.env`，页面只显示末四位。
- `.env` 被主仓库的 `.gitignore`（整个 `storage/`）和 `storage/.gitignore` 同时忽略，不会进入任何 git 历史。
- 开发时的代理（Claude）遵守 `dev-limit.md`：不读取、不修改这个文件；验证时只看变量名或退出码。
- key 不进日志，不发给模型，不出现在任何 Markdown 文件里。

`settings/models.yaml` 示例：

```yaml
providers:
  - id: anthropic-main
    preset: anthropic
    format: anthropic
    base_url: https://api.anthropic.com
    api_key_env: ANTHROPIC_MAIN_API_KEY     # 值在 storage/settings/.env
    models: [<模型名>]
  - id: local
    preset: ollama
    format: openai
    base_url: http://localhost:11434/v1
    api_key_env: null
    models: [<本地模型名>]
assignments:
  main: { provider: anthropic-main, model: <模型名> }
  auxiliary: { provider: local, model: <本地模型名> }
  cross_check: null
```

## 首次使用引导（遗漏检查第 7 项，已确认）

1. **建 base**：上传主简历和资料 → 提取 → 逐组核实（流程同 memory「新建 Project」）。
2. **配模型**：选预设或自定义，填 key，测试连接，分配用途。
3. **连接浏览器**：安装 Playwright 扩展，在 Chrome 里同意连接。
4. **可选**：填 Brave key（不填也能用免费搜索来源）。
5. **提示**：建议开启 Time Machine 备份 `storage/`。

## 隐私说明（遗漏检查第 5 项，已确认）

设置页的模型分配处写明：「填表与对话时，A / B 级信息（简历内容、联系方式等）会发送给所选的模型供应商；C 级信息（证件、工作授权、薪资等）永不发送。」可以给「填表」单独指定本地模型。

## 待用户决定

已确认（2026-10-22）：
1. 前端框架：React + Vite，打包成本地网页，由 Spring Boot 提供静态文件。
2. 密钥存放：本地 `.env`（`storage/settings/.env`）。
3. 预设列表：按上表，不增删。

待用户决定：无。
