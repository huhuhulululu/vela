#!/bin/bash
# 安全门：Release 产物不得含固定测试流钩子（App/PerfStream+Mac.swift，仅 DEBUG / PERF_PROBE 构建有）。
# 用法：scripts/check-release-no-perf-hook.sh <Vela.app 路径>      退出码 0 = 干净，1 = 含钩子或找不到二进制。
# 说明：Swift 把 15 字节以内的字符串编进指令，`strings` 搜不到 "--perf-stream" 本身，
#       所以检测依据是钩子里的长标识串 "vela-perf-stream-hook-enabled" 与类型名 PerfStream；
#       自检：对 PERF_PROBE 构建必须能检出（见 test-perf-gate.sh 不覆盖构建，本机构建后手动验证）。
set -u
APP="${1:-}"
[ -d "$APP" ] || { echo "用法: $0 <Vela.app>" >&2; exit 1; }
BIN="$APP/Contents/MacOS/$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$APP/Contents/Info.plist")"
[ -f "$BIN" ] || { echo "找不到二进制 ${BIN}" >&2; exit 1; }
N=$(strings "$BIN" | grep -ciE 'perf-stream|PerfStream')
echo "perf-stream 命中数: ${N}"
[ "$N" = 0 ]
