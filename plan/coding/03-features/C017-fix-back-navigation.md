# C017-fix · App 里没有「返回上一页」

- 对应：[C017](C017-macos-app.md)（Swift 窗口外壳）；[C023](../04-refinement/C023-ui-polish-resizable.md)（侧栏样式）。
- 依据：用户反馈（2026-11-07）：App 里没有返回按钮，每次都要自己重新定位；网页版靠浏览器的返回键，所以之前没做。每次重新打开或退出时，页面栈要清空。
- 状态：已施工，等用户在 App 里试（2026-11-07）。

## 目标

- App 里能回到上一页：侧栏顶部一个返回按钮（侧栏收起时在窄栏里），快捷键 ⌘[。没有上一页时按钮置灰。
- 每次窗口加载（启动 App、设置页「重启后端」、⌘R 重新加载）都从空栈开始，回不到上一次打开时的页面。
- 返回跳过已删掉的页（Project / Session / 工作台），一直退到还在的那页；都没了回「全局」（用户决定，2026-11-07）。
- 不做：前进按钮、触控板两指滑动返回、菜单项、浏览器里的返回按钮（用户决定，2026-11-07：只要返回）。

## 改动

- `frontend/src/route.ts`：内存里一份页面栈（最多 50 条）。`hashchange` 时进入的页和栈顶不同就压栈；`back()` 先弹栈，再 `location.replace` 到新的栈顶，所以返回不会再压一条，也不往浏览器历史里加。`useCanGoBack()` 给按钮用。不看 `history.length`。
- 跳过删掉的页：`route.ts` 的 `back()` 弹栈后逐条问「还在不在」，不在就继续弹，栈空了放「全局」；`pruneStack()` 在侧栏列表刷新时把已删掉的页从栈里去掉（当前页不动、相邻重复合并），按钮跟着置灰。`App.tsx` 的 `listedGone()` 按侧栏已读到的 `projects` 判断：Project、Session 看列表；工作台在某个 Session 的 `jobs` / `workspaces` 里就算在，不在列表里（Session 删了 Job 还在、只从 Dashboard 打开过）时调已有的 `GET /api/jobs/{id}`，只有 404 算删了（网络错误当还在）。没加后端接口。
- `frontend/src/App.tsx`：`BackButton`（只在 `shell.available()` 时显示）放在侧栏顶部「收起侧栏」左边和收起后的窄栏里；⌘[ 只在 App 里接管。
- `frontend/src/chat/icons.tsx` 加 `back` 图标；`styles.css` 侧栏顶部按钮组间距。
- `main.swift` 不改。

## 为什么清空不用改外壳

- 栈只在页面的 JS 内存里。`loadUI()`（启动、重启后端、⌘R）每次都加载不带 hash 的 `http://127.0.0.1:8088/`，页面从「全局」开始、栈是新的。
- 关掉窗口就退出 App（`applicationShouldTerminateAfterLastWindowClosed`），没有「进程还在、窗口重开」的情况；WKWebView 不恢复上次会话。
- WKWebView 自己的前进后退列表（含启动时的提示页）没有入口：没开 `allowsBackForwardNavigationGestures`，菜单里没有 `goBack`，返回只走前端的栈。

## 验证

- `frontend/` `npm run build` 退出码 0。
- Swift 没改，没重新打包；在 App 里点返回、⌘[、重启后端后置灰：未运行，等用户试。

## 已知

- 侧栏列表还没读到时（启动后几百毫秒）不跳过，都当还在。
