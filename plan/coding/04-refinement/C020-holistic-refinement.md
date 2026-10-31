# C020 · 整体精炼：常识推理引导、控件诚实观测、Memory 闭环与工作台体验

- 对应 Master：[agent-runtime.md](../../master/agent-runtime.md) §7.2–7.4、[workflow.md](../../master/workflow.md) 主流程与故障恢复、[memory.md](../../master/memory.md)
- 依据：用户（2026-10-28）对整套系统的复盘：
  1. 提示词与 skill 里残留重复啰嗦或已作废的旧规则（如「确认过的按钮记进站点记录」「上传先请用户选文件」「选不中时没有字面一致就问用户」），且缺少「扁平资料 → 层级/大类客观归属由模型按常识补全」的清晰引导；
  2. 下拉/级联（`PageScripts.SELECT`）与日期（`PageScripts.DATE`）在虚拟滚动、选了一半还有下一列、多列滚轮式日期面板、纯箭头翻页日历上缺少真实鼠标滚轮与诚实状态返回；
  3. `remember` 写入的 `companies/<company>.md` 与 `review/lessons.md` 尚未回注到两个 Agent 的上下文，且 `C006-fix-real-run` 第 9 条（上传区清空后 `read_resume` 与二次上传失败）待按原件直读 + 按需复制收口；
  4. 对话 Agent 提示词已允许按用户要求看已开标签页，但工具集尚未开放 `browser_tabs`；
  5. 前端工作台（`JobPage.tsx`）的填写报告未随运行状态自动刷新且未用 Markdown 表格渲染，事件流 `reset` 重读状态与 `reply` 仅在等待选文件时带 `files`（`C006-fix-real-run` 第 17 条）待补齐。
- 分工：本计划拆为 **A、B、C、D、E 五个独立工作包**。A–E 代码已在分支 `C20_upgrade` 工作树并完成合龙验证。
- 状态：已施工，等用户在真实网站试（A–E 合龙验证通过：本机 JDK 25 下 `mvn -B clean verify` 退出码 0、`npm run build` 退出码 0、无头 Chromium 5 项新脚本检查通过，2026-10-28）。

---

## 不做

- 不新增安全守卫、确认断点或硬编码的站点分支。
- 不改变「系统永远不执行最终提交」与「C 级真实值不进模型上下文」的边界。
- 本步（Gemini 4）**不修改**工作包 B–E 涉及的 Java 运行时、脚本、存储与前端代码。

---

## 五个工作包拆分与规格

### 工作包 A（本步由 Gemini 4 执行）：系统提示词、Skills 正文设计与工具说明对齐

- **目标**：依靠 LLM 自身的常识推理能力，Prompt 和 Skill 只做精炼启发式引导，去掉重复啰嗦和已作废的旧规则，并把预置 skill 的 `version` 加 1，使 `PlaybookSeeder` 自动送到用户本机。
- **要改的文件**：
  - `backend/src/main/java/jobapply/agents/ApplyAgent.java`
  - `backend/src/main/java/jobapply/agents/ResearchAgent.java`
  - `backend/src/main/resources/playbook/skills/apply-form/SKILL.md`（`version: 3` → `4`）
  - `backend/src/main/resources/playbook/skills/components/cascader-city/SKILL.md`（`version: 2` → `3`）
  - `backend/src/main/resources/playbook/skills/components/readonly-select/SKILL.md`（`version: 2` → `3`）
  - `backend/src/main/resources/playbook/skills/components/date-picker/SKILL.md`（`version: 3` → `4`）
  - `backend/src/main/resources/playbook/skills/components/file-upload/SKILL.md`（`version: 1` → `2`）
  - `backend/src/main/resources/playbook/skills/components/repeatable-entries/SKILL.md`（`version: 2` → `3`）
  - `backend/src/main/resources/playbook/skills/components/resume-parse/SKILL.md`（`version: 1` → `2`）
