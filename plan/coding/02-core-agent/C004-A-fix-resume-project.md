# C004-A-fix · 原件能删除、原件归属 Project

- 对应：[C004-A](C004-A-model-guard.md)（「资料」页原件、`base/assets.md`）；[C008](../03-features/C008-resume-match.md) / [C008-fix](../03-features/C008-fix-resume-confirm.md)（`list_resumes` / `read_resume` / `choose_resume`、对话里发来的简历登记成原件）；[C013](../03-features/C013-delete-session-project.md)（`.trash/` 软删）。Master：[memory.md](../../master/memory.md) storage 目录第 57–58、64 行，「新建 Project」第 157 行，「不做」第 397 行。
- 依据：用户反馈（2026-11-07）：「资料界面没有删除简历的方法，会导致无限膨胀。还有简历应该绑定 project，对话框内上传的简历绑定对应的 project」。
- 状态：已施工，等用户在本机试（2026-11-07）。

## 现状

- 原件只有一张全局表：`base/assets.md` + `base/originals/`（`persistence/AssetRegistry.java:24-114`）。能做的只有列出、登记（按 SHA-256 去重，`:90-114`）、改说明（`:117-136`）；**没有删除**，也没有任何 Project 字段。未识别的列会原样保留（`:259-260`）。
- 接口 `api/AssetController.java:47-76`：`GET/POST /api/assets`、`PATCH /api/assets/{key}`，没有 `DELETE`。前端 `pages/Assets.tsx:40-156`（「资料与简历 → 简历与原件」）只有上传和改说明；`api.ts:821-824`。
- Master 早就写了 `projects/<id>/assets.md`「本 Project 使用的简历版本、补充附件（引用 base/assets.md 的键）」（memory.md:64、:157），但**没有实现**：`StorageLayout` 没有这个路径，Project 目录里也没有这个文件；`project.md` 的 `default_resume`（`ProjectRepository.java:65,96`）只读写，没人用。
- 原件进库的入口只有两个：
  1. 资料页上传（`AssetController.upload`）。
  2. 对话里发来的文件：Session / 工作台的附件先存在各自的附件目录，只有模型调用 `choose_resume(attachment=#…)` 时才登记成原件（`harness/runtime/ResumeMatch.java:234-249`，说明写「用户在对话里发来的简历（job…）」）。Session 用 `choose`（`:192-232`），工作台用 `chooseInWorkspace`（`:109-152`）；两处都有 `job`，所以知道 Project（`job.project()`）。
- Agent 看到的是**全部**原件：`ResumeMatch.list()`（`:51-69`）、`asset(key)`（`:273-279`）、`latestAsset()`（`:180-191`，跳过排名时自动选最新）、`current()`（`:261-271`，有新上传就要重排）都用 `assets.list()`。接线：Session `SessionLoop.java:141,322-324`；工作台 `HarnessLoop.java:185,905-906,1080-1110`；后续段 `FollowupLoop.java:102,305-312,469-476`。提示词 `ApplyAgent.java:32-33`「资料库里有哪些原件」、`ResearchAgent.java:48-52`。所以排名会出现「资料库中的 9 份简历」，混着别的 Project 的版本。
- Job 引用原件的地方：`job.md` 的 `resume_choice`（`asset` 键 + `file` 文件名 + 排名里每项的 `key`/`file`），`materials`（`slot`、`asset`、`upload_name`、`sha256`，`JobMaterials.java:31`）；`uploads/<job>/` 里有等字节副本（`UploadArea`）。`ApplicationRunner.java:283-284` 开始申请时 `resume_choice.asset` 找不到原件就当没选；工作台上传位置的候选是全部原件（`ApplicationRunner.java:648`），选的键在 `:1016` 校验；`JobPage.tsx:155` 按 `resume_choice.asset` 预选上传位置。
- 删除 Project（C013，`Trash.moveProject`）不碰 `base/`，原件一直留着。

## 用户决定（2026-11-07，最终）

1. 「将这个简历的 project 做成下拉框可以手动置顶 project」：资料页每份简历一行一个 Project 下拉框，归属由用户手动设、随时改。已有简历迁移一次：被恰好一个 Project 的 Job 用过（`resume_choice` / `materials`）就归那个 Project，否则「未归属」，由用户在下拉框里选。未归属的简历 Agent 看不到。
2. 「多上传一次，不多次引用」：一份简历只属于一个 Project，不做跨 Project 引用。别的 Project 要用同一个文件就在那里再传一次，成为那个 Project 自己的一条。SHA-256 去重按 Project：同一文件在另一个 Project 可以再登记，同一 Project 里传两次仍去重。删除就是删这一条（进回收站）。
3. 同意：资料页的 Project 选择和 Dashboard 共用（同一次打开窗口里记住的选择）。
4. 回收站：删除把文件挪进 `storage/.trash/<时间>-asset-<键>/`，写 `RESTORE.md`（同 C013）；`job.md` 不改；用过它的 Job 照常显示文件名；开始申请时选定的简历不在了就先重排；读已删除的原件返回「原件 … 已删除」。
5. 「其他的不加归属作为通用资料……能够更新老的 app 的资料属性，使之能够加上类型 or 自动更新分类数据」：只有简历归属 Project。成绩单、证书等是「通用资料」：没有 Project，所有 Project 和 Agent 都看得到。原件加「分类」（简历 / 通用资料）。老数据启动时自动升级：被任一 Job 选为 `resume_choice` 的 → 简历；文件名或说明含 简历 / 履历 / resume / CV → 简历；其余 → 通用资料。用户可以在资料页改分类。升级幂等、不破坏数据，没有新列的旧文件照常读。
6. 「对话框内发送的，只在对话框中生效，不加入本地存储」：Session / 工作台对话里发来的文件不登记成原件。`choose_resume(attachment=…)` 选它只用于这个 Job：`resume_choice` 记附件编号和文件名，申请开始 / 工作台选定时从附件复制进 `uploads/<job>/`。

