#!/bin/bash
# Vela iOS 模拟器探针：在 iOS 模拟器里静音起播，按宿主进程 pid 周期采样，输出一行 JSON。
# 用法：scripts/perf-probe-ios.sh --app <.app> [--bundle <id>] --duration 秒 --scenario single|dual|background|pip
#       [--profile vela|spike] [--launch-args "额外启动参数"] [--device 名称] [--out 文件] [--raw-dir 目录] [--bg-hold 秒]
#       [--play-timeout 秒] [--wait-quiet 最多等待秒数] [--teardown]
# 兼容 macOS 自带 bash 3.2。依赖：xcrun simctl、ps、top、footprint、nettop、python3。
# 全程静音（正式 App 的 --perf-autoplay 强制静音；Spike 的 AVPlayer.volume 恒为 0）；只 simctl boot，不打开 Simulator.app，不截屏、不点击。
# --profile vela（默认）：正式 App 的 Perf 构建，bundle 缺省 com.vela.stage.ios.perf，日志子系统 com.vela.stage，
#   启动参数 --perf-autoplay，支持 single、background 与 pip（双窗尚未接入）。正式 App 的画中画默认自动进入：
#   pip 用 iPad 模拟器（DEVICE_MODEL），iPhone 模拟器不支持画中画，background 照旧全停。
# --profile spike：旧 Spike 原型，bundle 缺省 com.vela.stage.spike，日志子系统 com.vela.stage.spike，场景参数如下。
# 场景：
#   single      单窗（--autoplay-single）
#   dual        双窗（--autoplay-dual）
#   background  先前台采样 --duration 秒，再 simctl launch com.apple.Preferences 挤到后台（App 带 --no-pip），
#               等 5 秒后采样 --bg-hold 秒，再切回，测恢复首帧
#   pip         同 background，但允许画中画（单窗；加 --launch-args "--autoplay-dual" 则双窗，只焦点路进 PiP）
# 退出码：0 成功；1 环境/安装失败；2 用法错误；3 采样期间出现 macOS 探针或 Vela 进程（结果作废）；
#         4 起播失败（未在超时内出画面，或解析/起播报错）；5 采样期间 App 进程退出；6 无样本或指标缺失。
# 环境变量 DEVICE_UDID 可直接指定设备；DEVICE_MODEL 指定新建设备型号（默认 iPhone 17 Pro）。PROBE_FORCE_FAIL=nosamples|exit 仅供负样本自测。
set -u

APP=""
BUNDLE=""
DURATION=60
SCENARIO=""
LAUNCH_ARGS=""
DEVICE_NAME=vela-sim-probe
OUT_JSON=/tmp/vela-sim-result.json
RAW_DIR=""
BG_HOLD=30
PLAY_TIMEOUT=120
WAIT_QUIET=0
TEARDOWN=0
INTERVAL=5
SETTLE=5
PROFILE=vela

while [ $# -gt 0 ]; do
    case "$1" in
        --app) APP="$2"; shift 2 ;;
        --bundle) BUNDLE="$2"; shift 2 ;;
        --duration) DURATION="$2"; shift 2 ;;
        --scenario) SCENARIO="$2"; shift 2 ;;
        --profile) PROFILE="$2"; shift 2 ;;
        --launch-args) LAUNCH_ARGS="$2"; shift 2 ;;
        --device) DEVICE_NAME="$2"; shift 2 ;;
        --out) OUT_JSON="$2"; shift 2 ;;
        --raw-dir) RAW_DIR="$2"; shift 2 ;;
        --bg-hold) BG_HOLD="$2"; shift 2 ;;
        --play-timeout) PLAY_TIMEOUT="$2"; shift 2 ;;
        --wait-quiet) WAIT_QUIET="$2"; shift 2 ;;
        --teardown) TEARDOWN=1; shift ;;
        -h|--help) sed -n 2,20p "$0"; exit 0 ;;
        *) echo "未知参数: ${1}" >&2; exit 2 ;;
    esac
done

case "$SCENARIO" in single|dual|background|pip) ;; *) echo "--scenario 只能是 single|dual|background|pip" >&2; exit 2 ;; esac
case "$PROFILE" in
    vela)
        LOG_SUBSYSTEM=com.vela.stage
        BUNDLE="${BUNDLE:-com.vela.stage.ios.perf}"
        case "$SCENARIO" in single|background|pip) ;; *) echo "--profile vela 只支持 single|background|pip" >&2; exit 2 ;; esac
        ;;
    spike)
        LOG_SUBSYSTEM=com.vela.stage.spike
        BUNDLE="${BUNDLE:-com.vela.stage.spike}"
        ;;
    *) echo "--profile 只能是 vela|spike" >&2; exit 2 ;;
