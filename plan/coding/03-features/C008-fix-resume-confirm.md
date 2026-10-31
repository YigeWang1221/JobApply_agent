# C008-fix · 简历在对话里和用户定好，申请 Agent 直接上传

- 依据：用户（2026-10-23，真实跑通小米 Agent工程师时）：「MCP 调用的话，你的下拉选择框其实我是看不到的……应该是对话的 Session 和用户确定好要用哪一份简历去投递（已经保存的简历，或者他改好的某一份），聊确定了之后，Agent 去做投递、做下面的填写。」「尽量不要写死，用 prompt、skill 约束。」
- 现状（C008）：对话里排名后直接 `choose_resume` 记下推荐的那份；申请 Agent 到上传位置时 `ask_user` 开上传位置，工作台下拉框默认选中它，用户再点一次。用户在下拉框里看不到这份简历是什么，又多一次确认。
- 目标：简历只在对话里定一次。排名 → 用户说用哪份（推荐的、别的已保存的，或者在对话里发一份改好的）→ 记下 → 开始申请；申请 Agent 到简历上传位置直接上传这一份，不再问。

## 怎么做

**提示词 / skill（流程都在这里）**
- ResearchAgent「简历排名」：先排名、在对话里给表和建议，问用户用哪份（推荐一份）；用户确认（「就用推荐的」「用第二份」「直接投」都算）或者在对话里发来改好的文件后，才 `choose_resume`，然后 `start_application`。没确认不开始。
- ApplyAgent「上传」：「可上传的文件」里已经有合适的（例如用户在对话里定好的简历）就直接 `browser_file_upload`，不再问；没有合适的才用 `ask_user` 的 files。
- skill apply-form：一句同样的话。

**代码（只做提示词做不到的两处）**
1. `ApplicationRunner.start`：Job 的 `resume_choice` 里有原件时，开始时就把它放进上传区，成为「文件 1」（和用户在上传位置选了它走同一段 `stage`），开场写「用户在对话里定了用这份，是文件 1」。原因：上传只能用上传区里的编号（安全线，不放宽），文件不放进去模型就只能再问。
2. `choose_resume` 加可选 `attachment`：用户在这个 Session 里发来的文件编号 → 登记成新原件（`AssetRegistry.register`，和「资料与简历」上传同一个入口），作为选中的那份。原因：改好的简历要能直接用，不让用户再去资料页上传一次。只发了改好的那份时 `ranking` 可以不写。
- 不做：系统判断「用户确认了没有」的字段或拦截（交给提示词）；工作台下拉框默认选中保留（模型仍可能开上传位置，比如别的材料）。

## 怎么确认能用

- `mvn -B clean verify`、`npm run build` 通过。
- 本机真实跑：对话里排名 → 用户说用哪份 → 开始申请 → 申请 Agent 到简历上传位置直接上传，不再弹上传位置。

## 实际结果

2026-10-23 施工：

- `ApplicationRunner.start`：`resume_choice.asset` 在原件清单里时，`uploads.reset` 之后用同一段 `stage` 把它放进上传区（位置「简历」），成为文件 1；开场写「用户在对话里定了用 X（键），已经是文件 1，到简历上传位置直接 browser_file_upload 文件 1，不用再问。填项目、实习、教育和描述之前先 read_resume 读文件 1 的原文」。
- `choose_resume`：新可选参数 `attachment`（Session 里用户附件的编号，`#` 可带可不带）→ 读附件字节，`AssetRegistry.register` 登记成新原件（重复的直接用已有那份），说明写「用户在对话里发来的简历（Job、公司 · 岗位）」，作为选中的那份；有 `attachment` 时 `ranking` 可以不写。`required` 只剩 `job`。结果文字改为「申请开始时这份会放进上传区（文件 1），申请 Agent 直接上传」，不再说「现在用表告诉用户」。
- 工具说明：`choose_resume` 写明「在对话里给了排名和建议、用户说了用哪份（或发来改好的文件）以后才调用」；`start_application` 没有排名时的提示改为「告诉用户排名和建议、问用哪份；定了以后 choose_resume」。
- `ResearchAgent`「简历排名」：排名 → 表 + 建议 + 问用哪份（说明推荐哪份）→ 用户确认或发来文件后才 `choose_resume` → `start_application`；「系统不改简历：用户可以自己改好后直接在对话里发来」。
- `ApplyAgent`「上传」：可上传的文件里已经有合适的（开场会说是第几个），直接上传，不再问；没有才 `ask_user` 的 files。skill apply-form 同一句。
- 工作台下拉框默认选中保留，没改前端。
- 验证：`mvn -B -q clean verify` 退出码 0（surefire 621、failsafe 1，没有改测试、没有新增测试）。前端没改，`npm run build` 未运行。真实 Chrome、真实模型：未运行。
