#!/usr/bin/env python3
"""把 make-test-stream.sh 生成的点播 HLS 当作直播窗口提供出来（仅标准库，运行：python3 -I scripts/serve-test-stream.py ...）。

用法：serve-test-stream.py <流目录> --port-file <文件> [开关]
只绑 127.0.0.1 的系统分配端口，端口号写入 --port-file（先写临时名再改名，读到即完整）；收到 SIGTERM/SIGINT 即退出。
主列表原样提供；媒体列表按墙钟生成滑动窗口（3 个分片、MEDIA-SEQUENCE 随时间递增），分片文件循环使用，
回绕处加 EXT-X-DISCONTINUITY 并维护 DISCONTINUITY-SEQUENCE，音视频两路窗口同步。

开关全部默认关闭（macOS 的固定流探针不带这些参数，行为与以前相同）：
  --catalog-fixtures [目录]  同时提供合成目录与取流接口。省略目录时用仓库里的 android/tools/fixtures。
                             响应里的 hls_source / url 用请求的 Host 拼成 http 绝对地址（没有 TLS）。
  --ll-parts                 媒体列表附带 EXT-X-SERVER-CONTROL / EXT-X-PART / EXT-X-PRELOAD-HINT。
  --expire-idle <秒>         子列表地址带 session=，距上次成功请求达到该秒数就 403；再取主列表会发新 session。
                             GET /__test/expire 立刻作废已发出的 session（省掉干等）。
  --rewind                   允许一次性序号回退。GET /__test/rewind 后，每路媒体列表的下一次响应
                             MEDIA-SEQUENCE 至少减 1，并带 EXT-X-DISCONTINUITY。未触发时列表与平时相同。
                             开启后时间原点前移 60 秒，这样刚启动就能观察到回退。
  --rotate-uri               允许一次性改写最后一个分片地址。GET /__test/rotate 后，每路下一次响应
                             序号不变，最后一个分片 URI 多出 ?rot=N。
  --challenge                目录、取流、站点根在通关前返回质询 HTML（Just a moment / Verify your age /
                             cf-mitigated / challenges.cloudflare.com）。GET /__test/challenge-ok 后恢复。
                             媒体列表与分片不受质询影响。
  --request-log <jsonl>      每个响应追加一行 JSON：ts、method、path、status、bytes。

目录夹具（--catalog-fixtures）认这些路径，字段与现有解码器一致，全部是合成房间：
  GET  /api/ts/roomlist/room-list/     查询 limit/offset/genders/regions/hashtags/new_cams/keywords
  GET  /api/chatvideocontext/<用户名>/
  GET  /api/biocontext/<用户名>/
  GET  /api/ts/hashtags/tag-table-data/
  POST /get_edge_hls_url_ajax/         表单 room_slug
  GET  /hls/<用户名>/<主列表或分片>    映射到流目录里的同名文件；媒体列表仍按墙钟生成
筛选用的 region / new_cam / deleted 写在 catalog-meta.json，不会出现在目录 JSON 里。
"""
import json
import os
import re
import signal
import sys
import threading
import time
from urllib.parse import parse_qs, unquote, urlsplit

WINDOW = 3
MAX_PAGE = 90
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_FIXTURES = os.path.normpath(os.path.join(SCRIPT_DIR, "..", "android", "tools", "fixtures"))


class UsageError(Exception):
    def __init__(self, message, code=2):
        Exception.__init__(self, message)
        self.code = code


class Options(object):
    def __init__(self):
        self.root = None
        self.port_file = None
        self.catalog = None
        self.ll_parts = False
        self.expire_idle = None
        self.rewind = False
        self.rotate_uri = False
        self.challenge = False
        self.request_log = None