esac
[ -n "$APP" ] || { echo "必须给 --app" >&2; exit 2; }
case "$DURATION" in ''|*[!0-9]*) echo "--duration 必须是整数秒" >&2; exit 2 ;; esac
[ -d "$APP" ] || { echo "找不到 App: ${APP}" >&2; exit 1; }

WORK=$(mktemp -d /tmp/vela-sim-work.XXXXXX)
UDID="${DEVICE_UDID:-}"
CREATED=0
LOG_PID=""
APP_PID=""
CLEANED=0

now() { perl -MTime::HiRes=time -e 'printf "%.3f\n", time'; }
alive() { kill -0 "$1" 2>/dev/null; }
quiet_check() {
    # [p] 方括号：避免匹配到本命令自身
    [ -z "$(pgrep -f '[p]erf-probe\.sh' 2>/dev/null)$(pgrep -f 'Contents/MacOS/Vela$' 2>/dev/null)" ]
}

cleanup() {
    [ "$CLEANED" = 1 ] && return
    CLEANED=1
    [ -n "$LOG_PID" ] && kill "$LOG_PID" 2>/dev/null
    if [ -n "$UDID" ]; then
        xcrun simctl terminate "$UDID" "$BUNDLE" >/dev/null 2>&1
        xcrun simctl terminate "$UDID" com.apple.Preferences >/dev/null 2>&1
        xcrun simctl uninstall "$UDID" "$BUNDLE" >/dev/null 2>&1
        if [ "$TEARDOWN" = 1 ]; then
            xcrun simctl shutdown "$UDID" >/dev/null 2>&1
            [ "$CREATED" = 1 ] && xcrun simctl delete "$UDID" >/dev/null 2>&1
        fi
    fi
    if [ -n "$RAW_DIR" ]; then
        mkdir -p "$RAW_DIR" && cp "$WORK"/app-*.log "$WORK"/top.txt "$WORK"/meta.txt "$WORK"/samples.tsv "$WORK"/net.csv "$WORK"/phases.txt "$RAW_DIR"/ 2>/dev/null
    fi
    rm -rf "$WORK"
}
trap cleanup EXIT
trap 'exit 130' INT TERM HUP

fail_json() { echo "{\"error\":\"$1\",\"scenario\":\"${SCENARIO}\"}" >"$OUT_JSON"; cat "$OUT_JSON"; }

# 资源竞争：等 macOS 探针与 Vela 都退出
if [ "$WAIT_QUIET" -gt 0 ]; then
    waited=0
    until quiet_check; do
        [ "$waited" -ge "$WAIT_QUIET" ] && { echo "等待 ${WAIT_QUIET} 秒仍有 macOS 探针或 Vela 在跑" >&2; fail_json "contention-wait-timeout"; exit 3; }
        sleep 30; waited=$((waited + 30))
    done
    [ "$waited" -gt 0 ] && echo "已等待 ${waited} 秒，现场安静" >&2
fi

# 设备：创建或复用 iOS 17+ 模拟器
if [ -z "$UDID" ]; then
    UDID=$(xcrun simctl list devices -j | python3 -I -c '
import json, sys
name = sys.argv[1]
d = json.load(sys.stdin)["devices"]
for rt, devs in d.items():
    for x in devs:
        if x["name"] == name and x.get("isAvailable", True):
            print(x["udid"]); sys.exit()
' "$DEVICE_NAME")
fi
if [ -z "$UDID" ]; then
    RT=$(xcrun simctl list runtimes -j | python3 -I -c '
import json, sys
best = None
for r in json.load(sys.stdin)["runtimes"]:
    if r.get("platform") == "iOS" and r.get("isAvailable", True):
        v = tuple(int(x) for x in r["version"].split("."))
        if v[0] >= 17 and (best is None or v > best[0]): best = (v, r["identifier"])
print(best[1] if best else "")
')
    [ -n "$RT" ] || { echo "没有 iOS 17+ 模拟器运行时" >&2; fail_json "no-runtime"; exit 1; }
    UDID=$(xcrun simctl create "$DEVICE_NAME" "${DEVICE_MODEL:-iPhone 17 Pro}" "$RT" 2>/dev/null) || { echo "创建模拟器失败" >&2; fail_json "create-failed"; exit 1; }
    CREATED=1
fi
echo "设备 ${DEVICE_NAME} ${UDID}（本次创建=${CREATED}）" >&2

xcrun simctl boot "$UDID" >/dev/null 2>&1
xcrun simctl bootstatus "$UDID" >/dev/null 2>&1 || { echo "模拟器启动失败" >&2; fail_json "boot-failed"; exit 1; }
xcrun simctl uninstall "$UDID" "$BUNDLE" >/dev/null 2>&1
xcrun simctl install "$UDID" "$APP" || { echo "安装失败" >&2; fail_json "install-failed"; exit 1; }
xcrun simctl spawn "$UDID" log config --subsystem "$LOG_SUBSYSTEM" --mode level:debug,persist:debug >/dev/null 2>&1 \
    || echo "WARN: log config 失败" >&2

# 启动参数
if [ "$PROFILE" = vela ]; then
    ARGS="--perf-autoplay"
else
    case "$SCENARIO" in
        single) ARGS="--autoplay-single" ;;
        dual) ARGS="--autoplay-dual" ;;
        background) ARGS="--autoplay-single --no-pip" ;;
        pip) ARGS="--autoplay-single" ;;
    esac
