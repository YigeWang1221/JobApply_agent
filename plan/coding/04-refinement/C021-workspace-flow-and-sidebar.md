# C021 · 工作台内闭环投递、三级 Memory 贯通与左侧三级导航栏

- 对应 Master：[agent-runtime.md](../../master/agent-runtime.md) §7.1–7.3、[workflow.md](../../master/workflow.md) 主流程、[memory.md](../../master/memory.md)、[ui.md](../../master/ui.md)
- 依据：用户（2026-10-28）提出三项体验与架构改进：
  1. **职责边界重划**：`Session` 专注搜寻公司与岗位、官网核验、把匹配的岗位加进候选池（显示在 Session 右侧岗位栏）；单岗位的**简历匹配排名、针对 JD 的修改建议、用户确认选定简历、以及打开 Chrome 填表至提交前**全部移入该岗位专属的「申请工作台（JobPage）」内一步到位完成，不再在 Session 与工作台之间来回跳转；
  2. **三级 Memory（Base → Project → Session → Job 工作台）贯通**：将 `base/profile.md`（用户画像）与 Session 调研留下的 `job.md` `lead_note`（岗位调研备注）每步自动注入工作台 `FillContext`，配合 `read_jd` 读取 JD 快照与写作偏好，使工作台 Agent 一启动就具备完整的跨层上下文；
  3. **最左侧导航栏升级为三级结构**：`Project` → `Session` → `已打开的工作台`（`workspaces`），方便同时投递多个岗位时在最左侧边栏一键切换并观察状态。
- 分工：拆为 **工作包 A（由 Gemini 4 执行）**、**工作包 B（后端侧栏视图 `Views.java`）**、**工作包 C（前端三级侧栏与一键进工作台 UI）**。工作包 B 与工作包 C 文件完全正交，可由其他编码代理并行执行。
- 状态：**已施工，等用户在本机试**（2026-10-28）。三个工作包 A、B、C 都已落地并合龙验证：JDK 25 下 `mvn -B clean verify` 退出码 0（单元测试 660、场景测试 1），`npm run build` 退出码 0。真实 Chrome 与真实招聘站上的端到端冒烟**未运行**，等用户日常投递时试。

---

## 不做

- 不新增安全守卫、确认断点或硬编码站点分支。
- 不改变「系统永远不执行最终提交」与「C 级真实值不进模型上下文」边界。
- 未开启工作台的普通候选池岗位（`lead_status` 为 `new` / `shortlisted` / `skipped` / `closed` 且无 `application` 运行记录）不放进最左侧三级导航栏（留在 Session 右侧岗位栏与 Project 候选池）。

---

## 三个工作包拆分与规格

### 工作包 A（已施工，Gemini 4 执行）：Prompts、Skills、工作台简历匹配闭环与三级 Memory 注入

