# C017-fix · App 打包瘦身

- 对应：[C017](C017-macos-app.md)
- 状态：**已施工，等用户双击 App 试（2026-11-25；macOS 包 489→322 MB，DMG 388→208 MB）**
- 日期：2026-11-25

## 起因

11-14 打的 `releases/JobApply.app` 实测 489 MB、DMG 388 MB。jar 305 MB 里 onnxruntime 一个就 131–137 MB（5 个平台的本机库 + 约 95 MB 调试符号，运行时都不用）；DJL 分词器 18 MB（4 个平台）；node 116 MB 带调试符号。Java 运行时（jlink 49 MB）和 Playwright MCP（18 MB，不含浏览器）已接近最小。

## 做什么（只改打包脚本，不改代码和行为）

1. **onnxruntime 瘦身**：打包时从 jar 里的 `onnxruntime-*.jar` 删掉 `*.pdb`、`*.dSYM` 和其它平台的本机库，只留本平台（macOS `osx-aarch64`、Linux `linux-x64`、Windows `win-x64`）。
2. **分词器瘦身**：`tokenizers-*.jar` 只留本平台本机库。
   - 1、2 放在一个共用步骤里，各平台脚本在拿到 jar 后调用；嵌套 jar 重新放回时不压缩（Spring Boot 要求）。
3. **node 去符号**：macOS、Linux 拷完 node 后 `strip`；macOS 之后照旧 ad-hoc 签名。Windows 不做。
4. **DMG 改 LZMA**：`hdiutil -format ULMO`。

## 不做

- 换掉本地嵌入检索（SQLite FTS5）或改成按需下载；Tika 只留部分解析器；换 node 运行时；GraalVM；AOT 缓存。

## 怎么确认能用

- 瘦身后的 jar 能启动；onnxruntime 和分词器的本机库能在本机加载（不靠联网下载）。
- 去符号的 node 能跑 `@playwright/mcp`。
- 本机跑一次 macOS 打包，量 App 和 DMG 大小；App 能启动、后端起来。
- Linux / Windows 打包：本机没法跑，只检查脚本语法，标「未运行」。
- 真实 Chrome 投递、真实检索：等用户。

## 施工记录（2026-11-25，两个 Agent 并行）

- 1、2：新增 `appbuild/slim-jar.sh <jar> <macos|linux|windows>` 与 `appbuild/windows/slim-jar.ps1`；三个平台脚本在 jar 拷进 App 后各调用一次（`backend/target` 的 jar 不动）。删 `*.pdb`、`*.dSYM/` 和其它平台目录，只留 onnxruntime `osx-aarch64` / `linux-x64` / `win-x64`、分词器 `osx-aarch64` / `linux-x86_64` / `win-x86_64`；嵌套 jar 不压缩放回（Windows 用 JDK `jar --update --no-compress`）；目标平台目录缺失时报错退出。
- 3：macOS 拷完 node 后 `strip`，再单独 `codesign --force -s -`（后面的 `codesign --deep` 不签 Resources 里的可执行文件）；Linux 有 `strip` 就去符号。
- 4：`hdiutil -fs HFS+ -format ULMO`（APFS 下 ULMO 会报「No space left on device」，所以加了 HFS+）。

### 实测

| | 改前（11-14） | 改后 |
| --- | --- | --- |
| jar | 305 MB | 169 MB |
| node | 134 MB | 102 MB |
| App | 489 MB | 322 MB |
| DMG | 388 MB | 208 MB |

- 瘦身 jar：断网、空 DJL 缓存下 `OrtEnvironment` 和分词器都从 jar 加载本平台本机库。
- 打好的 App：`codesign --verify --deep --strict` 通过，node 签名有效；App 里的 node 跑 `@playwright/mcp --help` 正常；App 自带 Java 运行 jar（临时 storage）`/api/projects` 正常，空载 RSS 303 MB。
- 未运行：双击 App 界面；App 里真实检索（要下载模型）；Linux / Windows 打包；PowerShell 语法检查（本机没有 pwsh）。
