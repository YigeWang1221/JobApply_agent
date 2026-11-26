# F. B1 再查：GUI 插件能不能把 OpenCode Desktop 整个盖成我们的界面（只读调研）

日期 2026-11-25。源码：`anomalyco/opencode` 标签 `v2.4.1`（发版提交 `6f05140`，2026-11-23），浅克隆于 scratchpad，未安装、未构建。已用 `/Applications/OpenCode.app`（`CFBundleShortVersionString` = 2.4.1）的 `app.asar` 核对关键字符串（`gui-experimental`、`session.panel.end`、`window.bottom`、`data-plugin`、主进程模块报错文案）都在，说明安装包与该标签一致。未读用户的 opencode 配置、数据、日志。

下文路径相对仓库根。`plugin/` = `packages/plugin/src/gui/`，`app/` = `packages/app/src/`，`desktop/` = `packages/desktop/src/`。

## 0. 直接回答

- **公开 API 只能「往里加」，不能「换掉」**：能加侧栏面板、4 个 Slot、命令、菜单项、设置页、标题栏按钮、composer 上方的胶囊、代码块渲染器、主题、CSS、内嵌网页。路由表是写死的，会话列表、对话时间线、模型设置这些核心界面都不是插件，也没有注册点可以替换。
- **但插件代码不在沙箱里**：窗口里插件和应用同一个 `document`，`Style` 是不加作用域的全局 CSS；主进程里插件拿得到真的 `electron` 模块和真的 `BrowserWindow`。所以「用 CSS 藏掉内置界面 + 全窗口内嵌我们的 React 页」或「主进程直接让窗口加载我们的页面」在技术上**做得到**，只是完全在 API 约定之外，每次 Desktop 更新都可能坏。
- 「替换内置面板」其实只是：插件用 `ctx.layout.close("context:main", session)` 把内置 Context 标签从侧栏条里关掉，再打开自己的。内置插件「总是运行」，用户随时能从 + 菜单把它打开回来。
- 结论：不 fork 时，**正规做法**的上限是「OpenCode 的对话界面 + 我们的侧栏工作台 / 设置页 / 胶囊」，界面要求一条都满足不了；**黑科技做法**能做到看起来全是我们的界面，但那时 OpenCode Desktop 只剩一个外壳和自动更新器，换来的是脆弱性，价值和 B2（我们自己的壳 + `opencode serve`）几乎一样，风险更大。

## 1. 2.4.1 的 GUI 插件 API 面

入口：npm 包 `exports["./gui-experimental"]` 指向一个 CommonJS 文件，默认导出 `Plugin.define({ setup?, main? })`；`setup` 在每个窗口跑，`main` 在 Electron 主进程跑一次（`plugin/plugin.ts:47-55`）。同一个文件两边都执行（`desktop/main/plugin/module.ts:26-50`）。

### 1.1 窗口侧注册表（`plugin/registries.ts`）

| 注册表 | 能做什么 | 出处 |
| --- | --- | --- |
| `Command` | 命令面板项、快捷键、斜杠命令 | 29-100, 953 |
| `MenuItem` | 只有三个菜单：`session.panel`（侧栏 + 菜单）、`server.add`、`server.row` | 105, 130, 138, 963 |
| `Panel` | **只有** `region: "side"`：会话右侧区域的标签页；可设宽度 `width`/`wide` | 275-391（282 行） |
| `Panel.mobile` | 窄屏（<768px）视图，`kind: "tab"\|"menu"` 时「替换对话」——仅窄屏 | 227-250（238 行） |
| `SettingsPage` | 新增设置页，或往 `general`/`servers` 页加一节 / 几行；不能改、不能删内置页（如模型 / Provider） | 405-450（413 行） |
| `Server` | 贡献一个 opencode 服务端（SSH、WSL 用的就是它）；`ServerEntry.cover` 在服务端未就绪时**盖住**当前会话路由 | 488-552（534 行）；宿主 `app/runtime/plugin/server-shell.tsx:14-60` |
| `LinkHandler` | 接管消息里本地链接的打开 | 573-598 |
| `TitlebarItem` | 标题栏 / 标签栏底部的小胶囊 | 600-621, 1018 |
| `ComposerPill` | 输入框上方的胶囊（可带菜单） | 679-706 |
| `CodeBlock` / `CodeBlockAction` | 按语言把时间线里的 fenced 代码块渲染成自己的组件（助手文本和工具输出里都生效） | 708-784（723-728 行） |
| `Slot` | 见 1.2 | 880-951 |
| `Style` | 往 `document.head` 注入一段 CSS，**无作用域** | 1090；宿主 `app/runtime/plugin/render.tsx:104-112` |
| `Theme` | 颜色主题，进 设置 › 外观 | 866-878, 1101 |