- **已修改文件**：
  - `backend/src/main/java/jobapply/tools/ControlTools.java`：新增 `LIST_RESUMES`（`list_resumes`）、`CHOOSE_RESUME`（`choose_resume`），扩展 `READ_RESUME`（`read_resume`）同时支持按原件 `key`（对比多份原件）与按上传区 `file` 编号（填表读文件 1）读取。
  - `backend/src/main/java/jobapply/harness/runtime/ResumeMatch.java`：新增 `chooseInWorkspace` 与 `Chosen` 返回结构；支持工作台内传 `asset`、`attachment`（工作台附件登记为新原件）或 `skip_ranking`（未给 `asset` 时自动选最新上传的原件），`ranking` 在工作台内设为可选，写入 `job.md` 的 `resume_choice`。
  - `backend/src/main/java/jobapply/harness/context/FillContext.java`：`Inputs` 增加 `profileSummary`（`base/profile.md` 正文）与 `jobNote`（`job.md` 的 `lead_note`），保留 9 参数与 11 参数兼容构造；上下文输出 `# 用户画像（profile.md）` 与 `# 岗位调研备注（Session 留存）`。
  - `backend/src/main/java/jobapply/harness/runtime/HarnessLoop.java`：
    - 接入 `list_resumes`、`read_resume(key/file)`、`choose_resume`；
    - `Round.files` 改为可变列表，`choose_resume` 选定原件后当场调用 `UploadArea.stage` 与 `JobMaterials.write` 将该简历设为当前工作台上传区「文件 1」，并随 `current()` 持久化到 `AgentRun.files`；
    - 懒打开浏览器页面（`shouldDeferBrowser` + `ensurePage`）：当工作台刚启动且尚未选定简历时，`go()` 先不打开 Chrome 标签页，让模型先在工作台内完成阶段一（读 JD 与简历、输出匹配排名和修改建议、`ask_user` 等用户确认）；当模型调用任何浏览器工具或下一轮恢复时自动打开岗位页面并取初始快照；
    - `inputs()` 每步注入 `base/profile.md` 与 `lead_note`。
  - `backend/src/main/java/jobapply/workflow/ApplicationRunner.java`：`start()` 根据 `job.extra().get("resume_choice")` 区分开场提示词——未选简历时引导工作台 Agent 先在工作台里做简历匹配排名与修改建议，已选定简历时作为文件 1 直接看页面填表。
  - `backend/src/main/java/jobapply/agents/ApplyAgent.java`：`IDENTITY` 与 `METHOD` 升级为清晰的「阶段一：简历匹配与修改建议 → 阶段二：浏览器打开岗位页与表单填写」两阶段闭环。
  - `backend/src/main/java/jobapply/agents/ResearchAgent.java`、`backend/src/main/java/jobapply/harness/runtime/SessionLoop.java`、`backend/src/main/java/jobapply/tools/ResearchTools.java`：`Session` 聚焦搜岗、官网核验与候选池管理，去掉 `SessionLoop.startApplication` 中「未排简历就拦截不让启动工作台」的旧阻断，直接开启工作台交由工作台第一步排简历。
  - `backend/src/main/resources/playbook/skills/apply-form/SKILL.md`：`version` 4 → 5，同步工作台两阶段闭环与 `choose_resume` 设为文件 1 后直接上传的说明。

---

### 工作包 B（已施工）：后端左侧栏三级工作台视图（`Views.java`）

- **目标**：在 `GET /api/projects` 返回的每个 `SessionSummary` 中附带该 Session 下已打开的工作台列表 `workspaces`，供前端渲染 `Project → Session → 已打开工作台` 三级导航。
- **只修改后端文件（不碰前端 `frontend/`）**：
  - `backend/src/main/java/jobapply/api/Views.java`
  - （如需补充测试）`backend/src/test/java/jobapply/api/` 下对应测试
- **精确契约与实现要求**：
  1. 在 `Views.java` 中定义 `record WorkspaceSummary`（包内可见）：
     ```java
     record WorkspaceSummary(String id, String company, String title, String application, String bucket, String label) {
     }
     ```
     字段含义（JSON 序列化键名严格一致）：
     - `id`：`JobView.id()`（如 `"2026-10-28-001"`）
     - `company`：`JobView.company()`
     - `title`：`JobView.title()`
     - `application`：`JobView.application()`（申请线程状态：`"running"` / `"queued"` / `"waiting_user"` / `"ready"` / `"failed"` / `"interrupted"` / `"done"`，无线程时为 `null`）
     - `bucket`：`JobView.bucket()`（`"preparing"` / `"waiting"` / `"ready"` / `"applied"` / `"closed"`）
     - `label`：`JobView.label()`（如 `"准备中"`、`"待审阅"`、`"已投递"`）
  2. 将 `Views.SessionSummary` 扩展为（末尾新增 `List<WorkspaceSummary> workspaces`）：
     ```java
     record SessionSummary(String id, String project, String title, String createdAt, List<String> tags,
                           List<String> jobs, String handoff, String status, List<WorkspaceSummary> workspaces) {
     }
     ```
     保留或重载 `static SessionSummary session(SessionRecord s, SessionRunner runner)`（默认传 `List.of()`）与 `static SessionSummary session(SessionRecord s, SessionRunner runner, List<WorkspaceSummary> workspaces)`，避免破坏其它调用处。
  3. 在 `Views.project(ProjectSpec spec, List<SessionRecord> sessions, List<JobView> jobs, SessionRunner runner, LocalDate today)` 中计算每个 Session 的 `workspaces`：
     - **什么是「已打开的工作台」**：`JobView` 满足 `job.application() != null || "converted".equals(job.leadStatus())`（即已经开启过申请线程或已转为申请的岗位；仍在候选池的普通候选不进左侧三级菜单）。
     - **归属哪个 Session**：
       - 建立当前 Project 存在的 `sessionIds` 集合（按 `sessions` 列表顺序）；
       - 对每个已打开工作台的 `JobView`：优先看 `s.jobs().contains(job.id())` 或 `job.discoveredIn()` 匹配到的存活 Session；
       - 若关联的旧 Session 已被删除（不在当前 `sessions` 中），归入当前 Project 的第一个（最新）Session 下，确保已打开的工作台不会在侧栏丢失；
       - 同一个 Job 只归入一个 Session（不重复挂载）。