以下「改动」按这些决定改写，替代原来的 `projects/<id>/assets.md` 引用表设计和「待确认」各项。

## 改动

### B. 分类与归属（`base/assets.md` 加两列）

- `base/assets.md` 加两列：「分类」（`简历` / `通用资料`）和「Project」（只对简历有意义，空 = 未归属）。原件仍只在 `base/originals/`，一条登记一个文件；同一文件在两个 Project 各登记一条时各存一份（文件名照旧加 -2）。不新建 `projects/<id>/assets.md`。
- 登记（`AssetRegistry.register`）带分类和 Project；去重按（SHA-256、Project）相同（通用资料和未归属的简历 Project 都是空）。修改（`update`）可改说明、分类、Project：改成通用资料时清掉 Project。
- Agent 看到的 = 本 Project 的简历 + 全部通用资料（`AssetRegistry.visibleTo(project)`）。`ResumeMatch` 的 `list` / `read` / `asset` / `latestAsset` / `current` 都带 Project；别的 Project 的键报「不属于这个 Project」，不存在的报「已删除或不存在」。`latestAsset`、`current` 只看本 Project 的简历。工具描述、`ApplyAgent` / `ResearchAgent` 写「本 Project 的简历」。
- 工作台上传位置的候选（`ApplicationRunner.status`）和校验（`files`）只用本 Project 能看到的原件；开始申请时 `resume_choice.asset` 不在本 Project 能看到的里就先排简历。
- 对话里发来的文件（决定 6）：`choose_resume(attachment)` 不登记原件。Session 里记 `resume_choice.attachment` + `file` + `session`；开始申请时从 Session 附件读出、放进这个 Job 的附件区当文件 1。工作台里直接把附件设为文件 1（附件本来就在 `uploads/<job>/attachments/`）。后续段读选定简历时按附件找，找不到就说已不在。
- 升级（决定 5、1）：启动时（`AssetRegistry` bean 创建时）对「分类」为空的行自动分类，并给简历按 Job 用过的 Project 归属（恰好一个才填）。只填空列，已填的不动；出错只记日志，不挡启动。

### A. 删除

- `DELETE /api/assets/{key}`：原件文件挪进 `storage/.trash/<时间>-asset-<键>/base/originals/<文件名>`，`base/assets.md` 删掉这一行，`RESTORE.md` 抄下这一行和怎么挪回去（`Trash.moveAsset`）。
- 历史照常可读：不改 `job.md`；`uploads/<job>/` 的副本不动。`read_resume` / 后续段读到已删的报「原件 <键>（<文件名>）已删除」；JobPage 只在候选里有这个键时才预选。
- 不加「在跑就不让删」之类的拦截。

### 资料页

- 「简历与原件」顶部 Project 下拉框（和 Dashboard 共用这次窗口里记住的选择）。上传时选「自动 / 简历 / 通用资料」，简历归属当前选中的 Project。
- 列表分三块：本 Project 的简历、未归属的简历（有才显示）、通用资料。每行：文件名、分类下拉框、Project 下拉框（只对简历）、说明、删除（一个确认框）。
- 接口：`GET /api/assets` 每条带 `category`、`project`；`POST` 加表单字段 `category`、`project`；`PATCH` 收 `description` / `category` / `project`；`DELETE`。

## 要改的文件

- 后端：`persistence/AssetRegistry`、`persistence/Trash`（`moveAsset`）、`config/SettingsConfiguration`（启动升级）、`api/AssetController`、`harness/runtime/ResumeMatch`、`SessionLoop` / `HarnessLoop` / `FollowupLoop`、`workflow/ApplicationRunner`、`tools/ResearchTools` / `ControlTools` / `FollowupTools`、`agents/ApplyAgent` / `ResearchAgent`。
- 前端：`api.ts`、`pages/Assets.tsx`、`pages/JobPage.tsx`（预选）、`styles.css`（少量）。
- 文档：本文件、`plan/master/memory.md`、`dev-plan.md` 索引行、`dev-log.md`、`dev-recoding.md`。

