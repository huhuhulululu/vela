#!/usr/bin/env python3
"""Vela G-mac 实跑门判定（仅标准库，运行：python3 -I scripts/perf_gate.py ...）。

用法：
  perf_gate.py <基线.json> <结果.json> [--scenario play|idle]
  perf_gate.py --validate <结果.json>        仅做结果完整性自检（perf-probe.sh 收尾时调用）

基线 play 段须有 cpu_avg、footprint_avg_mb、app_size_mb，stream_mode 必须为 fixed（固定测试流，见 docs/PERF.md），可带 startup_runs（各轮起播）与 startup_median；
基线文件可以是单场景的扁平对象，也可以是 {"play": {...}, "idle": {...}}。结果的 stream_mode 须与基线相同（都是 fixed），否则拒绝比较。
play 的 CPU 直接比较主进程 cpu_avg（<= 基线 x 1.15 + 0.3 个百分点）；VTDecoderXPCService 是独立进程，只打印，不参与加减。
退出码：0 合格；1 不合格；2 用法或文件错误。缺字段、NaN、skipped、samples=0、起播 <= 0 一律不合格。
"""
import json
import math
import sys

PLAY_FIELDS = ["cpu_avg", "vtdecoder_cpu_avg", "footprint_avg_mb", "wakeups_avg", "app_size_mb"]
IDLE_FIELDS = ["cpu_avg", "wakeups_avg", "net_in_mb_per_min", "net_out_kb_per_min"]
COMMON_FIELDS = ["runtime_issues", "samples", "startup_to_play_s", "invalid_samples", "interval_s", "duration_s", "elapsed_s"]
MIN_VALID_SAMPLES = 5
MAX_INVALID_RATIO = 0.10


def num(d, key):
    """取有限数值；缺失、非数值、NaN、inf 返回 None。"""
    v = d.get(key)
    if isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v):
        return None
    return float(v)


def load(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError) as e:
        print("读取失败 %s: %s" % (path, e), file=sys.stderr)
        sys.exit(2)


def validate(res, scenario):
    """返回结果自身的不合格原因列表（与基线无关）。"""
    bad = []
    if not isinstance(res, dict):
        return ["结果不是 JSON 对象"]
    if "skipped" in res:
        bad.append("结果带 skipped: %s" % res.get("skipped"))
    if "error" in res:
        bad.append("结果带 error: %s" % res.get("error"))
    for k in COMMON_FIELDS + (IDLE_FIELDS if scenario == "idle" else PLAY_FIELDS):
        if num(res, k) is None:
            bad.append("指标缺失或非有限数值: %s" % k)
    # 2026-11-17 编排者裁决：wakeups 只作 idle 门；play 的 wakeups 随码流在 0 到 30/s 间波动，只打印。
    # cpu 大于 0 而 wakeups 恰为 0 仅在 stderr 警告（可能是 IDLEW 被冻结，不拒绝）
    if (scenario == "play" and num(res, "cpu_avg") and num(res, "wakeups_avg") is not None
            and res["cpu_avg"] > 0 and res["wakeups_avg"] == 0):
        print("警告: play 场景 cpu_avg > 0 但 wakeups_avg = 0（IDLEW 可能被冻结，或码流静止），wakeups 不作 play 门，仅提示", file=sys.stderr)
    if num(res, "samples") is not None and res["samples"] <= 0:
        bad.append("samples = %s" % res["samples"])
    # 采样完整性：失败样本只能记无效，不得补 0 充数；有效样本数须与时长对得上
    if scenario == "play":
        for k in ("footprint_avg_mb", "app_size_mb"):
            if num(res, k) is not None and res[k] <= 0:
                bad.append("%s = %s（采样失败补零？）" % (k, res[k]))
    if all(num(res, k) is not None for k in ("samples", "invalid_samples", "interval_s", "duration_s", "elapsed_s")):
        n, inv = res["samples"], res["invalid_samples"]
        if n < MIN_VALID_SAMPLES:
            bad.append("有效样本 %d < %d" % (n, MIN_VALID_SAMPLES))
        if n + inv > 0 and inv / (n + inv) > MAX_INVALID_RATIO:
            bad.append("无效样本占比 %.0f%% > 10%%（有效 %d、无效 %d）" % (100.0 * inv / (n + inv), n, inv))
        if res["interval_s"] > 0:
            if res["elapsed_s"] < res["duration_s"] * 0.95:
                bad.append("实际采样时长 %.1f s 短于要求 %.1f s" % (res["elapsed_s"], res["duration_s"]))
            if n + inv < 0.8 * res["elapsed_s"] / res["interval_s"]:
                bad.append("样本数 %d 与时长 %.1f s / 间隔 %.1f s 不符" % (n + inv, res["elapsed_s"], res["interval_s"]))
    if res.get("stream_mode") not in ("fixed", "live"):
        bad.append("stream_mode 缺失或非法: %s" % res.get("stream_mode"))
    if num(res, "startup_to_play_s") is not None and res["startup_to_play_s"] <= 0:
        bad.append("起播时间 %s 不大于 0" % res["startup_to_play_s"])
    return bad


