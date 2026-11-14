# C026-fix · 查状态遇到要登录 / 没找到状态页：整批查完后在 Chrome 里一次开好给用户

- 对应：[C026](C026-portal-status-check.md)；Master [agent-runtime.md](../../master/agent-runtime.md) 7.9「查状态」。
- 依据：用户（2026-11-14）：
  1. 「检查进度时，要登录的门户被标成手动登录，标签页就关了。我需要这些页留着，一个公司一页，我登录后再点第二次检查进度，Agent 就能查到。」
  2. 第一版（交还登录页 + 让模型复用开着的页）用户试过不对：登录后查到了的页没有自动关；有些页反复被打开。用户改定逻辑（同日）：并发照旧；每次查完都关自己的页，不管结果；要登录、没找到状态的进一个清单；整批查完后把清单里的页一次打开，一个公司 / 网站一页、Chrome 里已开着的网站不再开，这些页属于用户、以后不被自动关；下次查状态照常纳入要登录的，Agent 自己开页、查、关。
- 状态：**已施工（第二版），等用户在本机试（2026-11-14）**。

## 目标

一批「查状态」：每份申请查完就关掉自己开的页。要登录、没找到状态页的记进这一批的清单；整批查完后，系统在用户的 Chrome 里把清单里的门户一次打开（一个网站一页，已经开着的网站不再开），给用户登录或自己看。用户登录后再点「查状态」，这些申请照常被查。

不做：不替用户登录；不让模型去找、复用开着的页；不加确认框、不加新状态；不改并发、核准列表、查看间隔；单个（工作台）查状态不开清单。

## 第一版为什么不对（根因，按代码推断；本机 storage 里没找到当天的后续段记录，未从日志确认）

- **(a) 查到了的页没关**：第一版把登录页「交还给用户」（清掉名下记录），下次查状态全靠模型自己 `browser_tabs list` 再 `select` 它。模型没切过去、直接 `browser_navigate` 时（`browser_navigate` 总是新开一页，`harness/runtime/ReadOnlyTab.java:69-81`），跑完只关它自己新开的那页，交还的那页没有主人，谁都不会关。就算切过去了，关页也要求这个网址在 Chrome 里只有一页（`tools/OwnerBrowser.java` `closeOwn` 的 `sure`），重复的页不关。
- **(b) 页反复被打开**：同样的原因。每次遇到登录都交还一页；下次模型不复用、又新开一页再遇到登录，又交还一页。交还的页不归任何运行，越积越多。
- 共同的根因：「一家公司一页」靠模型每次选对页，系统里没有一个地方真的去重、也没有人负责这些页。

## 现在的做法（第二版）

1. **每次查完照旧关页**：`workflow/FollowupRunner.java` 跑完仍是 `Leave.CLOSE_ALL`（C026 原样）；第一版的 `HAND_BACK`、`CLOSE_WITH_CURRENT` 和「先 `browser_tabs list` 复用开着的页」的提示词都撤掉了。
2. **这一批的清单**：`FollowupRunner.checkFinished` 记结果时，`needs_login` 和 `not_found` 的申请记进 `checkToOpen`（Job → 网址：Agent 记下的 `portal_check.url`，没有时用投递网址）。
3. **整批查完一次打开**：排队和在查的都空了时，在线程池里先过一遍网址检查（`WebFetch.checkUrl`），再调 `BrowserSession.openForUser(urls)`：用保活会话列一次 Chrome 的标签页，已经开着的网站跳过，清单里同一个网站只开第一页，用 `browser_tabs new` 新开。保活会话开的页不记在任何运行名下，以后谁停下都不关。开了哪些记进 `StatusChecks.opened`。
4. **界面**：`CheckBar.tsx` 照旧不跳过要手动登录的（第一版改的保留）；批量结束后再取两次进度，显示一行「已在 Chrome 里打开 n 页给你登录 / 查看：主机名…」（悬停看完整网址）。按钮提示和「要手动登录」chip 的提示改成整批查完后会打开那家的页。`api.ts` 的 `StatusChecks` 加 `opened`。

## 怎么确认能用

- `backend/` `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -B clean verify`、`frontend/` `npm run build` 退出码 0。
- 用户在本机，用真实 Chrome 和真实模型：
  1. 筛出几份已投递的申请，里面有要登录的门户。点「查状态」：查的过程中每份申请开的页查完就关。
  2. 整批查完后，Chrome 里一次多出要登录 / 没找到状态页那几家的页，一个网站一页；同一家公司两份申请只开一页；已经开着这个网站的页时不再开。查状态栏显示「已在 Chrome 里打开 n 页…」。
  3. 在这些页里登录，再点「查状态」：按钮个数包含这些申请；Agent 自己开页查到状态，查完关掉自己的页。你登录用的那页不会被系统关。

## 施工记录（2026-11-14）

- 第一版（已撤回）：`Leave.HAND_BACK` / `CLOSE_WITH_CURRENT`、`FollowupRunner.leaveAfter`、`FollowupAgent.CHECK` 和 `FollowupLoop` 回执里「先找开着的页」，用户试过不对，代码已还原到 C026。
- 第二版：
  - `tools/BrowserSession.java`：`openForUser(List<String>)`（保活会话列页、按 `BrowserToolPolicy.siteOf` 去重、`browser_tabs new`；单页开不成记日志跳过，连接断开 / 卡住照常上报）。
  - `workflow/FollowupRunner.java`：`StatusChecks` 加 `opened`；`checkToOpen` / `checkOpened`（新一批时清空）；`checkFinished` 记清单，整批空了调 `openForUser`（线程池里跑，开完发一次 `changed`）。
  - 前端：`api.ts`、`dashboard/CheckBar.tsx`、`status.tsx`。
  - Master：`agent-runtime.md` 7.9「查状态」那一条改成第二版。
- 验证：`frontend/` `npm run build` 退出码 0；`backend/` `mvn -B clean verify` 退出码 0（单元 668、IT 1）。真实 Chrome、真实模型下的查状态：未运行（用户手动试）。
- 已知：用户中途点「停止」时，已经查完的里面要登录 / 没找到的也会在整批结束时打开。
