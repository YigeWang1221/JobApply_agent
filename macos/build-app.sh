#!/usr/bin/env bash
# 构建 appBuild/JobApply.app（C017）。可在任意目录运行。
set -euo pipefail

MACOS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(dirname "$MACOS_DIR")"
# 用 JOBAPPLY_JAVA_HOME 覆盖；不用外面的 JAVA_HOME，避免跑在非 25 的 JDK 上
export JAVA_HOME="${JOBAPPLY_JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"
MCP_VERSION=0.0.83

APP="$REPO/appBuild/JobApply.app"
RES="$APP/Contents/Resources"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

stage() { echo; echo "==> $*"; }

stage "1/7 前端 npm run build"
cd "$REPO/frontend"
[[ -d node_modules ]] || npm ci
npm run build

stage "2/7 后端 mvn package（JAVA_HOME=${JAVA_HOME}）"
cd "$REPO/backend"
mvn -B -q package -DskipTests
JAR="$(ls target/*.jar | grep -v -- '-plain.jar$' | head -n1)"
[[ -f "$JAR" ]] || { echo "找不到后端 jar" >&2; exit 1; }
echo "jar: $JAR"

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$RES"
cp "$JAR" "$RES/jobapply.jar"
cp -R "$REPO/frontend/dist" "$RES/web"

stage "3/7 jdeps + jlink 运行时"
mkdir -p "$TMP/jar"
(cd "$TMP/jar" && unzip -q "$RES/jobapply.jar")
MODS="$("$JAVA_HOME/bin/jdeps" --ignore-missing-deps --print-module-deps --multi-release 25 \
  --class-path "$TMP/jar/BOOT-INF/lib/*" "$TMP/jar/BOOT-INF/classes" | tail -n1)"
for m in jdk.crypto.ec jdk.zipfs jdk.unsupported java.instrument; do
  "$JAVA_HOME/bin/java" --list-modules | grep -q "^$m@" && MODS="$MODS,$m"
done
echo "modules: $MODS"
"$JAVA_HOME/bin/jlink" --add-modules "$MODS" --strip-debug --no-header-files --no-man-pages \
  --compress zip-6 --output "$RES/runtime"

stage "4/7 node + @playwright/mcp@$MCP_VERSION"
NODE="${NODE_BIN:-$(command -v node || true)}"
if [[ -z "$NODE" ]]; then
  # Finder / 非交互 shell 没有 nvm 的 PATH，退到 nvm 默认版本
  NODE="$(ls -d "$HOME"/.nvm/versions/node/*/bin/node 2>/dev/null | tail -n1)"
fi
[[ -x "$NODE" ]] || { echo "找不到 node，可用 NODE_BIN 指定" >&2; exit 1; }
NODE="$(cd "$(dirname "$NODE")" && pwd -P)/$(basename "$NODE")"
echo "node: $NODE ($("$NODE" --version))"
mkdir -p "$RES/node/bin"
cp "$NODE" "$RES/node/bin/node"
PATH="$(dirname "$NODE"):$PATH" "$(dirname "$NODE")/npm" install -g --prefix "$RES/node" "@playwright/mcp@$MCP_VERSION"
MCP_PKG="$RES/node/lib/node_modules/@playwright/mcp"
MCP_BIN="$("$NODE" -e 'const b=require(process.argv[1]+"/package.json").bin; console.log(typeof b==="string"?b:Object.values(b)[0])' "$MCP_PKG")"
echo "MCP 入口: $MCP_PKG/${MCP_BIN#./}"
[[ -f "$MCP_PKG/${MCP_BIN#./}" ]] || { echo "MCP 入口不存在" >&2; exit 1; }

stage "5/7 swiftc 外壳 + Info.plist"
swiftc -O -framework AppKit -framework WebKit "$MACOS_DIR/JobApply/main.swift" -o "$APP/Contents/MacOS/JobApply"
cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleIdentifier</key><string>local.jobapply</string>
  <key>CFBundleName</key><string>JobApply</string>
  <key>CFBundleDisplayName</key><string>JobApply</string>
  <key>CFBundleExecutable</key><string>JobApply</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>0.1.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>LSMinimumSystemVersion</key><string>13.0</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSPrincipalClass</key><string>NSApplication</string>
  <key>NSAppTransportSecurity</key>
  <dict><key>NSAllowsLocalNetworking</key><true/></dict>
</dict>
</plist>
PLIST

# 图标：从仓库根目录的 icon.png 裁出图案，做成 AppIcon.icns（Dock 栏里显示）
ICONSET="$TMP/AppIcon.iconset"
mkdir -p "$ICONSET"
swift "$MACOS_DIR/make-icon.swift" "$REPO/icon.png" "$TMP/icon-1024.png"
for size in 16 32 128 256 512; do
  sips -z "$size" "$size" "$TMP/icon-1024.png" --out "$ICONSET/icon_${size}x${size}.png" >/dev/null
  sips -z "$((size * 2))" "$((size * 2))" "$TMP/icon-1024.png" --out "$ICONSET/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET" -o "$RES/AppIcon.icns"

stage "6/7 ad-hoc 签名"
codesign --force --deep -s - "$APP"
du -sh "$APP"
echo "完成: $APP"

stage "7/7 dmg"
# 磁盘映像里放 App 和一个指向「应用程序」的快捷方式，打开后拖进去即可
DMG="$REPO/appBuild/JobApply-macos-arm64.dmg"
mkdir -p "$TMP/dmg"
cp -R "$APP" "$TMP/dmg/"
ln -s /Applications "$TMP/dmg/Applications"
hdiutil create -volname JobApply -srcfolder "$TMP/dmg" -ov -format UDZO "$DMG" >/dev/null
du -sh "$DMG"
echo "完成: $DMG"