### 1.2 四个 Slot（`plugin/registries.ts:880-918`）

| Slot | 位置 | 宿主渲染处 |
| --- | --- | --- |
| `window.bottom` | 整个窗口底部的通栏，在 toast 之上 | `app/shell/shell.tsx:335` |
| `session.header` | 对话时间线的标题行 | `app/session/timeline/message-timeline.tsx:627` |
| `session.location.menu` | 项目 / 位置菜单里的条目 | `app/session/session-identity-header.tsx:371` |
| `session.panel.end` | 右侧区域标签条末尾的按钮区 | `app/runtime/plugin/side-region.tsx:421` |

另有 3 个 Slot 只给内置插件（`plugin/builtin.ts:146-162`）。没有「侧栏」「主区域」「首页」「设置导航」之类的 Slot。

### 1.3 宿主 API（`plugin/host-apis.ts`，窗口 `ctx.*`，清单在 `plugin/context.ts:104-291`）

- `Layout`（853-1038）：开 / 关 / 隐藏侧栏标签、侧栏宽度、`settings(page)` 打开设置、`project()`、`draft()` 开草稿会话。**只管右侧区域和几个对话框**，不能开关左侧会话列表，也不能切换主区域内容。
- `Router`（1234-1239）：只有 `routing()` 和 `path()` 两个只读方法，**没有 navigate，也没有注册路由**。
- `Tabs`（1626-1676）：打开 / 聚焦 / 关闭会话标签。`Commands.run`（1599-1610）跑应用命令，但注释写明「应用自己的命令 id 不是稳定 API」。
- `Sessions` / `Screen` / `Composer`（591-806）：读当前会话、改输入框文本、`setModel` 换模型、附件、发送。
- `Embeds.View`（1679-1717）：一个盒子，主进程创建的 `WebContentsView` 填进去。盒子在哪、多大由插件的 DOM 决定：宿主取 `getBoundingClientRect()` 当边界（`app/runtime/plugin/embeds.tsx:315-317`）。
- 其它：Dialogs、Toasts、Attention、Storage、Desktop、Keybinds、Servers、`ctx.ui.*`（宿主组件）。

### 1.4 主进程侧（`plugin/main.ts`）

- `ctx.windows`：返回**真的 Electron `BrowserWindow`**（19-39, 233）。
- `ctx.embeds.create(view: WebContentsView, window)`（65-75, 242）。
- `ctx.serverEndpoints.get("sidecar")`：内置 opencode 服务端的 URL 和带鉴权的请求头（111-149, 224）。
- `ctx.cli`：opencode CLI 路径与版本（128-138）。
- `MenubarItem`：原生菜单项（309）。
- 主进程里 `require("electron")` 和所有 Node 内置模块拿到的是**真模块**（`desktop/main/plugin/module.ts:43`）。

### 1.5 内置插件只能用的（第三方用不了）

`BuiltinPromptGate`（发送前等待，第三方加了会被忽略，`plugin/builtin.ts:52-81`）、内置 Slot（146-162）、`BuiltinLayout.command`/`fitsSide`（340-358）。内置插件「总是运行」，不能被第三方关掉（`desktop/main/plugin/manager.ts:7`）。

## 2. 逐个回答「能不能」