fi
# 额外参数：带 --autoplay-dual 则去掉单窗参数
if [ -n "$LAUNCH_ARGS" ]; then
    case "$LAUNCH_ARGS" in *--autoplay-dual*) ARGS="" ;; esac
    ARGS="$ARGS $LAUNCH_ARGS"
fi
case "$ARGS" in *--autoplay-dual*) EXPECT=2 ;; *) EXPECT=1 ;; esac

STREAM="$WORK/stream.log"
: >"$STREAM"
xcrun simctl spawn "$UDID" log stream --level debug --style compact --predicate "subsystem == \"${LOG_SUBSYSTEM}\"" >"$STREAM" 2>/dev/null &
LOG_PID=$!
sleep 2

T0=$(now)
# shellcheck disable=SC2086
LAUNCH_OUT=$(xcrun simctl launch "$UDID" "$BUNDLE" $ARGS 2>&1) || { echo "启动失败: ${LAUNCH_OUT}" >&2; fail_json "launch-failed"; exit 4; }
APP_PID=$(echo "$LAUNCH_OUT" | sed -E 's/.*: *([0-9]+)$/\1/')
case "$APP_PID" in ''|*[!0-9]*) echo "取不到 pid: ${LAUNCH_OUT}" >&2; fail_json "no-pid"; exit 4 ;; esac
alive "$APP_PID" || { echo "宿主上找不到 pid ${APP_PID}" >&2; fail_json "pid-not-found"; exit 4; }
echo "App pid=${APP_PID} 参数=${ARGS}" >&2

# 等 EXPECT 路出画面（first-frame 日志）
while :; do
    n=$(grep -c 'first-frame lane=' "$STREAM")
    [ "$n" -ge "$EXPECT" ] && break
    alive "$APP_PID" || { echo "App 提前退出" >&2; fail_json "app-exited-before-play"; exit 5; }
    el=$(perl -e 'printf "%d", $ARGV[0]-$ARGV[1]' "$(now)" "$T0")
    if [ "$el" -ge 20 ] && grep -qE 'resolve-failed|relay-down|relay-nil|item-failed' "$STREAM"; then
        echo "起播失败（日志出现 resolve-failed / item-failed 等）" >&2; fail_json "play-failed"; exit 4
    fi
    [ "$el" -ge "$PLAY_TIMEOUT" ] && { echo "${PLAY_TIMEOUT} 秒内只出现 ${n}/${EXPECT} 路首帧" >&2; fail_json "play-timeout"; exit 4; }
    sleep 0.5
done
sleep "$SETTLE"

SAMPLES="$WORK/samples.tsv"; : >"$SAMPLES"
NETF="$WORK/net.csv"; : >"$NETF"
PHASES="$WORK/phases.txt"; : >"$PHASES"
phase() { echo "$1 $(now)" >>"$PHASES"; }
net_snap() { echo "#t $(now)" >>"$NETF"; nettop -x -n -L 1 -p "$APP_PID" -J bytes_in,bytes_out >>"$NETF" 2>/dev/null; }
PHASE=fg

