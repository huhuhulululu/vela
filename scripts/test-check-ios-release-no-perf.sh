#!/bin/bash
# check-ios-release-no-perf.sh 的负样本证明：带标识串、带 PERF_PROBE 的样本必须被拒，干净样本必须通过。兼容 bash 3.2。
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
GATE="$ROOT/scripts/check-ios-release-no-perf.sh"
T=$(mktemp -d /tmp/vela-noperf-test.XXXXXX)
trap 'rm -rf "$T"' EXIT
fail=0
check() { if [ "$2" = 1 ]; then echo "ok   $1"; else echo "FAIL $1"; fail=1; fi; }
run() { PERF_GATE_SETTINGS_FILE="$1" bash "$GATE" --app "$2" >"$T/out.txt" 2>&1; echo $?; }

CLEAN_SETTINGS="$T/clean.txt"; printf '    SWIFT_ACTIVE_COMPILATION_CONDITIONS = \n    SWIFT_VERSION = 5.0\n' >"$CLEAN_SETTINGS"
PERF_SETTINGS="$T/perf.txt"; printf '    SWIFT_ACTIVE_COMPILATION_CONDITIONS = PERF_PROBE\n    SWIFT_VERSION = 5.0\n' >"$PERF_SETTINGS"
EMPTY_SETTINGS="$T/empty.txt"; : >"$EMPTY_SETTINGS"

mk_app() { # $1 目录 $2 二进制内容
    mkdir -p "$1/Frameworks"
    printf 'plain binary body\0some other string\0' >"$1/VelaPhone"
    [ -n "$2" ] && printf '\0%s\0' "$2" >>"$1/VelaPhone"
    printf 'unrelated' >"$1/Frameworks/x.dylib"
}

mk_app "$T/clean.app" ""
check "干净样本通过（0）" "$([ "$(run "$CLEAN_SETTINGS" "$T/clean.app")" = 0 ] && echo 1 || echo 0)"

mk_app "$T/long.app" "vela-perf-entry-armed-8c41d7e2b9a0"
check "二进制含长标识串被拒（1）" "$([ "$(run "$CLEAN_SETTINGS" "$T/long.app")" = 1 ] && echo 1 || echo 0)"

mk_app "$T/arg.app" "--perf-autoplay"
check "二进制含入口参数名被拒（1）" "$([ "$(run "$CLEAN_SETTINGS" "$T/arg.app")" = 1 ] && echo 1 || echo 0)"

mk_app "$T/nested.app" ""
printf '\0--perf-autoplay\0' >"$T/nested.app/Frameworks/x.dylib"
check "子目录里的文件含标识串被拒（1）" "$([ "$(run "$CLEAN_SETTINGS" "$T/nested.app")" = 1 ] && echo 1 || echo 0)"

check "编译条件含 PERF_PROBE 被拒（1）" "$([ "$(run "$PERF_SETTINGS" "$T/clean.app")" = 1 ] && echo 1 || echo 0)"
check "构建设置为空判不确定（2），不空通过" "$([ "$(run "$EMPTY_SETTINGS" "$T/clean.app")" = 2 ] && echo 1 || echo 0)"
check "产物不存在判错误（2）" "$([ "$(run "$CLEAN_SETTINGS" "$T/none.app")" = 2 ] && echo 1 || echo 0)"
mkdir -p "$T/hollow.app"
check "空产物判错误（2）" "$([ "$(run "$CLEAN_SETTINGS" "$T/hollow.app")" = 2 ] && echo 1 || echo 0)"

# 标识串与 PerfEntry.swift 一致，改一处漏一处会让门形同虚设。
ENTRY_MARKER=$(grep -o 'vela-perf-entry-armed-[0-9a-f]*' "$ROOT/iOS/PerfEntry.swift" | head -1)
check "脚本里的标识串与 PerfEntry.swift 一致" "$(grep -q -- "$ENTRY_MARKER" "$GATE" && [ -n "$ENTRY_MARKER" ] && echo 1 || echo 0)"
check "入口参数名与脚本一致" "$(grep -q 'perf-autoplay' "$ROOT/iOS/PerfEntry.swift" && echo 1 || echo 0)"

[ "$fail" = 0 ] && echo "全部通过" || echo "有失败"
exit "$fail"