- **实现要点**：
  1. **`ApplyAgent.METHOD`**：
     - 保留 `ApplicationState`（`update_state`）工作底稿流程；
     - 新增**资料与表单的映射（扁平资料 → 层级与分类推理）**：资料里常只写叶子值（如籍贯/所在地只写「台州」、专业写「软件工程」），而表单可能是多级级联或大类选择（「国家 → 省 → 市」「学科门类 → 专业类 → 专业」）。客观的上下级归属由模型按常识补全（如直接传 `["中国", "浙江", "台州"]`，或当第 1 层返回国家/省份列表时直接选出归属的上级再继续），不为客观归属停下来问用户；只有主观意愿（意向城市、是否服从调剂）或资料里没有的更细层级（具体到哪个区/街道）才用 `ask_user` 问用户；
     - 明确**快捷工具与分步拆解**：`browser_select_option` 和 `browser_set_date` 是一次调用的快捷工具；当只完成了一半（返回已展开的下一层选项）或没找到（返回面板当前看到的列/格子）时，先按返回的选项/面板调整参数再调，或者在最新快照（含弹出层视图）里用 `browser_click` / `browser_type` 分步点完。
  2. **`ApplyAgent.SITUATIONS`**：精简与组件 skill 重复的操作细节，保留核心行为准则并清晰指向对应 skill。
  3. **`ResearchAgent.SITUATIONS`**：标签页规则与 C011-fix / 工作包 D 对齐——用户让看 Chrome 里开着的页面时，可用 `browser_tabs`（`list` / `select`）切过去读，或请用户贴链接；要拿它做申请则在工作台转交或由申请 Agent 切过去。
  4. **7 个预置 `SKILL.md` 升级**：
     - `cascader-city`（v3）：补全扁平资料的客观上级路径；处理「选了一半 / 还有下一列」（`need_more_levels` 或面板仍开着显示下一级选项）；说明虚拟滚动/滚轮与分组城市标签。
     - `readonly-select`（v3）：修正 `## 选不中时` 的死板规则，区分：① 当前列表其实是上级分类（国家、省份、学科门类、职位大类）→ 直接从返回的选项原文里选出归属的上级分类继续；② 同级选项写法不同但语义一致（「本科」与「大学本科」）→ 直接选语义一致的原文；③ 确实没有语义一致或归属关系的选项 → 用 `ask_user` 把选项原文列给用户选。
     - `date-picker`（v4）：覆盖四种日期控件形态（① 原生 `type=date/month`；② 可键入框；③ 日历网格面板，含点头部年月切表、头部原生下拉、左右箭头翻页；④ 滚轮式多列面板与纯左右翻页面板），说明 `needs_confirm` 与面板分步点击回退。
     - `apply-form`（v4）：删掉已作废的「用户确认过的按钮会记进这个站点的记录，以后在这个站点上直接放行」，改为仅对当前页该按钮一次有效；补充扁平资料到层级/分类控件的客观推理原则。
     - `file-upload`（v2）：删掉已作废的「页面上有上传位置时，先请用户选文件」，对齐 C008-fix（可上传文件里已有合适文件如文件 1 简历时直接带 `target` + `files: [1]` 调 `browser_file_upload`，缺文件时才 `ask_user`）。
     - `repeatable-entries`（v3）与 `resume-parse`（v2）：统一 `update_state`（申请状态）表述并精炼结构。

---

### 工作包 B（交由其他 Agent 施工）：控件物理手脚与诚实观测补齐（`PageScripts` & `PlaywrightMcpClient`）

- **目标**：补齐虚拟滚动列表、多层级联未完待续、多列滚轮日期面板、纯箭头翻页日历的底层脚本支持，并在工具返回中如实报告状态。
- **要改的文件**：
  - `backend/src/main/java/jobapply/tools/PageScripts.java`
  - `backend/src/main/java/jobapply/tools/PlaywrightMcpClient.java`
- **实现步骤**：
  1. **`PageScripts.SELECT`**：
     - 滚动查找时除 `scrollTop` 外，将鼠标移到列表容器中心使用 `page.mouse.wheel(0, step)` 触发真实滚轮事件，兼容虚拟滚动列表与自定义滚动条；
     - 点完传入的 `values` 各层后，检查弹出层里是否还展开了新的下一列（或 `aria-expanded` 仍为展开且未完成赋值）：若是，**不按 Escape 强行关闭**，返回 `status: 'need_more_levels'`、已点层数、以及下一列可见的选项原文（`choices`），让模型直接补齐下一层或分步点击。
  2. **`PageScripts.DATE`**：
     - 识别多列滚轮式日期面板（`kind === 'columns'`，如年列、月列、日列并排的 `ul` / picker column）：对目标年/月/日项执行 `scrollIntoView` 或在列中心 `page.mouse.wheel` 滚动定位后点击；
     - 支持日历面板头部的原生 `<select>`（年份/月份下拉直接 `selectOption`）；
     - 纯左右翻页箭头日历（头部年月文字不可点或点击后视图未切换，记 `labelDead`）：不再死点头部文字，改用左右翻页箭头在一个调用里连续翻到目标年月。
  3. **`PlaywrightMcpClient`**：
     - 在 `select` 中格式化 `need_more_levels` 返回说明（告诉模型前几层已点中、还差下一层，附上这层看到的选项原文）；
     - 在 `setDate` 中识别 `wheel` 方式并输出清晰读回说明。

