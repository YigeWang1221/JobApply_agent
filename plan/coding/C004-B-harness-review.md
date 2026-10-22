# C004-B · Harness 循环、skill、申请线程、关卡 B

- 对应 Master：[workflow.md](../master/workflow.md)「里程碑」第 3 段；「主流程」（用户登录、身份核对、上传与解析、逐页填写、集中问答、接力、保存并回读、关卡 B）「提交保护」「登录状态」「预算与停止条件」「流程优化的安全边界」「遗漏检查」第 5、6 项；[memory.md](../master/memory.md)「字段分级」「继承与解析」「Job 记录」；[ui.md](../master/ui.md)「隐私说明」
- 总表：[C004](C004-harness-guard.md)（拆分方式、共用的事实、已定决定、回写 Master 清单）
- 前置：[C004-A](C004-A-model-guard.md) 完成（模型、资料原件、MCP 参数、守卫）。
- 状态：**已确认**（用户确认，2026-10-22：「待确认」1–5 按建议；第 6 项在冒烟前由用户决定）。在 C004-A 完成后施工。

## 目标

后端对一个已经转为申请的 Job 跑**一条申请线程**（thread id = job id）：登录接力 → 模型核对登录与身份 → 填写循环 → 关卡 B，停在提交前，写出填写报告和过程日志。可观察行为：

1. **登录接力（沿用 C003，判断改由模型做）**：用户发 `logged_in` → `LoginCheckAgent` 读快照（只能调用 `browser_snapshot`、`browser_find` 和 `report_login`，最多 5 次），给出 `logged_in | not_logged_in | unsure`、`identity: matched | mismatch | unsure` 和依据（要看页头：头像、名字、「我的投递」「退出登录」）。`LoginPageDetector` 的结果只作提示。未登录 → `wait_login`，原因是模型给的依据；拿不准 → `wait_identity`；通过 → 写 `sites.md`，进入填写。用户可以带 `override`（决定 4），沿用 `login_override` 审计事件。
2. **填写循环（Model → Tool → Model）**：模型读最新快照、Project 的有效资料（A / B 级，**C 级不发**）、skill 目录、本次答案、报告草稿，自己决定下一步。每次调用先过 C004-A 的白名单和守卫，再发给 MCP；写操作之后，系统自己取一次快照，交给守卫和模型。旧快照在对话里替换成「（旧快照已省略）」。
3. **模型通过控制工具结束一轮**：
   - `ask_user`：把未知的必填项合并成一张清单（保留原文和选项）→ `wait_input`，`prep_state: needs_input`。
   - `request_files`：页面上有上传位置（简历、作品集、成绩单等）→ `wait_materials`。系统列出 `assets.md` 里的原件，用户在申请面板里选择，也可以当场拖入新文件（用 C004-A 的上传接口）→ 写进 `job.md` 的 `materials` → 复制成 `storage/uploads/<job-id>/` 下的等字节副本，并核对 SHA-256 → 继续。模型只看到文件编号和文件名。
   - `hand_to_user`：要用户亲自操作（C 级字段、注册、硬边界按钮、模型自己卡住）→ `wait_handoff`，`prep_state: handoff_user`。用户交回后，系统先只读取一次快照，并告诉模型「用户改过页面，不要覆盖用户填的内容」。
   - `load_skill`：读一个 skill 的正文，不结束这一轮。
   - `finish_filling`：能填的都填完了，并且已经保存、回读过 → 关卡 B。
4. **系统强制的停止**（模型绕不过去）：
   - 守卫返回 `Handoff` → `wait_handoff`。
   - 守卫返回 `NeedsConfirm`（软边界）→ `wait_confirm`，用户回答 `button_safe` / `button_unsafe`，结果记进 `sites/<host>.md`。
   - 守卫返回 `Reject` → 告诉模型「不要重试」；同一轮里再试同一类操作 → `wait_handoff`。
   - 预算软上限 → `wait_budget`，用户发 `continue` 后再给一轮同样的额度；打转检测命中 → `wait_handoff`。
   - 浏览器断开 → `wait_reconnect` → 重连后新开岗位页 → 交给用户确认登录 → 回到填写，**先只读核对，不重放任何写操作**。
