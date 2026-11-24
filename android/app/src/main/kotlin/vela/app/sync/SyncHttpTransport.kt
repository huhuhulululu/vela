package vela.app.sync

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import vela.core.sync.SyncDerived
import vela.core.sync.SyncEncoding
import vela.core.sync.SyncHttp
import vela.core.sync.SyncPutRequest
import vela.core.sync.SyncServerResponse
import vela.core.sync.SyncTransport
import vela.core.sync.sha256Hex
import vela.core.sync.signEcdsa
import vela.core.sync.signMessage

/**
 * 基于平台 `HttpURLConnection` 的同步传输（docs/plans/sync.md 第 4.3、9、10.1 节）：
 * 不引入新依赖、不跟随重定向、不带 Cookie、不使用缓存，`User-Agent` 固定为 `Vela-Sync/2`，
 * 响应体流式读取严格封顶于 [SyncHttp.maxResponseBytes]（802,048 字节）。
 */
class SyncHttpTransport(
    base: String,
    val keys: SyncDerived,
    private val timeoutMs: Int = REQUEST_TIMEOUT_MS,
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SyncTransport {
    private val normalizedBase = base.trimEnd('/')
    val blobUrl: String = "$normalizedBase/v2/blob/${keys.blobID}"
    val deleteUrl: String = "$blobUrl/delete"

    override suspend fun get(): SyncServerResponse =
        send(blobUrl, method = "GET", body = null, kind = SyncHttp.Kind.GET)

    override suspend fun put(request: SyncPutRequest): SyncServerResponse {
        val epoch = request.epoch ?: return SyncServerResponse.Transient
        val hash = sha256Hex(request.envelope.toByteArray(Charsets.UTF_8))
        val msg = signMessage("put", keys.blobID, epoch, request.base, request.base + 1, hash)
        val sig = try {
            SyncEncoding.base64url(signEcdsa(keys.d, msg))
        } catch (_: Exception) {
            return SyncServerResponse.Transient
        }
        val jsonBody = buildString {
            append("""{"base":${request.base},"data":"${request.envelope}"""")
            if (request.includesPub) {
                append(""","pub":"${keys.pubB64}"""")
            }
            append(""","sig":"$sig"}""")
        }.toByteArray(Charsets.UTF_8)
        return send(blobUrl, method = "PUT", body = jsonBody, kind = SyncHttp.Kind.PUT)
    }

    override suspend fun delete(base: Long, epoch: String?): SyncServerResponse {
        val currentEpoch = epoch ?: return SyncServerResponse.Transient
        val msg = signMessage("delete", keys.blobID, currentEpoch, base, base + 1, "-")
        val sig = try {
            SyncEncoding.base64url(signEcdsa(keys.d, msg))
        } catch (_: Exception) {
            return SyncServerResponse.Transient
        }
        val jsonBody = """{"base":$base,"sig":"$sig"}""".toByteArray(Charsets.UTF_8)
        return send(deleteUrl, method = "POST", body = jsonBody, kind = SyncHttp.Kind.DELETE)
    }

    private suspend fun send(
        urlText: String,
        method: String,
        body: ByteArray?,
        kind: SyncHttp.Kind,
    ): SyncServerResponse = withContext(dispatcher) {
        val conn = try {
            URL(urlText).openConnection() as HttpURLConnection
        } catch (_: Exception) {
            return@withContext SyncServerResponse.Transient
        }
        val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause != null) runCatching { conn.disconnect() }
        }
        try {
            conn.requestMethod = method
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Cache-Control", "no-store")
            conn.setRequestProperty("Accept-Encoding", "identity")
            for ((k, v) in extraHeaders) {
                conn.setRequestProperty(k, v)
            }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            // 3xx 一律拒绝跟随并归为暂时故障。
            if (status in 300..399) return@withContext SyncServerResponse.Transient
            val declaredLength = conn.contentLengthLong
            if (declaredLength > SyncHttp.maxResponseBytes.toLong()) {
                return@withContext SyncServerResponse.Transient
            }
            val responseBytes = readBounded(conn, status) ?: return@withContext SyncServerResponse.Transient
            val headers = HashMap<String, String>()
            conn.getHeaderField("Content-Length")?.let { headers["Content-Length"] = it }
            conn.getHeaderField("Retry-After")?.let { headers["Retry-After"] = it }
            SyncHttp.interpret(status, headers, responseBytes, kind)
        } catch (_: Exception) {
            SyncServerResponse.Transient
        } finally {
            cancelHandle?.dispose()
            runCatching { conn.disconnect() }
        }
    }

    private fun readBounded(conn: HttpURLConnection, status: Int): ByteArray? {
        val stream = try {
            if (status < 400) conn.inputStream else conn.errorStream
        } catch (_: Exception) {
            conn.errorStream
        } ?: return ByteArray(0)
        return stream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > SyncHttp.maxResponseBytes) return null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }

    companion object {
        const val USER_AGENT: String = "Vela-Sync/2"
        const val REQUEST_TIMEOUT_MS: Int = 20_000
    }
}
