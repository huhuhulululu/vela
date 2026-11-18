"""serve-test-stream.py 的单元测试：python3 -I scripts/test_serve_test_stream.py"""
import importlib.util
import json
import os
import re
import signal
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.error

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("serve_test_stream", os.path.join(HERE, "serve-test-stream.py"))
sts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sts)

N = 4


def make_stream():
    d = tempfile.mkdtemp(prefix="vela-fix3-stream.")
    with open(os.path.join(d, "master.m3u8"), "w") as f:
        f.write("#EXTM3U\n")
    with open(os.path.join(d, "v.m3u8"), "w") as f:
        f.write("#EXTM3U\n#EXT-X-TARGETDURATION:2\n")
        for i in range(N):
            f.write("#EXTINF:2.0,\nseg%d.ts\n" % i)
    return sts.Stream(d), d


def playlist_at(stream, first):
    """让墙钟刚好落在窗口起点 first（head = first + WINDOW）。"""
    stream.t0 = time.time() - (first + 0.5) * 2.0
    text = stream.live_playlist("v.m3u8").decode()
    seq = int(re.search(r"DISCONTINUITY-SEQUENCE:(\d+)", text).group(1))
    media = int(re.search(r"MEDIA-SEQUENCE:(\d+)", text).group(1))
    return media, seq, text.count("#EXT-X-DISCONTINUITY\n")


class DiscontinuitySequence(unittest.TestCase):
    def setUp(self):
        self.stream, self.dir = make_stream()

    def expected_seq(self, first):
        # 窗口之外已移出的 discontinuity 标签数（标签在序号为 N 的整数倍的分片之前，k>0）
        return len([k for k in range(1, first) if k % N == 0])

    def test_wrap_boundaries(self):
        for first in (N - 1, N, N + 1):
            media, seq, tags = playlist_at(self.stream, first)
            self.assertEqual(media, first)
            self.assertEqual(seq, self.expected_seq(first), "first=%d" % first)

    def test_first_equals_n_tag_in_window_not_counted(self):
        _, seq, tags = playlist_at(self.stream, N)
        self.assertEqual((seq, tags), (0, 1))

    def test_overlap_segment_consistent(self):
        # 相邻两次拉取的重叠分片：同一分片之前的 discontinuity 绝对序号必须一致
        def abs_index(first):
            _, seq, _ = playlist_at(self.stream, first)
            text = self.stream.live_playlist("v.m3u8").decode()
            idx, out, cur = first, {}, seq
            for line in text.splitlines():
                if line == "#EXT-X-DISCONTINUITY":
                    cur += 1
                elif line.startswith("seg"):
                    out[idx] = cur  # 该分片所属的 discontinuity 绝对序号
                    idx += 1
            return out
        for first in (N - 2, N - 1, N):
            a, b = abs_index(first), abs_index(first + 1)
            for k in set(a) & set(b):
                self.assertEqual(a[k], b[k], "分片 %d (first=%d)" % (k, first))