5. **关卡 B**：系统最后取一次只读快照，把报告草稿定稿为 `fill-report.md`，`prep_state: awaiting_review`，中断为 `wait_review`，标签页保持打开。用户可以发 `request_changes`（附说明）→ `prep_state` 回到 `preparing`，回到填写。**图里没有提交节点**；提交之后的只读核验不属于本段。
6. **过程日志**：每一步都追加到 `jobs/<job-id>/run-log.jsonl`（决定 6），供以后的 skill 修补候选和定期整理使用。
7. **申请面板**：前端加「申请」页，可以开始一条申请线程，查看状态，按当前的等待点显示对应的操作（发信号、回答问题、选择或拖入文件、确认按钮、继续预算、查看报告、要求修改）。在对话视图（第 7 段）出来之前，用它代替 curl。
8. **应用重启后恢复**：任何等待点都能在重启后查到并继续（沿用 C003 的 sqlite Checkpoint）。

## 不做

- 岗位核实（读 JD、核对岗位 ID、额度提示）：第 4 段。
- 关卡 A、Cover Letter：第 5 段。本段的 `materials` 在 `wait_materials` 里由用户选择。
- 提交后的只读核验、`user_submitting` 及之后的状态、`applied`：后续的 Coding Plan。
- skill 修补候选的流程（模型提出、用户确认后写入）：决定 6，另写一份 Coding Plan。本段只写过程日志，以及用户回答软边界时往 `sites/<host>.md` 里追加的记录。
- 对话视图、子任务、对话线程（thread id = session id）。
- 关闭自己新开的标签页；MCP 崩溃时更快判断断开。

## skill 体系（决定 6）

```text
storage/playbook/
├── skills/
│   ├── apply-form/SKILL.md          # 通用：读快照、多页表单、批量填写、保存并回读、按钮用途怎么判断（软边界的依据）
│   ├── components/                  # 按 UI 组件划分，哪个公司的官网都能用
│   │   ├── cascader-city/SKILL.md   #   分组城市、级联选择
│   │   ├── date-picker/SKILL.md
│   │   ├── readonly-select/SKILL.md
│   │   ├── file-upload/SKILL.md     #   上传按钮 → file chooser → file_upload，等解析
│   │   └── resume-parse/SKILL.md    #   上传后解析覆盖了字段，怎么校正
│   └── platforms/                   # 按招聘系统划分；本段不预置，等观察到再加
└── sites/<host>.md                  # 不是 skill：平台猜测、用户确认过的软边界按钮、观察记录
```

- **SKILL.md 的格式**：兼容 agentskills（research-hermes.md §5）。frontmatter 有 `name`、`description`、`applies_to`（可选：`url_patterns`，以及页面特征，例如某个组件库的 class 前缀）、`version`、`observed_count`；正文是陈述句的经验，不写命令句，每个文件不超过 8,000 个字符。
- **加载**：上下文里只放所有 skill 的名字和一句描述；`apply-form` 一直带上；`platforms/*` 的 `url_patterns` 命中时自动带上；其它的由模型调用 `load_skill` 读取（渐进加载）。
- **预置**：仓库里放一份初始的 skill（`backend/src/main/resources/playbook/`），第一次使用时，如果 `storage/playbook/skills/` 里没有，就复制过去；已经有的不覆盖。初始内容由开发代理根据旧工作区复盘（research-codex.md，已抽象）写成，**不含任何个人信息或具体公司的投递细节**。
- **`sites/<host>.md`**：frontmatter 有 `host`、`platform`（猜测）、`confirmed_buttons`（`{name, verdict: safe|unsafe, at, session}`）；正文是观察记录。系统只**追加**确认记录，不删减；一条 `safe` 记录只能放行软边界按钮。
- **谁能改什么**：代码和词表只能由开发代理改；skill 正文在本段只能由开发代理改（以后由候选流程改，需要用户确认）；`sites/<host>.md` 由系统按用户的回答追加。

