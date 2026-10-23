# C004-B · Harness 循环、skill、申请线程、关卡 B

- 对应 Master：[workflow.md](../master/workflow.md)「里程碑」第 3 段；「主流程」（用户登录、身份核对、上传与解析、逐页填写、集中问答、接力、保存并回读、关卡 B）「提交保护」「登录状态」「预算与停止条件」「流程优化的安全边界」「遗漏检查」第 5、6 项；[memory.md](../master/memory.md)「字段分级」「继承与解析」「Job 记录」；[ui.md](../master/ui.md)「隐私说明」
- 总表：[C004](C004-harness-guard.md)（拆分方式、共用的事实、已定决定、回写 Master 清单）
- 前置：[C004-A](C004-A-model-guard.md) 完成（模型、资料原件、MCP 参数、守卫）；[C004-A-1](C004-A-1-profile-import.md)、[C004-A-2](C004-A-2-profile-review.md) 完成（资料导入与画像，用户确认 2026-10-22 放在本段之前）。C004-A-1 把事实改成中英双语、给 `ProfileResolver` 加按语言取值，C004-A-2 把事实层写满；本段的来源层和填写报告要按这两段的结果对齐，施工前先核对一遍（已核对，2026-10-23，见「施工前核对」）。
- 状态：**施工完成，等真实 LLM 测试、冒烟和手动检查**（2026-10-23）。「待确认」1–5 按建议（2026-10-22）；施工前核对的 9 项和可选项按建议（2026-10-23）；第 6 项等用户把真实 Project 的流程走完后再定。

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
- 给「填表」单独分配模型（ui.md「隐私说明」提到可以单独指定本地模型）：本段只用 `main`，以后再加用途。
- 把画像 `base/profile.md` 和项目卡全文发给模型：项目卡的字段已经通过 `projects.<slug>.*` 进了有效资料，开放式的长问题走 `ask_user`。
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
- `FillContext`：系统提示词（规则、不能提交、被拒绝时不要重试、软边界按钮要给出用途和依据）、有效资料（按「关键规则 · 资料与语言」选出和取值）、skill 目录和已加载的正文、本次答案、报告草稿、最新快照。
- `SkillCatalog`：读 `storage/playbook/skills/**/SKILL.md` 的 frontmatter，按 `url_patterns` 选出要自动带上的 skill；`load_skill` 时读正文（超过预算就截断并注明）。

**`jobapply.agents`**
- `FillAgent`：填写角色的提示词和工具清单。
- `LoginCheckAgent`：登录与身份核对的提示词和只读工具清单。

**`jobapply.tools`**
- `BrowserTools`：交给模型的工具定义（Spring AI `ToolDefinition`，`call()` 一律抛异常）和参数翻译。`fill_form` / `type` / `select_option` 的每个字段多两个参数：`fact`（资料键、答案编号或 `none`）和 `confidence`（high / medium / low）；`click` 多 `purpose`、`final_step_suspected`、`evidence`；`file_upload` 只收文件编号。字段还可以带可选的 `language`（zh / en，指明用另一种语言的值）。这些多出来的参数由系统记进报告和日志；**先取出并去掉，再过 `BrowserToolPolicy` 和守卫**，最后发给 MCP（白名单不接受这些键）。字段参数的取出和去掉仿照 `SubmitGuard.ClickIntent`，写成对应的字段版本（`FieldIntent`，放在 `harness.policies`）。
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

**`jobapply.config`**：`HarnessProperties`（预算与打转检测的数字）；`LoginHandoffConfiguration` → `ApplicationConfiguration`；`ModelFactory` 加 `withTools(ChatModel, tools)`：仿照 `withSchema`，从模型自己的选项 `mutate()` 复制再挂工具，模型名不会被 SDK 默认值盖掉（`ModelRegistry.main()` 只给 `ChatModel`，拿不到 `Endpoint`）；Anthropic 格式把 `max_tokens` 调大（默认 4096 不够）。原来的 `withTools(Endpoint, …)` 留给 `ConnectionTester`。

