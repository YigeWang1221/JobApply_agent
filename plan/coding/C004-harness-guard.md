# C004 · Harness 循环、提交守卫、关卡 B（总表）

- 对应 Master：[workflow.md](../master/workflow.md)「里程碑」第 3 段；另见 A、B 两份各自列出的章节
- 状态：**已拆分，全部已确认**（用户确认，2026-10-22）。施工内容只写在下面两份里，本文件记录拆分方式、两份共用的事实、已定决定和回写 Master 清单。

| 份 | 内容 | 状态 |
| --- | --- | --- |
| [C004-A](C004-A-model-guard.md) | 模型接入（`models.yaml` + `.env`、两种接口格式、模型列表、测试连接）、资料原件（`assets.md`、拖拽上传、Tika 轻量分析）、前端设置页（React + Vite）、MCP 启动参数与输出目录、白名单与提交守卫（纯函数） | 已确认，待施工 |
| [C004-B](C004-B-harness-review.md) | Harness 循环、预算与打转检测、skill 体系与加载、申请线程（改名、新等待点）、选择上传文件、关卡 B 与填写报告、过程日志、申请面板、真实 LLM 测试与真实站点冒烟 | 已确认；在 C004-A 完成后施工 |

## 两份共用的事实（2026-10-22，只读查看本机 npx 缓存里的 `@playwright/mcp` 0.0.83 和 `~/.m2` 里的 Spring AI 2.0.1）

**Playwright MCP 0.0.83**
- 写操作工具的参数：`browser_click {target, element?, doubleClick?, button?, modifiers?}`；`browser_type {target, text, submit?, slowly?}`（`submit: true` 等于输完再按回车）；`browser_fill_form {fields: [{target, name, type: textbox|checkbox|radio|combobox|slider, value}]}`；`browser_select_option {target, values[]}`；`browser_press_key {key}`；`browser_file_upload {paths?[]}`（必须先有 file chooser 的 modal state）；`browser_handle_dialog {accept, promptText?}`；`browser_wait_for {time?, text?, textGone?}`；`browser_find {text?|regex?, filename?}`。
- **`target` 可以是快照里的 ref，也可以是「唯一的元素选择器」**。守卫只接受最新快照里出现过的 ref。
- **无障碍快照里看不到 `type=submit`**，只有角色和名字（例如 `button "提交" [ref=e12]`）。
- `--snapshot-mode none`：写操作的返回里不附带快照（默认 `full` 会把快照写成文件）。`--output-dir`：自动命名的输出文件（包括 console 日志）写在这里。`--console-level error`。`--image-responses omit`。`--no-webmcp`：不收集页面通过 WebMCP 注册的工具。
- 文件访问只允许 `output-dir` 和 workspace；**workspace 是 MCP 客户端声明的第一个 root**，没有声明 root 时是子进程的工作目录。
- 网页弹出的确认框在快照的 `### Modal state` 小节里显示。

**Spring AI 2.0.1**
- `ToolCallingChatOptions` 已经没有 `internalToolExecutionEnabled`；工具调用循环在 `ChatClient` 的 `ToolCallingAdvisor` 里。Harness **直接调用 `ChatModel`，不用 `ChatClient`、不挂 advisor**。交给模型的工具只有定义，`call()` 一律抛异常。提供方的 `ChatModel` 会不会自己执行工具，C004-A 第一步写小样核实。
- token 用量从 `ChatResponse.getMetadata().getUsage()` 取。

**新依赖的版本**（2026-10-22 查 Maven Central 和 npm）
- `spring-ai-openai`、`spring-ai-anthropic`：有 2.0.0 / 2.0.1 / 2.0.2，以及 2.1.0-RC1。跟着 BOM 用 2.0.1，和现有的 Spring AI 依赖对齐。
- `tika-core`、`tika-parsers-standard-package`：4.1.0（2026-10-13 发布）。
- 前端：`react` / `react-dom` 19.3.1（10-14），`vite` 8.3.4（10-08；最新的 8.3.5 是 10-21 才发布的，暂时不用），`@vitejs/plugin-react` 6.1.2（10-05），`typescript` 7.0.2（07-08），`@types/react` / `@types/react-dom` 19.3.0。Vite 8 要求 Node `^20.19 || >=22.12`，本机是 22.13.1。

## 已定决定（三轮答复，2026-10-22）

