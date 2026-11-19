#!/bin/bash
# 质询：目录被拦时弹出 WebView，在网页里打开通关地址后再起播。
# vela-upstream-flags: --challenge
set -eu

SCRIPT_DIR=$(CDPATH= cd "$(dirname "$0")" && pwd)
ROOT=$(CDPATH= cd "$SCRIPT_DIR/../../.." && pwd)

cd "$ROOT/android"
# 属性是一个词（-Pvela.upstream=http://…），不要再加引号拆开。
# 脚手架没有 perf 的 androidTest 变体，应用打 perf，仪器包打 debug。
./gradlew :app:assemblePerf :app:assembleDebugAndroidTest ${VELA_GRADLE_UPSTREAM:-}

app_apk=""
test_apk=""
for f in app/build/outputs/apk/perf/*.apk; do
  if [ ! -f "$f" ]; then
    continue
  fi
  case "$f" in
    *androidTest*) ;;
    *) app_apk=$f ;;
  esac
done
for f in app/build/outputs/apk/androidTest/debug/*.apk; do
  if [ -f "$f" ]; then
    test_apk=$f
  fi
done
if [ -z "${app_apk}" ] || [ -z "${test_apk}" ]; then
  echo "找不到 perf 包或 androidTest 包" >&2
  exit 1
fi

adb install -r -t "${app_apk}"
adb install -r -t "${test_apk}"

out_dir="${VELA_MODE_RESULT_DIR:-${TMPDIR:-/tmp}}"
mkdir -p "${out_dir}"
out="${out_dir}/challenge-instrument.txt"
set +e
adb shell am instrument -w \
  -e class vela.app.playchain.ChallengeEmulatorTest \
  -e upstream "${VELA_UPSTREAM:-}" \
  vela.app.test/androidx.test.runner.AndroidJUnitRunner >"${out}" 2>&1
status=$?
set -e
cat "${out}"
if [ "${status}" -ne 0 ]; then
  exit "${status}"
fi
if grep -F "FAILURES!!!" "${out}" >/dev/null 2>&1; then
  exit 1
fi
if grep -F "INSTRUMENTATION_FAILED" "${out}" >/dev/null 2>&1; then
  exit 1
fi
if ! grep -F "OK (" "${out}" >/dev/null 2>&1; then
  echo "仪器测试没有 OK" >&2
  exit 1
fi

proxy_out="${out_dir}/proxy-instrument.txt"
set +e
adb shell am instrument -w \
  -e class vela.app.infra.WebProxyEmulatorTest \
  vela.app.test/androidx.test.runner.AndroidJUnitRunner >"${proxy_out}" 2>&1
proxy_status=$?
set -e
cat "${proxy_out}"
if [ "${proxy_status}" -ne 0 ]; then
  exit "${proxy_status}"
fi
if grep -F "FAILURES!!!" "${proxy_out}" >/dev/null 2>&1; then
  exit 1
fi
if grep -F "INSTRUMENTATION_FAILED" "${proxy_out}" >/dev/null 2>&1; then
  exit 1
fi
if ! grep -F "OK (" "${proxy_out}" >/dev/null 2>&1; then
  echo "代理仪器测试没有 OK" >&2
  exit 1
fi

if [ -n "${VELA_REQUEST_LOG:-}" ] && [ -f "${VELA_REQUEST_LOG}" ]; then
  if ! grep -F '"path":"/api/ts/roomlist/room-list"' "${VELA_REQUEST_LOG}" >/dev/null 2>&1; then
    echo "请求日志里没有目录" >&2
    exit 1
  fi
  if ! grep -F '"path":"/__test/challenge-ok"' "${VELA_REQUEST_LOG}" >/dev/null 2>&1; then
    echo "请求日志里没有质询通关" >&2
    exit 1
  fi
  if ! grep -F "chatvideocontext" "${VELA_REQUEST_LOG}" >/dev/null 2>&1; then
    echo "请求日志里没有取流" >&2
    exit 1
  fi
fi