| 问题 | 答案 | 依据 |
| --- | --- | --- |
| 替换 / 隐藏主对话视图 | 公开 API **不能**。路由 `/server/:serverKey/session/:id` 写死渲染 `TargetSessionRouteContent` | `app/shell/routes/routes.tsx:54-81` |
| 替换 / 隐藏左侧会话列表 | 公开 API **不能**。是 `shell.tsx` 里的 `<aside>`，不是插件，没有注册点 | `app/shell/shell.tsx:~250-308` |
| 替换模型设置 | **不能**，`SettingsPage` 只能加页 / 加节，`page` 只有 `general`/`servers` | `registries.ts:405-415` |
| 加一级页面（如 Dashboard、Profile） | **不能**。路由只有 `/`、`/settings`、会话、`/new-session`、开发版 `/components` | `routes.tsx:56-79`；`Router` 无写方法 `host-apis.ts:1234-1239` |
| 定制工具调用的「过程块」 | **不能**，没有工具渲染注册表；只能用 `CodeBlock` 接管 agent 写出的 fenced 代码块 | `registries.ts:708-784` |
| CSS 藏掉内置界面 | **能**。`Style` 是全局 `<style>`，任何选择器都行；但内置界面的类名 / 结构不是 API，每版可能变 | `render.tsx:104-112`；文档 `services/site/.../build/plugins/gui/index.mdx:1618-1627` 只说「注入 CSS」 |
| 全窗口内嵌（WebContentsView 盖满窗口） | **能（约定外）**。窗口代码用 `new Function` 在同一全局执行（`plugin/host.ts:264`），插件可以 Portal 一个 `position:fixed; inset:0` 的盒子放 `Embeds.View`，边界取盒子的矩形；但宿主在菜单、弹层、对话框遮住它时会把它换成静态截图（`embeds.tsx:258-287, 320-335`），toast、命令面板一出现我们的页面就「冻住」 | 同左 |
| 主进程直接接管窗口 | **能（约定外）**。`ctx.windows.list()` 拿到真 `BrowserWindow`，可 `contentView.addChildView` 自己摆边界，甚至 `loadURL` 换掉整个渲染页，或用真 `electron` 另开一个窗口。之后 Desktop 自己的 IPC、插件桥、自动更新重启逻辑都可能打架 | `main.ts:19-39, 233`；`module.ts:43` |
| 「替换内置面板」 | 只是 `ctx.layout.close("context:main", …)` 关掉内置标签再 `open` 自己的；内置面板仍注册着，可从 + 菜单回来 | `A-context-panel.md` §1（其 gui.tsx:52-107）；内置面板 id `packages/gui-plugins/src/context/renderer.tsx:41-42` |

## 3. 做不到的根本原因

1. **路由不可扩展**：`AppRoutes` 是静态 JSX（`routes.tsx:54-81`），插件 API 里没有 Route 注册表，`Router` 只读。
2. **核心外壳不是插件**：`packages/gui-plugins/README.md` 开头写「除核心外壳外的每个功能都是内置 GUI 插件」——终端、review、文件、浏览器、SSH……而会话列表、时间线、首页、设置框架、composer 属于「核心外壳」，留在 `packages/app` 里，没有插件化。
3. **Solid 组件不导出**：插件只能用 `ctx.ui.*` 里给出的那些宿主组件；时间线、消息块、会话列表组件不对插件开放，也就谈不上「包一层改样式」。
4. **实验状态**：官方文档写明「GUI 插件 API 是实验性的，版本之间可能变化；`-experimental` 导出名就是这个意思」（`services/site/src/docs/content/build/plugins/gui/index.mdx:9-12`）。CSS 和 DOM 结构更是零承诺。
5. **加载链**：GUI 插件只从 Desktop 自己的 sidecar 服务端读取，要写进用户全局 `~/.config/opencode/opencode.json(c)` 的 `plugins`（`plugins.mdx:145-158`、`gui/index.mdx:197-212`）——等于往用户自己的 opencode 配置里加东西，会影响他平时用 opencode 写代码。

## 4. 实际天花板

### 4.1 只用正规 API（B1-正规）

窗口长这样：左侧是 OpenCode 的会话列表（按 opencode 的 Project = 目录 → Session 分组，不是我们的 Project → Session → Job）；中间是 OpenCode 的对话时间线和输入框；右侧区域是我们的「JobApply」标签（Solid 写，或用 `Embeds.View` 内嵌我们的 React 工作台页）；标题行 / 输入框上方几个胶囊显示投递状态；设置里多一页「JobApply」（简历、资料、模型以外的设置）；命令面板 / 斜杠命令触发「开始申请」等。Dashboard、Profile 只能放进侧栏标签或设置页，没有一级入口。过程块、工作台两段、侧栏三级都做不了。

### 4.2 约定外的「全盖住」（B1-hack）