**`jobapply.harness.policies`**：`FieldIntent`（见 `BrowserTools`）；`SnapshotRedactor`：按字段名遮掉快照里 C 级输入框的值（见「关键规则 · 隐私」）。

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

### 资料与语言（施工前核对补入，2026-10-23）

- **选哪些条目**：`ProfileResolver` 的结果里，只发两类：没有关联论断的条目；关联论断的用途是 `forms_and_resume`、状态不是 `discrepancy` 的条目。`interview_only`、`none`（包括 `needs_verification` 的默认用途）和 `discrepancy` 的条目不发、不填。资料层本来存不进 C 级（`Fact` 拒绝构造）。
- **语言**：系统按每一页快照里中文字符的比例判断页面语言（确定性规则，用测试固定）：可见文字里的中文字符（不计空白、标点、数字）**不少于 40% 为中文页面**，否则为英文页面（用户确认，2026-10-23），按这个语言调用 `resolve(project, answers, language)`。中英混排、判断错了时，模型在字段上带 `language` 指明用另一种语言的值；系统按那个语言再取一次。报告记下每个字段用的语言。
- **未审阅的译文**：可以填。报告里那一项的来源层带「未审阅」（`ResolvedFact.label()` 已经这样写），并在「未解决」里汇总一行「N 个字段用了未审阅的译文」，用户在关卡 B 看得到。

### 过程日志（`run-log.jsonl`）

每行一条：`{ts, session, step, kind, tool, ref, element, decision, purpose, evidence, page_title, url_path, outcome, note}`。

- `kind`：`model_call`（只记 token 用量）、`tool_call`、`guard`、`wait`、`user_signal`、`budget`、`loop_detected`、`skill_loaded`。
- `element` 只记角色和名字；`url_path` 不含查询参数；`user_signal` 记信号名，`request_changes` 时记用户写的说明。
- **不记填进去的值，不记快照全文，不记模型对话原文。** 这样用户可以放心把日志发给开发代理做定期整理（决定 6）。用户写的说明里如果有个人信息，是用户自己决定的，页面上会提示这一点。

### 隐私

- 快照全文、模型对话、填进去的值：不写进 Checkpoint、日志和 `run-log.jsonl`。Checkpoint 只放状态、计数、问题原文和选项、按钮名字、文件编号。
- 填进去的值只写进 `fill-report.md` 和 `job.md`「本次答案」（都在 `storage/` 下，不进主仓库的 Git）。
- 发给模型的资料过滤掉 C 级（ui.md「隐私说明」）。
- **快照里的 C 级值**：用户接力时亲手填的 C 级字段，之后的快照里会带着值（例如 `textbox "身份证号" [ref=e5]: …`）。`SnapshotRedactor` 按 `FieldClassGuard` 的词表判断字段名，把这类输入框的值换成「[已省略]」；发给模型、写报告、交给 `LoopDetector` 比较之前都先过一遍。守卫仍然用原始快照判断（只在内存里，不落盘）。

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

## 施工前核对（2026-10-23，用户确认按建议）

对照 C004-A、A-1、A-2 写出的代码核对本计划，补了下面几项（已写进上面对应的小节）：

1. 快照里的 C 级值：加 `SnapshotRedactor`（「关键规则 · 隐私」）。
2. 填表的语言：系统按页面判断，模型可以用 `language` 指明另一种（「关键规则 · 资料与语言」）。
3. 未审阅的译文：可以填，报告标出并在「未解决」汇总。
4. 论断的用途和状态：`FillContext` 只发 `forms_and_resume` 且不是 `discrepancy` 的条目，以及没有关联论断的条目。
5. 夹具由本段写（C004 总表决定 3 原写「A 写夹具」，C004-A 实际没有写 `forms/*`，总表已改）。
6. `ModelFactory.withTools(ChatModel, tools)`：主模型拿不到 `Endpoint`。
7. `fact` / `confidence` / `language` 先去掉再过白名单（`FieldIntent`）。
8. 「验证码」词表连短信验证码输入框也拦：保持接力，`verification` 夹具固定。
9. 填表单独分配模型：本段不做，只用 `main`。
- 可选项：画像和项目卡全文不发给模型（「不做」）。