## 要改的文件

类名施工时可以微调，职责不变。**不新增子包**。

**`jobapply.harness.runtime`**
- `HarnessLoop`：Model → Tool → Model。每一步：`FillContext` 组装消息 → 主模型的 `ChatModel.call`（来自 C004-A 的 `ModelRegistry`）→ 逐个取出工具调用 → 控制工具在本地处理；浏览器工具先过 `BrowserToolPolicy` 和 `SubmitGuard` / `FieldClassGuard`，再由 `BrowserSession.act` 执行 → 结果作为 `ToolResponseMessage` 放回对话。遇到控制工具或系统强制的停止时，返回 `LoopOutcome`。
- `LoopOutcome`：`NeedsInput`、`NeedsFiles`、`Handoff`、`ConfirmButton`、`BudgetReached`、`Finished`、`Disconnected`。
- `Budget`：每页 120 次、整个申请 600 次工具调用，300 万 token，到 80% 时提醒一次（workflow.md「预算与停止条件」）。「一页」指 URL 加标题变了。用量只存数字，进 Checkpoint。
- `LoopDetector`：同一个元素连续失败 5 次；相同操作得到相同结果连续 4 次；页面连续 10 次操作没有变化。

**`jobapply.harness.context`**
- `FillContext`：系统提示词（规则、不能提交、被拒绝时不要重试、软边界按钮要给出用途和依据）、有效资料（过滤掉 C 级）、skill 目录和已加载的正文、本次答案、报告草稿、最新快照。
- `SkillCatalog`：读 `storage/playbook/skills/**/SKILL.md` 的 frontmatter，按 `url_patterns` 选出要自动带上的 skill；`load_skill` 时读正文（超过预算就截断并注明）。

**`jobapply.agents`**
- `FillAgent`：填写角色的提示词和工具清单。
- `LoginCheckAgent`：登录与身份核对的提示词和只读工具清单。

**`jobapply.tools`**
- `BrowserTools`：交给模型的工具定义（Spring AI `ToolDefinition`，`call()` 一律抛异常）和参数翻译。`fill_form` / `type` / `select_option` 的每个字段多两个参数：`fact`（资料键、答案编号或 `none`）和 `confidence`（high / medium / low）；`click` 多 `purpose`、`final_step_suspected`、`evidence`；`file_upload` 只收文件编号。这些多出来的参数由系统记进报告和日志，去掉以后再发给 MCP。
- `ControlTools`：`ask_user`、`request_files`、`hand_to_user`、`load_skill`、`finish_filling`、`report_login` 的定义。
- `BrowserSession`：新增 `act(jobId, tool, args)`：先确认标签页属于这个 Job（沿用 `ownsTab`），发出写操作，再取一次快照返回。
- `LoginPageDetector`：保留，只作提示。

**`jobapply.workflow`**（决定 1：改名并扩展）
- `graphs`：`LoginHandoffGraph` → `ApplicationGraph`，`LoginHandoffRunner` → `ApplicationRunner`，`LoginHandoffException` → `ApplicationException`。
- `nodes`：`CheckPageNode` 改为调用 `LoginCheckAgent`；新增 `FillNode`（跑一次 `HarnessLoop`，把 `LoopOutcome` 翻译成下一步和 `prep_state`）、`StageFilesNode`（处理 `wait_materials` 的答复）、`ReviewNode`（报告定稿），以及等待点 `wait_input`、`wait_materials`、`wait_handoff`、`wait_confirm`、`wait_budget`、`wait_review`。去掉 C003 的 `done`（停在 `wait_review` 就表示到了关卡 B）。
- `state`：`LoginHandoffState` → `ApplicationState`（在 C003 的键上加预算计数、`pending_button`（名字、用途、依据）、`questions`（只放问题原文和选项）、`file_slots`、`handoff_reason`、`review_round`）；`LoginHandoffSignal` → `ApplicationSignal`；`LoginHandoffStatus` → `ApplicationStatus`。

