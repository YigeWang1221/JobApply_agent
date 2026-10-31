# C016 · 下拉框、级联、日期控件

- 依据：用户（2026-10-24）：申请 Agent 在用户的 Chrome 里填表时，日期（年月日下拉、日历面板）、国家 / 地区、省市区级联、可搜索的自定义下拉都很弱：慢、来回点、经常卡住。要求先调研开源做法，出计划，用户看过再施工。
- 不做：换掉 Playwright MCP；给模型开放 `browser_run_code_unsafe`；按站点写死的填表脚本；新的安全守卫；另开 Chrome / profile；截图 + 视觉模型。
- 状态：已施工（A–G 一次做完），等用户在真实网站试（2026-10-24）。

## 1. 现状与根因

证据标记：[源码] `@playwright/mcp@0.0.83` / playwright-core 1.64.0-alpha 的 `coreBundle.js`；[记录] 本机 `storage/` 里的真实运行记录（只统计调用序列，不抄内容）；[推断] 没实测。

**真实记录里的样子** [记录]：
- 2026-10-24-008（网易校招，Ant Design 3）：111 次模型调用、约 410 万 token。两段教育的「入校时间 / 获得学位证时间」加「学校所在地」级联用掉约 40 次工具调用；`browser_type` / `browser_fill_form` 在日期框上失败约 18 次，每次等满 15 秒超时；`browser_press_key`（多是 Escape）19 次。
- 2026-10-23-001（小米，飞书）：级联城市反复「点 combobox → 点 caret 图标 → 点没名字的 generic」，Escape 29 次，触发重复提醒 11 次。很多下拉在快照里是 `combobox ""`（没有名字），模型要靠前后文字猜是哪个字段。

**根因**：

1. **`browser_select_option` 和 `browser_fill_form` 的 combobox 只认原生 `<select>`** [源码]：两者都是 Playwright `locator.selectOption`，注入脚本里不是 `select` 就报「Element is not a <select> element」。选项文字对不上时返回 `error:optionsnotfound`，Playwright **一直重试到 15 秒动作超时**，MCP 的超时错误不带调用日志，模型既不知道原因也看不到有哪些选项。`fill_form` 逐个字段执行，遇到第一个失败的字段整批中止，后面的字段没填、模型也不知道填到了哪。
2. **只读输入框上键入必然等满 15 秒** [源码]：`browser_type` 和 `fill_form` 的 textbox 是 `locator.fill`，要等元素「可编辑」。Ant Design 3 日期框的 `<input>` 写死了 `readOnly`（antd 3.26.20 `createPicker.tsx`，能键入的输入框在弹出面板里），Element UI 不可搜索的 `el-select`、不可搜索的级联也是只读输入框。我们超时后的检查（`PlaywrightMcpClient.DIAGNOSE`）不看 `readonly`，还说「可见、没被盖住，可以再试一次」——模型就一直重试。
3. **超时检查把「关着的控件」报成「开着的弹出层」** [记录 + 源码]：`DIAGNOSE` 用 `[class*=picker]`、`[class*=cascader]` 等找弹出层，匹配到了日期框的日历图标 `i.ant-calendar-picker-icon`、级联的显示文字 `span.ant-cascader-picker-label`（都是 `position:absolute`）。结果每次都写「页面上还开着这些弹出层……先按 Escape」，模型照做，下次还这么写 → Escape 循环。
4. **一次 UI 小动作 = 一次模型调用 + 一份整页快照** [源码 + 记录]：`OwnerBrowser.act` 每个动作后都取整页快照（MCP 用 `--snapshot-mode none`，我们自己再调 `browser_snapshot`）。关着的弹出层是 `display:none`，不在快照里（`ariaSnapshot` 的可见性规则），所以下拉至少两轮（打开、点选项），三级级联四轮，日期面板翻年份每次一轮；008 里平均每轮约 3.7 万 token、几秒到十几秒。长列表（国家、学校）是虚拟滚动，快照里只有看得见的几条。
5. **可搜索下拉的输入方式不全**：`browser_type` 是一次性 `fill`（一个 input 事件），只认按键事件的老式联想框不出结果；MCP 有 `slowly`（逐字按键），我们的白名单允许、但工具定义没给模型 [源码]。回车被禁止（`press_key`、`type submit`），只能点选项提交——这是对的，但提示词没说清。
6. **重复提醒抓不住来回**：`LoopDetector` 只比较「连续相同」的结果；「打开 → Escape → 打开」每次页面都不同，不算重复。

## 2. 参考

都是只读源码（clone 在本次 scratchpad，没有运行），日期 2026-10-24。