## 实现步骤（7 个垂直切片，每片结束时 `mvn -B clean verify` 全绿）

1. **改名**：`LoginHandoff*` → `Application*`，接口路径改为 `/application`，行为不变，C003 的测试全部改名后照样通过。
2. **写操作与文件**：`BrowserSession.act`、`BrowserTools`、`FieldIntent`、`SnapshotRedactor`、`ModelFactory.withTools(ChatModel, …)`、`UploadArea`、`JobMaterials`；夹具 `forms/*`；假 MCP 服务端支持新工具和「按页面脚本变化」的夹具。
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
| `verification` | 点「下一步」后弹出「请完成安全验证」：立即停止写操作，转接力，不重试；另一页表单上自带「短信验证码」输入框：同样转接力（验证码只能由用户收、用户填；沿用 C004-A「宁可多拦」，不细分词表） |
| `dialog` | 网页弹出确认框：转接力，不调用 `handle_dialog` |
| `c-class` | 身份证号、工作授权、期望薪资、声明勾选框：都不填，转接力，报告里记「用户亲填」；用户填完交回后，模型收到的快照、报告和日志里都没有用户填的值 |
| `bilingual` | 英文页面：按英文取值；其中一项只有模型起草的英文：照填，报告标「未审阅」并在「未解决」汇总；关联论断用途为 `interview_only` 和状态为 `discrepancy` 的条目没有发给模型 |
| `login-no-redirect` | 登录后 URL 不变，只是页头多出用户名和「退出登录」：模型判断为已登录 |
| `submitted-page` | 某次点击后出现「投递成功」：立即停止，转接力，标为「可能已提交」 |

| 测试类 | 固定的行为 |
| --- | --- |
| `SkillCatalogTest`、`PlaybookSeederTest`、`SiteNotesTest` | frontmatter 解析；按 `url_patterns` 自动带上；正文超过预算时截断；预置不覆盖已有的文件；确认记录只追加；`safe` 记录不能放行硬边界按钮 |
| `HarnessLoopTest` | 用假模型加假服务端跑各夹具；预算软上限、80% 提醒、三种打转检测；对话里只保留最新一份快照；**发给模型的内容里没有 C 级资料，也没有快照里的 C 级值**；页面语言判断；`fact` / `confidence` / `language` 在白名单检查前去掉、没有到达服务端；被拒绝的调用没有到达服务端 |
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


## 实际结果（2026-10-23）

施工方式：用户要求多代理。切片 1 由主代理做；切片 2、3、6 和前端面板由四个代理在各自的 worktree 里并行做，主代理合并到主工作区后统一验证；切片 4、5、7 依次在主工作区做，其余 9 个夹具和切片 4 并行。每次合并后主代理都重跑 `mvn -B clean verify`。

