#!/bin/bash
# Vela 本机性能探针：后台静音播放一段时间，周期采样 CPU / 内存 / 唤醒 / 子进程，输出一行 JSON。
# 用法：scripts/perf-probe.sh [--duration 秒] [--app 路径] [--build] [--scenario play|idle] [--idle-method lock|hide]
# 兼容 macOS 自带 bash 3.2。依赖：log、top、ps、footprint、nettop、python3（查 responsible pid、解析 nettop、投递通知）。
# idle 场景：先正常起播，再用 --idle-method 让 App 进入非播放态，等 10 秒后才开始采样。
#   lock（默认）：投递分布式通知 com.apple.screenIsLocked，走 SystemActivity -> PlaybackPolicy.systemInactive，
#                 不碰窗口、不需任何权限；结束前投递 screenIsUnlocked。
#   hide：osascript 经 System Events 把进程设为不可见；需要「自动化 / 辅助功能」授权，失败则跳过。
#         注意：open -j 启动的 App 本来就是 visible=false，窗口遮挡状态不变，此法对它是空操作。
set -u

DURATION=180
APP=/tmp/vela-perf-dd/Build/Products/Release/Vela.app
BUILD=0
DOMAIN=com.vela.stage
INTERVAL=5
SCENARIO=play
IDLE_METHOD=lock
IDLE_SETTLE=10
PLAY_TIMEOUT=120
OUT_JSON=/tmp/vela-perf-result.json
WORK=$(mktemp -d /tmp/vela-perf-work.XXXXXX)

while [ $# -gt 0 ]; do
    case "$1" in
        --duration) DURATION="$2"; shift 2 ;;
        --app) APP="$2"; shift 2 ;;
        --build) BUILD=1; shift ;;
        --scenario) SCENARIO="$2"; shift 2 ;;
        --idle-method) IDLE_METHOD="$2"; shift 2 ;;
        -h|--help) sed -n 2,4p "$0"; exit 0 ;;
        *) echo "未知参数: ${1}" >&2; exit 2 ;;
    esac
done

case "$SCENARIO" in play|idle) ;; *) echo "--scenario 只能是 play 或 idle" >&2; exit 2 ;; esac
case "$IDLE_METHOD" in lock|hide) ;; *) echo "--idle-method 只能是 lock 或 hide" >&2; exit 2 ;; esac

ROOT=$(cd "$(dirname "$0")/.." && pwd)
if [ "$BUILD" = 1 ]; then
    echo "Release 构建到 /tmp/vela-perf-dd ..." >&2
    (cd "$ROOT" && xcodebuild -project Vela.xcodeproj -scheme Vela -configuration Release \
        -derivedDataPath /tmp/vela-perf-dd build >/tmp/vela-perf-build.log 2>&1) \
        || { echo "构建失败，见 /tmp/vela-perf-build.log" >&2; rm -rf "$WORK"; exit 1; }
    APP=/tmp/vela-perf-dd/Build/Products/Release/Vela.app
fi
[ -d "$APP" ] || { echo "找不到 App: ${APP}（加 --build 或 --app）" >&2; rm -rf "$WORK"; exit 1; }
BIN=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$APP/Contents/Info.plist")
if pgrep -x "$BIN" >/dev/null 2>&1; then
    echo "已有 ${BIN} 在运行，拒绝测量（会污染 defaults 与结果）。请先自行退出。" >&2
    rm -rf "$WORK"; exit 1
fi

APP_PID=""
LOG_PID=""
IDLE_ENTERED=0
CHILD_PIDS=""   # 验证过归属的子进程，空格分隔，仅用于收尾
HAD_DOMAIN=0
PREFS_MODIFIED=0   # 只有真的写过偏好，收尾才碰偏好
DEFAULTS_BAK="$WORK/defaults-backup.plist"
CLEANED=0
RESTORE_FAILED=0

# 运行前就存在的目录（存在则收尾不删，避免误伤用户数据）
PRE_DIRS=""
for d in "$HOME/Library/Caches/$DOMAIN" "$HOME/Library/WebKit/$DOMAIN" "$HOME"/Library/HTTPStorages/${DOMAIN}*; do
    [ -e "$d" ] && PRE_DIRS="${PRE_DIRS}|${d}"