| 项目 | 看的版本 | 它怎么做 | 适不适合我们 |
| --- | --- | --- | --- |
| browser-use | `3df5806`（2026-10-22）`tools/service.py`、`browser/watchdogs/default_action_watchdog.py` | 两个工具：`dropdown_options(index)` 列出选项原文（原生 select、`role=menu/listbox`、带 `aria-controls` 的 combobox——没展开时先派发 focus/click/mousedown 展开再去被控制的列表里读、Semantic UI），`select_dropdown(index, text)` 按原文或 value 选，**选不中时把全部可选项返回给模型**，选完核对值有没有被框架改回去；点到 `<select>` 时直接返回选项列表。`type` 识别联想框（`role=combobox`、`aria-autocomplete`、`list`、`aria-haspopup`+`aria-controls`），结果里提示「等建议出来再点，不要回车」，并报「实际值和键入值不同」；原生 date/month 和 jQuery datepicker 直接设值再派发事件 [源码] | **最贴合**：一个系统侧工具读选项 / 选选项，选不中返回候选，由模型挑原文。我们用固定的 `browser_run_code_unsafe` 模板实现同样的事 |
| Skyvern | `647b920`（2026-10-24）`webeye/actions/handler.py`、`webeye/scraper/domUtils.js` | 点击或键入前开 `MutationObserver`，只把**新出现的元素**（弹出的选项）交给一个小模型挑；`sequentially_select_from_dropdown` 一层层选（级联最多 3 层，日期面板最多 30 层并有时间预算）；联想框：逐字输入 → 从新出现的选项里挑 → 都不对就让模型生成几个备选写法再试；长列表 `scroll_down_to_load_all_options`；日期先按占位符核对格式；选完读回控件的值确认 [源码] | 思路对（只看新出现的部分、按层选、滚动加载、读回确认），实现太重（上万行、动作里嵌模型调用、按站点的特殊分支）。只借思路 |
| Stagehand | `8023f34`（2026-10-23）`packages/extension/services/actService.ts`、`prompt.ts` | `act` 分两步：原生 select 直接 `selectOptionFromDropdown`；自定义下拉先点开，再对比前后两份无障碍树（`diffCombinedTrees`），只把变化的部分交给第二次推理去点选项 [源码] | 「点开后只看变化的部分」适合用来省快照，放到后续 |
| Agent-E | `f218c3c`（2025-05-12）`ae/core/skills/click_using_selector.py` | 点击 / 输入后用 `MutationObserver` 告诉模型「出现了这些新元素，这个操作还没完成」 [源码] | 同上，后续 |
| Autofill-Jobs（求职填表扩展） | `a82a316`（2025-06-27）`contentScripts/autofill.js`、`workday.js` | Greenhouse 的 react-select：原生 setter 设值 + input 事件，再在控件上派发回车；Workday：点按钮，找 `ul[role=listbox]` 点文字匹配的项；Workday 的日期是分开的月 / 年输入框，直接键入 [源码] | 按站点写死，不适合；印证了「输入过滤 → 在列表里点」和「日期分段直接键入」 |
| Hermes | 本机 `e5dad4ac5` `tools/browser_tool.py` | 没有下拉工具，只有点击 / 键入，加截图视觉和页面里执行 JS [源码] | 不适用 |
| OpenMAIC `dff201b`、deepagents `3e7d5fa`、deep-agents-ui、langgraphjs | — | 没有浏览器表单处理 | 不适用 |
| Playwright MCP | issue #568、#1177 | 「弹层（portal）里的选项不在快照里」，维护者要可复现的例子后关闭，没有修 [网页] | 我们的记录里点开后 `option` 是有 ref 的，不是主因 |

AIHawk（Jobs_Applier_AI_Agent_AIHawk）的仓库现在已改成别的项目（`ef69f5e`，2026-10-14），没有求职填表代码，没参考。

## 3. 改法

原则：下拉选哪一项、拿不准时问不问用户，仍由模型决定；系统只把「打开、等列表、过滤、逐层点、滚动找、读回」这些机械动作在一次调用里做完，失败时说实情、把候选给模型。都用已有的做法：系统用固定模板经 `browser_run_code_unsafe` 代发（同 `setInputFiles` / `DIAGNOSE`），模板里只嵌 JSON 编码过的 ref 和值。

### A–D（每项的改动可以分开提交，见「实际结果」）

**A. 超时检查改对，只读框不再等 15 秒**（`PlaywrightMcpClient`、`OwnerBrowser`、`HarnessLoop` 小改）
- `DIAGNOSE` 多报：标签名、`type`、`readonly`、`disabled`、是不是原生 select、`aria-expanded` / `aria-haspopup`。「开着的弹出层」只算可见、fixed / absolute、**里面有选项类元素**（`role=option/menuitem/treeitem/gridcell`、`li`、`td`）或 `role=dialog` 的容器，不再按类名里有 picker / cascader 就算。
- `browser_type` 和 `fill_form` 发出之前，用一个只读模板一次查完所有目标 ref（几十毫秒）：只读或禁用的文本框不发 `fill`；`fill_form` 里不是原生 select 的 combobox 也不发。其余字段照常填；结果写明「填了哪些、哪些没填、为什么」，例如「这是只读的显示框（日期 / 下拉控件），键入不会生效：点它打开面板，或用 browser_select_option」。这不是守卫，只是省掉必然失败的 15 秒。
- 模型看到的：错误说明变成实情（只读、不是原生 select、没有弹出层开着）；`fill_form` 不再整批失败。

