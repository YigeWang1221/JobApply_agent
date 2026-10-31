# C012 · 在界面上改 Worker 数量

- 依据：用户（2026-10-24）「修改为支持前端改 worker 数量」。要求先写计划，接着施工。
- 现状：C011 的 Worker 池大小在启动时定死（`jobapply.harness.application-workers` 默认 3、`applications-per-site` 默认 2，`HarnessProperties` / `application.yml`），改了要重启或加命令行参数。
- 状态：已施工，等用户试（2026-10-24）。

## 目标

设置页能改「同时跑几个申请」和「同一个网站最多几个」，保存后马上生效、重启后还在。

- 调大：排队的马上按新上限开始。
- 调小：在跑的不停，只是不再放新的，等在跑的数降到新上限以下再放。

## 不做

- 不改 `HarnessProperties` 的其它项（工具调用上限、重复提醒）进界面。
- 不改浏览器层、不改排队顺序和同站点规则本身。
- 不加确认弹窗、不加预算。

## 做法

1. `ApplicationRunner`：两个上限从 `final` 的配置改成锁里的可变值；`dispatch()` 用当前值。执行器从固定大小线程池换成按需建线程的池（`newCachedThreadPool`），并发数只由 `dispatch()` 管（它本来就在管）。新增「读当前设置 + 在跑 / 排队数」和「改设置」两个方法：改设置先校验、写文件，再换内存里的值并 `dispatch()` 一次。
2. 存盘：`storage/settings/workers.yaml`（不含密钥），两项 `application_workers`、`applications_per_site`。启动时有这个文件就用它，没有就用 `application.yml` / 命令行的值（它们仍是默认值）。文件内容不合法时日志警告并用默认值。
3. 范围：Worker 1–6，每站 1 到 Worker 数；超出返回 400。
4. 接口：`GET /api/settings/workers`、`PUT /api/settings/workers`（JSON 体 `{workers, perSite}`），返回 `{workers, perSite, maxWorkers, running, queued}`。照 `ModelSettingsController` 的写法，写接口仍受 `LocalRequestGuard` 约束。
5. 前端：设置页「模型」标签下加一张卡片「同时申请」：两个数字框、一句说明（多开 = 多几个 Chrome 标签页同时操作、token 用得更快；调小不会停掉在跑的）、保存 + 轻提示、显示当前在跑 / 排队数。

## 怎么确认能用

- `backend/`：`mvn -B -q clean verify` 通过；`ApplicationRunnerPoolTest` 加一条：调小上限不影响在跑的、也不放新的；调大马上放排队的（假模型门闩，确定性）。
- `frontend/`：`npm run build` 通过。
- 真实应用、真实模型、真实 Chrome：未运行（用户的应用在 8080 上跑着），由用户重启后在设置页试。

## 实际结果

2026-10-24，按上面做法施工，没有偏差。路径相对 `backend/src/main/java/jobapply/`。

- `workflow/ApplicationRunner.java`：`workerLimit` / `perSiteLimit` 锁里可变，`dispatch()` 用它们；执行器换成 `newCachedThreadPool`（线程名仍是 `application-worker-N`）；新增 `concurrency()`、`setConcurrency(workers, perSite)`（校验 → 写文件 → 换值 → `dispatch()`）、`MAX_WORKERS = 6`。启动时 `workers.yaml` 读不了或不是正数时日志警告、用配置的值。
- `persistence/WorkerSettingsFile.java`（新）、`StorageLayout.workersYaml()`：`storage/settings/workers.yaml`，两项 `application_workers`、`applications_per_site`。
- `api/WorkerSettingsController.java`（新）：`GET /api/settings/workers` → `{workers, perSite, maxWorkers, running, queued}`；`PUT /api/settings/workers`，体 `{workers, perSite}`，缺项或超出范围 400（走 `ApiErrors` 的 `IllegalArgumentException`）。
- `HarnessProperties`、`application.yml`：注释说明这两项只是默认值。
- 前端：`frontend/src/pages/WorkerSettings.tsx`（新）放在设置 →「模型」标签的最下面；`api.ts` 加两个调用和类型；保存后轻提示「已保存，立即生效」，显示「现在：N 个在跑，M 个排队」。
- 测试：`ApplicationRunnerPoolTest` 加一条（2 个在跑、1 个排队；超出范围被拒且不写文件；调到 1 后在跑的两个不停、一个结束后排队的仍不放；调回 2 马上放；文件存在）。

验证：`mvn -B -q clean verify` 退出码 0（surefire 612、failsafe 1）；池测试单独连跑 5 次都通过；`npm run build` 通过（只有原来就有的包体积提示）。真实应用、真实模型、真实 Chrome：未运行。
