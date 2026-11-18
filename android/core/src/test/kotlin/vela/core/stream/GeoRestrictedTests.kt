package vela.core.stream

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import vela.core.base.site.ChatContext
import vela.core.base.site.FavoriteStatus
import vela.core.curation.FavoriteCache
import vela.core.curation.FavoriteEntry
import vela.core.curation.FavoriteResolver
import vela.core.curation.FavoritesBoard

/** 地区受限：公开但拿不到可播地址，归为「当前地区不可观看」，不算离线也不算在线。 */
class GeoRestrictedTests {
    private val now: Instant = Instant.ofEpochSecond(10_000)

    private fun context(fields: String): ByteArray =
        """{"room_status":"public","num_viewers":3,"broadcaster_username":"geo_room"$fields}""".toByteArray()

    @Test
    fun testPublicWithEmptyHLSSourceIsRestricted() {
        assertEquals(FavoriteStatus.Restricted, ChatContext.decode(200, context(",\"hls_source\":\"\""), now))
        assertEquals(FavoriteStatus.Restricted, ChatContext.decode(200, context(""","hls_source":"  """"), now))
    }

    @Test
    fun testPublicWithSourceOrWithoutFieldStaysOnline() {
        val withSource = context(""","hls_source":"https://edge.example/v1/edge/streams/a/llhls.m3u8?token=t"""")
        assertNotNull(ChatContext.decode(200, withSource, now).room)
        assertNotNull(ChatContext.decode(200, context(""), now).room)
        assertNotNull(ChatContext.decode(200, context(""","hls_source":null"""), now).room)
    }

    @Test
    fun testNonPublicWithEmptySourceIsOfflineNotRestricted() {
        val away = """{"room_status":"away","broadcaster_username":"x","hls_source":""}""".toByteArray()
        assertEquals(FavoriteStatus.Offline, ChatContext.decode(200, away, now))
    }

    @Test
    fun testRestrictedIsNotOnlineAndHasNoRoom() {
        assertNull(FavoriteStatus.Restricted.room)
        val rooms = FavoriteResolver.onlineRooms(
            favorites = listOf("geo_room"),
            statuses = mapOf("geo_room" to FavoriteStatus.Restricted),
        )
        assertTrue(rooms.isEmpty())
        assertFalse(
            FavoriteResolver.hasUnresolved(
                pending = listOf("geo_room"),
                statuses = mapOf("geo_room" to FavoriteStatus.Restricted),
            ),
        )
    }

    @Test
    fun testBoardShowsRestrictedBetweenOfflineAndUnknown() {
        val board = FavoritesBoard.make(
            favorites = listOf("gone", "geo", "later", "off"),
            statuses = mapOf(
                "gone" to FavoriteStatus.NotFound,
                "geo" to FavoriteStatus.Restricted,
                "off" to FavoriteStatus.Offline,
            ),
            catalog = emptyList(),
        )
        assertTrue(board.online.isEmpty())
        assertEquals(listOf("off", "geo", "later", "gone"), board.others.map { it.username })
        val geo = board.others[1]
        assertEquals(FavoriteEntry.State.restricted, geo.state)
        assertEquals("当前地区不可观看", geo.detail)
        assertNull(geo.room)
    }

    @Test
    fun testRestrictedIsCachedLikeOtherAnswers() {
        val cache = FavoriteCache().storing(mapOf("Geo" to FavoriteStatus.Restricted), now)
        assertEquals(FavoriteStatus.Restricted, cache.fresh("geo", now.plusSeconds(60)))
        val expired = now.plusMillis(((FavoriteCache.ttl + 1.0) * 1000.0).toLong())
        assertNull(cache.fresh("geo", expired))
    }

    @Test
    fun testEdgeTicketPublicWithoutURLIsGeo() {
        val empty = """{"success":true,"url":"","room_status":"public"}""".toByteArray()
        val ticket = StreamDecoder.decode(200, empty)
        assertFalse(ticket.isPlayable)
        assertEquals(StreamFailure.geo, ticket.failure)
        val offline = """{"success":true,"url":"","room_status":"offline"}""".toByteArray()
        assertEquals(StreamFailure.offline, StreamDecoder.decode(200, offline).failure)
    }
}
