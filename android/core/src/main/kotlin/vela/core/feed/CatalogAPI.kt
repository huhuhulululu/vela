package vela.core.feed

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import vela.core.base.model.FlexInt
import vela.core.base.model.Gender
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.model.dedupeRooms
import vela.core.base.model.lowerRoot
import vela.core.base.model.secureImageURL
import vela.core.base.model.timeIntervalSince1970
import vela.core.base.model.toIntExactOrNull
import vela.core.base.net.EndpointBackoffBox
import vela.core.base.net.EndpointBackedOff
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPHeaders
import vela.core.base.net.HTTPRequest
import vela.core.base.net.SiteEndpoint
import vela.core.base.site.CatalogFailure
import vela.core.base.site.CatalogPage
import vela.core.base.site.CatalogQuery
import vela.core.base.site.looksLikeChallenge

object RoomListDecoder {
    fun decode(
        data: ByteArray,
        requestLimit: Int,
        requestOffset: Int,
        now: Instant = Instant.now(),
    ): CatalogPage {
        if (looksLikeChallenge(data)) throw CatalogFailure.Challenge
        val payload = try {
            json.decodeFromString(RoomListPayload.serializer(), data.decodeToString())
        } catch (_: SerializationException) {
            throw CatalogFailure.Malformed
        } catch (_: IllegalArgumentException) {
            throw CatalogFailure.Malformed
        }
        val rooms = payload.rooms.map { it.room(now) }
        val total = payload.totalCount ?: rooms.size
        // 站点偶尔多返回一条：下一页起点按请求的页大小算，重叠的一条由去重吸收。
        val step = minOf(rooms.size, requestLimit.coerceAtLeast(1).coerceAtMost(CatalogQuery.maxPageSize))
        return CatalogPage(rooms = rooms, total = total, nextOffset = requestOffset + step)
    }

    private val json = Json { ignoreUnknownKeys = true }
}

class CatalogAPI(
    val client: HTTPClient,
    val backoff: EndpointBackoffBox? = EndpointBackoffBox.shared,
) {
    /**
     * 退避窗口内不发请求，抛 [EndpointBackedOff]。
     * 正文像质询页才算质询；其余 403 / 503 按状态码失败。
     * 正文不是 JSON 也不是质询页时登记退避。
     */
    suspend fun fetch(query: CatalogQuery): CatalogPage {
        val gate = backoff
        if (gate != null && !gate.allows(SiteEndpoint.catalog)) {
            throw EndpointBackedOff(SiteEndpoint.catalog)
        }
        val drafted = HTTPRequest(url = query.url().toString(), method = "GET")
        val request = HTTPHeaders.applyBrowser(drafted, referer = "https://chaturbate.com/")
        val result = client.send(request)
        if (looksLikeChallenge(result.data)) throw CatalogFailure.Challenge
        gate?.note(SiteEndpoint.catalog, result.status, result.data)
        if (result.status !in 200..299) throw CatalogFailure.Status(result.status)
        return RoomListDecoder.decode(result.data, requestLimit = query.limit, requestOffset = query.offset)
    }

    /**
     * 连续取最多 [pages] 页并合并。遇到短页、空页或到底即停。
     * 任何一页失败都整体失败，不返回残缺候选池。
     */
    suspend fun fetchPages(query: CatalogQuery, pages: Int): CatalogPage {
        val size = query.limit.coerceAtLeast(1).coerceAtMost(CatalogQuery.maxPageSize)
        var last = fetch(query)
        var merged = last
        var fetched = 1
        while (
            CatalogWindow.shouldContinue(
                fetched = fetched,
                pages = pages,
                lastCount = last.rooms.size,
                pageSize = size,
                nextOffset = last.nextOffset,
                total = last.total,
            )
        ) {
            last = fetch(query.withOffset(last.nextOffset))
            if (last.rooms.isEmpty()) break
            merged = PageMerge.appended(merged, last)
            fetched += 1
        }
        return merged
    }
}

/** 还要不要拉目录的下一页。页大小按接口上限截断。 */
object CatalogWindow {
    fun shouldContinue(
        fetched: Int,
        pages: Int,
        lastCount: Int,
        pageSize: Int,
        nextOffset: Int,
        total: Int,
    ): Boolean {
        val size = pageSize.coerceAtLeast(1).coerceAtMost(CatalogQuery.maxPageSize)
        return fetched < pages && lastCount >= size && nextOffset < total
    }
}

