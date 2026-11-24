#!/bin/bash
# G-mac 门的负样本证明：以下情形必须被拒绝，全部被正确拒绝则退出码 0。兼容 bash 3.2。
#   1. perf-probe.sh 起播超时（PLAY_TIMEOUT=1，用假 App / 假 open / 假 log，不碰真实偏好）-> 非 0
#   2. perf_gate.py 对删掉 samples 的结果 -> 不合格
#   3. perf_gate.py 对 skipped、startup=-1、NaN、缺指标、超阈值的结果 -> 不合格；对合格样本 -> 合格（防止空失败）
#   4. 补零样本（footprint=0、app_size=0）、无效样本过多、样本数与时长不符、模式不符（live 基线 / live 结果）、旧格式结果文件 -> 不合格
#   5. perf-probe.sh 提前退出时删除旧结果文件，不留上一轮 JSON
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
T=$(mktemp -d /tmp/vela-perfgate-test.XXXXXX)
trap 'rm -rf "$T"' EXIT
fail=0
check() { if [ "$2" = 1 ]; then echo "ok   $1"; else echo "FAIL $1"; fail=1; fi; }
gate() { python3 -I "$ROOT/scripts/perf_gate.py" "$@" >"$T/gate.out" 2>&1; }

# ---- 1. 起播超时 ----
BIN=VelaGateFake$$
mkdir -p "$T/bin" "$T/Fake.app/Contents"
cat >"$T/Fake.app/Contents/Info.plist" <<PL
<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0"><dict><key>CFBundleExecutable</key><string>${BIN}</string></dict></plist>
PL
# 假 open 起一个真 sleep（拷贝系统二进制改名会在 macOS 上被杀后卡在 UE 态），假 pgrep 只对它作答
cat >"$T/bin/open" <<SH
#!/bin/bash
sleep 60 >/dev/null 2>&1 &
echo \$! >"$T/fake.pid"
SH
cat >"$T/bin/pgrep" <<SH
#!/bin/bash
[ -s "$T/fake.pid" ] && cat "$T/fake.pid"
SH
printf '#!/bin/bash\nexit 1\n' >"$T/bin/defaults"      # 域不存在：探针不备份也不恢复
printf '#!/bin/bash\nsleep 30\n' >"$T/bin/log"
chmod +x "$T/bin/"*
export PERF_RESULT_JSON="$T/result.json"
echo '{"stale":true}' >"$PERF_RESULT_JSON"
bash "$ROOT/scripts/perf-probe.sh" --app "$T/NoSuch.app" >/dev/null 2>&1
check "提前退出（App 不存在）时旧结果文件被删" "$([ ! -e "$PERF_RESULT_JSON" ] && echo 1 || echo 0)"
PATH="$T/bin:$PATH" PLAY_TIMEOUT=1 bash "$ROOT/scripts/perf-probe.sh" --app "$T/Fake.app" --duration 5 >"$T/probe.out" 2>"$T/probe.err"
rc=$?
check "PLAY_TIMEOUT=1 起播超时非 0 退出 (rc=${rc})" "$([ "$rc" -ne 0 ] && grep -q 'startup_to_play_s=-1' "$T/probe.err" && echo 1 || echo 0)"
# 只按记下的 pid 结束本测试自起的 sleep
FP=$(cat "$T/fake.pid" 2>/dev/null)
[ -n "$FP" ] && kill "$FP" 2>/dev/null

