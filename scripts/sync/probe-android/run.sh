#!/bin/bash
# 在已连接的 Android 设备上跑 P-256 探针（minSdk 26 口径），并让 Go 验安卓签名。
# 兼容 bash 3.2。设备未出现时，若设置了 ADB_CONNECT 会先 connect。
set -eu

here=$(CDPATH= cd "$(dirname "$0")" && pwd)
root=$(CDPATH= cd "$here/../../.." && pwd)
vec="$root/scripts/sync/vectors"
android_home="${ANDROID_HOME:-/opt/android-sdk}"
platform="$android_home/platforms/android-34/android.jar"
d8="$android_home/build-tools/34.0.0/d8"
work="$here/build"
mkdir -p "$work"

if [ ! -f "$work/probe-vectors.json" ] || [ "${PROBE_REGEN:-0}" = "1" ]; then
  (cd "$vec" && go run . -probe "$work/probe-vectors.json" >/dev/null)
fi

javac --release 17 -d "$work/host" "$here/P256.java"
java -cp "$work/host" P256

javac --release 17 -classpath "$platform" -d "$work/classes" "$here/P256.java" "$here/Probe.java"
"$d8" --min-api 26 --lib "$platform" --output "$work" "$work/classes"/P256.class "$work/classes"/P256\$Jac.class "$work/classes"/Probe.class

if [ -n "${ADB_CONNECT:-}" ]; then
  adb connect "$ADB_CONNECT" >/dev/null 2>&1 || true
fi
serial="${ADB_SERIAL:-}"
if [ -z "$serial" ]; then
  serial=$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')
fi
if [ -z "$serial" ]; then
  echo "PROBE FAIL no adb device" >&2
  exit 1
fi

adb -s "$serial" push "$work/classes.dex" /data/local/tmp/vela-probe.dex >/dev/null
adb -s "$serial" push "$work/probe-vectors.json" /data/local/tmp/vela-probe.json >/dev/null
log="$work/device.log"
adb -s "$serial" shell CLASSPATH=/data/local/tmp/vela-probe.dex app_process / Probe /data/local/tmp/vela-probe.json >"$log"
cat "$log"

go_log="$work/go-verify.log"
(cd "$vec" && go run . -verify-android "$log") | tee "$go_log"

field() {
  awk -v k="$1" '$1==k {print $2; exit}' "$2"
}

import_r=$(field IMPORT "$log" || true)
sign_r=$(field SIGN "$log" || true)
pubkey_r=$(field PUBKEY "$log" || true)
go_on_and=$(field VERIFY_GO_ON_ANDROID "$log" || true)
check_on_and=$(field PUBCHECK_GO_ON_ANDROID "$log" || true)
and_sign=$(awk '$1=="VERIFY_ANDROID_ON_GO" && $2=="sign" {print $3; exit}' "$go_log" || true)
and_check=$(awk '$1=="VERIFY_ANDROID_ON_GO" && $2=="pubcheck" {print $3; exit}' "$go_log" || true)

mutual=FAIL
if [ "$go_on_and" = "PASS" ] && [ "$and_sign" = "PASS" ]; then
  mutual=PASS
fi
pubcheck=FAIL
if [ "$check_on_and" = "PASS" ] && [ "$and_check" = "PASS" ]; then
  pubcheck=PASS
fi

level="禁用"
if [ "$import_r" = "PASS" ] && [ "$sign_r" = "PASS" ] && [ "$mutual" = "PASS" ] && [ "$pubcheck" = "PASS" ] && [ "$pubkey_r" = "PASS" ]; then
  level="完整"
elif [ "$import_r" = "PASS" ] && [ "$sign_r" = "PASS" ] && [ "$mutual" = "PASS" ] && [ "$pubcheck" = "PASS" ] && [ "$pubkey_r" != "PASS" ]; then
  level="只能加入"
fi

echo "----"
echo "ITEM import $import_r"
echo "ITEM sign $sign_r"
echo "ITEM pubkey $pubkey_r"
echo "ITEM mutual $mutual"
echo "ITEM pubcheck $pubcheck"
echo "LEVEL $level"
echo "SDK_LINE $(awk '$1=="SDK"{print $2; exit}' "$log")"