| # | 决定 | 落在哪一份 |
| --- | --- | --- |
| 1 | C003 的登录接力图扩展成整条申请线程，`LoginHandoff*` 改名为 `Application*`，接口从 `/login-handoff` 改为 `/application`，旧路径删掉 | B |
| 2 | 在这里正式做模型接入，并写一个简单的前端：可以填自定义 URL 和 Key，从列表里选模型。前端按 ui.md 用 React + Vite（`frontend/`），先只做设置页 | A |
| 3 | 自动测试用仓库内的夹具；C004-B 再找一家「就算真投了也无所谓」的真实公司，在用户监控下跑到关卡 B 停下 | A 写夹具，B 跑真实站点 |
| 4 | 保留 override，意义改成「模型判断错了」；C003 已经给它加了审计事件 | B |
| 5 | **硬边界加软边界**。硬边界写在代码里：提交、提交申请、投递、立即申请、Submit、Apply 之类的按钮，以及回车、`type submit`，一律拒绝，由用户自己点。软边界：下一步、继续、保存之类的按钮，由模型按 skill 判断用途并说明依据，拿不准或者像是最后一步时问用户。其它按钮只要不碰硬边界词表就放行，靠「检测到疑似已提交的页面就立即停下」兜底 | A 写守卫，B 写 skill |
| 6 | skill 按「它是什么」组织（通用 / 组件 / 平台），不按站点组织；站点只留一份事实记录，不算 skill。分工：代码和硬边界只能由开发代理改；skill 的具体内容由运行时的 LLM 提出候选、用户确认后写入；skill 的结构整理定期带着日志找开发代理做。本段只做过程日志，候选流程另写一份 Coding Plan | B |
| 7 | 实现 `base/assets.md`，只登记用户上传的原件（不登记副本、解析出的文本和日志）。遇到上传框时列出可选文件问用户，再更新 `materials`；允许用户把文件拖进来，先做轻量分析（Tika 读出文字、模型给一句说明）再上传；完整的事实提取不在本段 | A 写原件登记与拖拽上传，B 写选择文件那一步 |
| 8 | memory 的路径全部相对 `storage/`（由 `jobapply.storage.root` 决定，代码只从 `StorageLayout` 取路径）；已在对话里解答 | — |
| 9 | 拆成 C004-A / C004-B；Key 由程序在运行时写入 `storage/settings/.env`；配置里用占位符 `${变量名}`；在用户监控下，代理可以运行调用真实 LLM 的测试（`dev-limit.md`「真实 LLM 测试」） | A |

## 回写 Master（用户确认，2026-10-22，已回写）

回写时顺带改了 workflow.md「岗位搜索 · 数据源登记」里的旧路径 `playbook/sites/<site>.md`，跟着 Playbook 的新结构走。

1. **workflow.md「提交保护」**：第 2、3 条改成「硬边界 + 软边界」（见决定 5）；删掉「元素是 `type=submit`」，写明无障碍快照看不到 `type`；删掉「没登记过的站点首次遇到『下一步 / 保存』都先问用户」（2026-10-22 那次的确认被决定 5 取代）；守卫只接受最新快照里的 ref。
2. **workflow.md「提交保护」末尾**：补一句 Spring AI 2.0.1 的实现方式（直接调 `ChatModel`，不用 `ChatClient` 的 `ToolCallingAdvisor`）。
3. **workflow.md「Chrome MCP」**：默认启动参数加上 `--snapshot-mode none --output-dir … --console-level error --image-responses omit --no-webmcp`；写明上传文件的范围由 MCP root 限定为 `storage/uploads/`。
4. **workflow.md「主流程」**：「用户登录」一行的 override 改成「模型判断错了时由用户强制继续」；「上传与解析」一行补上「先列出可选文件问用户，可以当场拖入新文件」。
5. **workflow.md「流程优化的安全边界」**：「以偏概全」一行改成「先写进 `sites/<host>.md`（事实）或修补组件 / 平台 skill」；补上决定 6 的分工。
6. **memory.md「storage 目录」**：`playbook/` 改成 `skills/{apply-form, components/*, platforms/*}/SKILL.md` 加 `sites/<host>.md`；加上 `cache/`（写进 `storage/.gitignore`）；Job 目录加上 `run-log.jsonl`；`base/assets.md` 的栏目。memory.md「Memory 写入」第 4 条相应修改。
7. **ui.md「密钥存放」**：`models.yaml` 的示例从 `api_key_env: <变量名>` 改成 `api_key: ${<变量名>}`；写明程序运行时由后端写入和读取 `.env`。
8. **architecture.md「api」一行**：拒绝带 `Origin` 的请求，改成「只放行允许名单里的本机前端地址」；`frontend/` 一行写上 React + Vite。
9. **base.md「后置」**：「模型供应商」改成已定：用户自己配置，支持 OpenAI 兼容和 Anthropic 两种格式。

## C003 留下的已知问题（处理情况）

- 标签页按 Job 归属：A、B 的所有工具调用都经过 `BrowserSession`，沿用 `ownsTab(jobId)` 的约束。
- Playwright MCP 往工作目录写文件：C004-A 处理（启动参数、输出目录、清理、`.gitignore`）。
- MCP 进程崩溃要等 60 秒才判断为断开：本段不处理。
- 扩展模式没有在真机上测过：C004-B 冒烟时如果用户装了扩展就顺带测。
- 不能关闭自己新开的标签页：本段不处理。
- 真实登录页的识别：C004-B 真实站点冒烟时顺带测到。
