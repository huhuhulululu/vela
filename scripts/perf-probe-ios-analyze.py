#!/usr/bin/env python3
"""perf-probe-ios.sh 的分析器：读 DIR 里的 samples.tsv / net.csv / phases.txt / app-*.log / meta.txt，输出一行 JSON。
用法：python3 -I perf-probe-ios-analyze.py DIR [--scenario S --expect N]（无 meta.txt 的旧目录用后两项补）。
退出码：0 成功；4 起播失败；6 无样本或指标缺失。PROBE_FORCE_FAIL=nosamples 仅供负样本自测。"""
import json, math, os, re, sys, datetime
d = sys.argv[1]
opts = dict(zip(sys.argv[2::2], sys.argv[3::2]))
meta = {}
try:
    for ln in open(os.path.join(d, "meta.txt")):
        k, _, v = ln.strip().partition("=")
        meta[k] = v
except OSError:
    pass
scenario = opts.get("--scenario", meta.get("scenario", ""))
expect = int(opts.get("--expect", meta.get("expect", "1")))
subsystem = opts.get("--subsystem", meta.get("subsystem", "com.vela.stage.spike"))
t0 = float(meta.get("t0", 0)); tbg = float(meta.get("tbg", 0)); tres = float(meta.get("tres", 0))
samples_f = os.path.join(d, "samples.tsv"); net_f = os.path.join(d, "net.csv"); phases_f = os.path.join(d, "phases.txt")
stream_f = os.path.join(d, "app-stream.log"); show_f = os.path.join(d, "app-show.log")
force = os.environ.get("PROBE_FORCE_FAIL", "")

def parse_log(path):
    out = []
    try: lines = open(path, errors="replace").read().splitlines()
    except OSError: return out
    for ln in lines:
        m = re.match(r"^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d+)", ln)
        k = re.search(r"\[" + re.escape(subsystem) + r":(\w+)\]\s+(.*)$", ln)
        if not (m and k): continue
        t = datetime.datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S.%f").timestamp()
        out.append((t, k.group(1), k.group(2)))
    return out

show = parse_log(show_f); stream = parse_log(stream_f)
def has_ff(L): return sum(1 for _, _, x in L if x.startswith("first-frame"))
logs = show if has_ff(show) >= has_ff(stream) else stream
log_source = "show" if logs is show else "stream"
if not t0 and logs: t0 = min(x[0] for x in logs) - 1
logs = [x for x in logs if x[0] >= t0 - 3]

phases = {}
for ln in open(phases_f):
    p = ln.split()
    if len(p) == 2: phases.setdefault(p[0], float(p[1]))

rows = []
for ln in open(samples_f):
    p = ln.rstrip("\n").split("\t")
    if len(p) == 6: rows.append((p[0], *map(float, p[1:])))

def stats(sel):
    if not sel: return None
    cpu = sorted(r[1] for r in sel); n = len(cpu)
    k = max(1, math.ceil(0.95 * n))
    return {"n": n, "cpu_avg": sum(cpu) / n, "cpu_p95": cpu[k - 1], "cpu_max": cpu[-1],
            "rss_mb": sum(r[2] for r in sel) / n / 1024, "footprint_mb": sum(r[3] for r in sel) / n,
            "footprint_max_mb": max(r[3] for r in sel), "threads": sel[-1][4], "wakeups": sum(r[5] for r in sel) / n}

snaps = []
for line in open(net_f, errors="replace"):
    line = line.rstrip("\n")
    if line.startswith("#t "): snaps.append({"t": float(line[3:]), "c": {}})
    elif snaps and line and not line.startswith(",bytes_in"):
        r = line.rstrip(",").split(",")
        if len(r) >= 3 and r[1].isdigit() and "<->" in r[0]:
            snaps[-1]["c"][r[0]] = (int(r[1]), int(r[2]))

def loop(key):
    host = key.split("<->")[1].rsplit(".", 1)[0]
    return host.startswith("127.") or host == "::1"

def win_bytes(t_a, t_b):
    """窗口内每条连接：末值 - 窗口前最后一次的值（窗口内新出现的连接从 0 起算）；分非回环与回环。"""
    before = {}; last = {}
    for s in snaps:
        if s["t"] <= t_a + 1.0: before.update(s["c"])
        elif s["t"] <= t_b: last.update(s["c"])
    tin = tout = loopb = 0
    for key, (bi, bo) in last.items():
        b0 = before.get(key, (0, 0))
        di = bi - b0[0]
        if di < 0: continue
        if loop(key): loopb += di
        else: tin += di; tout += bo - b0[1]
    return tin, tout, loopb

def msg_find(prefix, after=0.0):
    return [(t, c, m) for t, c, m in logs if m.startswith(prefix) and t >= after]

sel_bw = {}; first_frame_s = {}; ff_abs = {}
for t, c, m in logs:
    if m.startswith("lane-start"):
        s = re.search(r"slot=(\d+).* bw=(\d+)", m)
        if s: sel_bw[int(s.group(1))] = int(s.group(2))