- **验证命令**：
  - `cd backend && JOBAPPLY_APP_CONFIG=/tmp/jobapply-nonexistent-app.yaml JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -B test`
- **实际结果**（2026-10-28）：
  - 按上面的契约在 `Views.java` 里落地 `record WorkspaceSummary(...)`、`SessionSummary` 末尾的 `List<WorkspaceSummary> workspaces`、`session(s, runner)` 默认 `List.of()` 的重载，以及 `static Map<String, List<WorkspaceSummary>> workspaces(sessions, jobs)`。
  - `Views.project(...)` 里先算一次分组表，再把每个 Session 的那一份塞进 `SessionSummary`；`GET /api/projects` 之外的单 Session 返回（新建 Session、改标题）仍给空列表。
  - 新增 `backend/src/test/java/jobapply/api/ViewsWorkspacesTest.java`（5 项，虚构数据）：候选池岗位不进；`jobs` / 「发现于」认领；旧 Session 删掉时归最新 Session；多个 Session 引用同一个 Job 时只进一个；没有 Session 时为空。
  - 「最新 Session」= `SessionRepository.list()` 的第一个，即按 `createdAt`、`id` 倒序，和侧栏顺序一致。

---

### 工作包 C（已施工）：前端三级侧栏与一键启动进入工作台

- **目标**：最左侧导航栏支持 `Project → Session → 已打开的工作台` 三级展示与一键切换；从 `SessionPage` 右侧岗位栏、`JobPage` 顶部、`Dashboard` 候选池点「开始投递 / 开始申请」时直接调用 `api.startApplication` 打开并进入该岗位工作台，不再往 Session 发消息或跳回 Session。
- **只修改前端文件（不碰后端 `backend/`）**：
  - `frontend/src/api.ts`
  - `frontend/src/App.tsx`
  - `frontend/src/pages/SessionPage.tsx`
  - `frontend/src/pages/JobPage.tsx`
  - `frontend/src/dashboard/Dashboard.tsx`
  - `frontend/src/styles.css`
