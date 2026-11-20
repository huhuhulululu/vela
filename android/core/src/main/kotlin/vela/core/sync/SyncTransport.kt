package vela.core.sync

/** 服务端响应。所有 JSON 响应都带 epoch 与 now（部分错误由传输层填好）。 */
sealed class SyncServerResponse {
    data class Blob(val ver: Long, val pub: String, val data: String, val epoch: String, val now: Long) : SyncServerResponse()
    data class NotFound(val epoch: String, val now: Long) : SyncServerResponse()
    data class Gone(val deletedAt: Long) : SyncServerResponse()
    data class Stored(val ver: Long, val epoch: String, val now: Long) : SyncServerResponse()
    data class Conflict(val ver: Long, val pub: String, val data: String, val epoch: String, val now: Long) : SyncServerResponse()
    data object Deleted : SyncServerResponse()
    data object Forbidden : SyncServerResponse()
    data object PayloadTooLarge : SyncServerResponse()
    data class RateLimited(val retryAfter: Int) : SyncServerResponse()
    data object QuotaFull : SyncServerResponse()
    data object Transient : SyncServerResponse()
}

data class SyncPutRequest(val base: Long, val next: Long, val envelope: String, val epoch: String?) {
    val includesPub: Boolean get() = base == 0L
}

interface SyncTransport {
    suspend fun get(): SyncServerResponse
    suspend fun put(request: SyncPutRequest): SyncServerResponse
    suspend fun delete(base: Long, epoch: String?): SyncServerResponse
}

sealed class SyncOpenResult {
    data class Plaintext(val bytes: ByteArray) : SyncOpenResult() {
        override fun equals(other: Any?): Boolean {
            val o = other as? Plaintext ?: return false
            return bytes.contentEquals(o.bytes)
        }
        override fun hashCode(): Int = bytes.contentHashCode()
    }
    data object Unsupported : SyncOpenResult()
    data object Failed : SyncOpenResult()
}

interface SyncEnvelopeCodec {
    val publicKey: String
    fun seal(plaintext: ByteArray, ver: Long): String?
    fun open(envelope: String, ver: Long): SyncOpenResult
    fun digest(envelope: String): String
}
