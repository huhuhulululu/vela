package vela.core.base.play

import java.net.URI
import java.net.URISyntaxException

/**
 * 选中的一个画质档：视频子列表，以及它引用的独立音轨子列表（音频混在视频里时为 null）。
 * 主列表很快过期，子列表地址长期有效：起播时取一次主列表记下这两个地址，之后只刷新它们。
 */
data class StreamRendition(
    var video: URI,
    var audio: URI? = null,
    var videoAttributes: List<HLSAttribute> = emptyList(),
    var audioAttributes: List<HLSAttribute> = emptyList(),
    /** 还没从主列表选档：`video` 是主列表（或已是媒体列表）地址。 */
    var isUnselected: Boolean = false,
) {
    /**
     * 给播放器的本地主列表：只含选中的这一档，视频与音轨都指向本地合成地址。
     * 没有独立音轨时不需要主列表（直接给视频媒体列表）。
     */
    fun localMaster(base: String): String? {
        if (audio == null) return null
        val media = audioAttributes + HLSAttribute("URI", "\"$base/audio.m3u8\"")
        val lines = listOf(
            "#EXTM3U",
            "#EXT-X-VERSION:7",
            "#EXT-X-MEDIA:" + media.joinToString(",") { "${it.key}=${it.value}" },
            "#EXT-X-STREAM-INF:" + videoAttributes.joinToString(",") { "${it.key}=${it.value}" },
            "$base/video.m3u8",
        )
        return lines.joinToString("\n") + "\n"
    }

    companion object {
        private val streamKeys = setOf(
            "BANDWIDTH",
            "AVERAGE-BANDWIDTH",
            "RESOLUTION",
            "CODECS",
            "FRAME-RATE",
            "VIDEO-RANGE",
            "AUDIO",
        )

        fun unselected(master: URI): StreamRendition = StreamRendition(video = master, isUnselected = true)

        /** 最高的不超过 `maxBandwidth` 的档（都超过则取最低），连同它引用的默认音轨。带宽比较放宽 20_000。 */
        fun select(playlist: String, master: URI, maxBandwidth: Double): StreamRendition? {
            val lines = playlist.lineSequence().map { it.trim() }.toList()
            val variants = mutableListOf<Variant>()
            val audioMedia = mutableListOf<List<HLSAttribute>>()
            for ((index, line) in lines.withIndex()) {
                if (line.startsWith("#EXT-X-MEDIA:")) {
                    val attributes = HLSAttribute.parse(line.removePrefix("#EXT-X-MEDIA:"))
                    if (HLSAttribute.value("TYPE", attributes) == "AUDIO") audioMedia.add(attributes)
                } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    val attributes = HLSAttribute.parse(line.removePrefix("#EXT-X-STREAM-INF:"))
                    val bandwidth = HLSAttribute.value("BANDWIDTH", attributes)?.toIntOrNull() ?: continue
                    val uri = lines.drop(index + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
                    variants.add(Variant(bandwidth, attributes, uri))
                }
            }
            val eligible = variants.filter { it.bandwidth.toDouble() <= maxBandwidth + 20_000 }
            val chosen = eligible.maxByOrNull { it.bandwidth } ?: variants.minByOrNull { it.bandwidth } ?: return null
            val video = resolve(chosen.uri, master) ?: return null
            var rendition = StreamRendition(
                video = video,
                videoAttributes = chosen.attributes.filter { it.key in streamKeys },
            )
            val group = HLSAttribute.value("AUDIO", chosen.attributes)
            if (group != null) {
                val candidates = audioMedia.filter { attributes ->
                    HLSAttribute.value("GROUP-ID", attributes) == group &&
                        HLSAttribute.value("URI", attributes) != null
                }
                val pick = candidates.firstOrNull { HLSAttribute.value("DEFAULT", it) == "YES" } ?: candidates.firstOrNull()
                val audioUri = pick?.let { HLSAttribute.value("URI", it) }
                val audio = audioUri?.let { resolve(it, master) }
                if (pick != null && audio != null) {
                    rendition.audio = audio
                    rendition.audioAttributes = pick.filter { it.key != "URI" }
                } else {
                    rendition.videoAttributes = rendition.videoAttributes.filter { it.key != "AUDIO" }
                }
            }
            return rendition
        }

        private fun resolve(reference: String, base: URI): URI? {
            val parsed = try {
                URI(reference)
            } catch (_: URISyntaxException) {
                return null
            }
            if (parsed.scheme != null) return parsed
            return try {
                val resolved = base.resolve(reference)
                if (resolved.isAbsolute) resolved else null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private data class Variant(
            val bandwidth: Int,
            val attributes: List<HLSAttribute>,
            val uri: String,
        )
    }
}