LANE_SLOT = {"primary": 0, "secondary": 1}
for t, c, m in msg_find("first-frame"):
    s = re.search(r"slot=(\d+) ms=(\d+)", m)  # Spike：first-frame slot=<n> ms=<毫秒>
    v = re.search(r"lane=(\w+) ([\d.]+)s", m)  # 正式 App：first-frame lane=<id> <秒>s size=WxH
    if s: slot, secs = int(s.group(1)), int(s.group(2)) / 1000.0
    elif v and v.group(1) in LANE_SLOT: slot, secs = LANE_SLOT[v.group(1)], float(v.group(2))
    else: continue
    if slot not in ff_abs:
        ff_abs[slot] = t
        first_frame_s[str(slot)] = round(secs, 2)
first_frame_since_launch_s = {str(k): round(v - t0, 2) for k, v in ff_abs.items()}

fg_start = phases.get("fg_start", t0); fg_end = phases.get("fg_end", fg_start)
fg_rows = [r for r in rows if r[0] == "fg"]
if force == "nosamples": fg_rows = []
fg = stats(fg_rows)
res = {"scenario": scenario, "log_source": log_source}
err = None
if not fg_rows: err = "no-samples"
if fg:
    win = max(fg_end - fg_start, 1)
    tin, tout, loopb = win_bytes(fg_start, fg_end)
    bw_sum = sum(sel_bw.get(i, 0) for i in range(expect))
    res.update({"cpu_avg": round(fg["cpu_avg"], 2), "cpu_p95": round(fg["cpu_p95"], 2), "cpu_max": round(fg["cpu_max"], 2),
                "footprint_mb": round(fg["footprint_mb"], 1), "footprint_max_mb": round(fg["footprint_max_mb"], 1),
                "rss_mb": round(fg["rss_mb"], 1), "wakeups": round(fg["wakeups"], 1), "threads": int(fg["threads"]),
                "net_in_mb_per_min": round(tin / 1048576.0 / (win / 60.0), 3),
                "net_out_kb_per_min": round(tout / 1024.0 / (win / 60.0), 1),
                "net_loopback_kb_per_min": round(loopb / 1024.0 / (win / 60.0), 1),
                "selected_bw_kbps": {str(k): round(v / 1000) for k, v in sorted(sel_bw.items())},
                "net_ratio": round((tin * 8 / win) / bw_sum, 3) if bw_sum else None,
                "elapsed_s": round(win, 1)})
res["first_frame_s"] = first_frame_s
res["first_frame_since_launch_s"] = first_frame_since_launch_s
if len(ff_abs) < expect and not err: err = "first-frame-missing"

# 重叠：lane-health 每 5 秒一条（currentTime 前进=在出画面）；按 5 秒桶统计窗口内所有 expect 路同时前进的占比
health = {}
for t, c, m in logs:
    if m.startswith("lane-health"):
        s_ = re.search(r"slot=(\d+).*advancing=(true|false)", m)
        if s_ and fg_start <= t <= fg_end: health.setdefault(int(s_.group(1)), []).append((t, s_.group(2) == "true"))
