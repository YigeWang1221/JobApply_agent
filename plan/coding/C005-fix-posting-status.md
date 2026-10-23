# C005-fix · 岗位下线的状态；调研时进详情页看要求和招聘状态

- 依据：用户测试反馈（2026-10-23）：碰到岗位下线，界面上没有这个状态，不知道怎么标；搜索时要进具体页面看岗位要求和招聘状态。
- 不做：定时自动检查候选池里的岗位还在不在；从下线岗位推荐替代岗位。

## 做了什么

- 状态：候选池加 `lead_status = closed`（已下线），`new / shortlisted → closed`，`closed → new`（重新开放）。已转申请的加结局 `posting_closed`（岗位下线），任何阶段都可以这样结束。
- 接口：`POST /api/jobs/{id}/posting-closed`（`{ note? }`）：候选标 closed；已转申请的以 `closed:posting_closed` 结束，申请线程在跑时先请它停下。
- 对话 Agent：`update_lead` 的 status 可以是 closed；`record_status` 的 outcome 可以是 posting_closed；`list_jobs` 可以按 closed 筛选，「lead」不再包括已下线的。
- 提示词（撒网调研）：挑出结果后**进岗位自己的详情页**读 JD（web_fetch，读不到用浏览器），看要求（学历、毕业时间、方向、城市、经验、截止日期）和**还在不在招**（已下线、停止招聘、截止已过、404 或跳回列表）；下线的不放进候选池，note 写关键要求和招聘状态。核验时发现下线，标 closed。`add_lead` 的工具说明同步。
- 界面：候选池每行多「已下线」按钮，已下线的显示红色「已下线」和「重新开放」；Project 页「申请」每行多「岗位下线」；工作台头部「岗位下线了」。已结束的申请显示结局（「已结束 · 岗位下线」等）。

## 验证

- `mvn -B clean verify` 退出码 0（surefire 611、failsafe 1）；`npm run build` 通过。真实调研里模型会不会真的进详情页、标下线：未运行，等用户试。
