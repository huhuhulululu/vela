#!/bin/bash
# perf-probe.sh 偏好备份与恢复的回归测试：用假 defaults / open / log 放在 PATH 前面，不碰真实偏好，不启动任何 App。
# 每个场景约 13 秒（探针等不到 App 启动才收尾）。兼容 bash 3.2。
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
T=$(mktemp -d /tmp/vela-fixp-prefs.XXXXXX)
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin" "$T/Fake.app/Contents"
cat >"$T/Fake.app/Contents/Info.plist" <<'PL'
<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0"><dict><key>CFBundleExecutable</key><string>VelaFixpFake</string></dict></plist>
PL
cat >"$T/bin/defaults" <<'SH'
#!/bin/bash
echo "$*" >>"$FAKE_CALLS"
case "$1" in
    read) [ "$FAKE_READ" = ok ] ;;
    export) [ "$FAKE_EXPORT" = ok ] && echo '<plist version="1.0"><dict/></plist>' >"$3" ;;
    import) [ "$FAKE_IMPORT" = ok ] ;;
    *) exit 0 ;;
esac
SH
printf '#!/bin/bash\nexit 0\n' >"$T/bin/open"
printf '#!/bin/bash\nsleep 30\n' >"$T/bin/log"
chmod +x "$T/bin/"*

fail=0
check() { if [ "$2" = 1 ]; then echo "ok   $1"; else echo "FAIL $1"; fail=1; fi; }
run() { # read export import
    : >"$T/calls"
    FAKE_CALLS="$T/calls" FAKE_READ=$1 FAKE_EXPORT=$2 FAKE_IMPORT=$3 TMPDIR="$T" PATH="$T/bin:$PATH" \
        bash "$ROOT/scripts/perf-probe.sh" --app "$T/Fake.app" >"$T/out" 2>"$T/err"
    RC=$?
}
has() { grep -q "$1" "$T/calls" && echo 1 || echo 0; }
none() { grep -q "$1" "$T/calls" && echo 0 || echo 1; }

# A 域存在但 export 失败：非 0 退出，不写、不删、不 import
run ok fail ok
check "A rc!=0" $([ $RC -ne 0 ] && echo 1 || echo 0)
check "A 未写偏好" $(none '^write')
check "A 未删偏好" $(none '^delete')
check "A 未 import" $(none '^import')

# B 域不存在：改写后收尾整域删除，不 import
run fail fail ok
check "B 写过偏好" $(has '^write')
check "B 整域删除" $(has '^delete com.vela.stage$')
check "B 未 import" $(none '^import')

# C 域存在、import 失败：不整域删除，备份保留，非 0 退出并报错
run ok ok fail
check "C rc!=0" $([ $RC -ne 0 ] && echo 1 || echo 0)
check "C 未整域删除" $(none '^delete com.vela.stage$')
check "C 备份保留" $([ -n "$(ls "$T"/vela-perf-defaults-backup.*.plist 2>/dev/null)" ] && echo 1 || echo 0)
check "C 报错信息" $(grep -q '恢复失败' "$T/err" && echo 1 || echo 0)

# D 正常恢复：import 成功，不整域删除
rm -f "$T"/vela-perf-defaults-backup.*.plist
run ok ok ok
check "D import" $(has '^import')
check "D 未整域删除" $(none '^delete com.vela.stage$')
check "D 无残留备份" $([ -z "$(ls "$T"/vela-perf-defaults-backup.*.plist 2>/dev/null)" ] && echo 1 || echo 0)
exit $fail
