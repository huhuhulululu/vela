package vela.core.sites

import java.net.URI
import java.time.Instant
import vela.core.base.net.EndpointBackoffBox
import vela.core.base.net.HTTPClient
import vela.core.base.site.CatalogPage
import vela.core.base.site.CatalogQuery
import vela.core.base.site.ChatContext
import vela.core.base.site.FavoriteStatus
import vela.core.curation.Curation
import vela.core.curation.FavoriteFetcher
import vela.core.feed.CatalogAPI
import vela.core.base.model.Room
import vela.core.stream.SourcedTicket
import vela.core.stream.StreamDecoder
import vela.core.stream.StreamFailure
import vela.core.stream.StreamOrigin
import vela.core.stream.StreamSourcing
import vela.core.stream.probe

/** Chaturbate 走现有目录与起播路径，行为不变。侧车留在 App。 */
class ChaturbateAdapter(
    private val client: HTTPClient,
    private val backoff: EndpointBackoffBox? = EndpointBackoffBox.shared,
) : SiteAdapter {
    override val site: SiteID = SiteID.chaturbate
    override val capabilities: SiteCapabilities = SiteCapabilities.chaturbate
    override val challengeHome: URI? = URI.create("https://chaturbate.com/")

    override suspend fun fetchDirectory(query: CatalogQuery): CatalogPage =
        CatalogAPI(client, backoff).fetch(query)

    override suspend fun resolveStream(room: Room): SourcedTicket {
        val name = room.username
        val probe = ChatContext.probe(username = name, client = client, backoff = backoff)
        when (val step = StreamSourcing.first(probe)) {
            is StreamSourcing.Step.Use ->
                return SourcedTicket(ticket = step.ticket, origin = StreamOrigin.chatContext)
            StreamSourcing.Step.Fallback -> {
                val fallback = StreamDecoder.fetch(username = name, client = client, backoff = backoff)
                return SourcedTicket(
                    ticket = StreamSourcing.settle(probe, fallback),
                    origin = StreamOrigin.edge,
                )
            }
        }
    }

    override suspend fun status(keys: List<RoomKey>, now: Instant): Map<RoomKey, FavoriteStatus> {
        val mine = keys.filter { it.site == site }
        if (mine.isEmpty()) return emptyMap()
        val fetched = FavoriteFetcher(client = client, now = { now }, backoff = backoff).fetch(mine.map { it.name })
        val out = LinkedHashMap<RoomKey, FavoriteStatus>()
        for (key in mine) {
            val status = fetched.statuses[Curation.key(key.name)] ?: fetched.statuses[key.normalized]
            if (status != null) out[key] = status
        }
        return out
    }

    override fun thumbnail(room: Room, now: Instant): URI? = room.thumbnailURL

    override fun mediaHeaders(key: RoomKey): Map<String, String> = MEDIA_HEADERS

    override fun masterFailure(status: Int): StreamFailure? = null

    companion object {
        private val MEDIA_HEADERS = mapOf(
            "Referer" to "https://chaturbate.com/",
            "Origin" to "https://chaturbate.com",
        )
    }
}
