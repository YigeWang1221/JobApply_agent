# C031 · Claude / Notion 风格 UI 统一化与美化（Web + macOS App，绿色系）

对应 Master：[plan/master/ui.md](../../master/ui.md)、[plan/master/tracking.md](../../master/tracking.md)

状态：已施工，等用户在浏览器与 macOS App 里看（2026-11-25）

## 目标

将 JobApply 的 Web 前端（`frontend/`）与 macOS App 外壳（`appbuild/macos/JobApply/main.swift`）一次性统一美化为 **Claude（暖纸阅读画布与呼吸感）+ Notion（克制暖灰侧栏与清晰工具层级）** 风格，主强调色采用**森林绿 / 鼠尾草绿系**，并修复既有小瑕疵。

## 不做

- 不修改 Java 后端接口或业务逻辑。
- 不引入新的 npm UI / 图标 / 图表 / CSS 库，保持零额外依赖。
- 不触碰 `.env*` 与 `storage/` 隐私数据；保留 C025 的 `-webkit-text-security: disc` 中文输入法兼容实现、可拖拽分栏与所有既有交互。
- 不主动编写测试。

## 设计方向与五个切片

### Slice 1 · 设计系统、主题切换与基础控件底座
- 新增 `frontend/src/theme.ts`：管理 `"system" | "light" | "dark"` 三态主题（存 `localStorage` 键 `"jobapply:theme"`），同步更新 `document.documentElement.dataset.theme` 与 `shell.setTheme(mode)`。
- 扩展 `frontend/src/shell.ts`（`startDrag`、`setTheme`、`onBack` 注册）与 `frontend/src/main.tsx`（启动时 `initTheme()`，App 环境给 `<html>` 加 `.macapp`）。
- 升级 `frontend/src/styles.css` 顶层设计令牌：
  - **表面反转**：浅色模式下侧栏改为暖石浅灰（`#f3f2ec`），主画布改为暖白纸底（`#faf9f5`），卡片/浮层用纯白（`#ffffff`）配克制微阴影；深色模式采用暖炭灰（侧栏 `#181816`、主画布 `#1f1f1d`、卡片 `#262624`）。
  - **绿色系强调与图表**：森林绿 `--accent`（浅色 `#2d6a4f` / 深色 `#52b788`），漏斗 `--f1…--f6` 与主系列 `--s1` 采用绿色渐变梯队。
  - **基础控件统一**：按钮、输入框、文本域、下拉框（自定义箭头）、柔和聚焦环、细滚动条与微交互过渡。

### Slice 2 · macOS App 原生外壳融合
- `appbuild/macos/JobApply/main.swift`：
  - 启用 `.fullSizeContentView` + `titlebarAppearsTransparent = true` + `titleVisibility = .hidden`，与 Web 侧栏顶栏融为一体。
  - 设置 `minSize`（`940 × 640`）；修复 `center()` 覆盖 `setFrameAutosaveName` 的窗口位置记忆问题；同步 `window.backgroundColor` 与关闭 `webView` `drawsBackground` 消除闪白。
  - 重做启动页与报错页 HTML（自适应深浅色、绿色品牌标与旋转环、出错时提供「重试」与「在 Finder 中打开日志」按钮）。
  - 扩展 JS Bridge（`startDrag`、`setTheme`、`retryBackend`、`openLog`）。
  - 修复 `⌘R` 为刷新当前页（保留 hash 路由）；补齐「关于 JobApply」「设置… `⌘,`」「显示（放大/缩小/实际大小/全屏/重新加载）」「前往（返回 `⌘[`）」菜单。
- `frontend/src/pages/StorageLocation.tsx`：美化首次启动 `<FirstRun>` 欢迎卡片与加载占位。

### Slice 3 · 侧栏与全局导航重塑（Notion 风格）
- `frontend/src/chat/icons.tsx`：新增 `sun`、`moon`、`monitor` 线性图标。
- `frontend/src/App.tsx`：
  - `.macapp` 下为红绿灯留出安全区（`padding-top`），侧栏与页头空白区支持拖拽移动窗口。
  - 森林绿品牌徽章；`Project → 日期分组 → Session → 工作台` 三级视觉层次重塑；底部与收起态加入主题切换入口。
  - 修复 C030 遗留「Dashboard 页与 Project 行双高亮」问题（选中具体 Project 时不再高亮顶部 `Dashboard` 按钮）；在 `WorkspaceSummary` 字段支持范围内保留状态点。

### Slice 4 · 对话流与申请工作台（Claude 风格）
- `frontend/src/pages/SessionPage.tsx`、`frontend/src/pages/JobPage.tsx`、`frontend/src/chat/MessageList.tsx`、`Composer.tsx`、`ProcessBlock.tsx`、`SubRuns.tsx`：
  - 干净暖白阅读画布、柔和暖灰用户气泡、带品牌徽章的空对话引导态。
  - 输入框上方增加渐隐遮罩与悬浮阴影卡片；森林绿圆形发送按钮。
  - 思考与工具折叠块改为安静的折叠胶囊；`JobPage` 顶部元数据梳理呼吸感，右栏「需要你操作」与「参考信息」清晰分层。

