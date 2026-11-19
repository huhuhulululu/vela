#!/bin/bash
# 单路：首帧、uncropped、留边不是大块纯黑、R1 到 R3。
# vela-upstream-flags: --rewind --rotate-uri --expire-idle 35
set -eu

SCRIPT_DIR=$(CDPATH= cd "$(dirname "$0")" && pwd)
ROOT=$(CDPATH= cd "$SCRIPT_DIR/../../.." && pwd)

cd "$ROOT/android"
# 属性是一个词（-Pvela.upstream=http://…），不要再加引号拆开。
# 脚手架没有 perf 的 androidTest 变体（testBuildType 仍是 debug），
# 所以应用打 perf，仪器包打 debug。两边 applicationId 都是 vela.app。
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
out="${out_dir}/lane-instrument.txt"
set +e
adb shell am instrument -w \
  -e class vela.app.lane.LaneEmulatorTest \
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

if [ -n "${VELA_LOGCAT:-}" ] && [ -f "${VELA_LOGCAT}" ]; then
  if ! grep -F "display-check" "${VELA_LOGCAT}" | grep -F "uncropped=true" >/dev/null 2>&1; then
    echo "logcat 没有 uncropped=true 的 display-check" >&2
    exit 1
  fi
  if ! grep -F "first-frame lane=" "${VELA_LOGCAT}" >/dev/null 2>&1; then
    echo "logcat 没有 first-frame" >&2
    exit 1
  fi
fi
