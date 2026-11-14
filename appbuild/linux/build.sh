#!/usr/bin/env bash
# 构建 releases/JobApply-linux-x64 绿色版（app-image）。可在任意目录运行。
#
# 架构与行为：
# 1. 采用 JDK jpackage 打包为 --type app-image（自带经过 jlink 裁剪的轻量 JRE）；
# 2. --main-jar jobapply.jar，--main-class org.springframework.boot.loader.launch.JarLauncher；
#    Spring Boot 会读取 BOOT-INF/classes 内部清单并自动转调 JobApplyApplication，无需额外 launcher；
# 3. 后端激活 desktop profile（--spring.profiles.active=desktop），启动就绪后由 OpenBrowser 组件自动调用默认浏览器打开应用；
# 4. 前端静态页面与预装 Node + Playwright MCP 打包于 resources/ 目录；
# 5. MCP 节点执行文件命名为 node（不带 .exe）。
#
# 注意：当前开发环境为 macOS，此 Linux 构建脚本已完成结构设计与语法检查，未在真实 Linux 平台执行验证。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT_DIR="$REPO/releases/JobApply-linux-x64"
MCP_VERSION=0.0.83
ARCHIVE="$REPO/releases/JobApply-linux-x64.tar.gz"

# jpackage 只能打出当前系统的包，在 macOS 上运行会得到 Mac 程序
if [[ "$(uname -s)" != "Linux" ]]; then
  echo "错误: Linux 包必须在 Linux（x64）上构建，当前系统是 $(uname -s)" >&2
  exit 1
fi

# 支持用 JOBAPPLY_JAVA_HOME 或 JAVA_HOME 指定 JDK 25 路径
export JAVA_HOME="${JOBAPPLY_JAVA_HOME:-${JAVA_HOME:-}}"
if [[ -z "$JAVA_HOME" ]] || [[ ! -x "$JAVA_HOME/bin/jpackage" ]]; then
  echo "错误: 请先配置 JAVA_HOME（或 JOBAPPLY_JAVA_HOME）指向 JDK 25，且包含 jpackage 工具" >&2
  exit 1
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

stage() { echo; echo "==> $*"; }

stage "1/6 前端 npm run build"
cd "$REPO/frontend"
[[ -d node_modules ]] || npm ci
npm run build

stage "2/6 后端 mvn package（JAVA_HOME=${JAVA_HOME}）"
cd "$REPO/backend"
mvn -B -q package -DskipTests
JAR="$(ls target/*.jar | grep -v -- '-plain.jar$' | head -n1)"
[[ -f "$JAR" ]] || { echo "找不到后端 jar" >&2; exit 1; }
echo "jar: $JAR"

mkdir -p "$TMP/input"
cp "$JAR" "$TMP/input/jobapply.jar"

stage "3/6 jdeps + jlink 裁剪 JRE 运行时"
mkdir -p "$TMP/jar"
(cd "$TMP/jar" && unzip -q "$TMP/input/jobapply.jar")
MODS="$("$JAVA_HOME/bin/jdeps" --ignore-missing-deps --print-module-deps --multi-release 25 \
  --class-path "$TMP/jar/BOOT-INF/lib/*" "$TMP/jar/BOOT-INF/classes" | tail -n1)"
for m in jdk.crypto.ec jdk.zipfs jdk.unsupported java.instrument java.desktop; do
  "$JAVA_HOME/bin/java" --list-modules | grep -q "^$m@" && MODS="$MODS,$m"
done
echo "运行时模块: $MODS"
"$JAVA_HOME/bin/jlink" --add-modules "$MODS" --strip-debug --no-header-files --no-man-pages \
  --compress zip-6 --output "$TMP/runtime"

stage "4/6 jpackage 生成 app-image"
mkdir -p "$REPO/releases"
rm -rf "$OUT_DIR"

# Linux app-image 下 $APPDIR 对应 lib/app 目录
"$JAVA_HOME/bin/jpackage" \
  --type app-image \
  --dest "$REPO/releases" \
  --name "JobApply-linux-x64" \
  --input "$TMP/input" \
  --main-jar jobapply.jar \
  --main-class org.springframework.boot.loader.launch.JarLauncher \
  --runtime-image "$TMP/runtime" \
  --java-options "-Dspring.profiles.active=desktop" \
  --java-options "-Djobapply.desktop.resources=\$APPDIR/resources" \
  --java-options "-DJOBAPPLY_RESOURCES=\$APPDIR/resources" \
  --java-options "-Xmx384m" \
  --java-options "-XX:+UseSerialGC" \
  --java-options "-XX:TieredStopAtLevel=1" \
  --java-options "-XX:ReservedCodeCacheSize=64m"

RES="$OUT_DIR/lib/app/resources"
mkdir -p "$RES"

stage "5/6 复制前端页面与预装 Node + Playwright MCP"
cp -R "$REPO/frontend/dist" "$RES/web"

NODE="${NODE_BIN:-$(command -v node || true)}"
[[ -x "$NODE" ]] || { echo "找不到 node，可用 NODE_BIN 指定" >&2; exit 1; }
NODE="$(cd "$(dirname "$NODE")" && pwd -P)/$(basename "$NODE")"
echo "node: $NODE ($("$NODE" --version))"

# 命名为 node，不带 .exe
mkdir -p "$RES/node"
cp "$NODE" "$RES/node/node"
chmod +x "$RES/node/node"

# 预装 @playwright/mcp
PATH="$(dirname "$NODE"):$PATH" "$(dirname "$NODE")/npm" install -g --prefix "$RES/node" "@playwright/mcp@$MCP_VERSION"
MCP_PKG="$RES/node/lib/node_modules/@playwright/mcp"
MCP_BIN="$("$NODE" -e 'const b=require(process.argv[1]+"/package.json").bin; console.log(typeof b==="string"?b:Object.values(b)[0])' "$MCP_PKG")"
echo "MCP 入口: $MCP_PKG/${MCP_BIN#./}"
[[ -f "$MCP_PKG/${MCP_BIN#./}" ]] || { echo "MCP 入口不存在" >&2; exit 1; }

# 在应用根目录建立软链接方便调试与相对访问
ln -sfn "lib/app/resources" "$OUT_DIR/resources"

stage "6/6 打包 tar.gz"
rm -f "$ARCHIVE"
tar -czf "$ARCHIVE" -C "$REPO/releases" "JobApply-linux-x64"
echo "完成: $ARCHIVE"
echo "使用方式: tar -xzf JobApply-linux-x64.tar.gz && ./JobApply-linux-x64/bin/JobApply-linux-x64"