def parse_args(argv):
    """argv 含程序名。缺必填参数或开关不认识时抛 UsageError。"""
    opts = Options()
    args = argv[1:]
    i = 0
    while i < len(args):
        a = args[i]
        if a == "--port-file":
            i += 1
            if i >= len(args):
                raise UsageError(__doc__)
            opts.port_file = args[i]
        elif a == "--catalog-fixtures":
            if i + 1 < len(args) and not args[i + 1].startswith("-"):
                i += 1
                opts.catalog = args[i]
            else:
                opts.catalog = DEFAULT_FIXTURES
        elif a == "--ll-parts":
            opts.ll_parts = True
        elif a == "--expire-idle":
            i += 1
            if i >= len(args):
                raise UsageError(__doc__)
            try:
                opts.expire_idle = float(args[i])
            except ValueError:
                raise UsageError(__doc__)
            if opts.expire_idle <= 0:
                raise UsageError(__doc__)
        elif a == "--rewind":
            opts.rewind = True
        elif a == "--rotate-uri":
            opts.rotate_uri = True
        elif a == "--challenge":
            opts.challenge = True
        elif a == "--request-log":
            i += 1
            if i >= len(args):
                raise UsageError(__doc__)
            opts.request_log = args[i]
        elif a.startswith("-"):
            raise UsageError(__doc__)
        else:
            if opts.root is not None:
                raise UsageError(__doc__)
            opts.root = a
        i += 1
    if not opts.root or not opts.port_file:
        raise UsageError(__doc__)
    return opts


class SessionBook(object):
    """子列表 session：闲置达到 idle 秒就作废。idle 为 None 时不检查。"""

    def __init__(self):
        self.idle = None
        self.touched = {}
        self.dead = set()
        self._n = 0
        self.clock = time.time
        self.lock = threading.Lock()

    def issue(self, now=None):
        with self.lock:
            if now is None:
                now = self.clock()
            self._n += 1
            sid = "s%d" % self._n
            self.touched[sid] = now
            return sid

    def allow(self, sid, now=None):
        with self.lock:
            if self.idle is None:
                return True
            if now is None:
                now = self.clock()
            if sid in self.dead or sid not in self.touched:
                return False
            if now - self.touched[sid] >= self.idle:
                self.dead.add(sid)
                return False
            self.touched[sid] = now
            return True

    def expire_all(self):
        with self.lock:
            for sid in self.touched:
                self.dead.add(sid)


def rewrite_master(text, session):
    """给主列表里的子列表地址加上 session。session 为空时原样返回。"""
    if not session:
        return text
    lines = []
    for line in text.split("\n"):
        if line and not line.startswith("#") and "session=" not in line:
            if "?" in line:
                line = line + "&session=" + session
            else:
                line = line + "?session=" + session
        lines.append(line)
    return "\n".join(lines)


def parse_media(path):
    """返回 (目标时长, [(时长, 文件名)])。"""
    segs, dur, target = [], None, 2
    with open(path, encoding="utf-8") as fh:
        lines = fh.read().splitlines()
    for line in lines:
        line = line.strip()
        m = re.match(r"#EXTINF:([0-9.]+)", line)
        if m:
            dur = float(m.group(1))
        elif line.startswith("#EXT-X-TARGETDURATION:"):
            target = int(line.split(":")[1])
        elif line and not line.startswith("#") and dur is not None:
            segs.append((dur, line))
            dur = None
    return target, segs


