package vela.core.base.net

import java.time.Duration
import java.time.Instant
import vela.core.base.site.looksLikeChallenge

enum class SiteEndpoint {
    /** POST /get_edge_hls_url_ajax/ */
    streamURL,

    /** GET /api/ts/roomlist/room-list/ */
    catalog,

    /** GET /api/chatvideocontext/<用户名>/ */
    chatContext,

    /** GET /api/biocontext/<用户名>/ */
    bioContext,
}

/** 响应正文的形态判断。 */
object ResponseShape {
    /**
     * 去掉前导空白（含一次完整 UTF-8 BOM）后，第一个非空白字节是 `{` 或 `[`。
     * 只看开头。空白只有空格、制表、LF、CR。
     */
    fun looksLikeJSON(data: ByteArray): Boolean {
        var index = 0
        while (index < data.size && isWhitespace(u(data, index))) index++
        if (index + 3 <= data.size &&
            u(data, index) == 0xEF &&
            u(data, index + 1) == 0xBB &&
            u(data, index + 2) == 0xBF
        ) {
            index += 3
        }
        while (index < data.size && isWhitespace(u(data, index))) index++
        if (index >= data.size) return false
        val first = u(data, index)
        return first == '{'.code || first == '['.code
    }

    /**
     * 应当退避：正文既不是 JSON 也不是质询页。
     * [definitive] 里的状态码本身就是确定答案，正文是什么都不退避。
     */
    fun shouldBackOff(status: Int, data: ByteArray, definitive: Set<Int> = emptySet()): Boolean {
        if (status in definitive) return false
        return !looksLikeChallenge(data) && !looksLikeJSON(data)
    }

    private fun u(data: ByteArray, index: Int): Int = data[index].toInt() and 0xFF

    private fun isWhitespace(byte: Int): Boolean =
        byte == 0x20 || byte == 0x09 || byte == 0x0A || byte == 0x0D
}

/**
 * 按接口的退避窗口。Swift 里是值类型；这里是类，由 [EndpointBackoffBox] 独自持有。
 */
class EndpointBackoff {
    private val until: MutableMap<SiteEndpoint, Instant> = HashMap()

    fun allows(endpoint: SiteEndpoint, now: Instant): Boolean {
        val deadline = until[endpoint] ?: return true
        return !now.isBefore(deadline)
    }

    /** 从 [now] 起 [window] 秒内不再请求该接口。 */
    fun record(endpoint: SiteEndpoint, now: Instant) {
        val nanos = (window * 1_000_000_000.0).toLong()
        until[endpoint] = now.plus(Duration.ofNanos(nanos))
    }

    fun reset() {
        until.clear()
    }

    override fun equals(other: Any?): Boolean = other is EndpointBackoff && other.until == until

    override fun hashCode(): Int = until.hashCode()

    companion object {
        const val window: Double = 30.0
        const val waitingNote: String = "站点暂时限流，稍后自动重试"
        const val heldNote: String = "站点暂时限流，可稍后手动重试"
        const val catalogNote: String = "站点暂时限流，请稍后再试"

        fun pendingNote(playbackAllowed: Boolean): String = if (playbackAllowed) waitingNote else heldNote
    }
}

/** 处于退避窗口内、没有发出请求。 */
class EndpointBackedOff(val endpoint: SiteEndpoint) : Exception() {
    override fun equals(other: Any?): Boolean = other is EndpointBackedOff && other.endpoint == endpoint

    override fun hashCode(): Int = endpoint.hashCode()
}

/** 进程内共用的退避记录。App 用 [shared]；测试各建一份。 */
class EndpointBackoffBox(private val now: () -> Instant = { Instant.now() }) {
    private val lock = Any()
    private val state = EndpointBackoff()

    fun allows(endpoint: SiteEndpoint): Boolean {
        val at = now()
        synchronized(lock) {
            return state.allows(endpoint, at)
        }
    }

    fun record(endpoint: SiteEndpoint) {
        val at = now()
        synchronized(lock) {
            state.record(endpoint, at)
        }
    }

    /** 应当退避就登记并返回 true。 */
    fun note(
        endpoint: SiteEndpoint,
        status: Int,
        data: ByteArray,
        definitive: Set<Int> = emptySet(),
    ): Boolean {
        if (!ResponseShape.shouldBackOff(status, data, definitive)) return false
        record(endpoint)
        return true
    }

    fun reset() {
        synchronized(lock) {
            state.reset()
        }
    }

    companion object {
        val shared: EndpointBackoffBox = EndpointBackoffBox()
    }
}
