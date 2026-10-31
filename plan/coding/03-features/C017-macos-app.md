# C017 · 打包成一个 macOS App，数据位置由用户选

- 对应 Master：[ui.md](../../master/ui.md)「前端框架：由 Spring Boot 提供静态文件」与「设置」；[architecture.md](../../master/architecture.md) `frontend/` 一行（第 7 段）；[memory.md](../../master/memory.md) storage 结构；[base.md](../../master/base.md)「后置：部署」。确认后回写：base.md 把这一项改成「本机 App：C017」；memory.md 加「storage 根目录由用户选，记在 storage 外面的 App 配置里」；ui.md 设置页加「数据位置」。
- 依据：用户（2026-10-24）：「这个项目可以打包成一个一体的 macOS app」；选了 Swift 原生窗口。用户（2026-10-27）：数据位置要在前后端设计成用户自己选，把打包的 plan 写下来。
- 状态：已施工，等用户双击试（2026-10-27）；master 回写已完成。
- 不做：签名公证、上架、自动更新；Intel Mac；Windows / Linux；自动搬数据（换位置时不复制、不移动、不删旧文件夹）；改 Agent、工具、浏览器连接方式（仍是用户正在用的 Chrome + `--cdp-endpoint chrome`）。

## 1. 现在要几样东西才能跑

| 现在 | 问题 |
| --- | --- |
| 终端里 `mvn spring-boot:run` + `npm run dev` 两个进程 | 双击打不开 |
| Playwright MCP 用 `npx -y @playwright/mcp@0.0.83` | 从 Finder 打开的 App 没有 nvm 的 PATH，找不到 `npx`；第一次还要联网下载 |
| 需要本机装 JDK 25 | App 要自带 |
| `storage.root: ../storage`（按启动目录算） | App 的启动目录是 `/`；而且位置写死在配置里，用户改不了 |
| 界面在 Chrome 标签页里（5173） | C011-fix 以后 Agent 看得到 Chrome 里所有标签页，界面自己也在里面 |

## 2. App 的样子

```
JobApply.app/Contents/
  MacOS/JobApply            Swift 外壳（WKWebView 窗口 + 启动 / 重启 / 关闭后端 + 选文件夹）
  Resources/runtime/        jlink 裁剪的 Java 25 运行时
  Resources/jobapply.jar    Spring Boot fat jar（现有构建产物）
  Resources/web/            frontend/dist
  Resources/node/           node 可执行文件 + 预装的 @playwright/mcp@0.0.83
```

1. **外壳**（Swift，`swiftc` 本机已有，不引入 Xcode 工程、Rust、Electron）：
   - 启动 `runtime/bin/java -jar jobapply.jar --spring.profiles.active=macapp`，等 `http://127.0.0.1:8088/` 能打开后在窗口里加载。
   - 退出时给 Java 发 SIGTERM，Spring 正常关闭，现有代码会关掉 MCP 和它的 node 子进程。
   - 后端起不来（端口被占、Java 报错）时窗口显示错误和日志路径，不静默。日志写 `~/Library/Logs/JobApply/`。
   - WKWebView 要补：文件选择框（附件上传）；`target=_blank` 链接交给系统默认浏览器。
   - 给网页一个小桥（`window.webkit.messageHandlers.jobapply`）：`pickFolder` 弹系统选文件夹框、`restartBackend` 重启后端（第 3 节用）。
   - 界面不在 Chrome 里，Agent 列标签页时看不到它。
2. **后端 `application-macapp.yml`**（只在 App 里生效）：
   - `browser.mcp.command` 指向 `Resources/node/bin/node`，args 第一项换成预装的 `@playwright/mcp/cli.js`，其余照抄。路径由外壳经环境变量 `JOBAPPLY_RESOURCES` 传入。
   - `spring.web.resources.static-locations: file:${JOBAPPLY_RESOURCES}/web/`：Spring Boot 直接给界面。
   - `api.allowed-origins` 加 `http://127.0.0.1:8088`。
   - 端口固定 8088（用户 2026-10-27：本机 8080 被 Docker 占着）。开发版仍是 8080，两边能同时跑；别让它们用同一份 storage 同时运行。
3. **构建脚本** `macos/build-app.sh`（新顶层目录 `macos/` 放外壳源码和脚本）：`npm run build` → `mvn -B package -DskipTests` → `jdeps` 算模块后 `jlink` → 临时目录里 `npm install @playwright/mcp@0.0.83`、复制本机 node → `swiftc` → 拼成仓库根目录下的 `appBuild/JobApply.app`（`appBuild/` 进 .gitignore，用户 2026-10-27）→ `codesign -s -`。用户拖进「应用程序」即可。预计约 350MB。