class Stream(object):
    def __init__(self, root):
        self.root = root
        self.t0 = time.time()
        self.clock = time.time
        self.lock = threading.Lock()
        self.ll_parts = False
        self.rewind_enabled = False
        self.rotate_enabled = False
        self.rewind_left = set()
        self.rotate_left = set()
        self.rotate_n = 0
        self.sessions = SessionBook()
        self.media = {}
        for name in os.listdir(root):
            if name.endswith(".m3u8") and name != "master.m3u8":
                self.media[name] = parse_media(os.path.join(root, name))

    def arm_rewind(self):
        with self.lock:
            if not self.rewind_enabled:
                return False
            self.rewind_left = set(self.media.keys())
            return True

    def arm_rotate(self):
        with self.lock:
            if not self.rotate_enabled:
                return False
            self.rotate_left = set(self.media.keys())
            return True

    def live_playlist(self, name):
        with self.lock:
            body = self._base_playlist(name)
            if name in self.rewind_left:
                self.rewind_left.discard(name)
                body = self._rewound(body)
            if name in self.rotate_left:
                self.rotate_left.discard(name)
                body = self._rotated(body)
            if self.ll_parts:
                body = self._with_parts(body)
            return body

    def master_bytes(self):
        path = os.path.join(self.root, "master.m3u8")
        with open(path, "rb") as fh:
            raw = fh.read()
        if self.sessions.idle is None:
            return raw
        text = raw.decode("utf-8")
        sid = self.sessions.issue(self.clock())
        return rewrite_master(text, sid).encode("utf-8")

    def _base_playlist(self, name):
        target, segs = self.media[name]
        n = len(segs)
        seg_dur = segs[0][0]
        head = int((self.clock() - self.t0) / seg_dur) + WINDOW  # 当前最新分片的绝对序号 + 1
        first = head - WINDOW
        # 只累计已移出窗口的 discontinuity 标签：标签位于序号为 n 的整数倍（k>0）的分片之前，
        # 窗口首片自带的标签仍在列表里，不能再计一次
        disc_seq = max(0, (first - 1) // n)
        lines = ["#EXTM3U", "#EXT-X-VERSION:6", "#EXT-X-TARGETDURATION:%d" % target,
                 "#EXT-X-MEDIA-SEQUENCE:%d" % first, "#EXT-X-DISCONTINUITY-SEQUENCE:%d" % disc_seq,
                 "#EXT-X-INDEPENDENT-SEGMENTS"]
        for k in range(first, head):
            if k > 0 and k % n == 0:
                lines.append("#EXT-X-DISCONTINUITY")
            dur, fname = segs[k % n]
            lines.append("#EXTINF:%.6f," % dur)
            lines.append(fname)
        return ("\n".join(lines) + "\n").encode("utf-8")

    def _rewound(self, body):
        text = body.decode("utf-8")
        found = re.search(r"#EXT-X-MEDIA-SEQUENCE:(\d+)", text)
        if not found:
            return body
        seq = int(found.group(1))
        new_seq = seq - (WINDOW + 1)
        if new_seq < 0:
            new_seq = 0
        if new_seq >= seq and seq > 0:
            new_seq = seq - 1
        text = text.replace("#EXT-X-MEDIA-SEQUENCE:%d" % seq, "#EXT-X-MEDIA-SEQUENCE:%d" % new_seq, 1)
        if "#EXT-X-DISCONTINUITY" not in text:
            text = text.replace("#EXTINF:", "#EXT-X-DISCONTINUITY\n#EXTINF:", 1)
        if not text.endswith("\n"):
            text += "\n"
        return text.encode("utf-8")

    def _rotated(self, body):
        self.rotate_n += 1
        text = body.decode("utf-8")
        lines = text.split("\n")
        for i in range(len(lines) - 1, -1, -1):
            line = lines[i]
            if line and not line.startswith("#"):
                base = line.split("?", 1)[0]
                lines[i] = "%s?rot=%d" % (base, self.rotate_n)
                break
        out = "\n".join(lines)
        if not out.endswith("\n"):
            out += "\n"
        return out.encode("utf-8")

    def _with_parts(self, body):
        text = body.decode("utf-8")
        lines = text.split("\n")
        dur = 1.0
        last_uri = None
        for line in lines:
            if line.startswith("#EXTINF:"):
                try:
                    dur = float(line.split(":", 1)[1].split(",")[0])
                except ValueError:
                    dur = 1.0
            elif line and not line.startswith("#"):
                last_uri = line.split("?", 1)[0]
        if last_uri is None:
            return body
        part = dur / 2.0
        out = []
        inserted = False
        for line in lines:
            if (not inserted) and line.startswith("#EXT-X-INDEPENDENT-SEGMENTS"):
                out.append(line)
                out.append("#EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES,PART-HOLD-BACK=%.3f" % (part * 3))
                inserted = True
                continue
            out.append(line)
        if not inserted:
            out.insert(1, "#EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES,PART-HOLD-BACK=%.3f" % (part * 3))
        while out and out[-1] == "":
            out.pop()
        out.append('#EXT-X-PART:DURATION=%.3f,URI="%s?part=0",INDEPENDENT=YES' % (part, last_uri))
        out.append('#EXT-X-PART:DURATION=%.3f,URI="%s?part=1"' % (part, last_uri))
        out.append('#EXT-X-PRELOAD-HINT:TYPE=PART,URI="%s?part=2"' % last_uri)
        return ("\n".join(out) + "\n").encode("utf-8")


class Catalog(object):
    def __init__(self, directory):
        self.directory = directory
        with open(os.path.join(directory, "catalog.json"), encoding="utf-8") as fh:
            payload = json.load(fh)
        if isinstance(payload, dict):
            self.rooms = list(payload.get("rooms") or [])
        else:
            self.rooms = list(payload)
        self.meta = {}
        meta_path = os.path.join(directory, "catalog-meta.json")
        if os.path.isfile(meta_path):
            with open(meta_path, encoding="utf-8") as fh:
                loaded = json.load(fh)
                if isinstance(loaded, dict):
                    self.meta = loaded
        self.tags = {"hashtags": [], "total": 0}
        tags_path = os.path.join(directory, "tags.json")
        if os.path.isfile(tags_path):
            with open(tags_path, encoding="utf-8") as fh:
                loaded = json.load(fh)
                if isinstance(loaded, dict):
                    self.tags = loaded

    def meta_for(self, username):
        want = username.lower()
        for key, val in self.meta.items():
            if str(key).lower() == want and isinstance(val, dict):
                return val
        return {}

    def room_named(self, username):
        want = username.lower()
        for room in self.rooms:
            if str(room.get("username", "")).lower() == want:
                return room
        return None

    def page(self, query):
        limit = _clamp(_parse_int(_first(query, "limit"), 50), 1, MAX_PAGE)
        offset = max(0, _parse_int(_first(query, "offset"), 0))
        gender = _first(query, "genders")
        region = _first(query, "regions")
        tag = _hashtag(_first(query, "hashtags"))
        keywords = (_first(query, "keywords") or "").strip().lower()
        new_cams = (_first(query, "new_cams") or "").lower() in ("true", "1", "yes")
        chosen = []
        for room in self.rooms:
            name = str(room.get("username", ""))
            meta = self.meta_for(name)
            if gender and str(room.get("gender", "")).lower() != gender.lower():
                continue
            if region and str(meta.get("region", "")).upper() != region.upper():
                continue
            if tag:
                tags = [str(item).lower() for item in (room.get("tags") or [])]
                if tag not in tags:
                    continue
            if new_cams and not meta.get("new_cam"):
                continue
            if keywords and keywords not in _haystack(room):
                continue
            chosen.append(room)
        return {"rooms": chosen[offset:offset + limit], "total_count": len(chosen)}


def _first(query, key):
    vals = query.get(key) or []
    if not vals:
        return None
    return vals[0]


def _parse_int(value, default):
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _clamp(value, low, high):
    if value < low:
        return low
    if value > high:
        return high
    return value


def _hashtag(raw):
    if raw is None:
        return None
    text = raw.strip().lstrip("#")
    word = text.replace(",", " ").split()
    if not word:
        return None
    return word[0].lower()


def _haystack(room):
    parts = [str(room.get("username", "")), str(room.get("room_subject", ""))]
    for tag in room.get("tags") or []:
        parts.append(str(tag))
    return " ".join(parts).lower()


CHALLENGE_HTML = """<!DOCTYPE html>
<html>
<head><title>Just a moment...</title></head>
<body>
<p>Just a moment</p>
<p>Verify your age</p>
<p>cf-mitigated</p>
<p>challenges.cloudflare.com</p>
<p><a href="/__test/challenge-ok">continue</a></p>
</body>
</html>
"""

READY_HTML = "<!DOCTYPE html><title>ready</title><p>ready</p>\n"


class Gate(object):
    def __init__(self):
        self.cleared = False
        self.lock = threading.Lock()


class RequestLog(object):
    def __init__(self, path):
        self.path = path
        self.lock = threading.Lock()

    def write(self, method, path, status, nbytes):
        if not self.path:
            return
        rec = {
            "bytes": int(nbytes),
            "method": method,
            "path": path,
            "status": int(status),
            "ts": time.time(),
        }
        line = json.dumps(rec, sort_keys=True, separators=(",", ":"))
        with self.lock:
            with open(self.path, "a", encoding="utf-8") as fh:
                fh.write(line + "\n")
                fh.flush()
                os.fsync(fh.fileno())


def _json(payload, status=200):
    body = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8")
    return status, "application/json", body


def _read_bytes(path):
    with open(path, "rb") as fh:
        return fh.read()


def _safe_name(name):
    if not name or name.startswith(".") or "/" in name or "\\" in name or name in (".", ".."):
        return False
    return True


def make_handler(stream, opts, catalog, gate, reqlog):
    import http.server

    class H(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *a):
            pass

        def do_GET(self):
            self._handle("GET", b"")

        def do_POST(self):
            try:
                length = int(self.headers.get("Content-Length", "0") or 0)
            except ValueError:
                length = 0
            raw = self.rfile.read(length) if length > 0 else b""
            self._handle("POST", raw)

        def _handle(self, method, raw):
            split = urlsplit(self.path)
            path = _strip(split.path)
            query = parse_qs(split.query)
            try:
                status, ctype, body = self._dispatch(method, path, query, raw)
            except Exception:
                status, ctype, body = 500, "text/plain; charset=utf-8", b"error\n"
            reqlog.write(method, path, status, len(body))
            self._send(status, ctype, body)

        def _send(self, status, ctype, body):
            self.send_response(status)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "close")
            self.end_headers()
            try:
                self.wfile.write(body)
            except OSError:
                pass

        def _dispatch(self, method, path, query, raw):
            control = self._control(path)
            if control is not None:
                return control
            if opts.challenge and not self._cleared() and _gated(path):
                return 403, "text/html; charset=utf-8", CHALLENGE_HTML.encode("utf-8")
            if path == "/" and opts.challenge and self._cleared():
                return 200, "text/html; charset=utf-8", READY_HTML.encode("utf-8")
            if catalog is not None:
                routed = self._catalog(method, path, query, raw)
                if routed is not None:
                    return routed
            return self._media(path, query)

        def _cleared(self):
            with gate.lock:
                return gate.cleared

        def _control(self, path):
            if path == "/__test/rewind":
                if not opts.rewind:
                    return None
                stream.arm_rewind()
                return 200, "text/plain; charset=utf-8", b"ok\n"
            if path == "/__test/rotate":
                if not opts.rotate_uri:
                    return None
                stream.arm_rotate()
                return 200, "text/plain; charset=utf-8", b"ok\n"
            if path == "/__test/expire":
                if opts.expire_idle is None:
                    return None
                stream.sessions.expire_all()
                return 200, "text/plain; charset=utf-8", b"ok\n"
            if path == "/__test/challenge-ok":
                if not opts.challenge:
                    return None
                with gate.lock:
                    gate.cleared = True
                return 200, "text/plain; charset=utf-8", b"ok\n"
            return None

        def _catalog(self, method, path, query, raw):
            if path == "/api/ts/roomlist/room-list":
                return _json(catalog.page(query))
            if path.startswith("/api/chatvideocontext/"):
                user = _user_from(path, "chatvideocontext")
                return self._chat(user)
            if path.startswith("/api/biocontext/"):
                user = _user_from(path, "biocontext")
                return self._bio(user)
            if path == "/api/ts/hashtags/tag-table-data" or path.startswith("/api/ts/hashtags/"):
                return _json(catalog.tags)
            if path == "/get_edge_hls_url_ajax":
                if method != "POST":
                    return 405, "text/plain; charset=utf-8", b"method\n"
                form = parse_qs(raw.decode("utf-8", "replace"))
                slug = unquote((form.get("room_slug") or [""])[0])
                return self._ajax(slug)
            return None

        def _chat(self, user):
            meta = catalog.meta_for(user)
            if meta.get("deleted"):
                return _json({"detail": "deleted"}, 403)
            room = catalog.room_named(user)
            if room is None:
                return _json({"detail": "not found"}, 404)
            if room.get("has_password"):
                return 401, "text/plain; charset=utf-8", b"password\n"
            show = room.get("current_show") or "public"
            payload = {
                "age": room.get("display_age"),
                "broadcaster_gender": room.get("gender") or "",
                "broadcaster_username": room.get("username"),
                "num_viewers": room.get("num_users") or 0,
                "room_status": show,
                "room_title": room.get("room_subject") or "",
                "start_timestamp": room.get("start_timestamp"),
            }
            if show == "public":
                payload["hls_source"] = self._hls_url(room.get("username"))
            return _json(payload)

        def _bio(self, user):
            meta = catalog.meta_for(user)
            if meta.get("deleted"):
                return _json({"detail": "deleted"}, 403)
            room = catalog.room_named(user)
            if room is None and not meta:
                return _json({"detail": "not found"}, 404)
            if room is None:
                return _json({"detail": "not found"}, 404)
            return _json({
                "about_me": "synthetic profile",
                "follower_count": room.get("num_followers") or 0,
                "last_broadcast": meta.get("last_broadcast") or "2020-01-15T12:00:00Z",
                "location": room.get("location") or "",
                "time_since_last_broadcast": 3600,
            })

        def _ajax(self, slug):
            room = catalog.room_named(slug) if slug else None
            meta = catalog.meta_for(slug) if slug else {}
            if room is None or meta.get("deleted") or room.get("has_password"):
                show = "offline"
                if room is not None and room.get("has_password"):
                    show = "password"
                elif room is not None and not meta.get("deleted"):
                    show = room.get("current_show") or "offline"
                return _json({
                    "hidden_message": "",
                    "room_status": show,
                    "success": False,
                    "url": "",
                })
            show = room.get("current_show") or "public"
            url = self._hls_url(room.get("username")) if show == "public" else ""
            return _json({
                "hidden_message": "",
                "room_status": show,
                "success": show == "public",
                "url": url,
            })

        def _hls_url(self, username):
            host = self.headers.get("Host") or "127.0.0.1"
            return "http://%s/hls/%s/master.m3u8" % (host, username)

        def _media(self, path, query):
            name, bad = _media_name(path)
            if bad:
                return 404, "text/plain; charset=utf-8", b"not found\n"
            if name == "master.m3u8":
                if not os.path.isfile(os.path.join(stream.root, name)):
                    return 404, "text/plain; charset=utf-8", b"not found\n"
                return 200, "application/vnd.apple.mpegurl", stream.master_bytes()
            if name in stream.media:
                if stream.sessions.idle is not None:
                    sid = _first(query, "session")
                    if not stream.sessions.allow(sid, stream.clock()):
                        return 403, "text/plain; charset=utf-8", b"expired\n"
                return 200, "application/vnd.apple.mpegurl", stream.live_playlist(name)
            if name.endswith(".ts"):
                file_path = os.path.join(stream.root, name)
                if not os.path.isfile(file_path):
                    return 404, "text/plain; charset=utf-8", b"not found\n"
                return 200, "video/mp2t", _read_bytes(file_path)
            if name.endswith(".m3u8"):
                file_path = os.path.join(stream.root, name)
                if os.path.isfile(file_path):
                    return 200, "application/vnd.apple.mpegurl", _read_bytes(file_path)
            return 404, "text/plain; charset=utf-8", b"not found\n"

    return H