**`jobapply.persistence`**
- `UploadArea`：按 `materials` 把原件复制成 `storage/uploads/<job-id>/` 下的等字节副本，核对 SHA-256；线程到达关卡 B 或被丢弃时清空这个目录。
- `JobMaterials`：把用户选的原件写进 `job.md` 的 `materials`（引用 `assets.md` 的键，带 `upload_name`、`sha256`）。
- `JobAnswers`：把 `answers` 写进 `job.md` 正文的「本次答案」一节（带 session 和时间；C 级被拒绝），作为 `ProfileResolver` 的 Session 层。
- `FillReportWriter`：写 `fill-report.md` 的草稿和定稿（格式见「关键规则 · 填写报告」）。
- `RunLog`：追加 `run-log.jsonl`（格式见「关键规则 · 过程日志」）。
- `SiteNotes`：读写 `playbook/sites/<host>.md`，只追加确认记录。
- `PlaybookSeeder`：第一次使用时复制初始 skill。
- `StorageLayout`：加 `playbookSkills()`、`playbookSite(host)`、`fillReport(...)`、`runLog(...)`、`uploadsDir(jobId)`。

**`jobapply.config`**：`HarnessProperties`（预算与打转检测的数字）；`LoginHandoffConfiguration` → `ApplicationConfiguration`。

**`jobapply.api`**：`LoginHandoffController` → `ApplicationController`，接口见「关键规则 · HTTP」；新增 `GET .../report`，返回报告的 Markdown 文本。

**`frontend/`**：`src/pages/Application.tsx`（申请面板，每 2 秒轮询一次状态）。

**测试**：见「测试」。夹具放在 `backend/src/test/resources/fixtures/`。

## 关键规则

### 图

```text
START → open_page → [wait_login] → check_page（LoginCheckAgent）─┬→ [wait_login]（未登录）
                                                                 ├→ [wait_identity]（拿不准）─┬→ finish
                                                                 │                            └→ [wait_login]（账号不对）
                                                                 └→ finish（写 sites.md）
finish → fill ⟲ ─┬→ [wait_input]     ─ answers                  → fill
                 ├→ [wait_materials] ─ files                    → stage_files → fill
                 ├→ [wait_handoff]   ─ handed_back              → fill（先只读核对）
                 ├→ [wait_confirm]   ─ button_safe / button_unsafe → fill
                 ├→ [wait_budget]    ─ continue                 → fill
                 └→ review（报告定稿）→ [wait_review] ─ request_changes → fill
任意浏览器节点 ─(断开)→ [wait_reconnect] → reconnect → open_page → [wait_login] → … → fill（恢复）
```

- 方括号是 `interruptBefore` 的等待点，恢复方式沿用 C003（先 `updateState` 写入信号，再 `resume`）。
- `fill` 是**一个节点**，站点差异全在循环里，图上没有站点分支。
- `recursionLimit` 从 50 调到 200。

### 填写报告（`fill-report.md`）

```markdown
# 填写报告 · <公司> · <岗位>（第 N 轮，<时间>）

## 字段
| 页面 | 字段 | 值 | 来源层 | 置信度 | 分级 |
| --- | --- | --- | --- | --- | --- |
| 基本信息 | 姓名 | <值> | 来自 base（pin live） | high | **B** |
| 教育经历 | 专业 | <值> | 来自 Project <id> / overrides，<日期> 确认 | high | A |
| 基本信息 | 身份证号 | （用户已填） | 用户亲填 | — | C |

## 附件
| 文件 | 原件键 | 大小 | SHA-256 | 页面回读 |

## 未解决
- <问题原文与原因>

## 守卫记录
- 被拒绝的点击、要确认的按钮、用户的回答（只记按钮名字）
```