**B. `browser_select_option` 能选任何下拉，含级联和搜索**（`BrowserTools`、`BrowserToolPolicy`、`PlaywrightMcpClient` 加一个模板、`OwnerBrowser`）
- 模型看到的：沿用这个工具名（模型本来就会对下拉用它），说明改成「选任何下拉里的一项：原生 select、自定义下拉、可搜索下拉、级联（`values` 按层写，如 ["广东","深圳"]）。选不中时返回现在能看到的选项原文，从里面挑原文再选；没有语义一致的项就问用户」。可选 `search: true`（先在框里输入再选，用于远程搜索）。`fact` / `confidence` / `language` 照旧。
- 系统做的：目标是原生 `<select>` 时仍走 MCP 的 `browser_select_option`，但先按规范化文字（空白、全角、大小写）找到确切的选项文字，找不到就不发、直接返回所有选项（不再等 15 秒）。不是原生的，走一个固定模板：
  1. 真实点击触发元素（`page.locator('aria-ref=…').click()`）；可搜索时逐字输入第一层的值（`pressSequentially`）。
  2. 等新出现的、可见的弹出列表（找法同 A：带选项类元素的 fixed / absolute 容器，或触发元素 `aria-controls` / `aria-owns` 指向的列表），最多约 3 秒；远程搜索等列表内容稳定。
  3. 在列表里按「完全相同 → 规范化后相同 → 唯一的包含匹配」找这一层的值，真实点击它；有下一层就等下一列出现再找（级联），最多按 `values` 的层数。
  4. 列表能滚动、又没找到时，按页滚动收集，直到找到或到底（有上限）。
  5. 读回触发元素显示的值或已选标签；弹出层还开着就按一次 Escape 关掉。
  6. 返回 JSON：选中了什么、控件现在显示什么；没选中时返回第几层没找到、这一层能看到的选项（最多约 50 条，过 `SnapshotRedactor`）、是否还有更多。**有歧义或没找到时不点**。
- 之后照常取快照交给模型。点击只落在触发元素和弹出列表里的选项类元素上，不点按钮；触发元素的 ref 仍要在最新快照里（白名单的角色放宽到 combobox、listbox、textbox、searchbox、button、generic）。
- 常见组件库的类名（Element UI / Element Plus、Ant Design、iView、Vant、react-select、Workday 的 listbox）只作为找弹出列表和选项的补充线索，主路径靠 ARIA 角色和「新出现的可见容器」。

**C. 逐字输入给模型用**（`BrowserTools` 一行）
- `browser_type` 加可选 `slowly`（逐字按键，给只认按键的联想框）。白名单已经允许。

**D. 提示词和 skill**（`ApplyAgent`、skill `readonly-select` / `cascader-city` / `date-picker` / `apply-form`）
- 下拉、级联、国家城市：先用 `browser_select_option`（一次调用）；返回候选时从候选里挑原文再调一次；还不行再手动点。没有名字的 `combobox ""` 按它前面的标签文字认是哪个字段。回车不可用，联想框靠点选项提交。
- 日期按控件分三种：原生 `type=date/month` 直接按 ISO 格式填；能键入的框按占位符的格式键入，再 Tab 失焦，读回显示值；只读的框（A 会告诉模型）点开面板——面板里有输入框就在那里键入，否则点面板头部的年份 / 月份切到年份表、月份表一次跳过去，不要按「上一年」一年一年翻；面板关不掉时按一次 Escape 即可，不反复按。
- `PlaybookSeeder` 只在缺文件时补 skill：施工时把 skill 的 `version` 加 1，并在交付说明里请用户删掉本机 `storage/playbook/skills/components/` 下这三个旧文件（或施工时改成按 version 覆盖，单独确认）。

### E–G（用户 2026-10-24 改为和 A–D 同一次施工）

- **E. 日期工具**：`browser_set_date(target, "2023-09")`，系统按控件类型做：原生直接设值；可键入框键入 + 失焦 + 读回；只读框打开面板找面板内输入框，没有就用年份表 / 月份表点到目标（antd、Element、iView、react-datepicker、Workday 分段输入）。先看第一段后日期还慢不慢。
- **F. 点开后只给变化的部分**：点击打开了弹出层时，结果里只放弹出层的子树和一行页面摘要，不放整页快照（Stagehand 的 diff、Agent-E 的新元素）。动快照流程，和 C006-fix-real-run 第 5 条 token 一起看。
- **G. 来回的提醒**：`LoopDetector` 加一条「同一个元素在最近 N 步里被反复操作、中间夹着 Escape / 失败」，提醒里建议用 `browser_select_option` 或问用户。只是提醒，不拦。

## 4. 和现有边界的关系

- **最终提交**：B 的模板只点触发元素和弹出列表里的选项类元素，不点任何按钮；点击守卫不变。日期面板、多选级联底部的「确定」按钮仍会被提交类按钮的硬边界拒绝——这是 C006-fix-real-run 第 18 条，等用户决定，本计划不动。
- **C 级占位符**：`browser_select_option` 的值照旧过 `FieldIntent`（C 级检查）和发出那一刻的占位符替换；模板只在发给 MCP 的参数里拿到真实值，返回的候选列表过 `SnapshotRedactor`。
- **回车**：仍然禁止；模板不按回车，靠点选项。Escape 本来就允许。
- **`browser_run_code_unsafe`**：仍不在模型白名单里，只有固定模板，参数 JSON 编码后嵌入。
- 不加新守卫、不加确认、不另开 Chrome。

## 5. 怎么确认能用