**按切片**
1. 改名：`LoginHandoff*` → `Application*`，接口 `/api/jobs/{jobId}/application`，旧路径删掉；`StorageLayout` 加 `uploadsDir(jobId)`、`playbookDir()`、`playbookSkills()`、`playbookSite(host)`（主机名校验，不能越出 `sites/`）、`fillReport(...)`、`runLog(...)`。surefire 594、failsafe 2，行为不变。
2. 写操作与文件：`BrowserSession.act`、`BrowserSession.find`、`tools.BrowserTools`（10 个白名单工具的定义和参数翻译；`file_upload` 只收文件编号）、`harness.policies.FieldIntent`、`harness.policies.SnapshotRedactor`、`ModelFactory.withTools(ChatModel, …)`（`TOOL_MAX_TOKENS` 16,000）、`persistence.UploadArea`、`persistence.JobMaterials`；测试侧 `FakeFormScript`（假 MCP 的表单模式，格式写在它的 javadoc 里）、`FakeBrowser.form/userEdit/calls/hardRefs`。
3. skill：`harness.context.SkillCatalog`、`persistence.PlaybookSeeder`、`persistence.SiteNotes`；预置 `apply-form` 和 5 个组件 skill（`backend/src/main/resources/playbook/skills/`，中文陈述句，0.9k–6.2k 字，无个人信息、无公司名；主代理逐条看过 `apply-form`）。
4. Harness 循环：`harness.runtime` 的 `HarnessLoop`、`LoopOutcome`、`LoopState`、`Budget`、`LoopDetector`；`harness.context` 的 `FillContext`、`PageLanguage`（40% 规则）；`agents` 的 `FillAgent`、`LoginCheckAgent`；`tools.ControlTools`；`config.HarnessProperties`（`application.yml` 的 `jobapply.harness.*`）；`ResolvedFact` 加 `claim`。测试辅助 `ScriptedChatModel`。
5. 图：`FillNode`、`StageFilesNode`、`ReviewNode`、`ResumeNode`（wait_input / handoff / confirm / budget / review 的信号）、`FillHarness`（用到时才取主模型）；重写 `ApplicationGraph`、`ApplicationRunner`、`ApplicationState` / `Signal` / `Status`、`CheckPageNode`（改用 `LoginCheckAgent`）、`FinishNode`；去掉 `done`；`recursionLimit` 200；`FillReportWriter.load/parse` 让报告行在重启后保留。
6. 报告、答案、日志：`persistence.FillReportWriter`、`JobAnswers`（答案编号 `ans-NNN`，C 级整批拒绝）、`RunLog`（只有工厂方法，没有记值的参数）。
7. HTTP 与场景：`ApplicationController`（202 / 200 / 404 / 409 / 400 / 403 / 415；`GET …/report` 返回 `text/markdown`）；前端「申请」页 `frontend/src/pages/Application.tsx`（每 2 秒轮询，按等待点显示操作，`request_changes` 旁提示说明原文会进日志）；新夹具 `scenario-full`；`ApplicationScenarioIT` 通过 HTTP 走完「开始 → 登录 → 填写 → 集中问答 → 选择文件 → 软边界确认 → 关卡 B → 要求修改 → 再到关卡 B」，最后检查 `job.md`、`events.jsonl`、`fill-report.md`、`run-log.jsonl`、`sites/careers.example.com.md`、台账和 `uploads/`；手动入口 `ApplicationLlmManualCheck`、扩展后的 `ApplicationManualSmoke`（`-Djobapply.smoke.form=<夹具>`，在 127.0.0.1 上提供 `forms/*.html`）。

**夹具**：`backend/src/test/resources/fixtures/forms/` 下 13 个（计划的 12 个加 `scenario-full`），每个有 `.html` 和 `.scenario.json`；`FakeFormScriptTest.everyFormFixtureIsConsistent` 自动检查全部夹具。所有硬边界按钮都通向「投递成功」页，误点能被发现。