- 来源层：按模型给的 `fact` 键，去 `ProfileResolver` 的结果里取 `ResolvedFact.label()`；`fact: none` 记为「模型推断」。填进去的值和资料原值不一致时（例如格式改写），两者都列出来。
- B 级字段加粗；C 级字段只写「用户已填」，**系统不读取、不记录它的值**。
- 填写过程中报告以草稿的形式写出，头部标「草稿」；中断后恢复时交给模型，作为「已经填过什么」的依据。

### 过程日志（`run-log.jsonl`）

每行一条：`{ts, session, step, kind, tool, ref, element, decision, purpose, evidence, page_title, url_path, outcome, note}`。

- `kind`：`model_call`（只记 token 用量）、`tool_call`、`guard`、`wait`、`user_signal`、`budget`、`loop_detected`、`skill_loaded`。
- `element` 只记角色和名字；`url_path` 不含查询参数；`user_signal` 记信号名，`request_changes` 时记用户写的说明。
- **不记填进去的值，不记快照全文，不记模型对话原文。** 这样用户可以放心把日志发给开发代理做定期整理（决定 6）。用户写的说明里如果有个人信息，是用户自己决定的，页面上会提示这一点。

### 隐私

- 快照全文、模型对话、填进去的值：不写进 Checkpoint、日志和 `run-log.jsonl`。Checkpoint 只放状态、计数、问题原文和选项、按钮名字、文件编号。
- 填进去的值只写进 `fill-report.md` 和 `job.md`「本次答案」（都在 `storage/` 下，不进主仓库的 Git）。
- 发给模型的资料过滤掉 C 级（ui.md「隐私说明」）。

### HTTP（只监听 127.0.0.1；`Origin` 必须在允许名单里）

| 方法 | 路径 | 请求体 | 返回 |
| --- | --- | --- | --- |
| `POST` | `/api/jobs/{jobId}/application` | `{"project","session","accountHint"?}` | 202 + 状态 |
| `GET` | `/api/jobs/{jobId}/application` | — | 200 + 状态 |
| `POST` | `/api/jobs/{jobId}/application/signal` | `{"session","signal","override"?,"answers"?,"files"?,"note"?}` | 202 + 状态；信号和当前等待的内容不符时返回 409 |
| `GET` | `/api/jobs/{jobId}/application/report` | — | 报告的 Markdown；还没有报告时返回 404 |

- 状态：在 C003 的字段上加 `waitingFor: login | identity | reconnect | input | materials | handoff | confirm | budget | review`，以及 `questions`、`fileSlots` 和可选的原件、`button`（名字、用途、依据）、`usage`、`report`。
- 每个等待点能接受的信号：`wait_login`：`logged_in`（可以带 `override`）；`wait_identity`：`identity_confirmed` / `wrong_account`；`wait_reconnect`：`reconnected`；`wait_input`：`answers`；`wait_materials`：`files`（原件键的列表，每个对应一个上传位置）；`wait_handoff`：`handed_back`；`wait_confirm`：`button_safe` / `button_unsafe`；`wait_budget`：`continue`；`wait_review`：`request_changes`。
- `answers` 里不能回答 C 级的问题（返回 400，那一项只能由用户在浏览器里亲自填）。

## 实现步骤（7 个垂直切片，每片结束时 `mvn -B clean verify` 全绿）

