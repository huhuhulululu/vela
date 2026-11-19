package vela.core.base.play

/**
 * 媒体列表里与新鲜度有关的信息：TARGETDURATION、最后一个完整分片的媒体序号、它的时长，
 * 以及是否带 `EXT-X-DISCONTINUITY`、最后一个完整分片的 URI（用于判断重推流 / 换地址）。
 * 只数完整分片（EXTINF），LL-HLS 的 EXT-X-PART 不算进展。
 */
data class MediaPlaylistInfo(
    var targetDuration: Double,
    var lastSequence: Int,
    var lastSegmentDuration: Double? = null,
    var hasDiscontinuity: Boolean = false,
    var lastSegmentURI: String? = null,
) {
    companion object {
        /** 没有任何完整分片返回 null。缺 TARGETDURATION 或不是正数时按 [RelayDefaults.defaultTargetDuration]。 */
        fun parse(playlist: String): MediaPlaylistInfo? {
            var target: Double? = null
            var sequence = 0
            var count = 0
            var lastDuration: Double? = null
            var discontinuity = false
            var lastUri: String? = null
            var awaitingUri = false
            for (raw in playlist.lineSequence()) {
                val line = raw.trim()
                if (line.startsWith("#EXT-X-TARGETDURATION:")) {
                    target = line.removePrefix("#EXT-X-TARGETDURATION:").toDoubleOrNull()
                } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                    sequence = line.removePrefix("#EXT-X-MEDIA-SEQUENCE:").toIntOrNull() ?: 0
                } else if (line == "#EXT-X-DISCONTINUITY") {
                    discontinuity = true
                } else if (line.startsWith("#EXTINF:")) {
                    count += 1
                    val value = line.removePrefix("#EXTINF:").takeWhile { it != ',' }
                    lastDuration = value.toDoubleOrNull()
                    awaitingUri = true
                } else if (awaitingUri && line.isNotEmpty() && !line.startsWith("#")) {
                    lastUri = line
                    awaitingUri = false
                }
            }
            if (count <= 0) return null
            val safeTarget = if ((target ?: 0.0) > 0.0) target!! else RelayDefaults.defaultTargetDuration
            return MediaPlaylistInfo(
                targetDuration = safeTarget,
                lastSequence = sequence + count - 1,
                lastSegmentDuration = lastDuration,
                hasDiscontinuity = discontinuity,
                lastSegmentURI = lastUri,
            )
        }
    }
}