**验证**
- 在 `backend/` 用 JDK 25 执行 `mvn -B clean verify`：退出码 0，surefire 715、failsafe 3，Failures / Errors / Skipped 均为 0（主代理最后一次运行）。
- 自动测试只用 `FakePlaywrightMcpServer`、`ScriptedChatModel` 和 `FakeModelServer`，没有访问外部网络、没有调用 npx、没有连真实浏览器、没有调用真实模型。
- 测试里的 4 个假 Key 在构建日志和 surefire / failsafe 报告里出现 0 次。
- 夹具里的提交类按钮一次都没有被点到：`HarnessLoopTest` 每个用例之后、`ApplicationScenarioIT` 结束时按假服务端的调用记录断言；没有 `handle_dialog` 调用。
- Checkpoint 里没有快照文字和填进去的值（`ApplicationRunnerTest` 用 JDBC 直接读 sqlite 断言）；C 级值（包括用户接力时在页面上填的）没有出现在模型请求、报告和日志里。
- `frontend/` 执行 `npm run build` 通过。
- 真实 LLM 测试（`ApplicationLlmManualCheck`）：未运行。真实 Chrome 冒烟（`ApplicationManualSmoke`）：未运行。真实公司冒烟：未运行（第 6 项未定）。用户在页面上的检查：未运行。

## 偏差

1. **包依赖**：`persistence` 新引用了 `memory.ResolvedFact`（`FillReportWriter`，形成包之间的循环）和 `harness.policies` 的词表（`JobAnswers`、`SiteNotes`）。能编译，没有架构测试；以前 `persistence` 只引用 `domain`。
2. **报告格式**：表格多一列「语言」；值那一格在格式改写时附「（资料原值：…）」，在改正页面解析结果时附「（页面原值：…，已改正）」；「草稿」写在标题的括号里。草稿不提交 storage git，定稿时连同 `run-log.jsonl` 一起提交。
3. **`materials` 的形状**：写成列表 `[{slot?, asset, upload_name, sha256}]`；memory.md「Job 记录」的示意是映射（`resume`、`attachments`）。**待用户确认后回写 Master。**
4. **计划外的类和方法**：`BrowserSession.find`（模型的 `browser_find` 要的是文字，不是快照）、`LoopState`、`ResumeNode`、`FillHarness`、`PageLanguage`、`FieldIntent`、`SnapshotRedactor`、`FillReportWriter.Recorded`、`SiteNotes.notePlatform/appendObservation`；`LoopOutcome.ConfirmButton` 多了 `host`；点击的 `purpose` 多了 `open_control`（软边界放行仍只认 `next_step` / `save_draft`）。
5. **`ResolvedFact` 加 `claim`**：按论断的用途和状态筛选需要它；台账里找不到的论断编号按「不能发」处理。指向被筛掉条目的 `fact` 在发给 MCP 之前就被拒绝（告诉模型原因，不算守卫拒绝）。
6. **「同一类操作再试」**：按「工具 + 拒绝来源」判断，同一轮里第二次被守卫拒绝的点击就转接力，即使 ref 不同。连续三次只回文字、不调用工具也转接力（`NO_PROGRESS`）。
7. **预算**：控制工具也计入调用次数；80% 提醒每轮额度一次，同时作为提示交给模型。
8. **报告草稿**：每一轮开始时从 `fill-report.md` 重新读入，重启后已填的行保留；对同一个 Job 重新 `start` 时也会沿用旧行，轮次从 1 开始。
9. **主模型没有配置**：登录核对时停在 `wait_login` 并给出「还没有配置主模型」；填写时线程记为失败，要重新开始。
10. **`override`**：不调用模型，按 C003 的规则自己取快照、比对账号提示，记 `login_override`。
11. **关卡 B 不占浏览器**：停在 `wait_review` 时释放标签页归属，免得一个停在关卡 B 的 Job 挡住别的 Job；之后 `request_changes` 会重新打开岗位页，走一遍登录核对。
12. **`request_changes` 的说明**：在下一轮填写用掉之前存在 Checkpoint 里（用户自己写的文字，不是快照或填进去的值）。
13. **skill**：`url_patterns` 的格式由施工定：去掉协议后整条 URL 的不区分大小写的通配符，只有 `*`；`page_markers` 只解析、不用于自动加载；`PlaybookSeeder` 用写死的文件清单（jar 里不能可靠地列目录，测试核对清单和资源目录一致）；用户删掉的预置 skill 下次会被补回；按钮记录按名字取最后一条，后来的 `unsafe` 撤销之前的 `safe`（两条都保留）。
14. **答案**：本次答案记为 A 级。模型推断（`fact: none`）的字段在报告里也记为 A 级。
15. **页面语言**：空页面按中文；`- /url:` 行和角色、属性不计入比例。
16. **场景测试**：主模型通过设置接口配置成 `FakeModelServer`，没有替换 Bean，这样也覆盖了「设置 → 模型注册表 → 工具调用」这条路径。修了一个测试辅助的问题：`FakeBrowser` 会把用过的 `exitOnNextCall` 写回成 true。
17. **「提交类按钮没被点到」**：按测试类断言（`HarnessLoopTest` 每个用例、IT 结束时、`ApplicationRunnerTest` 按 ref），不是整个测试套件一个总断言。


