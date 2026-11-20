#!/bin/bash
# 画中画：开自动进入时回桌面只留焦点路；关掉则全停（背景仪器测）。
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

out_dir="${VELA_MODE_RESULT_DIR:-${TMPDIR:-/tmp}}"
mkdir -p "${out_dir}"
out="${out_dir}/pip-instrument.txt"
set +e
adb shell am instrument -w \
  -e package vela.app.pip \
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
echo "pip-instrument ok"