# S3 服务端验收测试。签名消息按 docs/plans/sync.md 第 9 节在本文件现场拼装，
# 不复用服务端的组包函数来生成被测请求。阈值一律写死为文档中的数字。

import base64
import datetime
import hashlib
import http.client
import json
import os
import re
import socket
import sqlite3
import subprocess
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

import app
import backup
import healthcheck
import rotate_epoch

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
VECTORS = os.path.join(ROOT, "Tests", "Fixtures", "sync", "vectors.json")
WYCHEPROOF = os.path.join(HERE, "testdata", "ecdsa_secp256r1_sha256_test.json")

CREATE_BYTE_LIMIT = 214_748_364
UPDATE_BYTE_LIMIT = 268_435_456
SPACE_LIMIT = 5000
MAX_ENVELOPE = 800_100
MAX_BODY = 802_048
HEADER_LIMIT = 8192
DEADLINE_S = 10.0
# 第 4.1 节写死的 P-256 阶。
P256_N = int("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)


def b64url_encode(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def sign_message(op, blob_id, epoch, base, envelope):
    """第 9 节：6 行 ASCII，0x0A 连接，末尾不加换行。"""
    if op == "put":
        line6 = hashlib.sha256(envelope.encode("ascii")).hexdigest()
    elif op == "delete":
        line6 = "-"
    else:
        raise AssertionError(op)
    text = "\n".join(
        [
            "vela-sync/v2/" + op,
            blob_id,
            epoch,
            str(base),
            str(base + 1),
            line6,
        ]
    )
    raw = text.encode("ascii")
    if raw.endswith(b"\n"):
        raise AssertionError("trailing newline")
    return raw


def new_key():
    sk = ec.generate_private_key(ec.SECP256R1())
    pub = sk.public_key().public_bytes(
        serialization.Encoding.X962,
        serialization.PublicFormat.UncompressedPoint,
    )
    if len(pub) != 65 or pub[0] != 0x04:
        raise AssertionError("uncompressed P-256 public key")
    return sk, pub


def raw_sign(sk, msg):
    """第 9 节：ECDSA P-256 SHA-256，线上 r‖s 各 32 字节大端。k 由平台 CSPRNG 生成。"""
    der = sk.sign(msg, ec.ECDSA(hashes.SHA256()))
    r, s = decode_dss_signature(der)
    if r < 1 or r >= P256_N or s < 1 or s >= P256_N:
        raise AssertionError("signature scalar out of range")
    return r.to_bytes(32, "big") + s.to_bytes(32, "big")


def _read_der_len(buf, i):
    if i >= len(buf):
        return None
    first = buf[i]
    i += 1
    if first < 0x80:
        return first, i, True
    nbytes = first & 0x7F
    if nbytes == 0 or i + nbytes > len(buf):
        return None
    # 长形式不得有前导 0，也不得在短形式够用时使用。
    canonical = buf[i] != 0 and not (nbytes == 1 and buf[i] < 0x80)
    value = int.from_bytes(buf[i : i + nbytes], "big")
    return value, i + nbytes, canonical


def _read_der_int(buf, i):
    if i >= len(buf) or buf[i] != 0x02:
        return None
    got = _read_der_len(buf, i + 1)
    if got is None:
        return None
    length, nxt, len_ok = got
    if length < 1 or nxt + length > len(buf):
        return None
    raw = buf[nxt : nxt + length]
    nxt += length
    if raw[0] & 0x80:
        return ("negative", nxt)
    canonical = len_ok
    if len(raw) > 1 and raw[0] == 0x00 and (raw[1] & 0x80) == 0:
        canonical = False
    return ("int", int.from_bytes(raw, "big"), nxt, canonical)


def _rs_from_strict_der(sig):
    """严格 DER 的 (r, s)。返回 ("ok", r, s) 或 (跳过原因,)。"""
    if not sig or sig[0] != 0x30:
        return ("structural",)
    got = _read_der_len(sig, 1)
    if got is None:
        return ("structural",)
    length, i, len_ok = got
    if i + length != len(sig):
        return ("structural",)
    first = _read_der_int(sig, i)
    if first is None:
        return ("structural",)
    if first[0] == "negative":
        return ("negative",)
    _tag, r, i, r_ok = first
    second = _read_der_int(sig, i)
    if second is None:
        return ("structural",)
    if second[0] == "negative":
        return ("negative",)
    _tag, s, i, s_ok = second
    if i != len(sig):
        return ("structural",)
    if not (len_ok and r_ok and s_ok):
        return ("noncanonical",)
    if r >= (1 << 256) or s >= (1 << 256):
        return ("too_wide",)
    return ("ok", r, s)


def envelope(payload_len=22):
    text = "e1." + ("A" * 16) + "." + ("B" * payload_len)
    if not re.fullmatch(r"e1\.[A-Za-z0-9_-]{16}\.[A-Za-z0-9_-]{22,}", text):
        raise AssertionError("bad envelope fixture")
    return text


def put_bytes(base, data, sig, pub=None):
    obj = {"base": base, "data": data, "sig": b64url_encode(sig)}
    if pub is not None:
        obj["pub"] = b64url_encode(pub)
    return json.dumps(obj, separators=(",", ":")).encode("utf-8")


def delete_bytes(base, sig):
    obj = {"base": base, "sig": b64url_encode(sig)}
    return json.dumps(obj, separators=(",", ":")).encode("utf-8")


class FakeClock:
    def __init__(self, ms):
        self.ms = ms

    def __call__(self):
        return self.ms

    def advance(self, delta):
        self.ms += delta


class UnitTest(unittest.TestCase):
    def test_constants_match_spec(self):
        self.assertEqual(app.CREATE_BYTE_LIMIT, CREATE_BYTE_LIMIT)
        self.assertEqual(app.UPDATE_BYTE_LIMIT, UPDATE_BYTE_LIMIT)
        self.assertEqual(app.SPACE_LIMIT, SPACE_LIMIT)
        self.assertEqual(app.MAX_ENVELOPE, MAX_ENVELOPE)
        self.assertEqual(app.MAX_BODY, MAX_BODY)
        self.assertEqual(app.HEADER_LIMIT, HEADER_LIMIT)
        self.assertEqual(app.REQUEST_DEADLINE_S, DEADLINE_S)
        self.assertEqual(app.LRU_BUCKETS, 10_000)
        self.assertEqual(app.UPDATE_BYTE_LIMIT, 256 * 1024 * 1024)
        self.assertEqual(app.UPDATE_BYTE_LIMIT * 80 // 100, CREATE_BYTE_LIMIT)
        self.assertEqual(app.HEALTH_BYTE_LIMIT, UPDATE_BYTE_LIMIT * 70 // 100)
        self.assertEqual(app.P256_N, P256_N)

    def test_signed_message_bytes(self):
        blob = "ab" * 32
        epoch = "cd" * 16
        digest = "ef" * 32
        msg = app.signed_message("put", blob, epoch, 0, digest)
        self.assertEqual(
            msg,
            b"\n".join(
                [
                    b"vela-sync/v2/put",
                    blob.encode("ascii"),
                    epoch.encode("ascii"),
                    b"0",
                    b"1",
                    digest.encode("ascii"),
                ]
            ),
        )
        self.assertFalse(msg.endswith(b"\n"))
        deleted = app.signed_message("delete", blob, epoch, 4, "-")
        self.assertTrue(deleted.endswith(b"\n5\n-"))
        self.assertFalse(deleted.endswith(b"-\n"))

    def test_p256_format_and_high_s(self):
        sk, pub = new_key()
        msg = b"vela-sync/v2/put\n" + b"ab" * 32
        sig = raw_sign(sk, msg)
        self.assertEqual(len(sig), 64)
        self.assertTrue(app.verify_p256(pub, sig, msg))
        flipped = bytearray(sig)
        flipped[-1] ^= 0x01
        self.assertFalse(app.verify_p256(pub, bytes(flipped), msg))
        for bad in (sig[:63], sig + b"\x00", b"", sig[:32], b"\x11" * 128):
            self.assertFalse(app.verify_p256(pub, bad, msg))
        r = int.from_bytes(sig[:32], "big")
        s = int.from_bytes(sig[32:], "big")
        one = (1).to_bytes(32, "big")
        for raw in (
            b"\x00" * 32 + s.to_bytes(32, "big"),
            r.to_bytes(32, "big") + (b"\x00" * 32),
            P256_N.to_bytes(32, "big") + one,
            one + P256_N.to_bytes(32, "big"),
            (P256_N + 1).to_bytes(32, "big") + one,
        ):
            self.assertEqual(len(raw), 64)
            self.assertFalse(app.verify_p256(pub, raw, msg))
        high = r.to_bytes(32, "big") + (P256_N - s).to_bytes(32, "big")
        self.assertNotEqual(high[32:], sig[32:])
        self.assertTrue(app.verify_p256(pub, high, msg))
        off = b"\x04" + (b"\x11" * 64)
        self.assertFalse(app.verify_p256(off, sig, msg))
        self.assertFalse(app.verify_p256(pub[:32], sig, msg))
        self.assertFalse(app.verify_p256(b"\x02" + pub[1:], sig, msg))

    def test_wycheproof_ecdsa_p256_sha256(self):
        # Wycheproof 这份是 ASN.1 签名。线上格式是固定 64 字节 r‖s，
        # 只能表达「严格 DER 且 r、s 均为小于 2^256 的非负整数」的条目。
        # BER、负整数、超过 32 字节的整数、截断或类型错误表达不了，不送入验签；
        # 长度不为 64 的拒绝见 test_p256_format_and_high_s 与 HTTP 用例。
        with open(WYCHEPROOF, encoding="utf-8") as handle:
            doc = json.load(handle)
        self.assertEqual(doc["algorithm"], "ECDSA")
        self.assertEqual(doc["schema"], "ecdsa_verify_schema_v1.json")
        seen = 0
        included = {"valid": 0, "invalid": 0, "acceptable": 0}
        skipped = {
            "structural": 0,
            "negative": 0,
            "noncanonical": 0,
            "too_wide": 0,
        }
        out_of_range = {"r0": 0, "s0": 0, "r_ge_n": 0, "s_ge_n": 0}
        problems = []
        for group in doc["testGroups"]:
            self.assertEqual(group.get("sha"), "SHA-256")
            self.assertEqual(group["publicKey"]["curve"], "secp256r1")
            pub = bytes.fromhex(group["publicKey"]["uncompressed"])
            self.assertEqual(len(pub), 65)
            self.assertEqual(pub[0], 0x04)
            for case in group["tests"]:
                seen += 1
                sig = bytes.fromhex(case["sig"]) if case["sig"] else b""
                parsed = _rs_from_strict_der(sig)
                if parsed[0] != "ok":
                    skipped[parsed[0]] += 1
                    continue
                r, s = parsed[1], parsed[2]
                raw = r.to_bytes(32, "big") + s.to_bytes(32, "big")
                msg = bytes.fromhex(case["msg"]) if case["msg"] else b""
                accepted = app.verify_p256(pub, raw, msg)
                result = case["result"]
                included[result] = included.get(result, 0) + 1
                if not (1 <= r < P256_N and 1 <= s < P256_N):
                    if r == 0:
                        out_of_range["r0"] += 1
                    if s == 0:
                        out_of_range["s0"] += 1
                    if r >= P256_N:
                        out_of_range["r_ge_n"] += 1
                    if s >= P256_N:
                        out_of_range["s_ge_n"] += 1
                    self.assertFalse(accepted)
                if result == "valid" and not accepted:
                    problems.append(("valid-rejected", case["tcId"], case.get("flags")))
                elif result == "invalid" and accepted:
                    problems.append(("invalid-accepted", case["tcId"], case.get("flags")))
                elif result == "acceptable" and accepted not in (True, False):
                    problems.append(("acceptable", case["tcId"]))
        self.assertEqual(problems, [])
        self.assertEqual(seen, doc["numberOfTests"])
        self.assertGreater(included["valid"], 0)
        self.assertGreater(included["invalid"], 0)
        self.assertGreater(out_of_range["r0"], 0)
        self.assertGreater(out_of_range["s0"], 0)
        self.assertGreater(out_of_range["r_ge_n"], 0)
        self.assertGreater(out_of_range["s_ge_n"], 0)
        for reason in skipped:
            self.assertGreater(skipped[reason], 0, reason)
        self.assertEqual(
            seen,
            sum(included.values()) + sum(skipped.values()),
        )

    def test_vectors_json_sign_section_when_present(self):
        if not os.path.exists(VECTORS):
            self.skipTest("S0 vectors.json 尚未产出")
        with open(VECTORS, encoding="utf-8") as handle:
            doc = json.load(handle)
        self.assertIn("sign", doc)
        self.assertIn("signInvalid", doc)
        self.assertGreater(len(doc["sign"]), 0)
        self.assertGreater(len(doc["signInvalid"]), 0)
        p256 = 0
        legacy = 0

        def rebuild(item):
            op = item["op"]
            line6 = "-" if op == "delete" else item["envelope_sha256_hex"]
            text = "\n".join(
                [
                    "vela-sync/v2/" + op,
                    item["blobID"],
                    item["epoch"],
                    str(item["base"]),
                    str(item["next"]),
                    line6,
                ]
            )
            return text.encode("ascii")

        for item in doc["sign"]:
            if all(k in item for k in ("op", "blobID", "epoch", "base", "next")):
                msg = rebuild(item)
                self.assertEqual(msg, bytes.fromhex(item["message_hex"]))
                self.assertEqual(item["next"], item["base"] + 1)
            else:
                msg = bytes.fromhex(item["message_hex"])
            pub = app.b64url_decode(item["pub_b64url"])
            sig = app.b64url_decode(item["sig_b64url"])
            if len(pub) == 65 and pub[0] == 0x04:
                self.assertTrue(app.verify_p256(pub, sig, msg))
                if item.get("valid") is False:
                    self.fail("sign entry marked invalid")
                p256 += 1
            elif len(pub) == 32 and "signSeed_hex" in item:
                # v4 Ed25519 金样本还在树里；不能当成 P-256 验签。
                legacy += 1
            else:
                self.fail("unrecognized sign vector shape")
        for item in doc["signInvalid"]:
            if all(k in item for k in ("op", "blobID", "epoch", "base", "next")):
                msg = rebuild(item)
                if "message_hex" in item:
                    self.assertEqual(msg, bytes.fromhex(item["message_hex"]))
            elif "message_hex" in item:
                msg = bytes.fromhex(item["message_hex"])
            else:
                self.fail("signInvalid missing message")
            try:
                pub = app.b64url_decode(item["pub_b64url"])
                sig = app.b64url_decode(item["sig_b64url"])
            except ValueError:
                continue
            if len(pub) == 65 and pub[0] == 0x04:
                self.assertFalse(app.verify_p256(pub, sig, msg))
                p256 += 1
            elif len(pub) == 32:
                legacy += 1
                self.assertFalse(app.verify_p256(pub, sig, msg))
            else:
                self.assertFalse(app.verify_p256(pub, sig, msg))
        self.assertGreater(p256 + legacy, 0)

    def test_lru_evicts_oldest_bucket_only(self):
        self.assertEqual(app.LRU_BUCKETS, 10_000)
        limiter = app.RateLimiter()
        now = 5_000_000
        for i in range(10_000):
            ok, _ = limiter.allow("b%d" % i, now, 60_000, 10)
            self.assertTrue(ok)
        ok, _ = limiter.allow("b-new", now, 60_000, 10)
        self.assertTrue(ok)
        self.assertEqual(limiter.stats("b0", now, 60_000)[0], 0)
        self.assertEqual(limiter.stats("b1", now, 60_000)[0], 1)
        # 全局写入计数不进 LRU，桶淘汰不能把它清掉。
        limiter.add_global(now)
        for i in range(10_000):
            limiter.allow("c%d" % i, now, 60_000, 10)
        self.assertEqual(limiter.stats_global(now, 60_000)[0], 1)

    def test_schema_and_pragmas(self):
        sql = open(os.path.join(HERE, "schema.sql"), encoding="utf-8").read()
        self.assertIn("CHECK(length(id)=64)", sql)
        self.assertIn("CHECK(length(pub)=65)", sql)
        self.assertIn("CREATE TABLE IF NOT EXISTS deleted", sql)
        self.assertIn("CREATE TABLE IF NOT EXISTS creations", sql)
        self.assertIn("CREATE INDEX IF NOT EXISTS creations_ts", sql)
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        db = os.path.join(tmp.name, "sync.db")
        app.init_db(db)
        conn = app.connect(db)
        self.assertEqual(conn.execute("PRAGMA journal_mode").fetchone()[0], "wal")
        self.assertEqual(conn.execute("PRAGMA synchronous").fetchone()[0], 2)
        self.assertGreaterEqual(conn.execute("PRAGMA busy_timeout").fetchone()[0], 5000)
        epoch = conn.execute("SELECT value FROM meta WHERE key='epoch'").fetchone()[0]
        self.assertRegex(epoch, r"^[0-9a-f]{32}$")
        app.init_db(db)
        epoch2 = conn.execute("SELECT value FROM meta WHERE key='epoch'").fetchone()[0]
        self.assertEqual(epoch, epoch2)
        mode = os.stat(db).st_mode & 0o777
        self.assertEqual(mode, 0o600)
        conn.close()


class ProtocolTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = os.path.join(self.tmp.name, "sync.db")
        # 正午 UTC，给「每小时 3 次、自然日 10 次」留出同日的时间窗。
        noon = datetime.datetime(2026, 6, 15, 12, 0, tzinfo=datetime.timezone.utc)
        self.clock = FakeClock(int(noon.timestamp()) * 1000)
        self.srv = None
        self.thread = None
        self.ip = "203.0.113.10"
        self.start()

    def tearDown(self):
        self.stop()
        self.tmp.cleanup()

    def start(self):
        self.srv = app.make_server(self.db, "127.0.0.1", 0, clock=self.clock)
        self.thread = threading.Thread(
            target=self.srv.serve_forever,
            kwargs={"poll_interval": 0.05},
            daemon=True,
        )
        self.thread.start()
        self.port = self.srv.server_address[1]

    def stop(self):
        if self.srv is not None:
            self.srv.shutdown()
            self.srv.server_close()
            if self.thread is not None:
                self.thread.join(timeout=3)
            self.srv = None
            self.thread = None

    def call(self, method, path, body=None, ip=None, headers=None, omit_ip=False, timeout=5):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=timeout)
        hdrs = {}
        if not omit_ip:
            hdrs["CF-Connecting-IP"] = self.ip if ip is None else ip
        if headers:
            hdrs.update(headers)
        conn.request(method, path, body=body, headers=hdrs)
        resp = conn.getresponse()
        data = resp.read()
        status = resp.status
        hdr = {k.lower(): v for k, v in resp.getheaders()}
        conn.close()
        return status, hdr, data

    def assert_json(self, status, hdr, data, code):
        self.assertEqual(status, code, data)
        ctype = hdr.get("content-type", "")
        self.assertEqual(ctype.split(";")[0].strip(), "application/json")
        self.assertEqual(hdr.get("cache-control"), "no-store")
        self.assertFalse(data.startswith(b"<"))
        obj = json.loads(data.decode("utf-8"))
        self.assertRegex(obj["epoch"], r"^[0-9a-f]{32}$")
        self.assertIsInstance(obj["now"], int)
        self.assertNotIsInstance(obj["now"], bool)
        self.assertEqual(obj["now"], self.clock())
        return obj

    def epoch_of(self, blob):
        status, hdr, data = self.call("GET", "/v2/blob/" + blob)
        obj = self.assert_json(status, hdr, data, 404)
        return obj["epoch"]

    def signed_put(self, sk, blob, epoch, base, env, pub=None):
        msg = sign_message("put", blob, epoch, base, env)
        body = put_bytes(base, env, raw_sign(sk, msg), pub)
        return body

    def create(self, blob=None, env=None, ip=None, sk=None, pub=None):
        blob = blob or ("ab" * 32)
        env = env or envelope()
        if sk is None or pub is None:
            sk, pub = new_key()
        epoch = self.epoch_of(blob)
        body = self.signed_put(sk, blob, epoch, 0, env, pub)
        status, hdr, data = self.call("PUT", "/v2/blob/" + blob, body, ip=ip)
        obj = self.assert_json(status, hdr, data, 200)
        self.assertEqual(obj["ver"], 1)
        self.assertEqual(obj["epoch"], epoch)
        return sk, pub, epoch, env, body

    def test_get_404_and_json_shape(self):
        blob = "cd" * 32
        status, hdr, data = self.call("GET", "/v2/blob/" + blob)
        obj = self.assert_json(status, hdr, data, 404)
        self.assertEqual(set(obj), {"epoch", "now"})

    def test_create_with_pub_then_get(self):
        blob = "ab" * 32
        env = envelope(30)
        sk, pub, epoch, env, body = self.create(blob, env)
        status, hdr, data = self.call("GET", "/v2/blob/" + blob)
        obj = self.assert_json(status, hdr, data, 200)
        self.assertEqual(obj["ver"], 1)
        self.assertEqual(obj["pub"], b64url_encode(pub))
        self.assertEqual(obj["data"], env)
        self.assertEqual(obj["epoch"], epoch)
        conn = sqlite3.connect(self.db)
        row = conn.execute(
            "SELECT ver, size, length(data), length(pub) FROM blobs WHERE id=?", (blob,)
        ).fetchone()
        conn.close()
        self.assertEqual(row[0], 1)
        self.assertEqual(row[1], len(env))
        self.assertEqual(row[2], len(env))
        self.assertEqual(row[3], 65)
        self.assertEqual(len(b64url_encode(pub)), 87)
        # 非创建请求带 pub 会被拒，已登记的 pub 不变。
        msg = sign_message("put", blob, epoch, 1, envelope(40))
        bad = put_bytes(1, envelope(40), raw_sign(sk, msg), pub)
        status, hdr, data = self.call("PUT", "/v2/blob/" + blob, bad)
        self.assert_json(status, hdr, data, 400)
        status, hdr, data = self.call("GET", "/v2/blob/" + blob)
        obj = self.assert_json(status, hdr, data, 200)
        self.assertEqual(obj["pub"], b64url_encode(pub))
        self.assertEqual(obj["data"], env)

    def test_bad_signature_403_creates_nothing(self):
        blob = "11" * 32
        env = envelope()
        sk, pub = new_key()
        epoch = self.epoch_of(blob)
        msg = sign_message("put", blob, epoch, 0, env)
        sig = bytearray(raw_sign(sk, msg))
        sig[-1] ^= 0x01
        body = put_bytes(0, env, bytes(sig), pub)
        status, hdr, data = self.call("PUT", "/v2/blob/" + blob, body)
        obj = self.assert_json(status, hdr, data, 403)
        self.assertEqual(set(obj), {"epoch", "now"})
        status, hdr, data = self.call("GET", "/v2/blob/" + blob)
        self.assert_json(status, hdr, data, 404)
        conn = sqlite3.connect(self.db)
        n = conn.execute("SELECT count(*) FROM creations").fetchone()[0]
        conn.close()
        self.assertEqual(n, 0)

    def test_sig_length_and_scalar_range_403(self):
        blob = "12" * 32
        env = envelope()
        sk, pub = new_key()
        epoch = self.epoch_of(blob)
        msg = sign_message("put", blob, epoch, 0, env)
        good = raw_sign(sk, msg)
        r = int.from_bytes(good[:32], "big")
        s = int.from_bytes(good[32:], "big")
        one = (1).to_bytes(32, "big")
        cases = [
            good[:63],
            good + b"\x00",
            b"\x00" * 32 + s.to_bytes(32, "big"),
            r.to_bytes(32, "big") + (b"\x00" * 32),
            P256_N.to_bytes(32, "big") + one,
            one + P256_N.to_bytes(32, "big"),
        ]
        # 验签失败每分钟 5 次，每条换一个 IP 桶，避免第 6 条被记成 429。
        for i, raw in enumerate(cases):
            body = put_bytes(0, env, raw, pub)
            status, hdr, data = self.call(
                "PUT", "/v2/blob/" + blob, body, ip="203.0.113.%d" % (20 + i)
            )
            self.assert_json(status, hdr, data, 403)
        status, hdr, data = self.call("GET", "/v2/blob/" + blob)
        self.assert_json(status, hdr, data, 404)

    def test_bad_point_400_on_create(self):
        blob = "13" * 32
        env = envelope()
        epoch = self.epoch_of(blob)
        msg = sign_message("put", blob, epoch, 0, env)
        sk, good_pub = new_key()
        sig = raw_sign(sk, msg)
        for pub in (
            b"\x04" + (b"\x11" * 64),
            b"\x02" + good_pub[1:],
            good_pub[:64],
            good_pub + b"\x00",
            b"\x00" * 65,
        ):
            body = put_bytes(0, env, sig, pub)
            status, hdr, data = self.call("PUT", "/v2/blob/" + blob, body)
            self.assert_json(status, hdr, data, 400)
        status, hdr, data = self.call("GET", "/v2/blob/" + blob)
        self.assert_json(status, hdr, data, 404)

    def test_high_s_create_accepted(self):
        blob = "14" * 32
        env = envelope()
        sk, pub = new_key()
        epoch = self.epoch_of(blob)
        msg = sign_message("put", blob, epoch, 0, env)
        sig = raw_sign(sk, msg)
        r = int.from_bytes(sig[:32], "big")
        s = int.from_bytes(sig[32:], "big")
        high = r.to_bytes(32, "big") + (P256_N - s).to_bytes(32, "big")
        self.assertNotEqual(high, sig)
        body = put_bytes(0, env, high, pub)
        status, hdr, data = self.call("PUT", "/v2/blob/" + blob, body)
        obj = self.assert_json(status, hdr, data, 200)
        self.assertEqual(obj["ver"], 1)

    def test_wrong_epoch_403(self):
        blob = "22" * 32
        env = envelope()
        sk, pub = new_key()
        epoch = self.epoch_of(blob)
        wrong = "0" * 32 if epoch != "0" * 32 else "1" * 32
        body = self.signed_put(sk, blob, wrong, 0, env, pub)
        status, hdr, data = self.call("PUT", "/v2/blob/" + blob, body)
        self.assert_json(status, hdr, data, 403)
        status, hdr, data = self.call("GET", "/v2/blob/" + blob)
        self.assert_json(status, hdr, data, 404)

    def test_conflict_409_includes_current_body(self):
        blob = "33" * 32
        sk, pub, epoch, env, body = self.create(blob, envelope(24))
        status, hdr, data = self.call("PUT", "/v2/blob/" + blob, body)
        obj = self.assert_json(status, hdr, data, 409)
        self.assertEqual(obj["ver"], 1)
        self.assertEqual(obj["pub"], b64url_encode(pub))
        self.assertEqual(obj["data"], env)
        self.assertEqual(obj["epoch"], epoch)
        # 版本不符但签名是按请求体里的 base 签的：先验签，再 409。
        other = envelope(28)
        msg = sign_message("put", blob, epoch, 5, other)
        mismatch = put_bytes(5, other, raw_sign(sk, msg))
        status, hdr, data = self.call("PUT", "/v2/blob/" + blob, mismatch)
        obj = self.assert_json(status, hdr, data, 409)
        self.assertEqual(obj["data"], env)
        msg = sign_message("put", blob, epoch, 5, other)
        bad = bytearray(raw_sign(sk, msg))
        bad[0] ^= 0x04
        status, hdr, data = self.call("PUT", "/v2/blob/" + blob, put_bytes(5, other, bytes(bad)))
        self.assert_json(status, hdr, data, 403)

    def test_concurrent_create_one_200_one_409(self):
        blob = "44" * 32
        env = envelope(26)
        sk, pub = new_key()
        epoch = self.epoch_of(blob)
        body = self.signed_put(sk, blob, epoch, 0, env, pub)
        barrier = threading.Barrier(2)
        results = []
        lock = threading.Lock()

        def worker():
            barrier.wait()
            status, hdr, data = self.call("PUT", "/v2/blob/" + blob, body)
            with lock:
                results.append((status, hdr, data))

        threads = [threading.Thread(target=worker) for _ in range(2)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=10)
            self.assertFalse(thread.is_alive())
        codes = sorted(item[0] for item in results)
        self.assertEqual(codes, [200, 409])
        for status, hdr, data in results:
            obj = self.assert_json(status, hdr, data, status)
            if status == 409:
                self.assertEqual(obj["ver"], 1)
                self.assertEqual(obj["data"], env)
                self.assertEqual(obj["pub"], b64url_encode(pub))
        conn = sqlite3.connect(self.db)
        self.assertEqual(conn.execute("SELECT count(*) FROM blobs").fetchone()[0], 1)
        conn.close()

    def test_concurrent_update_cas(self):
        blob = "45" * 32
        sk, pub, epoch, env, _ = self.create(blob, envelope(22))
        bodies = []
        for length in (40, 48):
            env_n = envelope(length)
            bodies.append(self.signed_put(sk, blob, epoch, 1, env_n))
        barrier = threading.Barrier(2)
        results = []
        lock = threading.Lock()

        def worker(body):
            barrier.wait()
            got = self.call("PUT", "/v2/blob/" + blob, body)
            with lock:
                results.append(got)

        threads = [threading.Thread(target=worker, args=(body,)) for body in bodies]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=10)
            self.assertFalse(thread.is_alive())
        codes = sorted(item[0] for item in results)
        self.assertEqual(codes, [200, 409])
        winner = None
        for status, hdr, data in results:
            obj = self.assert_json(status, hdr, data, status)
            if status == 200:
                self.assertEqual(obj["ver"], 2)
                winner = obj
            else:
                self.assertEqual(obj["ver"], 2)
                self.assertIn(obj["data"], (envelope(40), envelope(48)))
        status, hdr, data = self.call("GET", "/v2/blob/" + blob)
        obj = self.assert_json(status, hdr, data, 200)
        self.assertEqual(obj["ver"], 2)
        self.assertEqual(obj["data"], json.loads([d for s, _, d in results if s == 409][0])["data"])

    def test_replay_same_signature_409(self):
        blob = "55" * 32
        sk, pub, epoch, env, body = self.create(blob)
        status, hdr, data = self.call("PUT", "/v2/blob/" + blob, body)
        obj = self.assert_json(status, hdr, data, 409)
        self.assertEqual(obj["ver"], 1)
        self.assertEqual(obj["data"], env)
        nxt = envelope(36)
        update = self.signed_put(sk, blob, epoch, 1, nxt)
        status, hdr, data = self.call("PUT", "/v2/blob/" + blob, update)
        self.assert_json(status, hdr, data, 200)
        status, hdr, data = self.call("PUT", "/v2/blob/" + blob, update)
        obj = self.assert_json(status, hdr, data, 409)
        self.assertEqual(obj["ver"], 2)
        self.assertEqual(obj["data"], nxt)

    def test_delete_then_replay_and_any_write_410(self):
        blob = "66" * 32
        sk, pub, epoch, env, create_body = self.create(blob)
        msg = sign_message("delete", blob, epoch, 1, env)
        dbody = delete_bytes(1, raw_sign(sk, msg))
        status, hdr, data = self.call("POST", "/v2/blob/" + blob + "/delete", dbody)
        obj = self.assert_json(status, hdr, data, 200)
        self.assertEqual(set(obj), {"epoch", "now"})
        status, hdr, data = self.call("GET", "/v2/blob/" + blob)
        obj = self.assert_json(status, hdr, data, 410)
        self.assertEqual(obj["deletedAt"], self.clock())
        self.assertEqual(set(obj), {"deletedAt", "epoch", "now"})
        conn = sqlite3.connect(self.db)
        self.assertIsNone(conn.execute("SELECT id FROM blobs WHERE id=?", (blob,)).fetchone())
        row = conn.execute(
            "SELECT deleted_at, last_epoch, last_ver FROM deleted WHERE id=?", (blob,)
        ).fetchone()
        conn.close()
        self.assertEqual(tuple(row), (self.clock(), epoch, 1))
        # 重放原首写，验签之前就 410。
        status, hdr, data = self.call("PUT", "/v2/blob/" + blob, create_body)
        obj = self.assert_json(status, hdr, data, 410)
        self.assertEqual(obj["deletedAt"], self.clock())
        # 坏签名同样 410，而不是 403。
        garbage = put_bytes(0, env, b"\x02" * 64, pub)
        status, hdr, data = self.call("PUT", "/v2/blob/" + blob, garbage)
        self.assert_json(status, hdr, data, 410)
        # 旧 PUT、新首写、再次 delete 都是 410。
        update = self.signed_put(sk, blob, epoch, 1, envelope(33))
        status, hdr, data = self.call("PUT", "/v2/blob/" + blob, update)
        self.assert_json(status, hdr, data, 410)
        sk2, pub2 = new_key()
        fresh = self.signed_put(sk2, blob, epoch, 0, envelope(34), pub2)
        status, hdr, data = self.call("PUT", "/v2/blob/" + blob, fresh)
        self.assert_json(status, hdr, data, 410)
        status, hdr, data = self.call("POST", "/v2/blob/" + blob + "/delete", dbody)
        self.assert_json(status, hdr, data, 410)
        other = "67" * 32
        status, hdr, data = self.call("GET", "/v2/blob/" + other)
        self.assert_json(status, hdr, data, 404)

    def test_missing_row_base_nonzero_404(self):
        blob = "68" * 32
        sk, _pub = new_key()
        epoch = self.epoch_of(blob)
        body = self.signed_put(sk, blob, epoch, 1, envelope())
        status, hdr, data = self.call("PUT", "/v2/blob/" + blob, body)
        self.assert_json(status, hdr, data, 404)

    def test_envelope_413_and_max_accepted(self):
        blob_big = "70" * 32
        prefix = "e1." + ("A" * 16) + "."
        too_big = prefix + ("B" * (MAX_ENVELOPE + 1 - len(prefix)))
        self.assertEqual(len(too_big), MAX_ENVELOPE + 1)
        sk, pub = new_key()
        epoch = self.epoch_of(blob_big)
        body = self.signed_put(sk, blob_big, epoch, 0, too_big, pub)
        self.assertLessEqual(len(body), MAX_BODY)
        status, hdr, data = self.call("PUT", "/v2/blob/" + blob_big, body, timeout=10)
        self.assert_json(status, hdr, data, 413)
        status, hdr, data = self.call("GET", "/v2/blob/" + blob_big)
        self.assert_json(status, hdr, data, 404)
        exact = prefix + ("B" * (MAX_ENVELOPE - len(prefix)))
        self.assertEqual(len(exact), MAX_ENVELOPE)
        body = self.signed_put(sk, blob_big, epoch, 0, exact, pub)
        self.assertLessEqual(len(body), MAX_BODY)
        status, hdr, data = self.call("PUT", "/v2/blob/" + blob_big, body, timeout=10)
        obj = self.assert_json(status, hdr, data, 200)
        self.assertEqual(obj["ver"], 1)
        grown = prefix + ("C" * (MAX_ENVELOPE + 1 - len(prefix)))
        update = self.signed_put(sk, blob_big, epoch, 1, grown)
        status, hdr, data = self.call("PUT", "/v2/blob/" + blob_big, update, timeout=10)
        self.assert_json(status, hdr, data, 413)
        status, hdr, data = self.call("GET", "/v2/blob/" + blob_big)
        obj = self.assert_json(status, hdr, data, 200)
        self.assertEqual(obj["data"], exact)

    def test_body_over_limit_413_without_reading(self):
        blob = "71" * 32
        raw = (
            "PUT /v2/blob/%s HTTP/1.1\r\n"
            "Host: localhost\r\n"
            "CF-Connecting-IP: 203.0.113.71\r\n"
            "Content-Length: %d\r\n"
            "\r\n" % (blob, MAX_BODY + 1)
        ).encode("ascii")
        started = time.monotonic()
        sock = socket.create_connection(("127.0.0.1", self.port), timeout=2)
        try:
            sock.sendall(raw)
            sock.settimeout(2)
            data = sock.recv(4096)
        finally:
            sock.close()
        elapsed = time.monotonic() - started
        self.assertLess(elapsed, 2.0)
        self.assertTrue(data.startswith(b"HTTP/1.1 413"), data)
        status, hdr, body = _split_http(data)
        self.assert_json(status, hdr, body, 413)

    def test_missing_content_length_411_and_transfer_encoding_400(self):
        blob = "72" * 32
        raw = (
            "PUT /v2/blob/%s HTTP/1.1\r\n"
            "Host: localhost\r\n"
            "CF-Connecting-IP: 203.0.113.72\r\n"
            "\r\n" % blob
        ).encode("ascii")
        status, hdr, body = _exchange(self.port, raw)
        self.assert_json(status, hdr, body, 411)
        raw = (
            "POST /v2/blob/%s/delete HTTP/1.1\r\n"
            "Host: localhost\r\n"
            "CF-Connecting-IP: 203.0.113.72\r\n"
            "Transfer-Encoding: chunked\r\n"
            "\r\n" % blob
        ).encode("ascii")
        started = time.monotonic()
        status, hdr, body = _exchange(self.port, raw)
        self.assertLess(time.monotonic() - started, 2.0)
        self.assert_json(status, hdr, body, 400)
        status, hdr, data = self.call("GET", "/v2/blob/" + blob)
        self.assert_json(status, hdr, data, 404)

    def test_slow_body_disconnects_within_deadline(self):
        blob = "73" * 32
        raw = (
            "PUT /v2/blob/%s HTTP/1.1\r\n"
            "Host: localhost\r\n"
            "CF-Connecting-IP: 203.0.113.73\r\n"
            "Content-Length: 100\r\n"
            "\r\n" % blob
        ).encode("ascii")
        sock = socket.create_connection(("127.0.0.1", self.port), timeout=2)
        try:
            started = time.monotonic()
            sock.sendall(raw)
            sock.settimeout(DEADLINE_S + 3)
            try:
                data = sock.recv(4096)
            except socket.timeout:
                self.fail("server held the connection past %ss" % (DEADLINE_S + 3))
            elapsed = time.monotonic() - started
        finally:
            sock.close()
        self.assertGreaterEqual(elapsed, DEADLINE_S - 1)
        self.assertLess(elapsed, DEADLINE_S + 3)
        self.assertFalse(data.startswith(b"HTTP/1.1 200"))
        self.assertFalse(data.startswith(b"HTTP/1.1 204"))

    def test_space_count_507_sequential_and_concurrent(self):
        _seed_blobs(self.db, SPACE_LIMIT - 1)
        sk, pub = new_key()
        first = "%064x" % 6000
        second = "%064x" % 6001
        epoch = self.epoch_of(first)
        env = envelope()
        body = self.signed_put(sk, first, epoch, 0, env, pub)
        status, hdr, data = self.call("PUT", "/v2/blob/" + first, body, ip="203.0.113.81")
        self.assert_json(status, hdr, data, 200)
        body2 = self.signed_put(sk, second, epoch, 0, env, pub)
        status, hdr, data = self.call("PUT", "/v2/blob/" + second, body2, ip="203.0.113.82")
        self.assert_json(status, hdr, data, 507)
        conn = sqlite3.connect(self.db)
        self.assertEqual(conn.execute("SELECT count(*) FROM blobs").fetchone()[0], SPACE_LIMIT)
        conn.close()

        self.stop()
        os.remove(self.db)
        for suffix in ("-wal", "-shm"):
            path = self.db + suffix
            if os.path.exists(path):
                os.remove(path)
        self.start()
        _seed_blobs(self.db, SPACE_LIMIT - 1)
        left = "%064x" % 6100
        right = "%064x" % 6101
        epoch = self.epoch_of(left)
        body_l = self.signed_put(sk, left, epoch, 0, env, pub)
        body_r = self.signed_put(sk, right, epoch, 0, env, pub)
        barrier = threading.Barrier(2)
        results = []
        lock = threading.Lock()

        def worker(blob, body, ip):
            barrier.wait()
            got = self.call("PUT", "/v2/blob/" + blob, body, ip=ip)
            with lock:
                results.append(got[0])

        threads = [
            threading.Thread(target=worker, args=(left, body_l, "203.0.113.83")),
            threading.Thread(target=worker, args=(right, body_r, "203.0.113.84")),
        ]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=15)
            self.assertFalse(thread.is_alive())
        self.assertEqual(sorted(results), [200, 507])
        conn = sqlite3.connect(self.db)
        self.assertEqual(conn.execute("SELECT count(*) FROM blobs").fetchone()[0], SPACE_LIMIT)
        conn.close()

    def test_byte_quota_create_80_update_100_concurrent(self):
        sk, pub, epoch, env, _ = self.create("%064x" % 7001, envelope(), ip="203.0.113.90")
        _set_size(self.db, "%064x" % 7001, CREATE_BYTE_LIMIT)
        other = "%064x" % 7002
        body = self.signed_put(sk, other, epoch, 0, envelope(), pub)
        status, hdr, data = self.call("PUT", "/v2/blob/" + other, body, ip="203.0.113.91")
        self.assert_json(status, hdr, data, 507)
        _set_size(self.db, "%064x" % 7001, CREATE_BYTE_LIMIT - 1)
        status, hdr, data = self.call("PUT", "/v2/blob/" + other, body, ip="203.0.113.91")
        self.assert_json(status, hdr, data, 200)

        self.stop()
        os.remove(self.db)
        for suffix in ("-wal", "-shm"):
            path = self.db + suffix
            if os.path.exists(path):
                os.remove(path)
        self.start()
        anchor = "%064x" % 7100
        left = "%064x" % 7101
        right = "%064x" % 7102
        sk, pub, epoch, _, _ = self.create(anchor, envelope(), ip="203.0.113.92")
        sk_l, pub_l, _, env_l, _ = self.create(left, envelope(), ip="203.0.113.93")
        sk_r, pub_r, _, env_r, _ = self.create(right, envelope(), ip="203.0.113.94")
        self.assertEqual(len(env_l), 42)
        # 总量 = T-50；只更新 left，增长 50，落在上限上，允许。
        _set_size(self.db, anchor, UPDATE_BYTE_LIMIT - 50 - len(env_l) - len(env_r))
        grown = envelope(22 + 50)
        self.assertEqual(len(grown), len(env_l) + 50)
        body = self.signed_put(sk_l, left, epoch, 1, grown)
        status, hdr, data = self.call("PUT", "/v2/blob/" + left, body, ip="203.0.113.93")
        self.assert_json(status, hdr, data, 200)
        conn = sqlite3.connect(self.db)
        total = int(conn.execute("SELECT total(size) FROM blobs").fetchone()[0])
        conn.close()
        self.assertEqual(total, UPDATE_BYTE_LIMIT)
        # 再涨 1 字节，507，且原行不变。
        bigger = envelope(22 + 51)
        # left 当前长度是 92。bigger 相对当前是 +1。从上限再 +1。
        self.assertEqual(len(bigger) - len(grown), 1)
        body = self.signed_put(sk_l, left, epoch, 2, bigger)
        status, hdr, data = self.call("PUT", "/v2/blob/" + left, body, ip="203.0.113.93")
        self.assert_json(status, hdr, data, 507)
        status, hdr, data = self.call("GET", "/v2/blob/" + left)
        obj = self.assert_json(status, hdr, data, 200)
        self.assertEqual(obj["data"], grown)

        # 并发：起点总量 T-100，两边各 +80，只能成一个。
        self.stop()
        os.remove(self.db)
        for suffix in ("-wal", "-shm"):
            path = self.db + suffix
            if os.path.exists(path):
                os.remove(path)
        self.start()
        anchor = "%064x" % 7200
        left = "%064x" % 7201
        right = "%064x" % 7202
        sk, pub, epoch, _, _ = self.create(anchor, envelope(), ip="203.0.113.95")
        sk_l, _, _, env_l, _ = self.create(left, envelope(), ip="203.0.113.96")
        sk_r, _, _, env_r, _ = self.create(right, envelope(), ip="203.0.113.97")
        _set_size(self.db, anchor, UPDATE_BYTE_LIMIT - 100 - len(env_l) - len(env_r))
        up_l = envelope(22 + 80)
        up_r = envelope(22 + 80)
        body_l = self.signed_put(sk_l, left, epoch, 1, up_l)
        body_r = self.signed_put(sk_r, right, epoch, 1, up_r)
        barrier = threading.Barrier(2)
        results = []
        lock = threading.Lock()

        def worker(blob, body, ip):
            barrier.wait()
            got = self.call("PUT", "/v2/blob/" + blob, body, ip=ip)
            with lock:
                results.append(got[0])

        threads = [
            threading.Thread(target=worker, args=(left, body_l, "203.0.113.96")),
            threading.Thread(target=worker, args=(right, body_r, "203.0.113.97")),
        ]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=15)
            self.assertFalse(thread.is_alive())
        self.assertEqual(sorted(results), [200, 507])
        conn = sqlite3.connect(self.db)
        total = int(conn.execute("SELECT total(size) FROM blobs").fetchone()[0])
        conn.close()
        self.assertLessEqual(total, UPDATE_BYTE_LIMIT)
        self.assertGreater(total, UPDATE_BYTE_LIMIT - 100)

    def test_create_rate_hour_persists_across_restart_and_day_and_global(self):
        env = envelope()
        sk, pub = new_key()
        ids = ["%064x" % (8000 + i) for i in range(4)]
        epoch = self.epoch_of(ids[0])
        for i in range(3):
            body = self.signed_put(sk, ids[i], epoch, 0, env, pub)
            status, hdr, data = self.call("PUT", "/v2/blob/" + ids[i], body, ip="203.0.113.110")
            self.assert_json(status, hdr, data, 200)
        body = self.signed_put(sk, ids[3], epoch, 0, env, pub)
        status, hdr, data = self.call("PUT", "/v2/blob/" + ids[3], body, ip="203.0.113.110")
        obj = self.assert_json(status, hdr, data, 429)
        self.assertRegex(hdr["retry-after"], r"^[1-9][0-9]*$")
        self.assertEqual(int(hdr["retry-after"]), 3600)
        conn = sqlite3.connect(self.db)
        self.assertEqual(conn.execute("SELECT count(*) FROM creations").fetchone()[0], 3)
        conn.close()
        self.stop()
        self.start()
        status, hdr, data = self.call("GET", "/v2/blob/" + ids[3])
        obj = self.assert_json(status, hdr, data, 404)
        self.assertEqual(obj["epoch"], epoch)
        status, hdr, data = self.call("PUT", "/v2/blob/" + ids[3], body, ip="203.0.113.110")
        self.assert_json(status, hdr, data, 429)
        conn = sqlite3.connect(self.db)
        conn.execute("DELETE FROM creations")
        conn.commit()
        conn.close()
        status, hdr, data = self.call("PUT", "/v2/blob/" + ids[3], body, ip="203.0.113.110")
        self.assert_json(status, hdr, data, 200)

        # 滑动 3600 秒窗口：恰好到期后可以再创建。
        self.clock.advance(3_600_000)
        nxt = "%064x" % 8100
        body = self.signed_put(sk, nxt, epoch, 0, env, pub)
        status, hdr, data = self.call("PUT", "/v2/blob/" + nxt, body, ip="203.0.113.110")
        self.assert_json(status, hdr, data, 200)

    def test_create_rate_per_ip_day_and_global_day(self):
        # 每小时最多 3 次，所以每 3600 秒再来一组，凑满自然日 10 次。
        env = envelope()
        sk, pub = new_key()
        epoch = None
        made = 0
        ip = "203.0.113.120"
        while made < 10:
            batch = 3 if made < 9 else 1
            for _ in range(batch):
                blob = "%064x" % (8200 + made)
                if epoch is None:
                    epoch = self.epoch_of(blob)
                body = self.signed_put(sk, blob, epoch, 0, env, pub)
                status, hdr, data = self.call("PUT", "/v2/blob/" + blob, body, ip=ip)
                self.assert_json(status, hdr, data, 200)
                made += 1
            if made < 10:
                self.clock.advance(3_600_000)
        blob = "%064x" % 8299
        body = self.signed_put(sk, blob, epoch, 0, env, pub)
        status, hdr, data = self.call("PUT", "/v2/blob/" + blob, body, ip=ip)
        obj = self.assert_json(status, hdr, data, 429)
        retry = int(hdr["retry-after"])
        self.assertGreater(retry, 3600)
        self.assertEqual(retry, _seconds_until_utc_midnight(self.clock()))
        # 换 IP 仍受全局 50 次约束。先补到 50。
        for n in range(40):
            blob = "%064x" % (8300 + n)
            ip_n = "203.0.113.%d" % (130 + (n % 20))
            # 每个 IP 在本小时窗口最多再放 3 个；130-149 都是新 IP。
            body = self.signed_put(sk, blob, epoch, 0, env, pub)
            status, hdr, data = self.call("PUT", "/v2/blob/" + blob, body, ip=ip_n)
            self.assert_json(status, hdr, data, 200, )
        blob = "%064x" % 8399
        body = self.signed_put(sk, blob, epoch, 0, env, pub)
        status, hdr, data = self.call(
            "PUT", "/v2/blob/" + blob, body, ip="198.51.100.9"
        )
        self.assert_json(status, hdr, data, 429)
        self.assertEqual(int(hdr["retry-after"]), _seconds_until_utc_midnight(self.clock()))

    def test_rate_429_ip_sigfail_space_and_global(self):
        blob = "90" * 32
        for _ in range(120):
            status, hdr, data = self.call("GET", "/v2/blob/" + blob, ip="203.0.113.140")
            self.assert_json(status, hdr, data, 404)
        status, hdr, data = self.call("GET", "/v2/blob/" + blob, ip="203.0.113.140")
        self.assert_json(status, hdr, data, 429)
        self.assertRegex(hdr["retry-after"], r"^[1-9][0-9]*$")
        self.clock.advance(60_000)
        status, hdr, data = self.call("GET", "/v2/blob/" + blob, ip="203.0.113.140")
        self.assert_json(status, hdr, data, 404)

        sk, pub = new_key()
        epoch = self.epoch_of("91" * 32)
        env = envelope()
        for i in range(5):
            msg = sign_message("put", "91" * 32, epoch, 0, env)
            sig = bytearray(raw_sign(sk, msg))
            sig[i] ^= 0x01
            body = put_bytes(0, env, bytes(sig), pub)
            status, hdr, data = self.call("PUT", "/v2/blob/" + ("91" * 32), body, ip="203.0.113.141")
            self.assert_json(status, hdr, data, 403)
        msg = sign_message("put", "91" * 32, epoch, 0, env)
        sig = bytearray(raw_sign(sk, msg))
        sig[0] ^= 0x02
        body = put_bytes(0, env, bytes(sig), pub)
        status, hdr, data = self.call("PUT", "/v2/blob/" + ("91" * 32), body, ip="203.0.113.141")
        self.assert_json(status, hdr, data, 429)
        # 验签失败不占用空间成功写入额度，合法创建仍然成功。
        body = self.signed_put(sk, "91" * 32, epoch, 0, env, pub)
        status, hdr, data = self.call("PUT", "/v2/blob/" + ("91" * 32), body, ip="203.0.113.141")
        self.assert_json(status, hdr, data, 200)

        # 每空间成功写入 30 次，第 31 次 429。
        sk, pub, epoch, env, _ = self.create("92" * 32, ip="203.0.113.142")
        for ver in range(1, 30):
            env_n = envelope(22 + ver)
            body = self.signed_put(sk, "92" * 32, epoch, ver, env_n)
            status, hdr, data = self.call("PUT", "/v2/blob/" + ("92" * 32), body, ip="203.0.113.142")
            self.assert_json(status, hdr, data, 200)
        env_n = envelope(80)
        body = self.signed_put(sk, "92" * 32, epoch, 30, env_n)
        status, hdr, data = self.call("PUT", "/v2/blob/" + ("92" * 32), body, ip="203.0.113.142")
        self.assert_json(status, hdr, data, 429)
        self.clock.advance(60_000)
        status, hdr, data = self.call("PUT", "/v2/blob/" + ("92" * 32), body, ip="203.0.113.142")
        self.assert_json(status, hdr, data, 200)

    def test_global_write_limit_429(self):
        env = envelope()
        sk, pub = new_key()
        epoch = self.epoch_of("%064x" % 9000)
        writes = 0
        for space in range(20):
            blob = "%064x" % (9000 + space)
            ip = "198.51.100.%d" % (10 + space)
            body = self.signed_put(sk, blob, epoch, 0, env, pub)
            status, hdr, data = self.call("PUT", "/v2/blob/" + blob, body, ip=ip)
            self.assert_json(status, hdr, data, 200)
            writes += 1
            current = env
            for ver in range(1, 30):
                nxt = envelope(22 + (ver % 20))
                body = self.signed_put(sk, blob, epoch, ver, nxt)
                status, hdr, data = self.call("PUT", "/v2/blob/" + blob, body, ip=ip)
                self.assert_json(status, hdr, data, 200)
                writes += 1
                current = nxt
            self.assertEqual(len(current), len(envelope(22 + (29 % 20))))
        self.assertEqual(writes, 600)
        blob = "%064x" % 9099
        body = self.signed_put(sk, blob, epoch, 0, env, pub)
        status, hdr, data = self.call("PUT", "/v2/blob/" + blob, body, ip="198.51.100.200")
        self.assert_json(status, hdr, data, 429)
        self.assertRegex(hdr["retry-after"], r"^[1-9][0-9]*$")

    def test_cf_connecting_ip_only(self):
        blob = "a1" * 32
        status, hdr, data = self.call("GET", "/v2/blob/" + blob, omit_ip=True)
        self.assert_json(status, hdr, data, 400)
        status, hdr, data = self.call(
            "GET",
            "/v2/blob/" + blob,
            omit_ip=True,
            headers={"X-Forwarded-For": "198.51.100.9"},
        )
        self.assert_json(status, hdr, data, 400)
        status, hdr, data = self.call(
            "GET", "/v2/blob/" + blob, ip="999.1.1.1"
        )
        self.assert_json(status, hdr, data, 400)
        status, hdr, data = self.call("GET", "/v2/blob/" + blob, ip="01.2.3.4")
        self.assert_json(status, hdr, data, 400)
        raw = (
            "GET /v2/blob/%s HTTP/1.1\r\n"
            "Host: localhost\r\n"
            "CF-Connecting-IP: 203.0.113.10\r\n"
            "CF-Connecting-IP: 203.0.113.10\r\n"
            "\r\n" % blob
        ).encode("ascii")
        status, hdr, body = _exchange(self.port, raw)
        self.assert_json(status, hdr, body, 400)
        raw = (
            "GET /v2/blob/%s HTTP/1.1\r\n"
            "Host: localhost\r\n"
            "cf-connecting-ip: 203.0.113.50\r\n"
            "X-Forwarded-For: 198.51.100.9\r\n"
            "\r\n" % blob
        ).encode("ascii")
        status, hdr, body = _exchange(self.port, raw)
        self.assert_json(status, hdr, body, 404)
        # 创建频率只看 CF-Connecting-IP。同一 XFF、不同 CF 不共享额度。
        env = envelope()
        sk, pub = new_key()
        epoch = self.epoch_of("%064x" % 8400)
        for i in range(3):
            blob_i = "%064x" % (8400 + i)
            body = self.signed_put(sk, blob_i, epoch, 0, env, pub)
            status, hdr, data = self.call(
                "PUT",
                "/v2/blob/" + blob_i,
                body,
                ip="203.0.113.60",
                headers={"X-Forwarded-For": "198.51.100.60"},
            )
            self.assert_json(status, hdr, data, 200)
        blob_i = "%064x" % 8403
        body = self.signed_put(sk, blob_i, epoch, 0, env, pub)
        status, hdr, data = self.call(
            "PUT",
            "/v2/blob/" + blob_i,
            body,
            ip="203.0.113.60",
            headers={"X-Forwarded-For": "198.51.100.61"},
        )
        self.assert_json(status, hdr, data, 429)
        blob_i = "%064x" % 8404
        body = self.signed_put(sk, blob_i, epoch, 0, env, pub)
        status, hdr, data = self.call(
            "PUT",
            "/v2/blob/" + blob_i,
            body,
            ip="203.0.113.61",
            headers={"X-Forwarded-For": "203.0.113.60"},
        )
        self.assert_json(status, hdr, data, 200)

    def test_ipv6_bucket_is_slash_64(self):
        env = envelope()
        sk, pub = new_key()
        epoch = self.epoch_of("%064x" % 8500)
        addrs = ["2001:db8:1:2::1", "2001:db8:1:2::2", "2001:db8:1:2::abcd"]
        for i, addr in enumerate(addrs):
            blob = "%064x" % (8500 + i)
            body = self.signed_put(sk, blob, epoch, 0, env, pub)
            status, hdr, data = self.call("PUT", "/v2/blob/" + blob, body, ip=addr)
            self.assert_json(status, hdr, data, 200)
        blob = "%064x" % 8503
        body = self.signed_put(sk, blob, epoch, 0, env, pub)
        status, hdr, data = self.call("PUT", "/v2/blob/" + blob, body, ip="2001:db8:1:2::ffff")
        self.assert_json(status, hdr, data, 429)
        blob = "%064x" % 8504
        body = self.signed_put(sk, blob, epoch, 0, env, pub)
        status, hdr, data = self.call("PUT", "/v2/blob/" + blob, body, ip="2001:db8:1:3::1")
        self.assert_json(status, hdr, data, 200)

    def test_log_has_prefix_only_grep(self):
        blob = "abcdef01" * 8
        self.assertEqual(len(blob), 64)
        env = envelope(25)
        sk, pub, epoch, env, body = self.create(blob, env, ip="203.0.113.77")
        sig = json.loads(body.decode("utf-8"))["sig"]
        pub_text = b64url_encode(pub)
        buried = "/v2/blob/" + blob + "0"
        status, hdr, data = self.call("GET", buried, ip="2001:db8:85a3:1234::5")
        self.assert_json(status, hdr, data, 400)
        text = "\n".join(self.srv.access_log) + "\n"
        path = os.path.join(self.tmp.name, "access.log")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text)
        self.assertEqual(_grep(blob, path), 1)
        self.assertEqual(_grep(sig, path), 1)
        self.assertEqual(_grep(pub_text, path), 1)
        self.assertEqual(_grep(env, path), 1)
        self.assertEqual(_grep("203.0.113.77", path), 1)
        self.assertEqual(_grep("2001:db8:85a3:1234::5", path), 1)
        self.assertEqual(_grep(blob[:8], path), 0)
        self.assertEqual(_grep("/v2/blob/:id", path), 0)
        self.assertEqual(_grep("203.0.113.0/24", path), 0)
        self.assertEqual(_grep("2001:db8:85a3::/48", path), 0)
        self.assertNotIn("198.51.100", text)

    def test_no_enumeration(self):
        blob = "a2" * 32
        self.create(blob)
        for path in (
            "/",
            "/v2",
            "/v2/",
            "/v2/blob",
            "/v2/blobs",
            "/v2/list",
            "/v2/blob/" + blob + "/list",
        ):
            status, hdr, data = self.call("GET", path)
            obj = self.assert_json(status, hdr, data, 404)
            self.assertNotIn(blob, data.decode("utf-8"))
            self.assertNotIn("data", obj)
        status, hdr, data = self.call("DELETE", "/v2/blob/" + blob)
        self.assert_json(status, hdr, data, 405)
        upper = "A" * 64
        status, hdr, data = self.call("GET", "/v2/blob/" + upper)
        self.assert_json(status, hdr, data, 400)
        status, hdr, data = self.call("GET", "/v2/blob/" + ("ab" * 31))
        self.assert_json(status, hdr, data, 400)

    def test_headers_over_8kib_400(self):
        blob = "a3" * 32
        pad = "x" * (HEADER_LIMIT + 100)
        raw = (
            "GET /v2/blob/%s HTTP/1.1\r\n"
            "Host: localhost\r\n"
            "CF-Connecting-IP: 203.0.113.10\r\n"
            "X-Pad: %s\r\n"
            "\r\n" % (blob, pad)
        ).encode("ascii")
        self.assertGreater(len(raw), HEADER_LIMIT)
        status, hdr, body = _exchange(self.port, raw)
        self.assertEqual(status, 400)
        self.assert_json(status, hdr, body, 400)

    def test_base_range_and_noncanonical_pub(self):
        blob = "a4" * 32
        sk, pub = new_key()
        epoch = self.epoch_of(blob)
        env = envelope()
        msg = sign_message("put", blob, epoch, 0, env)
        sig = raw_sign(sk, msg)
        pub_text = b64url_encode(pub)
        alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        last = alphabet.index(pub_text[-1])
        self.assertEqual(last & 0x03, 0)
        bad_pub = pub_text[:-1] + alphabet[last + 1]
        raw = json.dumps(
            {"base": 0, "data": env, "sig": b64url_encode(sig), "pub": bad_pub},
            separators=(",", ":"),
        ).encode("utf-8")
        status, hdr, data = self.call("PUT", "/v2/blob/" + blob, raw)
        self.assert_json(status, hdr, data, 400)
        too_far = put_bytes((1 << 53) - 1, env, sig, pub)
        status, hdr, data = self.call("PUT", "/v2/blob/" + blob, too_far)
        self.assert_json(status, hdr, data, 400)
        status, hdr, data = self.call(
            "PUT",
            "/v2/blob/" + blob,
            b'{"base": true, "data": "x", "sig": "aa", "pub": "bb"}',
        )
        self.assert_json(status, hdr, data, 400)

    def test_connection_cap_32(self):
        held = []
        try:
            for _ in range(32):
                sock = socket.create_connection(("127.0.0.1", self.port), timeout=2)
                held.append(sock)
            deadline = time.time() + 2
            while time.time() < deadline and self.srv.active_count() < 32:
                time.sleep(0.01)
            self.assertGreaterEqual(self.srv.active_count(), 32)
            extra = socket.create_connection(("127.0.0.1", self.port), timeout=2)
            try:
                extra.settimeout(2)
                data = extra.recv(128)
                self.assertEqual(data, b"")
            finally:
                extra.close()
            self.assertGreaterEqual(self.srv.active_count(), 32)
        finally:
            for sock in held:
                try:
                    sock.close()
                except OSError:
                    pass

    def test_rotate_epoch_rejects_old_signatures(self):
        blob = "a5" * 32
        sk, pub, epoch, env, _ = self.create(blob)
        new_epoch = rotate_epoch.rotate(self.db)
        self.assertRegex(new_epoch, r"^[0-9a-f]{32}$")
        self.assertNotEqual(new_epoch, epoch)
        status, hdr, data = self.call("GET", "/v2/blob/" + blob)
        obj = self.assert_json(status, hdr, data, 200)
        self.assertEqual(obj["epoch"], new_epoch)
        stale = self.signed_put(sk, blob, epoch, 1, envelope(30))
        status, hdr, data = self.call("PUT", "/v2/blob/" + blob, stale)
        self.assert_json(status, hdr, data, 403)
        fresh = self.signed_put(sk, blob, new_epoch, 1, envelope(30))
        status, hdr, data = self.call("PUT", "/v2/blob/" + blob, fresh)
        self.assert_json(status, hdr, data, 200)

    def test_backup_snapshot_mode_and_retention(self):
        blob = "a6" * 32
        self.create(blob)
        dest = os.path.join(self.tmp.name, "backups")
        os.makedirs(dest, 0o755)
        old = os.path.join(dest, "sync-old.sqlite")
        recent = os.path.join(dest, "sync-recent.sqlite")
        note = os.path.join(dest, "keep.txt")
        for path in (old, recent):
            with open(path, "wb") as handle:
                handle.write(b"x")
        with open(note, "w", encoding="utf-8") as handle:
            handle.write("keep\n")
        old_time = time.time() - 8 * 24 * 3600
        recent_time = time.time() - 24 * 3600
        os.utime(old, (old_time, old_time))
        os.utime(recent, (recent_time, recent_time))
        snap = backup.backup(self.db, dest)
        self.assertEqual(os.stat(snap).st_mode & 0o777, 0o600)
        self.assertEqual(os.stat(dest).st_mode & 0o777, 0o700)
        self.assertFalse(os.path.exists(old))
        self.assertTrue(os.path.exists(recent))
        self.assertTrue(os.path.exists(note))
        conn = sqlite3.connect("file:%s?mode=ro" % snap, uri=True)
        row = conn.execute("SELECT id FROM blobs WHERE id=?", (blob,)).fetchone()
        epoch = conn.execute("SELECT value FROM meta WHERE key='epoch'").fetchone()[0]
        conn.close()
        self.assertEqual(row[0], blob)
        self.assertRegex(epoch, r"^[0-9a-f]{32}$")
        names = [name for name in os.listdir(dest) if name.startswith("sync-") and name.endswith(".sqlite")]
        self.assertIn(os.path.basename(snap), names)
        self.assertLessEqual(len(names), 3)

    def test_healthcheck_alerts_at_70_percent(self):
        blob = "%064x" % 8600
        self.create(blob, ip="203.0.113.150")
        code = healthcheck.main(["--db", self.db])
        self.assertEqual(code, 0)
        _set_size(self.db, blob, app.HEALTH_BYTE_LIMIT)
        code = healthcheck.main(["--db", self.db])
        self.assertEqual(code, 1)
        _set_size(self.db, blob, app.HEALTH_BYTE_LIMIT - 1)
        code = healthcheck.main(["--db", self.db])
        self.assertEqual(code, 0)