- **精确契约与实现要求**：
  1. **`frontend/src/api.ts`**：
     - 新增 `export interface WorkspaceSummary`（与工作包 B 的 JSON 严格对齐）：
       ```ts
       export interface WorkspaceSummary {
         id: string;
         company: string;
         title: string;
         application: string | null;
         bucket: string;
         label: string;
       }
       ```
     - 在 `SessionSummary` 中增加可选字段 `workspaces?: WorkspaceSummary[];`（用可选字段保证前后端独立编译与兼容）。
  2. **`frontend/src/App.tsx`**：
     - 在 `Sidebar` 的每个 `Session`（`li`）内部，若 `(s.workspaces ?? []).length > 0`，在 `.side-session-row` 下方渲染三级工作台列表 `<ul className="side-workspaces">`：
       - 每项显示状态圆点（按 `w.application` / `w.bucket`：`running` / `queued` 蓝点脉冲、`waiting_user` / `failed` / `interrupted` 黄点、`ready` 绿点、`applied` 灰绿勾）+ `{w.company} · {w.title}`；
       - 点击直接调用 `go({ kind: "job", id: w.id })`；
       - 当 `route.kind === "job" && route.id === w.id` 时，该工作台项高亮（`.side-workspace.active`）。
     - 在 `App` 的 `currentProject` 推导中：当 `route.kind === "job"` 时，也能通过 `projects` 里的 `s.workspaces` 或 `s.jobs` 反查出所属 Project ID，使顶栏「新对话」等操作保持在当前 Project。
  3. **`frontend/src/pages/SessionPage.tsx`**：
     - 右侧 `JobsPanel` 的每个岗位卡片/行提供直接操作按钮：
       - 若 `job.application` 已存在或 `job.leadStatus === "converted"`：显示「打开工作台」按钮，点击直接 `go({ kind: "job", id: job.id })`；
       - 否则若 `job.canStart` 为 `true`：显示「开始投递」主按钮，点击直接调用 `await api.startApplication(job.id, { project: job.project, session: id })`，成功后调用 `onChanged()` 并 `go({ kind: "job", id: job.id })` 直接进入该岗位工作台！同时保留点标题查看「详情」。
  4. **`frontend/src/pages/JobPage.tsx` 与 `frontend/src/dashboard/Dashboard.tsx`**：
     - `JobPage.tsx`：删除原先 `startInSession` 里 `api.sendMessage(session, "给 ... 排一下简历，然后开始申请")` 并 `go({ kind: "session", id: session })` 的跳回逻辑！改为直接调用 `await api.startApplication(job.id, { project: job.project, session })`，然后调用 `load()` 和 `onChanged()` 原地刷新进入工作台运行态（若该 Project 还没有任何可用 `session`，先自动 `await api.createSession(job.project)` 拿一个 `session.id` 再 `startApplication`）。
     - `Dashboard.tsx`：候选池点「开始申请」时，同样直接调用 `api.startApplication(job.id, { project: job.project, session })`（没有 `session` 时先 `createSession`），随后直接 `go({ kind: "job", id: job.id })` 进入工作台，不再往 Session 发消息、不再跳去 SessionPage。
  5. **`frontend/src/styles.css`**：
     - 新增 `.side-workspaces`、`.side-workspace`、`.side-workspace.active`、`.side-ws-dot`（及 `running` / `waiting` / `ready` / `applied` 状态色）与 `SessionPage` 右侧岗位栏「开始投递 / 工作台」按钮样式。
- **验证命令**：
  - `cd frontend && npm run build`
- **实际结果**（2026-10-28）：
  - `frontend/src/api.ts`：`WorkspaceSummary` 与后端字段完全一致（见下面「字段契约」），`SessionSummary.workspaces` 仍为可选字段。
  - `frontend/src/App.tsx`：`Sidebar` 在每个 Session 行下面渲染 `.side-workspaces` 三级列表（状态圆点 + `company · title`，当前工作台高亮），`projectFor()` 在 `route.kind === "job"` 时通过 `s.jobs` / `s.workspaces` 反查所属 Project。
  - `frontend/src/pages/SessionPage.tsx`：`JobsPanel` 按契约给「打开工作台」（已有申请线程或 `converted`）/「开始投递」（`canStart`）两个按钮，并保留「详情」入口（没开始投递、或者这个岗位现在不能开始时的唯一入口）。
  - `frontend/src/pages/JobPage.tsx`：删掉「往 Session 发一句话再跳回 Session」的旧逻辑，改为 `startWorkspace()` 原地调 `api.startApplication`（没有可用 Session 时先 `createSession`），成功后 `onChanged()` 刷新；`JobPage` 因此多了一个 `onChanged` prop（`App.tsx` 传入）。
  - `frontend/src/dashboard/Dashboard.tsx`：候选池「开始申请」同样直接 `api.startApplication`，成功后 `go({ kind: "job", id })`。
  - `frontend/src/styles.css`：新增 `.side-workspaces` / `.side-workspace` / `.side-ws-dot` 四档状态色与 `button.btn-xs`。

---

## 字段契约（合龙核对结果，2026-10-28）

`Views.WorkspaceSummary` ↔ `api.ts` `WorkspaceSummary`，六个字段名、顺序与可空性完全一致，只在前端把 `bucket` 收窄成了联合类型：