- `backend/` 里 `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -B -q clean verify` 退出码 0。
- 只补一处测试：模板参数的嵌入（值里有引号、反斜杠、换行时生成的代码仍正确），这里错了会把错的值填进申请表。其它不新写测试。
- 用户在真实页面上试（Agent 照常跑，用户看工作台的步骤）：
  1. 网易校招那类 Ant Design 3 表单：教育的入校 / 毕业时间、学校所在地级联——不再出现 15 秒超时和成串的 Escape；每个日期、每个级联大约 1–3 次工具调用。
  2. 飞书类表单的城市级联、学历下拉：一次 `browser_select_option` 选中，或者拿到候选后第二次选中。
  3. 一个国际 ATS（Greenhouse / Workday / Lever）：国家下拉、地点联想框（远程搜索）能选中；没有一致选项时 Agent 来问用户，而不是硬选。
  4. 对比同一类表单改前改后的模型调用次数和 token（工作台 / `storage/runs/<job>.json` 的 usage）。

## 用户的决定（2026-10-24）

1. 沿用 `browser_select_option`，扩展成能选任何下拉，不另起工具名。
2. 先说日期工具（E）、只给弹出层（F）、来回提醒（G）放后面；同日改为 A–G 一次做完，不留「后续」。E 的要求：一次调用设好三种日期控件；找不到日不点、返回面板内容；只点面板里的东西；要按「确定」时告诉模型，不改提交守卫。F：打开弹出层后只给弹出层 + 简短页头，拿不准时给整页。G：在已有的重复提醒里加来回检测，只提醒不拦。
3. skill 更新：`PlaybookSeeder` 在预置 skill 的 `version` 比本机那份新时覆盖；本机那份版本相同或更高时不动。改写的 skill 把 version 加 1。

## 实际结果（2026-10-24）

固定脚本都放在新文件 `tools/PageScripts.java`：开头一行 `const args = <JSON>;`（值只以 JSON 嵌入），接共用的页面函数 `DOM`，再接各自的步骤。弹出层 = 可见的 fixed / absolute 容器、里面有选项类元素（option、menuitem、treeitem、gridcell、li、td），不按组件库类名认。脚本自己有 15 秒时限（MCP 调用超时 30 秒）。

**A. 超时检查、只读框不等 15 秒**
- `PageScripts.DIAGNOSE`（替换 `PlaywrightMcpClient` 里原来的模板）多报只读、禁用、原生 select、aria-expanded / haspopup；弹出层按上面的规则找，不再把日历图标、级联显示文字当成开着的弹出层。`Diagnosis.describe` 对只读 / 禁用 / 原生 select 直接说怎么办，不再说「可以再试一次」；弹出层的说法改成「要选就直接点，要关按一次 Escape，不反复按」。
- `PageScripts.INSPECT` + `PlaywrightMcpClient.inspect / cannotFill` + `OwnerBrowser.inspect` + `BrowserSession.cannotFill`：`HarnessLoop` 在发 `browser_type` / `browser_fill_form` 之前查一次。只读、禁用、不是输入框、原生 select 上键入，以及 fill_form 里不是原生 select 的 combobox，不发；fill_form 其余字段照常填，结果写「这些字段没有填：「名字」（ref）原因」；全部被跳过时这次调用不发出、返回原因。检查本身失败时照常发出。
- `fill_form` 的工具说明同步改写。
- 文件：`tools/PageScripts.java`（DOM、DIAGNOSE、INSPECT）、`tools/PlaywrightMcpClient.java`（diagnose、inspect、cannotFill、Diagnosis.describe）、`tools/OwnerBrowser.java`（inspect）、`tools/BrowserSession.java`（cannotFill；说明文字过 personal.env 脱敏）、`harness/runtime/HarnessLoop.java`（cannotFill、reasons、without）、`tools/BrowserTools.java`（fill_form 说明）、测试替身 `FakeFormScript.java`（按脚本标记应答）。

**B. `browser_select_option` 能选任何下拉**
- 工具说明改写，新增可选 `search`；白名单角色放宽到 combobox、listbox、textbox、searchbox、button、generic，`values` 必须是非空字符串列表。
- `PageScripts.SELECT`：原生 select 由脚本按「完全相同 → 规范化（NFKC、大小写、空白）后相同 → 唯一包含」找到选项原文，再交给 MCP 的 `browser_select_option`；找不到或有歧义不发，返回全部选项。自定义下拉：真实点击打开（aria-expanded 已经是 true 时不再点）→ 等新出现的弹出层（或 aria-controls 指的列表）→ 每层先滚到顶再找，没找到按页滚动收集 → 能输入的下拉没找到时输入第一层的值过滤（`search: true` 时先输入；远程结果等列表稳定）→ 点中 → 级联等下一列出现或这一列内容变化 → 最后读回框里显示的值是否包含点中的项 → 列表还开着时聚焦触发元素按一次 Escape。歧义 / 没找到不点，返回这一层看到的选项原文（最多 50 条，标明还有更多）。选项只点弹出层里的选项类元素，不点按钮、输入框、链接。
- 结果不回显值（真实值可能是占位符替换后的 C 级值）：成功时说点了几层、显示的值对不对得上；失败的说明按快照的写法列选项（`- listbox "名字":` / `- option "…"`），经 `BrowserSession` 的 personal.env 脱敏和 `HarnessLoop` 里的 `SnapshotRedactor.redact`。
- target 是 button / generic 时，`HarnessLoop` 先过已有的点击守卫（`SubmitGuard.click`）：提交类按钮照旧拒绝，翻页 / 保存类不能拿它点。combobox、textbox 点了不会提交，不拦。
- 文件：`tools/PageScripts.java`（SELECT）、`tools/PlaywrightMcpClient.java`（select、choices）、`tools/OwnerBrowser.java`（脚本的一句话放进说明）、`harness/policies/BrowserToolPolicy.java`、`tools/BrowserTools.java`、`harness/runtime/HarnessLoop.java`（target 守卫、失败说明脱敏）。