class FlagDefaults(unittest.TestCase):
    """新开关默认全关；旧的两参数用法仍能解析。"""

    def test_parse_minimal_keeps_flags_off(self):
        opts = sts.parse_args(["serve-test-stream.py", "/tmp/stream", "--port-file", "/tmp/port"])
        self.assertIsNone(opts.catalog)
        self.assertFalse(opts.ll_parts)
        self.assertIsNone(opts.expire_idle)
        self.assertFalse(opts.rewind)
        self.assertFalse(opts.rotate_uri)
        self.assertFalse(opts.challenge)
        self.assertIsNone(opts.request_log)

    def test_usage_rejects_unknown_and_incomplete(self):
        self.assertEqual(sts.main(["serve-test-stream.py"]), 2)
        self.assertEqual(sts.main(["serve-test-stream.py", "/tmp/s", "--port-file", "/tmp/p", "--nope"]), 2)
        self.assertEqual(sts.main(["serve-test-stream.py", "/tmp/s", "--port-file", "/tmp/p", "--expire-idle"]), 2)
        self.assertEqual(sts.main(["serve-test-stream.py", "/tmp/s", "--port-file", "/tmp/p", "--expire-idle", "0"]), 2)
        self.assertEqual(sts.main(["serve-test-stream.py", "/tmp/s", "--port-file", "/tmp/p", "--expire-idle", "abc"]), 2)
        self.assertEqual(sts.main(["serve-test-stream.py", "/tmp/s", "--port-file", "/tmp/p", "--request-log"]), 2)

    def test_parse_accepts_each_switch(self):
        opts = sts.parse_args([
            "serve-test-stream.py", "/tmp/stream", "--port-file", "/tmp/port",
            "--catalog-fixtures", "/tmp/fixtures",
            "--ll-parts", "--expire-idle", "35", "--rewind", "--rotate-uri", "--challenge",
            "--request-log", "/tmp/req.jsonl",
        ])
        self.assertEqual(opts.catalog, "/tmp/fixtures")
        self.assertTrue(opts.ll_parts)
        self.assertEqual(opts.expire_idle, 35)
        self.assertTrue(opts.rewind)
        self.assertTrue(opts.rotate_uri)
        self.assertTrue(opts.challenge)
        self.assertEqual(opts.request_log, "/tmp/req.jsonl")

    def test_catalog_flag_without_path_uses_repo_fixtures(self):
        opts = sts.parse_args([
            "serve-test-stream.py", "/tmp/stream", "--port-file", "/tmp/port", "--catalog-fixtures",
        ])
        self.assertTrue(opts.catalog.endswith(os.path.join("android", "tools", "fixtures")))
        self.assertTrue(os.path.isfile(os.path.join(opts.catalog, "catalog.json")))


def _frozen(stream, at):
    stream.clock = lambda: at


class LowLatencyParts(unittest.TestCase):
    def setUp(self):
        self.stream, self.dir = make_stream()
        _frozen(self.stream, self.stream.t0 + (N + 0.5) * 2.0)

    def test_off_by_default(self):
        text = self.stream.live_playlist("v.m3u8").decode()
        self.assertNotIn("#EXT-X-PART:", text)
        self.assertNotIn("#EXT-X-SERVER-CONTROL", text)
        self.assertNotIn("#EXT-X-PRELOAD-HINT", text)

    def test_on_keeps_full_segments_and_adds_parts(self):
        plain = self.stream.live_playlist("v.m3u8").decode()
        self.stream.ll_parts = True
        text = self.stream.live_playlist("v.m3u8").decode()
        self.assertIn("#EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES", text)
        self.assertIn("#EXT-X-PART:", text)
        self.assertIn("#EXT-X-PRELOAD-HINT:TYPE=PART,", text)
        self.assertEqual(text.count("#EXTINF:"), plain.count("#EXTINF:"))
        self.assertEqual(
            re.search(r"MEDIA-SEQUENCE:(\d+)", text).group(1),
            re.search(r"MEDIA-SEQUENCE:(\d+)", plain).group(1),
        )
        # 分片地址仍指向已有的文件，查询由服务器去掉
        part = re.search(r'#EXT-X-PART:[^\n]*URI="([^"]+)"', text)
        self.assertIsNotNone(part)
        self.assertTrue(part.group(1).split("?")[0].startswith("seg"))


