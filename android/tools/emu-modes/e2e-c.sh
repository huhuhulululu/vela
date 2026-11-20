#!/bin/bash
# 里程碑 C 画中画端到端：S-C1 自动进入与焦点路、S-C2 静音与焦点、S-C3 关闭与后台归零。
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

if ! run_instrument pip-s1 "vela.app.e2e.c.MilestoneCTest#test01PipAutoEnterFocusLaneOnlyAndUncropped"; then
  exit 1
fi
adb shell am force-stop vela.app
if ! run_instrument pip-s2 "vela.app.e2e.c.MilestoneCTest#test02MutedAudioFocusTransientInPip"; then
  exit 1
fi
adb shell am force-stop vela.app
if ! run_instrument pip-s3 "vela.app.e2e.c.MilestoneCTest#test03AutoEnterOffAndClosePipBackgroundIdle"; then
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

def is_media(row):
    target = row.get("path") or ""
    return target.endswith(".m3u8") or target.endswith(".ts")

pip_media = 0
if index("/marker/pip-s1-begin") >= 0 and index("/marker/pip-s1-end") >= 0:
    pip = between("/marker/pip-s1-begin", "/marker/pip-s1-end")
    pip_rows = [row for row in pip if is_media(row)]
    pip_media = len(pip_rows)
    if not pip_rows:
        raise SystemExit("画中画窗口里没有媒体请求，焦点路可能未联网")

quiet = between("/marker/pip-s3-begin", "/marker/pip-s3-end")
if quiet:
    paths = [row.get("path") for row in quiet[:8]]
    raise SystemExit("关闭画中画后仍有 %d 条请求: %s" % (len(quiet), paths))

print("e2e-c-requests pip_media=%d background_rows=0" % len(pip_media))
PY

echo "e2e-c ok"