done

# responsible pid 映射：stdin 逐行 pid，输出 "pid responsible"
# launchctl procinfo 需要 root，改用 libSystem 的 responsibility_get_pid_responsible_for_pid。
resp_map() {
    python3 -c '
import ctypes, sys
f = ctypes.CDLL("/usr/lib/libSystem.B.dylib").responsibility_get_pid_responsible_for_pid
f.argtypes = [ctypes.c_int]; f.restype = ctypes.c_int
for line in sys.stdin:
    p = line.strip()
    if p.isdigit():
        print(p, f(int(p)))
'
}

# 投递分布式通知（仅 Vela 等监听者会响应，无任何界面副作用）
post_note() {
    python3 -c '
import sys
from Foundation import NSDistributedNotificationCenter
NSDistributedNotificationCenter.defaultCenter().postNotificationName_object_userInfo_deliverImmediately_(sys.argv[1], None, None, True)
' "$1"
}

alive() { kill -0 "$1" 2>/dev/null; }

cleanup() {
    [ "$CLEANED" = 1 ] && return
    CLEANED=1
    [ -n "$LOG_PID" ] && kill "$LOG_PID" 2>/dev/null
    if [ "$IDLE_ENTERED" = 1 ] && [ "$IDLE_METHOD" = lock ]; then post_note com.apple.screenIsUnlocked; fi
    if [ -n "$APP_PID" ] && alive "$APP_PID"; then
        kill "$APP_PID" 2>/dev/null
        n=0; while alive "$APP_PID" && [ $n -lt 10 ]; do sleep 0.5; n=$((n + 1)); done
        alive "$APP_PID" && kill -9 "$APP_PID" 2>/dev/null
    fi
    # 记下的 WebKit 子进程：App 退出后若仍在，且命令确属 WebKit XPC 服务，才按 pid 结束
    sleep 1
    for p in $CHILD_PIDS; do
        if alive "$p"; then
            c=$(ps -p "$p" -o command= 2>/dev/null)
            case "$c" in *com.apple.WebKit.*) kill "$p" 2>/dev/null ;; esac
        fi
    done
    sleep 1
    # 恢复 defaults：没改过就不碰；备份在才 import（失败不删域、不删备份，保留并报错）；原先无此域才整域删除
    if [ "$PREFS_MODIFIED" = 1 ]; then
        if [ "$HAD_DOMAIN" = 1 ]; then
            if defaults import "$DOMAIN" "$DEFAULTS_BAK" >/dev/null 2>&1; then
                # import 不一定清掉我们新写的键：备份里没有的探针键单独删
                for k in ageConfirmed muted; do
                    plutil -extract "$k" raw -o - "$DEFAULTS_BAK" >/dev/null 2>&1 || defaults delete "$DOMAIN" "$k" >/dev/null 2>&1
                done
            else
                KEPT="${TMPDIR:-/tmp}/vela-perf-defaults-backup.$$.plist"
                cp "$DEFAULTS_BAK" "$KEPT" 2>/dev/null || KEPT="$DEFAULTS_BAK"
                [ "$KEPT" = "$DEFAULTS_BAK" ] && PERF_KEEP=1
                echo "错误：偏好恢复失败，备份保留在 ${KEPT}，请手动 defaults import ${DOMAIN} 该文件" >&2
                RESTORE_FAILED=1
            fi
        else
            defaults delete "$DOMAIN" >/dev/null 2>&1
        fi
    fi
    # 清理本次运行产生的缓存（运行前就存在的不动）
    for d in "$HOME/Library/Caches/$DOMAIN" "$HOME/Library/WebKit/$DOMAIN" "$HOME"/Library/HTTPStorages/${DOMAIN}*; do
        [ -e "$d" ] || continue
        case "$PRE_DIRS" in *"|${d}"*) continue ;; esac
        rm -rf "$d"
    done
    [ "${PERF_KEEP:-0}" = 1 ] || rm -rf "$WORK"
    [ "$RESTORE_FAILED" = 1 ] && exit 1
}
trap cleanup EXIT
trap 'exit 130' INT TERM HUP