**C. `browser_type` 给模型 `slowly`**：`tools/BrowserTools.java` 的 schema 加一项（白名单本来就允许）。

**D. 提示词和 skill**
- `ApplyAgent`：工作方法第 4 条、「点不动」「日期」「下拉和级联」三条改写（先用 browser_select_option / browser_set_date；没名字的 combobox 按前面的标签认；回车不可用、联想框靠点选项；日期三种情况；面板关不掉按一次 Escape；要「确定」时问用户）。
- skill 改写并 version 1 → 2：`components/date-picker`、`components/readonly-select`、`components/cascader-city`；`apply-form` 改了批量填写、超时处理和「只给弹出层」的说明，也 1 → 2。
- `PlaybookSeeder`：缺的补上；已有的只在预置 version 更高时覆盖；本机那份 version 相同或更高、没有 version、读不出来时不动。
- 文件：`agents/ApplyAgent.java`、上面四个 `SKILL.md`、`persistence/PlaybookSeeder.java`。

**E. `browser_set_date`**
- 新工具（不是 MCP 的工具，过白名单后由 `PageScripts.DATE` 代发）：`target` + `date`（YYYY-MM-DD / YYYY-MM / YYYY，白名单检查格式），带 fact / confidence / language；值照旧过 C 级检查和占位符替换。角色：textbox、combobox、searchbox、button、generic。
- 脚本：原生 date / month / datetime-local 框直接 `fill` ISO 值；能键入的框按占位符（或现有值）的格式键入，Tab，读回对得上就结束；只读的框或键入没生效时点开面板——面板里有可键入的框就先在那里键入；否则看面板现在是日期表 / 月份表 / 年份表 / 年代表，点头部的年份、月份切表，目标年份不在年份表里时点十年翻页，相差一两个月才用月份箭头，最后点「本月」的日期格（不点上 / 下月的灰格）。只点面板里最上层的格子、头部年月和翻页箭头；文字是确定、今天、清除等的一律不点。点完面板还开着并有「确定」→ `needs_confirm`，模型被告知用 ask_user 请用户点。找不到时不点，返回面板的头部和格子。
- 其它：`OwnerBrowser.afterTimeout` 也覆盖它；工作台步骤的标签（`api/Views.java`）显示它的字段名。
- 文件：`tools/PageScripts.java`（DATE）、`tools/PlaywrightMcpClient.java`（setDate、panel）、`harness/policies/BrowserToolPolicy.java`（SET_DATE）、`tools/BrowserTools.java`（定义、translate）、`tools/OwnerBrowser.java`、`harness/runtime/HarnessLoop.java`（values、withRealValues）、`api/Views.java`。

**F. 打开弹出层后只给弹出层**
- 新文件 `harness/context/PopupView.java`：`browser_click` / `browser_type` 之后，对比前后两份快照，ref 在上一份里没有、祖先都是旧的 = 新出现部分的根；只留里面有选项类角色的块（option 等，或至少两个 listitem / cell）。给模型的快照换成「一行说明 + 操作的元素那一行 + 弹出层」，页头（标题、地址）照旧由 `Conversation.render` 给。先按整页脱敏再截取。换了网址、有 modal state、页面少于 40 行、新块超过 3 块或占整页 60% 以上时给整页。系统内部（白名单、守卫、重复检测）仍用整页快照。
- 文件：`harness/context/PopupView.java`、`harness/runtime/HarnessLoop.java`（withPage）。

**G. 来回提醒**
- `LoopDetector`：记最近 8 步；同一个 ref 在其中被操作 3 次以上、中间夹着 Escape 或它自己的失败时，提醒「像是在来回点，下拉用 browser_select_option、日期用 browser_set_date，还不行 ask_user」。同一个元素至少隔 3 步再提醒；只提醒，不拦。
- 文件：`harness/runtime/LoopDetector.java`。

**测试**：只新增计划里那一个（`PlaywrightMcpClientTest.scriptArgumentsSurviveQuotesBackslashesAndNewlines`）。改了编码旧行为的：`BrowserToolPolicyTest`（select 角色、set_date 格式）、`BrowserToolsTest`（工具清单多了 set_date）、`BrowserSessionActTest`（选原生下拉多一次脚本调用、MCP 收到的是选项原文）。

**本地检查**（只在 scratchpad 的本地 HTML 上、headless，未连用户的 Chrome，进程已退出）：原生 select（包含匹配、歧义、没找到）、ARIA combobox + portal listbox、Element 式 li 长列表（滚动找、没找到返回选项）、两列级联（成功；第 2 层没找到）、远程搜索（自动输入、search: true、歧义、无结果）、Ant Design 3 式只读日期框（同年近月用箭头；跨年经年份表 / 月份表；跨十年翻页）、原生 month / date、按 YYYY/MM 占位符键入，都得到预期结果；Java 里的脚本文本和检查过的 JS 逐字相同。

