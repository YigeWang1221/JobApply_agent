#!/usr/bin/env bash
# 打包瘦身（C017-fix）：在 Spring Boot fat jar 里，onnxruntime 和 DJL 分词器只留本平台本机库，
# 并删掉 onnxruntime 的调试符号（*.pdb、*.dSYM）。原地修改传入的 jar，只给放进 App 的那份拷贝用。
# 用法: appbuild/slim-jar.sh <jar> <macos|linux|windows>
set -euo pipefail

JAR="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
case "${2:-}" in
  macos)   ORT=osx-aarch64; TOK=osx-aarch64 ;;
  linux)   ORT=linux-x64;   TOK=linux-x86_64 ;;
  windows) ORT=win-x64;     TOK=win-x86_64 ;;
  *) echo "用法: $0 <jar> <macos|linux|windows>" >&2; exit 1 ;;
esac

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"
unzip -q "$JAR" 'BOOT-INF/lib/onnxruntime-*.jar' 'BOOT-INF/lib/tokenizers-*.jar'

# slim <nested jar> <本机库根目录> <本平台目录名>：删掉其它平台目录和调试符号，确认本平台还在
slim() {
  local nested="$1" root="$2" keep="$3" entries drop
  entries="$(unzip -Z1 "$nested")"
  drop="$({ grep -E "^$root[^/]+/" <<<"$entries" | grep -v "^$root$keep/"
            grep -E '\.pdb$|\.dSYM/' <<<"$entries"; } || true)"
  [[ -z "$drop" ]] || zip -q -d "$nested" -@ <<<"$drop"
  unzip -Z1 "$nested" | grep -q "^$root$keep/." \
    || { echo "瘦身失败: $nested 里没有 $root$keep/" >&2; exit 1; }
}
for j in BOOT-INF/lib/onnxruntime-*.jar; do slim "$j" ai/onnxruntime/native/ "$ORT"; done
for j in BOOT-INF/lib/tokenizers-*.jar;  do slim "$j" native/lib/ "$TOK"; done

# Spring Boot 要求 nested jar 不压缩（STORED）放回
zip -q -0 -X "$JAR" BOOT-INF/lib/onnxruntime-*.jar BOOT-INF/lib/tokenizers-*.jar
echo "瘦身完成 ($2): $(du -h "$JAR" | cut -f1) $JAR"
