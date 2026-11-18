"""serve-test-stream.py 的单元测试：python3 -I scripts/test_serve_test_stream.py"""
import importlib.util
import os
import re
import sys
import tempfile
import time
import unittest

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


class StatsLogTests(unittest.TestCase):
    def test_records_one_line_per_response(self):
        d = tempfile.mkdtemp(prefix="vela-stats.")
        path = os.path.join(d, "stats.txt")
        stats = sts.StatsLog(path)
        stats.record("seg_0_000.ts", 1234)
        stats.record("media_0.m3u8", 200)
        lines = open(path).read().splitlines()
        self.assertEqual(len(lines), 2)
        t, nbytes, name = lines[0].split()
        self.assertEqual((int(nbytes), name), (1234, "seg_0_000.ts"))
        self.assertLess(abs(float(t) - time.time()), 5)


if __name__ == "__main__":
    unittest.main()