## 3. 数据位置：用户自己选

**存哪**：storage 根目录记在 storage 外面的一个小文件 `~/Library/Application Support/JobApply/app.yaml`（`storageRoot: /绝对路径`）。它只放这一项，不放密钥。`.env`、`personal.env` 仍在所选 storage 的 `settings/` 下，跟着数据走。

**后端**：
- 启动时按顺序取根目录：命令行 / 环境变量 `JOBAPPLY_STORAGE_ROOT` → `app.yaml` → `application.yml` 默认（开发时仍是 `../storage`，开发流程不变）。`StorageProperties` 只有两处引用，改的是它取值的地方，其余代码不动。
- 新接口：
  - `GET /api/app/storage`：当前根目录、来源（命令行 / app.yaml / 默认）、是否像已有数据（有 `projects/` 或 `settings/`）。
  - `POST /api/app/storage/check {path}`：只读检查——是绝对路径、存在或能建、可写；是空文件夹还是已有 JobApply 数据。不写任何东西。
  - `PUT /api/app/storage {path}`：检查通过后写 `app.yaml`，返回「重启后生效」。有申请或对话正在跑时返回 409，说明哪些在跑，不强行切。
- 不支持运行中热切换：storage 被各处缓存（事件日志、Worker 池、索引），切换就重启后端，最干净。

**前端**：
- **第一次打开**（没有 `app.yaml`、也没有命令行指定）：一屏「选数据位置」，两个选项——默认 `~/Library/Application Support/JobApply/storage`，或「选择文件夹…」（可以选仓库里已有的 `storage/`）。选完显示检查结果（「空文件夹，将新建」/「发现已有数据：N 个 Project」），确定后保存并重启后端，进入主界面。
- **设置页「数据位置」**：显示当前路径和来源，「在 Finder 中显示」「更改…」。更改走同样的检查，保存后提示「已切换，正在重启」，外壳重启后端、窗口重新加载。说明一句：旧文件夹原样保留，要带走数据请自己挪。
- 「选择文件夹…」在 App 里走外壳的 `pickFolder`；在开发版（Chrome 里，没有桥）退成一个路径输入框。
- 重启：App 里调外壳 `restartBackend`；开发版只提示「请重启后端」。

## 4. 怎么确认能用

- `macos/build-app.sh` 成功，产出 `JobApply.app`。
- 从 Finder 双击：首次出现「选数据位置」；选仓库 `storage/` 后看到自己的 Project；设置页能看到模型；对话能拿到回复；连上 Chrome 后让申请 Agent 打开一个岗位页；附件能上传。
- 设置页换到一个空文件夹 → 重启后是空的；再换回来 → 数据都在，旧文件夹没被动过。
- 有申请在跑时更改位置被拒，说明原因。
- Cmd+Q 后没有残留 java / node 进程。
- 开发流程（`mvn spring-boot:run` + `npm run dev`）照旧用 `../storage`。

## 5. 已定

- 窗口：Swift WKWebView 外壳（用户，2026-10-24）。
- 数据：位置由用户在首次打开和设置页里选，可以选仓库里的 `storage/`（用户，2026-10-27）。

## 实际结果（2026-10-27）

- 外壳：`macos/JobApply/main.swift`（启动前查 8088、等就绪、错误页带日志路径、退出 SIGTERM→10 秒后 SIGKILL、桥 `pickFolder` / `restartBackend` / `reveal`、文件选择框、外链交给默认浏览器、菜单）。
- 构建：`macos/build-app.sh` 六段；JDK 用 `JOBAPPLY_JAVA_HOME` 覆盖，node 用 `NODE_BIN` 覆盖；图标由 `macos/make-icon.swift` 从仓库根目录 `icon.png` 裁出图案做成 `AppIcon.icns`（用户 2026-10-27 要求）。产物 335MB。
- 后端：新 `config/StorageLocation.java`（取值顺序、`app.yaml` 读写、只读检查）、`api/AppStorageController.java`（`GET /api/app/storage`、`POST .../check`、`PUT`，有运行中的申请 / 对话时 409）、`application-macapp.yml`（8088、静态文件、Origin、MCP 用自带 node + `cli.js`）。
- 前端：`shell.ts`（桥）、`pages/StorageLocation.tsx`（首次打开的「选数据位置」+ 设置页「数据位置」），`main.tsx`、`Settings.tsx`、`api.ts`。

## 偏差

- 还没选位置时，浏览器初始化脚本写到 `$TMPDIR/jobapply-unchosen`，免得启动时就建出默认的 storage 文件夹。
- 就绪判断把任何 HTTP 回应都算就绪（含 404）。
