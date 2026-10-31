# C006-fix · C006 的修补：回复里附文件和图片；申请工作台改成整屏

- 依据：用户测试反馈（2026-10-23）：辅助投递的界面太小；回复只能写文字，不能附文件和图片。用户选定（2026-10-23）：图片发给模型看；附的文件只放这个 Job（Session 的放这个 Session），不进资料库；申请工作台和 Session 对话都要能附。
- 目标：两个输入框都能附文件、图片（点按钮、拖进来、粘贴截图）；模型能看到图片；申请 Agent 能把附的文件上传到表单。
- 不做：附件进资料库；读取 PDF / Word 正文给模型；删除已发出的附件；模型不支持看图时自动降级（失败要看得见）。

## 1. 申请工作台整屏（只改前端）

- 和 Session 页同一套布局：整屏高度，中间消息流（约 720px 居中），底部固定输入框；右侧一栏「现在要你做的事」：问题、上传位置、计划、报告、用量、事件（可收起，窄屏变按钮弹出）。
- 底部输入框就是回复：文字 + 附件一起发，问题的回答和上传位置的选择仍在右栏，发送时一并带上。没在等用户时输入框禁用并写明原因。

## 2. 接口

- 上传：`POST /api/jobs/{jobId}/application/attachments`、`POST /api/sessions/{id}/attachments`，multipart 字段 `file`。返回 `{ id, name, size, contentType, image }`。单个不超过 20 MB；`SecretFileRules` 判为密钥类的文件名拒绝。
  - Job：存进 `uploads/<job-id>/`（申请 Agent 的上传暂存区，MCP 只能读这里）。
  - Session：存进这个 Session 目录下的 `attachments/`。
- 读取（缩略图、点开看）：`GET` 同路径 `/{attachmentId}`，原样返回文件。
- 发送：Session 的 `POST /messages` body 加 `attachments: string[]`；申请的 `POST /reply` body 加 `attachments: string[]`。可以只有附件没有文字。
- 视图：用户消息（`ChatMessage` role=user）加 `attachments: [{ id, name, contentType, image, url }]`。

## 3. 给模型

- 用户那一条 Turn 记下附件（id、名字、类型）。
- 发给模型时：文字后面追加一行「用户附了文件：#id 名字（类型）」；图片（png / jpeg / webp / gif）同时作为图片输入放进这条用户消息（Spring AI 的 `Media`，施工时核实 2.0.1 两种格式都支持）。
- 申请 Agent：附的文件进 `AgentRun.files`（和上传位置选的文件同一套编号），模型可以直接用 `browser_file_upload` 上传。
- 模型不支持看图时请求会失败：照常把错误显示给用户，不静默去掉图片。
- 为了不让每轮都重发很多图片：只有最近 3 条带图片的用户消息发图片，更早的只留文字那一行。

## 4. 前端输入框（两个页面共用 `chat/Composer`）

- 回形针按钮选文件；拖进输入框；粘贴截图。选中后立即上传，输入框上方显示附件小卡（图片显示缩略图，其它显示名字和大小），可移除；上传中不能发送。
- 已发出的用户消息下面显示附件：图片缩略图（点开新标签页看原图），其它文件一行名字。

## 怎么确认能用

- `mvn -B clean verify`、`npm run build` 通过。
- 用户在本机：申请工作台占满整屏；在两个页面各附一张截图问模型「图里写了什么」，模型能答出来；在申请里附一个 PDF，Agent 能把它上传到表单。

## 实际结果

### 前端（1、4 节）

