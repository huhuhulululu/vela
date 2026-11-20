package vela.core.hls

import java.net.URI
import java.net.URISyntaxException

data class LadderVariant(
    val bandwidth: Int,
    val width: Int,
    val height: Int,
)

object PowerPolicy {
    fun peakBitrate(pixelHeight: Double, resting: Boolean): Double {
        val full = when {
            pixelHeight < 420.0 -> 896_000.0
            pixelHeight < 700.0 -> 1_696_000.0
            pixelHeight < 1100.0 -> 3_096_000.0
            else -> 5_128_000.0
        }
        return if (resting) minOf(full, 896_000.0) else full
    }
}

/**
 * 主列表选档，以及把低延迟媒体列表改成可从任意地址提供的稳态列表。
 * 带宽比较放宽 20_000，和 Swift `PlaylistLadder` 一致。
 */
object PlaylistLadder {
    fun variants(playlist: String): List<LadderVariant> {
        val found = mutableListOf<LadderVariant>()
        for (line in lines(playlist)) {
            if (!line.contains("EXT-X-STREAM-INF")) continue
            val bandwidth = integer(named = "BANDWIDTH", line) ?: continue
            val size = resolution(line)
            found.add(LadderVariant(bandwidth, size?.first ?: 0, size?.second ?: 0))
        }
        return found.sortedBy { it.bandwidth }
    }

    /**
     * 不超过 `maxBandwidth` 的最高媒体列表。都超过就用最低的一档。
     * 相对地址按 `master` 解析成绝对地址。
     */
    fun mediaURL(playlist: String, master: URI, maxBandwidth: Double): URI? {
        val rows = lines(playlist)
        var bestBandwidth = Int.MIN_VALUE
        var bestUri: String? = null
        var lowestBandwidth = Int.MAX_VALUE
        var lowestUri: String? = null
        var index = 0
        while (index < rows.size) {
            val line = rows[index]
            val bandwidth = if (line.contains("EXT-X-STREAM-INF")) integer(named = "BANDWIDTH", line) else null
            if (bandwidth != null) {
                val uri = rows.drop(index + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                if (uri != null) {
                    if (bandwidth < lowestBandwidth) {
                        lowestBandwidth = bandwidth
                        lowestUri = uri
                    }
                    if (bandwidth.toDouble() <= maxBandwidth + 20_000.0 && bandwidth > bestBandwidth) {
                        bestBandwidth = bandwidth
                        bestUri = uri
                    }
                }
            }
            index += 1
        }
        val chosen = bestUri ?: lowestUri ?: return null
        return resolve(chosen, master)
    }

    /**
     * 去掉低延迟标签，相对地址改成绝对地址。已经是主列表（含 `EXT-X-STREAM-INF`）或没有分片时返回 null。
     * 结果以换行结尾。
     */
    fun steadyPlaylist(playlist: String, base: URI): String? {
        val rows = lines(playlist)
        if (rows.any { it.contains("EXT-X-STREAM-INF") }) return null
        val output = mutableListOf<String>()
        var sawSegment = false
        for (line in rows) {
            if (line.isEmpty()) continue
            if (isLowLatencyTag(line)) continue
            if (line.startsWith("#")) {
                val rewritten = if (line.startsWith("#EXT-X-VERSION")) {
                    "#EXT-X-VERSION:7"
                } else {
                    rewriteTag(line, base)
                }
                output.add(rewritten)
                continue
            }
            sawSegment = true
            output.add(resolve(line, base)?.toString() ?: line)
        }
        if (!sawSegment) return null
        if (output.none { it.startsWith("#EXT-X-VERSION") }) {
            val insertAt = if (output.firstOrNull() == "#EXTM3U") 1 else 0
            output.add(insertAt, "#EXT-X-VERSION:7")
        }
        return output.joinToString("\n") + "\n"
    }

    fun peakBitrate(pixelHeight: Double, variants: List<LadderVariant>, resting: Boolean): Double {
        val cap = PowerPolicy.peakBitrate(pixelHeight, resting)
        val eligible = variants.filter { it.bandwidth.toDouble() <= cap + 20_000.0 }
        val match = eligible.lastOrNull()
        if (match != null) return match.bandwidth.toDouble()
        val lowest = variants.firstOrNull()
        if (lowest != null) return minOf(cap, lowest.bandwidth.toDouble())
        return cap
    }

    private fun lines(playlist: String): List<String> =
        playlist.lineSequence().map { it.trim() }.toList()

    private fun isLowLatencyTag(line: String): Boolean =
        line.startsWith("#EXT-X-SERVER-CONTROL") ||
            line.startsWith("#EXT-X-PART") ||
            line.startsWith("#EXT-X-PRELOAD-HINT") ||
            line.startsWith("#EXT-X-SKIP") ||
            line.startsWith("#EXT-X-RENDITION-REPORT")

    private fun rewriteTag(line: String, base: URI): String {
        val marker = "URI=\""
        val start = line.indexOf(marker)
        if (start < 0) return line
        val valueStart = start + marker.length
        val end = line.indexOf('"', valueStart)
        if (end < 0) return line
        val resolved = resolve(line.substring(valueStart, end), base) ?: return line
        return line.substring(0, valueStart) + resolved.toString() + line.substring(end)
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

    private fun integer(named: String, line: String): Int? {
        val marker = "$named="
        val start = line.indexOf(marker)
        if (start < 0) return null
        val tail = line.substring(start + marker.length)
        val digits = tail.takeWhile { it.isDigit() }
        return digits.toIntOrNull()
    }

    private fun resolution(line: String): Pair<Int, Int>? {
        val marker = "RESOLUTION="
        val start = line.indexOf(marker)
        if (start < 0) return null
        val tail = line.substring(start + marker.length)
        val token = tail.takeWhile { it != ',' && !it.isWhitespace() }
        val parts = token.split('x')
        if (parts.size != 2) return null
        val width = parts[0].toIntOrNull() ?: return null
        val height = parts[1].toIntOrNull() ?: return null
        return width to height
    }
}
