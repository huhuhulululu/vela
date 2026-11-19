#!/bin/bash
# 后台：暂停后不再拉媒体；按 HOME 释放后 30 秒伪上游零请求。
# 播放线程、CPU tick、回前台恢复由仪器测试在设备上断言。
# 请求窗口用 /marker/ 边界，不依赖宿主机和模拟器时钟对齐。
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
out="${out_dir}/background-instrument.txt"
set +e
adb shell am instrument -w \
  -e package vela.app.lifecycle \
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

if [ -z "${VELA_REQUEST_LOG:-}" ] || [ ! -f "${VELA_REQUEST_LOG}" ]; then
  echo "没有请求日志" >&2
  exit 1
fi

python3 - "${VELA_REQUEST_LOG}" <<'PY'
import json
import sys

path = sys.argv[1]
rows = []
with open(path, "r", encoding="utf-8") as handle:
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

def between(start, end):
    left = index(start)
    right = index(end)
    if left < 0 or right < 0 or right <= left:
        raise SystemExit("请求日志缺少边界 %s .. %s" % (start, end))
    return rows[left + 1:right]

def is_media(row):
    target = row.get("path") or ""
    return target.endswith(".m3u8") or target.endswith(".ts")

paused = between("/marker/pause-begin", "/marker/pause-end")
media = [row.get("path") for row in paused if is_media(row)]
if media:
    raise SystemExit("暂停后仍有媒体请求: %s" % media[:8])

quiet = between("/marker/background-begin", "/marker/background-end")
if quiet:
    paths = [row.get("path") for row in quiet[:8]]
    raise SystemExit("后台窗口里还有 %d 条请求: %s" % (len(quiet), paths))
print("background-requests ok pause_rows=%d background_rows=0" % len(paused))
PY
