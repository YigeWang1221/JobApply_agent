# C023 · UI 增量：视觉打磨与左右栏可拖拽伸缩

- 对应 Master：[ui.md](../master/ui.md)「界面结构」（不改结构，只改样子和栏宽）
- 依据：用户（2026-10-31）：「做 UI design 的增量开发，第一个是现在不够美观，第二个是现在的 app 左右边框栏不能自由伸缩」。
- 状态：**已施工，等用户在浏览器里看**（2026-10-31；计划经用户确认后施工）。
- 设计语言不变：沿用 C005 / C006-fix 版式定下的样子（[ui-mockup.html](../reference/ui-mockup.html)：暖灰底、白面板、细边框、8px 圆角、IBM Plex、深色跟随系统）。这是打磨，不是重做。

## 现状（无头 Chromium + 虚构数据截图看到的，2026-10-31）

截图用本地 mock 接口在草稿目录里拍，不是用户数据，不进仓库。

1. **侧栏太挤、太吵**：248px 固定宽，Session 标题被截成「找上海的 Ag…」，工作台行也截断；每一行常驻铅笔和**红色**垃圾桶图标，满屏红点；Project id（`autumn`）用等宽字体挤在标题旁；底部一大段说明文字常驻，占掉约 70px。
2. **图标混用**：「⌂ 全局」「▤ 资料与简历」「⚙ 模型设置」用 Unicode 字符，其它地方用 SVG（`chat/icons.tsx`），大小、基线都对不齐。
3. **按钮层级乱**：主按钮是纯黑（`button.primary` = `--ink`），强调色蓝只用在链接和选中；同一行里「开始投递」黑色实心、「详情」「打开工作台」描边；禁用的「开始申请」是灰色实心块，看着像坏了。工作台头部两行按钮（岗位链接、回到 Project、发现它的 Session、岗位下线了）同一权重挤在一起。
4. **标签（chip）不统一**：Session 标签 `research` 用等宽字体 + 蓝描边，状态 chip 用无衬线；右栏一行里常出现两个意思重复的 chip（「待审阅」+「停在提交前」、「等你」+「等你」），把岗位名挤成「星河数据 · …」。
5. **字小、对比度低**：大量 11–12px 文字用 `--muted #898781`，在 `#fcfcfb` 上约 3.5:1，低于小字 4.5:1；每周节奏图的坐标字约 7px。
6. **原始数据直出**：工作台右栏事件时间是 `2026-10-30T13:22:22.460Z` 这样的 ISO 串。
7. **对齐与留白**：对话页头部（约 72px）和右栏标题不在一条线；Dashboard 两列面板高度不齐，左列下方大块空白；看板空列只有「—」。
8. **深色模式**基本可用，问题同上（红色删除图标、低对比小字更明显）。

## A · 视觉打磨（只动样式和少量 JSX，不加依赖）

1. **侧栏**：行内铅笔 / 垃圾桶只在 hover、键盘聚焦、当前行时出现，垃圾桶平时用 `--muted`，hover 才变 `--crit-ink`；Project id 改成小号次要文字或只在 title 里；底部说明文字折成一个「?」提示（hover / 点击看全文）；行高、缩进、分组标题统一到 4px 网格。
2. **图标统一**：全局、资料、设置换成 `chat/icons.tsx` 里的 SVG（缺的在同一文件补 2–3 个 path）。
3. **按钮三级**：primary（实心，保持 `--ink`，见待定 1）、secondary（描边）、ghost（无边框，用于行内次要操作）；统一高度（28px / 紧凑 24px）和圆角；禁用态改为降低对比的描边样式，不再是灰实心块。工作台头部把「回到 Project / 发现它的 Session / 岗位下线了」收成 ghost 按钮或一个「更多」菜单，主操作只留一个实心。
4. **chip 统一**：一套 `.chip` 尺寸和字体（无衬线，11.5–12px，圆角 999px），语义色只用 good / warn / crit / accent / 中性五种；右栏每行只显示一个状态 chip（服务器给的 `label`），重复的那个去掉。
5. **字号与对比**：定 4 级字号变量（12 / 13 / 14 / 16，标题 20），去掉 11px 以下的字；`--muted` 调深到小字 ≥ 4.5:1（浅色约 `#74726c`，深色同步检查）；图表坐标字 ≥ 11px。
6. **时间格式**：事件时间显示成本地 `MM-DD HH:mm`（参照 `chat/MessageList.tsx` 的 `Time` 和 `dashboard/metrics.ts` 的 `dayOf`），不显示 ISO 串。
7. **对齐**：对话页头部、右栏头部同一高度；Dashboard 两列 `align-items: start` 并让较短一列不留大块空白（或改为自然流）；看板空列显示一行淡色「暂无」。
8. 只用现有颜色变量；所有新增颜色在 `:root` 和深色块各写一份。

## B · 左右栏可拖拽伸缩

