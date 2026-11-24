package vela.app.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import vela.core.sync.SyncEncoding
import vela.core.sync.SyncHttp
import vela.core.sync.sha256Hex
import vela.core.sync.signMessage
import vela.core.sync.verifyEcdsa

/**
 * 只绑定 `127.0.0.1:0` 的假同步服务端（验收 6）：实现 v2 协议真验签、版本 CAS（409 带体）、
 * 404/410/403/507/429/302/超大体等故障注入，结束后通过 [close] 释放端口与线程池。
 */
internal class FakeSyncServer(
    val epoch: String = "0123456789abcdef0123456789abcdef",
    var nowMs: () -> Long = { 1_800_000_000_000L },
) : AutoCloseable {
    data class RecordedRequest(
        val method: String,
        val path: String,
        val userAgent: String?,
        val cookie: String?,
    )

    data class StoredBlob(
        var ver: Long,
        val pub: String,
        var data: String,
    )

    sealed class Fault {
        data object Forbidden : Fault()
        data object QuotaFull : Fault()
        data class RateLimited(val retryAfterHeader: String?) : Fault()
        data object Redirect302 : Fault()
        data class OversizedBody(val bytes: Int) : Fault()
        data object ForceNotFound : Fault()
        data object SetCookieOnNext : Fault()
    }

    private val lock = Any()
    private val blobs = HashMap<String, StoredBlob>()
    private val deleted = HashMap<String, Long>()
    private val faults = ArrayDeque<Fault>()
    private val json = Json { ignoreUnknownKeys = true }

    val requests = CopyOnWriteArrayList<RecordedRequest>()
    @Volatile
    var redirectTargetHits: Int = 0

    private val executor = Executors.newCachedThreadPool()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        setExecutor(executor)
        createContext("/v2/blob/") { exchange -> handleBlob(exchange) }
        createContext("/redirect-target") { exchange ->
            redirectTargetHits += 1
            sendJson(exchange, 200, """{"unexpected":true}""")
        }
        start()
    }

    val port: Int get() = server.address.port
    val baseUrl: String get() = "http://127.0.0.1:$port"

    fun enqueueFault(fault: Fault) {
        synchronized(lock) { faults.addLast(fault) }
    }

    fun dropBlob(blobID: String) {
        synchronized(lock) {
            blobs.remove(blobID)
            deleted.remove(blobID)
        }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun handleBlob(exchange: HttpExchange) {
        try {
            val method = exchange.requestMethod
            val path = exchange.requestURI.path
            requests.add(
                RecordedRequest(
                    method = method,
                    path = path,
                    userAgent = exchange.requestHeaders.getFirst("User-Agent"),
                    cookie = exchange.requestHeaders.getFirst("Cookie"),
                ),
            )
            val fault = synchronized(lock) { if (faults.isNotEmpty()) faults.removeFirst() else null }
            if (fault != null) {
                when (fault) {
                    Fault.Forbidden -> {
                        sendJson(exchange, 403, """{"error":"forbidden"}""")
                        return
                    }
                    Fault.QuotaFull -> {
                        sendJson(exchange, 507, """{"error":"quota"}""")
                        return
                    }
                    is Fault.RateLimited -> {
                        if (fault.retryAfterHeader != null) {
                            exchange.responseHeaders.add("Retry-After", fault.retryAfterHeader)
                        }
                        sendJson(exchange, 429, """{"error":"rate"}""")
                        return
                    }
                    Fault.Redirect302 -> {
                        exchange.responseHeaders.add("Location", "$baseUrl/redirect-target")
                        exchange.sendResponseHeaders(302, -1)
                        exchange.close()
                        return
                    }
                    is Fault.OversizedBody -> {
                        val payload = ByteArray(fault.bytes) { 'a'.code.toByte() }
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, payload.size.toLong())
                        exchange.responseBody.use { it.write(payload) }
                        return
                    }
                    Fault.ForceNotFound -> {
                        sendJson(exchange, 404, """{"epoch":"$epoch","now":${nowMs()}}""")
                        return
                    }
                    Fault.SetCookieOnNext -> {
                        exchange.responseHeaders.add("Set-Cookie", "vela_test=secret; Path=/")
                    }
                }
            }

            val rest = path.removePrefix("/v2/blob/")
            val isDelete = rest.endsWith("/delete")
            val blobID = if (isDelete) rest.removeSuffix("/delete") else rest
            when {
                method == "GET" && !isDelete -> handleGet(exchange, blobID)
                method == "PUT" && !isDelete -> handlePut(exchange, blobID)
                method == "POST" && isDelete -> handleDelete(exchange, blobID)
                else -> sendJson(exchange, 405, """{"error":"method"}""")
            }
        } catch (_: Exception) {
            runCatching { sendJson(exchange, 500, """{"error":"internal"}""") }
        }
    }

    private fun handleGet(exchange: HttpExchange, blobID: String) {
        val now = nowMs()
        val (status, body) = synchronized(lock) {
            val goneAt = deleted[blobID]
            if (goneAt != null) {
                410 to """{"deletedAt":$goneAt,"epoch":"$epoch","now":$now}"""
            } else {
                val blob = blobs[blobID]
                if (blob == null) {
                    404 to """{"epoch":"$epoch","now":$now}"""
                } else {
                    200 to blobJson(blob, now)
                }
            }
        }
        sendJson(exchange, status, body)
    }

    private fun handlePut(exchange: HttpExchange, blobID: String) {
        val raw = exchange.requestBody.readBytes()
        if (raw.size > SyncHttp.maxResponseBytes) {
            sendJson(exchange, 413, """{"error":"too_large"}""")
            return
        }
        val obj = runCatching { json.parseToJsonElement(String(raw, Charsets.UTF_8)) as? JsonObject }.getOrNull()
        if (obj == null) {
            sendJson(exchange, 400, """{"error":"json"}""")
            return
        }
        val base = (obj["base"] as? JsonPrimitive)?.longOrNull
        val data = (obj["data"] as? JsonPrimitive)?.contentOrNull
        val sigText = (obj["sig"] as? JsonPrimitive)?.contentOrNull
        val reqPub = (obj["pub"] as? JsonPrimitive)?.contentOrNull
        if (base == null || data == null || sigText == null) {
            sendJson(exchange, 400, """{"error":"fields"}""")
            return
        }
        val now = nowMs()
        val (status, body) = synchronized(lock) {
            val goneAt = deleted[blobID]
            if (goneAt != null) {
                return@synchronized 410 to """{"deletedAt":$goneAt,"epoch":"$epoch","now":$now}"""
            }
            val existing = blobs[blobID]
            val verifyPub = existing?.pub ?: reqPub
            if (verifyPub == null) {
                return@synchronized 403 to """{"error":"no_pub"}"""
            }
            val pubBytes = SyncEncoding.decodeBase64url(verifyPub)
            val sigBytes = SyncEncoding.decodeBase64url(sigText)
            val msg = signMessage("put", blobID, epoch, base, base + 1, sha256Hex(data.toByteArray(Charsets.UTF_8)))
            if (pubBytes == null || sigBytes == null || !verifyEcdsa(pubBytes, msg, sigBytes)) {
                return@synchronized 403 to """{"error":"bad_sig"}"""
            }
            if (existing == null) {
                if (base != 0L) {
                    return@synchronized 404 to """{"epoch":"$epoch","now":$now}"""
                }
                val stored = StoredBlob(ver = 1L, pub = verifyPub, data = data)
                blobs[blobID] = stored
                200 to """{"ver":1,"epoch":"$epoch","now":$now}"""
            } else {
                if (base != existing.ver) {
                    return@synchronized 409 to blobJson(existing, now)
                }
                existing.ver += 1
                existing.data = data
                200 to """{"ver":${existing.ver},"epoch":"$epoch","now":$now}"""
            }
        }
        sendJson(exchange, status, body)
    }

    private fun handleDelete(exchange: HttpExchange, blobID: String) {
        val raw = exchange.requestBody.readBytes()
        val obj = runCatching { json.parseToJsonElement(String(raw, Charsets.UTF_8)) as? JsonObject }.getOrNull()
        val base = (obj?.get("base") as? JsonPrimitive)?.longOrNull
        val sigText = (obj?.get("sig") as? JsonPrimitive)?.contentOrNull
        if (base == null || sigText == null) {
            sendJson(exchange, 400, """{"error":"fields"}""")
            return
        }
        val now = nowMs()
        val (status, body) = synchronized(lock) {
            val goneAt = deleted[blobID]
            if (goneAt != null) {
                return@synchronized 410 to """{"deletedAt":$goneAt,"epoch":"$epoch","now":$now}"""
            }
            val existing = blobs[blobID]
                ?: return@synchronized 404 to """{"epoch":"$epoch","now":$now}"""
            val pubBytes = SyncEncoding.decodeBase64url(existing.pub)
            val sigBytes = SyncEncoding.decodeBase64url(sigText)
            val msg = signMessage("delete", blobID, epoch, base, base + 1, "-")
            if (pubBytes == null || sigBytes == null || !verifyEcdsa(pubBytes, msg, sigBytes)) {
                return@synchronized 403 to """{"error":"bad_sig"}"""
            }
            if (base != existing.ver) {
                return@synchronized 409 to blobJson(existing, now)
            }
            blobs.remove(blobID)
            deleted[blobID] = now
            200 to """{"deletedAt":$now,"epoch":"$epoch","now":$now}"""
        }
        sendJson(exchange, status, body)
    }

    private fun blobJson(blob: StoredBlob, now: Long): String =
        """{"ver":${blob.ver},"pub":"${blob.pub}","data":"${blob.data}","epoch":"$epoch","now":$now}"""

    private fun sendJson(exchange: HttpExchange, status: Int, jsonText: String) {
        val bytes = jsonText.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
