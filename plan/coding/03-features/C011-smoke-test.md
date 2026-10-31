# C011 · 用户自己的 Chrome 上的冒烟清单（两个 Worker 并发，不提交）

- 依据：[C011](C011-concurrent-workers.md) 第 7 节最后一步「在用户的 Chrome 上做一次手动冒烟」、「实际结果 · 浏览器层 · 用户在自己 Chrome 上冒烟时看什么」；[C010](C010-steer.md) 插话。
- 谁做：主会话和用户一起做。用户点 Chrome 的「允许」、登录、看屏幕；主会话发下面的接口请求、跑观察脚本、记结果。
- 观察脚本：[`c011_smoke_observe.py`](c011_smoke_observe.py)（只读，只用标准库；不打印填写的值、工具参数、页面标题、插话原文）。
- 结果写进 `dev-log.md`（没跑的写「未运行」），细节回填到本文件最后的「实际结果」。

## 硬性规则

- **严格不提交**。系统本来就不点最终提交（提交守卫）；这次冒烟里：用户不点任何「提交 / Submit / Send application」；主会话不调 `POST /api/jobs/{id}/submitted`，不在 Session 里说「已提交」，不让 Session Agent 调 `mark_submitted`。哪个申请停在提交前（`ready` / `awaiting_review`）就停在那里，不往前走。
- 只在用户这次给的岗位网址上操作；「登录好了」只在用户说了以后才发。
- 不改 `application.yml`；需要的设置只用启动参数。不改代码。
- 原始事件流（`/api/events`）的 `step` 里有工具参数（填进去的值）：主会话不把原始事件流贴进对话，用观察脚本看。

## 已知未解决问题（必须单独列出）

- **K1 同站点弹窗 + 对话框卡住**（C011 第 5.1 节、浏览器层矩阵最后一行）：同一个网站上两个页（一个申请开着同站弹窗）时，这个申请的页上弹 alert / confirm，点击整次调用不返回（60 秒调用超时），同站另一页的列表调用也会卡；切前台没用，要用户在 Chrome 里手动关掉对话框或那一页。
- 这次冒烟**不故意触发 K1**（推荐方案里三个岗位在三个不同网站）。但只要出现下面任何一种，就把当时那一项记成 **「失败（已知问题 K1）」，不算通过**：
  - 某个申请的一次浏览器调用 ≥ 30 秒没返回（观察脚本 (a) 来源 2「每个线程最长的一次调用」≥ 30000 ms，或工作台上一步一直转圈），并且页面上有对话框；
  - 需要用户手动关对话框、关页，或者手动点「重新连接」才能继续。

## 0. 准备

### 0.1 用户要给的东西

- **3 个真实岗位网址**：J1、J2、J3，**三个不同的网站**（至少 J1 和 J2 不同网站；网站按注册域名算，例如 `a.greenhouse.io` 和 `b.greenhouse.io` 是同一个网站）。
- 选用户愿意「填到提交前、不提交」的岗位。注意：有的网站（例如 Workday 类）填写时会自动在网站上存草稿，这算在网站上留下了未提交的申请草稿，用户事先知道并同意。
- 最好是不用登录、或者用户已经在 Chrome 里登录好的网站，减少等待。
- Chrome：`chrome://inspect/#remote-debugging` 的远程调试开关打开（和平时一样）。

### 0.2 怎么让第三个申请「排队」（选最不打扰的）

`HarnessProperties`（`@ConfigurationProperties("jobapply.harness")`）里有 `applicationWorkers`（默认 3）和 `applicationsPerSite`（默认 2），Spring Boot 认命令行参数 `--jobapply.harness.application-workers=2`（代码里有这个绑定；实际效果在第 3 步看 J3 是不是 `queued` 来确认）。

| 方案 | 做法 | 为什么 |
| --- | --- | --- |
| **推荐：Worker 数 2 + 3 个不同网站的岗位** | 启动时加 `--jobapply.harness.application-workers=2` | 只用 3 个岗位；同时只有 2 个标签页在动；三个网站不同，不故意走 K1；不改文件 |
| 备选：默认设置 + 同一个网站的 3 个岗位（每站上限 2） | 不加参数 | 不用重启应用；但故意让两个申请在同一网站上并发，更容易碰到 K1，出现了就按上面记失败 |
| 不选：4 个岗位 | 默认 3 个 Worker | 多填一个真实申请，最打扰 |

下面按推荐方案写。

### 0.3 启动（谁启动按用户安排；主会话的子代理不启动）

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
SCRATCH=<主会话的 scratchpad 目录>
cd backend && mvn -B -q -DskipTests package \
  && java -jar target/jobapply-0.0.1-SNAPSHOT.jar --jobapply.harness.application-workers=2 2>&1 | tee "$SCRATCH/app.log"
