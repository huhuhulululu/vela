#!/bin/bash
# 里程碑 B 双窗端到端：两路不同房、焦点出声、副路操作、双窗记住（S-B1）；
# 热降档 59/60 秒、副路资源降级、两路同出画面 ≥ 90%（S-B2）。
# 质询、R1–R3、后台归零由 challenge / lane / background / e2e-a 覆盖，这里不重复开那些开关。
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

if ! run_instrument dual "vela.app.e2e.b.MilestoneBTest#test01DualDistinctFocusAndRemembered"; then
  exit 1
fi
if ! run_instrument thermal "vela.app.e2e.b.MilestoneBTest#test02ThermalDegradeAndSimultaneousFrames"; then
  exit 1
fi
adb shell am force-stop vela.app
if ! run_instrument restore "vela.app.e2e.b.MilestoneBTest#test03DualPreferenceSurvivesRelaunch"; then
  exit 1
fi

if [ -n "${VELA_LOGCAT:-}" ] && [ -f "${VELA_LOGCAT}" ]; then
  if ! grep -F "first-frame lane=" "${VELA_LOGCAT}" >/dev/null 2>&1; then
    echo "logcat 没有 first-frame" >&2
    exit 1
  fi
fi

if [ -z "${VELA_REQUEST_LOG:-}" ] || [ ! -f "${VELA_REQUEST_LOG}" ]; then
  echo "没有请求日志" >&2
  exit 1
fi

python3 - "${VELA_REQUEST_LOG}" <<'PY'
import json
import sys

log_path = sys.argv[1]
rows = []
with open(log_path, "r", encoding="utf-8") as handle:
    for line in handle:
        line = line.strip()
        if not line:
            continue
        rows.append(json.loads(line))

def index(marker):
    for i, row in enumerate(rows):
        if row.get("path") == marker:
            return i
    return -1

def require_marker(marker):
    if index(marker) < 0:
        raise SystemExit("请求日志缺少 %s" % marker)

for marker in (
    "/marker/dual-begin",
    "/marker/dual-end",
    "/marker/thermal-begin",
    "/marker/thermal-end",
    "/marker/degrade-begin",
    "/marker/degrade-end",
    "/marker/both-begin",
    "/marker/both-end",
):
    require_marker(marker)

if not any((row.get("path") or "").startswith("/api/ts/roomlist/room-list") for row in rows):
    raise SystemExit("请求日志里没有目录")
if not any("chatvideocontext" in (row.get("path") or "") for row in rows):
    raise SystemExit("请求日志里没有取流")

print("e2e-b-requests ok markers=8")
PY