class RewindAndRotate(unittest.TestCase):
    def setUp(self):
        self.stream, self.dir = make_stream()
        # first = 20，窗口离 0 足够远，回退后序号仍为非负
        _frozen(self.stream, self.stream.t0 + (20 + 0.5) * 2.0)

    def _seq_and_last(self, text):
        seq = int(re.search(r"MEDIA-SEQUENCE:(\d+)", text).group(1))
        last = [ln for ln in text.splitlines() if ln and not ln.startswith("#")][-1]
        return seq, last

    def test_rewind_is_one_shot_and_marks_discontinuity(self):
        self.stream.rewind_enabled = True
        before = self.stream.live_playlist("v.m3u8").decode()
        self.assertTrue(self.stream.arm_rewind())
        mid = self.stream.live_playlist("v.m3u8").decode()
        after = self.stream.live_playlist("v.m3u8").decode()
        bseq, _ = self._seq_and_last(before)
        mseq, _ = self._seq_and_last(mid)
        aseq, _ = self._seq_and_last(after)
        self.assertLess(mseq, bseq)
        # 必须是独立的一行；#EXT-X-DISCONTINUITY-SEQUENCE 不算
        self.assertIn("#EXT-X-DISCONTINUITY", mid.splitlines())
        self.assertEqual(aseq, bseq)

    def test_rewind_adds_standalone_discontinuity_despite_sequence_tag(self):
        body = ("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:20\n"
                "#EXT-X-DISCONTINUITY-SEQUENCE:0\n#EXTINF:2.0,\nseg_0_020.ts\n"
                "#EXTINF:2.0,\nseg_0_021.ts\n#EXTINF:2.0,\nseg_0_022.ts\n").encode()
        out = self.stream._rewound(body).decode()
        self.assertIn("#EXT-X-DISCONTINUITY", out.splitlines())
        self.assertLess(self._seq_and_last(out)[0], 20)

    def test_rewind_disabled_cannot_arm(self):
        self.assertFalse(self.stream.arm_rewind())
        first = self.stream.live_playlist("v.m3u8").decode()
        second = self.stream.live_playlist("v.m3u8").decode()
        self.assertEqual(self._seq_and_last(first), self._seq_and_last(second))

    def test_rotate_changes_only_last_uri(self):
        self.stream.rotate_enabled = True
        before = self.stream.live_playlist("v.m3u8").decode()
        self.assertTrue(self.stream.arm_rotate())
        mid = self.stream.live_playlist("v.m3u8").decode()
        after = self.stream.live_playlist("v.m3u8").decode()
        bseq, blast = self._seq_and_last(before)
        mseq, mlast = self._seq_and_last(mid)
        aseq, alast = self._seq_and_last(after)
        self.assertEqual(mseq, bseq)
        self.assertNotEqual(mlast, blast)
        self.assertIn("?rot=", mlast)
        self.assertEqual(mlast.split("?")[0], blast.split("?")[0])
        self.assertEqual(alast, blast)
        self.assertEqual(aseq, bseq)


class SessionIdle(unittest.TestCase):
    def test_idle_then_expire_and_new_session_works(self):
        book = sts.SessionBook()
        book.idle = 35
        now = 1000.0
        sid = book.issue(now)
        self.assertTrue(book.allow(sid, now + 34.9))
        self.assertFalse(book.allow(sid, now + 34.9 + 35))
        self.assertFalse(book.allow(sid, now + 34.9 + 35 + 1))
        fresh = book.issue(now + 100)
        self.assertTrue(book.allow(fresh, now + 100))
        self.assertNotEqual(fresh, sid)

    def test_disabled_book_allows_anything(self):
        book = sts.SessionBook()
        self.assertIsNone(book.idle)
        self.assertTrue(book.allow(None, 0))
        self.assertTrue(book.allow("missing", 0))

    def test_master_rewrite_adds_session_query(self):
        text = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nmedia_0.m3u8\n"
        out = sts.rewrite_master(text, "s1")
        self.assertIn("media_0.m3u8?session=s1", out)
        self.assertIn("#EXT-X-STREAM-INF:BANDWIDTH=1000", out)
        self.assertEqual(sts.rewrite_master(text, None), text)