def _strip(path):
    if path != "/" and path.endswith("/"):
        return path[:-1]
    return path or "/"


def _gated(path):
    if path == "/":
        return True
    if path.startswith("/api/"):
        return True
    if path == "/get_edge_hls_url_ajax":
        return True
    return False


def _user_from(path, kind):
    parts = [unquote(p) for p in path.strip("/").split("/")]
    # api / <kind> / <user>
    if len(parts) >= 3:
        return parts[2]
    return ""


def _media_name(path):
    parts = [unquote(p) for p in path.strip("/").split("/") if p != ""]
    if not parts:
        return "", True
    if parts[0] == "hls":
        if len(parts) != 3 or not _safe_name(parts[1]) or not _safe_name(parts[2]):
            return "", True
        return parts[2], False
    if len(parts) != 1 or not _safe_name(parts[0]):
        return "", True
    return parts[0], False


class _LocalServer(__import__("http.server").server.ThreadingHTTPServer):
    # 跳过 HTTPServer.server_bind 里对 127.0.0.1 的反向解析（getfqdn）：GitHub macOS 机器上它会卡数秒，端口文件写不出来。
    def server_bind(self):
        __import__("socketserver").TCPServer.server_bind(self)
        self.server_name, self.server_port = "127.0.0.1", self.server_address[1]


