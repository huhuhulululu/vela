#!/usr/bin/env python3
"""Vela sync blob server (docs/plans/sync.md sections 9 and 10).

Stdlib plus python3-cryptography. Listens on 127.0.0.1 only.
"""

from __future__ import annotations

import argparse
import base64
import datetime
import hashlib
import http.server
import ipaddress
import json
import os
import re
import secrets
import socket
import sqlite3
import sys
import threading
import time
from collections import OrderedDict, deque

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ec import (
    ECDSA,
    SECP256R1,
    EllipticCurvePublicKey,
)
from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature
from cryptography.hazmat.primitives.hashes import SHA256

HERE = os.path.dirname(os.path.abspath(__file__))
SCHEMA_PATH = os.path.join(HERE, "schema.sql")

# Section 8 / 10.3. Literals, not derived, where the spec writes a truncated integer.
MAX_ENVELOPE = 800_100
MAX_BODY = 802_048
HEADER_LIMIT = 8192
REQUEST_DEADLINE_S = 10.0
MAX_CONNECTIONS = 32
SPACE_LIMIT = 5000
UPDATE_BYTE_LIMIT = 268_435_456  # 256 MiB
CREATE_BYTE_LIMIT = 214_748_364  # already-at 80% rejects create
HEALTH_BYTE_LIMIT = UPDATE_BYTE_LIMIT * 70 // 100

IP_REQUESTS_PER_MIN = 120
IP_SIGFAIL_PER_MIN = 5
SPACE_WRITES_PER_MIN = 30
GLOBAL_WRITES_PER_MIN = 600
CREATE_PER_IP_HOUR = 3
CREATE_PER_IP_DAY = 10
CREATE_GLOBAL_DAY = 50
WINDOW_MS = 60_000
HOUR_MS = 3_600_000
DAY_MS = 86_400_000
KEEP_CREATIONS_MS = 2 * DAY_MS
LRU_BUCKETS = 10_000
# 访问日志只经 stderr 进 journald；内存里只留最近这些行（测试读取用），防止长期运行无限增长。
ACCESS_LOG_KEEP = 1000
BASE_MAX = (1 << 53) - 2
# Section 4.1. Order of P-256. Reject r or s outside [1, n-1] before verify.
P256_N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551

ENVELOPE_RE = re.compile(r"^e1\.[A-Za-z0-9_-]{16}\.[A-Za-z0-9_-]{22,}$")
BLOB_ID_RE = re.compile(r"^[0-9a-f]{64}$")
HEADER_NAME_RE = re.compile(r"^[!#$%&'*+.^_`|~0-9a-z-]+$")

REASONS = {
    200: "OK",
    400: "Bad Request",
    403: "Forbidden",
    404: "Not Found",
    405: "Method Not Allowed",
    410: "Gone",
    411: "Length Required",
    413: "Content Too Large",
    429: "Too Many Requests",
    500: "Internal Server Error",
    507: "Insufficient Storage",
}


class RequestTimeout(Exception):
    pass


class ConnectionClosed(Exception):
    pass


class HeadersTooLarge(Exception):
    pass


class BadRequest(Exception):
    pass


class Outcome:
    def __init__(self, status, fields=None, commit=False, wrote=False, sig_failed=False, retry=None, epoch=None):
        self.status = status
        self.fields = fields or {}
        self.commit = commit
        self.wrote = wrote
        self.sig_failed = sig_failed
        self.retry = retry
        self.epoch = epoch