### Slice 5 · Dashboard 与设置页统一（Notion 风格）
- `frontend/src/pages/Global.tsx`、`frontend/src/dashboard/Kpis.tsx`、`CheckBar.tsx`、`Board.tsx`、`AppsTable.tsx`、`frontend/src/pages/Settings.tsx`：
  - KPI 区改为 5×2 响应式网格（1280px 常见窗口从容舒展），增加微阴影与 `tabular-nums`。
  - 筛选条、7 列看板（增设浅灰列容器与白色卡片）、申请总表（行 hover 高亮与更清晰的阶段列）、设置页两层页签与表单卡片统一风格。

## 验证方式

- Web：`cd frontend && npm run build` 退出码 0。
- macOS App：`swiftc -typecheck -target arm64-apple-macos13.0 -framework AppKit -framework WebKit appbuild/macos/JobApply/main.swift` 退出码 0。

## 实际结果

- **Slice 1（设计系统与主题）**：新增 [frontend/src/theme.ts](../../../frontend/src/theme.ts)（`system / light / dark` 三态主题管理与 `useTheme`），更新 [frontend/src/shell.ts](../../../frontend/src/shell.ts) 与 [frontend/src/main.tsx](../../../frontend/src/main.tsx)（启动应用主题、`.macapp` 类标记与返回桥注册），在 [frontend/src/styles.css](../../../frontend/src/styles.css) 落地暖石浅灰侧栏（`--side-bg: #f3f2ec` / 深色 `#181816`）+ 暖纸主画布（`--bg: #faf9f5` / 深色 `#1f1f1d`）+ 森林绿强调色（`--accent: #2d6a4f` / 深色 `#52b788`）+ 绿色渐变漏斗、微阴影层级、定制 `<select>` 箭头、聚焦环与细滚动条。
- **Slice 2（macOS App 外壳融合）**：更新 [appbuild/macos/JobApply/main.swift](../../../appbuild/macos/JobApply/main.swift)（启用 `.fullSizeContentView` + `titlebarAppearsTransparent` + 隐藏标题、`minSize 940×640`、修复 `setFrameUsingName` 后再决定 `center()`、同步动态背景色与关闭 `drawsBackground` 防闪白、重做自适应深浅色的启动/错误页并加「重试启动 / 打开日志」按钮、新增 `startDrag / setTheme / retryBackend / openLog` 桥接、修复 `⌘R` 重载当前路径、补齐关于 / 设置 `⌘,` / 缩放 `⌘+`/`⌘-`/`⌘0` / 全屏 `⌃⌘F` / 返回 `⌘[` 菜单）；美化 [frontend/src/pages/StorageLocation.tsx](../../../frontend/src/pages/StorageLocation.tsx) 的 `<FirstRun>` 欢迎页。
- **Slice 3（侧栏与导航）**：[frontend/src/chat/icons.tsx](../../../frontend/src/chat/icons.tsx) 新增 `sun / moon / monitor`；[frontend/src/App.tsx](../../../frontend/src/App.tsx) 替换绿色 `J` 徽章、接入顶栏拖拽与折叠态红绿灯避让、底部与收起态加入外观切换、修复选中具体 Project 时与顶部 `Dashboard` 同时高亮的双高亮问题。
- **Slice 4（对话与工作台）**：[frontend/src/pages/SessionPage.tsx](../../../frontend/src/pages/SessionPage.tsx) 与 [frontend/src/pages/JobPage.tsx](../../../frontend/src/pages/JobPage.tsx) 统一顶栏拖拽与带品牌徽章的空对话引导，[frontend/src/styles.css](../../../frontend/src/styles.css) 调整暖灰用户气泡、输入框渐隐遮罩与悬浮阴影、森林绿发送按钮、轻量化工具卡与右栏 `--side-bg`，`JobPage` 对「要你回答」「上传位置」加 `.fold-action` 视觉区分。
- **Slice 5（Dashboard 与设置页）**：[frontend/src/dashboard/Kpis.tsx](../../../frontend/src/dashboard/Kpis.tsx) 与 [frontend/src/styles.css](../../../frontend/src/styles.css) 改为 5 列双行网格并高亮 `Offer`（绿）/ `待追踪`（琥珀），[frontend/src/pages/Global.tsx](../../../frontend/src/pages/Global.tsx) 与 [frontend/src/pages/Settings.tsx](../../../frontend/src/pages/Settings.tsx) 接入顶栏拖拽，看板列容器、申请总表行 hover、两层页签层级同步统一。
- **验证**：`cd frontend && npm run build` 退出码 0（`tsc -b && vite build`，323 modules）；`swiftc -typecheck -module-cache-path /tmp/swift-module-cache -target arm64-apple-macos13.0 -framework AppKit -framework WebKit appbuild/macos/JobApply/main.swift` 退出码 0。`appbuild/build.sh macos` 重新打包与浏览器/App 实跑预览未运行（等用户试）。