# 采样一轮：PHASE cpu rss footprint threads wakeups
sample_once() {
    quiet_check || { echo "采样期间出现 macOS 探针或 Vela 进程，本轮作废" >&2; fail_json "contended"; exit 3; }
    alive "$APP_PID" || { echo "采样中 App 进程退出" >&2; fail_json "app-exited"; exit 5; }
    t_loop=$(now)
    rss=$(ps -o rss= -p "$APP_PID" | tr -d ' ')
    fp=$(footprint "$APP_PID" 2>/dev/null | awk '/Footprint:/ {
        for (i=1;i<=NF;i++) if ($i=="Footprint:") {v=$(i+1); u=$(i+2)}
        m=(u=="KB")?v/1024:(u=="GB")?v*1024:(u=="B")?v/1048576:v; printf "%.1f", m; exit }')
    top -l 2 -s 2 -pid "$APP_PID" -stats pid,cpu,threads,idlew >"$WORK/top.txt" 2>/dev/null
    read -r cpu thr idle <<EOF
$(awk -v app="$APP_PID" '
    /^PID/ {blk++; next}
    blk==1 && $1==app {i1=$4; gsub(/[+-]/, "", i1)}
    blk==2 && $1==app {c=$2; t=$3; sub(/\/.*/, "", t); i2=$4; gsub(/[+-]/, "", i2); found=1}
    END {if (found) printf "%.1f %d %.1f\n", c+0, t+0, (i2-i1)/2; else print "0 0 0"}' "$WORK/top.txt")
EOF
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$PHASE" "$cpu" "${rss:-0}" "${fp:-0}" "$thr" "$idle" >>"$SAMPLES"
    net_snap
    spent=$(perl -e 'printf "%.2f", $ARGV[0]-$ARGV[1]' "$(now)" "$t_loop")
    wait_s=$(perl -e 'my $w=$ARGV[0]-$ARGV[1]; printf "%.2f", $w>0?$w:0' "$INTERVAL" "$spent")
    sleep "$wait_s"
}

run_window() {  # $1 秒数
    end=$(perl -e 'printf "%.3f", $ARGV[0]+$ARGV[1]' "$(now)" "$1")
    while [ "$(perl -e 'print $ARGV[0] < $ARGV[1] ? 1 : 0' "$(now)" "$end")" = 1 ]; do sample_once; done
}

phase "fg_start"
net_snap
if [ "${PROBE_FORCE_FAIL:-}" = exit ]; then
    xcrun simctl terminate "$UDID" "$BUNDLE" >/dev/null 2>&1; sleep 1
fi
if [ "${PROBE_FORCE_FAIL:-}" = nosamples ]; then DURATION=0; fi
run_window "$DURATION"
phase "fg_end"

T_BG=0; T_RESUME=0
if [ "$SCENARIO" = background ] || [ "$SCENARIO" = pip ]; then
    PHASE=bg_wait
    T_BG=$(now)
    phase "bg_launch"
    xcrun simctl launch "$UDID" com.apple.Preferences >/dev/null 2>&1 || { fail_json "bg-launch-failed"; exit 1; }
    sleep "$SETTLE"
    PHASE=bg
    phase "bg_start"
    net_snap
    run_window "$BG_HOLD"
    phase "bg_end"
    PHASE=resume
    T_RESUME=$(now)
    phase "resume"
    xcrun simctl launch "$UDID" "$BUNDLE" >/dev/null 2>&1 || { fail_json "resume-launch-failed"; exit 1; }
    sleep 1
    run_window 25
    phase "end"
else
    phase "end"
fi
net_snap
sleep 2
kill "$LOG_PID" 2>/dev/null; LOG_PID=""
# 结束后用 log show 取一份（优先来源；stream 作后备）
SHOW="$WORK/show.log"
START_STR=$(perl -MPOSIX -e 'print strftime("%Y-%m-%d %H:%M:%S", localtime($ARGV[0]-5))' "${T0%.*}")
xcrun simctl spawn "$UDID" log show --start "$START_STR" --info --debug --style compact \
    --predicate "subsystem == \"${LOG_SUBSYSTEM}\"" >"$SHOW" 2>/dev/null
cp "$STREAM" "$WORK/app-stream.log"
cp "$SHOW" "$WORK/app-show.log"

printf 'scenario=%s\nexpect=%s\nt0=%s\ntbg=%s\ntres=%s\nsubsystem=%s\n' "$SCENARIO" "$EXPECT" "$T0" "$T_BG" "$T_RESUME" "$LOG_SUBSYSTEM" >"$WORK/meta.txt"
python3 -I "$(dirname "$0")/perf-probe-ios-analyze.py" "$WORK" >"$WORK/result.json"
RC=$?
cp "$WORK/result.json" "$OUT_JSON"
cat "$OUT_JSON"
exit "$RC"
