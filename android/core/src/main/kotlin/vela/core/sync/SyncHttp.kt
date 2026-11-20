package vela.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * HTTP 响应限制与解析（第 8、11 节）。Retry-After 夹到 1 秒到 1 天；
 * 响应体超过请求体上限就不再解析，按暂时故障处理。
 */
object SyncHttp {
    const val maxResponseBytes: Int = 802_048
    const val maxRetryAfter: Int = 86_400

    enum class Kind { GET, PUT, DELETE }

    private val json = Json { ignoreUnknownKeys = true }

    fun interpret(
        status: Int,
        headers: Map<String, String>,
        body: ByteArray,
        kind: Kind,
    ): SyncServerResponse {
        val declared = headers.entries.firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }?.value?.toLongOrNull()
        if (declared != null && declared > maxResponseBytes) return SyncServerResponse.Transient
        if (body.size > maxResponseBytes) return SyncServerResponse.Transient
        val retry = headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }?.value?.toIntOrNull()
        return parse(status, body, retry, kind)
    }

    fun parse(status: Int, body: ByteArray, retryAfter: Int?, kind: Kind): SyncServerResponse {
        if (status == 429) return SyncServerResponse.RateLimited(minOf(maxRetryAfter, maxOf(1, retryAfter ?: 60)))
        if (status == 413) return SyncServerResponse.PayloadTooLarge
        if (status == 507) return SyncServerResponse.QuotaFull
        if (status == 403) return SyncServerResponse.Forbidden
        val obj = try {
            json.parseToJsonElement(String(body, Charsets.UTF_8)) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return SyncServerResponse.Transient
        fun uint(k: String): Long? {
            val v = obj[k] as? JsonPrimitive ?: return null
            val n = v.longOrNull ?: return null
            return if (n >= 0) n else null
        }
        fun text(k: String): String? = (obj[k] as? JsonPrimitive)?.contentOrNull
        return when (status) {
            200 -> when (kind) {
                Kind.GET -> {
                    val ver = uint("ver") ?: return SyncServerResponse.Transient
                    val pub = text("pub") ?: return SyncServerResponse.Transient
                    val data = text("data") ?: return SyncServerResponse.Transient
                    val epoch = text("epoch") ?: return SyncServerResponse.Transient
                    val now = uint("now") ?: return SyncServerResponse.Transient
                    SyncServerResponse.Blob(ver, pub, data, epoch, now)
                }
                Kind.PUT -> {
                    val ver = uint("ver") ?: return SyncServerResponse.Transient
                    val epoch = text("epoch") ?: return SyncServerResponse.Transient
                    val now = uint("now") ?: return SyncServerResponse.Transient
                    SyncServerResponse.Stored(ver, epoch, now)
                }
                Kind.DELETE -> SyncServerResponse.Deleted
            }
            404 -> {
                val epoch = text("epoch") ?: return SyncServerResponse.Transient
                val now = uint("now") ?: return SyncServerResponse.Transient
                SyncServerResponse.NotFound(epoch, now)
            }
            409 -> {
                val ver = uint("ver") ?: return SyncServerResponse.Transient
                val pub = text("pub") ?: return SyncServerResponse.Transient
                val data = text("data") ?: return SyncServerResponse.Transient
                val epoch = text("epoch") ?: return SyncServerResponse.Transient
                val now = uint("now") ?: return SyncServerResponse.Transient
                SyncServerResponse.Conflict(ver, pub, data, epoch, now)
            }
            410 -> SyncServerResponse.Gone(uint("deletedAt") ?: 0)
            else -> SyncServerResponse.Transient
        }
    }
}