- **左侧栏**：在侧栏右边缘加一条拖拽手柄（`role="separator"`、`aria-orientation="vertical"`、`aria-valuenow/min/max`、`tabIndex=0`）。宽度范围 200–420px，默认 248px。
- **右栏**（Session 页岗位栏、工作台「要你做的事」栏共用一个组件）：手柄在右栏左边缘，范围 280–560px，默认 340px；主对话区保持最小 480px，不够时先压右栏。
- **交互**：指针拖动（pointer capture，拖动时 `body` 禁止选中文字、光标 `col-resize`）；双击手柄回到默认宽；键盘 ←/→ 每次 16px，Home / End 到最小 / 最大。
- **记住宽度**：localStorage，每栏一个键：`jobapply.sidebar.width`、`jobapply.sessionPanel.width`、`jobapply.jobPanel.width`（两种右栏内容不同，分开记）。读写都包 try/catch，读不到用默认值；松手时才写。
- **收起照旧**：左栏收起到 52px 的 rail 和 `jobapply.sidebar.collapsed` 不变；收起时不显示手柄，展开回到上次拖的宽度。右栏的开 / 关按钮不变。
- **窄屏照旧**：≤1100px 右栏仍是浮层、不显示手柄；≤760px 侧栏仍堆在上方、不显示手柄。
- **实现**：一个小组件 + hook 放在 `frontend/src/`（例如 `ResizeHandle.tsx`，与 `RenameTitle.tsx` 同层），宽度写成 CSS 变量（`--side-w`、`--panel-w`），`.app` 的 `grid-template-columns` 和 `.jobsside` 的 `width` 改读变量。不加拖拽库。

## 要改的文件

- `frontend/src/styles.css`：A 的大部分；B 的手柄样式和变量。
- `frontend/src/App.tsx`：侧栏图标、行内按钮、说明折叠；左栏手柄与宽度。
- `frontend/src/pages/SessionPage.tsx`、`frontend/src/pages/JobPage.tsx`：右栏手柄与宽度；工作台头部按钮分级；右栏 chip 去重；事件时间格式。
- `frontend/src/chat/icons.tsx`：补缺的图标。
- 新增 `frontend/src/ResizeHandle.tsx`（手柄组件 + 宽度 hook）。
- 视需要小改 `frontend/src/dashboard/*.tsx`（对齐、空列、坐标字号）。不碰 `backend/`。

## 怎么确认能用

- `npm run build` 退出码 0。
- 用户在浏览器里看：全局、Project、Session、工作台、设置五页，浅色和深色各看一遍；拖左右栏、双击复位、刷新后宽度还在、收起 / 展开侧栏、把窗口缩到 1100px 和 760px 以下看布局不乱；Tab 到手柄用方向键能调宽。
- 代理可另用无头 Chromium + 草稿目录里的 mock 接口拍前后对照截图（不进仓库）。

## 不做

- 不换设计语言、字体、配色体系，不做新页面，不改信息结构和路由。
- 不加 UI 库、图标库、拖拽库或 CSS 框架。
- 不改后端接口和数据；不改窄屏（≤1100px / ≤760px）的现有行为。
- 不做手动切换深浅色的开关（仍跟随系统）。
- 不为这一步写测试。

## 已确认（用户，2026-10-31）

1. **主按钮颜色**：保持纯黑实心（`--ink`）。
2. **侧栏底部说明文字**：折成「?」提示（hover / 点击看全文）。
3. **左右栏都能自由拉伸**（用户原话：「我需要左右栏都能自由拉伸」）：左侧栏和右栏（Session 页岗位栏、工作台右栏）都可拖拽，**不设 420 / 560 这类紧上限**。每栏只有一个小的下限（左栏 180px，右栏 240px）；上限按当前窗口宽度推出来，保证主对话区至少留约 400px；窗口变化时重新夹住。右栏宽度 Session 页和工作台分开记。双击复位、键盘调宽照计划；收起 / rail、窄屏（≤1100 / ≤760）行为不变。
4. **左栏上限**：用户没单独回答，按第 3 条用「由窗口宽度推出的上限」代替 420px。

上面 B 节里的 200–420 / 280–560 / 主区 480px 以本节为准。

## 施工记录（2026-10-31）

**A · 视觉打磨**