def gate(base, res, scenario):
    rows = []  # (名称, 基线, 结果, 阈值描述, 是否通过)

    def check(name, b, r, desc, ok):
        rows.append((name, b, r, desc, ok))

    check("runtime_issues", None, res["runtime_issues"], "= 0", res["runtime_issues"] == 0)
    if scenario == "idle":
        check("cpu_avg", None, res["cpu_avg"], "<= 0.1%", res["cpu_avg"] <= 0.1)
        check("wakeups_avg", None, res["wakeups_avg"], "<= 2/s", res["wakeups_avg"] <= 2)
        check("net_in_mb_per_min", None, res["net_in_mb_per_min"], "= 0", res["net_in_mb_per_min"] == 0)
        check("net_out_kb_per_min", None, res["net_out_kb_per_min"], "= 0", res["net_out_kb_per_min"] == 0)
        return rows
    print("stream_mode %s  码率 %s kbps" % (res.get("stream_mode"), res.get("stream_bitrate_kbps")))
    limits = [
        ("footprint_avg_mb", lambda b: b + 5, "基线 + 5 MB"),
        ("app_size_mb", lambda b: b + 0.3, "基线 + 0.3 MB"),
    ]
    # CPU：直接比较主进程 cpu_avg（ADR-005 撤销 ADR-003 的「扣除硬解」；VTDecoderXPCService 是独立进程，
    # 用它的负载去抵消主进程会掩盖回退）。固定测试流下码率恒定，主进程 CPU 可比。
    print("wakeups_avg(仅打印) %s" % res["wakeups_avg"])
    print("vtdecoder_cpu_avg(仅打印，独立进程) %.2f" % res["vtdecoder_cpu_avg"])
    check("cpu_avg", base["cpu_avg"], res["cpu_avg"], "<= 基线 x 1.15 + 0.3", res["cpu_avg"] <= base["cpu_avg"] * 1.15 + 0.3 + 1e-9)
    for k, f, desc in limits:
        b = base[k]
        check(k, b, res[k], "<= " + desc, res[k] <= f(b) + 1e-9)
    # 起播：2026-11-17 编排者裁决采用方案 A，> 0 且 <= 基线中位数 + 1.0 s（单轮波动约 1.1 s，不用单值 + 0.5 s）
    if num(base, "startup_median") is not None:
        sb = base["startup_median"]
    else:
        sb = base["startup_to_play_s"]
        print("警告: 基线缺 startup_median，退回单值 startup_to_play_s（单轮波动大，请补 startup_runs 与 startup_median）")
    check("startup_to_play_s", sb, res["startup_to_play_s"], "> 0 且 <= 基线中位数 + 1.0 s",
          0 < res["startup_to_play_s"] <= sb + 1.0 + 1e-9)
    return rows


def main(argv):
    if len(argv) == 3 and argv[1] == "--validate":
        res = load(argv[2])
        scenario = res.get("scenario", "play") if isinstance(res, dict) else "play"
        if scenario not in ("play", "idle"):
            scenario = "play"
        bad = validate(res, scenario)
        for m in bad:
            print("不合格: " + m, file=sys.stderr)
        return 1 if bad else 0

    args = argv[1:]
    scenario = None
    if "--scenario" in args:
        i = args.index("--scenario")
        if i + 1 >= len(args):
            print("--scenario 需要取值 play|idle", file=sys.stderr)
            return 2
        scenario = args[i + 1]
        del args[i:i + 2]
    if len(args) != 2 or scenario not in (None, "play", "idle"):
        print(__doc__, file=sys.stderr)
        return 2
    base_all, res = load(args[0]), load(args[1])
    if scenario is None:
        scenario = res.get("scenario", "play") if isinstance(res, dict) else "play"
        if scenario not in ("play", "idle"):
            scenario = "play"

    bad = validate(res, scenario)
    if isinstance(res, dict) and res.get("scenario") not in (None, scenario):
        bad.append("结果场景 %s 与 --scenario %s 不符" % (res.get("scenario"), scenario))
    base = None
    if scenario == "play":
        base = base_all.get("play", base_all) if isinstance(base_all, dict) else None
        if not isinstance(base, dict):
            bad.append("基线不是 JSON 对象")
        else:
            for k in ("cpu_avg", "footprint_avg_mb", "app_size_mb"):
                if num(base, k) is None or base[k] <= 0:
                    bad.append("基线缺失、非有限数值或为 0: %s" % k)
            if base.get("stream_mode") != "fixed":
                bad.append("基线 stream_mode 须为 fixed（当前 %s）：活直播码率每轮不同，不可作回归基线" % base.get("stream_mode"))
            elif isinstance(res, dict) and res.get("stream_mode") != base.get("stream_mode"):
                bad.append("模式不同不可比较：基线 %s，结果 %s" % (base.get("stream_mode"), res.get("stream_mode")))
            if num(base, "startup_median") is None and num(base, "startup_to_play_s") is None:
                bad.append("基线缺失 startup_median 与 startup_to_play_s")
            for k in ("startup_median",):
                if k in base and num(base, k) is None:
                    bad.append("基线缺失或非有限数值: %s" % k)
    if bad:
        for m in bad:
            print("不合格: " + m)
        print("结果: FAIL")
        return 1

    rows = gate(base, res, scenario)
    ok_all = True
    print("场景: %s" % scenario)
    for name, b, r, desc, ok in rows:
        ok_all = ok_all and ok
        print("%-5s %-20s 结果 %-10s 基线 %-10s 要求 %s" % (
            "PASS" if ok else "FAIL", name, r, "-" if b is None else b, desc))
    print("结果: %s" % ("PASS" if ok_all else "FAIL"))
    return 0 if ok_all else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
