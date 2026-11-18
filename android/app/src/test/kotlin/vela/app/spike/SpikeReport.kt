package vela.app.spike

internal data class HitWindow(
    val requests: Int,
    val perMinute: Double,
    val videoPlaylists: Int,
    val audioPlaylists: Int,
    val audioSegments: Int,
    val mediaSegments: Int,
    val longConnections: Int,
    val maxConcurrent: Int,
)

internal fun FakeUpstream.window(fromMs: Long, toMs: Long): HitWindow {
    val hits = snapshotHits().filter { it.startedMs in fromMs..toMs }
    val elapsed = (toMs - fromMs).coerceAtLeast(1)
    return HitWindow(
        requests = hits.size,
        perMinute = hits.size * 60_000.0 / elapsed,
        videoPlaylists = hits.count { it.path.endsWith("video.m3u8") || it.path.endsWith("master.m3u8") },
        audioPlaylists = hits.count { it.path.endsWith("audio.m3u8") },
        audioSegments = hits.count { it.path.endsWith(".aac") },
        mediaSegments = hits.count { it.path.endsWith(".ts") },
        longConnections = hits.count { it.heldMs >= 200 },
        maxConcurrent = maxConcurrent(),
    )
}

internal object SpikeReport {
    fun emit(test: String, values: Map<String, Any?>) {
        val body = values.entries.joinToString(",") { (key, value) ->
            "\"${escape(key)}\":${json(value)}"
        }
        println("SPIKE_JSON {\"test\":\"${escape(test)}\",$body}")
    }

    private fun json(value: Any?): String = when (value) {
        null -> "null"
        is Boolean, is Int, is Long -> value.toString()
        is Double, is Float -> value.toString()
        else -> "\"${escape(value.toString())}\""
    }

    private fun escape(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")
}