def b64url_encode(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def b64url_decode(text):
    # Section 4.2: no padding, no whitespace, canonical unused bits.
    if not isinstance(text, str) or not re.fullmatch(r"[A-Za-z0-9_-]+", text):
        raise ValueError("base64url")
    pad = "=" * ((-len(text)) % 4)
    try:
        raw = base64.urlsafe_b64decode(text + pad)
    except Exception as exc:
        raise ValueError("base64url") from exc
    if b64url_encode(raw) != text:
        raise ValueError("noncanonical")
    return raw


def p256_public_key(pub):
    """Section 10.5. ValueError if pub is not a 65-byte uncompressed P-256 point."""
    if not isinstance(pub, (bytes, bytearray)):
        raise ValueError("pub")
    # 先查形状：from_encoded_point 也接受 33 字节压缩点，第 9 节只允许 65 字节、首字节 0x04 的未压缩点。
    if len(pub) != 65 or pub[0] != 0x04:
        raise ValueError("pub")
    return EllipticCurvePublicKey.from_encoded_point(SECP256R1(), bytes(pub))


def verify_p256(pub, sig, msg):
    """Section 9. Format-check r||s, then ECDSA(SHA256). False rejects the signature."""
    if not isinstance(pub, (bytes, bytearray)) or not isinstance(sig, (bytes, bytearray)):
        return False
    if not isinstance(msg, (bytes, bytearray)):
        return False
    if len(sig) != 64:
        return False
    r = int.from_bytes(sig[:32], "big")
    s = int.from_bytes(sig[32:], "big")
    if r < 1 or r >= P256_N or s < 1 or s >= P256_N:
        return False
    try:
        key = p256_public_key(pub)
    except ValueError:
        return False
    try:
        key.verify(encode_dss_signature(r, s), msg, ECDSA(SHA256()))
        return True
    except InvalidSignature:
        return False


def signed_message(op, blob_id, epoch, base, line6):
    # Section 9: six ASCII lines joined by 0x0A, no trailing newline.
    text = "\n".join(
        [
            "vela-sync/v2/" + op,
            blob_id,
            epoch,
            str(int(base)),
            str(int(base) + 1),
            line6,
        ]
    )
    return text.encode("ascii")


def size_tier(n):
    # Section 10.4 says "体积档位" but does not list the bands.
    # Fixed bands so the log never contains the exact byte count.
    if n <= 0:
        return "0"
    if n < 1024:
        return "lt1k"
    if n < 8192:
        return "lt8k"
    if n < 65536:
        return "lt64k"
    if n <= MAX_BODY:
        return "lt802k"
    return "over"


def format_log(now_ms, method, route, ident, status, tier, ip_prefix):
    sec = int(now_ms) // 1000
    ms = abs(int(now_ms)) % 1000
    stamp = time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(sec)) + ".%03dZ" % ms
    if not re.fullmatch(r"[A-Z]{1,16}", method or ""):
        method = "-"
    if ident and BLOB_ID_RE.fullmatch(ident):
        prefix = ident[:8]
    else:
        prefix = "-"
    if not re.fullmatch(r"[0-9]+|-", str(status)):
        status = "-"
    if not re.fullmatch(r"[A-Za-z0-9_.:/-]+", route or ""):
        route = "unknown"
    if not re.fullmatch(r"[A-Za-z0-9:./-]+", ip_prefix or ""):
        ip_prefix = "-"
    return "%s %s %s %s %s %s %s" % (stamp, method, route, prefix, status, tier, ip_prefix)


def parse_ip(value):
    if not isinstance(value, str):
        return None
    text = value.strip()
    if not text or any(ch.isspace() for ch in text):
        return None
    try:
        ip = ipaddress.ip_address(text)
    except ValueError:
        return None
    if getattr(ip, "scope_id", None):
        return None
    return ip


def ip_bucket(ip):
    if isinstance(ip, ipaddress.IPv4Address):
        return str(ip)
    return str(ipaddress.ip_network("%s/64" % ip, strict=False))


def ip_log_prefix(ip):
    if isinstance(ip, ipaddress.IPv4Address):
        return str(ipaddress.ip_network("%s/24" % ip, strict=False))
    return str(ipaddress.ip_network("%s/48" % ip, strict=False))


def utc_day_start_ms(now_ms):
    sec = int(now_ms) // 1000
    dt = datetime.datetime.fromtimestamp(sec, datetime.timezone.utc)
    start = dt.replace(hour=0, minute=0, second=0, microsecond=0)
    return int(start.timestamp() * 1000)