# 用户想看界面时另开：cd frontend && npm run dev   # http://127.0.0.1:5173
```

应用日志要 `tee` 下来：观察脚本 (a) 来源 2 用里面的「浏览器工具 X，N ms」行（毫秒时间 + 线程名）。

### 0.4 主会话的变量

```bash
BASE=http://127.0.0.1:8080
H='Content-Type: application/json'          # 所有 POST 都要带（LocalRequestGuard：写请求只收 JSON）
OBS=plan/coding/03-features/c011_smoke_observe.py         # 在仓库根目录执行
curl -s $BASE/api/projects | python3 -c 'import json,sys; [print(p["id"]) for p in json.load(sys.stdin)["projects"]]'
PROJECT=<上面列出的、用户指定的 Project id>
```

只看状态的小命令（不打印问题、计划原文）：

```bash
st() { curl -s $BASE/api/jobs/$1/application | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("job"), d.get("status"), d.get("prepState"), "未取走插话", len(d.get("inserted") or []))'; }
```

## 1. 基线 + 只弹一次允许

| | |
| --- | --- |
| 步骤 | 主会话：`curl -s $BASE/api/browser` 记下（应用刚启动时是 `not_connected`、`generation` 0）。主会话：`curl -s -X POST -H "$H" -d '{}' $BASE/api/browser/reconnect`。**用户**：Chrome 弹出允许提示时点「允许」，并数一下弹了几次。主会话再 `curl -s $BASE/api/browser`。 |
| 通过 | 用户只看到 **1 次**允许提示；之后 `state=connected`、`generation=1`。 |
| 失败 | 弹了 2 次以上（这一步或之后任何一步）；`generation` 不是 1；`disconnected`。 |

这一项在整个冒烟结束时才定：之后每一步都不该再弹（用户一直数着，最后报总数）。

## 2. 开始记录

```bash
python3 $OBS watch --out "$SCRATCH/c011-rec.jsonl" J1 J2 J3 &    # 第 3 步拿到 Job 编号后再起；后台一直跑到第 8 步
```

脚本订阅全局事件流（只记工具名、状态、时间），每秒查一次 `GET /api/browser` 和每个 Job 的状态，状态变了打印一行。

## 3. 建 Session、记下 3 个岗位、并发开始

```bash
# 建 Session
SID=$(curl -s -X POST -H "$H" -d '{"title":"C011 冒烟"}' $BASE/api/projects/$PROJECT/sessions \
      | python3 -c 'import json,sys; print(json.load(sys.stdin)["session"]["id"])')

# 让 Session Agent 只记成候选，不开始申请
curl -s -X POST -H "$H" $BASE/api/sessions/$SID/messages -o /dev/null -w '%{http_code}\n' \
  -d '{"text":"把下面 3 个岗位记成候选（add_lead），不要开始申请，也不要选简历：\n<J1 网址>\n<J2 网址>\n<J3 网址>"}'

# 等它回完（status 变 idle）
curl -s $BASE/api/sessions/$SID | python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])'

# 拿 Job 编号（只打印编号、网站、能不能开始）
curl -s $BASE/api/projects/$PROJECT | python3 -c '
import json,sys
for j in json.load(sys.stdin)["jobs"]: print(j["id"], j["site"], j["phase"], "canStart=%s" % j["canStart"])'
J1=...; J2=...; J3=...
```

用户之前已经有这几个岗位的 Job 时，直接用已有的编号（`canStart` 要是 true），跳过 `add_lead`。

起观察脚本（第 2 步），然后**紧挨着**开始三个申请：

```bash
for J in $J1 $J2 $J3; do
  curl -s -X POST -H "$H" -d "{\"project\":\"$PROJECT\",\"session\":\"$SID\"}" $BASE/api/jobs/$J/application \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("job"), d.get("status"))'
done
st $J1; st $J2; st $J3
```

| | |
| --- | --- |
| 通过 | J1、J2 `running`；J3 `queued`。用户看到 Chrome 里多了 2 个新标签页（J1、J2 各一个），没有新的允许提示。 |
| 失败 | J3 也是 `running`（Worker 数参数没生效：先 `stop` J3，查启动参数，再来）；任何一个 409 / 400（看返回的 `error`）；弹了新的允许提示。 |

## 4. 排队中插话（QUEUED）

**确认 J3 还是 `queued` 再发**（`st $J3`）。

```bash
curl -s -X POST -H "$H" $BASE/api/jobs/$J3/application/reply -w '\n%{http_code}\n' \
  -d "{\"session\":\"$SID\",\"text\":\"[SMOKE-Q] 测试插话：开始以后第一件事先调用 update_plan，在计划最前面加一项「SMOKE-Q 已收到」，然后照常做。不要提交。\"}" \
  | tail -1
