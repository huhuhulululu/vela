#!/bin/bash
# 画中画：回桌面自动进入或 3 秒全停；只放行焦点路；比例夹紧。
# 登记到 emulator-run.sh（all 会按文件名执行本模式）。
set -eu

SCRIPT_DIR=$(CDPATH= cd "$(dirname "$0")" && pwd)
ROOT=$(CDPATH= cd "$SCRIPT_DIR/../../.." && pwd)

cd "$ROOT/android"
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
adb shell pm clear vela.app >/dev/null

out_dir="${VELA_MODE_RESULT_DIR:-${TMPDIR:-/tmp}}"
mkdir -p "${out_dir}"

run_instrument() {
  name=$1
  class=$2
  out="${out_dir}/${name}.txt"
  set +e
  adb shell am instrument -w \
    -e class "${class}" \
    -e upstream "${VELA_UPSTREAM:-}" \
    vela.app.test/androidx.test.runner.AndroidJUnitRunner >"${out}" 2>&1
  status=$?
  set -e
  cat "${out}"
  if [ "${status}" -ne 0 ]; then
    return "${status}"
  fi
  if grep -F "FAILURES!!!" "${out}" >/dev/null 2>&1; then
    return 1
  fi
  if grep -F "INSTRUMENTATION_FAILED" "${out}" >/dev/null 2>&1; then
    return 1
  fi
  if ! grep -F "OK (" "${out}" >/dev/null 2>&1; then
    echo "仪器测试没有 OK（${name}）" >&2
    return 1
  fi
  return 0
}

if ! run_instrument pip-no-auto "vela.app.pip.PipEmulatorTest#testHomeWithoutAutoEnterStopsAllWithinThreeSeconds"; then
  exit 1
fi
adb shell am force-stop vela.app
if ! run_instrument pip-auto "vela.app.pip.PipEmulatorTest#testHomeWithAutoEnterEitherEntersPipOrStopsAll"; then
  exit 1
fi
adb shell am force-stop vela.app
if ! run_instrument pip-aspect "vela.app.pip.PipEmulatorTest#testAspectClampRejectsExtremeRatios"; then
  exit 1
fi
if ! run_instrument pip-mute-action "vela.app.pip.PipEmulatorTest#testMuteRemoteActionIntentExists"; then
  exit 1
fi

echo "pip mode ok"
