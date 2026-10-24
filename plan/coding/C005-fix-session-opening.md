# C005-fix · 新建 Session 不再自动写项目总结

- 依据：用户（2026-10-24）：每开一个新 Session，Agent 都先写一大段项目状态（候选池、停在提交前的、已投递的、已关闭的），再问「今天想先做哪个」。这些内容该出现在 Project 主页 / Dashboard，不该出现在对话里。
- 现状：`SessionRunner.create` 新建时插入 `OPENING`，后台调一次模型，让它先调 `list_jobs` 再写开场。这是 `plan/master/ui.md`「新建 Session」的早期设计。
- 不做：改 Dashboard 的版式；改 Agent 别的提示词。

## 改法

1. **新建 Session 是空白对话**：去掉 `OPENING` 和新建时的自动运行，不调模型。用户发第一条消息时 Agent 才开始跑；它需要项目状态时自己调 `list_jobs`（`ResearchAgent` 提示词里已经有）。
2. **前端空白页**：对话区没有消息时显示一行固定提示（例如「说说想找什么岗位、投哪几个，或者更新一下面试情况」）和输入框，不显示「正在思考」。
3. **项目状态放在 Project 主页**：核对 Project 页 / Dashboard 有没有这几块：停在提交前等你审阅的申请、「该查看了」的已投递申请（查看日期到了的），以及候选池待投数。缺哪块就在现有组件里补一小块（复用 `FollowUps` / `Upcoming` / `LeadPool` 等），不新建页面。
4. 回写 `plan/master/ui.md`「新建 Session」：改成空白对话、项目状态看 Project 主页；`tracking.md`「匹配反馈与提醒」和 `workflow.md` 遗漏检查第 10 项里「新建 Session 的开场里提醒」改成「Project 主页里提醒」（用户本次指令即确认）。
5. 已有 Session 里已经存下的开场消息不动。

## 怎么确认能用

- `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -B -q clean verify` 退出码 0；`frontend/` 下 `npm run build` 通过。
- 依赖开场的测试按新行为改（新建后没有模型调用、没有消息）。
- 用户在本机新建一个 Session：对话是空的，没有调模型；Project 主页上能看到等审阅、该查看了的申请。

## 实际结果

（施工后写）
