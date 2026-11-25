package vela.core.curation

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.site.FavoriteStatus

private fun room(
    name: String,
    viewers: Int = 5,
    show: ShowState = ShowState.publicShow,
    thumb: String? = null,
    seconds: Int? = null,
): Room = Room(
    username = name,
    viewers = viewers,
    show = show,
    thumbnailURL = thumb?.let(URI::create),
    secondsOnline = seconds,
)

class FavoritesBoardTests {
    @Test
    fun testFourStatesClassified() {
        val board = FavoritesBoard.make(
            favorites = listOf("on", "off", "gone", "unk"),
            statuses = mapOf(
                "on" to FavoriteStatus.Online(room("on")),
                "off" to FavoriteStatus.Offline,
                "gone" to FavoriteStatus.NotFound,
            ),
            catalog = emptyList(),
        )
        assertEquals(listOf("on"), board.online.map { it.username })
        assertEquals(listOf(FavoriteEntry.State.online), board.online.map { it.state })
        assertEquals(listOf("off", "unk", "gone"), board.others.map { it.username })
        assertEquals(
            listOf(FavoriteEntry.State.offline, FavoriteEntry.State.unknown, FavoriteEntry.State.missing),
            board.others.map { it.state },
        )
        assertEquals(1, board.onlineCount)
        assertFalse(board.isEmpty)
        assertNotNull(board.online[0].room)
        assertTrue(board.others.all { it.room == null && it.thumbnailURL == null })
    }

    @Test
    fun testStatusesWinOverCatalog() {
        val board = FavoritesBoard.make(
            favorites = listOf("alice", "bob"),
            statuses = mapOf(
                "alice" to FavoriteStatus.Offline,
                "bob" to FavoriteStatus.Online(room("bob", viewers = 7)),
            ),
            catalog = listOf(room("alice", viewers = 99), room("bob", viewers = 1)),
        )
        assertEquals(listOf("bob"), board.online.map { it.username })
        assertEquals(7, board.online[0].room?.viewers)
        assertEquals(listOf(FavoriteEntry.State.offline), board.others.map { it.state })
    }

    @Test
    fun testCatalogFillsOnlyPlayableRooms() {
        val board = FavoritesBoard.make(
            favorites = listOf("pub", "priv", "away"),
            statuses = emptyMap(),
            catalog = listOf(
                room("PUB", viewers = 3),
                room("priv", show = ShowState.privateShow),
                room("away", show = ShowState.away),
            ),
        )
        assertEquals(listOf("pub"), board.online.map { it.username })
        assertEquals(3, board.online[0].room?.viewers)
        assertEquals(listOf("priv", "away"), board.others.map { it.username })
        assertEquals(listOf(FavoriteEntry.State.unknown, FavoriteEntry.State.unknown), board.others.map { it.state })
    }

    @Test
    fun testOnlineSortedByViewersStableOnTies() {
        val board = FavoritesBoard.make(
            favorites = listOf("a", "b", "c", "d"),
            statuses = mapOf(
                "a" to FavoriteStatus.Online(room("a", viewers = 10)),
                "b" to FavoriteStatus.Online(room("b", viewers = 50)),
                "c" to FavoriteStatus.Online(room("c", viewers = 10)),
                "d" to FavoriteStatus.Online(room("d", viewers = 50)),
            ),
            catalog = emptyList(),
        )
        assertEquals(listOf("b", "d", "a", "c"), board.online.map { it.username })
    }

    @Test
    fun testOthersSegmentOrder() {
        val board = FavoritesBoard.make(
            favorites = listOf("m1", "u1", "o1", "m2", "o2", "u2"),
            statuses = mapOf(
                "m1" to FavoriteStatus.NotFound,
                "m2" to FavoriteStatus.NotFound,
                "o1" to FavoriteStatus.Offline,
                "o2" to FavoriteStatus.Offline,
            ),
            catalog = emptyList(),
        )
        assertEquals(listOf("o1", "o2", "u1", "u2", "m1", "m2"), board.others.map { it.username })
    }

    @Test
    fun testThumbnailSource() {
        val board = FavoritesBoard.make(
            favorites = listOf("HasThumb", "NoThumb"),
            statuses = mapOf(
                "hasthumb" to FavoriteStatus.Online(room("HasThumb", viewers = 2, thumb = "https://example.com/x.jpg")),
                "nothumb" to FavoriteStatus.Online(room("NoThumb", viewers = 1)),
            ),
            catalog = emptyList(),
        )
        assertEquals(URI.create("https://example.com/x.jpg"), board.online[0].thumbnailURL)
        assertEquals(URI.create("https://thumb.live.mmcdn.com/riw/nothumb.jpg"), board.online[1].thumbnailURL)
    }