---

### 工作包 C（交由其他 Agent 施工）：Memory 读写闭环与上传区按需定位

- **目标**：让 `remember` 写入的公司记忆与通用经验真正进入两个 Agent 的上下文，并彻底解决上传区 `settle` 清空后 `read_resume` 和二次上传找不到文件的问题（`C006-fix-real-run` 第 9 条）。
- **要改的文件**：
  - `backend/src/main/java/jobapply/persistence/MemoryNotes.java`
  - `backend/src/main/java/jobapply/harness/context/FillContext.java`
  - `backend/src/main/java/jobapply/harness/runtime/SessionLoop.java`
  - `backend/src/main/java/jobapply/harness/runtime/HarnessLoop.java`
  - `backend/src/main/java/jobapply/workflow/ApplicationRunner.java`
  - `backend/src/main/java/jobapply/persistence/UploadArea.java`
- **实现步骤**：
  1. **Memory 读回注入**：
     - `MemoryNotes` 提供读取 `companies/<company>.md` 与 `review/lessons.md` 条目的只读方法；
     - `FillContext`（申请 Agent）在上下文里加入这家公司的记录（`companies/<company>.md`）和已积累的通用经验（`review/lessons.md`）；
     - `SessionLoop`（对话 Agent）在上下文里加入 `review/lessons.md`（以及涉及具体公司时的公司记忆）。
  2. **原件直读与上传按需复制（`C006-fix-real-run` 第 9 条）**：
     - 申请 Agent 的 `read_resume` 直接按文件清单里的原件标识从 `base/originals/`（或会话附件原件）读取文字，不再依赖 `storage/uploads/<job-id>/` 下的临时副本；
     - `browser_file_upload` 翻译文件编号为路径时，若 `storage/uploads/<job-id>/` 下的副本因 `settle` 被清空，按需调用 `uploads.stage` 从原件重新复制一份再传给 MCP。

---

### 工作包 D（交由其他 Agent 施工）：对话 Agent 标签页能力对齐（`browser_tabs`）

- **目标**：对话 Agent 的工具集补上 `browser_tabs`（`list` / `select`），与提示词里「用户让看已开着的页面时切过去读」一致。
- **要改的文件**：
  - `backend/src/main/java/jobapply/tools/ResearchTools.java`
  - `backend/src/main/java/jobapply/harness/runtime/SessionLoop.java`
- **实现步骤**：
  1. `ResearchTools.definitions()` 加入 `BrowserTools.tabs()`（`browser_tabs`），`ResearchTools.isBrowserTool` 或分支识别 `BrowserTools.TABS`；
  2. `SessionLoop` 处理 `browser_tabs` 的 `list` 与 `select`（委托给该 Session 的 `BrowserSession.tabs` / `select`，与 `HarnessLoop` 一致）。

---

### 工作包 E（交由其他 Agent 施工）：前端申请工作台（`JobPage.tsx`）体验修复

- **目标**：填写报告实时跟随状态刷新并用 Markdown 渲染表格；修复后端重启或状态变化后工作台残留旧上传槽导致回复报 400 的问题（`C006-fix-real-run` 第 17 条）。
- **要改的文件**：
  - `frontend/src/pages/JobPage.tsx`
- **实现步骤**：
  1. `Report` 组件监听运行状态 / 步数变化自动重拉 `getApplicationReport`，默认用 `<Markdown>` 渲染报告正文（使字段表格正常渲染），并提供「查看原文」切换按钮；
  2. 全局事件流收到 `reset` 或重连后重新读取工作台状态；发送 `reply` 时，仅当 `status.status === 'waiting_user'` 且 `slots.length > 0` 时才附带 `files` 参数。

---

## 测试与验收标准

