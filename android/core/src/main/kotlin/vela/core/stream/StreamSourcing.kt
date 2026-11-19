package vela.core.stream

import java.net.URI
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive
import vela.core.base.model.ShowState
import vela.core.base.net.EndpointBackoffBox
import vela.core.base.net.HTTPClient
import vela.core.base.net.ResponseShape
import vela.core.base.net.SiteEndpoint
import vela.core.base.site.ChatContext
import vela.core.base.site.looksLikeChallenge
import vela.core.hls.RenewalPolicy

/** 一次解析用的是哪个来源（只记来源，不记地址）。 */
enum class StreamOrigin(val rawValue: String) {
    /** `chatvideocontext` 的 `hls_source`。 */
    chatContext("chatvideocontext"),

    /** `get_edge_hls_url_ajax` 直连。 */
    edge("ajax"),

    /** 侧车网页里的 `get_edge_hls_url_ajax`。 */
    sidecar("sidecar"),
}

/** 解析结果与来源。 */
data class SourcedTicket(
    val ticket: StreamTicket,
    val origin: StreamOrigin,
) {
    /** 不可播放时的原因（可播放为 null）。 */
    val failure: StreamFailure?
        get() = ticket.failure
}

/** 起播视角下 `chatvideocontext` 的结果。 */
sealed class ContextProbe {
    /** 200 + JSON：房间状态与 `hls_source`（字段缺失、为空或不是 https 时为 null）。 */
    data class Room(val show: ShowState, val source: URI?) : ContextProbe()

    /** 正文是质询页。 */
    data object Challenge : ContextProbe()

    /** 正文不是 JSON 也不是质询页（调用方已登记退避）。 */
    data object NotJSON : ContextProbe()

    /** 退避窗口内，没有发请求。 */
    data object BackedOff : ContextProbe()

    /** 网络失败、非 200、JSON 不合格。 */
    data object Failed : ContextProbe()
}

/**
 * 起播用的解码：只取 `room_status` 与 `hls_source`，不抛错。
 * 挂在 L0a 的 [ChatContext] 上，不改那个文件。
 */
fun ChatContext.probe(status: Int, data: ByteArray): ContextProbe {
    if (looksLikeChallenge(data)) return ContextProbe.Challenge
    if (ResponseShape.shouldBackOff(status, data, definitiveStatuses)) return ContextProbe.NotJSON
    if (status != 200) return ContextProbe.Failed
    val fields = probeFields(data) ?: return ContextProbe.Failed
    return ContextProbe.Room(show = ShowState.fromApi(fields.roomStatus), source = httpsUrl(fields.hlsSource))
}

/**
 * 发请求并解码。退避窗口内不发请求；非 JSON 登记退避。
 * 取消原样抛出（不算网络故障），其余网络错误算 [ContextProbe.Failed]。
 */
suspend fun ChatContext.probe(
    username: String,
    client: HTTPClient,
    backoff: EndpointBackoffBox? = EndpointBackoffBox.shared,
): ContextProbe {
    if (backoff != null && !backoff.allows(SiteEndpoint.chatContext)) return ContextProbe.BackedOff
    val result = try {
        client.send(request(username))
    } catch (error: Exception) {
        if (RenewalPolicy.isCancellation(error) || !coroutineContext.isActive) throw error
        return ContextProbe.Failed
    }
    val outcome = probe(result.status, result.data)
    if (outcome == ContextProbe.NotJSON) backoff?.record(SiteEndpoint.chatContext)
    return outcome
}

/**
 * 起播来源的选择：先看 `chatvideocontext`，能定论就不再请求 `get_edge_hls_url_ajax`。
 */
object StreamSourcing {
    sealed class Step {
        /** 直接采用（可播放，或状态已能定论为不可播放）。 */
        data class Use(val ticket: StreamTicket) : Step()

        /** 退回 `get_edge_hls_url_ajax`（及侧车备路）。 */
        data object Fallback : Step()
    }

    /**
     * 公开且有 `hls_source`：直接用。
     * 非公开的已知状态：直接给出原因，不再多发请求。
     * 公开但地址为空、状态未知、请求失败、被质询、非 JSON、退避中：退回 ajax。
     */
    fun first(probe: ContextProbe): Step {
        val room = probe as? ContextProbe.Room ?: return Step.Fallback
        return when (room.show) {
            ShowState.publicShow -> {
                val source = room.source ?: return Step.Fallback
                Step.Use(StreamTicket(success = true, url = source, status = ShowState.publicShow))
            }
            ShowState.unknown -> Step.Fallback
            else -> Step.Use(StreamTicket(success = true, url = null, status = room.show))
        }
    }

    /**
     * 退回 ajax 后的定论。`chatvideocontext` 已说公开却没有地址，ajax 也给不出可播地址
     * （公开无地址，或状态说不清）时，归为地区受限；其余以 ajax 的结果为准。
     */
    fun settle(probe: ContextProbe, fallback: StreamTicket): StreamTicket {
        val room = probe as? ContextProbe.Room ?: return fallback
        if (room.show != ShowState.publicShow || room.source != null || fallback.isPlayable) return fallback
        if (fallback.status != ShowState.publicShow && fallback.status != ShowState.unknown) return fallback
        return StreamTicket(
            success = fallback.success,
            url = null,
            status = ShowState.publicShow,
            message = fallback.message,
        )
    }
}