def _seed_blobs(db, count):
    env = envelope()
    conn = sqlite3.connect(db, timeout=5)
    conn.execute("BEGIN")
    rows = []
    for i in range(1, count + 1):
        rows.append(("%064x" % i, 1, b"\x04" + (b"\x11" * 64), env, len(env), 0, 0))
    conn.executemany(
        "INSERT INTO blobs(id, ver, pub, data, size, created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
        rows,
    )
    conn.commit()
    conn.close()


def _set_size(db, blob, size):
    conn = sqlite3.connect(db, timeout=5)
    conn.execute("PRAGMA busy_timeout=5000")
    conn.execute("UPDATE blobs SET size=? WHERE id=?", (size, blob))
    conn.commit()
    conn.close()


def _seconds_until_utc_midnight(now_ms):
    sec = now_ms // 1000
    dt = datetime.datetime.fromtimestamp(sec, datetime.timezone.utc)
    start = dt.replace(hour=0, minute=0, second=0, microsecond=0)
    nxt = start + datetime.timedelta(days=1)
    remain_ms = int(nxt.timestamp() * 1000) - now_ms
    return max(1, (remain_ms + 999) // 1000)


def _grep(needle, path):
    result = subprocess.run(
        ["grep", "-F", "-e", needle, path],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    return result.returncode


def _exchange(port, payload, timeout=3):
    data = b""
    sock = socket.create_connection(("127.0.0.1", port), timeout=timeout)
    try:
        sock.settimeout(timeout)
        sock.sendall(payload)
        chunks = []
        while True:
            try:
                piece = sock.recv(65536)
            except socket.timeout:
                break
            if not piece:
                break
            chunks.append(piece)
        data = b"".join(chunks)
    finally:
        sock.close()
    return _split_http(data)


def _split_http(raw):
    head, _, body = raw.partition(b"\r\n\r\n")
    lines = head.decode("iso-8859-1").split("\r\n")
    status = int(lines[0].split(" ", 2)[1])
    headers = {}
    for line in lines[1:]:
        if not line or ":" not in line:
            continue
        key, value = line.split(":", 1)
        headers[key.lower().strip()] = value.strip()
    return status, headers, body


if __name__ == "__main__":
    unittest.main()