- **本步（工作包 A）验证**：
  - 在 `backend/` 执行 `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -B -q clean test`，确认全部单元测试通过（含 `PlaybookSeederTest` 的预置清单、字符预算 `<= 8000`、无隐私数据检查、frontmatter `version` 解析）。
- **全部工作包合龙后验收**：
  - `backend/` 下 `mvn -B -q clean verify` 退出码 0；`frontend/` 下 `npm run build` 退出码 0。
  - 真实网站验证：扁平资料（如「台州」）面对省市/国家省市级联时模型自行补全上级并一次选中；滚轮日期/纯箭头日历能一次设好；停在提交前要求修改时 `read_resume` 和上传不报错；工作台报告表格正常渲染且实时刷新。

---

## 实际结果（工作包 A，2026-10-28）

- **已执行工作包 A（Gemini 4）**：
  - `backend/src/main/java/jobapply/agents/ApplyAgent.java`：`METHOD` 增加「资料与表单的映射（客观归属自行补全，主观意愿才问用户）」与「快捷工具与分步拆解」；精简 `SITUATIONS` 中与组件 skill 重复的细碎操作描述，保留所有核心边界并指向对应 skill。
  - `backend/src/main/java/jobapply/agents/ResearchAgent.java`：`SITUATIONS` 的「标签页」一条与 `browser_tabs`（`list` / `select`）对齐。
  - 升级 7 份预置 `SKILL.md` 正文与 `version`：
    - `apply-form/SKILL.md`（v3 → v4）：去掉「确认过的按钮记进站点记录以后直接放行」的旧话，补扁平资料到层级/分类的客观映射原则。
    - `components/cascader-city/SKILL.md`（v2 → v3）：增加「扁平资料补全上级路径」「选了一半 / 还有下一列」「虚拟滚动与分组城市」。
    - `components/readonly-select/SKILL.md`（v2 → v3）：重构「选不中时」的三类处理（上级分类直接选归属项、同级异名选语义一致项、确无一致或归属才 `ask_user`）。
    - `components/date-picker/SKILL.md`（v3 → v4）：覆盖原生框、可键入框、日历网格面板（含头部下拉与纯箭头翻页）、滚轮式多列面板四种形态及分步回退。
    - `components/file-upload/SKILL.md`（v1 → v2）：去掉「先请用户选文件」旧话，对齐 C008-fix（已有合适文件如文件 1 直接上传）。
    - `components/repeatable-entries/SKILL.md`（v2 → v3）：对齐 `update_state`（申请状态）并精炼排查顺序。
    - `components/resume-parse/SKILL.md`（v1 → v2）：对齐 `update_state`（申请状态）与自动解析字段核对。
- **当时未做**：工作包 B–E。C、D 见下一节。

---

## 实际结果（工作包 C、D，2026-10-28）

- **工作包 C（Memory 闭环与上传按需定位）**：
  - `MemoryNotes.lessons()`：读 `review/lessons.md` 正文（去 frontmatter）；文件不存在或正文空则 `Optional.empty()`。`company()` 仍读 `companies/<company>.md`。
  - `FillContext.Inputs` 增加 `companyNotes`、`lessons`，保留原来的 9 参数构造。上下文在站点记录之后输出 `# 这家公司的记录` 和 `# 通用经验（lessons.md）`。
  - `HarnessLoop.Round.inputs()` 注入 `memoryNotes.company(run.company())` 与 `memoryNotes.lessons()`。
  - `SessionLoop.Round.context()` 在 skill 目录后附 `# 经验总结（lessons.md）`（对话侧没有当前岗位公司，不注入公司记忆）。
  - `read_resume`：有 `assetKey` 时先从 `base/originals/` 读原件；找不到再走上传区路径。
  - `stagedPaths()`：有 `assetKey` 且 `uploads/<job-id>/` 副本不在时，当场 `UploadArea.stage` 从原件再复制一份。`UploadArea` / `ApplicationRunner` 未改（`stage` 已够用）。
- **工作包 D（对话 Agent `browser_tabs`）**：
  - `ResearchTools.definitions()` 加入 `BrowserTools.tabs()`；`isBrowserTool` 含 `BrowserToolPolicy.TABS`。
  - `SessionLoop.browserTool` 开头处理 `list` / `select`：`browser.tabs(session)` / `browser.select(session, index)`，`select` 用 `withPage` 保存快照。不经 MCP 模型白名单（与申请循环相同，由循环代发）。