1. **改名**：`LoginHandoff*` → `Application*`，接口路径改为 `/application`，行为不变，C003 的测试全部改名后照样通过。
2. **写操作与文件**：`BrowserSession.act`、`BrowserTools`、`UploadArea`、`JobMaterials`；假 MCP 服务端支持新工具和「按页面脚本变化」的夹具。
3. **skill**：`SkillCatalog`、`PlaybookSeeder`、初始 skill 的内容、`SiteNotes`。
4. **Harness 循环**：`HarnessLoop`、`Budget`、`LoopDetector`、`FillContext`、`ControlTools`、`FillAgent`，以及脚本化的假模型。先跑通「多页表单填完 → `finish_filling`」，再加上拒绝、软边界确认、打转、预算。
5. **图**：`FillNode`、`StageFilesNode`、`ReviewNode`、新的等待点和信号，接上 `JobLedger`（`needs_input`、`handoff_user`、`awaiting_review` 的跳转，C002 的状态机都已允许）；登录核对改用 `LoginCheckAgent`。
6. **报告、答案、日志**：`FillReportWriter`、`JobAnswers`、`RunLog`。
7. **HTTP、申请面板、场景测试**：`ApplicationController`、`Application.tsx`、从 HTTP 走到底的场景测试。

## 测试

`mvn -B clean verify` 里的自动测试**不访问外部网络、不连接真实浏览器、不调用 npx、不调用真实模型**。MCP 一律是 `FakePlaywrightMcpServer`，模型一律是脚本化的 `ScriptedChatModel`（按顺序返回事先写好的工具调用，并记录收到的消息，供测试断言）。

**夹具**（决定 3）：每个夹具有两份，表达同一个表单。
- `forms/<name>.html`：真实的 HTML 页面，给手动冒烟用（由冒烟类在 127.0.0.1 上提供）。
- `forms/<name>.scenario.json`：给假 MCP 服务端用，写明每一页的快照 YAML，以及点击某个 ref、填写某个字段之后变成哪一页、哪些字段的值变成了什么。

| 夹具 | 固定的行为 |
| --- | --- |
| `multi-page` | 三页表单，每页一个「下一步」，最后一页是「提交申请」：填完前两页，停在提交前，`finish_filling` |
| `submit-labels` | 「立即申请」「确认意向并投递」「预览并提交」「保存并提交」「Submit」「Apply」「Send」「Finish」「Confirm」被硬边界拒绝；「下一步」在模型给出 `unsure` 或「像是最后一步」时要确认 |
| `parsed-override` | 上传简历后，页面把专业和日期解析错了：模型按资料改正，报告里记下改正前后的值 |
| `readonly-select` | 只读下拉框：用 `select_option` 或点选项，不能 `type` |
| `grouped-cities` | 按省份分组的城市多选：只选资料里的意向城市 |
| `file-slots` | 简历和作品集两个上传位置：进入 `wait_materials`，选择后上传的是 `uploads/` 里的副本 |
| `verification` | 点「下一步」后弹出「请完成安全验证」：立即停止写操作，转接力，不重试 |
| `dialog` | 网页弹出确认框：转接力，不调用 `handle_dialog` |
| `c-class` | 身份证号、工作授权、期望薪资、声明勾选框：都不填，转接力，报告里记「用户亲填」 |
| `login-no-redirect` | 登录后 URL 不变，只是页头多出用户名和「退出登录」：模型判断为已登录 |
| `submitted-page` | 某次点击后出现「投递成功」：立即停止，转接力，标为「可能已提交」 |

| 测试类 | 固定的行为 |
| --- | --- |
| `SkillCatalogTest`、`PlaybookSeederTest`、`SiteNotesTest` | frontmatter 解析；按 `url_patterns` 自动带上；正文超过预算时截断；预置不覆盖已有的文件；确认记录只追加；`safe` 记录不能放行硬边界按钮 |
| `HarnessLoopTest` | 用假模型加假服务端跑各夹具；预算软上限、80% 提醒、三种打转检测；对话里只保留最新一份快照；**发给模型的内容里没有 C 级资料**；被拒绝的调用没有到达服务端 |
| `ApplicationRunnerTest` | 每个等待点的进入和恢复；`prep_state` 的跳转；断开 → 重连 → 先只读核对，**不重放写操作**（用假服务端的调用记录断言）；重启恢复；信号不符时返回 409 且 Checkpoint 不变；**Checkpoint 里没有快照文字和填进去的值**（直接读 sqlite 断言）；C003 原有的登录接力场景全部保留 |
| `FillReportWriterTest`、`JobAnswersTest`、`JobMaterialsTest`、`UploadAreaTest`、`RunLogTest` | 格式；追加而不是覆盖；SHA-256 不符时报错；C 级答案被拒绝；**日志里没有填进去的值**（用夹具里的假资料逐条断言） |
| `ApplicationControllerTest` | 新接口的 202 / 200 / 404 / 409 / 400 / 403 / 415 |
| `ApplicationScenarioIT` | 启动 Spring 应用，通过 HTTP 走完「开始 → 登录 → 填写 → 集中问答 → 选择文件 → 软边界确认 → 关卡 B → 要求修改 → 再到关卡 B」，最后检查 `job.md`、`events.jsonl`、`fill-report.md`、`run-log.jsonl`、`sites/<host>.md`、台账 |

