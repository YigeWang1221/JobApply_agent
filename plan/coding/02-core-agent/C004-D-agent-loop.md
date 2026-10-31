# C004-D · Agent 循环：替换固定图（agent-runtime.md 7.8 第 1 步）

- 依据：[agent-runtime.md 第 7 节](../../master/agent-runtime.md)（用户确认，2026-10-23）。
- 目标：申请流程由一个 LLM 驱动的循环完成：系统打开岗位页，模型自己判断登录、填写、问用户，停在最终提交前。在夹具上用真实模型跑一遍。
- 不做：上下文压缩、经验写回 skill、多个 Job 并行、资料原文引用（第 6 节）、完整界面。不写测试。

## 要改的地方

- 删：图、节点、等待点、信号、Checkpoint、登录核对、页面语言、预算类和对应测试（7.7）；LangGraph4j 依赖。
- 循环（`harness.runtime`）：`HarnessLoop` 改写；`AgentRun` 是一次申请的全部状态（对话、计划、给用户的话、问题、上传位置、已备好的文件、用量），存在 `storage/runs/<job-id>.json`。
- 上下文（`harness.context.FillContext`）和提示词（`agents.ApplyAgent`）按 7.3 组织。
- 工具：`ControlTools` 换成 `ask_user`、`update_plan`、`read_skill`、`finish`；`BrowserTools` 额外参数可选。
- 守卫：`SubmitGuard` 只剩拒绝提交类按钮；C 级字段被拒时告诉模型交给用户；页面状态只提醒。
- 运行器（`workflow.ApplicationRunner`）：开始、回复、停止、状态、报告。
- 试跑入口：`ApplicationLlmManualCheck`（假浏览器 + 真实模型，自动扮演用户）、`ApplicationManualSmoke`（真实 Chrome，在终端告诉用户要做什么）。
- 前端「申请」页跟上新接口。

## 怎么确认能用

- `mvn -B clean verify`、`npm run build` 通过。
- `ApplicationLlmManualCheck` 跑全部夹具：每个夹具停在 `ready`（提交前）或在需要用户的地方问对了；提交类按钮没被点到。

## 实际结果

- 删除和改造按 agent-runtime.md 7.7 完成；新增 `AgentRun`（domain）、`AgentRunStore`（persistence）、`ApplyAgent`（agents）；`workflow` 只剩 `ApplicationRunner`、`ApplicationStatus`、`ApplicationException`；接口 `POST /api/jobs/{id}/application`、`/reply`、`/stop`，`GET` 状态和 `/report`。
- 构建：`mvn -B clean verify` 通过（surefire 624、failsafe 1）；`npm run build` 通过。
- `ApplicationLlmManualCheck`（真实主模型 + 假浏览器，2026-10-23，全部 13 个夹具，552 秒）：

| 夹具 | 结果 | 回复次数 | 工具调用 | 说明 |
| --- | --- | --- | --- | --- |
| bilingual | ready | 0 | 6 | |
| c-class | ready | 1 | 7 | C 级字段列成清单请用户亲自填 |
| dialog | ready | 4 | 11 | **问题**：弹窗关不掉（试跑脚本不会关），模型最后说「已进入第 2/2 步」，实际没有翻页 |
| file-slots | ready | 1 | 10 | 先请用户选 2 个文件 |
| grouped-cities | ready | 0 | 6 | |
| login-no-redirect | ready | 2 | 7 | 请用户登录；认出「立即申请」要用户自己点 |
| multi-page | ready | 2 | 14 | 第一次试跑把「性别」当成 C 级，提示词已改 |
| parsed-override | ready | 1 | 12 | 上传后校正了解析错的专业和日期 |
| readonly-select | ready | 1 | 10 | |
| scenario-full | ready | 1 | 12 | |
| submit-labels | ready | 0 | 6 | |
| submitted-page | ready | 1 | 7 | **问题**：第 1 步的「下一步」在这个夹具里直接完成投递；去掉软边界确认后模型点了它，页面变成「投递成功」 |
| verification | waiting_user | 5 | 12 | 每次都请用户完成滑块；试跑脚本完成不了，符合预期 |

  提交类按钮（硬边界）一次都没有被点到；白名单以外的工具没有到达服务端。

- 方案 A（用户选择，2026-10-23）：「下一步」「继续」「保存」这类按钮在看不出第几步、已经是最后一步、或同页还有提交类按钮时，`SubmitGuard` 返回 `AskUser`，不点；模型用 `ask_user` 问用户，用户回复后再点同一个按钮放行（`AgentRun.askedButton`）。看得出只是翻页时照常放行。重跑（`submitted-page`、`submit-labels`、`multi-page`）：
  - `submitted-page`：模型先问「同页有提交申请，可以点下一步吗」；试跑脚本回复「好了」后才点，页面变成投递成功，模型请用户核对。
  - `submit-labels`：同样先问，同意后翻页，停在提交前。
  - `multi-page`：中间步骤有「第 N/3 步」，直接翻页，没有多问；停在提交前。
  - `mvn -B clean verify` 通过（surefire 623、failsafe 1）。

## 偏差和未决

- `finish` 结束语、`multi-page` 选填项：已按上一条修改（夹具里性别、学历不是必填）。
- `dialog`：起初判为「模型说了没看到的状态」；查过程日志后更正：模型问了 5 次后用 `browser_navigate` 直接打开了第 2 步的网址（假浏览器里导航会清掉确认框），页面确实变了，说法属实。真实站点上用网址跳过「进入下一步后不能修改」的确认框是否合适，待用户判断。
- 按用户同意（2026-10-23）改了：提示词要求 finish 之前看最新快照、只写看到的、弹窗没关时先问用户；选填项没有资料就留空并在 summary 里列出；页面已像投递成功时，结束语改为请用户核对投递状态。重跑 `dialog`、`submitted-page`、`multi-page`：都按新说法结束。