# 记下 defaults 原状：域不存在才算「原先没有」；域存在但备份失败就立即退出，什么都不改
if defaults read "$DOMAIN" >/dev/null 2>&1; then
    if defaults export "$DOMAIN" "$DEFAULTS_BAK" >/dev/null 2>&1; then
        HAD_DOMAIN=1
    else
        echo "错误：${DOMAIN} 偏好存在但备份（defaults export）失败，未做任何修改" >&2
        exit 1
    fi
fi
PREFS_MODIFIED=1
defaults write "$DOMAIN" ageConfirmed -bool true
defaults write "$DOMAIN" muted -bool true

# 日志流：先开，再启动 App，才抓得到启动日志
LOGF="$WORK/app.log"
: >"$LOGF"
log stream --level info --style compact --predicate "process == \"$BIN\"" >"$LOGF" 2>/dev/null &
LOG_PID=$!
sleep 1

now() { perl -MTime::HiRes=time -e 'printf "%.3f\n", time'; }

T0=$(now)
open -g -j -n "$APP"
n=0
while [ -z "$APP_PID" ] && [ $n -lt 40 ]; do
    APP_PID=$(pgrep -nx "$BIN")
    [ -z "$APP_PID" ] && sleep 0.25
    n=$((n + 1))
done
[ -n "$APP_PID" ] || { echo "App 没有启动" >&2; exit 1; }
echo "App pid=${APP_PID}" >&2

# 等开始播放：优先用 AVPlayer timeControlStatus 变 .playing 时打的 "playing-started" 日志（Release 也有，真实起播时刻）；
# 旧构建没有它时回退到 "audio tracks=N enabled=1"（晚于真实起播一个音轨检查延迟）。first-frame 日志仅 DEBUG 构建才有。
STARTUP=-1
STARTUP_MARKER=none
while :; do
    if grep -qE '\[com\.vela\.stage:playback\] playing-started' "$LOGF"; then
        STARTUP=$(perl -e 'printf "%.2f", $ARGV[0]-$ARGV[1]' "$(now)" "$T0")
        STARTUP_MARKER=playing-started
        break
    fi
    if grep -qE '\[com\.vela\.stage:playback\] audio tracks=[0-9]+ enabled=[1-9]' "$LOGF"; then
        STARTUP=$(perl -e 'printf "%.2f", $ARGV[0]-$ARGV[1]' "$(now)" "$T0")
        STARTUP_MARKER=audio-tracks
        break
    fi
    alive "$APP_PID" || { echo "App 提前退出" >&2; exit 1; }
    el=$(perl -e 'printf "%d", $ARGV[0]-$ARGV[1]' "$(now)" "$T0")
    if [ "$el" -ge "$PLAY_TIMEOUT" ]; then
        echo "WARN: ${PLAY_TIMEOUT} 秒内未见开始播放日志，继续采样（startup_to_play_s=-1）" >&2
        break
    fi
    sleep 0.5
done
[ "$STARTUP" != -1 ] && echo "startup_to_play_s=${STARTUP}" >&2
sleep 5   # 让首帧后的瞬态过去

if [ "$SCENARIO" = idle ]; then
    if [ "$IDLE_METHOD" = lock ]; then
        post_note com.apple.screenIsLocked || { echo "投递通知失败，跳过 idle" >&2; echo '{"scenario":"idle","skipped":"post notification failed"}' >"$OUT_JSON"; cat "$OUT_JSON"; exit 0; }
    else
        if ! err=$(osascript -e 'tell application "System Events" to set visible of process "'"$BIN"'" to false' 2>&1); then
            echo "osascript 失败（多半缺辅助功能 / 自动化授权），跳过 idle: ${err}" >&2
            echo '{"scenario":"idle","skipped":"osascript not permitted"}' >"$OUT_JSON"; cat "$OUT_JSON"; exit 0
        fi
    fi
    IDLE_ENTERED=1
    echo "已进入非播放态（${IDLE_METHOD}），等待 ${IDLE_SETTLE} 秒后采样" >&2
    sleep "$IDLE_SETTLE"
fi

