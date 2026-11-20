#!/bin/bash
# 里程碑 A 端到端：年龄确认、目录、首帧、静音稳态、划动换房、收藏与口味、
# 暂停后零媒体、后台零请求，杀进程后收藏和口味还在。
# 播放期间每 5 秒采一次 dumpsys meminfo 的 TOTAL PSS 和 dumpsys cpuinfo，
# 结束时打印一行 JSON，并写到结果目录的 e2e-a-resources.json。
# 质询页和 R1–R3 由 challenge、lane 模式覆盖，这里不重复开那些开关。
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
samples="${out_dir}/e2e-a-samples.jsonl"
stop_flag="${out_dir}/e2e-a-sample.stop"
json_path="${out_dir}/e2e-a-resources.json"
: >"${samples}"
rm -f "${stop_flag}" "${json_path}"

sample_once() {
  pid=$1
  memf="${out_dir}/e2e-a-mem.txt"
  cpuf="${out_dir}/e2e-a-cpu.txt"
  statf="${out_dir}/e2e-a-stat.txt"
  adb shell dumpsys meminfo vela.app >"${memf}" 2>/dev/null || true
  adb shell dumpsys cpuinfo >"${cpuf}" 2>/dev/null || true
  adb shell cat "/proc/${pid}/stat" >"${statf}" 2>/dev/null || true
  python3 - "${memf}" "${cpuf}" "${statf}" "$(date +%s)" >>"${samples}" <<'PY' || true
import json
import re
import sys

mem_path, cpu_path, stat_path, stamp = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
try:
    mem = open(mem_path, "r", encoding="utf-8", errors="replace").read()
except OSError:
    mem = ""
try:
    cpu = open(cpu_path, "r", encoding="utf-8", errors="replace").read()
except OSError:
    cpu = ""
pss = None
for line in mem.splitlines():
    found = re.search(r"TOTAL PSS:\s*([0-9][0-9,]*)", line)
    if found:
        pss = int(found.group(1).replace(",", ""))
if pss is None:
    sys.exit(0)
percent = None
total = None
for line in cpu.splitlines():
    found = re.search(r"([0-9]+(?:\.[0-9]+)?)%\s+[0-9]+/vela\.app:", line)
    if found:
        percent = float(found.group(1))
    found = re.search(r"([0-9]+(?:\.[0-9]+)?)%\s+TOTAL:", line)
    if found:
        total = float(found.group(1))
ticks = None
try:
    stat = open(stat_path, "r", encoding="utf-8", errors="replace").read()
except OSError:
    stat = ""
end = stat.rfind(")")
if end >= 0:
    fields = stat[end + 2:].split()
    if len(fields) > 12:
        ticks = int(fields[11]) + int(fields[12])
row = {
    "cpu_percent": percent,
    "cpu_ticks": ticks,
    "cpuinfo_total_percent": total,
    "pss_kb": pss,
    "t": int(stamp),
}
sys.stdout.write(json.dumps(row, separators=(",", ":"), sort_keys=True) + "\n")
PY
}

(
  while [ ! -f "${stop_flag}" ]; do
    begin=$(date +%s)
    pid=$(adb shell pidof vela.app 2>/dev/null | tr -d '\r' | awk 'NF { print $1; exit }') || pid=""
    if [ -n "${pid}" ]; then
      sample_once "${pid}"
    fi
    now=$(date +%s)
    remain=$((5 - (now - begin)))
    if [ "${remain}" -gt 0 ]; then
      sleep "${remain}"
    fi
  done
) &
sampler_pid=$!

write_json() {
  python3 - "${samples}" "${json_path}" <<'PY'
import json
import sys

source, dest = sys.argv[1], sys.argv[2]
rows = []
try:
    handle = open(source, "r", encoding="utf-8")
except OSError:
    handle = None
if handle is not None:
    with handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            rows.append(json.loads(line))
doc = {
    "interval_s": 5,
    "package": "vela.app",
    "sample_count": len(rows),
    "samples": rows,
    "schema": 1,
}
text = json.dumps(doc, separators=(",", ":"), sort_keys=True)
with open(dest, "w", encoding="utf-8") as out:
    out.write(text + "\n")
sys.stdout.write(text + "\n")
PY
}

finalize() {
  touch "${stop_flag}"
  if [ -n "${sampler_pid}" ]; then
    wait "${sampler_pid}" 2>/dev/null || true
    sampler_pid=""
  fi
  if [ ! -f "${json_path}" ]; then
    write_json || true
  fi
}
trap finalize EXIT

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

if ! run_instrument journey "vela.app.e2e.a.MilestoneATest#test01Journey"; then
  exit 1
fi
adb shell am force-stop vela.app
if ! run_instrument restore "vela.app.e2e.a.MilestoneATest#test02FavoriteAndTasteSurviveProcessDeath"; then
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