# ---- 2/3. perf_gate.py ----
cat >"$T/base.json" <<'J'
{"play":{"stream_mode":"fixed","cpu_avg":4.0,"footprint_avg_mb":300,"wakeups_avg":30,"app_size_mb":10,"startup_to_play_s":4.0}}
J
cat >"$T/good.json" <<'J'
{"scenario":"play","stream_mode":"fixed","stream_bitrate_kbps":3494,"cpu_avg":4.2,"vtdecoder_cpu_avg":3.4,"footprint_avg_mb":302,"wakeups_avg":31,"app_size_mb":10.1,"startup_to_play_s":4.2,"runtime_issues":0,"samples":20,"invalid_samples":0,"interval_s":5,"duration_s":100,"elapsed_s":101.0}
J
gate "$T/base.json" "$T/good.json"; check "合格样本判合格" "$([ $? -eq 0 ] && echo 1 || echo 0)"
python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1])); del d["samples"]; json.dump(d,open(sys.argv[2],"w"))' "$T/good.json" "$T/nosamples.json"
gate "$T/base.json" "$T/nosamples.json"; check "删掉 samples 判不合格" "$([ $? -eq 1 ] && echo 1 || echo 0)"
mut() { # 名称 python 表达式（修改 d）
    python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1])); exec(sys.argv[2]); open(sys.argv[3],"w").write(json.dumps(d))' "$T/good.json" "$2" "$T/m.json"
    case "$2" in *NaN*) sed 's/"cpu_avg": *"NaN"/"cpu_avg": NaN/' "$T/m.json" >"$T/m.tmp" && mv "$T/m.tmp" "$T/m.json" ;; esac
    gate "$T/base.json" "$T/m.json"; check "$1 判不合格" "$([ $? -eq 1 ] && echo 1 || echo 0)"
}
mut "samples=0" 'd["samples"]=0'
mut "startup=-1" 'd["startup_to_play_s"]=-1'
mut "带 skipped" 'd["skipped"]="x"'
mut "缺 cpu_avg" 'del d["cpu_avg"]'
mut "cpu_avg=NaN" 'd["cpu_avg"]="NaN"'
# CPU 口径：直接比较主进程 cpu_avg <= 基线 x 1.15 + 0.3（基线 4.0 -> 上限 4.9）；vtdecoder 只打印
mut_ok() { # 名称 python 表达式；期望合格
    python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1])); exec(sys.argv[2]); open(sys.argv[3],"w").write(json.dumps(d))' "$T/good.json" "$2" "$T/m.json"
    gate "$T/base.json" "$T/m.json"; check "$1 判合格" "$([ $? -eq 0 ] && echo 1 || echo 0)"
}
mut_ok "主进程 cpu_avg = 4.9 合格" 'd["cpu_avg"]=4.9'
mut "主进程 cpu_avg = 5.0 超限" 'd["cpu_avg"]=5.0'
mut "vtdecoder 很低也不能抵消主进程回退（5.5 - 0 不扣除）" 'd["cpu_avg"]=5.5; d["vtdecoder_cpu_avg"]=0.0'
mut_ok "vtdecoder 高不影响主进程判定" 'd["vtdecoder_cpu_avg"]=9.0'
mut "缺 vtdecoder_cpu_avg" 'del d["vtdecoder_cpu_avg"]'
# 补零与采样完整性
mut "footprint=0（补零）" 'd["footprint_avg_mb"]=0'
mut "app_size=0" 'd["app_size_mb"]=0'
mut "有效样本 4 < 5" 'd["samples"]=4; d["duration_s"]=20; d["elapsed_s"]=20.5'
mut "无效样本占比 > 10%" 'd["invalid_samples"]=4'
mut_ok "无效样本占比 = 10% 仍可" 'd["samples"]=18; d["invalid_samples"]=2'
mut "样本数与时长不符（100 秒只有 10 个）" 'd["samples"]=10'
mut "实际时长短于要求" 'd["elapsed_s"]=60'
mut "缺 stream_mode" 'del d["stream_mode"]'
mut "结果为 live 模式（基线 fixed）" 'd["stream_mode"]="live"'
mut "旧格式结果文件（无 invalid_samples 等）" 'del d["invalid_samples"]; del d["interval_s"]; del d["duration_s"]; del d["elapsed_s"]; del d["stream_mode"]'
python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1])); d["play"]["stream_mode"]="live"; open(sys.argv[2],"w").write(json.dumps(d))' "$T/base.json" "$T/base-live.json"
gate "$T/base-live.json" "$T/good.json"; check "基线为 live 模式拒绝比较" "$([ $? -eq 1 ] && echo 1 || echo 0)"
python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1])); del d["play"]["stream_mode"]; open(sys.argv[2],"w").write(json.dumps(d))' "$T/base.json" "$T/base-old.json"
gate "$T/base-old.json" "$T/good.json"; check "旧基线（无 stream_mode）拒绝" "$([ $? -eq 1 ] && echo 1 || echo 0)"
mut "footprint 超基线 5MB" 'd["footprint_avg_mb"]=306'
mut_ok "play wakeups 超阈值仍通过（wakeups 只作 idle 门）" 'd["wakeups_avg"]=37'
mut_ok "play wakeups=0 仅警告仍通过" 'd["wakeups_avg"]=0'
mut "app_size 超基线 0.3MB" 'd["app_size_mb"]=10.4'
# 起播方案 A：基线中位数 + 1.0 s
cat >"$T/base-med.json" <<'J'
{"play":{"stream_mode":"fixed","cpu_avg":4.0,"footprint_avg_mb":300,"wakeups_avg":30,"app_size_mb":10,"startup_to_play_s":4.0,"startup_runs":[1.0,1.2,1.5,2.0,2.4],"startup_median":1.5}}
J
python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1])); d["startup_to_play_s"]=float(sys.argv[3]); open(sys.argv[2],"w").write(json.dumps(d))' "$T/good.json" "$T/s-ok.json" 2.4
gate "$T/base-med.json" "$T/s-ok.json"; check "起播 = 中位数 + 0.9 通过" "$([ $? -eq 0 ] && echo 1 || echo 0)"
python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1])); d["startup_to_play_s"]=float(sys.argv[3]); open(sys.argv[2],"w").write(json.dumps(d))' "$T/good.json" "$T/s-bad.json" 2.6
gate "$T/base-med.json" "$T/s-bad.json"; check "起播 = 中位数 + 1.1 拒绝" "$([ $? -eq 1 ] && echo 1 || echo 0)"
gate "$T/base.json" "$T/good.json"; check "基线缺 startup_median 时退回单值并警告" "$(grep -q '警告' "$T/gate.out" && echo 1 || echo 0)"
mut "起播超单值基线 + 1.0s（无中位数）" 'd["startup_to_play_s"]=5.1'
mut "runtime_issues=1" 'd["runtime_issues"]=1'
cat >"$T/idle.json" <<'J'
{"scenario":"idle","cpu_avg":0.02,"wakeups_avg":1.5,"net_in_mb_per_min":0,"net_out_kb_per_min":0,"runtime_issues":0,"samples":20,"startup_to_play_s":4.0,"stream_mode":"fixed","invalid_samples":0,"interval_s":5,"duration_s":100,"elapsed_s":101.0}
J
gate "$T/base.json" "$T/idle.json"; check "idle 合格样本判合格" "$([ $? -eq 0 ] && echo 1 || echo 0)"
sed 's/"net_in_mb_per_min":0/"net_in_mb_per_min":0.5/' "$T/idle.json" >"$T/idle-bad.json"
sed 's/"wakeups_avg":1.5/"wakeups_avg":2.5/' "$T/idle.json" >"$T/idle-wk.json"
gate "$T/base.json" "$T/idle-wk.json"; check "idle wakeups 超 2/s 判不合格" "$([ $? -eq 1 ] && echo 1 || echo 0)"
gate "$T/base.json" "$T/idle-bad.json"; check "idle 有网络判不合格" "$([ $? -eq 1 ] && echo 1 || echo 0)"

[ "$fail" = 0 ] && echo "全部负样本被正确拒绝" || echo "存在未被拒绝的负样本"
exit "$fail"
