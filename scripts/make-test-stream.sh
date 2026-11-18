#!/bin/bash
# 生成固定测试流（合成 HLS），供 perf-probe.sh --fixed-stream 使用；生成物不入库。
# 用法：scripts/make-test-stream.sh [输出目录] [时长秒] [ladder]    默认 /tmp/vela-teststream，240 秒（120 个 2 秒分片，由服务脚本循环成直播窗口）
# 内容：testsrc2 彩条 + 内置计时，1920x1080 30fps，H.264 固定码率约 3 Mbps，每 2 秒一个关键帧与分片；
#       静音 AAC 做独立音轨（EXT-X-MEDIA 组 aud），与线上「视频档 + 独立音轨」主列表结构一致。
# 第三个参数为 ladder 时生成多档（1080p 3000k、720p 1200k、540p 600k，共用一条音轨），
#       用来验证 iOS 三格按格子尺寸选档真的降低了下行（ios-triple 修订 v2 M11）；G-mac 基线仍用默认单档。
# 已生成且参数一致则跳过（OUT/.stamp 记参数）。兼容 macOS 自带 bash 3.2。
set -eu
OUT="${1:-/tmp/vela-teststream}"
SECS="${2:-240}"
MODE="${3:-single}"
case "$MODE" in single|ladder) ;; *) echo "第三个参数只能是 ladder" >&2; exit 2 ;; esac
FFMPEG=$(command -v ffmpeg) || { echo "找不到 ffmpeg（brew install ffmpeg）" >&2; exit 1; }
STAMP="v1 1920x1080 30fps 3000k ${SECS}s"
[ "$MODE" = ladder ] && STAMP="v1-ladder 1080p3000k 720p1200k 540p600k 30fps ${SECS}s"
if [ -f "$OUT/.stamp" ] && [ "$(cat "$OUT/.stamp")" = "$STAMP" ] && [ -f "$OUT/master.m3u8" ]; then
    echo "测试流已存在：${OUT}" >&2
    exit 0
fi
rm -rf "$OUT"
mkdir -p "$OUT"
if [ "$MODE" = ladder ]; then
    "$FFMPEG" -hide_banner -loglevel error -y \
        -f lavfi -i "testsrc2=size=1920x1080:rate=30:duration=${SECS}" \
        -f lavfi -i "anullsrc=channel_layout=stereo:sample_rate=48000" -t "$SECS" \
        -filter_complex "[0:v]split=3[v1][v2][v3];[v2]scale=1280:720[v2o];[v3]scale=960:540[v3o]" \
        -map "[v1]" -map "[v2o]" -map "[v3o]" -map 1:a \
        -c:v libx264 -preset veryfast -profile:v high -level 4.1 -pix_fmt yuv420p \
        -b:v:0 3000k -minrate:v:0 3000k -maxrate:v:0 3000k -bufsize:v:0 6000k \
        -b:v:1 1200k -minrate:v:1 1200k -maxrate:v:1 1200k -bufsize:v:1 2400k \
        -b:v:2 600k -minrate:v:2 600k -maxrate:v:2 600k -bufsize:v:2 1200k \
        -x264-params nal-hrd=cbr -g 60 -keyint_min 60 -sc_threshold 0 \
        -c:a aac -b:a 64k -ar 48000 -ac 2 \
        -f hls -hls_time 2 -hls_playlist_type vod -hls_flags independent_segments \
        -master_pl_name master.m3u8 \
        -var_stream_map "v:0,agroup:aud v:1,agroup:aud v:2,agroup:aud a:0,agroup:aud,name:audio,language:en,default:yes" \
        -hls_segment_filename "$OUT/seg_%v_%03d.ts" "$OUT/media_%v.m3u8"
    [ -f "$OUT/master.m3u8" ] || { echo "ffmpeg 没产出 master.m3u8" >&2; exit 1; }
    echo "$STAMP" >"$OUT/.stamp"
    echo "多档测试流已生成：${OUT}" >&2
    exit 0
fi
"$FFMPEG" -hide_banner -loglevel error -y \
    -f lavfi -i "testsrc2=size=1920x1080:rate=30:duration=${SECS}" \
    -f lavfi -i "anullsrc=channel_layout=stereo:sample_rate=48000" -t "$SECS" \
    -map 0:v -map 1:a \
    -c:v libx264 -preset veryfast -profile:v high -level 4.1 -pix_fmt yuv420p \
    -b:v 3000k -minrate 3000k -maxrate 3000k -bufsize 6000k -x264-params nal-hrd=cbr \
    -g 60 -keyint_min 60 -sc_threshold 0 \
    -c:a aac -b:a 64k -ar 48000 -ac 2 \
    -f hls -hls_time 2 -hls_playlist_type vod -hls_flags independent_segments \
    -master_pl_name master.m3u8 \
    -var_stream_map "v:0,agroup:aud a:0,agroup:aud,name:audio,language:en,default:yes" \
    -hls_segment_filename "$OUT/seg_%v_%03d.ts" "$OUT/media_%v.m3u8"
[ -f "$OUT/master.m3u8" ] || { echo "ffmpeg 没产出 master.m3u8" >&2; exit 1; }
echo "$STAMP" >"$OUT/.stamp"
echo "测试流已生成：${OUT}" >&2
