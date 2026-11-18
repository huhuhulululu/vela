#!/bin/bash
# perf-probe-ios.sh 负样本自测：每个失败路径必须以预期的非 0 码退出。
# 用法：scripts/test-perf-probe-ios.sh [<VelaPhone Perf .app> [vela|spike]]
#       （前四项不需要模拟器；带 App 时后三项会起模拟器并联网，各约 30 秒；profile 缺省 vela）
set -u
APP="${1:-}"
PROFILE="${2:-vela}"
HERE=$(cd "$(dirname "$0")" && pwd)
PROBE="$HERE/perf-probe-ios.sh"
OUT=$(mktemp /tmp/vela-sim-neg.XXXXXX)
FAILS=0
expect() {  # 名称 期望码 命令...
    name=$1; want=$2; shift 2
    "$@" >"$OUT" 2>/dev/null
    got=$?
    if [ "$got" = "$want" ]; then echo "PASS $name rc=$got $(head -c 120 "$OUT")"; else echo "FAIL $name 期望 rc=$want 实际 rc=$got"; FAILS=$((FAILS + 1)); fi
}
expect bad-scenario 2 bash "$PROBE" --app /tmp --bundle x --scenario nope
expect missing-app 1 bash "$PROBE" --app /nonexistent.app --bundle x --scenario single
expect bad-profile 2 bash "$PROBE" --app /tmp --scenario single --profile nope
expect vela-dual-accepted 1 bash "$PROBE" --app /nonexistent.app --scenario dual --profile vela
if [ -n "$APP" ]; then
    common="--app $APP --profile $PROFILE --out $OUT"
    # shellcheck disable=SC2086
    PROBE_FORCE_FAIL=nosamples expect no-samples 6 bash "$PROBE" $common --scenario single --duration 10
    # shellcheck disable=SC2086
    PROBE_FORCE_FAIL=exit expect process-exit 5 bash "$PROBE" $common --scenario single --duration 20
    # shellcheck disable=SC2086
    expect play-timeout 4 bash "$PROBE" $common --scenario single --duration 10 --play-timeout 0
fi
rm -f "$OUT"
[ "$FAILS" = 0 ] && echo "全部负样本按预期失败" || { echo "${FAILS} 项未按预期"; exit 1; }