**和计划不同的地方**
- 日期面板的导航要点头部年月和翻页箭头，不只是「格子」：理解为只点面板里的东西，确定 / 今天 / 清除这类按钮一律不点。
- 选完列表还开着时按一次 Escape 之前先把焦点放回触发元素（Element / antd 的下拉在自己的输入框上处理 Escape 并阻止冒泡；焦点在 body 上时 Escape 可能关掉外面的对话框）。
- 结果里不回显选中的值和读回的值，只说「对得上 / 对不上」，避免 C 级真实值进模型上下文；值以新快照为准。
- 选下拉的 target 守卫只在角色是 button / generic 时做（名字里带「申请」的下拉框不会被误拒）。

### 修复（2026-10-25）：PopupView 遇到无角色行抛 NullPointerException

- 现象：申请 2026-10-24-011 在个人中心简历页点「Add」之后三次「出错：NullPointerException」，没有栈。
- 根因：`PopupView.options` 对 role 为 null 的行（`- /url:`、`- /placeholder:`、多行文字）调用 `Set.of(...).contains(null)`，不可变集合直接抛 NPE。F 只在下拉面板（每行都有角色）上检查过，点开含普通表单的新块时才触发。修法：角色为 null 的行跳过。
- 同时把 `ApplicationRunner` 记进状态的意外错误改成带类名和出错位置（`NullPointerException（位置：PopupView.options:119）`），日志带完整栈，Job 事件的 reason 带错误原文。
- 文件：`harness/context/PopupView.java`、`workflow/ApplicationRunner.java`；测试 `harness/context/PopupViewTest.java`。

## 实跑后修正（2026-10-25）

### 证据（申请 2026-10-24-010，网易 Ant Design 3 简历页，新构建）

和改前比：没有 15 秒超时，日期能设上。还剩：
1. 普通 antd3 下拉：`browser_select_option` 7 次全失败（「第 1 层没有找到要选的项…这一层没有看到选项」），选项其实在；每次失败下拉都还开着，脚本却报 `closed: true`。根因：`aria-controls` 找到的浮层在淡入时 `opacity` 是 0，`shown()` 把 `opacity === '0'` 当成不可见，读到的选项是空的，`close()` 也以为已经关了。
2. 弹出层视图（F）把整页挤出上下文：最新页面换成弹出层后，更早的快照都省略，说明里却写「那些元素的 ref 仍然能用」；模型看不到它们，按 Escape 再取整页（这次 4 次）。
3. 模型每一步重新分析整页：66 次调用里 38 次输出超过 1500 token，相邻调用中位间隔 16 秒；发回模型的 AssistantMessage 只有文字和工具调用，没有上一步的思考。
4. 来回提醒（G）触发 6 次，全是误报。
5. 要到日的 antd3 日期框给了 `YYYY-MM`：返回含糊的「读回的值对不上」，模型又用了 3 次调用。

### 用户的决定（2026-10-25）

1. 下拉脚本简单修：可见性不看透明度（照 Playwright：有大小、非 `visibility:hidden`、非 `display:none`）；打开后每 100 毫秒看一次、最多约 1.2 秒，有选项就走，不固定等；开关先看触发元素的 `aria-expanded`（没有时看弹出层可见性），最后还开着就把焦点放回触发元素按一次 Escape，`closed` 照实说。选下拉和设日期两条路都这样。
2. 弹出层视图：最新一步是弹出层时，上一份整页快照留在上下文里，不省略；说明改成准确的；能省就省，正确优先。
3. 同一次运行里把上一步的思考发回给模型（只对支持的供应商）；`ApplyAgent` 加一句：字段清单、已知选项、做过的决定记在 `update_plan` 里，照它做，不每步重列整页。
4. 整个去掉来回提醒（G）；C016 之前就有的三条重复提醒保留。
5. 要到日的日期框只给了年月：面板翻到那一年那一月（不点日），返回 `need_day`；模型用 ask_user 问用户是哪一天，回答后用完整日期再调 `browser_set_date`。原生 date 框给 `YYYY-MM` 同样返回 `need_day`。
6. 第 18 条放行：对话框、卡片、日期面板、级联里的「确定」「确 定」「完成」「确认」不再被硬拦，由模型判断（软边界）；系统永远不做最终提交：明确的提交类按钮仍硬拦；问「确认提交…? / 确定投递…?」的对话框里的确认按钮也仍硬拦。日期工具遇到要按「确定」的面板，让模型自己点。

### 实际结果

**1. 下拉 / 日期脚本**（`tools/PageScripts.java`）
- `DOM.shown` 和 `DATE` 里 `PANEL.shown` 去掉 `opacity` 判断。
- 新 `expandedOf`：触发元素自己、它里面、或外面最近的 combobox / `aria-haspopup` 祖先上的 `aria-expanded`；`op: 'open'` 先看它，没有这个属性时才看弹出层可不可见。`op: 'trigger'` 的 `expanded` 也用它。
- `SELECT`：新 `isOpen()`（带上触发元素）、`waitItems(level)`（每层第一次读选项时每 100 毫秒看一次、最多 1.2 秒）；`close()` 还开着时聚焦触发元素、按一次 Escape、最多再等 600 毫秒看它关没关，返回实情；级联等下一列、`typeFilter` 里的开关判断都改用 `isOpen()`。
- `DATE`：新 `panelOpen()`（带上日期框）、`viewNow()`（认不出是哪种表时每 100 毫秒再看、最多 1.2 秒）；`need_day` 见第 5 条。

