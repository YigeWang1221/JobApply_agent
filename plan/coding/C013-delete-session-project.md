# C013 · 删除 Session / Project

- 依据：用户（2026-10-24）「好像还是做不到 delete session/project。参考那两个 repo 在 subagent 上 design 然后执行」。参考结论见 [research-agent-design.md「删除会话 / 项目」](../reference/research-agent-design.md)。
- 现状：界面和接口都没有删除；Session、Project 只能改名。
- 状态：已施工，等用户试（2026-10-24）。

## 目标

侧栏上能删一个对话（Session）或一个 Project。删错了能找回来，删的时候只问一次。

## 不做

- 不做恢复接口、回收站页面、自动清空回收站（手动挪回去就恢复，见下）。
- 不删 Job 本身（除非删的是它所在的 Project），不改 Job 记录里的 Session 引用。
- 不碰 `base/`、`settings/`、`playbook/`、`browser/`、`review/`、别的 Project、`example.data/`。
- 不加二次确认、不加「输入名字确认」。

## 语义和理由

**软删进回收站。** 删除 = 把目录原子地挪进 `storage/.trash/<yyyyMMdd-HHmmss>-<session|project>-<id>/`，里面照原来相对 storage 的路径摆放（例如 `projects/<p>/sessions/<s>/`），再写一份 `RESTORE.md` 列出挪了哪些路径。理由：本地文件，挪目录比标删除位简单，所有读路径不用改；删错了把文件夹挪回去就好，所以确认框只问一次也不怕。

**删 Session**

- 只挪 `projects/<p>/sessions/<s>/`（对话、附件、读过的网页）。
- 它发现 / 涉及的 Job **留在 Project 里**：Job 属于 Project，Session 只是发现或碰过它。Job 的 `discovered_in` 和事件里的 `session` 照旧，当历史。
- 已删除的 Session 仍是合法的「历史 Session」：台账写入（`JobLedger`）和回复申请线程接受回收站里的 Session，照样记原来的 id；只有新建候选（`addLead`）和开始新的申请线程要求 Session 还在。不然这个 Session 发起的申请就再也推进不了。
- 回收站里的编号不复用：新 Session、新 Job 编号时把回收站里的也算上，旧 Job 的「发现于」不会指到新对话，恢复时也不撞。
- 在跑时**不拒绝**：请它停下（当前这次工具调用之后），停下时挪走，接口回 202 `deleting`。理由：409 要用户先按停止、等它停、再删一次，正是 AGENTS.md 说的「盯着屏幕等待」；停下再挪不会和正在写的对话交错。等删除期间发消息、重新生成 409。
- 这个 Session 发起的申请线程不受影响；回报时 Session 不在了就不写（`SessionRunner.report` 原本就检查）。

**删 Project**

- 有对话在跑、或者它的 Job 有申请在跑 / 排队时 409，说是哪几个；别的 Project 继承它时 409。理由：范围大，牵涉申请线程和浏览器标签页，先停是一次明确的动作；在跑的写着同一批文件。
- 挪走整个 `projects/<p>/`，以及它的 Job 的 `runs/<job>.json`、`uploads/<job>/`（这两处按 job id 放在全局目录）。
- 挪完按剩下的 Project 重新生成全局台账 `jobs-index.md`、`exports/applications.csv`（都是生成文件）。
- 检查和挪走在 `SessionRunner` 和 `ApplicationRunner` 两把锁里做，中间不会有新运行开始。

**恢复（手动）**：打开 `storage/.trash/<那一次>/`，把里面的 `projects/`（以及 `runs/`、`uploads/`）挪回 storage 下同样的位置，刷新页面。Project 恢复后全局台账在下一次写台账时重新生成。

## 接口

- `DELETE /api/sessions/{id}` → 200 `{id, status: "deleted"}`；在跑时 202 `{id, status: "deleting"}`；没有 404。
- `DELETE /api/projects/{id}` → 200 `{id}`；没有 404；在跑 / 被继承 409。
- 写接口照 `LocalRequestGuard`：DELETE 也要 `Content-Type: application/json`（前端 `request()` 本来就带 `{}`），不用改防护。
- 发 `changed` 事件，打开着的页面跟着刷新。

