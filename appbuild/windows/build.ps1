# PowerShell 构建 releases/JobApply-windows-x64 绿色版（app-image）。
# 可在 Windows 终端中运行（PowerShell 5.1 或 PowerShell 7+）：
#   powershell -ExecutionPolicy Bypass -File .\appbuild\windows\build.ps1
#
# 架构与行为：
# 1. 采用 JDK jpackage 打包为 --type app-image（自带经过 jlink 裁剪的轻量 JRE）；
# 2. --main-jar jobapply.jar，--main-class org.springframework.boot.loader.launch.JarLauncher；
#    Spring Boot 会读取 BOOT-INF/classes 内部清单并自动转调 JobApplyApplication，无需额外 launcher；
# 3. 后端激活 desktop profile（--spring.profiles.active=desktop），启动就绪后由 OpenBrowser 组件自动调用默认浏览器打开应用；
# 4. 前端静态页面与预装 Node + Playwright MCP 打包于 resources\ 目录；
# 5. MCP 节点执行文件命名为 node（不带 .exe）。
#
# 注意：当前开发环境为 macOS，此 Windows 构建脚本已完成结构设计与逻辑编写，未在真实 Windows 平台执行验证。

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Repo = (Resolve-Path "$ScriptDir\..\..").Path
$OutDir = "$Repo\releases\JobApply-windows-x64"
$McpVersion = "0.0.83"

function Stage-Info($msg) {
    Write-Host "`n==> $msg" -ForegroundColor Cyan
}

# 1. 检查 JAVA_HOME 与 jpackage
$JavaHome = $env:JOBAPPLY_JAVA_HOME
if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if (-not $JavaHome) {
    Write-Error "请先配置 JAVA_HOME（或 JOBAPPLY_JAVA_HOME）环境变量指向 JDK 25"
}

$Java = Join-Path $JavaHome "bin\java.exe"
$Jdeps = Join-Path $JavaHome "bin\jdeps.exe"
$Jlink = Join-Path $JavaHome "bin\jlink.exe"
$Jpackage = Join-Path $JavaHome "bin\jpackage.exe"

if (-not (Test-Path $Jpackage)) {
    Write-Error "在 $JavaHome\bin 下未找到 jpackage.exe，请确认安装了完整的 JDK 25"
}

$Tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("jobapply-build-" + [System.Guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $Tmp | Out-Null

try {
    # 2. 前端构建
    Stage-Info "1/6 前端 npm run build"
    Push-Location "$Repo\frontend"
    if (-not (Test-Path "node_modules")) {
        npm ci
    }
    npm run build
    Pop-Location

    # 3. 后端构建
    Stage-Info "2/6 后端 mvn package"
    Push-Location "$Repo\backend"
    mvn -B -q package -DskipTests
    $Jar = Get-ChildItem -Path "target\*.jar" | Where-Object { $_.Name -notmatch "-plain\.jar$" } | Select-Object -First 1
    if (-not $Jar) {
        Write-Error "找不到后端 jar 文件"
    }
    Write-Host "jar: $($Jar.FullName)"
    Pop-Location

    $InputDir = Join-Path $Tmp "input"
    New-Item -ItemType Directory -Path $InputDir | Out-Null
    Copy-Item $Jar.FullName (Join-Path $InputDir "jobapply.jar")

    # 4. jdeps + jlink 裁剪 JRE
    Stage-Info "3/6 jdeps + jlink 裁剪 JRE 运行时"
    $JarExtract = Join-Path $Tmp "jar"
    New-Item -ItemType Directory -Path $JarExtract | Out-Null
    Expand-Archive -Path (Join-Path $InputDir "jobapply.jar") -DestinationPath $JarExtract

    $ClassPath = Join-Path $JarExtract "BOOT-INF\lib\*"
    $Classes = Join-Path $JarExtract "BOOT-INF\classes"
    $DepsOutput = & $Jdeps --ignore-missing-deps --print-module-deps --multi-release 25 --class-path $ClassPath $Classes
    $Mods = ($DepsOutput | Select-Object -Last 1).Trim()

    foreach ($m in @("jdk.crypto.ec", "jdk.zipfs", "jdk.unsupported", "java.instrument", "java.desktop")) {
        $found = & $Java --list-modules | Select-String "^$m@"
        if ($found) {
            $Mods = "$Mods,$m"
        }
    }
    Write-Host "运行时模块: $Mods"

    $RuntimeDir = Join-Path $Tmp "runtime"
    & $Jlink --add-modules $Mods --strip-debug --no-header-files --no-man-pages --compress zip-6 --output $RuntimeDir

    # 5. jpackage 生成绿色版 app-image
    Stage-Info "4/6 jpackage 生成 app-image"
    $ReleasesDir = Join-Path $Repo "releases"
    if (-not (Test-Path $ReleasesDir)) {
        New-Item -ItemType Directory -Path $ReleasesDir | Out-Null
    }
    if (Test-Path $OutDir) {
        Remove-Item -Recurse -Force $OutDir
    }

    # Windows jpackage app-image 下 $APPDIR 对应 app 目录
    & $Jpackage `
      --type app-image `
      --dest $ReleasesDir `
      --name "JobApply-windows-x64" `
      --input $InputDir `
      --main-jar "jobapply.jar" `
      --main-class "org.springframework.boot.loader.launch.JarLauncher" `
      --runtime-image $RuntimeDir `
      --java-options "-Dspring.profiles.active=desktop" `
      --java-options "-Djobapply.desktop.resources=`$APPDIR\resources" `
      --java-options "-DJOBAPPLY_RESOURCES=`$APPDIR\resources" `
      --java-options "-Xmx384m" `
      --java-options "-XX:+UseSerialGC" `
      --java-options "-XX:TieredStopAtLevel=1" `
      --java-options "-XX:ReservedCodeCacheSize=64m"

    # 6. 部署 web 资源与 Node + Playwright MCP
    Stage-Info "5/6 复制前端页面与预装 Node + Playwright MCP"
    $ResDir = Join-Path $OutDir "app\resources"
    New-Item -ItemType Directory -Path $ResDir -Force | Out-Null

    # 复制前端页面
    Copy-Item -Recurse -Force "$Repo\frontend\dist" (Join-Path $ResDir "web")

    # 准备 Node 运行时
    $NodeExe = (Get-Command "node" -ErrorAction SilentlyContinue).Source
    if (-not $NodeExe) {
        Write-Error "找不到 node 命令，请确认 Node.js 已安装并配置在 PATH 中"
    }
    Write-Host "node: $NodeExe"

    $NodeTargetDir = Join-Path $ResDir "node"
    New-Item -ItemType Directory -Path $NodeTargetDir -Force | Out-Null

    # 按照跨平台统一契约：复制为无扩展名的 node 文件
    Copy-Item $NodeExe (Join-Path $NodeTargetDir "node") -Force
    # 同时保留 node.exe 保证 Windows 兼容性
    Copy-Item $NodeExe (Join-Path $NodeTargetDir "node.exe") -Force

    # 全局安装 @playwright/mcp
    $NodeBinDir = Split-Path -Parent $NodeExe
    $NpmCmd = Join-Path $NodeBinDir "npm.cmd"
    if (-not (Test-Path $NpmCmd)) { $NpmCmd = "npm" }

    & $NpmCmd install -g --prefix $NodeTargetDir "@playwright/mcp@$McpVersion"

    # 在根目录做个备用镜像目录
    $RootRes = Join-Path $OutDir "resources"
    if (-not (Test-Path $RootRes)) {
        Copy-Item -Recurse -Force $ResDir $RootRes
    }

    Stage-Info "6/6 Windows 产物就绪"
    Write-Host "完成: $OutDir" -ForegroundColor Green
    Write-Host "启动方式: $OutDir\JobApply-windows-x64.exe" -ForegroundColor Green
} finally {
    if (Test-Path $Tmp) {
        Remove-Item -Recurse -Force $Tmp -ErrorAction SilentlyContinue
    }
}