def build_server(opts, catalog):
    import http.server
    stream = Stream(opts.root)
    stream.ll_parts = opts.ll_parts
    stream.rewind_enabled = opts.rewind
    stream.rotate_enabled = opts.rotate_uri
    if opts.rewind:
        # 刚启动时窗口还在 0，回退看不出来。前移原点，序号已经往前走过。
        stream.t0 = time.time() - 60
    if opts.expire_idle is not None:
        stream.sessions.idle = opts.expire_idle
        stream.sessions.clock = stream.clock
    gate = Gate()
    reqlog = RequestLog(opts.request_log)
    handler = make_handler(stream, opts, catalog, gate, reqlog)
    server = _LocalServer(("127.0.0.1", 0), handler)
    server.daemon_threads = True
    return server


def main(argv):
    try:
        opts = parse_args(argv)
    except UsageError as exc:
        sys.stderr.write(str(exc))
        if not str(exc).endswith("\n"):
            sys.stderr.write("\n")
        return exc.code
    if not os.path.isfile(os.path.join(opts.root, "master.m3u8")):
        sys.stderr.write("流目录里没有 master.m3u8: %s\n" % opts.root)
        return 1
    catalog = None
    if opts.catalog is not None:
        if not os.path.isfile(os.path.join(opts.catalog, "catalog.json")):
            sys.stderr.write("夹具目录里没有 catalog.json: %s\n" % opts.catalog)
            return 1
        try:
            catalog = Catalog(opts.catalog)
        except (OSError, ValueError) as exc:
            sys.stderr.write("夹具读不了: %s\n" % exc)
            return 1
    if opts.request_log:
        parent = os.path.dirname(os.path.abspath(opts.request_log))
        if parent and not os.path.isdir(parent):
            sys.stderr.write("请求日志目录不存在: %s\n" % parent)
            return 1
    server = build_server(opts, catalog)
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    signal.signal(signal.SIGINT, lambda *_: sys.exit(0))
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    tmp = opts.port_file + ".tmp"
    with open(tmp, "w") as fh:
        fh.write(str(server.server_address[1]))
    os.rename(tmp, opts.port_file)
    try:
        while worker.is_alive():
            worker.join(3600)
    finally:
        server.shutdown()
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