SAMPLES="$WORK/samples.tsv"
: >"$SAMPLES"
L0=$(wc -l <"$LOGF" | tr -d ' ')
TS=$(now)
# 网络：每轮循环末尾抓一份 nettop 累计快照（不能与 top 并发，常驻的 nettop 会让 top 的 IDLEW 冻结，唤醒数变 0）
NETF="$WORK/net.csv"
: >"$NETF"
net_snap() { echo "#t $(now)" >>"$NETF"; nettop -x -n -L 1 -p "$APP_PID" -J bytes_in,bytes_out >>"$NETF" 2>/dev/null; }
net_snap
END=$(perl -e 'printf "%.3f", $ARGV[0]+$ARGV[1]' "$TS" "$DURATION")
ni=0
while :; do
    t_loop=$(now)
    if [ "$(perl -e 'print $ARGV[0] >= $ARGV[1] ? 1 : 0' "$t_loop" "$END")" = 1 ]; then break; fi
    alive "$APP_PID" || { echo "采样中 App 退出" >&2; break; }

    PSF="$WORK/ps.txt"
    ps -axo pid=,ppid=,rss=,command= >"$PSF"
    # 归属判定：responsible pid == App pid（排除 App 自己）；只在 ppid 为 1 或 App 的候选里查
    CAND=$(awk -v app="$APP_PID" '$2==1 || $2==app {print $1}' "$PSF")
    echo "$CAND" | resp_map >"$WORK/resp.txt"
    # KIDSEP：非 VT 子进程（WebKit 等）；VTSEP：归属本 App 的 VTDecoderXPCService（硬解，单列）
    read -r KIDSEP VTSEP <<EOF
$(awk -v app="$APP_PID" 'NR==FNR {if ($2==app && $1!=app) r[$1]=1; next}
    ($1 in r) {if ($0 ~ /VTDecoderXPCService/) vt=vt "|" $1; else nk=nk "|" $1}
    END {print nk "|", vt "|"}' "$WORK/resp.txt" "$PSF")
EOF
    for k in $(echo "$KIDSEP $VTSEP" | tr '|' ' '); do
        case " $CHILD_PIDS " in *" $k "*) ;; *) CHILD_PIDS="$CHILD_PIDS $k" ;; esac
    done

    main_rss=$(awk -v p="$APP_PID" '$1==p {print $3}' "$PSF")
    kids_rss=$(awk -v ks="$KIDSEP" 'index(ks, "|" $1 "|") {s+=$3} END {print s+0}' "$PSF")
    fp=$(footprint "$APP_PID" 2>/dev/null | awk '/Footprint:/ {
        for (i=1;i<=NF;i++) if ($i=="Footprint:") {v=$(i+1); u=$(i+2)}
        m=(u=="KB")?v/1024:(u=="GB")?v*1024:(u=="B")?v/1048576:v; printf "%.1f", m; exit }')

    # top 两次采样，取第二次（第一次的 CPU 是自启动以来的平均，不可用）
    TOPF="$WORK/top.txt"
    top -l 2 -s 2 -n 2000 -stats pid,cpu,threads,idlew,command >"$TOPF" 2>/dev/null
    read -r m_cpu m_thr m_idle k_cpu vt_cpu <<EOF