| 字段 | 后端 `record WorkspaceSummary` | 前端 `interface WorkspaceSummary` | 一致 |
| --- | --- | --- | --- |
| `id` | `String`（`JobView.id()`） | `string` | 是 |
| `company` | `String` | `string` | 是 |
| `title` | `String` | `string` | 是 |
| `application` | `String`（无申请线程时 null） | `string \| null` | 是 |
| `bucket` | `String`（`JobBucket.value()`） | `JobBucket`（`"lead" \| "preparing" \| ...`） | 是（前端更窄，不影响 JSON） |
| `label` | `String`（`JobStatus.label()`） | `string` | 是 |

`SessionSummary` 两侧也都一致：`workspaces` 后端恒给列表（可能为空），前端声明为可选并对 `undefined` 兜底成 `[]`，旧后端也能编译。

---

## 合龙验证（2026-10-28）

- **核对范围**：工作包 A 的 `HarnessLoop` 改动是否自洽——可变 `files` 与 `current()` 存盘、`browse(...)` 里的 `recordUploads` / `stagedPaths()`、每轮 `progress.accept(current())` 是否带上 `agentrun.files()`。
- **结论**：
  - `Round.files` 是 `new ArrayList<>(run.files())`；`readResume`、`recordUploads`、`stagedPaths`、`inputs()` 四处都已改用实例 `files`，不再有 `run.files()` 直读（`rg` 核对只剩构造处一处）。
  - `current()` = `run.withFiles(files).withProgress(...)`，`ApplicationRunner` 的 `progress` 消费者每轮 `runs.save(progressed)`，所以 `choose_resume` 当场 stage 出来的文件 1 在下一轮就落盘。
  - `shouldDeferBrowser()` 的四个条件（`fresh`、`files.isEmpty()`、没有已认领标签页、`resume_choice` 不是本次的）与 `ensurePage()` 的懒打开都对得上；开场提示不含「匹配简历」的旧路径仍立即打开页面。
- **本次修的两处**（都能清楚定位、且会造成实际损失）：
  1. `HarnessLoop.chooseResume`：原来 `uploads.stage(...)` / `JobMaterials.write(...)` 抛出的 `RuntimeException`（原件被改过、SHA 对不上、磁盘出错）会一路冒到 `AgentLoop`，把整次运行记成失败。现在改成只让这次工具调用返回「失败：…」，并把 `files` 的修改挪到复制和写入都成功之后，避免文件清单和磁盘不一致。
  2. `SessionPage.JobsPanel`：工作包 C 把原来的「详情」按钮整个换掉了，导致 `canStart === false`（没有岗位链接、已经在提交中等）的岗位在右侧栏没有任何入口。现在恢复「详情」链接，与「开始投递」并列。
- **后端**：`backend/` 下 `JOBAPPLY_APP_CONFIG=/tmp/jobapply-nonexistent-app.yaml JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -B clean verify` → **退出码 0**，单元测试 **660** 项（含新增 `ViewsWorkspacesTest` 5 项）+ `MemoryLedgerScenarioIT` **1** 项，Failures 0、Errors 0。
  - 过程中出现过一次 `DeleteSessionProjectTest` 失败（C013 的旧测试，文件未改动）：断言拿到的快照里多出 `playbook/skills/research/verify-official/.SKILL.md*.tmp`，是 `AtomicFiles` 的临时文件与快照 `Files.walk` 撞上了；单独连跑 10 次全通过，改完代码后重跑全量也通过，判为**既有 flaky，不是 C021 引入**，本步不改它。
- **前端**：`frontend/` 下 `npm run build` → **退出码 0**（`tsc -b && vite build`，只有 Vite 的 chunk > 500 kB 提示）。
- **未运行**：真实 Chrome、真实招聘站的端到端冒烟；侧栏三级列表在浏览器里的实际观感。
- **偏差**：工作包 C 把 `SessionPage` 右侧栏原来的「详情」按钮整个替换掉了，导致不能开始投递的岗位没有入口，本步补回；其余三处按钮（`SessionPage` 开始投递、`JobPage` 原地启动、`Dashboard` 候选池）都已按契约改成直接调 `api.startApplication`，没有偏差。
