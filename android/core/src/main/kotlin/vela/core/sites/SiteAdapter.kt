package vela.core.sites

import java.net.URI
import java.time.Instant
import vela.core.base.model.Room
import vela.core.base.net.EndpointBackoffBox
import vela.core.base.net.HTTPClient
import vela.core.base.site.CatalogPage
import vela.core.base.site.CatalogQuery
import vela.core.base.site.FavoriteStatus
import vela.core.stream.SourcedTicket
import vela.core.stream.StreamFailure

/** 一个站点列表 / 播放接口实际具备的能力。没有的筛选项不向该站发请求。 */
@JvmInline
value class SiteCapabilities(val rawValue: Int) {
    operator fun contains(other: SiteCapabilities): Boolean =
        other.rawValue != 0 && rawValue and other.rawValue == other.rawValue

    operator fun plus(other: SiteCapabilities): SiteCapabilities = SiteCapabilities(rawValue or other.rawValue)

    companion object {
        /** 列表接口能按性别筛。 */
        val serverGender = SiteCapabilities(1 shl 0)

        /** 能按地区筛。 */
        val serverRegion = SiteCapabilities(1 shl 1)

        /** 能按标签筛。 */
        val serverTag = SiteCapabilities(1 shl 2)

        /** 有「新开播」筛选。 */
        val freshCams = SiteCapabilities(1 shl 3)

        /** 有全站搜索。 */
        val keywordSearch = SiteCapabilities(1 shl 4)

        /** 有热门标签接口。 */
        val hotTags = SiteCapabilities(1 shl 5)

        /** 能查离线收藏的上次开播。 */
        val lastBroadcast = SiteCapabilities(1 shl 6)

        /** 会出质询页，需要侧车确认（只有 cb）。 */
        val challengePage = SiteCapabilities(1 shl 7)

        /** 主列表取不到（5xx / 403 / 404）即判这间不可播（只有 c4）。 */
        val strictMaster = SiteCapabilities(1 shl 8)

        val chaturbate: SiteCapabilities =
            serverGender + serverRegion + serverTag + freshCams + keywordSearch + hotTags + lastBroadcast + challengePage
    }
}

interface SiteAdapter {
    val site: SiteID
    val capabilities: SiteCapabilities

    /** 质询确认页的入口；没有质询能力为 null。 */
    val challengeHome: URI?

    /** 目录分页。能力里没有、又不能本地过滤的筛选项：返回空页且 nextOffset == total，不发请求。 */
    suspend fun fetchDirectory(query: CatalogQuery): CatalogPage

    suspend fun resolveStream(room: Room): SourcedTicket

    /** 在线状态。查不到的键不放进结果。 */
    suspend fun status(keys: List<RoomKey>, now: Instant): Map<RoomKey, FavoriteStatus>

    /** 此刻可用的缩略图。距过期不足 60 秒返回 null。 */
    fun thumbnail(room: Room, now: Instant): URI?

    /** 中继取主列表 / 子列表时要带的头（Referer、Origin）。 */
    fun mediaHeaders(key: RoomKey): Map<String, String>

    /** 主列表状态码是否已能定论为不可播。仅 strictMaster 站点返回非 null。 */
    fun masterFailure(status: Int): StreamFailure?
}

sealed class SiteError : Exception() {
    /** 键里的站点没有注册适配器。 */
    data class UnknownSite(val id: String) : SiteError()

    /** 调了该站没有的能力。 */
    data class Unsupported(val site: SiteID) : SiteError()
}

class SiteRegistry(adapters: List<SiteAdapter>) {
    private val bySite: Map<SiteID, SiteAdapter> = adapters.associateBy { it.site }

    fun adapter(site: SiteID): SiteAdapter? = bySite[site]

    companion object {
        fun live(
            client: HTTPClient,
            backoff: EndpointBackoffBox? = EndpointBackoffBox.shared,
            viewerRegions: Set<String>,
        ): SiteRegistry = SiteRegistry(
            listOf(
                ChaturbateAdapter(client, backoff),
                Cam4Adapter(client, backoff, viewerRegions),
            ),
        )
    }
}
