#!/bin/bash
# 里程碑 B 双窗端到端：两路不同房、焦点出声、热降档与回升、副路降级、双路出画占比。
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

if ! run_instrument dual-journey "vela.app.e2e.b.MilestoneBTest#test01DualDistinctRoomsFocusAudioAndRemembered"; then
  exit 1
fi
if ! run_instrument dual-thermal "vela.app.e2e.b.MilestoneBTest#test02ThermalDualRenderAndSecondaryDegrade"; then
  exit 1
fi
adb shell am force-stop vela.app
if ! run_instrument dual-remember "vela.app.e2e.b.MilestoneBTest#test03RememberedDualAfterStop"; then
  exit 1
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
        if line:
            rows.append(json.loads(line))

def index(marker):
    for i, row in enumerate(rows):
        if row.get("path") == marker:
            return i
    return -1

def between(start, end):
    left = index(start)
    right = index(end)
    if left < 0 or right < 0 or right <= left:
        raise SystemExit("请求日志缺少边界 %s .. %s" % (start, end))
    return rows[left + 1 : right]

dual = between("/marker/dual-begin", "/marker/dual-end")
users = set()
for row in dual:
    path = row.get("path") or ""
    if "chatvideocontext" in path:
        parts = path.strip("/").split("/")
        if parts:
            users.add(parts[-1].split("?", 1)[0].lower())
if len(users) < 2:
    raise SystemExit("双窗旅程里取流房间不足 2 个：%s" % sorted(users))

thermal = between("/marker/thermal-begin", "/marker/thermal-end")
thermal_users = set()
for row in thermal:
    path = row.get("path") or ""
    if "chatvideocontext" in path:
        parts = path.strip("/").split("/")
        if parts:
            thermal_users.add(parts[-1].split("?", 1)[0].lower())
if len(thermal_users) < 2:
    raise SystemExit("热降档窗口里取流房间不足 2 个")
print("e2e-b-requests dual_users=%d thermal_users=%d" % (len(users), len(thermal_users)))
PY

echo "e2e-b ok"