#!/usr/bin/env python3
"""把 make-test-stream.sh 生成的点播 HLS 当作直播窗口提供出来（仅标准库，运行：python3 -I scripts/serve-test-stream.py ...）。

用法：serve-test-stream.py <流目录> --port-file <文件>
只绑 127.0.0.1 的系统分配端口，端口号写入 --port-file（先写临时名再改名，读到即完整）；收到 SIGTERM/SIGINT 即退出。
主列表原样提供；媒体列表按墙钟生成滑动窗口（3 个分片、MEDIA-SEQUENCE 随时间递增），分片文件循环使用，
回绕处加 EXT-X-DISCONTINUITY 并维护 DISCONTINUITY-SEQUENCE，音视频两路窗口同步。
"""
import http.server
import os
import re
import signal
import sys
import time

WINDOW = 3


def parse_media(path):
    """返回 (目标时长, [(时长, 文件名)])。"""
    segs, dur, target = [], None, 2
    for line in open(path, encoding="utf-8"):
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


class Stream:
    def __init__(self, root):
        self.root = root
        self.t0 = time.time()
        self.media = {}
        for name in os.listdir(root):
            if name.endswith(".m3u8") and name != "master.m3u8":
                self.media[name] = parse_media(os.path.join(root, name))

    def live_playlist(self, name):
        target, segs = self.media[name]
        n = len(segs)
        seg_dur = segs[0][0]
        head = int((time.time() - self.t0) / seg_dur) + WINDOW  # 当前最新分片的绝对序号 + 1
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
        return ("\n".join(lines) + "\n").encode()


def make_handler(stream):
    class H(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            name = self.path.split("?")[0].lstrip("/")
            if "/" in name or name.startswith("."):
                return self.send_error(404)
            if name in stream.media:
                body, ctype = stream.live_playlist(name), "application/vnd.apple.mpegurl"
            elif name in ("master.m3u8",) or name.endswith(".ts"):
                path = os.path.join(stream.root, name)
                if not os.path.isfile(path):
                    return self.send_error(404)
                body = open(path, "rb").read()
                ctype = "application/vnd.apple.mpegurl" if name.endswith(".m3u8") else "video/mp2t"
            else:
                return self.send_error(404)
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()
            try:
                self.wfile.write(body)
            except OSError:
                pass
    return H


def main(argv):
    if len(argv) != 4 or argv[2] != "--port-file":
        print(__doc__, file=sys.stderr)
        return 2
    root, port_file = argv[1], argv[3]
    if not os.path.isfile(os.path.join(root, "master.m3u8")):
        print("流目录里没有 master.m3u8: %s" % root, file=sys.stderr)
        return 1
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), make_handler(Stream(root)))
    server.daemon_threads = True
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    tmp = port_file + ".tmp"
    with open(tmp, "w") as f:
        f.write(str(server.server_address[1]))
    os.rename(tmp, port_file)
    try:
        server.serve_forever()
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