    @Test
    fun testDetailText() {
        val board = FavoritesBoard.make(
            favorites = listOf("long", "short", "fresh", "off", "gone", "unk"),
            statuses = mapOf(
                "long" to FavoriteStatus.Online(room("long", viewers = 12_000, seconds = 3_900)),
                "short" to FavoriteStatus.Online(room("short", viewers = 300, seconds = 600)),
                "fresh" to FavoriteStatus.Online(room("fresh", viewers = 5)),
                "off" to FavoriteStatus.Offline,
                "gone" to FavoriteStatus.NotFound,
            ),
            catalog = emptyList(),
        )
        assertEquals(
            listOf(
                "1.2万人在看 · 已开播 1 小时 5 分",
                "300人在看 · 已开播 10 分钟",
                "5人在看",
            ),
            board.online.map { it.detail },
        )
        assertEquals(listOf("离线", "未查询", "账号不存在"), board.others.map { it.detail })
        for (entry in board.online + board.others) {
            assertFalse(entry.detail.contains("—") || entry.detail.contains("–"))
        }
    }

    @Test
    fun testDedupeCaseInsensitiveAndBlankIgnored() {
        val board = FavoritesBoard.make(
            favorites = listOf("Alice", "  ", "", "alice", "ALICE", "Bob", "bob"),
            statuses = mapOf(
                "ALICE" to FavoriteStatus.Offline,
                "BoB" to FavoriteStatus.Online(room("bob")),
            ),
            catalog = emptyList(),
        )
        assertEquals(listOf("Alice"), board.others.map { it.username })
        assertEquals(listOf(FavoriteEntry.State.offline), board.others.map { it.state })
        assertEquals(listOf("Bob"), board.online.map { it.username })
        assertEquals(listOf("Bob"), board.online.map { it.id })
    }

    @Test
    fun testEmptyFavorites() {
        val board = FavoritesBoard.make(
            favorites = emptyList(),
            statuses = mapOf("x" to FavoriteStatus.Offline),
            catalog = listOf(room("y")),
        )
        assertTrue(board.isEmpty)
        assertEquals(0, board.onlineCount)
        assertEquals(FavoritesBoard(emptyList(), emptyList()), board)
    }

    @Test
    fun testMenuSummary() {
        val none = FavoritesBoard.make(emptyList(), emptyMap(), emptyList())
        assertEquals("还没有收藏", none.menuSummary(checking = false))
        val on = FavoritesBoard.make(
            favorites = listOf("a", "b"),
            statuses = mapOf(
                "a" to FavoriteStatus.Online(room("a")),
                "b" to FavoriteStatus.Online(room("b")),
            ),
            catalog = emptyList(),
        )
        assertEquals("2 位在线", on.menuSummary(checking = true))
        val off = FavoritesBoard.make(
            favorites = listOf("a"),
            statuses = mapOf("a" to FavoriteStatus.Offline),
            catalog = emptyList(),
        )
        assertEquals("收藏的人现在都不在线", off.menuSummary(checking = false))
        assertEquals("正在查询…", off.menuSummary(checking = true))
        val unknown = FavoritesBoard.make(favorites = listOf("a"), statuses = emptyMap(), catalog = emptyList())
        assertEquals("还没有查询状态", unknown.menuSummary(checking = false))
    }

    @Test
    fun testCam4CatalogRoomWithoutStreamIsNotOnlineAndExpiredThumbIsDropped() {
        val now = java.time.Instant.ofEpochSecond(1_700_000_000)
        val expiredThumb = URI.create("https://snapshots.example/expired.jpg")
        val stream = URI.create("https://preview.example/live/master.m3u8")
        val streamless = Room(username = "no_stream", viewers = 15, show = ShowState.publicShow, site = vela.core.sites.SiteID.cam4, stream = null)
        val expired = Room(
            username = "old_thumb",
            viewers = 20,
            show = ShowState.publicShow,
            thumbnailURL = expiredThumb,
            site = vela.core.sites.SiteID.cam4,
            stream = stream,
            thumbnailExpires = now.plusSeconds(10),
        )
        val board = FavoritesBoard.make(
            favorites = listOf("c4:no_stream", "c4:old_thumb"),
            statuses = emptyMap(),
            catalog = listOf(streamless, expired),
            now = now,
        )
        assertEquals(listOf("c4:old_thumb"), board.online.map { it.username })
        assertNull(board.online.firstOrNull()?.thumbnailURL, "距过期不足 60 秒的 Cam4 缩略图应丢弃")
        assertEquals(listOf("c4:no_stream"), board.others.map { it.username })
        assertEquals(FavoriteEntry.State.unknown, board.others.firstOrNull()?.state, "无流 Cam4 房不能回落判在线")
    }
}