## 界面

- 侧栏 Project 行、Session 行悬停时，改名笔旁边多一个垃圾桶。点了弹一个确认框：写清楚挪走什么、留下什么（删 Session：Job 留在 Project 里；删 Project：base、设置、playbook、别的 Project 不动；在跑时会怎样），只有「删除 / 取消」。409 的原因显示在框里。
- 删的是当前页（或当前 Session 所在的 Project）时回到全局。
- 引用已删除 Session 的地方不崩：打开已删的 Session 显示「会话已删除」；Job 页「发现它的 Session」换成「会话已删除」，发消息 / 开始申请改用后端挑的可用 Session（`JobDetail.session`：申请线程的、发现它的，都删了时用 Project 最新的一个）；候选池「发现于」显示「会话已删除」；看板「开始申请」同样换 Session。

## 怎么确认能用

- `backend/`：`JAVA_HOME=… mvn -B -q clean verify` 通过。新增一条聚焦的测试（临时 storage、虚构数据）：删 Session 只挪这一个目录、Job 还在且还能更新、编号不复用；删 Project 只挪这个 Project 和它的 Job 的 runs / uploads，base、settings、别的 Project 一个字节不变，全局台账没有它了；有申请在跑时删 Project 409。
- `frontend/`：`npm run build` 通过。
- 真实应用：未运行（用户的应用在 8080 上）。用户重新构建后在侧栏试。

## 实际结果

2026-10-24，按上面施工。路径相对 `backend/src/main/java/jobapply/`。

- `persistence/Trash.java`（新）：`moveSession`、`moveProject`，在 `StorageLock` 里挪目录（同盘原子移动）、写 `RESTORE.md`；Project 挪完重新生成全局台账。挪之前检查源在 storage 下、目标在这次的回收站目录下。
- `persistence/StorageLayout.java`：`trashDir()`、`trashEntries()`、`trashedSession()`、`trashedSessionIds()`、`trashedJobIds()`。
- `persistence/SessionRepository.java`：`existsOrDeleted()`；新 Session 编号算上回收站里的。`JobRepository.allJobIds()` 同样。
- `persistence/JobLedger.java`：已有 Job 的写入接受已删除的 Session；`addLead` 要求 Session 还在；`session.md` 不在时不更新它。
- `workflow/SessionRunner.java`：`delete()`（在跑时请停、结束时挪走，`Deletion.DELETED / DELETING`）、`deleteProject()`（继承 / 在跑检查后在申请线程的锁里挪走）；删除期间 `send`、`regenerate` 409。
- `workflow/ApplicationRunner.java`：回复已有线程时接受已删除的 Session（`requireKnownSession`）；`whileIdle(jobIds, action)`：这些 Job 都不在跑 / 排队时在锁里执行，否则 409。
- `api/SessionController.java`、`api/ProjectController.java`：两个 DELETE。`api/JobController.java`：`JobDetail` 加 `session`、`deletedSessions`。
- 前端：`DeleteConfirm.tsx`（新，确认框）、`App.tsx` 侧栏垃圾桶和删后跳转、`SessionPage.tsx` 404 显示「会话已删除」、`JobPage.tsx`、`dashboard/Dashboard.tsx`、`dashboard/LeadPool.tsx` 处理已删除的 Session、`chat/icons.tsx` 加 `trash`、`styles.css` 加确认框样式、`api.ts` 两个接口和 `JobDetail` 两个字段。
- 测试：`backend/src/test/java/jobapply/workflow/DeleteSessionProjectTest.java`。
- 验证：见 `dev-log.md` 本条。
- 偏差：界面上没做 ⋯ 菜单，垃圾桶和改名笔并排（侧栏已经是这个样式，少一层点击）。`plan/master/memory.md` 的 storage 目录树还没加 `.trash/` 一行：master 改动，等用户确认后回写。