def _wait_port(path, proc, timeout=5):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if os.path.isfile(path) and os.path.getsize(path) > 0:
            return open(path, encoding="utf-8").read().strip()
        if proc.poll() is not None:
            err = proc.stderr.read().decode("utf-8", "replace")
            raise AssertionError("server exited %s: %s" % (proc.returncode, err))
        time.sleep(0.05)
    raise AssertionError("port file not written")


class ServerProc(object):
    def __init__(self, root, args):
        self.dir = tempfile.mkdtemp(prefix="vela-sts-srv.")
        self.port_file = os.path.join(self.dir, "port")
        cmd = [
            sys.executable, "-I", os.path.join(HERE, "serve-test-stream.py"),
            root, "--port-file", self.port_file,
        ] + args
        self.proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.port = _wait_port(self.port_file, self.proc)
        self.base = "http://127.0.0.1:%s" % self.port

    def open(self, path, data=None, method=None):
        import urllib.request
        url = self.base + path
        req = urllib.request.Request(url, data=data, method=method)
        delay = 0.05
        while True:
            try:
                with urllib.request.urlopen(req, timeout=5) as resp:
                    body = resp.read()
                    return resp.status, dict(resp.headers), body
            except urllib.error.HTTPError as exc:
                return exc.code, dict(exc.headers), exc.read()
            except urllib.error.URLError:
                if delay > 1:
                    raise
                time.sleep(delay)
                delay *= 2

    def close(self):
        if self.proc.poll() is None:
            self.proc.send_signal(signal.SIGTERM)
            try:
                self.proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait(timeout=5)


