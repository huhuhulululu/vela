import json
import re
import sys


def app_cpu_percent(cpuinfo, package="vela.app"):
    pattern = re.compile(
        r"([0-9]+(?:\.[0-9]+)?)%\s+[0-9]+/" + re.escape(package) + r":"
    )
    for line in cpuinfo.splitlines():
        found = pattern.search(line)
        if found:
            return float(found.group(1))
    return None


def stat_ticks(stat_text):
    end = stat_text.rfind(")")
    if end < 0:
        return None
    fields = stat_text[end + 2 :].split()
    if len(fields) <= 12:
        return None
    try:
        return int(fields[11]) + int(fields[12])
    except ValueError:
        return None


def monotonic_prefix(rows):
    prefix = []
    for row in rows:
        ticks = row.get("cpu_ticks")
        stamp = row.get("t")
        if ticks is None or stamp is None:
            break
        if prefix and ticks < prefix[-1]["cpu_ticks"]:
            break
        prefix.append(row)
    return prefix


def tick_summary(rows, hz=100):
    prefix = monotonic_prefix(rows)
    if len(prefix) < 2:
        return None, None, None
    delta = prefix[-1]["cpu_ticks"] - prefix[0]["cpu_ticks"]
    seconds = prefix[-1]["t"] - prefix[0]["t"]
    ratio = None
    if seconds > 0 and hz > 0:
        ratio = delta / float(seconds * hz)
    return delta, seconds, ratio


def check_document(doc):
    count = int(doc.get("sample_count") or 0)
    if count < 3:
        raise SystemExit("资源采样只有 %d 条，播放期间应每 5 秒有一行" % count)
    rows = doc.get("samples") or []
    pss = [row.get("pss_kb") for row in rows if row.get("pss_kb")]
    if not pss:
        raise SystemExit("资源 JSON 里没有 TOTAL PSS")
    has_cpu = any(
        row.get("cpu_percent") is not None
        or row.get("cpuinfo_total_percent") is not None
        or row.get("cpu_ticks") is not None
        for row in rows
    )
    if not has_cpu:
        raise SystemExit("资源 JSON 里没有 cpuinfo / CPU tick")
    print("e2e-a-resources samples=%d pss_kb=%s..%s" % (count, min(pss), max(pss)))
    delta, seconds, ratio = tick_summary(rows, hz=100)
    if delta is None:
        print("e2e-a-resources tick_delta=n/a wall_s=n/a single_core=n/a hz=100")
        return
    core = "n/a" if ratio is None else "%.1f%%" % (ratio * 100.0)
    print(
        "e2e-a-resources tick_delta=%d wall_s=%d single_core=%s hz=100"
        % (delta, seconds, core)
    )


def main(argv):
    if len(argv) != 2:
        raise SystemExit("用法: resource_sample.py <e2e-a-resources.json>")
    with open(argv[1], "r", encoding="utf-8") as handle:
        doc = json.load(handle)
    check_document(doc)


if __name__ == "__main__":
    main(sys.argv)