def retry_after_ms(remain_ms):
    if remain_ms <= 0:
        return 1
    return max(1, (int(remain_ms) + 999) // 1000)


def seconds_until_utc_midnight(now_ms):
    return retry_after_ms(utc_day_start_ms(now_ms) + DAY_MS - int(now_ms))


def parse_base(obj):
    if "base" not in obj:
        raise ValueError("base")
    base = obj["base"]
    if isinstance(base, bool) or not isinstance(base, int):
        raise ValueError("base")
    if base < 0 or base > BASE_MAX:
        raise ValueError("base")
    return base


def classify_path(path):
    prefix = "/v2/blob/"
    if not path.startswith(prefix):
        return "unknown", None
    rest = path[len(prefix) :]
    if "/" in rest:
        ident, extra = rest.split("/", 1)
        if extra == "delete":
            if BLOB_ID_RE.fullmatch(ident):
                return "delete", ident
            return "bad-id", None
        return "unknown", None
    if BLOB_ID_RE.fullmatch(rest):
        return "blob", rest
    return "bad-id", None


class RateLimiter:
    """In-memory sliding windows. At most LRU_BUCKETS keys; global writes are separate."""

    def __init__(self, max_buckets=None):
        self.max_buckets = LRU_BUCKETS if max_buckets is None else max_buckets
        self._lock = threading.Lock()
        self._buckets = OrderedDict()
        self._global = deque()

    def _prune(self, dq, now, window):
        cutoff = now - window
        while dq and dq[0] <= cutoff:
            dq.popleft()

    def _retry(self, dq, now, window):
        if not dq:
            return 1
        return retry_after_ms(dq[0] + window - now)

    def _append(self, key, now):
        dq = self._buckets.get(key)
        if dq is None:
            while len(self._buckets) >= self.max_buckets:
                self._buckets.popitem(last=False)
            dq = deque()
            self._buckets[key] = dq
        else:
            self._buckets.move_to_end(key)
        dq.append(now)

    def allow(self, key, now, window, limit):
        """Record the hit if under the limit. Rejected hits are not stored."""
        with self._lock:
            dq = self._buckets.get(key)
            if dq is None:
                n = 0
                retry = 1
            else:
                self._buckets.move_to_end(key)
                self._prune(dq, now, window)
                n = len(dq)
                retry = self._retry(dq, now, window)
            if n >= limit:
                return False, retry
            self._append(key, now)
            return True, 0

    def stats(self, key, now, window):
        with self._lock:
            dq = self._buckets.get(key)
            if dq is None:
                return 0, 1
            self._buckets.move_to_end(key)
            self._prune(dq, now, window)
            return len(dq), self._retry(dq, now, window)

    def add(self, key, now):
        with self._lock:
            self._append(key, now)

    def add_global(self, now):
        with self._lock:
            self._global.append(now)

    def stats_global(self, now, window):
        with self._lock:
            self._prune(self._global, now, window)
            return len(self._global), self._retry(self._global, now, window)


def connect(db_path):
    conn = sqlite3.connect(db_path, timeout=5, isolation_level=None)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=FULL")
    conn.execute("PRAGMA busy_timeout=5000")
    return conn


def _chmod_private(path):
    try:
        if os.path.exists(path):
            os.chmod(path, 0o600)
    except OSError:
        pass


def init_db(db_path):
    parent = os.path.dirname(os.path.abspath(db_path))
    if parent:
        os.makedirs(parent, exist_ok=True)
    conn = connect(db_path)
    try:
        with open(SCHEMA_PATH, encoding="utf-8") as handle:
            conn.executescript(handle.read())
        conn.execute(
            "INSERT INTO meta(key, value) SELECT 'epoch', ? "
            "WHERE NOT EXISTS (SELECT 1 FROM meta WHERE key='epoch')",
            (secrets.token_hex(16),),
        )
    finally:
        conn.close()
    _chmod_private(db_path)
    for suffix in ("-wal", "-shm"):
        _chmod_private(db_path + suffix)


def _epoch(conn):
    row = conn.execute("SELECT value FROM meta WHERE key='epoch'").fetchone()
    if row is None or not re.fullmatch(r"[0-9a-f]{32}", row[0]):
        raise RuntimeError("epoch missing")
    return row[0]


def _totals(conn):
    row = conn.execute("SELECT count(*), total(size) FROM blobs").fetchone()
    return int(row[0]), int(row[1] or 0)


def _conflict_fields(row):
    return {
        "ver": row["ver"],
        "pub": b64url_encode(bytes(row["pub"])),
        "data": row["data"],
    }


def _check_envelope(data, raw):
    if len(raw) > MAX_ENVELOPE:
        return 413
    if ENVELOPE_RE.fullmatch(data) is None:
        return 400
    return None


def _write_limit(limiter, blob_id, now):
    n, retry = limiter.stats("w:" + blob_id, now, WINDOW_MS)
    if n >= SPACE_WRITES_PER_MIN:
        return retry
    n, retry = limiter.stats_global(now, WINDOW_MS)
    if n >= GLOBAL_WRITES_PER_MIN:
        return retry
    return None


def _creation_block(conn, bucket, now):
    # Drop rows older than 2 days, then enforce the hour and UTC-day caps.
    conn.execute("DELETE FROM creations WHERE ts < ?", (now - KEEP_CREATIONS_MS,))
    hour_rows = conn.execute(
        "SELECT ts FROM creations WHERE ip_bucket=? AND ts > ?",
        (bucket, now - HOUR_MS),
    ).fetchall()
    day_start = utc_day_start_ms(now)
    day_n = conn.execute(
        "SELECT count(*) FROM creations WHERE ip_bucket=? AND ts >= ?",
        (bucket, day_start),
    ).fetchone()[0]
    global_n = conn.execute(
        "SELECT count(*) FROM creations WHERE ts >= ?",
        (day_start,),
    ).fetchone()[0]
    retries = []
    if len(hour_rows) >= CREATE_PER_IP_HOUR:
        oldest = min(row[0] for row in hour_rows)
        retries.append(retry_after_ms(oldest + HOUR_MS - now))
    if day_n >= CREATE_PER_IP_DAY or global_n >= CREATE_GLOBAL_DAY:
        retries.append(seconds_until_utc_midnight(now))
    if retries:
        return max(retries)
    return None


def _decode_sig(sig_text):
    # Length is a signature-format check (section 9), not a 400 decode error.
    return b64url_decode(sig_text)


def _put_locked(conn, blob_id, obj, bucket, now, limiter):
    epoch = _epoch(conn)
    deleted = conn.execute("SELECT deleted_at FROM deleted WHERE id=?", (blob_id,)).fetchone()
    if deleted is not None:
        return Outcome(410, {"deletedAt": int(deleted["deleted_at"])}, epoch=epoch)

    try:
        base = parse_base(obj)
        data = obj.get("data")
        sig_text = obj.get("sig")
        if not isinstance(data, str) or not isinstance(sig_text, str):
            raise ValueError("fields")
        if "pub" in obj and not isinstance(obj.get("pub"), str):
            raise ValueError("pub")
        raw = data.encode("ascii")
        sig = _decode_sig(sig_text)
    except (ValueError, UnicodeEncodeError):
        return Outcome(400, epoch=epoch)

    row = conn.execute(
        "SELECT ver, pub, data, size FROM blobs WHERE id=?", (blob_id,)
    ).fetchone()

    if base == 0:
        if "pub" not in obj:
            return Outcome(400, epoch=epoch)
        try:
            pub = b64url_decode(obj["pub"])
            p256_public_key(pub)
        except ValueError:
            return Outcome(400, epoch=epoch)
        digest = hashlib.sha256(raw).hexdigest()
        msg = signed_message("put", blob_id, epoch, base, digest)
        if not verify_p256(pub, sig, msg):
            return Outcome(403, sig_failed=True, epoch=epoch)
        if row is not None:
            return Outcome(409, _conflict_fields(row), epoch=epoch)
        env_status = _check_envelope(data, raw)
        if env_status:
            return Outcome(env_status, epoch=epoch)
        limited = _write_limit(limiter, blob_id, now)
        if limited:
            return Outcome(429, retry=limited, epoch=epoch)
        count, total = _totals(conn)
        if count >= SPACE_LIMIT or total >= CREATE_BYTE_LIMIT:
            return Outcome(507, epoch=epoch)
        blocked = _creation_block(conn, bucket, now)
        if blocked:
            return Outcome(429, retry=blocked, epoch=epoch)
        conn.execute(
            "INSERT INTO blobs(id, ver, pub, data, size, created_at, updated_at) "
            "VALUES (?,?,?,?,?,?,?)",
            (blob_id, base + 1, pub, data, len(raw), now, now),
        )
        conn.execute(
            "INSERT INTO creations(ts, ip_bucket) VALUES (?, ?)",
            (now, bucket),
        )
        return Outcome(200, {"ver": base + 1}, commit=True, wrote=True, epoch=epoch)

    if "pub" in obj:
        return Outcome(400, epoch=epoch)
    if row is None:
        return Outcome(404, epoch=epoch)
    digest = hashlib.sha256(raw).hexdigest()
    msg = signed_message("put", blob_id, epoch, base, digest)
    if not verify_p256(bytes(row["pub"]), sig, msg):
        return Outcome(403, sig_failed=True, epoch=epoch)
    if base != int(row["ver"]):
        return Outcome(409, _conflict_fields(row), epoch=epoch)
    env_status = _check_envelope(data, raw)
    if env_status:
        return Outcome(env_status, epoch=epoch)
    limited = _write_limit(limiter, blob_id, now)
    if limited:
        return Outcome(429, retry=limited, epoch=epoch)
    _count, total = _totals(conn)
    new_total = total - int(row["size"]) + len(raw)
    if new_total > UPDATE_BYTE_LIMIT:
        return Outcome(507, epoch=epoch)
    cur = conn.execute(
        "UPDATE blobs SET ver=?, data=?, size=?, updated_at=? WHERE id=? AND ver=?",
        (base + 1, data, len(raw), now, blob_id, base),
    )
    if cur.rowcount != 1:
        fresh = conn.execute(
            "SELECT ver, pub, data FROM blobs WHERE id=?", (blob_id,)
        ).fetchone()
        fields = _conflict_fields(fresh if fresh is not None else row)
        return Outcome(409, fields, epoch=epoch)
    return Outcome(200, {"ver": base + 1}, commit=True, wrote=True, epoch=epoch)


def _delete_locked(conn, blob_id, obj, now, limiter):
    epoch = _epoch(conn)
    deleted = conn.execute("SELECT deleted_at FROM deleted WHERE id=?", (blob_id,)).fetchone()
    if deleted is not None:
        return Outcome(410, {"deletedAt": int(deleted["deleted_at"])}, epoch=epoch)
    try:
        base = parse_base(obj)
        sig_text = obj.get("sig")
        if not isinstance(sig_text, str):
            raise ValueError("sig")
        sig = _decode_sig(sig_text)
    except (ValueError, UnicodeEncodeError):
        return Outcome(400, epoch=epoch)
    row = conn.execute(
        "SELECT ver, pub, data, size FROM blobs WHERE id=?", (blob_id,)
    ).fetchone()
    if row is None:
        return Outcome(404, epoch=epoch)
    msg = signed_message("delete", blob_id, epoch, base, "-")
    if not verify_p256(bytes(row["pub"]), sig, msg):
        return Outcome(403, sig_failed=True, epoch=epoch)
    if base != int(row["ver"]):
        return Outcome(409, _conflict_fields(row), epoch=epoch)
    limited = _write_limit(limiter, blob_id, now)
    if limited:
        return Outcome(429, retry=limited, epoch=epoch)
    cur = conn.execute("DELETE FROM blobs WHERE id=? AND ver=?", (blob_id, base))
    if cur.rowcount != 1:
        fresh = conn.execute(
            "SELECT ver, pub, data FROM blobs WHERE id=?", (blob_id,)
        ).fetchone()
        fields = _conflict_fields(fresh if fresh is not None else row)
        return Outcome(409, fields, epoch=epoch)
    conn.execute(
        "INSERT INTO deleted(id, deleted_at, last_epoch, last_ver) VALUES (?,?,?,?)",
        (blob_id, now, epoch, int(row["ver"])),
    )
    return Outcome(200, {}, commit=True, wrote=True, epoch=epoch)


def _run_write(db_path, fn):
    conn = connect(db_path)
    try:
        conn.execute("BEGIN IMMEDIATE")
        try:
            outcome = fn(conn)
        except Exception:
            try:
                conn.execute("ROLLBACK")
            except sqlite3.Error:
                pass
            raise
        if outcome.commit:
            conn.execute("COMMIT")
        else:
            conn.execute("ROLLBACK")
        return outcome
    finally:
        conn.close()


def perform_put(db_path, blob_id, obj, bucket, now, limiter):
    outcome = _run_write(
        db_path, lambda conn: _put_locked(conn, blob_id, obj, bucket, now, limiter)
    )
    return _finish_write(outcome, limiter, blob_id, bucket, now)


def perform_delete(db_path, blob_id, obj, bucket, now, limiter):
    outcome = _run_write(db_path, lambda conn: _delete_locked(conn, blob_id, obj, now, limiter))
    return _finish_write(outcome, limiter, blob_id, bucket, now)


def _finish_write(outcome, limiter, blob_id, bucket, now):
    if outcome.wrote:
        limiter.add("w:" + blob_id, now)
        limiter.add_global(now)
    if outcome.sig_failed:
        recorded, retry = limiter.allow("sig:" + bucket, now, WINDOW_MS, IP_SIGFAIL_PER_MIN)
        if not recorded:
            outcome.status = 429
            outcome.retry = retry
            outcome.fields = {}
    return outcome


def perform_get(db_path, blob_id):
    conn = connect(db_path)
    try:
        conn.execute("BEGIN")
        try:
            epoch = _epoch(conn)
            deleted = conn.execute(
                "SELECT deleted_at FROM deleted WHERE id=?", (blob_id,)
            ).fetchone()
            if deleted is not None:
                deleted_at = int(deleted["deleted_at"])
                conn.execute("COMMIT")
                return Outcome(410, {"deletedAt": deleted_at}, epoch=epoch)
            row = conn.execute(
                "SELECT ver, pub, data FROM blobs WHERE id=?", (blob_id,)
            ).fetchone()
            if row is None:
                conn.execute("COMMIT")
                return Outcome(404, epoch=epoch)
            fields = {
                "ver": int(row["ver"]),
                "pub": b64url_encode(bytes(row["pub"])),
                "data": row["data"],
            }
            conn.execute("COMMIT")
            return Outcome(200, fields, epoch=epoch)
        except Exception:
            try:
                conn.execute("ROLLBACK")
            except sqlite3.Error:
                pass
            raise
    finally:
        conn.close()


def read_epoch(db_path):
    conn = connect(db_path)
    try:
        return _epoch(conn)
    finally:
        conn.close()


class SyncHTTPServer(http.server.ThreadingHTTPServer):
    daemon_threads = True
    block_on_close = False
    allow_reuse_address = True
    request_queue_size = 128

    def process_request(self, request, client_address):
        try:
            request.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except OSError:
            pass
        with self.conn_lock:
            if self.active >= MAX_CONNECTIONS:
                try:
                    request.close()
                except OSError:
                    pass
                return
            self.active += 1
            self.started_at[id(request)] = time.monotonic()
        try:
            super().process_request(request, client_address)
        except Exception:
            with self.conn_lock:
                self.active -= 1
                self.started_at.pop(id(request), None)
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            with self.conn_lock:
                self.active -= 1
                self.started_at.pop(id(request), None)

    def handle_error(self, request, client_address):
        sys.stderr.write("sync server: handler error\n")

    def active_count(self):
        with self.conn_lock:
            return self.active

    def record_log(self, line):
        with self.conn_lock:
            self.access_log.append(line)
        try:
            sys.stderr.write(line + "\n")
            sys.stderr.flush()
        except Exception:
            pass


def make_server(db_path, host="127.0.0.1", port=0, clock=None):
    if host != "127.0.0.1":
        raise ValueError("loopback only")
    init_db(db_path)
    server = SyncHTTPServer((host, port), Handler)
    server.db_path = db_path
    server.clock = clock or (lambda: int(time.time() * 1000))
    server.limiter = RateLimiter()
    server.access_log = deque(maxlen=ACCESS_LOG_KEEP)
    server.conn_lock = threading.Lock()
    server.active = 0
    server.started_at = {}
    server.timeout = None
    return server


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def setup(self):
        self.connection = self.request
        self.rfile = None
        self.wfile = None

    def finish(self):
        try:
            self.connection.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            self.connection.close()
        except OSError:
            pass

    def log_message(self, fmt, *args):
        return

    def log_request(self, code="-", size="-"):
        return

    def handle(self):
        self.replied = False
        self.got_bytes = False
        self.method = "-"
        self.route = "unknown"
        self.ident = None
        self.ip_prefix = "-"
        self.tier = "0"
        self.allow = None
        self.now = int(self.server.clock())
        started = self.server.started_at.get(id(self.connection), time.monotonic())
        self.deadline = started + REQUEST_DEADLINE_S
        try:
            self.epoch = read_epoch(self.server.db_path)
        except Exception:
            self.epoch = None
        try:
            self._process()
        except RequestTimeout:
            self._log_only("-")
        except ConnectionClosed:
            if self.got_bytes:
                self._log_only("-")
        except HeadersTooLarge:
            self.route = "unknown"
            self._reply(400, {})
        except BadRequest:
            self._reply(400, {})
        except (ValueError, RecursionError):
            # 畸形输入（例如嵌套过深的 JSON、越界的数字）是客户端错误，不是服务端故障。
            self._reply(400, {})
        except Exception as exc:
            # 只记异常类名，不带请求内容与异常消息。
            try:
                sys.stderr.write("sync server: 500 %s\n" % type(exc).__name__)
            except Exception:
                pass
            self._reply(500, {})

    def _process(self):
        header, pre = self._read_headers()
        self.got_bytes = True
        method, target, headers = _parse_request(header)
        self.method = method
        if "?" in target or "#" in target or not target.startswith("/"):
            raise BadRequest()
        kind, ident = classify_path(target)
        self.ident = ident
        if kind == "blob":
            self.route = "/v2/blob/:id"
        elif kind == "delete":
            self.route = "/v2/blob/:id/delete"
        elif kind == "bad-id":
            self.route = "bad-id"
        else:
            self.route = "unknown"

        if any(name == "transfer-encoding" for name, _value in headers):
            self._reply(400, {})
            return
        lengths = [value for name, value in headers if name == "content-length"]
        if len(lengths) > 1:
            raise BadRequest()
        if lengths:
            if not re.fullmatch(r"0|[1-9][0-9]*", lengths[0]):
                raise BadRequest()
            content_length = int(lengths[0])
        else:
            content_length = None

        cfs = [value for name, value in headers if name == "cf-connecting-ip"]
        if len(cfs) != 1:
            self._reply(400, {})
            return
        ip = parse_ip(cfs[0])
        if ip is None:
            self._reply(400, {})
            return
        self.ip_prefix = ip_log_prefix(ip)
        bucket = ip_bucket(ip)

        if method in ("PUT", "POST") and content_length is None:
            self._reply(411, {})
            return
        allowed, retry = self.server.limiter.allow(
            "req:" + bucket, self.now, WINDOW_MS, IP_REQUESTS_PER_MIN
        )
        if not allowed:
            self._reply(429, {}, retry=retry)
            return
        if content_length is not None and content_length > MAX_BODY:
            self.tier = "over"
            self._reply(413, {})
            return
        if method == "GET" and content_length:
            self._reply(400, {})
            return
        if content_length:
            body = self._read_exact(content_length, pre)
            self.tier = size_tier(len(body))
        else:
            body = b""
            self.tier = "0"

        if kind == "unknown":
            self._reply(404, {})
            return
        if kind == "bad-id":
            self._reply(400, {})
            return
        if kind == "blob" and method == "GET":
            outcome = perform_get(self.server.db_path, ident)
            if outcome.epoch:
                self.epoch = outcome.epoch
            self._reply(outcome.status, outcome.fields)
            return
        if kind == "blob" and method == "PUT":
            self._write(body, ident, bucket, perform_put)
            return
        if kind == "delete" and method == "POST":
            self._write(body, ident, bucket, perform_delete)
            return
        self.allow = "GET, PUT" if kind == "blob" else "POST"
        self._reply(405, {})

    def _write(self, body, ident, bucket, fn):
        try:
            obj = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            self._reply(400, {})
            return
        if not isinstance(obj, dict):
            self._reply(400, {})
            return
        outcome = fn(self.server.db_path, ident, obj, bucket, self.now, self.server.limiter)
        if outcome.epoch:
            self.epoch = outcome.epoch
        self._reply(outcome.status, outcome.fields, retry=outcome.retry)

    def _read_headers(self):
        buf = bytearray()
        while b"\r\n\r\n" not in buf:
            if len(buf) >= HEADER_LIMIT:
                raise HeadersTooLarge()
            chunk = self._recv(min(4096, HEADER_LIMIT - len(buf)))
            buf += chunk
        idx = buf.find(b"\r\n\r\n")
        if idx < 0 or idx + 4 > HEADER_LIMIT:
            raise HeadersTooLarge()
        return bytes(buf[:idx]), bytes(buf[idx + 4 :])

    def _read_exact(self, n, pre):
        if len(pre) >= n:
            return pre[:n]
        buf = bytearray(pre)
        while len(buf) < n:
            buf += self._recv(min(65536, n - len(buf)))
        return bytes(buf)

    def _recv(self, n):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise RequestTimeout()
        self.connection.settimeout(remaining)
        try:
            chunk = self.connection.recv(n)
        except socket.timeout:
            raise RequestTimeout()
        if not chunk:
            raise ConnectionClosed()
        return chunk

    def _drain_pending(self):
        conn = self.connection
        conn.setblocking(False)
        try:
            for _ in range(8):
                try:
                    chunk = conn.recv(65536)
                except (BlockingIOError, InterruptedError):
                    break
                if not chunk:
                    break
        finally:
            try:
                conn.setblocking(True)
            except OSError:
                pass

    def _reply(self, status, fields, retry=None):
        if self.replied:
            return
        self.replied = True
        obj = {}
        if fields:
            obj.update(fields)
        if self.epoch is not None:
            obj["epoch"] = self.epoch
        obj["now"] = self.now
        self._emit_log(status)
        try:
            self._drain_pending()
            body = json.dumps(obj, separators=(",", ":"), ensure_ascii=True).encode("utf-8")
            reason = REASONS.get(status, "Error")
            lines = [
                "HTTP/1.1 %d %s" % (status, reason),
                "Content-Type: application/json",
                "Cache-Control: no-store",
                "Content-Length: %d" % len(body),
                "Connection: close",
            ]
            if status == 429:
                lines.append("Retry-After: %d" % max(1, int(retry or 1)))
            if status == 405 and self.allow:
                lines.append("Allow: %s" % self.allow)
            raw = ("\r\n".join(lines) + "\r\n\r\n").encode("ascii") + body
            self.connection.sendall(raw)
        except OSError:
            pass

    def _log_only(self, status):
        if self.replied:
            return
        self.replied = True
        self._emit_log(status)

    def _emit_log(self, status):
        line = format_log(
            self.now,
            self.method,
            self.route,
            self.ident,
            status,
            self.tier,
            self.ip_prefix,
        )
        self.server.record_log(line)


def _parse_request(header_bytes):
    try:
        text = header_bytes.decode("latin-1")
    except UnicodeError as exc:
        raise BadRequest() from exc
    if "\x00" in text:
        raise BadRequest()
    lines = text.split("\r\n")
    if not lines or " " not in lines[0]:
        raise BadRequest()
    parts = lines[0].split(" ")
    if len(parts) != 3:
        raise BadRequest()
    method, target, version = parts
    if version not in ("HTTP/1.0", "HTTP/1.1"):
        raise BadRequest()
    if not re.fullmatch(r"[A-Z]{1,16}", method):
        raise BadRequest()
    headers = []
    for line in lines[1:]:
        if line == "":
            continue
        if line[0] in " \t" or ":" not in line:
            raise BadRequest()
        name, value = line.split(":", 1)
        name = name.strip().lower()
        if not name or HEADER_NAME_RE.fullmatch(name) is None:
            raise BadRequest()
        headers.append((name, value.strip()))
    return method, target, headers


def main(argv=None):
    parser = argparse.ArgumentParser(description="Vela sync blob server")
    parser.add_argument("--db", required=True)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8080)
    args = parser.parse_args(argv)
    if args.host != "127.0.0.1":
        sys.stderr.write("refusing non-loopback bind\n")
        return 2
    if args.port < 0 or args.port > 65535:
        sys.stderr.write("bad port\n")
        return 2
    server = make_server(args.db, args.host, args.port)
    sys.stdout.write("PORT %d\n" % server.server_address[1])
    sys.stdout.flush()
    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