data class PageMergeResult(
    val rooms: List<Room>,
    val added: Int,
)

object PageMerge {
    /**
     * 后页并入已展示的目录。[current] 还在列表里时，它和它之前的房间保持原位；
     * 新房间按用户名去重（不分大小写）后，跟当前项之后的房间一起交给 [order]。
     * 没有新增时原样返回，不调用 [order]。
     */
    fun keepingCurrent(
        existing: List<Room>,
        incoming: List<Room>,
        current: String?,
        order: (List<Room>) -> List<Room>,
    ): PageMergeResult {
        val seen = existing.mapTo(HashSet()) { it.username.lowerRoot() }
        val fresh = ArrayList<Room>()
        for (room in incoming) {
            if (seen.add(room.username.lowerRoot())) fresh.add(room)
        }
        if (fresh.isEmpty()) return PageMergeResult(existing, 0)
        val combined = existing + fresh
        val at = if (current == null) -1 else combined.indexOfFirst { it.username == current }
        if (at < 0) return PageMergeResult(order(combined), fresh.size)
        val head = combined.subList(0, at + 1).toList()
        val tail = combined.subList(at + 1, combined.size).toList()
        return PageMergeResult(head + order(tail), fresh.size)
    }

    /** 顺序翻页：去重、总量取较大值、游标用后一页的。 */
    fun appended(base: CatalogPage, next: CatalogPage): CatalogPage =
        CatalogPage(
            rooms = dedupeRooms(base.rooms + next.rooms),
            total = maxOf(base.total, next.total),
            nextOffset = next.nextOffset,
        )
}

private fun CatalogQuery.withOffset(offset: Int): CatalogQuery =
    CatalogQuery(gender, region, tag, limit, offset, newCams, keywords)

@Serializable
internal data class RoomListPayload(
    val rooms: List<RoomListRoom>,
    @SerialName("total_count") val totalCount: Int? = null,
)

@Serializable
internal data class RoomListRoom(
    val gender: String? = null,
    val location: String? = null,
    @SerialName("current_show") val currentShow: String? = null,
    val username: String,
    @SerialName("room_subject") val roomSubject: String? = null,
    val tags: List<String>? = null,
    @SerialName("num_users") val numUsers: Int? = null,
    @SerialName("num_followers") val numFollowers: Int? = null,
    @SerialName("has_password") val hasPassword: Boolean? = null,
    @SerialName("display_age") val displayAge: FlexInt? = null,
    @SerialName("start_timestamp") val startTimestamp: FlexInt? = null,
    val img: String? = null,
) {
    fun room(now: Instant): Room {
        var show = ShowState.fromApi(currentShow)
        if (hasPassword == true && show == ShowState.publicShow) show = ShowState.password
        return Room(
            username = username,
            subject = roomSubject.orEmpty(),
            viewers = numUsers ?: 0,
            followers = numFollowers ?: 0,
            gender = Gender.fromApi(gender),
            tags = tags.orEmpty(),
            show = show,
            location = location.orEmpty(),
            thumbnailURL = secureImageURL(img),
            age = displayAge?.value.toIntExactOrNull(),
            secondsOnline = secondsOnline(startTimestamp?.value, now),
        )
    }

    companion object {
        /**
         * 开播时刻（秒级 Unix 时间，Swift `Int` / 64 位）换算成已开播秒数。
         * 缺失、非正或在未来的按缺失处理。结果放不进 32 位时为 null（房间字段是 `Int?`）。
         */
        fun secondsOnline(startTimestamp: Long?, now: Instant): Int? {
            if (startTimestamp == null || startTimestamp <= 0L) return null
            val nowSeconds = epochSecondsTruncated(now)
            val seconds = nowSeconds - startTimestamp
            if (seconds < 0L) return null
            return seconds.toIntExactOrNull()
        }

        private fun epochSecondsTruncated(now: Instant): Long {
            val seconds = now.timeIntervalSince1970()
            if (!seconds.isFinite()) return 0L
            if (seconds >= 9223372036854775808.0) return Long.MAX_VALUE
            if (seconds <= -9223372036854775808.0) return Long.MIN_VALUE
            return seconds.toLong()
        }
    }
}