if [ -z "${VELA_REQUEST_LOG:-}" ] || [ ! -f "${VELA_REQUEST_LOG}" ]; then
  echo "没有请求日志" >&2
  exit 1
fi

python3 - "${VELA_REQUEST_LOG}" "${VELA_STREAM_DIR:-/tmp/vela-teststream}" <<'PY'
import json
import os
import re
import sys

log_path, stream_dir = sys.argv[1], sys.argv[2]
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

def between(start, end):
    left = index(start)
    right = index(end)
    if left < 0 or right < 0 or right <= left:
        raise SystemExit("请求日志缺少边界 %s .. %s" % (start, end))
    return rows[left + 1:right]

def marker_ts(marker):
    at = index(marker)
    if at < 0:
        raise SystemExit("请求日志缺少 %s" % marker)
    return float(rows[at]["ts"])

def base_name(path):
    name = (path or "").split("?", 1)[0]
    if name.endswith("/"):
        name = name[:-1]
    return name.rsplit("/", 1)[-1]

master_path = os.path.join(stream_dir, "master.m3u8")
if not os.path.isfile(master_path):
    raise SystemExit("没有测试流主列表：%s" % master_path)
with open(master_path, "r", encoding="utf-8") as handle:
    master = handle.read()
bandwidths = [int(item) for item in re.findall(r"BANDWIDTH=(\d+)", master)]
if not bandwidths:
    raise SystemExit("主列表没有 BANDWIDTH")
bandwidth = max(bandwidths)
audio = set()
for line in master.splitlines():
    if "TYPE=AUDIO" not in line:
        continue
    found = re.search(r'URI="([^"]+)"', line)
    if not found:
        continue
    playlist = os.path.basename(found.group(1).split("?", 1)[0])
    audio.add(playlist)
    playlist_path = os.path.join(stream_dir, playlist)
    if not os.path.isfile(playlist_path):
        continue
    with open(playlist_path, "r", encoding="utf-8") as handle:
        for entry in handle:
            entry = entry.strip()
            if entry and not entry.startswith("#"):
                audio.add(os.path.basename(entry.split("?", 1)[0]))

def is_audio(path):
    return base_name(path) in audio

def is_media(path):
    name = base_name(path)
    return name.endswith(".m3u8") or name.endswith(".ts")

aged = between("/marker/age-begin", "/marker/age-end")
api = [row.get("path") for row in aged if (row.get("path") or "").startswith("/api/")]
if api:
    raise SystemExit("未确认年龄就请求了目录：%s" % api[:8])

if not any((row.get("path") or "").startswith("/api/ts/roomlist/room-list") for row in rows):
    raise SystemExit("请求日志里没有目录")
if not any("chatvideocontext" in (row.get("path") or "") for row in rows):
    raise SystemExit("请求日志里没有取流")

quiet = between("/marker/mute-begin", "/marker/mute-end")
audio_playlists = [row.get("path") for row in quiet if is_audio(row.get("path")) and base_name(row.get("path")).endswith(".m3u8")]
audio_segments = [row.get("path") for row in quiet if is_audio(row.get("path")) and base_name(row.get("path")).endswith(".ts")]
if len(audio_playlists) > 1:
    raise SystemExit("静音稳态 audio 播放列表 %d 次：%s" % (len(audio_playlists), audio_playlists[:8]))
if audio_segments:
    raise SystemExit("静音稳态仍有音频分片：%s" % audio_segments[:8])

duration = marker_ts("/marker/mute-end") - marker_ts("/marker/mute-begin")
if duration < 50:
    raise SystemExit("静音窗口只有 %.1f 秒" % duration)
media_bytes = sum(int(row.get("bytes") or 0) for row in quiet if is_media(row.get("path")))
ratio = (media_bytes * 8.0) / (bandwidth * duration)
print(
    "mute-window audio_playlists=%d audio_segments=0 bytes=%d bandwidth=%d duration=%.1f ratio=%.3f"
    % (len(audio_playlists), media_bytes, bandwidth, duration, ratio)
)
if ratio > 1.15:
    raise SystemExit("媒体字节超过档位带宽的 1.15 倍：%.3f" % ratio)

paused = between("/marker/pause-begin", "/marker/pause-end")
media = [row.get("path") for row in paused if is_media(row.get("path"))]
if media:
    raise SystemExit("暂停后仍有媒体请求: %s" % media[:8])

background = between("/marker/background-begin", "/marker/background-end")
if background:
    paths = [row.get("path") for row in background[:8]]
    raise SystemExit("后台窗口里还有 %d 条请求: %s" % (len(background), paths))
print("e2e-a-requests ok pause_rows=%d background_rows=0" % len(paused))
PY

finalize

python3 -I tools/resource_sample.py "${json_path}"
