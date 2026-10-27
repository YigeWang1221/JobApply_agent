# C017 · 打包成一个 macOS App

- 对应 Master：[ui.md](../master/ui.md)「前端框架：由 Spring Boot 提供静态文件」、[architecture.md](../master/architecture.md) `frontend/` 一行（第 7 段）；[base.md](../master/base.md)「后置：部署」——本计划只做**本机自用的 .app**，确认后在 base.md 把这一项改成「本机 App：C017」。
- 依据：用户（2026-10-24）：「这个项目可以打包成一个一体的 macOS app」。
- 状态：计划，等用户确认（2026-10-24）。
- 不做：签名公证、上架、自动更新、dmg 安装包、给别人分发；Windows / Linux；改 Agent、工具、浏览器连接方式（仍是用户自己正在用的 Chrome + `--cdp-endpoint chrome`）。

## 1. 现在要几样东西才能跑

| 现在 | 问题 |
| --- | --- |
| 终端里 `mvn spring-boot:run` + `npm run dev` 两个进程 | 双击打不开 |
| Playwright MCP 用 `npx -y @playwright/mcp@0.0.83` | 从 Finder 打开的 App 没有 nvm 的 PATH，找不到 `npx`；第一次还要联网下载 |
| 需要本机装 JDK 25 | App 要自带 |
| `storage.root: ../storage`（按启动目录算） | App 的启动目录是 `/`，要给绝对路径 |
| 界面在 Chrome 标签页里（5173） | C011-fix 以后 Agent 看得到 Chrome 里所有标签页，界面自己也在里面 |

## 2. 做法

`JobApply.app` 里装四样东西，一个小的 Swift 外壳把它们串起来：

```
JobApply.app/Contents/
  MacOS/JobApply            Swift 外壳（WKWebView 窗口 + 启动/关闭后端）
  Resources/runtime/        jlink 裁剪的 Java 25 运行时
  Resources/jobapply.jar    Spring Boot fat jar（现有构建产物，不改）
  Resources/web/            frontend/dist
  Resources/node/           node 可执行文件 + 预装的 @playwright/mcp@0.0.83
```

1. **外壳**（Swift，约 150 行，`swiftc` 本机已有，不引入 Xcode 工程、Rust、Electron）：
   - 启动时 `runtime/bin/java -jar jobapply.jar --spring.profiles.active=macapp`，等 `http://127.0.0.1:8080/` 能打开后在窗口里加载。
   - 退出 App 时给 Java 发 SIGTERM，Spring 正常关闭，现有代码会关掉 MCP 和它的 node 子进程。
   - 后端起不来（端口被占、Java 报错）时，窗口里显示错误和日志路径，不静默。
   - WKWebView 要补的：文件选择框（附件上传）、`target=_blank` 的链接交给系统默认浏览器打开。
   - 界面不在 Chrome 里，Agent 列标签页时就看不到它。
2. **后端加一个 `application-macapp.yml`**（只在 App 里生效，开发时不变）：
   - `browser.mcp.command` 指向 `Resources/node/bin/node`，args 第一项换成预装的 `@playwright/mcp/cli.js`，其余参数照抄现在的。路径由外壳通过环境变量 `JOBAPPLY_RESOURCES` 传进来。
   - `spring.web.resources.static-locations: file:${JOBAPPLY_RESOURCES}/web/`：Spring Boot 直接给界面，不再要 Vite。
   - `api.allowed-origins` 加上 `http://127.0.0.1:8080`（同源请求也带 Origin）。
   - `storage.root`：见第 4 节待定。
   - 端口固定 8080：开发版后端在跑时 App 会报「端口被占」，正好避免两个后端同时写一份 storage。
3. **构建脚本** `macos/build-app.sh`（新顶层目录 `macos/` 放外壳源码和脚本，名字就是职责）：`npm run build` → `mvn -B package -DskipTests` → `jdeps` 算模块后 `jlink` → 在临时目录 `npm install @playwright/mcp@0.0.83` 并复制本机 node → `swiftc` 编外壳 → 拼成 `macos/build/JobApply.app`（进 .gitignore）→ `codesign -s -` 本机签名。用户把它拖进「应用程序」即可。
4. 预计大小约 350MB（jar 150MB、node 约 110MB、运行时约 70MB）。

## 3. 怎么确认能用

- 运行 `macos/build-app.sh` 成功，产出 `JobApply.app`。
- 从 Finder 双击（不经过终端，验证 PATH 问题）：窗口出现、Project 列表是自己的数据；设置页能看到模型；对话里发一条消息能拿到回复；连上 Chrome（第一次弹允许）后让申请 Agent 打开一个岗位页；附件能选文件上传。
- Cmd+Q 退出后 `ps` 里没有残留的 java / node 进程。
- 开发流程（`mvn spring-boot:run` + `npm run dev`）不受影响。

## 4. 待用户决定

1. **窗口**：Swift WKWebView 外壳（推荐，上面写的）/ 不做窗口、App 只起后端再在 Chrome 里打开界面（最简单，但界面标签页在 Agent 看得到的 Chrome 里）/ Electron（再多约 200MB）。
2. **数据放哪**：继续用仓库里的 `storage/`（开发版和 App 看同一份数据，App 里写绝对路径）/ 放 `~/Library/Application Support/JobApply/storage`（App 和仓库分开，现有数据要用户自己挪过去）。
