#!/usr/bin/env bash
# 各平台打包调度脚本。可在任意目录运行。
# 用法:
#   ./appbuild/build.sh [macos|linux|windows]
# 不传参数时自动根据当前操作系统检测并转调对应平台的打包脚本。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() {
  cat <<'EOF'
JobApply 统一打包调度脚本

用法:
  ./appbuild/build.sh [平台名称]

支持的平台:
  macos    构建 macOS 原生外壳 App 与 DMG（产物: releases/JobApply.app、releases/JobApply-macos-arm64.dmg）
  linux    构建 Linux x64 绿色版 app-image（产物: releases/JobApply-linux-x64/）
  windows  构建 Windows x64 绿色版 app-image（转调 windows/build.ps1，产物: releases/JobApply-windows-x64/）

未指定平台时，默认按当前系统 uname -s 自动检测。
EOF
}

detect_platform() {
  local os
  os="$(uname -s)"
  case "$os" in
    Darwin)
      echo "macos"
      ;;
    Linux)
      echo "linux"
      ;;
    MINGW*|CYGWIN*|MSYS*)
      echo "windows"
      ;;
    *)
      echo "未知操作系统: $os，请显式传入目标平台 (macos|linux|windows)" >&2
      exit 1
      ;;
  esac
}

PLATFORM="${1:-}"
if [[ -z "$PLATFORM" ]]; then
  PLATFORM="$(detect_platform)"
  echo "==> 未指定目标平台，根据当前操作系统自动选择: $PLATFORM"
fi

case "$PLATFORM" in
  macos|mac|darwin)
    echo "==> 执行 macOS 打包脚本: $SCRIPT_DIR/macos/build.sh"
    exec "$SCRIPT_DIR/macos/build.sh"
    ;;
  linux)
    echo "==> 执行 Linux 打包脚本: $SCRIPT_DIR/linux/build.sh"
    exec "$SCRIPT_DIR/linux/build.sh"
    ;;
  windows|win)
    echo "==> 执行 Windows 打包脚本: $SCRIPT_DIR/windows/build.ps1"
    if command -v powershell.exe >/dev/null 2>&1; then
      exec powershell.exe -ExecutionPolicy Bypass -File "$SCRIPT_DIR/windows/build.ps1"
    elif command -v pwsh >/dev/null 2>&1; then
      exec pwsh -File "$SCRIPT_DIR/windows/build.ps1"
    else
      echo "当前环境未找到 PowerShell，请在 Windows 终端中运行: powershell -ExecutionPolicy Bypass -File appbuild/windows/build.ps1" >&2
      exit 1
    fi
    ;;
  -h|--help|help)
    usage
    exit 0
    ;;
  *)
    echo "未知平台: $PLATFORM" >&2
    usage >&2
    exit 1
    ;;
esac