**2. 弹出层视图留住整页**
- `Conversation.LatestPage` 加 `base`：最新一份是弹出层视图时，之前那份整页（之前也是弹出层视图时沿用它的整页）。`LatestPage.next` 计算它；`AgentLoop` 每次工具得到页面时用它。`Conversation.messages` 在整页原来的位置照原样渲染（和它当最新页面时一字不差，前缀缓存还能命中），不再写「旧快照已省略」。
- `PopupView` 说明改成「上一份整页快照还留在对话里：页面除了这个弹出层没有变，那份快照里的 ref 仍然能用」；新 `PopupView.isView` 按这句开头认出弹出层视图。
- `ApplyAgent` 工作方法、`apply-form` skill 同步改说法。
- token：弹出层这一步多带一份整页，但那份是上一步已经发过的同一段文字（在缓存前缀里）；没有前缀缓存时等于没有 F 时的大小再加弹出层。

**3. 发回思考**
- 现状：`ModelCaller.thinking` 读 Anthropic 带签名的 thinking 块、OpenAI 兼容格式 metadata 里的 `reasoningContent`（来自响应的 `reasoning_content` 或 `reasoning`），存进 `AgentRun.Turn.thinking`，只给界面看。Spring AI 2.0.1 `OpenAiChatModel` 发请求时，AssistantMessage 的 metadata 有非空 `reasoningContent` 就写成 `reasoning_content`（源码核实）；`AnthropicChatModel` 不读这个键。
- 供应商（2026-10-25 查 DeepSeek 官方文档 `api-docs.deepseek.com/guides/thinking_mode`）：带工具时，之前各轮的 `reasoning_content` 要发回、拼进上下文，同一轮工具调用里没发回会 400；不带工具时不用发回、发了也忽略。本机配置（`storage/settings/models.yaml`，只看非密钥项）：主模型、辅助模型是 OpenAI 兼容网关上的 `open-deepseek-v4.1-flash`，交叉检查是 `antigravity-3.8-flash-medium`；010 那次运行 95 条模型回复里 82 条带思考，平均约 5000 字。
- 改法：`Conversation.messages` 对最后一条用户消息之后（这次运行的开头、恢复时的回答、或插话之后）的模型回复，把存下的思考放进 metadata `reasoningContent`；更早的不发。OpenAI 兼容格式由 Spring AI 发成 `reasoning_content`，没有思考的模型（例如普通 OpenAI）什么都不多发；Anthropic 格式不发回（没开 extended thinking，签名也拼不回去，见 `ModelSettingsFile` 注释）。对话 Agent 共用同一段代码，也在一条用户消息之内发回。
- `ApplyAgent` 工作方法第 3 条加：计划里记字段清单（字段、值和来源、填了没有）、已知的下拉选项原文和做过的决定；之后照计划做下一件事，有变化用 `update_plan` 改，不每步重列整页。
- token：每次调用的输入多出这次运行里之前各步的思考，按 010 估算每步约 2000–3000 token，到第 n 步多约 n × 2500；一整次 60 多步的运行累计多几百万输入 token。这些都在不变的前缀里，网关有前缀缓存时多半按缓存价计；换来的是模型不必每步重新分析（输出 token 和间隔应该下降，要看真实跑）。
- 文件：`harness/context/Conversation.java`、`harness/runtime/AgentLoop.java`（一行）、`domain/AgentRun.java`、`persistence/ModelSettingsFile.java`（注释）、`agents/ApplyAgent.java`。

**4. 去掉来回提醒**
- `LoopDetector` 恢复成 C016 之前的样子（连续失败、相同结果、页面不变三条）；`HarnessLoop` 里的调用本来就是这三条用的，没动；提示词和 skill 里没有提到 G 的文字。

**5. 只给年月的要到日日期框**
- `DATE`：记下面板一开始是哪种表；一开始是日期表（要到日的控件）、只给了年月时，照常翻到目标年月（月份表点了月份回到日期表接着走），到了那个月不点任何日期，返回 `need_day`；能键入的框占位符或现有值带日时不键入半个日期，直接去面板；打不开面板时也返回 `need_day`。原生 date 框照旧不动它，返回 `need_day`。
- `PlaywrightMcpClient.setDate`：`need_day` 作为失败结果（报告里不记这个字段填好了）返回清楚的话：「need_day，没有设……面板已经翻到你给的那一年那一月，没有点任何日期……用 ask_user 问用户这一项是哪一天（这一页有几个就一起问），回答后用 browser_set_date 写完整的 YYYY-MM-DD 再设一次」。
- `ApplyAgent` 的「日期」一条、skill `date-picker`（version 3）同步。