class HttpBehaviors(unittest.TestCase):
    def setUp(self):
        self.stream_dir = tempfile.mkdtemp(prefix="vela-sts-root.")
        with open(os.path.join(self.stream_dir, "master.m3u8"), "w") as fh:
            fh.write("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080\nmedia_0.m3u8\n")
        with open(os.path.join(self.stream_dir, "media_0.m3u8"), "w") as fh:
            fh.write("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2.0,\nseg0.ts\n#EXTINF:2.0,\nseg1.ts\n")
        with open(os.path.join(self.stream_dir, "seg0.ts"), "wb") as fh:
            fh.write(b"seg0-bytes")
        with open(os.path.join(self.stream_dir, "seg1.ts"), "wb") as fh:
            fh.write(b"seg1-bytes")
        self.fix = tempfile.mkdtemp(prefix="vela-sts-fix.")
        rooms = {
            "rooms": [
                {
                    "username": "synth_nova",
                    "gender": "f",
                    "location": "Other",
                    "current_show": "public",
                    "room_subject": "synthetic color bars",
                    "tags": ["synth", "bars"],
                    "num_users": 120,
                    "num_followers": 7,
                    "has_password": False,
                    "display_age": 23,
                    "start_timestamp": 1700000000,
                },
                {
                    "username": "synth_rio",
                    "gender": "t",
                    "location": "Other",
                    "current_show": "public",
                    "room_subject": "synthetic south",
                    "tags": ["synth", "south"],
                    "num_users": 40,
                    "num_followers": 1,
                    "has_password": False,
                    "display_age": 30,
                    "start_timestamp": 1700001000,
                },
                {
                    "username": "synth_locked",
                    "gender": "f",
                    "location": "Other",
                    "current_show": "public",
                    "room_subject": "synthetic lock",
                    "tags": ["synth"],
                    "num_users": 3,
                    "num_followers": 0,
                    "has_password": True,
                    "display_age": 28,
                    "start_timestamp": 1700002000,
                },
            ]
        }
        with open(os.path.join(self.fix, "catalog.json"), "w") as fh:
            json.dump(rooms, fh)
        meta = {
            "synth_nova": {"region": "NA", "new_cam": False, "last_broadcast": "2020-01-15T12:00:00Z"},
            "synth_rio": {"region": "SA", "new_cam": True, "last_broadcast": "2024-11-01T08:30:00Z"},
            "synth_locked": {"region": "O", "new_cam": False, "last_broadcast": "2022-02-02T02:02:02Z"},
            "synth_gone": {"deleted": True},
        }
        with open(os.path.join(self.fix, "catalog-meta.json"), "w") as fh:
            json.dump(meta, fh)
        tags = {"hashtags": [{"hashtag": "synth", "room_count": 3}], "total": 1}
        with open(os.path.join(self.fix, "tags.json"), "w") as fh:
            json.dump(tags, fh)
        self.servers = []

    def tearDown(self):
        for srv in self.servers:
            srv.close()

    def start(self, args):
        srv = ServerProc(self.stream_dir, args)
        self.servers.append(srv)
        return srv

    def test_defaults_do_not_serve_catalog_or_controls(self):
        srv = self.start([])
        status, _, body = srv.open("/api/ts/roomlist/room-list/?limit=10&offset=0")
        self.assertEqual(status, 404)
        status, _, body = srv.open("/master.m3u8")
        self.assertEqual(status, 200)
        self.assertNotIn(b"session=", body)
        status, _, _ = srv.open("/__test/rewind")
        self.assertEqual(status, 404)
        status, _, _ = srv.open("/__test/rotate")
        self.assertEqual(status, 404)
        status, _, _ = srv.open("/__test/expire")
        self.assertEqual(status, 404)
        status, _, _ = srv.open("/__test/challenge-ok")
        self.assertEqual(status, 404)

    def test_catalog_fixtures_filter_and_stream_ticket(self):
        srv = self.start(["--catalog-fixtures", self.fix])
        status, _, body = srv.open("/api/ts/roomlist/room-list/?limit=1&offset=0")
        self.assertEqual(status, 200)
        page = json.loads(body.decode())
        self.assertEqual(page["total_count"], 3)
        self.assertEqual([r["username"] for r in page["rooms"]], ["synth_nova"])
        status, _, body = srv.open("/api/ts/roomlist/room-list/?limit=10&offset=0&genders=t")
        page = json.loads(body.decode())
        self.assertEqual([r["username"] for r in page["rooms"]], ["synth_rio"])
        status, _, body = srv.open("/api/ts/roomlist/room-list/?limit=10&offset=0&keywords=bars")
        page = json.loads(body.decode())
        self.assertEqual([r["username"] for r in page["rooms"]], ["synth_nova"])
        status, _, body = srv.open("/api/ts/roomlist/room-list/?limit=10&offset=0&new_cams=true")
        page = json.loads(body.decode())
        self.assertEqual([r["username"] for r in page["rooms"]], ["synth_rio"])
        status, _, body = srv.open("/api/ts/roomlist/room-list/?limit=10&offset=0&regions=SA")
        page = json.loads(body.decode())
        self.assertEqual([r["username"] for r in page["rooms"]], ["synth_rio"])
        status, _, body = srv.open("/api/ts/roomlist/room-list/?limit=10&offset=0&hashtags=south")
        page = json.loads(body.decode())
        self.assertEqual([r["username"] for r in page["rooms"]], ["synth_rio"])
        status, _, body = srv.open("/api/chatvideocontext/synth_nova/")
        self.assertEqual(status, 200)
        ctx = json.loads(body.decode())
        self.assertEqual(ctx["room_status"], "public")
        self.assertTrue(ctx["hls_source"].startswith("http://"))
        self.assertTrue(ctx["hls_source"].endswith("/hls/synth_nova/master.m3u8"))
        status, _, master = srv.open(ctx["hls_source"][len(srv.base):])
        self.assertEqual(status, 200)
        self.assertIn(b"#EXTM3U", master)
        status, _, body = srv.open("/api/chatvideocontext/synth_locked/")
        self.assertEqual(status, 401)
        status, _, body = srv.open("/api/chatvideocontext/synth_gone/")
        self.assertEqual(status, 403)
        self.assertIn("detail", json.loads(body.decode()))
        status, _, _ = srv.open("/api/chatvideocontext/nobody/")
        self.assertEqual(status, 404)
        status, _, body = srv.open(
            "/get_edge_hls_url_ajax/",
            data=b"room_slug=synth_nova",
            method="POST",
        )
        self.assertEqual(status, 200)
        ticket = json.loads(body.decode())
        self.assertTrue(ticket["success"])
        self.assertTrue(ticket["url"].endswith("/hls/synth_nova/master.m3u8"))
        status, _, body = srv.open("/api/biocontext/synth_nova/")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body.decode())["last_broadcast"], "2020-01-15T12:00:00Z")
        status, _, body = srv.open("/api/ts/hashtags/tag-table-data/?sort=-rc&page=1&g=&limit=20")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body.decode())["hashtags"][0]["hashtag"], "synth")

    def test_repo_fixtures_serve_synth_rooms(self):
        srv = self.start(["--catalog-fixtures"])
        status, _, body = srv.open("/api/ts/roomlist/room-list/?limit=90&offset=0")
        self.assertEqual(status, 200)
        names = [r["username"] for r in json.loads(body.decode())["rooms"]]
        self.assertIn("synth_nova", names)
        self.assertIn("synth_rio", names)

    def test_challenge_blocks_api_until_cleared_but_not_hls(self):
        srv = self.start(["--catalog-fixtures", self.fix, "--challenge"])
        status, _, master = srv.open("/master.m3u8")
        self.assertEqual(status, 200)
        self.assertIn(b"#EXTM3U", master)
        status, _, body = srv.open("/api/ts/roomlist/room-list/?limit=10&offset=0")
        self.assertEqual(status, 403)
        low = body.decode("utf-8", "replace").lower()
        self.assertIn("just a moment", low)
        self.assertIn("verify your age", low)
        self.assertIn("cf-mitigated", low)
        self.assertIn("challenges.cloudflare.com", low)
        self.assertFalse(body.lstrip().startswith(b"{"))
        status, _, _ = srv.open("/")
        self.assertEqual(status, 403)
        status, _, _ = srv.open("/__test/challenge-ok")
        self.assertEqual(status, 200)
        status, _, body = srv.open("/api/ts/roomlist/room-list/?limit=10&offset=0")
        self.assertEqual(status, 200)
        self.assertIn(b"synth_nova", body)
        status, _, home = srv.open("/")
        self.assertEqual(status, 200)
        self.assertNotIn(b"Just a moment", home)

    def test_expire_idle_and_force_expire(self):
        srv = self.start(["--expire-idle", "0.4"])
        status, _, body = srv.open("/master.m3u8")
        self.assertEqual(status, 200)
        text = body.decode()
        self.assertIn("media_0.m3u8?session=", text)
        media = "/" + [ln for ln in text.splitlines() if ln and not ln.startswith("#")][-1]
        status, _, playlist = srv.open(media)
        self.assertEqual(status, 200)
        self.assertIn(b"#EXT-X-MEDIA-SEQUENCE:", playlist)
        status, _, _ = srv.open("/__test/expire")
        self.assertEqual(status, 200)
        status, _, _ = srv.open(media)
        self.assertEqual(status, 403)
        status, _, body = srv.open("/master.m3u8")
        fresh = "/" + [ln for ln in body.decode().splitlines() if ln and not ln.startswith("#")][-1]
        self.assertNotEqual(fresh, media)
        status, _, playlist = srv.open(fresh)
        self.assertEqual(status, 200)
        self.assertIn(b"#EXTINF:", playlist)

    def test_expire_idle_wall_clock(self):
        srv = self.start(["--expire-idle", "0.4"])
        _, _, body = srv.open("/master.m3u8")
        media = "/" + [ln for ln in body.decode().splitlines() if ln and not ln.startswith("#")][-1]
        status, _, _ = srv.open(media)
        self.assertEqual(status, 200)
        time.sleep(0.6)
        status, _, _ = srv.open(media)
        self.assertEqual(status, 403)

    def test_rewind_and_rotate_controls(self):
        srv = self.start(["--rewind", "--rotate-uri"])
        status, _, first = srv.open("/media_0.m3u8")
        self.assertEqual(status, 200)
        self.assertNotIn(b"?rot=", first)
        status, _, _ = srv.open("/__test/rotate")
        self.assertEqual(status, 200)
        status, _, rotated = srv.open("/media_0.m3u8")
        self.assertIn(b"?rot=", rotated)
        seq1 = re.search(br"MEDIA-SEQUENCE:(\d+)", first).group(1)
        seq2 = re.search(br"MEDIA-SEQUENCE:(\d+)", rotated).group(1)
        self.assertEqual(seq1, seq2)
        status, _, _ = srv.open("/__test/rewind")
        self.assertEqual(status, 200)
        status, _, rewound = srv.open("/media_0.m3u8")
        seq3 = int(re.search(br"MEDIA-SEQUENCE:(\d+)", rewound).group(1))
        self.assertLess(seq3, int(seq1))
        self.assertIn(b"#EXT-X-DISCONTINUITY", rewound)

    def test_ll_parts_on_http(self):
        srv = self.start(["--ll-parts"])
        status, _, body = srv.open("/media_0.m3u8")
        self.assertEqual(status, 200)
        self.assertIn(b"#EXT-X-PART:", body)
        self.assertIn(b"#EXTINF:", body)

    def test_request_log_jsonl(self):
        log_path = os.path.join(self.fix, "req.jsonl")
        srv = self.start(["--request-log", log_path])
        status, _, body = srv.open("/master.m3u8")
        self.assertEqual(status, 200)
        status, _, _ = srv.open("/no-such-file.ts")
        self.assertEqual(status, 404)
        srv.close()
        lines = [json.loads(ln) for ln in open(log_path, encoding="utf-8") if ln.strip()]
        self.assertGreaterEqual(len(lines), 2)
        paths = [ln["path"] for ln in lines]
        self.assertIn("/master.m3u8", paths)
        self.assertIn("/no-such-file.ts", paths)
        master = [ln for ln in lines if ln["path"] == "/master.m3u8"][0]
        self.assertEqual(master["method"], "GET")
        self.assertEqual(master["status"], 200)
        self.assertGreater(master["bytes"], 0)
        self.assertIn("ts", master)
        missing = [ln for ln in lines if ln["path"] == "/no-such-file.ts"][0]
        self.assertEqual(missing["status"], 404)