st $J3
```

| | |
| --- | --- |
| 发的时候 | 返回 202；`st` 显示 `queued`、未取走插话 1。 |
| 通过（第 7 步 J3 开始以后看） | 观察脚本 (d)：J3 有一条「插话」，标记 `SMOKE-Q`，「之前的模型回复数」= 0（这次运行第一次调模型之前就到了）；轮询表里「当时的 status」含 `queued`；下一次模型回复的工具里有 `update_plan`，计划里出现 `SMOKE-Q`。 |
| 部分 | 插话送到了（位置、状态都对），但模型没按它改计划：记「机制通过、模型没照做」，不算完整通过。 |
| 失败 | 409 / 400；发的时候已经不是 `queued`（那就不是这项测试，换一个排队的再做）；J3 开始后对话里没有这条插话（丢了）；这条变成了「普通回复」。 |

## 5. 两个 Worker 同时动作、各在自己的标签页

J1、J2 在跑时：

- **用户**：看 Chrome。两个申请各在自己的标签页里动作；前台页不会每个动作都来回跳（只有开页、切到自己的弹窗时会切一次前台）。中途切到第三个标签页看一会儿、或者把窗口最小化一会儿，再回来。
- 申请要登录时（状态 `waiting_user`，消息说要登录）：**用户**登录，说「好了」以后主会话才发：
  ```bash
  curl -s -X POST -H "$H" -d "{\"session\":\"$SID\",\"text\":\"登录好了\"}" $BASE/api/jobs/$J1/application/reply -o /dev/null -w '%{http_code}\n'
  ```
- 申请问问题、要选简历时：用户愿意就照常回答（`answers` / `files` 的格式见 `ApplicationController.ReplyRequest`），不愿意就直接让它停在这里，不影响并发的证据。
- 让两个申请同时跑几分钟，至少都进了申请表、各自做了 10 次以上浏览器调用。中途跑一次报告看进度：
  ```bash
  python3 $OBS report --rec "$SCRATCH/c011-rec.jsonl" --app-log "$SCRATCH/app.log" $J1 $J2 $J3
  ```

| | |
| --- | --- |
| 通过 | (a) 来源 1：J1–J2 「重叠次数」≥ 1、最大重叠 > 0 ms；来源 2：两个 `application-worker` 线程之间也有重叠（MCP 层同时在调用）。(b)：J1、J2 的页面地址都在自己的网站上，没有 `FOREIGN` / `CHECK` 标记；`browser_tabs` 返回里没有别的 Job 的网址。用户没看到任何一个申请在别人的标签页上打字 / 点击；看第三页、最小化时点击没有一直超时。 |
| 不确定（不算通过） | 两个都做了 10 次以上浏览器调用、时间上也同时在跑，但重叠次数是 0：再让它们跑一段；还是 0 就记「未证明重叠」。 |
| 失败 | 有 `FOREIGN` 标记或用户看到动作落在别人的页上；(e) 的「浏览器调用失败」里大量点击超时（尤其用户切走 / 最小化以后）；出现 K1 的样子（按上面记「失败（已知问题 K1）」）。 |

## 6. 运行中插话（RUNNING）

挑 J2（或 J1），**确认是 `running` 再发**（`st $J2`；如果是 `waiting_user`，先回复让它接着跑，等变回 `running`）。

```bash
curl -s -X POST -H "$H" $BASE/api/jobs/$J2/application/reply -w '\n%{http_code}\n' \
  -d "{\"session\":\"$SID\",\"text\":\"[SMOKE-R] 测试插话：下一步先调用 update_plan，在计划里加一项「SMOKE-R 已收到」，然后照常继续。不要提交。\"}" \
  | tail -1