## 追加：浏览器连接属于应用（用户确认，2026-10-23）

冒烟时用户发现「每一步都要在 Chrome 里同意一次 MCP 连接」。用户的要求：MCP 连接一直挂着，不跟着 Session、Project 或 Job 走；只要用户打开了，除非用户自己在 Chrome 里关掉（那时重新申请一次），系统不自己关掉再重新申请。

查到的原因（按冒烟日志里每次调用的耗时）：
- 连接成功时本来就是一直挂着的：一次运行里只有第一次调用等了用户同意（约 28 秒），之后每次调用都是毫秒级。
- 每个夹具单独起一个冒烟应用（新的 JVM、新的 MCP 进程），所以每个夹具都要再同意一次：这是测试方法的问题，和真实使用不一致。
- **产品缺陷 1**：连接失败以后，`BrowserSession` 丢掉连接，下一次调用（包括下一个 Job）又自动去连，Chrome 就再弹一次。Playwright MCP 自己也会在每次工具调用时重试连接。
- **产品缺陷 2**：远程调试模式下连不上 Chrome（「Could not connect to chrome」、用户 30 秒内没同意时的「Timeout 30000ms exceeded」）没有被认成断开，线程直接记为失败，而不是进入 `wait_reconnect`。
- `reconnected` 不管连接好不好都关掉重开。

修改：
- `BrowserSession`：连接属于应用。只在「应用启动后第一次使用浏览器」和「用户发 `reconnected`」时建立连接；连接失败或断开以后不自动重试，之后的调用直接报断开、不碰 MCP，所有用到浏览器的 Job 都停在 `wait_reconnect`；`reconnected` 在连接还好时沿用、不关掉，失败或断开过才重新连接一次。只有应用关闭时才关掉连接。
- `PlaywrightMcpClient`：`could not connect to chrome`、`initializeServer` 算作断开。
- `ApplicationManualSmoke`：夹具可以用逗号分隔多个，一个应用、一条连接、每个夹具一个 Job 依次跑；每个 Job 停在关卡 B、转接力或失败时算结束；MCP 输出目录的残留在应用关闭以后再查（连接打开期间 MCP 会写 console 日志，关闭时清空）。
- 测试：新增 `BrowserSessionConnectionTest`（两个 Job 和一次 `reconnected` 只用一条连接；连接失败后不再碰 MCP，用户重连时才建第二条；等用户同意超时算断开）；`PlaywrightMcpClientTest.serverExitingMidCallIsADisconnect` 改成「断开后要用户重连」。
- 验证：`mvn -B clean verify` 退出码 0，surefire 718、failsafe 3。其间有一次 `ApplicationRunnerTest.unconfiguredMainModelIsShownAndTheUserCanRetryOrOverride` 在整套测试里失败（期望 waiting，实际 failed），单独跑和整类跑三次都通过，再跑整套也通过：记为偶发，断言里加了完整状态，下次出现时能看到原因。

## 追加：改成 Agent 驱动（2026-10-23；已由 [agent-runtime.md](../master/agent-runtime.md) 取代，代码没有落地）