另外用一个总的断言固定：所有夹具里提交类的按钮，在整个测试套件里**一次都没有被点到**（假服务端的调用记录里没有）。

**前端**：`npm run build` 通过。

## 真实 LLM 测试与冒烟（在用户监控下进行；默认不运行）

1. **真实模型 + 假浏览器**（代理可以运行，`dev-limit.md`「真实 LLM 测试」）：`ApplicationLlmManualCheck` 用用户配置的主模型，在假 MCP 服务端的各个夹具上跑一遍。确认：真实模型给出的工具调用能被正确执行；守卫一次都没有被绕过；预算和打转检测正常；报告的来源层正确。只打印每个夹具的结果和用量。
2. **真实模型 + 真实 Chrome + 本地 HTML 夹具**：冒烟类在 127.0.0.1 上提供 `forms/*.html`，连接用户的 Chrome，跑 `multi-page`、`submit-labels`、`verification`、`file-slots`。确认真实快照能被 `SnapshotIndex` 解析，守卫在真实页面上同样生效，`storage/cache/playwright-mcp/` 里没有残留。
3. **真实公司**（决定 3）：用户选一家「就算真投了也无所谓」的公司，由用户登录，系统跑到关卡 B 停下；用户看报告和页面，自己决定提不提交。用哪个 Project 的资料由用户决定。
4. 结果写进「实际结果」；没做的步骤在 `dev-log.md` 写「未运行」。

## 验收标准

- `mvn -B clean verify` 全绿（C004-A 完成后的测试数，加上本段新增的）。
- 自动测试过程中没有访问外部网络，没有调用 npx，没有调用真实模型。
- 白名单以外的调用、被守卫拒绝的调用都没有到达 MCP 服务端；夹具里提交类的按钮一次都没有被点到。
- Checkpoint、日志、`run-log.jsonl` 里没有快照文字和填进去的值；发给模型的内容里没有 C 级资料。
- 用户完成真实 LLM 测试和冒烟的第 1、2 步，第 3 步由用户决定做不做。

## 待确认（用户确认，2026-10-22：全部按建议）

1. **申请面板**：在对话视图出来之前，前端加一个「申请」页代替 curl，用来发信号、回答问题、选择或拖入文件、查看报告。**建议：同意。**
2. **初始 skill**：`apply-form` 和 5 个组件 skill 由开发代理根据 research-codex.md（已抽象）写成，预置在仓库里；`platforms/` 先不放，等观察到再加。**建议：同意。**
3. **问上传文件的时机**：模型遇到上传位置时再问（`request_files`），不在开始时就问。**建议：同意。**
4. **`uploads/<job-id>/` 的清理**：到达关卡 B 后清空。但用户在关卡 B 要求修改、需要重新上传时，会再复制一次。**建议：同意。**
5. **过程日志里记 `request_changes` 的说明原文**：这是用户自己写的反馈，对 skill 整理最有用；但如果里面写了个人信息，日志就不再是完全干净的。**建议：记，并在面板上提示。**
6. **真实公司冒烟用哪个 Project 的资料**：由用户在冒烟前决定。
