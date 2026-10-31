# C017-fix · App 连不上 Chrome：不读 Chrome 资料夹里的端口文件

- 对应：[C017](C017-macos-app.md)；[workflow.md](../../master/workflow.md)「Chrome MCP」（连用户自己的 Chrome，不变）。
- 依据：用户在另一台 Mac（MacBook Air）上用打包的 App 测试（2026-10-27）：远程调试开着、Chrome 在 127.0.0.1:9222 监听，App 报「连不上 Chrome」，Chrome 没有弹出允许。
- 状态：已施工，等用户在 Air 上试（2026-10-27）。

## 根因

- `--cdp-endpoint chrome` 让 Playwright MCP 先读 `~/Library/Application Support/Google/Chrome/DevToolsActivePort` 拿端口，再连 `ws://localhost:<端口>/devtools/browser`（0.0.83 `resolveChannelEndpoint`，不用文件里的 id）。
- App 里的 node 读这个文件时，macOS 做了「访问其他应用的数据」检查（TCC 日志 `kTCCServiceSystemPolicyAppDataDetailed`，发起方 `local.jobapply`、访问方 App 自带的 node），读不到；Playwright 把任何读失败都报成「file not found」，我们再转成「没打开远程调试」，说的不是实情。
- 在终端 / VS Code 里跑开发版时，终端已有权限，所以开发机上没出现。

## 改动

- `application.yml`、`application-macapp.yml`：`--cdp-endpoint ws://127.0.0.1:9222/devtools/browser`（开关打开后 Chrome 默认监听 9222；写 127.0.0.1，Chrome 只监听 IPv4）。不再读 Chrome 的资料夹，也就不用用户去系统设置里给权限。
- `PlaywrightMcpClient`：`ECONNREFUSED` 算连接错误，提示「127.0.0.1:9222 没有在监听」。

## 验证

- `mvn -B -q clean verify` 退出码 0；`macos/build-app.sh` 退出码 0。
- 自己起的 headless Chrome（`--remote-debugging-port`）不接受不带 id 的 `/devtools/browser`；用户 Chrome 的 chrome://inspect 开关模式下 Playwright 本来就连这个地址（开发版一直这么连上的）。在 Air 上用 App 真实连接：未运行。

## 未决

- Chrome 不在 9222 上监听时（端口被别的程序占了）会连不上；真遇到再加「9222 不通时读端口文件」。

## 追加：关闭 App 卡住（2026-10-27）

- 现象：点关闭、Dock 里退出、强制退出时卡一会儿。
- 根因（实测）：Spring Boot 默认 graceful shutdown，最多等 30 秒还没结束的请求；界面一直开着 `/api/events` 推送，所以每次都等满（不开推送 1 秒，开着 30 秒，日志 `Graceful shutdown aborted with one or more requests still active`）。外壳在主线程上等，窗口就卡住。强制退出只杀外壳，Java 后端留下占着 8088（按代码推断）。不是在等 LLM：两个 Runner 关闭时都是 `shutdownNow()`。
- 改动：`application.yml` `server.shutdown: immediate`（开发版同样）；`main.swift` 给后端的标准输入接一根管道，新 `config/ShellWatch.java`（`jobapply.app.exit-with-shell`，只在 macapp）读到管道结尾就关闭后端；`application-macapp.yml` 端口注释更正。
- 验证：`mvn -B -q clean verify` 退出码 0；`macos/build-app.sh` 退出码 0。自带后端在 18080：开着推送时 SIGTERM 约 0 秒退出（改前 30 秒）；杀掉拿着管道写端的进程（模拟外壳被强制退出），后端立刻退出。双击 App 后关闭：未运行。