- **未改**：`PageScripts`、`PlaywrightMcpClient`、`BrowserTools` 描述、前端、`ApplyAgent` / `ResearchAgent` / `SKILL.md`（当时；其后 B、E 已在同分支落地）。

---

## 实际结果（工作包 B、E，工作树，2026-10-28）

- **工作包 B**（控件脚本与诚实观测）：`PageScripts.SELECT` 在列表容器上使用 `page.mouse.wheel`，点完传入层后若还有下一列返回 `need_more_levels`；`PageScripts.DATE` 识别多列滚轮（`kind === 'columns'` / `how: 'wheel'`）与头部不可点时的 `labelDead` 箭头翻页。`PlaywrightMcpClient` 把 `need_more_levels` 与 `how=wheel` 格式化给模型。`BrowserTools` 的 select/date 说明含 mouse wheel / wheel / arrow。单元侧有 `PlaywrightMcpClientTest.selectNeedMoreLevelsExplainsTheOpenNextColumn` 与 `BrowserToolsTest` 对描述字符串的断言。
- **工作包 E**（工作台）：`JobPage.tsx` 的 `Report` 按 `status:toolCalls:round` 变化重拉报告，默认 `<Markdown>`，可切「查看原文」；全局事件 `reset` 时重读工作台；`reply` 仅在 `waiting_user` 且有上传槽时带 `files`。

---

## 合龙验证（2026-10-28）

- **本机 JDK 25（`25.0.5`，`/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`）下 `backend/` `mvn -B clean verify`**：**退出码 0**（surefire 655、failsafe 1 `MemoryLedgerScenarioIT` 全部通过，Failures / Errors / Skipped 均为 0；执行时以 `JOBAPPLY_APP_CONFIG=/tmp/jobapply-nonexistent-app.yaml` 隔离本机 `~/Library/Application Support/JobApply/app.yaml`，使 `JobApplyApplicationTest` 默认 `../storage` 断言不受本机桌面配置干扰）。
- **`frontend/` `npm run build`**：`tsc -b && vite build` **退出码 0**。
- **无头 Chromium 5 项新脚本本地 DOM 验证**（使用本机 `/Users/yigewang/Library/Caches/ms-playwright/chromium_headless_shell-1243/chrome-headless-shell-mac-arm64/chrome-headless-shell` 与 `playwright-core` 直接加载 `PageScripts.DOM` / `SELECT` / `DATE`）：
  1. 虚拟滚动 `wheel` 下拉（`overflow: hidden`、仅响应 `wheel` 事件动态渲染选项）→ `status: 'selected'`, `verified: true`；
  2. 3 层级联先传 2 层 `['浙江', '台州']` → 返回 `status: 'need_more_levels'`, `closed: false`, `options: ['椒江区', '临海市']`（保持浮层开启）；紧接着再调 3 层 `['浙江', '台州', '椒江区']` → 认回已开浮层并选中关闭（`status: 'selected'`, `closed: true`, `verified: true`）；
  3. 多列滚轮日期面板（年/月/日三列 `ul`）只给年月 `2026-09` → `status: 'need_day'`, `how: 'wheel'`, `closed: true`；再给完整日期 `2026-09-15` → `status: 'set'`, `how: 'wheel'`, `verified: true`；
  4. 日历头部原生 `<select>` 选择年月（`2026-10-18`）→ `status: 'set'`, `how: 'panel'`, `verified: true`；
  5. 日历头部文字不可点（`labelDead`）改用左右箭头在单次调用内连续翻页到目标年月（`2026-10-12`）→ `status: 'set'`, `how: 'panel'`, `verified: true`。
- **合龙验证抓到并修复的 `PageScripts.java` 问题**：
  1. `DATE` 的 `selectHeader`：`h.evaluate((s, {want, part}) => ..., {want, part})`（Playwright `evaluate` 要求多参数封装为单对象；同时仅在目标项与当前 `s.value` 不同时才 `selectOption`、分别检查年/月避免 `||` 短路跳过月份下拉，并从可见 `<select>` 选中项兜底读取 `hy` / `hm`）；
  2. `SELECT`：当上一轮返回 `need_more_levels` 保持浮层开启后，下一轮再调 `browser_select_option` 时，`if (info.expanded)` 以及点击后 `!popup` 回退 `pre: []` 认回已开着的浮层。
- **未运行**：真实招聘网站与真实 Chrome 端到端冒烟。