buckets = {}
for slot, lst in health.items():
    for t, adv in lst:
        buckets.setdefault(int((t - fg_start) // 5), {})[slot] = adv
total = max(1, int((fg_end - fg_start) // 5))
both = sum(1 for b in buckets.values() if all(b.get(i) for i in range(expect)))
res["overlap_ratio"] = round(min(1.0, both / total), 3)
res["health_ticks"] = {str(k): len(v) for k, v in sorted(health.items())}
res["stalled_ticks"] = {str(k): sum(1 for _, a in v if not a) for k, v in sorted(health.items())}
res["renew_failures"] = sum(1 for _, c, m in logs if m.startswith("renew failed"))
res["fetch_failed"] = sum(1 for _, c, m in logs if m.startswith("fetch-failed"))

dc = [m for _, c, m in logs if m.startswith("display-check")]
res["display_checks"] = len(dc)
def _wh(m, key):
    r = re.search(r"\b" + key + r"=(\d+)x(\d+)", m)
    return (int(r.group(1)), int(r.group(2))) if r else None
def _layer_ok(m):  # 播放层 bounds 与窗格（bounds=）每边相差 ≤1 点；旧格式没有 layer= 一律判否
    b, l = _wh(m, "bounds"), _wh(m, "layer")
    return bool(b and l and abs(b[0] - l[0]) <= 1 and abs(b[1] - l[1]) <= 1)
res["layer_matches_all"] = bool(dc) and all(_layer_ok(m) for m in dc)
res["uncropped_all"] = bool(dc) and res["layer_matches_all"] and all(
    "uncropped=true" in m and re.search(r"gravity=\S*ResizeAspect(?!Fill)", m, re.I) for m in dc)
res["display_samples"] = sorted(set(re.sub(r"lane=\S+ ", "", m) for m in dc))[:6]
src = [int(re.search(r"sources=(\d+)", m).group(1)) for _, c, m in logs if m.startswith("relay sources=")]
res["relay_sources_max"] = max(src) if src else 0
lr = [m for _, c, m in logs if m.startswith("lane-rooms")]  # 双窗两路房间是否重复（日志只记结论，不记房间名）
res["lane_rooms_checks"] = len(lr)
res["lane_rooms_dup"] = len([1 for m in lr if "distinct=false" in m])
res["item_failed"] = [m for _, c, m in logs if m.startswith("item-failed")][:5]
res["resolve_failed"] = sum(1 for _, c, m in logs if m.startswith("resolve-failed"))
if res["resolve_failed"] and not err and not ff_abs: err = "play-failed"

if scenario in ("background", "pip"):
    if not tres: tres = phases.get("resume", 0.0)
    bg_launch = phases.get("bg_launch", tbg); bgs = phases.get("bg_start", bg_launch); bge = phases.get("bg_end", bgs)
    bgr = [r for r in rows if r[0] == "bg"]; b = stats(bgr)
    if b:
        tin, tout, loopb = win_bytes(bgs, bge); w = max(bge - bgs, 1)
        res.update({"bg_cpu_avg": round(b["cpu_avg"], 2), "bg_cpu_max": round(b["cpu_max"], 2), "bg_footprint_mb": round(b["footprint_mb"], 1),
                    "bg_threads": int(b["threads"]), "bg_wakeups": round(b["wakeups"], 1),
                    "bg_net_in_kb_per_min": round(tin / 1024.0 / (w / 60.0), 2),
                    "bg_net_loopback_kb_per_min": round(loopb / 1024.0 / (w / 60.0), 2), "bg_samples": len(bgr)})
    else:
        err = err or "no-bg-samples"
    for key, pat in (("suspend_logged", "suspend"), ("relay_listener_stopped", "relay listener stopped"),
                     ("audio_deactivated", "audio deactivated"), ("pip_started", "pip started"),
                     ("pip_failed", "pip failed"), ("pip_will_start", "pip will-start")):
        res[key] = len([1 for t, c, m in logs if t >= bg_launch and m.startswith(pat)])
    res["suspend_lines"] = [m for _, c, m in msg_find("suspend", bg_launch)][:3]
    # 正式 App 的后台顺序（§3.2）：lane suspend → audio deactivated → relay listener stopped；回前台 epoch resume / lane resume
    for key, pat in (("lane_suspend", "lane suspend"), ("epoch_suspend", "epoch suspend"),
                     ("epoch_resume", "epoch resume"), ("lane_resume", "lane resume")):
        res[key] = len([1 for t, c, m in logs if t >= bg_launch and m.startswith(pat)])
    seq = [(t, m) for t, c, m in logs if t >= bg_launch and t < tres and
           (m.startswith(("lane suspend", "audio deactivated", "relay listener stopped", "epoch suspend")) or m.startswith("audio step"))]
    res["bg_sequence"] = [m for _, m in seq][:12]
    order = [next((t for t, m in seq if m.startswith(p)), None) for p in ("lane suspend", "audio deactivated", "relay listener stopped")]
    res["bg_order_ok"] = all(order) and order[0] <= order[1] <= order[2]
    res["pip_lines"] = [m for t, c, m in logs if c == "pip" and t >= bg_launch][:10]
    fetches = [(t, int(re.search(r"total=(\d+)", m).group(1))) for t, c, m in msg_find("relay fetches", bgs) if t <= bge]
    res["bg_relay_fetch_total_delta"] = (fetches[-1][1] - fetches[0][1]) if len(fetches) >= 2 else 0
    res["bg_relay_fetch_lines"] = len(fetches)
    rf = [t for t, c, m in msg_find("first-frame", tres)]
    res["resume_first_frame_s"] = round(rf[0] - tres, 2) if rf else None
    res["resume_lines"] = ([m for _, c, m in msg_find("resume after", tres - 1)] + [m for _, c, m in msg_find("epoch resume", tres - 1)]
                           + [m for _, c, m in msg_find("lane resume", tres - 1)])[:4]
    res["scene_lines"] = [m for _, c, m in msg_find("scenePhase", bg_launch)][:4]

res["audio_lines"] = [m for _, c, m in logs if c == "audio"][:12]
res["samples"] = len(fg_rows)
if err is None:
    for k in ["cpu_avg", "footprint_mb", "wakeups", "net_in_mb_per_min", "threads"]:
        v = res.get(k)
        if v is None or (isinstance(v, float) and math.isnan(v)): err = "missing-" + k; break
if err: res["error"] = err
print(json.dumps(res, ensure_ascii=False, separators=(",", ":")))
sys.exit({None: 0, "no-samples": 6, "no-bg-samples": 6, "first-frame-missing": 4, "play-failed": 4}.get(err, 6))