class EmulatorRunScript(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.mkdtemp(prefix="vela-emu-test.")
        self.bin = os.path.join(self.work, "bin")
        os.mkdir(self.bin)
        self.modes = os.path.join(self.work, "modes")
        os.mkdir(self.modes)
        self.stream = os.path.join(self.work, "stream")
        os.mkdir(self.stream)
        with open(os.path.join(self.stream, "master.m3u8"), "w") as fh:
            fh.write("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nmedia_0.m3u8\n")
        with open(os.path.join(self.stream, "media_0.m3u8"), "w") as fh:
            fh.write("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2.0,\nseg0.ts\n")
        with open(os.path.join(self.stream, "seg0.ts"), "wb") as fh:
            fh.write(b"x")
        self.script = os.path.join(HERE, "..", "android", "tools", "emulator-run.sh")

    def _adb(self, state):
        path = os.path.join(self.bin, "adb")
        with open(path, "w") as fh:
            fh.write(
                "#!/bin/sh\n"
                "cmd=$1\n"
                "shift\n"
                "case \"$cmd\" in\n"
                "  devices)\n"
                "    printf '%%s\\n' \"List of devices attached\" \"emulator-5554 %s\"\n"
                "    ;;\n"
                "  logcat)\n"
                "    for arg in \"$@\"; do\n"
                "      if [ \"$arg\" = \"-c\" ]; then exit 0; fi\n"
                "    done\n"
                "    echo fake-logcat\n"
                "    while true; do sleep 1; done\n"
                "    ;;\n"
                "  *) exit 0 ;;\n"
                "esac\n" % state
            )
        os.chmod(path, 0o755)

    def _env(self):
        env = os.environ.copy()
        env["PATH"] = self.bin + os.pathsep + env.get("PATH", "")
        env["TMPDIR"] = self.work
        env["VELA_STREAM_DIR"] = self.stream
        env["VELA_EMU_MODES_DIR"] = self.modes
        env["VELA_UPSTREAM_NO_CATALOG"] = "1"
        return env

    def _run(self, args, env):
        proc = subprocess.Popen(
            ["bash", self.script] + args,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env,
        )
        try:
            out, err = proc.communicate(timeout=30)
        except subprocess.TimeoutExpired:
            proc.kill()
            out, err = proc.communicate()
            self.fail("timeout\n%s\n%s" % (out.decode(), err.decode()))
        return proc.returncode, out.decode(), err.decode()

    def test_refuses_offline_emulator(self):
        self._adb("offline")
        code, out, err = self._run(["all"], self._env())
        self.assertNotEqual(code, 0, out + err)
        self.assertIn("device", err)

    def test_all_runs_modes_and_passes_upstream(self):
        self._adb("device")
        marker = os.path.join(self.work, "marker")
        playlist = os.path.join(self.work, "playlist.m3u8")
        mode = os.path.join(self.modes, "b-mode.sh")
        with open(mode, "w") as fh:
            fh.write("#!/bin/bash\n")
            fh.write("# vela-upstream-flags: --ll-parts\n")
            fh.write('printf "%s\\n" "$VELA_UPSTREAM" "$VELA_GRADLE_UPSTREAM" "$VELA_UPSTREAM_LOCAL" > ')
            fh.write(json.dumps(marker) + "\n")
            fh.write("python3 - \"$VELA_UPSTREAM_LOCAL/media_0.m3u8\" > " + json.dumps(playlist) + " <<'PY'\n")
            fh.write("import sys, urllib.request\n")
            fh.write("sys.stdout.buffer.write(urllib.request.urlopen(sys.argv[1], timeout=5).read())\n")
            fh.write("PY\n")
            fh.write('test -s "$VELA_REQUEST_LOG"\n')
            fh.write('test -n "$VELA_LOGCAT"\n')
        with open(os.path.join(self.modes, "a-fail.sh"), "w") as fh:
            fh.write("#!/bin/bash\nexit 3\n")
        code, out, err = self._run(["all"], self._env())
        self.assertNotEqual(code, 0, out + err)
        self.assertIn("mode=a-fail exit=3", out)
        self.assertIn("mode=b-mode exit=0", out)
        self.assertIn("summary", out)
        self.assertIn("failed=1", out)
        text = open(marker, encoding="utf-8").read().splitlines()
        self.assertTrue(text[0].startswith("http://10.0.2.2:"))
        self.assertIn("-Pvela.upstream=" + text[0], text[1])
        self.assertTrue(text[2].startswith("http://127.0.0.1:"))
        playlist = open(os.path.join(self.work, "playlist.m3u8"), "rb").read()
        self.assertIn(b"#EXT-X-PART:", playlist)

    def test_all_without_modes_exits_zero(self):
        self._adb("device")
        code, out, err = self._run(["all"], self._env())
        self.assertEqual(code, 0, out + err)
        self.assertIn("summary", out)
        self.assertIn("failed=0", out)

    def test_missing_mode_fails(self):
        self._adb("device")
        code, out, err = self._run(["nope"], self._env())
        self.assertNotEqual(code, 0, out + err)


if __name__ == "__main__":
    unittest.main()