**6. 第 18 条：确认类按钮放行**
- `SubmitWords`：硬边界词表去掉 确认、确定、完成、confirm、done；新增确认类 `CONFIRM_ZH`（确认、确定、完成）、`CONFIRM_EN`（confirm、done、ok），`isConfirm`；`isSoft` 包含确认类。
- `SubmitGuard.readOnly`（申请的点击守卫和调研的只读点击都经过它）：名字含硬边界词 → 拒绝（「确认提交」「确定投递」「完成申请」照旧）；名字是确认类时看 `around(快照, ref)`：最近的 `dialog` / `alertdialog` / `tooltip` 祖先的整棵子树，没有时是上一层的子树，在最外层时是整页（去掉 `/url:` 行），文字里有硬边界词 → 拒绝（「确认提交申请？」对话框里的「确定」）。其余确认类按钮走软边界：模型没给 `final_step_suspected` 交回模型补上；给 false 放行；给 true 先问用户。确认类不看「同一页还有提交按钮」这类页面信号（日期面板的「确定」不会每次都问）。
- `browser_click` 的说明改写，`purpose` 加 `confirm`。`PlaywrightMcpClient.setDate` 的 `needs_confirm` 改成让模型在新快照里用 `browser_click` 点面板上的「确定」（purpose: confirm，final_step_suspected: false）。脚本自己仍不点确定。
- `ApplyAgent`「系统会拒绝的操作」、skill `apply-form`（version 3）、`repeatable-entries`（version 2）同步。
- 文件：`harness/policies/SubmitWords.java`、`harness/policies/SubmitGuard.java`、`tools/BrowserTools.java`、`tools/PlaywrightMcpClient.java`、`agents/ApplyAgent.java`、上面三个 skill。

**测试**：`SubmitGuardTest` 提交类那组去掉单独的「确认 / Confirm / Done」，加上「确认提交」「确定投递」「完成申请」；新增两条：面板 / 卡片里的确认类按钮交给模型（没说明 → 交回模型，false → 放行，true → 问用户）；问「确认提交申请？」「确定投递该职位吗？」的对话框里的「确定 / 确 定 / 确认 / OK / Confirm」，带 `final_step_suspected: false`、带用户批准、调研的只读点击都拒绝。`FakeFormScriptTest` 的夹具检查：「Confirm」不再按名字算硬边界，改为断言它在那一页上仍被拒绝（最外层、整页有提交类按钮）。

**验证**：`backend/` 下 `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -B -q clean verify` 退出码 0。

**本地 headless 页面检查（2026-10-25，补做）**：scratchpad 里自己起的 `chrome-headless-shell`（Playwright 缓存的 153，`--remote-debugging-port=9333`，独立临时 profile）+ `python3 -m http.server`（127.0.0.1）放的本地 HTML；`@playwright/mcp@0.0.83` 走 stdio，参数照 `application.yml`（`--cdp-endpoint http://127.0.0.1:9333 --snapshot-mode none --timeout-action 15000 …`），先 `browser_snapshot` 取 ref，再把 `PageScripts.code(...)` 原样生成的代码（反射调用编译好的类）交给 `browser_run_code_unsafe`。没有连用户的 Chrome（9222），起的进程都已结束。
- antd3 式下拉（下拉层 opacity 0→1 淡入 250 毫秒、选项约 100 毫秒后才渲染、触发元素带 `aria-controls` / `aria-expanded`）：选「本科（全日制）」一次选中，`closed: true`、`verified: true`，页面上下拉已关。
- 同一个下拉，没有的「计算机」→ `not_found`，「本科」→ `ambiguous`（两项候选）：都返回全部 6 个选项原文，按一次 Escape 后 `closed: true`，页面上确实关了。
- Element 式两层级联（触发元素没有 `aria-expanded`，走弹出层可见性）：「浙江省 / 杭州市」`selected`、2 层、关了、读回对得上。
- 能搜索的下拉（每次输入都扔掉旧容器、换新 id 重画，`aria-controls` 不更新）：`search: true` 选「清华大学」、`search: false` 且目标不在初始列表里时脚本自己输入过滤选「浙江大学」，都 `selected`、关了，页面重画过容器（2 个）。
- antd3 式要到日的日期框（只读输入框，面板淡入并盖住输入框）给 `2026-12`：面板从 10 月翻到 12 月，没有点日，返回 `need_day`；同一页紧接着给 `2026-12-15`、新开页面给 `2026-12-15`：都 `set`、读回对得上。
- 原生 `<input type=date>` 给 `2026-12` → `need_day`，值没动；给 `2026-12-15` → `set`（native）。

**检查中发现并修掉的问题**
- `need_day` 之后面板还开着：antd3 的面板盖在日期框上，问完用户再用完整日期调 `browser_set_date` 时点不到日期框（`elementHandle.click: Timeout 3000ms exceeded … ant-calendar-input … intercepts pointer events`），脚本报错。改法：`DATE` 新 `closePanel()`，返回 `need_day` 之前把焦点放到面板里的输入框（没有时是日期框）按一次 Escape，最多等 600 毫秒，结果里带 `closed`；`PlaywrightMcpClient.setDate` 的话改成「面板翻到过……没有点任何日期，之后按 Escape 关上了」（没关上时仍说「面板还开着」）；skill `date-picker` 同步一句（本机还是 version 2，version 3 会送到）。改后同一页紧接着设完整日期成功。
- 弹出层视图的说明不准：「页面除了这个弹出层没有变」——连续打开的弹出层（级联第二列出来时第一列）、输入框里新键入的字、新出现但不带选项的部分，都不在留住的整页快照里，也不在弹出层视图里。`PopupView.NOTE`、`ApplyAgent` 那一句、skill `apply-form` 改成：上一份整页快照还在、ref 能用；它之后别的变化这里没列出，要看时用 `browser_snapshot` 取整页（不用按 Escape）。

**和决定不同的地方**
- `need_day` 作为失败结果返回（不是成功），这样填写报告不会把这个日期记成填好了。
- 确认类按钮多算了「OK」（以前不在硬边界里，问提交的对话框里的「OK」反而拦不住）；英文的 Finish、Complete 仍是硬边界（向导最后一步常叫这个）。