用户指出：C004-B 写成了「LLM 驱动的定时任务脚本」：9 个固定等待点、单独的登录核对 Agent 和它的等待点、固定的打转规则、每个字段必填的 `fact` / `confidence` / `language`、系统按 40% 判断页面语言；而填写的提示词只有十几行，没有讲项目和经历条目、日期、下拉框这些情况怎么处理。按 `AGENTS.md`「Agent 开发原则」和 [research-agent-design.md](../reference/research-agent-design.md) 改：

**图**
```text
START → open_page → agent ⟲ ─┬→ [wait_user] ─ 用户答复 ─→ agent
                              ├→ review（报告定稿）→ [wait_review] ─ request_changes ─→ agent
                              └→ [wait_reconnect] ─ reconnected ─→ reconnect → open_page
```
- 去掉 `check_page`、`wait_login`、`wait_identity`、`finish`、`LoginCheckAgent`：要不要登录由模型看页面判断，需要时用 `ask_user` 请用户登录。
- `wait_input`、`wait_materials`、`wait_handoff`、`wait_confirm`、`wait_budget` 合成一个 `wait_user`；等待的内容由模型（或守卫、预算）给出的 `kind` 决定：`login`、`input`、`materials`、`handoff`、`confirm`、`budget`。状态里的 `waitingFor` 仍然用这几个值，前端不变；信号名不变（`logged_in`、`answers`、`files`、`handed_back`、`button_safe` / `button_unsafe`、`continue`）。

**交给模型的工具**
- 浏览器工具（不变）。字段上的 `fact`、`confidence`、`language` 改成可选：写了就进报告，没写记为「模型推断」。
- `ask_user`：一个工具，`kind` 是 login / input / materials / handoff，附说明、问题清单（保留原文和选项）或上传位置。
- `update_plan`：模型自己维护这一页的计划、已完成的、剩下的和遇到的问题（参考 Deep Agents 的 `write_todos`、Browser Use 的 todo）。每一轮放回上下文，不进 Checkpoint。
- `load_skill`、`finish_filling`（不变）。

**代码里留下的（安全边界）**：硬边界按钮、回车、C 级字段（不发不填，快照遮值）、工具白名单、上传路径、出现安全验证 / 弹窗 / 疑似已提交时停止写操作并交给用户、软边界按钮拿不准时问用户、预算上限（到上限时问用户要不要继续，75% 时提醒模型）。

**改成反馈给模型、不再强制转交的**：同一个元素连续失败、相同操作相同结果、页面一直没变化、被拒绝后又试：系统在工具结果里如实告诉模型「这已经是第 N 次」，由模型换办法或 `ask_user`。

**提示词**：按节组织：身份和目标 → 边界 → 工作方法（每一步先核对上一步、看页面、计划这一页、批量填、回读核对、翻页）→ 按情形的引导（登录、个人信息、教育、项目和经历条目、日期、下拉框和级联、上传和解析覆盖、开放问题和问用户、卡住时、完成时）。每节写明对应的 skill。资料两种语言都给出，模型按页面选。

**主模型失败**（用户确认，2026-10-23）：没有配置主模型 → 线程直接失败并给出原因；调用失败是网络原因（连接失败、超时、服务暂时不可用）→ 最多等 2 分钟反复重试，期间用户可以发 `stop` 中断；2 分钟后还不行 → 失败并给出原因。在对话视图出来之前，「停止」在申请面板上。

**多个 Job**：一条浏览器连接上每个 Job 一个标签页，操作前切到自己的那一页（见上一节「浏览器连接属于应用」）；一个 Job 停在等用户的地方时，别的 Job 照样可以用浏览器。

**验证方式**（用户确认，2026-10-23：不再新写测试）：用真实模型在夹具上跑（`ApplicationLlmManualCheck`）和真实 Chrome 冒烟；原有固定安全边界和存储格式的单元测试保留；固定旧流程的测试类移出构建（存档到会话目录，不删除内容）。
