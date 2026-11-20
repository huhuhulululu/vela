package vela.app.spike

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.util.concurrent.ConcurrentHashMap

internal enum class SegmentAddressMode {
    /** 改写后的列表里，分片行是上游的绝对地址。 */
    REAL,

    /** 分片行是 vela://lane/<id>/seg/<序号>，真实地址只留在序号表里。 */
    SYNTHETIC,
}

@OptIn(UnstableApi::class)
internal class SpikeSourceFactory(
    val upstream: FakeUpstream,
    val segmentMode: SegmentAddressMode = SegmentAddressMode.REAL,
    val stripLowLatency: Boolean = true,
    val renewOn403: Boolean = false,
    val renewDelayMs: Long = 0L,
    val onRenew: (() -> Unit)? = null,
    val laneId: String = "a",
) : DataSource.Factory {
    val segmentMap = ConcurrentHashMap<Long, String>()
    var playlistFetches: Int = 0
        private set

    override fun createDataSource(): DataSource = SpikeSource(this)

    fun remember(sequence: Long, realUrl: String) {
        segmentMap[sequence] = realUrl
    }

    fun notePlaylistFetch() {
        playlistFetches++
    }
}

@OptIn(UnstableApi::class)
internal class SpikeSource(
    private val factory: SpikeSourceFactory,
) : BaseDataSource(/* isNetwork = */ true) {
    private var body: ByteArray = ByteArray(0)
    private var position: Int = 0
    private var opened: Boolean = false
    private var reportedUri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val loaded = load(dataSpec)
        body = loaded.bytes
        reportedUri = loaded.reported
        val start = dataSpec.position.toInt().coerceAtLeast(0)
        position = start.coerceAtMost(body.size)
        opened = true
        transferStarted(dataSpec)
        val remaining = (body.size - position).toLong()
        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, remaining) else remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= body.size) return C.RESULT_END_OF_INPUT
        val n = minOf(length, body.size - position)
        body.copyInto(buffer, offset, position, position + n)
        position += n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = reportedUri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        body = ByteArray(0)
    }

    private fun load(dataSpec: DataSpec): Loaded {
        val uri = dataSpec.uri
        if (uri.scheme == "vela") return loadSynthetic(uri)
        return loadHttp(uri.toString(), reportReal = uri)
    }

    private fun loadSynthetic(uri: Uri): Loaded {
        val path = uri.path.orEmpty()
        val sequence = path.substringAfterLast("/seg/", "").toLongOrNull()
        if (sequence != null) {
            val real = factory.segmentMap[sequence]
                ?: "${factory.upstream.baseUrl}/seg/s$sequence.ts?t=${factory.upstream.exposeToken}"
            val response = getWithRenew(real)
            if (response.code !in 200..299) throw badStatus(response, uri)
            val reported = if (factory.segmentMode == SegmentAddressMode.REAL) Uri.parse(real) else uri
            return Loaded(response.body, reported)
        }
        val upstreamUrl = upstreamPlaylist(uri)
        val response = getWithRenew(upstreamUrl)
        if (response.code !in 200..299) throw badStatus(response, uri)
        val text = rewritePlaylist(response.body.toString(Charsets.UTF_8))
        return Loaded(text.toByteArray(Charsets.UTF_8), uri)
    }

    private fun loadHttp(url: String, reportReal: Uri): Loaded {
        val response = getWithRenew(url)
        if (response.code !in 200..299) throw badStatus(response, reportReal)
        val bytes = if (url.contains(".m3u8")) {
            rewritePlaylist(response.body.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        } else {
            response.body
        }
        return Loaded(bytes, reportReal)
    }

    private fun upstreamPlaylist(uri: Uri): String {
        val path = uri.path.orEmpty()
        val base = when {
            path.endsWith("/audio.m3u8") -> factory.upstream.audioPlaylistUrl()
            path.endsWith("/master.m3u8") -> factory.upstream.masterPlaylistUrl()
            else -> factory.upstream.videoPlaylistUrl()
        }
        val query = uri.query
        return if (query.isNullOrEmpty()) base else "$base?$query"
    }

    private fun getWithRenew(url: String): SpikeHttpResponse {
        var response = SpikeHttp.get(url)
        if (response.code == 403 && factory.renewOn403) {
            if (factory.renewDelayMs > 0) Thread.sleep(factory.renewDelayMs)
            factory.onRenew?.invoke()
            response = SpikeHttp.get(url)
        }
        return response
    }

    private fun rewritePlaylist(text: String): String {
        if (!text.contains("#EXTINF") && !text.contains("#EXT-X-STREAM-INF")) return text
        if (text.contains("#EXT-X-STREAM-INF")) return text
        factory.notePlaylistFetch()
        val lines = text.split('\n')
        var sequence = 0L
        var pendingInf = false
        val out = ArrayList<String>(lines.size + 1)
        var sawSegment = false
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (factory.stripLowLatency && isLowLatencyTag(line)) continue
            if (factory.stripLowLatency && line.startsWith("#EXT-X-VERSION")) {
                out.add("#EXT-X-VERSION:7")
                continue
            }
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                out.add(line)
                continue
            }
            if (line.startsWith("#EXTINF:")) {
                pendingInf = true
                out.add(line)
                continue
            }
            if (pendingInf && !line.startsWith("#")) {
                pendingInf = false
                sawSegment = true
                val real = absolutize(line)
                factory.remember(sequence, real)
                val shown = if (factory.segmentMode == SegmentAddressMode.SYNTHETIC) {
                    "vela://lane/${factory.laneId}/seg/$sequence"
                } else {
                    real
                }
                out.add(shown)
                sequence++
                continue
            }
            out.add(line)
        }
        if (!sawSegment) return text
        if (factory.stripLowLatency && out.none { it.startsWith("#EXT-X-VERSION") }) {
            val at = if (out.firstOrNull() == "#EXTM3U") 1 else 0
            out.add(at, "#EXT-X-VERSION:7")
        }
        return out.joinToString("\n") + "\n"
    }

    private fun absolutize(reference: String): String {
        if (reference.startsWith("http://") || reference.startsWith("https://") || reference.startsWith("vela://")) {
            return reference
        }
        return factory.upstream.baseUrl.trimEnd('/') + "/" + reference.trimStart('/')
    }

    private fun badStatus(response: SpikeHttpResponse, uri: Uri): HttpDataSource.InvalidResponseCodeException {
        return HttpDataSource.InvalidResponseCodeException(
            response.code,
            null,
            null,
            emptyMap(),
            DataSpec(uri),
            response.body,
        )
    }

    private data class Loaded(val bytes: ByteArray, val reported: Uri)

    companion object {
        private fun isLowLatencyTag(line: String): Boolean {
            return line.startsWith("#EXT-X-SERVER-CONTROL") ||
                line.startsWith("#EXT-X-PART") ||
                line.startsWith("#EXT-X-PRELOAD-HINT") ||
                line.startsWith("#EXT-X-SKIP") ||
                line.startsWith("#EXT-X-RENDITION-REPORT")
        }
    }
}
