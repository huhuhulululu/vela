#!/bin/bash
# 金样本：单测，以及 go run 两次逐字节相同。兼容 bash 3.2。
set -eu
here=$(CDPATH= cd "$(dirname "$0")" && pwd)
root=$(CDPATH= cd "$here/../../.." && pwd)
cd "$here"
go test -count=1 .
a=$(mktemp "${TMPDIR:-/tmp}/vela-vec.XXXXXX")
b=$(mktemp "${TMPDIR:-/tmp}/vela-vec.XXXXXX")
trap 'rm -f "$a" "$b"' EXIT
go run . -o "$a"
go run . -o "$b"
cmp -s "$a" "$b"
out="$root/Tests/Fixtures/sync"
mkdir -p "$out"
cp "$a" "$out/vectors.json"
echo "vectors ok $(wc -c < "$out/vectors.json" | tr -d ' ') bytes"
