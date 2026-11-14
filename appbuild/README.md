# JobApply 各平台打包指南

本项目提供 macOS、Linux、Windows 三大桌面平台的独立打包脚本与工具，产物统一输出至仓库根目录的 `releases/` 文件夹（已被 `.gitignore` 忽略）。

---

## 目录结构

```text
appbuild/
├── build.sh             # 顶层统一入口脚本（按传参或系统自动转调）
├── README.md            # 说明文档（本文）
├── macos/
│   ├── build.sh         # macOS 打包脚本（编译前端、后端、裁剪 JRE、编译 Swift 外壳、打 dmg）
│   ├── JobApply/
│   │   └── main.swift   # Swift 原生外壳源码（WKWebView + 进程守护 + 文件夹选择桥）
│   └── make-icon.swift  # 生成 AppIcon.icns 工具
├── linux/
│   └── build.sh         # Linux x64 jpackage 打包脚本（--type app-image 绿色版）
└── windows/
    └── build.ps1        # Windows x64 jpackage PowerShell 打包脚本（--type app-image 绿色版）
```

---

## 架构与跨平台方案

1. **macOS 平台**
   - **外壳方案**：Swift 原生 AppKit + WKWebView 窗口外壳。
   - **运行机制**：外壳直接启动内嵌的 Java 运行时，等待端口就绪后在窗口中加载网页；App 退出时自动给 Java 发送 SIGTERM。
   - **产物形态**：`releases/JobApply.app` 与 `releases/JobApply-macos-arm64.dmg`。

2. **Linux 与 Windows 平台**
   - **外壳方案**：采用 JDK `jpackage` 制作 `--type app-image` 自包含免安装绿色版（自带经 `jlink` 裁剪的最小 JRE，无需预先安装 Java 环境）。
   - **启动入口**：
     - `--main-jar jobapply.jar`
     - `--main-class org.springframework.boot.loader.launch.JarLauncher`
     - Spring Boot 会自动读取内部清单启动主程序 `JobApplyApplication`，不需要额外的启动器类。
   - **浏览器交互**：后端激活 `desktop` profile，Spring 就绪后由 `OpenBrowser` Bean 自动调用系统的默认浏览器打开 `http://127.0.0.1:8088/`，不做原生窗口外壳。
   - **资源布局**：前端静态网页（`dist`）以及预装的 Node 与 Playwright MCP 存放在 `resources/` 目录中，JVM 启动参数通过 `-Djobapply.desktop.resources=...` 与 `-DJOBAPPLY_RESOURCES=...` 传递给后端；Node 可执行文件统一命名为 `node`（Windows 下额外保留 `node.exe`）。
   - **产物形态**：
     - Linux: `releases/JobApply-linux-x64/`
     - Windows: `releases/JobApply-windows-x64/`

---

## 构建环境要求

### 全平台通用依赖
- **JDK 25**（带 `jlink` 与 `jpackage`）
- **Node.js**（v20+）及 **npm**
- **Maven**（3.9+）

### 各平台专有依赖
- **macOS**：macOS 13+，系统自带的 `swiftc`、`codesign`、`hdiutil`、`iconutil`。
- **Linux**：Linux x64，安装了 X11 或 Wayland 图形桌面环境（用于支持 `xdg-open` 浏览器自动打开）。
- **Windows**：Windows 10/11 x64，PowerShell 5.1 或 PowerShell 7+。

---

## 使用方法

### 1. 统一调度脚本
在仓库根目录或 `appbuild/` 目录下运行：
```bash
./appbuild/build.sh
```
脚本会自动根据操作系统类型调用对应平台的构建流程。也可显式指定平台：
```bash
./appbuild/build.sh macos
./appbuild/build.sh linux
./appbuild/build.sh windows
```

### 2. macOS 单独构建
```bash
./appbuild/macos/build.sh
```
构建完成后产物位于：
- `releases/JobApply.app`
- `releases/JobApply-macos-arm64.dmg`

### 3. Linux 单独构建
在 Linux 环境终端中执行：
```bash
./appbuild/linux/build.sh
```
构建完成后产物位于：
- `releases/JobApply-linux-x64/`（目录）与 `releases/JobApply-linux-x64.tar.gz`（分发用压缩包）
启动方式：
```bash
./releases/JobApply-linux-x64/bin/JobApply-linux-x64
```

### 4. Windows 单独构建
在 Windows PowerShell 终端中执行：
```powershell
powershell -ExecutionPolicy Bypass -File .\appbuild\windows\build.ps1
```
构建完成后产物位于：
- `releases\JobApply-windows-x64\`
启动方式：
双击运行 `releases\JobApply-windows-x64\JobApply-windows-x64.exe`。

---

## 注意事项

- 当前开发机器为 macOS，macOS 构建流程已实机跑通。
- Linux 与 Windows 脚本严格按照跨平台规范编写，但开发机非对应原生操作系统，未在真实 Linux/Windows 机器上执行实际构建。在对应系统首次打包若有微调需求，可根据日志做针对性调试。
