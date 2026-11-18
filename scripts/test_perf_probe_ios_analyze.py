#!/usr/bin/env python3
"""perf-probe-ios-analyze.py 的三路核对负样本（ios-triple 修订 v2 M11）。仅标准库，运行：python3 -I scripts/test_perf_probe_ios_analyze.py

每个用例在临时目录里造一份探针现场（采样、阶段、日志、服务端统计），跑分析器并核对退出码：
正样本必须通过；缺第三路首帧、第三路重复、后台仍留中继源、回桌面 5 秒后服务端仍有字节、画中画期间不止一路联网、
服务端统计为空（空成功）都必须判失败。
"""
import datetime
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ANALYZER = os.path.join(HERE, "perf-probe-ios-analyze.py")
T0 = 1_900_000_000.0
SUB = "com.vela.stage"


def stamp(t):
    return datetime.datetime.fromtimestamp(t).strftime("%Y-%m-%d %H:%M:%S.%f")


def line(t, category, message):
    return "%s I  VelaPhone[1:2] [%s:%s] %s" % (stamp(t), SUB, category, message)


def build(root, *, lanes=3, first_frames=("primary", "secondary", "tertiary"), distinct=True,
          bg_sources_left=0, pip=False, bg_bytes_after5=0, bg_playlist_lanes=None, stats=True):
    fg_start, fg_end = T0 + 10, T0 + 40
    bg_launch, bg_start, bg_end, resume = T0 + 41, T0 + 46, T0 + 66, T0 + 67
    with open(os.path.join(root, "meta.txt"), "w") as f:
        f.write("scenario=background\nexpect=%d\nt0=%s\ntbg=%s\ntres=%s\nsubsystem=%s\n" % (lanes, T0, bg_launch, resume, SUB))
    with open(os.path.join(root, "phases.txt"), "w") as f:
        for name, t in (("fg_start", fg_start), ("fg_end", fg_end), ("bg_launch", bg_launch), ("bg_start", bg_start),
                        ("bg_end", bg_end), ("resume", resume), ("end", resume + 25)):
            f.write("%s %.3f\n" % (name, t))
    with open(os.path.join(root, "samples.tsv"), "w") as f:
        for _ in range(6):
            f.write("fg\t30.0\t120000\t110.0\t30\t5.0\n")
        for _ in range(4):
            f.write("bg\t0.2\t60000\t60.0\t8\t0.5\n")
    open(os.path.join(root, "net.csv"), "w").close()
    logs = []
    for i, lane in enumerate(first_frames):
        logs.append(line(T0 + 2 + i * 0.1, "playback", "first-frame lane=%s 0.5s size=1920x1080" % lane))
    logs.append(line(T0 + 3, "lanes", "lane-rooms distinct=%s lanes=%d" % ("true" if distinct else "false", lanes)))
    for n in range(1, lanes + 1):
        logs.append(line(T0 + 2 + n * 0.1, "relay", "relay sources=%d reason=register" % n))
    if pip:
        logs.append(line(bg_launch + 0.5, "pip", "pip started"))
    remaining = lanes
    for i in range(lanes - (1 if pip else 0)):
        remaining -= 1
        logs.append(line(bg_launch + 0.2 + i * 0.01, "relay", "relay sources=%d reason=deactivate" % remaining))
    if bg_sources_left:
        logs.append(line(bg_launch + 1, "relay", "relay sources=%d reason=register" % bg_sources_left))
    logs.append(line(bg_launch + 0.3, "lifecycle", "lane suspend lane=secondary"))
    logs.append(line(resume + 1, "playback", "first-frame lane=primary 0.4s size=1920x1080"))
    text = "\n".join(sorted(logs)) + "\n"
    for name in ("app-show.log", "app-stream.log"):
        with open(os.path.join(root, name), "w") as f:
            f.write(text)
    if stats:
        rows = []
        t = fg_start
        while t < fg_end:  # 每路每 2 秒一个视频列表、一个分片
            for _ in range(lanes):
                rows.append((t, 900, "media_1.m3u8"))
                rows.append((t + 0.1, 300_000, "seg_1_001.ts"))
            t += 2
        if pip or bg_playlist_lanes:
            t = bg_start
            while t < bg_end:
                for _ in range(bg_playlist_lanes or 1):
                    rows.append((t, 900, "media_0.m3u8"))
                    rows.append((t + 0.1, 700_000, "seg_0_001.ts"))
                t += 2
        if bg_bytes_after5:
            rows.append((bg_launch + 8, bg_bytes_after5, "seg_1_002.ts"))
        with open(os.path.join(root, "stream-stats.txt"), "w") as f:
            for r in sorted(rows):
                f.write("%.3f %d %s\n" % r)
    else:
        open(os.path.join(root, "stream-stats.txt"), "w").close()


def run(case, want, reason=None, **kw):
    root = tempfile.mkdtemp(prefix="vela-analyze-")
    try:
        build(root, **kw)
        out = subprocess.run([sys.executable, "-I", ANALYZER, root], capture_output=True, text=True)
        ok = out.returncode == want and (reason is None or '"error":"%s"' % reason in out.stdout)
        if want == 0:
            ok = ok and '"error"' not in out.stdout
        print("%s %s rc=%d 期望 %d %s" % ("PASS" if ok else "FAIL", case, out.returncode, want, out.stdout.strip()[:160]))
        return ok
    finally:
        shutil.rmtree(root, ignore_errors=True)


def main():
    results = [
        run("三路后台正样本", 0),
        run("画中画只留焦点路正样本", 0, pip=True),
        run("缺第三路首帧", 4, "first-frame-missing", first_frames=("primary", "secondary")),
        run("第三路与别的路重复", 7, "lane-rooms-dup", distinct=False),
        run("后台仍留中继源", 7, "bg-relay-sources", bg_sources_left=1),
        run("回桌面 5 秒后服务端仍有字节", 7, "bg-server-bytes", bg_bytes_after5=188_000),
        run("画中画期间不止一路联网", 7, "bg-pip-lanes", pip=True, bg_playlist_lanes=2),
        run("服务端统计为空不能当没联网", 7, "server-stats-empty", stats=False),
    ]
    if all(results):
        print("全部用例符合预期")
        return 0
    print("%d 项未按预期" % results.count(False))
    return 1


if __name__ == "__main__":
    sys.exit(main())