- 申请工作台（`pages/JobPage.tsx`）改成和 Session 页同一套布局：`App.tsx` 对 job 路由也加 `main full`；标题行放公司 · 岗位、链接（岗位 / 官方 / 回到 Project / 发现它的 Session）、核验和状态、没有申请时的「开始申请」、右栏开关「要你做的事」（等你时没展开会高亮）。中间复用 `MessageList`，底部复用 `Composer`。
- 右栏（复用 `.jobsside`，1100px 以下变成弹出）：状态 / 说明 / 出错 / 浏览器断开提示、问题、上传位置（原来的选原件和拖入）、计划、报告（ready 或已有报告时）、用量、事件。原来的 `ReplyPanel` 去掉，问题回答和上传位置选择提到页面里，按同一次等待（`replyKey`）保留。
- 底部输入框就是回复：发送时一并带上回答、上传位置、`attachments`；校验照旧（必填问题、必填上传位置；ready 时必须写修改说明），还差什么写在输入框上方一行；ready 时下方保留隐私提示。没有申请 / 运行中 / 其它状态时输入框禁用、占位文字写原因；运行中发送键是停止。发送失败用轻提示显示后端原话，文字和附件不清空。
- 附件：新增 `chat/useAttachments.ts`（选中即上传、20 MB 前端检查、图片 object URL 预览、移除、清空）；`Composer` 加可选 `attach`，有它才出现回形针、拖入、粘贴截图，输入框上方显示小卡（缩略图或名字 + 大小、上传中转圈、失败写原因、可移除）；上传中不能发送；只有附件也能发。Composer 另加 `allowEmpty`、`blocked`、`lockedReason`、`sendLabel`。
- 已发出的用户消息：`ChatMessage` user 加 `attachments`，`MessageList` 在气泡下显示图片缩略图（点开新标签页）和文件行；Session 页的待确认消息、重发、重试都带上那一条的附件。
- `api.ts`：`Attachment`、`SentAttachment`、`MAX_ATTACHMENT_BYTES`、`uploadFile` 小工具（原件上传也改用它）、`sessionAttachment`、`sessionAttachmentUrl`、`jobAttachment`、`sendMessage(id, text, attachments)`、`ReplyBody.attachments`。
- 验证：`npm run build` 通过。浏览器里实际点一遍：未运行。

### 后端（2、3 节）

- 存储：新增 `persistence/AttachmentStore`，一个附件一个目录 `<dir>/<id>/<原文件名>`，id 是 12 位十六进制；只留文件名最后一段，`SecretFileRules` 判为密钥类、空文件、超过 20 MB 都是 400；类型按扩展名判断（四种图片自己判断，其它用 JDK 的类型表）。Job 的在 `uploads/<job-id>/attachments/`，Session 的在 `sessions/<id>/attachments/`（`StorageLayout` 加两个路径）。`UploadArea.clear` 不再清 `attachments/`（关卡 B 后对话还要显示），新加 `reset` 在重新开始申请时全部清空。
- 接口：`POST/GET .../application/attachments[/{id}]`（没有申请线程 404）、`POST/GET /api/sessions/{id}/attachments[/{id}]`；上传返回 201 `{ id, name, size, contentType, image }`；读取原样返回、inline，带 `nosniff` 和 `Content-Security-Policy: sandbox`，不存在的 id 404。`LocalRequestGuard` 对这两个上传路径放行 multipart。`/messages` 和 `/reply` 的 body 加 `attachments`，有附件时文字可以为空，未知 id 400。用户的 `ChatMessage` 加 `attachments: [{ id, name, contentType, image, url }]`，Agent 的为 `[]`。
- 给模型：`AgentRun.Turn` 加 `attachments`（id、名字、类型、申请里的文件编号），旧记录没有时为空；Session 的 `transcript.jsonl` 也存这一项。`Conversation.messages` 在文字后每个附件加一行「用户附了文件：#id 名字（类型）」，申请里再写 `browser_file_upload` 的文件编号；最近 3 条带图片的用户消息把图片作为 Spring AI `Media`（byte[]）放进 `UserMessage`，更早的只留文字并注明没再发；附件文件不见了时抛出。已核实 2.0.1：`OpenAiChatModel` 把 `UserMessage.getMedia()` 里的 `image/*` byte[] 转成 data URL 的 `image_url` 片段，`AnthropicChatModel` 转成 base64 的 `ImageBlockParam`。模型不支持看图时照常作为运行失败显示。
- 申请 Agent：回复里的附件接着加进 `AgentRun.files`（`StagedFile.attachment` 记附件 id，`assetKey` 为空），`stagedPaths` 按附件目录解析，所以 `browser_file_upload` 用编号就能上传；选原件写 `job.md` 的 materials 时跳过附件（不进资料库）。
- 验证：`mvn -B clean verify` 通过（611 个单元测试 + IT，BUILD SUCCESS）。真实模型看图、真实表单上传附件：未运行。
