package vela.core.sites

import java.net.URI
import java.time.Instant
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.model.lowerRoot
import vela.core.base.net.EndpointBackoffBox
import vela.core.base.net.EndpointBackedOff
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.ResponseShape
import vela.core.base.net.SiteEndpoint
import vela.core.base.net.UserAgent
import vela.core.base.site.CatalogFailure
import vela.core.base.site.CatalogPage
import vela.core.base.site.CatalogQuery
import vela.core.base.site.FavoriteStatus
import vela.core.base.site.SiteSearchText
import vela.core.stream.SourcedTicket
import vela.core.stream.StreamFailure
import vela.core.stream.StreamOrigin
import vela.core.stream.StreamTicket

/**
 * Cam4 访客适配器。目录索引只在内存，上限 2000 条，超出丢最旧。不轮询。
 */
class Cam4Adapter(
    private val client: HTTPClient,
    private val backoff: EndpointBackoffBox? = EndpointBackoffBox.shared,
    private val viewerRegions: Set<String>,
    private val clock: () -> Instant = { Instant.now() },
) : SiteAdapter {
    private data class Entry(
        val room: Room,
        val offset: Int,
        val limit: Int,
        val fetchedAt: Instant,
        val geo: Boolean,
    )

    private val lock = Any()
    private val index = LinkedHashMap<String, Entry>()
    private val order = ArrayList<String>()

    override val site: SiteID = SiteID.cam4
    override val capabilities: SiteCapabilities = SiteCapabilities.strictMaster
    override val challengeHome: URI? = null

    override suspend fun fetchDirectory(query: CatalogQuery): CatalogPage {
        if (unsupported(query)) return CatalogPage(rooms = emptyList(), total = 0, nextOffset = 0)
        val limit = query.limit.coerceIn(1, Cam4Directory.maxPageSize)
        val needsFilter = viewerRegions.isNotEmpty() ||
            query.gender != null ||
            SiteSearchText.normalized(query.keywords) != null
        var cursor = maxOf(query.offset, 0)
        val collected = ArrayList<Room>()
        val seen = HashSet<String>()
        var total = 0
        var scans = 0
        while (true) {
            val raw = load(offset = cursor, limit = limit)
            scans += 1
            total = maxOf(total, raw.total)
            for (room in filtered(raw, query).rooms) {
                if (seen.add(room.identity)) collected.add(room)
            }
            val upstreamStep = raw.nextOffset - cursor
            cursor = raw.nextOffset
            if (!needsFilter || collected.size >= limit || upstreamStep < limit || upstreamStep <= 0 || cursor >= total || scans >= maxFilterScans) {
                break
            }
        }
        return CatalogPage(rooms = collected, total = total, nextOffset = cursor)
    }

    override suspend fun resolveStream(room: Room): SourcedTicket {
        val name = room.key.name.lowerRoot()
        val cached = lookup(name)
        if (cached != null && fresh(cached, streamTTLMillis, clock())) {
            return ticket(cached, cached.room.stream)
        }
        val offset = cached?.offset ?: 0
        val limit = cached?.limit ?: Cam4Directory.maxPageSize
        val stamp = clock()
        load(offset = offset, limit = limit)
        val again = lookup(name)
        if (again != null && !again.fetchedAt.isBefore(stamp)) {
            return ticket(again, again.room.stream)
        }
        drop(name)
        return SourcedTicket(
            ticket = StreamTicket(success = true, url = null, status = ShowState.offline),
            origin = StreamOrigin.cam4Directory,
        )
    }

    override suspend fun status(keys: List<RoomKey>, now: Instant): Map<RoomKey, FavoriteStatus> {
        val out = LinkedHashMap<RoomKey, FavoriteStatus>()
        synchronized(lock) {
            for (key in keys) {
                if (key.site != site) continue
                val entry = index[key.name.lowerRoot()] ?: continue
                if (now.toEpochMilli() - entry.fetchedAt.toEpochMilli() > statusTTLMillis) continue
                out[key] = when {
                    entry.geo -> FavoriteStatus.Restricted
                    entry.room.show == ShowState.privateShow || entry.room.stream == null -> FavoriteStatus.Offline
                    else -> FavoriteStatus.Online(entry.room)
                }
            }
        }
        return out
    }

    override fun thumbnail(room: Room, now: Instant): URI? {
        val url = room.thumbnailURL ?: return null
        val expires = room.thumbnailExpires ?: return url
        return if (expires.toEpochMilli() - now.toEpochMilli() < thumbnailMarginMillis) null else url
    }

    override fun mediaHeaders(key: RoomKey): Map<String, String> = MEDIA_HEADERS

    override fun masterFailure(status: Int): StreamFailure? {
        if (status in 500..599 || status == 403 || status == 404) return StreamFailure.unavailable
        return null
    }

    private fun unsupported(query: CatalogQuery): Boolean =
        query.region != null || CatalogQuery.normalizedHashtag(query.tag) != null || query.newCams

    private suspend fun load(offset: Int, limit: Int): CatalogPage {
        val gate = backoff
        if (gate != null && !gate.allows(SiteEndpoint.cam4Directory)) {
            throw EndpointBackedOff(SiteEndpoint.cam4Directory)
        }
        val pageSize = limit.coerceIn(1, Cam4Directory.maxPageSize)
        val request = HTTPRequest(url = Cam4Directory.url(offset = offset, limit = pageSize).toString(), method = "GET")
            .withHeader("User-Agent", UserAgent.safari)
            .withHeader("Accept", "application/json, text/plain, */*")
            .withHeader("Referer", "https://www.cam4.com/")
            .withHeader("Origin", "https://www.cam4.com")
            .withTimeout(12.0)
        val result = client.send(request)
        val nonJson = ResponseShape.shouldBackOff(result.status, result.data)
        if (result.status !in 200..299) {
            if (nonJson) gate?.record(SiteEndpoint.cam4Directory)
            throw CatalogFailure.Status(result.status)
        }
        if (nonJson || !ResponseShape.looksLikeJSON(result.data)) {
            gate?.record(SiteEndpoint.cam4Directory)
            throw CatalogFailure.Malformed
        }
        val now = clock()
        val page = Cam4Directory.decode(
            data = result.data,
            requestLimit = pageSize,
            requestOffset = offset,
            now = now,
            viewerRegions = viewerRegions,
        )
        remember(page.rooms, offset, pageSize, now)
        return page
    }

    /** 观看地区被主播屏蔽的房间不进目录（只留在索引里）；总数与游标仍按原页。 */
    private fun filtered(page: CatalogPage, query: CatalogQuery): CatalogPage {
        var rooms = page.rooms.filter { !geoBlocked(it) }
        val gender = query.gender
        if (gender != null) rooms = rooms.filter { it.gender == gender }
        val keywords = SiteSearchText.normalized(query.keywords)
        if (keywords != null) {
            val needle = keywords.lowerRoot()
            rooms = rooms.filter { needle in it.username.lowerRoot() || needle in it.identity }
        }
        return CatalogPage(rooms = rooms, total = page.total, nextOffset = page.nextOffset)
    }

    private fun remember(rooms: List<Room>, offset: Int, limit: Int, at: Instant) {
        synchronized(lock) {
            for (room in rooms) {
                val name = room.key.name.lowerRoot()
                index[name] = Entry(room = room, offset = offset, limit = limit, fetchedAt = at, geo = geoBlocked(room))
                order.remove(name)
                order.add(name)
            }
            while (order.size > indexLimit) {
                val oldest = order.removeAt(0)
                index.remove(oldest)
            }
        }
    }

    private fun geoBlocked(room: Room): Boolean =
        room.show == ShowState.publicShow &&
            room.stream == null &&
            room.blockedRegions.isNotEmpty() &&
            Cam4Regions.isBlocked(room.blockedRegions, viewerRegions)

    private fun lookup(name: String): Entry? = synchronized(lock) { index[name] }

    private fun drop(name: String) {
        synchronized(lock) {
            index.remove(name)
            order.remove(name)
        }
    }

    private fun fresh(entry: Entry, ttlMillis: Long, now: Instant): Boolean =
        now.toEpochMilli() - entry.fetchedAt.toEpochMilli() <= ttlMillis

    private fun ticket(entry: Entry, url: URI?): SourcedTicket {
        val ticket = when {
            entry.geo -> StreamTicket(success = true, url = null, status = ShowState.publicShow)
            entry.room.show == ShowState.privateShow ->
                StreamTicket(success = true, url = null, status = ShowState.privateShow)
            url == null -> StreamTicket(success = true, url = null, status = ShowState.offline)
            else -> StreamTicket(success = true, url = url, status = ShowState.publicShow)
        }
        return SourcedTicket(ticket, StreamOrigin.cam4Directory)
    }

    companion object {
        const val streamTTLMillis: Long = 10 * 60 * 1000
        const val statusTTLMillis: Long = 3 * 60 * 1000
        const val indexLimit: Int = 2_000
        const val thumbnailMarginMillis: Long = 60_000
        /** 本地过滤把原页滤短或滤空时，单次目录请求最多顺延扫描的原页数。 */
        const val maxFilterScans: Int = 5
        private val MEDIA_HEADERS = mapOf(
            "Referer" to "https://www.cam4.com/",
            "Origin" to "https://www.cam4.com",
        )
    }
}

/** 带过期时刻且离过期不足 [Cam4Adapter.thumbnailMarginMillis] 时不再用。 */
fun Room.usableThumbnail(now: Instant = Instant.now()): URI? {
    val expires = thumbnailExpires ?: return thumbnailURL
    return if (expires.toEpochMilli() - now.toEpochMilli() < Cam4Adapter.thumbnailMarginMillis) null else thumbnailURL
}
