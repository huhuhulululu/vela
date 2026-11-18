#!/bin/bash
# 模拟器验收入口。bash 3.2 可跑（macOS 自带 bash 与 Linux 皆可）。
#
# 用法：android/tools/emulator-run.sh <模式|all>
#
# 先确认 adb 里有 state=device 的设备，再按模式启停伪上游、抓 logcat、执行模式脚本、汇总退出码。
# all 按文件名顺序执行 emu-modes/*.sh；一个都没有时仍启停一次伪上游，退出码 0。
# 任一模式非 0，则全部跑完后以 1 退出。
#
# 模式脚本可在前 40 行写一行（参数按空白拆开，不要加引号）：
#   # vela-upstream-flags: --rewind --rotate-uri --ll-parts --expire-idle 35 --challenge
# 框架总会加上 --request-log。目录夹具默认开；VELA_UPSTREAM_NO_CATALOG=1 时关掉。
# --rewind / --rotate-uri / --expire-idle 只是打开能力，播放列表在触发前与平时相同。
# 模式脚本在宿主机上执行，需要注入时请求：
#   $VELA_UPSTREAM_LOCAL/__test/rewind
#   $VELA_UPSTREAM_LOCAL/__test/rotate
#   $VELA_UPSTREAM_LOCAL/__test/expire
#   $VELA_UPSTREAM_LOCAL/__test/challenge-ok
#
# 传给模式脚本的环境：
#   ANDROID_SERIAL         选中的设备
#   VELA_UPSTREAM          http://10.0.2.2:<端口>（模拟器访问宿主机回环）
#   VELA_UPSTREAM_LOCAL    http://127.0.0.1:<端口>
#   VELA_UPSTREAM_PORT
#   VELA_GRADLE_UPSTREAM   -Pvela.upstream=http://10.0.2.2:<端口>
#   VELA_REQUEST_LOG       本模式的 jsonl
#   VELA_LOGCAT            本模式的 logcat
#   VELA_MODE
#   VELA_MODE_RESULT_DIR
#   VELA_STREAM_DIR
#
# 可选环境：VELA_STREAM_DIR（默认 /tmp/vela-teststream）、VELA_EMU_MODES_DIR（默认本目录下 emu-modes）。
set -eu

SCRIPT_DIR=$(CDPATH= cd "$(dirname "$0")" && pwd)
ROOT=$(CDPATH= cd "$SCRIPT_DIR/../.." && pwd)
MODES="${VELA_EMU_MODES_DIR:-$SCRIPT_DIR/emu-modes}"
STREAM_DIR="${VELA_STREAM_DIR:-/tmp/vela-teststream}"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/vela-emu.XXXXXX")

SERVER_PID=""
LOGCAT_PID=""

stop_children() {
  if [ -n "${LOGCAT_PID}" ]; then
    kill "${LOGCAT_PID}" 2>/dev/null || true
    wait "${LOGCAT_PID}" 2>/dev/null || true
    LOGCAT_PID=""
  fi
  if [ -n "${SERVER_PID}" ]; then
    kill "${SERVER_PID}" 2>/dev/null || true
    wait "${SERVER_PID}" 2>/dev/null || true
    SERVER_PID=""
  fi
}

cleanup() {
  stop_children
}
trap cleanup EXIT
trap 'exit 1' INT TERM

usage() {
  echo "用法：emulator-run.sh <模式|all>" >&2
  exit 2
}

if [ "$#" -ne 1 ]; then
  usage
fi
TARGET=$1

if ! command -v adb >/dev/null 2>&1; then
  echo "找不到 adb" >&2
  exit 1
fi

serial=$(adb devices | awk 'NR>1 && $2 == "device" { print $1; exit }') || serial=""
if [ -z "${serial}" ]; then
  echo "没有已启动的模拟器（adb devices 里没有 state=device）" >&2
  adb devices >&2 || true
  exit 1
fi
export ANDROID_SERIAL="${serial}"
echo "模拟器 ${serial}" >&2

if [ ! -f "${STREAM_DIR}/master.m3u8" ]; then
  "${ROOT}/scripts/make-test-stream.sh" "${STREAM_DIR}"
fi

request_count() {
  if [ -f "$1" ]; then
    awk 'END { print NR + 0 }' "$1"
  else
    echo 0
  fi
}

mode_flags() {
  awk 'NR<=40 && /^# vela-upstream-flags:/ { sub(/^# vela-upstream-flags:[[:space:]]*/, ""); print; exit }' "$1"
}

health_check() {
  python3 - "$1" <<'PY'
import sys
import urllib.request
urllib.request.urlopen(sys.argv[1], timeout=5).read()
PY
}

