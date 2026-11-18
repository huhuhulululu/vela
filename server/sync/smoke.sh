#!/usr/bin/env bash
# Local curl smoke: create, read, conflict, delete.
# bash 3.2 / BSD. Python only builds the signed bodies (stdlib + cryptography).
set -eu

cd "$(dirname "$0")"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/vela-sync-smoke.XXXXXX")"
PID=""

cleanup() {
  if [ -n "$PID" ]; then
    kill "$PID" >/dev/null 2>&1 || true
    wait "$PID" >/dev/null 2>&1 || true
  fi
  rm -rf "$TMP"
}
trap cleanup EXIT

python3 -u app.py --db "$TMP/sync.db" --host 127.0.0.1 --port 0 >"$TMP/out" 2>"$TMP/err" &
PID="$!"

i=0
PORT=""
while [ "$i" -lt 50 ]; do
  if [ -s "$TMP/out" ]; then
    PORT="$(awk 'NR==1 { print $2; exit }' "$TMP/out")"
    if [ -n "$PORT" ]; then
      break
    fi
  fi
  i=$((i + 1))
  sleep 0.1
done

if [ -z "$PORT" ]; then
  echo "server did not start" >&2
  exit 1
fi

BASE="http://127.0.0.1:${PORT}"
IP="203.0.113.10"
BLOB="abababababababababababababababababababababababababababababababab"

# 404 and epoch. -D writes headers; body is the JSON.
curl -sS -D "$TMP/h" -o "$TMP/b" \
  -H "CF-Connecting-IP: ${IP}" \
  "${BASE}/v2/blob/${BLOB}"
awk 'NR==1 { print $2; exit }' "$TMP/h" >"$TMP/code"
if [ "$(cat "$TMP/code")" != "404" ]; then
  echo "read-missing expected 404" >&2
  exit 1
fi

SMOKE_BODY="$TMP/b" SMOKE_DIR="$TMP" python3 - << 'PY'
import hashlib, json, os
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

out = os.environ["SMOKE_DIR"]
obj = json.load(open(os.environ["SMOKE_BODY"], encoding="utf-8"))
epoch = obj["epoch"]
blob = "ab" * 32
env = "e1." + ("A" * 16) + "." + ("B" * 22)
sk = ec.generate_private_key(ec.SECP256R1())
pub = sk.public_key().public_bytes(
    serialization.Encoding.X962,
    serialization.PublicFormat.UncompressedPoint,
)
if len(pub) != 65 or pub[0] != 4:
    raise SystemExit("pub")

def b64url(raw):
    import base64
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")

def sign(op, base, envelope):
    line6 = "-" if op == "delete" else hashlib.sha256(envelope.encode("ascii")).hexdigest()
    msg = "\n".join([
        "vela-sync/v2/" + op,
        blob,
        epoch,
        str(base),
        str(base + 1),
        line6,
    ]).encode("ascii")
    der = sk.sign(msg, ec.ECDSA(hashes.SHA256()))
    r, s = decode_dss_signature(der)
    raw = r.to_bytes(32, "big") + s.to_bytes(32, "big")
    return b64url(raw)

create = {"base": 0, "data": env, "sig": sign("put", 0, env), "pub": b64url(pub)}
delete = {"base": 1, "sig": sign("delete", 1, None)}
with open(os.path.join(out, "create.json"), "w", encoding="utf-8") as handle:
    json.dump(create, handle, separators=(",", ":"))
with open(os.path.join(out, "delete.json"), "w", encoding="utf-8") as handle:
    json.dump(delete, handle, separators=(",", ":"))
PY

code() {
  awk 'NR==1 { print $2; exit }' "$1"
}

curl -sS -D "$TMP/h" -o "$TMP/b" \
  -X PUT \
  -H "CF-Connecting-IP: ${IP}" \
  -H "Content-Type: application/json" \
  --data-binary @"$TMP/create.json" \
  "${BASE}/v2/blob/${BLOB}"
if [ "$(code "$TMP/h")" != "200" ]; then
  echo "create expected 200" >&2
  exit 1
fi
echo "smoke create 200"

curl -sS -D "$TMP/h" -o "$TMP/b" \
  -H "CF-Connecting-IP: ${IP}" \
  "${BASE}/v2/blob/${BLOB}"
if [ "$(code "$TMP/h")" != "200" ]; then
  echo "read expected 200" >&2
  exit 1
fi
echo "smoke read 200"

curl -sS -D "$TMP/h" -o "$TMP/b" \
  -X PUT \
  -H "CF-Connecting-IP: ${IP}" \
  -H "Content-Type: application/json" \
  --data-binary @"$TMP/create.json" \
  "${BASE}/v2/blob/${BLOB}"
if [ "$(code "$TMP/h")" != "409" ]; then
  echo "conflict expected 409" >&2
  exit 1
fi
echo "smoke conflict 409"

curl -sS -D "$TMP/h" -o "$TMP/b" \
  -X POST \
  -H "CF-Connecting-IP: ${IP}" \
  -H "Content-Type: application/json" \
  --data-binary @"$TMP/delete.json" \
  "${BASE}/v2/blob/${BLOB}/delete"
if [ "$(code "$TMP/h")" != "200" ]; then
  echo "delete expected 200" >&2
  exit 1
fi
echo "smoke delete 200"

curl -sS -D "$TMP/h" -o "$TMP/b" \
  -H "CF-Connecting-IP: ${IP}" \
  "${BASE}/v2/blob/${BLOB}"
if [ "$(code "$TMP/h")" != "410" ]; then
  echo "read-after-delete expected 410" >&2
  exit 1
fi
echo "smoke read-after-delete 410"
