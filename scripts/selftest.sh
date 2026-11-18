#!/bin/bash
# Vela 调试版自测：无声、无截屏、不点击，由 App 自己判定并写 os_log（subsystem com.vela.stage, category selftest）。
# 用法：scripts/selftest.sh [unmute|lock-resume|boss ...]   不带场景则依次跑全部三个
#       scripts/selftest.sh --check-release                  构建 Release，确认二进制不含自测代码
# 环境变量：SELFTEST_KEEP=1 结束时保留 /tmp/vela-st2-*（调试用）；SELFTEST_WAIT_MIN 等待他人占用的分钟数（默认 90）。
# 兼容 macOS 自带 bash 3.2。任一场景 FAIL 则非 0 退出。
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
DOMAIN=com.vela.stage
DD=/tmp/vela-st2-dd
WORK=$(mktemp -d /tmp/vela-st2-work.XXXXXX)
BACKUP="$WORK/defaults-backup.plist"
HAD_DOMAIN=0
PREFS_TOUCHED=0
APP_PID=""
SCENARIO_TIMEOUT=180
WAIT_MIN=${SELFTEST_WAIT_MIN:-90}

clean_caches() {
    rm -rf "$HOME/Library/Caches/$DOMAIN" "$HOME/Library/HTTPStorages/$DOMAIN" "$HOME/Library/WebKit/$DOMAIN"
}

restore_prefs() {
    [ "$PREFS_TOUCHED" = 1 ] || return 0
    if [ "$HAD_DOMAIN" = 1 ]; then
        defaults delete "$DOMAIN" >/dev/null 2>&1
        if ! defaults import "$DOMAIN" "$BACKUP" >/dev/null 2>&1; then
            local kept="${TMPDIR:-/tmp}/vela-selftest-defaults-backup.$$.plist"
            cp "$BACKUP" "$kept" 2>/dev/null
            echo "错误：偏好恢复失败，备份保留在 ${kept}" >&2
        fi
    else
        defaults delete "$DOMAIN" >/dev/null 2>&1
    fi
    PREFS_TOUCHED=0
}

cleanup() {
    # 只结束自己记下的 pid
    if [ -n "$APP_PID" ] && kill -0 "$APP_PID" 2>/dev/null; then kill "$APP_PID" 2>/dev/null; fi
    restore_prefs
    clean_caches
    if [ "${SELFTEST_KEEP:-0}" != 1 ]; then rm -rf /tmp/vela-st2-*; else rm -rf "$WORK"; fi
}
trap cleanup EXIT

# 他人的 perf-probe / xcodebuild / Vela。按进程命令的开头匹配，避免把命令行里碰巧含这些词的 shell 当成占用。
busy_procs() {
    ps -axo pid=,command= | grep -E '^ *[0-9]+ +(/bin/)?(ba)?sh [^ ]*perf-probe\.sh|^ *[0-9]+ +[^ ]*/xcodebuild( |$)|^ *[0-9]+ +[^ ]*Contents/MacOS/Vela$'
}

wait_for_idle() {
    local waited=0 limit=$((WAIT_MIN * 60))
    while :; do
        if [ -z "$(busy_procs)" ]; then
            return 0
        fi
        if [ "$waited" -ge "$limit" ]; then
            echo "等待 ${WAIT_MIN} 分钟后仍有 perf-probe / xcodebuild / Vela 在运行，放弃" >&2
            return 1
        fi
        sleep 30
        waited=$((waited + 30))
    done
}

build() {   # build <配置> <derivedData>
    xcodebuild -project "$ROOT/Vela.xcodeproj" -scheme Vela -destination 'platform=macOS' -configuration "$1" \
        -derivedDataPath "$2" CODE_SIGN_IDENTITY=- CODE_SIGNING_ALLOWED=NO build >"$WORK/build-$1.log" 2>&1 \
        || { echo "构建失败：" >&2; tail -30 "$WORK/build-$1.log" >&2; return 1; }
}

check_release() {
    wait_for_idle || return 1
    echo "构建 Release ..."
    build Release /tmp/vela-st2-dd-rel || return 1
    local bin=/tmp/vela-st2-dd-rel/Build/Products/Release/Vela.app/Contents/MacOS/Vela
    [ -f "$bin" ] || { echo "找不到 Release 产物 ${bin}" >&2; return 1; }
    local n
    n=$(strings "$bin" | grep -c selftest)
    echo "Release 二进制中 selftest 字符串行数: ${n}"
    [ "$n" = 0 ] || { echo "FAIL：Release 含自测代码" >&2; return 1; }
    echo "PASS：Release 安全门为 0"
}

run_scenario() {   # run_scenario <场景> -> 0 PASS / 1 FAIL
    local scn="$1" app="$DD/Build/Products/Debug/Vela.app" started
    started=$(date '+%Y-%m-%d %H:%M:%S')
    open -g -j -n "$app" --args --selftest "$scn" || { echo "[$scn] 启动失败"; return 1; }
    local i=0
    while [ -z "$APP_PID" ] && [ "$i" -lt 20 ]; do
        APP_PID=$(pgrep -f "Debug/Vela.app/Contents/MacOS/Vela --selftest $scn" | head -1)
        [ -n "$APP_PID" ] || sleep 0.5
        i=$((i + 1))
    done
    [ -n "$APP_PID" ] || { echo "[$scn] 找不到进程"; return 1; }
    local waited=0
    while kill -0 "$APP_PID" 2>/dev/null; do
        if [ "$waited" -ge "$SCENARIO_TIMEOUT" ]; then
            echo "[$scn] 超时 ${SCENARIO_TIMEOUT} 秒，结束 pid ${APP_PID}"
            kill "$APP_PID" 2>/dev/null
            break
        fi
        sleep 2
        waited=$((waited + 2))
    done
    APP_PID=""
    sleep 2
    local lines
    lines=$(log show --start "$started" --predicate 'subsystem == "com.vela.stage" AND category == "selftest"' --info --style compact 2>/dev/null \
        | grep "selftest ${scn}" | sed -e 's/^[^ ]* [^ ]* *//')
    echo "---- ${scn} 日志 ----"
    echo "$lines"
    echo "--------------------"
    if echo "$lines" | grep -q "selftest ${scn} PASS"; then
        echo "[$scn] PASS"; return 0
    fi
    echo "[$scn] FAIL ($(echo "$lines" | grep "selftest ${scn} FAIL" | tail -1 | sed -e 's/.*FAIL //'))"
    return 1
}

if [ "${1:-}" = "--check-release" ]; then
    check_release
    exit $?
fi

SCENARIOS="$*"
[ -n "$SCENARIOS" ] || SCENARIOS="unmute lock-resume boss"
for s in $SCENARIOS; do
    case "$s" in unmute|lock-resume|boss) ;; *) echo "未知场景: ${s}" >&2; exit 2 ;; esac
done

wait_for_idle || exit 1
echo "构建 Debug ..."
build Debug "$DD" || exit 1

if defaults read "$DOMAIN" >/dev/null 2>&1; then
    defaults export "$DOMAIN" "$BACKUP" >/dev/null 2>&1 \
        || { echo "错误：${DOMAIN} 偏好存在但备份失败，未做任何修改" >&2; exit 1; }
    HAD_DOMAIN=1
fi
PREFS_TOUCHED=1
defaults write "$DOMAIN" ageConfirmed -bool true
defaults write "$DOMAIN" muted -bool true

FAILED=0
for s in $SCENARIOS; do
    run_scenario "$s" || FAILED=1
done
[ "$FAILED" = 0 ] && echo "全部场景 PASS" || echo "存在 FAIL 场景"
exit "$FAILED"