st $J2; sleep 5; st $J2
```

| | |
| --- | --- |
| 发的时候 | 202；`st` 显示 `running`、未取走插话 1；几秒到几十秒后（当前这批工具做完、下一次调模型之前）变成 0。 |
| 通过 | (d)：J2 有一条「插话」，标记 `SMOKE-R`，「之前的模型回复数」> 0；轮询表「当时的 status」= `running`；下一次模型回复里有 `update_plan`，计划里出现 `SMOKE-R`。正在执行的浏览器动作没有被打断（(e) 没有因此多出失败）。 |
| 部分 | 送到了，模型没照做：记「机制通过、模型没照做」。 |
| 失败 | 409 / 400；变成「普通回复」（发的时候已经不在跑，重做）；插话一直没被取走而运行还在继续；丢了。 |

## 7. 空出名额，J3 开始（顺带：新 Worker 不再弹允许）

```bash
curl -s -X POST -H "$H" -d '{}' $BASE/api/jobs/$J1/application/stop | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("job"), d.get("status"))'
# 当前这次工具调用之后停下；然后 J3 应该从 queued 变 running
st $J1; st $J3
```

| | |
| --- | --- |
| 通过 | J1 停下（`waiting_user`）；J3 变 `running`，开了自己的新标签页；**没有新的允许提示**；(c)：连接代数一直是 1；应用日志「启动浏览器 MCP」1 次、「使用者的浏览器会话断了」0 次。然后回到第 4 步看 SMOKE-Q 的证据。J3 和 J2 同时跑时，(a) 里 J2–J3 也应该有重叠（加分项，不是必需）。 |
| 失败 | J3 一直 `queued`；弹了新的允许提示；代数变了；J2 被 J1 的停止影响（J2 的状态或动作出错）。 |

## 8. 收尾（不提交）

```bash
for J in $J2 $J3; do curl -s -X POST -H "$H" -d '{}' $BASE/api/jobs/$J/application/stop -o /dev/null; done
st $J1; st $J2; st $J3
kill %1     # 结束 watch（或 Ctrl-C）
python3 $OBS report --rec "$SCRATCH/c011-rec.jsonl" --app-log "$SCRATCH/app.log" --since <开始申请前的时间，例如 2026-10-24T15:00:00+08:00> $J1 $J2 $J3
```

已经停在提交前（`ready`）的就让它停着。用户自己决定要不要关掉这些标签页、要不要在网站上删掉草稿。

| | |
| --- | --- |
| 通过 | (e)：三个都「没有」走到已提交，`phase` 都是 `prep`，`页面像已投递` 都是否、`appliedAt` 为空。用户最后报的允许提示总数是 1。 |
| 失败 | 任何一个走到已提交 / 页面像已投递：**马上停下来查**，这是越线。 |

## 观察脚本

```bash
python3 plan/coding/03-features/c011_smoke_observe.py watch  --out REC.jsonl [--interval 1] [--duration 秒] JOB...
python3 plan/coding/03-features/c011_smoke_observe.py report --rec REC.jsonl [--app-log APP.log] [--since ISO] [--storage DIR] JOB...
# 两个子命令前都可以加 --base http://127.0.0.1:8080
```

| 表 | 看什么 | 证据强弱 |
| --- | --- | --- |
| (a) 来源 1 | watch 记录：收到 `activity`（工具开始）到收到 `step`（工具结束），本机毫秒时钟，每个 Job 一组区间，两两算重叠次数和最大重叠 | **强**（毫秒；包括 Java 端每次动作前的标签页核对和动作后的快照；没开 watch 就没有） |
| (a) 来源 2 | 应用日志「浏览器工具 X，N ms」：行时间 = 调用结束（毫秒），开始 = 结束 − N，按线程（`application-worker-N`，日志里可能截成 `cation-worker-N`） | **强**，是 MCP 层真正的调用区间；但线程名不带 Job 编号 |
| (a) 来源 3 | `storage/runs/<job>.json` 工具结果的 `startedAt` + `ms` | **弱**：`startedAt` 只到秒；分「一定重叠」「可能重叠」；只有最近一次开始之后、已经存盘的轮次 |
| (b) | `run-log.jsonl` 每次浏览器调用后的 `url_path`（自己会话里的快照地址，已去掉查询参数）；`browser_tabs` 返回里的网址 | 中：能发现落到别的网站上；同一网站的两个申请只能按岗位地址提示 `CHECK`，要人看 |
| (c) | `GET /api/browser` 的 `state` / `generation`（watch 每秒一次 + 事件流 + 报告时一次）；应用日志里服务进程启动次数、会话断开次数 | 代数只证明应用没重连；**允许提示的次数以用户看到的为准** |
| (d) | 对话里「用户插话：」那条的位置、标记、下一次模型回复的工具名；watch 轮询里插话没被取走时的状态；计划里的标记 | 强（位置 + 轮询状态）；模型照不照做看 `update_plan` / 计划标记 |
| (e) | `GET /api/jobs/{id}` 的 `phase`、`prepState`、`pageLooksSubmitted`、`appliedAt`，申请状态，守卫判断次数，浏览器调用失败次数 | 强 |

`run-log.jsonl` 是追加的，旧运行的记录也在里面；报告时用 `--since` 只看这次。

## 实际结果

（冒烟后填写：每一项通过 / 部分 / 失败 / 失败（已知问题 K1）/ 未运行，允许提示总数，观察脚本的关键数字。）
