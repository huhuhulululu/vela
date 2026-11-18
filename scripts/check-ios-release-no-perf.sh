#!/bin/bash
# 安全门：Release 的 iOS 产物里不能带性能探针入口（T15）。
#   1. 编译条件：目标配置的 SWIFT_ACTIVE_COMPILATION_CONDITIONS / OTHER_SWIFT_FLAGS 里没有 PERF_PROBE
#   2. 产物：App 包内所有文件的可打印字符串里搜不到入口标识串（长标识串不会被内联成立即数，短字符串会，所以单查参数名不够）
# 用法：check-ios-release-no-perf.sh [--configuration 配置名（默认 Release）] [--app 已构建的 .app]
# 不带 --app 时先构建（-sdk iphonesimulator CODE_SIGNING_ALLOWED=NO）。
# 退出码：0 干净；1 发现探针（负样本：对 Perf 配置跑应得 1）；2 构建或环境错误。
# 测试钩子：PERF_GATE_SETTINGS_FILE 指向一个文件时，用它代替 xcodebuild -showBuildSettings 的输出。兼容 bash 3.2。
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CONFIG=Release
APP=""
while [ $# -gt 0 ]; do
    case "$1" in
        --configuration) CONFIG="${2:-}"; shift 2 ;;
        --app) APP="${2:-}"; shift 2 ;;
        *) echo "未知参数: $1" >&2; exit 2 ;;
    esac
done
[ -n "$CONFIG" ] || { echo "缺少配置名" >&2; exit 2; }

# 入口标识串：必须与 iOS/PerfEntry.swift 里的值一致（改一处要改两处，测试会核对）。
MARKERS="perf-autoplay vela-perf-entry-armed-8c41d7e2b9a0"

TMP=$(mktemp -d /tmp/vela-ios-noperf.XXXXXX)
trap 'rm -rf "$TMP"' EXIT
found=0

# ---- 1. 编译条件 ----
if [ -n "${PERF_GATE_SETTINGS_FILE:-}" ]; then
    cp "$PERF_GATE_SETTINGS_FILE" "$TMP/settings.txt" || exit 2
else
    xcodebuild -project "$ROOT/Vela.xcodeproj" -scheme VelaPhone -sdk iphonesimulator \
        -configuration "$CONFIG" -showBuildSettings >"$TMP/settings.txt" 2>"$TMP/settings.err" \
        || { echo "读取构建设置失败" >&2; tail -5 "$TMP/settings.err" >&2; exit 2; }
fi
if ! grep -qE 'SWIFT_ACTIVE_COMPILATION_CONDITIONS|OTHER_SWIFT_FLAGS|SWIFT_VERSION' "$TMP/settings.txt"; then
    echo "构建设置里没有 Swift 相关项，无法判定（防止空通过）" >&2
    exit 2
fi
if grep -E 'SWIFT_ACTIVE_COMPILATION_CONDITIONS|OTHER_SWIFT_FLAGS' "$TMP/settings.txt" | grep -q 'PERF_PROBE'; then
    echo "FOUND 编译条件含 PERF_PROBE（配置 ${CONFIG}）"
    found=1
else
    echo "ok   编译条件不含 PERF_PROBE（配置 ${CONFIG}）"
fi

# ---- 2. 产物 ----
if [ -z "$APP" ]; then
    DD="$TMP/dd"
    xcodebuild -project "$ROOT/Vela.xcodeproj" -scheme VelaPhone -sdk iphonesimulator \
        -configuration "$CONFIG" -derivedDataPath "$DD" CODE_SIGNING_ALLOWED=NO build >"$TMP/build.log" 2>&1 \
        || { echo "构建失败" >&2; tail -20 "$TMP/build.log" >&2; exit 2; }
    APP="$DD/Build/Products/$CONFIG-iphonesimulator/VelaPhone.app"
fi
[ -d "$APP" ] || { echo "找不到产物: $APP" >&2; exit 2; }

count=0
while IFS= read -r -d '' file; do
    count=$((count + 1))
    strings -a "$file" >"$TMP/strings.txt" 2>/dev/null || continue
    for marker in $MARKERS; do
        n=$(grep -c -- "$marker" "$TMP/strings.txt")
        if [ "$n" -gt 0 ]; then
            echo "FOUND 产物里有标识串 ${marker}（${n} 处）: ${file#"$APP"/}"
            found=1
        fi
    done
done < <(find "$APP" -type f -print0)
if [ "$count" -eq 0 ]; then
    echo "产物里没有文件，无法判定（防止空通过）" >&2
    exit 2
fi
[ "$found" -eq 0 ] && echo "ok   产物 $count 个文件里搜不到入口标识串"

if [ "$found" -ne 0 ]; then exit 1; fi
echo "PASS 配置 $CONFIG 没有性能探针"