$(awk -v app="$APP_PID" -v ks="$KIDSEP" -v vs="$VTSEP" '
    /^PID/ {blk++; next}
    blk==1 && $1==app {i1=$4; gsub(/[+-]/, "", i1)}
    blk==2 && $1 ~ /^[0-9]+$/ {
        thr=$3; sub(/.*\//, "", thr); idle=$4; gsub(/[+-]/, "", idle)
        if ($1==app) {mc=$2; mt=thr; mi=(idle-i1)/2}
        else if (index(ks, "|" $1 "|")) kc+=$2
        if (index(vs, "|" $1 "|")) vc+=$2
    }
    END {printf "%.1f %d %.1f %.1f %.1f\n", mc+0, mt+0, mi+0, kc+0, vc+0}' "$TOPF")
EOF
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$ni" "$m_cpu" "${main_rss:-0}" "${fp:-0}" "$m_thr" "$m_idle" "${kids_rss:-0}" "$k_cpu" "$vt_cpu" >>"$SAMPLES"
    ni=$((ni + 1))
    net_snap
    # 把循环补到 INTERVAL 秒
    spent=$(perl -e 'printf "%.2f", $ARGV[0]-$ARGV[1]' "$(now)" "$t_loop")
    wait_s=$(perl -e 'my $w=$ARGV[0]-$ARGV[1]; printf "%.2f", $w>0?$w:0' "$INTERVAL" "$spent")
    sleep "$wait_s"
done
TE=$(now)
sleep 2   # 让 log stream 落盘
L1=$(wc -l <"$LOGF" | tr -d ' ')
WIN="$WORK/window.log"
sed -n "$((L0 + 1)),${L1}p" "$LOGF" >"$WIN"
ELAPSED=$(perl -e 'printf "%.1f", $ARGV[0]-$ARGV[1]' "$TE" "$TS")

ISSUES=$(grep -c 'Publishing changes from within view updates' "$WIN")
LOGLINES=$(grep -c . "$WIN")
# 中继日志里可数的只有 renew（URL 过期后的换票）；清单抓取本身只在慢 / 失败时才记，无法按次数统计
RENEWS=$(grep -cE '\[com\.vela\.stage:relay\] renew (ok|failed)' "$WIN")
APP_MB=$(du -sk "$APP" | awk '{printf "%.1f", $1/1024}')

JSON=$(awk -F'\t' -v issues="$ISSUES" -v loglines="$LOGLINES" -v renews="$RENEWS" -v startup="$STARTUP" -v smarker="$STARTUP_MARKER" \
    -v appmb="$APP_MB" -v elapsed="$ELAPSED" '
    {n++; cpu[n]=$2; rss+=$3; fp+=$4; thr=$5; idle+=$6; kr+=$7; kc+=$8; vt+=$9; if ($2>mx) mx=$2}
    END {
        if (n==0) {print "{\"error\":\"no samples\"}"; exit}
        for (i=1;i<=n;i++) sum+=cpu[i]
        for (i=1;i<=n;i++) for (j=i+1;j<=n;j++) if (cpu[j]<cpu[i]) {t=cpu[i]; cpu[i]=cpu[j]; cpu[j]=t}
        k=int(0.95*n); if (k<0.95*n) k++; if (k<1) k=1
        mins=elapsed/60
        printf "{\"cpu_avg\":%.2f,\"cpu_p95\":%.2f,\"cpu_max\":%.2f,\"rss_avg_mb\":%.1f,\"footprint_avg_mb\":%.1f,\"wakeups_avg\":%.1f,\"threads\":%d,\"children_rss_mb\":%.1f,\"children_cpu_avg\":%.2f,\"vtdecoder_cpu_avg\":%.2f,\"runtime_issues\":%d,\"app_log_lines\":%d,\"startup_to_play_s\":%s,\"startup_marker\":\"%s\",\"relay_refreshes_per_min\":%.2f,\"app_size_mb\":%s,\"samples\":%d,\"elapsed_s\":%s}\n",
            sum/n, cpu[k], mx, rss/n/1024, fp/n, idle/n, thr, kr/n/1024, kc/n, vt/n, issues, loglines, startup, smarker, renews/mins, appmb, n, elapsed
    }' "$SAMPLES")

# 网络解析：进程行（Vela.<pid>）只是当前存活连接之和，连接关闭后会回落，不能直接做差，所以全部按连接累计值计：
# 每条连接取「最后一次出现的累计值 - 首个快照里的值」；首个快照之后新出现的连接整段都算窗口内；连接在两次快照间关闭时，
# 其最后一次快照之后的字节会漏记（最多漏一个采样间隔），总量因此是下限。
# 这里只有字节与远端地址，没有 URL，清单 / 分片 / 缩略图 / 目录接口无法按请求类型区分；请求类型粗分见 HTTP 请求数（按响应大小）。
NET=$(python3 -c '
import sys, socket, json
snaps = []
for line in open(sys.argv[1], errors="replace"):
    line = line.rstrip("\n")
    if line.startswith("#t "):
        snaps.append({"t": float(line[3:]), "proc": None, "conns": {}})
    elif snaps and line and not line.startswith(",bytes_in"):
        r = line.rstrip(",").split(",")
        if len(r) < 3 or not r[1].isdigit(): continue
        if r[0].startswith("Vela."): snaps[-1]["proc"] = (int(r[1]), int(r[2]))
        elif "<->" in r[0]: snaps[-1]["conns"][r[0]] = (int(r[1]), int(r[2]))
snaps = [x for x in snaps if x["proc"]]
if len(snaps) < 2:
    print(json.dumps({"net_error": "fewer than 2 nettop snapshots"})); sys.exit()
mins = (snaps[-1]["t"] - snaps[0]["t"]) / 60.0
last, base = {}, snaps[0]["conns"]
for sn in snaps[1:]:
    last.update(sn["conns"])
kinds = {"tcp": 0, "udp": 0, "loop": 0}; remotes = {}; tin = tout = 0
for key, (bi, bo) in last.items():
    b0 = base.get(key, (0, 0))
    bi -= b0[0]; bo -= b0[1]
    if bi < 0 or bo < 0: continue
    proto = key.split(" ")[0]
    host = key.split("<->")[1].rsplit(".", 1)[0]
    k = "loop" if (host.startswith("127.") or host == "::1") else ("udp" if proto.startswith("udp") else "tcp")
    kinds[k] += bi + bo; tin += bi; tout += bo
    v = remotes.setdefault((host, k), [0, 0]); v[0] += bi; v[1] += bo
kinds_in = {}
for (h, k), (bi, bo) in remotes.items(): kinds_in[k] = kinds_in.get(k, 0) + bi
def per(x): return round(x / mins, 1)
top = []
for (h, k), (bi, bo) in sorted(remotes.items(), key=lambda kv: -kv[1][0])[:5]:
    try:
        socket.setdefaulttimeout(2); name = socket.gethostbyaddr(h)[0]
    except Exception:
        name = ""
    top.append({"remote": h, "ptr": name, "proto": k, "in_kb_per_min": per(bi / 1024.0), "out_kb_per_min": per(bo / 1024.0)})
print(json.dumps({
    "net_in_mb_per_min": round(tin / 1048576.0 / mins, 3),
    "net_out_kb_per_min": per(tout / 1024.0),
    "net_tcp_in_mb_per_min": round(kinds_in.get("tcp", 0) / 1048576.0 / mins, 3),
    "net_udp_in_mb_per_min": round(kinds_in.get("udp", 0) / 1048576.0 / mins, 3),
    "net_loopback_kb_per_min": per(kinds["loop"] / 1024.0),
    "net_snapshots": len(snaps),
    "net_top_remotes": top,
}, separators=(",", ":")))
' "$NETF" 2>/dev/null)
[ -n "$NET" ] || NET='{"net_error":"nettop parse failed"}'

# HTTP 请求数：log stream 里 CFNetwork 每个 URLSession 任务结束时打一行 `Task <..> summary for task ... response_bytes=N`，
# 窗口内这类行的条数即请求数（含 URLSession 发出的所有请求：清单、分片、缩略图、目录接口；不含 AVPlayer 走 mediaserverd 的流量，
# 若有）。按响应大小粗分：小 <4KB（清单 / 接口）、中 4KB 到 256KB（目录 JSON / 缩略图）、大 >=256KB（分片），属启发式。
HTTP=$(grep -E 'CFNetwork:Summary\] Task .* summary for task' "$WIN" | grep -oE 'response_bytes=[0-9]+' | sed 's/.*=//' \
    | awk -v el="$ELAPSED" '{n++; b+=$1; if ($1<4096) {s++; sb+=$1} else if ($1<262144) {m++; mb+=$1} else {l++; lb+=$1}}
      END {mn=el/60; printf "\"http_requests_per_min\":%.1f,\"http_resp_mb_per_min\":%.3f,\"http_small_per_min\":%.1f,\"http_mid_per_min\":%.1f,\"http_large_per_min\":%.1f,\"http_large_mb_per_min\":%.3f", n/mn, b/1048576/mn, s/mn, m/mn, l/mn, lb/1048576/mn}')
[ -n "$HTTP" ] || HTTP='"http_requests_per_min":-1'

JSON=$(printf '%s' "$JSON" | sed -E 's/\}$//')
JSON="${JSON},\"scenario\":\"${SCENARIO}\",\"idle_method\":\"$([ "$SCENARIO" = idle ] && echo "$IDLE_METHOD" || echo none)\",${HTTP},$(printf '%s' "$NET" | sed -E 's/^\{//')"

echo "$JSON" >"$OUT_JSON"
echo "$JSON"
