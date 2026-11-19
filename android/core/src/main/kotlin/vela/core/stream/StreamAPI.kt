package vela.core.stream

import java.net.URI
import java.util.Locale
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import vela.core.base.model.ShowState
import vela.core.base.model.trimSwiftWhitespace
import vela.core.base.net.EndpointBackoffBox
import vela.core.base.net.EndpointBackedOff
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPHeaders
import vela.core.base.net.HTTPRequest
import vela.core.base.net.SiteEndpoint
import vela.core.base.net.percentEncode
import vela.core.base.site.looksLikeChallenge

/** 直连取流失败原因。可播放时为 null。 */
enum class StreamFailure {
    geo,
    privateShow,
    group,
    away,
    offline,
    hidden,
    password,
    unavailable,
}

/** `get_edge_hls_url_ajax` 解出来的一张票。 */
data class StreamTicket(
    val success: Boolean,
    val url: URI?,
    val status: ShowState,
    val message: String = "",
) {
    val isPlayable: Boolean
        get() = success && url != null && status.isPlayable

    /**
     * 接口实测确认的非公开状态。只有 private / group / hidden / password / offline 才算。
     * 接口失败带来的 unknown、暂时离开、公开但没地址都不是，不能据此把房间从列表筛掉。
     */
    val delistedShow: ShowState?
        get() = when (status) {
            ShowState.privateShow,
            ShowState.group,
            ShowState.hidden,
            ShowState.password,
            ShowState.offline,
            -> status
            ShowState.publicShow, ShowState.away, ShowState.unknown -> null
        }

    val failure: StreamFailure?
        get() {
            if (isPlayable) return null
            if (status == ShowState.publicShow && url == null) return StreamFailure.geo
            return when (status) {
                ShowState.publicShow -> StreamFailure.unavailable
                ShowState.privateShow -> StreamFailure.privateShow
                ShowState.group -> StreamFailure.group
                ShowState.away -> StreamFailure.away
                ShowState.offline, ShowState.unknown -> StreamFailure.offline
                ShowState.hidden -> StreamFailure.hidden
                ShowState.password -> StreamFailure.password
            }
        }
}

/**
 * 取流解码失败。
 * 质询要用户确认；403 / 503 且不是质询页按暂时无法播放，可自动跳过。
 */
sealed class StreamDecodeError : Exception() {
    /** 正文命中站点质询页（`looksLikeChallenge`）。 */
    data object Challenge : StreamDecodeError()

    /** 403 / 503 但正文不是质询页。 */
    data class Unavailable(val status: Int) : StreamDecodeError()

    data object Malformed : StreamDecodeError()

    /** 只有 [Challenge] 为 true。 */
    val isChallenge: Boolean
        get() = this is Challenge

    /** 普通不可用对应的失败原因。质询与格式错误为 null。 */
    val failure: StreamFailure?
        get() = if (this is Unavailable) StreamFailure.unavailable else null
}

object StreamDecoder {
    fun decode(status: Int, data: ByteArray): StreamTicket {
        if (looksLikeChallenge(data)) throw StreamDecodeError.Challenge
        if (status == 403 || status == 503) throw StreamDecodeError.Unavailable(status)
        if (status !in 200..299) throw StreamDecodeError.Malformed
        val payload = streamPayload(data) ?: throw StreamDecodeError.Malformed
        return StreamTicket(
            success = payload.success,
            url = httpsUrl(payload.url),
            status = ShowState.fromApi(payload.roomStatus),
            message = payload.hiddenMessage ?: "",
        )
    }

    /**
     * 直连取流地址。退避窗口内不发请求，抛 [EndpointBackedOff]（与真实 403 / 503 的 [StreamDecodeError.Unavailable] 分开）。
     * 正文不是 JSON 也不是质询页时登记退避，按暂时不可用抛 [StreamDecodeError.Unavailable]。网络错误原样抛出。
     */
    suspend fun fetch(
        username: String,
        client: HTTPClient,
        backoff: EndpointBackoffBox? = EndpointBackoffBox.shared,
    ): StreamTicket {
        if (backoff != null && !backoff.allows(SiteEndpoint.streamURL)) {
            throw EndpointBackedOff(SiteEndpoint.streamURL)
        }
        val result = client.send(StreamRequest.make(username))
        if (backoff?.note(SiteEndpoint.streamURL, status = result.status, data = result.data) == true) {
            throw StreamDecodeError.Unavailable(result.status)
        }
        return decode(result.status, result.data)
    }
}

object StreamRequest {
    fun make(username: String): HTTPRequest {
        val slug = percentEncode(username, ::isQueryAllowed)
        val drafted = HTTPRequest(
            url = "https://chaturbate.com/get_edge_hls_url_ajax/",
            method = "POST",
            body = "room_slug=$slug".toByteArray(Charsets.UTF_8),
        )
        return HTTPHeaders.applyBrowser(drafted, referer = "https://chaturbate.com/$username/")
            .withHeader("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
    }
}

/**
 * 只收 https。空白按 Swift `whitespacesAndNewlines` 裁掉。
 * `URL(string:)` 失败时为 null。
 */
internal fun httpsUrl(raw: String?): URI? {
    if (raw == null) return null
    val trimmed = raw.trimSwiftWhitespace()
    if (!trimmed.startsWith("https://")) return null
    return try {
        URI(trimmed)
    } catch (_: java.net.URISyntaxException) {
        null
    }
}

/** Apple `CharacterSet.urlQueryAllowed`：可打印 ASCII 去掉空格以及 `"#%<>[\]^`{|}`。 */
private fun isQueryAllowed(byte: Int): Boolean {
    if (byte in 'A'.code..'Z'.code || byte in 'a'.code..'z'.code || byte in '0'.code..'9'.code) return true
    return when (byte.toChar()) {
        '!', '$', '&', '\'', '(', ')', '*', '+', ',', '-', '.', '/',
        ':', ';', '=', '?', '@', '_', '~',
        -> true
        else -> false
    }
}

private val looseLocale: Locale = Locale.ROOT

/** Swift `LooseBool`：布尔、整数 1、字符串 "1" / "true"。对象或数组让整段解码失败。 */
internal fun looseOk(element: JsonElement?): Boolean {
    if (element == null || element is JsonNull) return false
    val primitive = element as? JsonPrimitive ?: throw StreamDecodeError.Malformed
    if (primitive.isString) {
        val text = primitive.content
        return text == "1" || text.lowercase(looseLocale) == "true"
    }
    primitive.booleanOrNull?.let { return it }
    primitive.longOrNull?.let { return it == 1L }
    return false
}
