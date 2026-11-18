package vela.core.taste

import java.net.URI
import java.time.Instant
import kotlin.math.max
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import vela.core.base.model.FlexInt
import vela.core.base.model.Prefs
import vela.core.base.model.Room
import vela.core.base.model.lowerRoot
import vela.core.base.model.timeIntervalSince1970
import vela.core.base.model.trimSwiftWhitespace
import vela.core.base.net.HTTPHeaders
import vela.core.base.net.HTTPRequest
import vela.core.base.site.CatalogFailure
import vela.core.base.site.looksLikeChallenge

/** 全站热门标签的一项：标签名（小写、不带 #）与站点给出的房间数。 */
data class HotTag(
    val name: String,
    val rooms: Int,
)

/**
 * `tag-table-data` 接口：全站热门标签（按房间数降序）。
 * 没有 `hashtag` 的项跳过，数量缺失按 0。不访问真实站点；调用方拿字节来解码。
 */
object HotTags {
    const val limit: Int = 20

    fun url(limit: Int = HotTags.limit): URI {
        val capped = max(limit, 1)
        return URI(
            "https",
            "chaturbate.com",
            "/api/ts/hashtags/tag-table-data/",
            "sort=-rc&page=1&g=&limit=$capped",
            null,
        )
    }

    fun request(): HTTPRequest =
        HTTPHeaders.applyBrowser(
            HTTPRequest(url = url().toString(), method = "GET"),
            referer = "https://chaturbate.com/tags/",
        )

    fun decode(status: Int, data: ByteArray): List<HotTag> {
        if (status == 403 || status == 503 || looksLikeChallenge(data)) throw CatalogFailure.Challenge
        if (status !in 200..299) throw CatalogFailure.Status(status)
        val payload = try {
            hotJson.decodeFromString(HotTagPayload.serializer(), data.decodeToString())
        } catch (_: Exception) {
            throw CatalogFailure.Malformed
        }
        val seen = HashSet<String>()
        val out = ArrayList<HotTag>()
        for (item in payload.hashtags) {
            val name = normalized(item.hashtag) ?: continue
            if (!seen.add(name)) continue
            out.add(HotTag(name = name, rooms = max(0, item.roomCount?.value ?: 0)))
        }
        return out
    }

    /** 去掉 #、首尾空白，转小写；空的或超过名字长度上限的丢弃。 */
    internal fun normalized(raw: String?): String? {
        if (raw == null) return null
        val name = raw.trimSwiftWhitespace().dropWhile { it == '#' }.lowerRoot()
        if (name.isEmpty() || name.length > Prefs.maxNameLength) return null
        return name
    }

    /** 有全站热门标签用它，否则退到当前在线房间里最常见的标签。 */
    fun filterHints(hot: List<HotTag>?, rooms: List<Room>, limit: Int = 8): List<String> {
        if (!hot.isNullOrEmpty()) return hot.take(limit).map { it.name }
        return Taste().suggestedTags(rooms, limit)
    }
}

/**
 * 有全站热门标签时取其中画像里还没有的；没有或全都已在画像里时，退到在线房间的常见标签。
 */
fun Taste.suggestedTags(hot: List<HotTag>?, fallback: List<Room>, limit: Int = 12): List<String> {
    val fromHot = (hot ?: emptyList()).map { it.name }.filter { beliefs[Taste.tagKey(it)] == null }
    if (fromHot.isNotEmpty()) return fromHot.take(limit)
    return suggestedTags(fallback, limit)
}

/**
 * 成功的结果用 6 小时；失败后 60 秒内不再自动重试。不落盘。
 * 空列表按失败处理，不覆盖已有结果。
 */
data class HotTagCache(
    private val tags: List<HotTag> = emptyList(),
    private val fetchedAt: Instant? = null,
    private val failedAt: Instant? = null,
) {
    fun fresh(now: Instant): List<HotTag>? {
        val at = fetchedAt ?: return null
        val age = now.timeIntervalSince1970() - at.timeIntervalSince1970()
        if (age <= ttl && tags.isNotEmpty()) return tags
        return null
    }

    fun needsFetch(now: Instant): Boolean {
        if (fresh(now) != null) return false
        val failed = failedAt ?: return true
        return now.timeIntervalSince1970() - failed.timeIntervalSince1970() >= retryAfter
    }

    fun storing(tags: List<HotTag>, at: Instant): HotTagCache {
        if (tags.isEmpty()) return recordingFailure(at)
        return copy(tags = tags, fetchedAt = at, failedAt = null)
    }

    fun recordingFailure(at: Instant): HotTagCache = copy(failedAt = at)

    companion object {
        const val ttl: Double = 6.0 * 3_600
        const val retryAfter: Double = 60.0
    }
}

private val hotJson: Json = Json { ignoreUnknownKeys = true }

@Serializable
private data class HotTagPayload(
    val hashtags: List<HotTagItem>,
)

@Serializable
private data class HotTagItem(
    val hashtag: String? = null,
    @SerialName("room_count") val roomCount: FlexInt? = null,
)