在 `main` 里拿 `BrowserWindow`，加一个铺满窗口的 `WebContentsView` 加载我们的 React 应用（或直接 `loadURL`），React 通过 `serverEndpoints.get("sidecar")` 给的地址和鉴权头直连 opencode 服务端。看起来就是我们的应用，OpenCode 在底下跑。问题：
- 依赖未承诺的行为（主进程拿真 `BrowserWindow`、不被沙箱），Desktop 每周发版，更新后可能直接坏，而且用户的 Desktop 会自动更新（`app.asar` 里有 `electron-updater`），版本锁不住。
- 用户打开 OpenCode 写代码时也会被我们的界面盖住（插件是全局配置里的），两用冲突。
- 到这一步 Desktop 只提供「Electron 外壳 + sidecar + 更新器」——这正是 B2 我们自己壳子 + `opencode serve` 已有的，B1-hack 没有额外收益。

### 4.3 最小 fork 要改什么（参考）

1. `app/shell/routes/routes.tsx`：加一个插件可注册的 Route 注册表（或直接加 `/jobapply/*` 路由），并把 `/` 首页换成我们的。
2. `app/shell/shell.tsx`：`<aside>` 会话列表换成可替换的 Slot / 注册表，或换成我们的 Project → Session → Job 树。
3. 时间线：在 `packages/session-ui` 给工具调用 part 加渲染注册表（像 `CodeBlock` 那样），才能画过程块。
4. 设置：允许隐藏 / 替换内置 Provider 页。
5. 关掉 `updater` 内置插件、换品牌和签名、自己出 Electron 包。
改动点本身不多（几个文件），但随之要接手 Bun + Effect + Electron 构建链、签名公证、跟上游每周合并——即 `plan-B-opencode.md` §7 的 B3 成本。

## 5. 稳定性：GUI 插件 API 改得多快

用浅克隆按标签取树（`--filter=blob:none`）比较 `packages/plugin/src/gui`：

| 标签 | 日期 | `plugin/src/gui` 文件数 | `gui-plugins/src` 文件数 |
| --- | --- | --- | --- |
| v2.0.0 / v2.0.32 / v2.1.0 / v2.1.4 | 2026-09-11 ~ 10-23 | 0（不存在） | 0 |
| v2.2.0 | 2026-10-27 | 24 | 868 |
| v2.2.7 | 2026-11-10 | 24 | 1028 |
| v2.3.0 | 2026-11-13 | 24 | 1022 |
| v2.3.2 | 2026-11-18 | 24 | 1040 |
| v2.4.0 | 2026-11-23 | 24 | 1280 |

SDK 目录的改动量（`git diff --shortstat`）：v2.2.0→v2.2.7 9 个文件 +625/−23；v2.2.7→v2.3.0 2 个文件 +3/−2；v2.3.0→v2.3.2 +2；v2.3.2→v2.4.0 11 个文件 +303/−44；v2.4.0→v2.4.1 无变化。合计 v2.2.0→v2.4.1：14 个文件 +927/−63。

解读：
- GUI 插件 API **只有约 4 周历史**（v2.2.0 起），4 周里 2.x 已发 51 个版本（v2.0.0–v2.4.1）。
- 改动以新增为主，但也有签名变化：`Declarations`/`Definition`/`define` 加了泛型参数（icons），`IconName` 改成派生类型，`Composer.focus` 加了参数，`Plugin.define` 增加 `main`。对第三方是小破坏。
- 公开 API 之外的东西（内置界面类名、DOM 结构、应用命令 id、主进程窗口行为）没有任何稳定承诺，B1-hack 依赖的正是这些。

## 6. 对 B1 的结论

- B1-正规：界面要求（侧栏三级、过程块、工作台两段、Dashboard / Profile 一级页）一条都做不到，与 `plan-B-opencode.md` §6 的判断一致，只是理由现在有源码出处。
- B1-hack：能「看起来是我们的应用」，但脆弱、和用户自己用 OpenCode 冲突、版本锁不住，收益等于 B2。
- 不改变 `plan-B-opencode.md` 推荐 B2 的结论。

## 7. 没核实的

- 没实际跑插件，`loadURL` 换渲染页 / 全窗口 embed 被 toast 冻结等行为是按源码推断。
- 内置界面 DOM 是否有稳定的 `data-*` 钩子可供 CSS 隐藏，没逐个看。
- 用户能否在 Desktop 设置里关闭自动更新，没查。
