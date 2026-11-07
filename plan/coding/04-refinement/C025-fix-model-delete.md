# C025-fix · 模型设置删不掉：删提供方 / 去掉模型时清掉相关用途分配

- 对应：[C025](C025-settings-ui-polish.md)；Master [ui.md](../../master/ui.md)「模型设置」。
- 依据：用户（2026-11-05）：「旧版模型设置有删不掉的情况，看新版还有没有。」「如果在模型用途设置里，就把用途列表里相关的清掉，让用户重新设。」
- 状态：**已确认，已施工**（2026-11-05），等用户在本机试。

## 现象

旧版和 C025 新版一样：提供方或模型只要还被某个用途（主模型 / 辅助 / 交叉核验）使用，删除就是 409，错误显示在卡片底部、离「删除」按钮很远；页面上不显示谁在用它。用户的 `models.yaml` 只有一个提供方，三个用途都指向它，所以一定删不掉。去掉模型时，失败的那个模型已从页面列表里消失，刷新又回来。想先清用途再删时，若主模型的最近一次测试失败过，「保存分配」也被工具调用检查挡住。

## 根因

- `ModelSettingsFile.java` `Settings.withoutProvider`（原 152-156）：在用就抛 `InUseException`，`ModelSettingsService.deleteProvider`（原 153-157）转成 409。
- `ModelSettingsService.upsertProvider`（原 109-114）：去掉的模型在用就 409。
- `ModelSettingsService.assign`（原 286-293）：只要传了主模型就查工具调用测试，主模型没变也查。
- 前端 `ModelSettings.tsx`：错误放在卡片底部（原 309）；后端给的 `usedBy` 没用上；× 只改本地列表。

## 修法

- 后端：`withoutProvider` 删提供方时一起去掉指向它的分配；`upsertProvider` 用新的 `withoutAssignmentsOutside` 去掉指向被删模型的分配；删掉 `InUseException`。`assign` 只在主模型换成新的时检查工具调用测试，没变或清空都不查。
- 前端：提供方和模型旁显示「用作 主模型、…」；删除和去掉在用模型前，确认框说明会清掉哪些用途；卡片错误放在「删除」旁；已保存模型点 × 立即保存（失败时留在列表里）；清掉的用途在「用途分配」里提示「随删除清空了，请重新选一个再保存」。

## 验证

- `frontend/` `npm run build`；`backend/` `mvn -B clean verify`（改了原来断言 409 的两个测试：`ModelSettingsFileTest`、`ModelSettingsControllerTest`）。
- 浏览器里删提供方 / 去掉在用模型：等用户试。

## 补：OpenCode Go 要求 `x-opencode-session`（2026-11-05）

- 现象：OpenCode Go（预设 `opencode-go`）对没有 `x-opencode-session` 头的请求返回「400: Request is missing x-opencode-session and cannot be routed efficiently.」。
- 参考：DSH 插件 github.com/beihzb/dsh-opencode-session-header（用户提供，2026-11-05；本次没有拉取仓库，提交号未记录）：AsyncLocalStorage 存会话 id，fetch 中间件按 host 补头。
- 修法（不用用户配置）：
  - `config/OpenCodeSession.java`：`ScopedValue` 存这次对话的 id；OkHttp 拦截器只对 host 为 `opencode.ai` 或 `*.opencode.ai` 的请求补头，请求里已有同名头（用户在自定义请求头里写的）时不动。`ModelFactory` 给 OpenAI / Anthropic 两种客户端都挂这个拦截器。
  - 对话 id：调研 Session `jobapply-session-<sessionId>`；申请 `jobapply-apply-<jobId>`；后续段 `jobapply-followup-<jobId>`（查状态 C026 是发进后续段的消息，同一个）。在三个 Runner 调 loop 的地方绑定，同一个对话每一轮、每次重试都一样。
  - 没有对话时（测试连接、拉模型列表 `ModelCatalogClient`、导入时的抽取）用 `jobapply-default`。
  - 并行：同步调用的拦截器和 SDK 重试都在调模型的线程上跑；`ScopedValue` 不传给别的线程，Worker 之间不串。
- 验证：`backend/` `mvn -B clean verify`（见 dev-log）。真的连 OpenCode Go：等用户试。
