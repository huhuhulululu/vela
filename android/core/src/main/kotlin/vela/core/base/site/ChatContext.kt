package vela.core.base.site

import java.net.URI
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import vela.core.base.model.FlexInt
import vela.core.base.model.Gender
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.model.timeIntervalSince1970
import vela.core.base.model.trimSwiftWhitespace
import vela.core.base.net.HTTPHeaders
import vela.core.base.net.HTTPRequest
import vela.core.base.net.isPathAllowed
import vela.core.base.net.percentEncode
import kotlin.math.max

/** 一个收藏房间此刻的状态。在线只指公开、可播放。 */
sealed class FavoriteStatus {
    open val room: Room?
        get() = null

    data class Online(override val room: Room) : FavoriteStatus()

    data object Offline : FavoriteStatus()

    data object NotFound : FavoriteStatus()

    /** 公开但拿不到可播地址。 */
    data object Restricted : FavoriteStatus()
}

/**
 * `chatvideocontext`：按用户名查单个房间。
 * 存在 200 + JSON；不存在 404；已删除 403 + JSON（带 detail）；需密码 401；质询是 HTML。
 */
object ChatContext {
    /** 状态码本身就是答案，正文是什么都不退避。 */
    val definitiveStatuses: Set<Int> = setOf(401, 404)

    fun url(username: String): String {
        val slug = percentEncode(username, ::isPathAllowed)
        return "https://chaturbate.com/api/chatvideocontext/$slug/"
    }

    fun request(username: String): HTTPRequest {
        val drafted = HTTPRequest(url = url(username), method = "GET")
        return HTTPHeaders.applyBrowser(drafted, referer = "https://chaturbate.com/$username/").withTimeout(8.0)
    }

    fun decode(status: Int, data: ByteArray, now: Instant): FavoriteStatus {
        if (looksLikeChallenge(data)) throw CatalogFailure.Challenge
        return when (status) {
            200 -> decodeRoom(data, now)
            404 -> FavoriteStatus.NotFound
            401 -> FavoriteStatus.Offline
            403 -> {
                if (hasStringDetail(data)) FavoriteStatus.NotFound else throw CatalogFailure.Challenge
            }
            else -> throw CatalogFailure.Status(status)
        }
    }

    private fun decodeRoom(data: ByteArray, now: Instant): FavoriteStatus {
        val payload = try {
            json.decodeFromString(Payload.serializer(), data.decodeToString())
        } catch (_: kotlinx.serialization.SerializationException) {
            throw CatalogFailure.Malformed
        } catch (_: IllegalArgumentException) {
            throw CatalogFailure.Malformed
        }
        val show = ShowState.fromApi(payload.roomStatus)
        val name = payload.username
        if (!show.isPlayable || name.isNullOrEmpty()) return FavoriteStatus.Offline
        val source = payload.hlsSource
        if (source != null && source.trimSwiftWhitespace().isEmpty()) return FavoriteStatus.Restricted
        val start = payload.startTimestamp
        val seconds = if (start != null && start > 0.0) {
            max(0, (now.timeIntervalSince1970() - start).toInt())
        } else {
            null
        }
        return FavoriteStatus.Online(
            Room(
                username = name,
                subject = payload.roomTitle.orEmpty(),
                viewers = payload.viewers ?: 0,
                gender = payload.gender?.take(1)?.let(Gender::fromApi),
                show = show,
                thumbnailURL = URI.create("https://thumb.live.mmcdn.com/riw/$name.jpg"),
                age = payload.age?.toIntExactOrNull(),
                secondsOnline = seconds,
            ),
        )
    }

    private fun hasStringDetail(data: ByteArray): Boolean {
        val element = try {
            Json.parseToJsonElement(data.decodeToString())
        } catch (_: Exception) {
            return false
        }
        val obj = element as? JsonObject ?: return false
        val detail = obj["detail"] ?: return false
        val primitive = detail as? JsonPrimitive ?: return false
        return primitive.isString
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Payload(
        @SerialName("room_status") val roomStatus: String? = null,
        @SerialName("num_viewers") val viewers: Int? = null,
        @SerialName("room_title") val roomTitle: String? = null,
        @SerialName("broadcaster_username") val username: String? = null,
        @SerialName("broadcaster_gender") val gender: String? = null,
        val age: FlexInt? = null,
        @SerialName("start_timestamp") val startTimestamp: Double? = null,
        @SerialName("hls_source") val hlsSource: String? = null,
    )
}
