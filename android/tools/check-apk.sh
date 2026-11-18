#!/bin/sh
# 检查 release APK：权限白名单、allowBackup=false、没有 service、没有开机 receiver、
# R8 mapping 不含 vela.app.hooks、体积不超过上限。POSIX sh，macOS / Linux 均可。
set -eu

MAX_KB=5120
MAPPING=""
APK=""

usage() {
  echo "usage: check-apk.sh [--max-kb N] [--mapping FILE] APK" >&2
}

while [ $# -gt 0 ]; do
  case "$1" in
    --max-kb)
      shift
      if [ $# -eq 0 ]; then
        usage
        exit 2
      fi
      MAX_KB=$1
      ;;
    --mapping)
      shift
      if [ $# -eq 0 ]; then
        usage
        exit 2
      fi
      MAPPING=$1
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    --)
      shift
      break
      ;;
    -*)
      echo "unknown option: $1" >&2
      usage
      exit 2
      ;;
    *)
      if [ -n "$APK" ]; then
        echo "extra argument: $1" >&2
        exit 2
      fi
      APK=$1
      ;;
  esac
  shift
done

if [ -z "$APK" ]; then
  usage
  exit 2
fi

export CHECK_APK_PATH="$APK"
export CHECK_APK_MAX_KB="$MAX_KB"
export CHECK_APK_MAPPING="$MAPPING"

python3 - <<'PY'
import os
import re
import subprocess
import sys
from pathlib import Path

apk = Path(os.environ["CHECK_APK_PATH"])
mapping_arg = os.environ.get("CHECK_APK_MAPPING") or ""
try:
    max_kb = int(os.environ["CHECK_APK_MAX_KB"])
except ValueError:
    print("max-kb 必须是整数", file=sys.stderr)
    sys.exit(2)
if max_kb < 0:
    print("max-kb 不能为负", file=sys.stderr)
    sys.exit(2)
if not apk.is_file():
    print(f"找不到 APK: {apk}", file=sys.stderr)
    sys.exit(2)

ALLOWED = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
}
BOOT_MARKERS = (
    "android.intent.action.BOOT_COMPLETED",
    "android.intent.action.QUICKBOOT_POWERON",
    "com.htc.intent.action.QUICKBOOT_POWERON",
)

def version_key(name):
    nums = []
    for part in name.split("."):
        digits = []
        for ch in part:
            if ch.isdigit():
                digits.append(ch)
            else:
                break
        nums.append(int("".join(digits)) if digits else -1)
    return nums

def find_aapt():
    found = []
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        home = os.environ.get(key)
        if not home:
            continue
        tools = Path(home) / "build-tools"
        if not tools.is_dir():
            continue
        for child in tools.iterdir():
            candidate = child / "aapt"
            if candidate.is_file() and os.access(candidate, os.X_OK):
                found.append(candidate)
    if found:
        found.sort(key=lambda path: version_key(path.parent.name))
        return found[-1]
    from shutil import which
    located = which("aapt")
    return Path(located) if located else None

def run_aapt(aapt, *args):
    completed = subprocess.run(
        [str(aapt), *args],
        text=True,
        capture_output=True,
        check=False,
    )
    if completed.returncode != 0:
        sys.stderr.write(completed.stderr)
        print(f"aapt 失败: {' '.join(args[:2])}", file=sys.stderr)
        sys.exit(1)
    return completed.stdout

def default_mapping(apk_path):
    parts = list(apk_path.resolve().parts)
    for index in range(len(parts) - 3):
        if parts[index] == "outputs" and parts[index + 1] == "apk":
            variant = parts[index + 2]
            base = Path(*parts[:index])
            return base / "outputs" / "mapping" / variant / "mapping.txt"
    return None

class Node:
    def __init__(self, tag, indent):
        self.tag = tag
        self.indent = indent
        self.attrs = []
        self.children = []

def parse_tree(text):
    roots = []
    stack = []
    for raw in text.splitlines():
        if not raw.strip():
            continue
        indent = len(raw) - len(raw.lstrip(" "))
        content = raw.strip()
        if content.startswith("N:"):
            continue
        if content.startswith("E:"):
            tag = content.split()[1]
            node = Node(tag, indent)
            while stack and stack[-1].indent >= indent:
                stack.pop()
            if stack:
                stack[-1].children.append(node)
            else:
                roots.append(node)
            stack.append(node)
        elif content.startswith("A:") and stack:
            stack[-1].attrs.append(content)
    return roots

def walk(node, ancestors=()):
    chain = ancestors + (node,)
    yield node, chain
    for child in node.children:
        yield from walk(child, chain)

def allow_backup_value(line):
    if re.search(r"=true\b|0xffffffff", line):
        return True
    if re.search(r"=false\b|\(type 0x12\)0x0\b", line):
        return False
    return None

aapt = find_aapt()
if aapt is None:
    print("找不到 aapt。请设置 ANDROID_HOME 或 ANDROID_SDK_ROOT。", file=sys.stderr)
    sys.exit(2)

badging = run_aapt(aapt, "dump", "badging", str(apk))
xmltree = run_aapt(aapt, "dump", "xmltree", str(apk), "AndroidManifest.xml")

permissions = []
for line in badging.splitlines():
    if line.startswith("uses-permission"):
        match = re.search(r"name='([^']+)'", line)
        if match:
            permissions.append(match.group(1))

problems = []
for name in permissions:
    if name in ALLOWED or name.endswith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"):
        continue
    problems.append(f"权限不在白名单: {name}")

backup = None
for line in xmltree.splitlines():
    if "allowBackup" in line:
        backup = allow_backup_value(line)
        break
if backup is not False:
    problems.append(f"allowBackup 必须为 false，实际: {backup}")

roots = parse_tree(xmltree)
for root in roots:
    for node, chain in walk(root):
        tags = [item.tag for item in chain]
        if node.tag == "service" and "application" in tags:
            problems.append("manifest 含有 service")
        if node.tag == "receiver" and "application" in tags:
            blob = "\n".join(node.attrs)
            def collect(current, into):
                into.extend(current.attrs)
                for child in current.children:
                    collect(child, into)
            attrs = []
            collect(node, attrs)
            blob = "\n".join(attrs)
            for marker in BOOT_MARKERS:
                if marker in blob:
                    problems.append(f"开机 receiver: {marker}")

mapping = Path(mapping_arg) if mapping_arg else default_mapping(apk)
if mapping is None or not mapping.is_file():
    problems.append("找不到 R8 mapping（release 必须开启 minify）")
    mapping_text = ""
else:
    mapping_text = mapping.read_text(encoding="utf-8", errors="replace")
    if "vela.app.hooks" in mapping_text or "vela/app/hooks" in mapping_text:
        problems.append("R8 mapping 含有 vela.app.hooks")

size = apk.stat().st_size
limit = max_kb * 1024
print(f"apk_bytes={size}")
print(f"apk_kb={size / 1024:.1f}")
print(f"max_kb={max_kb}")
print(f"mapping={mapping if mapping else ''}")
print("permissions=" + ",".join(permissions))
print(f"allowBackup={backup}")
if size > limit:
    problems.append(f"体积超过上限: {size} bytes > {limit} bytes")

if problems:
    for item in problems:
        print(item, file=sys.stderr)
    sys.exit(1)
print("check-apk=ok")
PY