## Master Plan 回写（用户已用上面的回答确认）

- `memory.md` storage 目录的 `base/assets.md`、`projects/<id>/assets.md` 两行，「新建 Project」一节，「不做」一节，按上面的设计改写。

## 验证

- `backend/` `mvn -B clean verify` 退出码 0；`frontend/` `npm run build` 退出码 0。
- 用户在本机：
  1. 升级后打开资料页：被一个 Project 的 Job 用过的简历出现在那个 Project 下；其余简历在「未归属」，用下拉框指定 Project；成绩单等在「通用资料」。
  2. 删掉一份 → 列表消失，`storage/.trash/` 多一个 `…-asset-…` 目录。
  3. 选 B 上传一份新简历 → 只在 B 下；在 B 的工作台里排名只列 B 的简历（加通用资料）。同一文件在 A 再传 → A 下多一条。
  4. 在对话里发一份简历让 Agent 选定 → 这次申请用它，资料页不出现。
  5. 打开一个用过已删简历的岗位：「拟用简历：文件名」仍显示；重新开始申请时先排简历。

## 施工记录（2026-11-07）

- `AssetRegistry`：`Asset` 加 `category`、`project`；表格加「分类」「Project」两列，排在已有列（包括用户自己加的列）之后，旧文件列顺序不变。`register(…, category, project)`（分类为空按文件名猜）；去重按 SHA-256 + Project。`update`（说明 / 分类 / 归属，改成通用资料清掉 Project）、`remove`（先 `Trash.moveAsset` 再删行）、`visibleTo` / `findVisible`、`upgrade`、`guessCategory`。旧的 `register(name, type, content)` 登记成通用资料，`describe` 走 `update`。
- 升级：`SettingsConfiguration.assetRegistry` 建 bean 时调 `upgrade()`，只处理「分类」为空的行；扫所有 Project 的 Job 的 `resume_choice.asset`（定为简历、记 Project）和 `materials[].asset`（记 Project）；没被选过的按文件名 / 说明里的 简历 / 履历 / resume / CV 判断。简历用过它的 Project 恰好一个才填归属。出错只记日志。用临时数据跑过一次：3 行补上分类，第二次 0 行，其它列和表格前后的文字不变。
- `Trash.moveAsset`：`<时间>-asset-<键>/base/originals/<文件名>` + `RESTORE.md`（抄登记行和恢复方法）；文件已经不在时只写说明。
- `AssetController`：`POST` 收 `category`、`project`（Project 要存在）；`PATCH` 收 `{description, category, project}`；新增 `DELETE /api/assets/{key}`，返回回收站目录。
- `ResumeMatch`：`list` / `read` 带 Project，只看 `visibleTo`；`asset` 区分「已删除（或不存在）」和「不属于这个 Project」；跳过排名时自动选本 Project 最新的简历；`current` 只看本 Project 的简历，选定的原件不在本 Project 时算没排过。`choose` / `chooseInWorkspace` 收到 `attachment` 不再登记原件：`resume_choice` 记 `attachment`、`file`、`source`（session / workspace）；排名里可以写 `#附件编号`。新增 `chosenAttachment`（先找 Session 附件，再找申请的附件）和 `readChosen`（后续段读选定简历，原件删了报「原件 … 已删除」）。
- `HarnessLoop.chooseResume`：选的是附件时直接当文件 1（`StagedFile` 带附件编号），不写 `materials`。`list_resumes` / `read_resume` 传 `run.project()`。
- `ApplicationRunner.start`：`resume_choice.asset` 要在本 Project 能看到的里，否则先排简历；选的是对话里发来的附件时，重置上传区前读出、重置后存进这个申请的附件区当文件 1。上传位置候选和校验只用本 Project 能看到的原件。
- `SessionLoop`、`FollowupLoop` 传 Project；`ResearchTools` / `ControlTools` / `FollowupTools` 描述和 `ApplyAgent` / `ResearchAgent` / 开场提示写「本 Project 的简历」「只用于这次申请、不进资料库」。
- 前端：`api.ts`（`Asset.category` / `project`、`upload(file, category, project)`、`updateAsset`、`deleteAsset`）；`Assets.tsx`「简历与原件」重写：顶部 Project 下拉框（`lastDashboardProject` / `rememberDashboardProject`，和 Dashboard 共用），上传时选「自动 / 简历 / 通用资料」，三块表格（本 Project 的简历、未归属的简历、通用资料），每行分类下拉框、Project 下拉框、说明、删除（行内「确认删除 / 取消」，只问一次）。归属的 Project 已被删除的简历算未归属。`JobPage.tsx`：预选只在候选里有这个键时；上传位置拖进的新文件按文件名分类、简历归这个 Job 的 Project，候选只列本 Project 的简历和通用资料。`styles.css` 加 `.row.spread`。
- 验证：`backend/` `mvn -B clean verify` 退出码 0；`frontend/` `npm run build` 退出码 0。真实页面、真实 Agent 未运行。