- 侧栏（`App.tsx`、`styles.css`）：铅笔 / 垃圾桶平时隐藏，悬停、键盘聚焦（`:focus-within`）、当前行才出现；垃圾桶和铅笔同为 `--muted`，悬停才变 `--crit-ink` + 浅红底（工作台行的垃圾桶同样处理）。Project id 不再挤在标题旁，放进 `title`。底部说明折成「Projects」旁的「?」（`SideHelp`，悬停 / 聚焦 / 点击显示，浮层宽度不超出侧栏）。全局、资料与简历、模型设置换成 `chat/icons.tsx` 的 SVG；`icons.tsx` 补一个 `help` 图标（`home` / `file` / `gear` 原来就有）。
- 按钮：主按钮仍是 `--ink` 黑实心；禁用的主按钮改为透明底 + 细描边 + `--muted` 字（不再是灰实心块）；新增 `button.ghost`（无边框）；`btn-xs` 最小高度 24px。工作台头部「回到 Project / 发现它的 Session / 会话已删除 / 岗位下线了」收进一个「更多 ▾」菜单（`<details>`），头部只剩一个实心主按钮「开始申请」。
- chip：`.chip` 固定无衬线、12px、行高 18px、圆角 999px；Session 标签 `tagc` 改为 `chip tagc`（Session 页头部和 Dashboard「最近的 Session」）。`JobState` 加 `compact`：申请线程在动时只显示申请状态，否则显示阶段，另一个写进 `title`；Session 右栏每行和工作台头部用 `compact`。
- 字号与对比：`:root` 加 `--fs-xs/sm/md/lg/xl`（12/13/14/16/20）；所有 9 / 10.5 / 11 / 11.5px 改成 12px，图表坐标字保留 11px（原 9px 的柱内数字改 11px）。`--muted` 浅色 `#898781` → `#6f6d67`（在 `--surface` 上约 4.9:1、在 `--bg` 上约 4.6:1），深色 → `#9a9890`。
- 每周节奏图：宽度随周数（每周 56，360–960），`maxWidth` 等于自身宽度，不再把 960 宽的图缩进窄列（坐标字原来约 7px）。
- 事件时间：工作台右栏事件、简历排名时间显示本地 `MM-DD HH:mm`（`JobPage.tsx` 的 `shortTime`）。
- 对齐：对话页头部和右栏头部都至少 64px、同一条底线。Dashboard 改为两列各一叠：左「漏斗 + 每周节奏」，右「接下来 7 天 + 待追踪 + 复盘概览」，下面是看板和时间线；看板空列显示一行淡色「暂无」。

**B · 左右栏拖拽**

- 新增 `frontend/src/ResizeHandle.tsx`：`ResizeHandle`（`role="separator"`、`aria-orientation/valuenow/valuemin/valuemax`、`tabIndex=0`；指针拖动用 pointer capture，拖动中直接改目标元素上的 CSS 变量、不每帧重渲染，`body.resizing` 禁选中、光标 `col-resize`；双击复位；←/→ 每次 16px，Home / End 到下限 / 上限）、`useStoredWidth`（localStorage，读写包 try/catch，松手才写）、`useWindowWidth`、`ResizablePanel`（右栏共用）。
- 左栏：`.app` 的 `grid-template-columns` 读 `--side-w`；默认 248，下限 180，上限 = 窗口宽 − 400（对话页 / 工作台且宽于 1100 时再减右栏下限 240）；窗口变化时重新夹住（只夹显示值，记住的值不变）。键 `jobapply.sidebar.width`。手柄 `position: fixed` 贴在侧栏右边缘；收起（rail）时不渲染手柄。
- 右栏：`.jobsside` 用 `flex: 0 1 var(--panel-w)`、`min-width: 240px`，宽屏时 `.chatmain` `min-width: 400px`，所以窗口或左栏变宽时由 CSS 先压右栏；拖动上限 = 对话页宽 − 400。默认 340。Session 页键 `jobapply.sessionPanel.width`，工作台键 `jobapply.jobPanel.width`。右栏窄于 300px 时（容器查询）岗位行的按钮换到标题下面。
- 窄屏照旧：≤1100 右栏仍是浮层、不显示右栏手柄；≤760 侧栏仍堆在上方、不显示左栏手柄。

**验证**

- `frontend/` 里 `npm run build` 退出码 0。
- 无头 Chromium（Playwright 自带的 headless shell，草稿目录 mock 接口，虚构数据）拍了全局 / Project / Session / 工作台浅深各一张，另拍拖宽、拖到极限、拖到下限、工作台右栏拖宽、1200 / 1000 / 700 宽、「?」提示、「更多」菜单。用 CDP 发鼠标事件拖手柄，量到：默认 248 / 340 → 拖宽 388 / 500；拖到极限侧栏 800、右栏被压到 240、主区 400；拖到下限 180 / 240；同页刷新后宽度还在；工作台右栏单独记（默认 340，拖到 460，双击回 340）；键盘 → 两次 +32；1200 宽时侧栏、右栏、主区仍合计不溢出。
- 用户在浏览器里看：未运行（等用户）。

## 偏差

- 计划 A-3 写「收成 ghost 按钮或一个『更多』菜单」：选了「更多」菜单，因为改成 ghost 后头部在 1440 宽仍折成两行。
- 工作台头部的状态 chip 也用了 `compact`（计划只点了右栏）：原来同样是「待审阅 + 停在提交前」两个意思重复的 chip。
- Dashboard 的「每周节奏」「复盘概览」挪进上面两列（原来在看板和时间线下面单独一行），用来消掉左列下方的空白；信息没有增减。
- 截图里过程摘要的「其他 -2 步」是 mock 数据（步骤里 `kind` 与工具计数对不上）造成的，没在真实数据里复现，没追。