start_server() {
  script=$1
  name=$2
  portf="${WORK}/${name}.port"
  reqlog="${WORK}/${name}.requests.jsonl"
  errf="${WORK}/${name}.server.err"
  : >"${reqlog}"
  extra=$(mode_flags "${script}")
  rm -f "${portf}"
  set -- python3 -I "${ROOT}/scripts/serve-test-stream.py" "${STREAM_DIR}" --port-file "${portf}" --request-log "${reqlog}"
  if [ "${VELA_UPSTREAM_NO_CATALOG:-}" != "1" ]; then
    set -- "$@" --catalog-fixtures
  fi
  if [ -n "${extra}" ]; then
    # 模式头里的开关按空白拆开，故意不打引号
    set -- "$@" ${extra}
  fi
  "$@" >"${WORK}/${name}.server.out" 2>"${errf}" &
  SERVER_PID=$!
  n=0
  while [ ! -s "${portf}" ]; do
    if ! kill -0 "${SERVER_PID}" 2>/dev/null; then
      echo "伪上游没有起来（${name}）" >&2
      cat "${errf}" >&2 || true
      SERVER_PID=""
      return 1
    fi
    n=$((n + 1))
    if [ "${n}" -gt 50 ]; then
      echo "伪上游端口文件超时（${name}）" >&2
      cat "${errf}" >&2 || true
      return 1
    fi
    sleep 0.1
  done
  port=$(tr -d ' \t\r\n' < "${portf}")
  case "${port}" in
    ""|*[!0-9]*)
      echo "端口无效：${port}" >&2
      return 1
      ;;
  esac
  VELA_UPSTREAM="http://10.0.2.2:${port}"
  VELA_UPSTREAM_LOCAL="http://127.0.0.1:${port}"
  VELA_UPSTREAM_PORT="${port}"
  VELA_GRADLE_UPSTREAM="-Pvela.upstream=${VELA_UPSTREAM}"
  VELA_REQUEST_LOG="${reqlog}"
  export VELA_UPSTREAM VELA_UPSTREAM_LOCAL VELA_UPSTREAM_PORT VELA_GRADLE_UPSTREAM VELA_REQUEST_LOG
  export VELA_STREAM_DIR="${STREAM_DIR}"
  return 0
}

start_logcat() {
  name=$1
  logcat="${WORK}/${name}.logcat"
  : >"${logcat}"
  adb logcat -c >/dev/null 2>&1 || true
  adb logcat -v time >"${logcat}" 2>&1 &
  LOGCAT_PID=$!
  VELA_LOGCAT="${logcat}"
  export VELA_LOGCAT
}

run_mode() {
  name=$1
  script=$2
  code=1
  upstream="-"
  reqlog=""
  logcat=""
  resdir="${WORK}/${name}.result"
  mkdir -p "${resdir}"
  export VELA_MODE="${name}"
  export VELA_MODE_RESULT_DIR="${resdir}"
  if start_server "${script}" "${name}"; then
    upstream="${VELA_UPSTREAM}"
    reqlog="${VELA_REQUEST_LOG}"
    start_logcat "${name}"
    logcat="${VELA_LOGCAT}"
    if health_check "${VELA_UPSTREAM_LOCAL}/master.m3u8"; then
      set +e
      bash "${script}"
      code=$?
      set -e
    else
      echo "伪上游健康检查失败（${name}）" >&2
      code=1
    fi
  fi
  stop_children
  nreq=$(request_count "${reqlog}")
  printf 'mode=%s exit=%s upstream=%s requests=%s logcat=%s\n' \
    "${name}" "${code}" "${upstream}" "${nreq}" "${logcat}"
  if [ "${code}" -eq 0 ]; then
    return 0
  fi
  return 1
}

ran=0
failed=0

if [ "${TARGET}" = "all" ]; then
  if [ -d "${MODES}" ]; then
    for script in "${MODES}"/*.sh; do
      if [ ! -f "${script}" ]; then
        continue
      fi
      ran=$((ran + 1))
      name=$(basename "${script}" .sh)
      if ! run_mode "${name}" "${script}"; then
        failed=$((failed + 1))
      fi
    done
  fi
  if [ "${ran}" -eq 0 ]; then
    smoke="${WORK}/bootstrap.sh"
    printf '%s\n' '#!/bin/bash' 'exit 0' >"${smoke}"
    ran=1
    if ! run_mode bootstrap "${smoke}"; then
      failed=$((failed + 1))
    fi
  fi
else
  script="${MODES}/${TARGET}.sh"
  if [ ! -f "${script}" ]; then
    echo "没有模式脚本：${script}" >&2
    exit 1
  fi
  ran=1
  if ! run_mode "${TARGET}" "${script}"; then
    failed=$((failed + 1))
  fi
fi

printf 'summary modes=%s failed=%s\n' "${ran}" "${failed}"
if [ "${failed}" -ne 0 ]; then
  exit 1
fi
exit 0
