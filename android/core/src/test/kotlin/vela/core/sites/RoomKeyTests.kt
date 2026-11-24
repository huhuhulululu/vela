package vela.core.sites

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import vela.core.base.lane.LaneCursor
import vela.core.base.lane.LaneID
import vela.core.base.model.Room
import vela.core.base.model.RoomQuery
import vela.core.base.model.ShowState
import vela.core.base.model.dedupeRooms
import vela.core.base.site.CatalogPage
import vela.core.curation.Curation
import vela.core.curation.FavoriteExchange
import vela.core.feed.FeedStore
import vela.core.ledger.WatchLedger
import vela.core.ledger.ledgerJson
import vela.core.selection.LaneSelection
import vela.core.taste.Taste
import vela.core.taste.tasteJson

/** 房间键、站点模式，以及带前缀键在收藏 / 列表里的行为。输入与期望对齐 Swift `RoomKeyTests` 与 `SiteStorageTests`。 */
class RoomKeyTests {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testRawRefBareNameAndCaseInsensitiveEquality() {
        val bare = RoomKey.parsing("Alice")
        val prefixed = RoomKey.parsing("cb:Alice")
        val lower = RoomKey.parsing("cb:alice")
        assertEquals(bare, prefixed)
        assertEquals(prefixed, lower)
        assertEquals("cb:Alice", bare?.raw)
        assertEquals("Alice", bare?.ref)
        assertEquals("alice", bare?.normalized)
        assertEquals("cb:alice", lower?.raw)
        assertEquals("c4:bob", RoomKey.parsing("c4:Bob")?.raw)
        assertEquals("c4:bob", RoomKey.parsing("c4:Bob")?.ref)
        assertNotEquals(RoomKey.parsing("alice"), RoomKey.parsing("c4:alice"))
    }

    @Test
    fun testSiteIDPattern() {
        assertTrue(SiteID.isValidID("a1"))
        assertTrue(SiteID.isValidID("abcdefgh"))
        assertFalse(SiteID.isValidID("1a"))
        assertFalse(SiteID.isValidID("A1"))
        assertFalse(SiteID.isValidID("a"))
        assertFalse(SiteID.isValidID("abcdefghi"))
        assertEquals("Chaturbate", SiteID.chaturbate.displayName)
        assertEquals("Cam4", SiteID.cam4.displayName)
    }

    @Test
    fun testRejectsUnknownSiteEmptyAndIllegalNames() {
        assertNull(RoomKey.parsing(""))
        assertNull(RoomKey.parsing("a"))
        assertNull(RoomKey.parsing("bad name"))
        assertNull(RoomKey.parsing("c4:bad-name"))
        assertNull(RoomKey.parsing("c4:has.dot"))
        assertNull(RoomKey(SiteID.cam4, "a".repeat(31)))
        assertNull(RoomKey.parsing("zz:alice"))
        assertNull(RoomKey.parsing("1a:alice"))
        assertNull(RoomKey.parsing("A1:alice"))
        assertNotNull(RoomKey(SiteID.cam4, "ab"))
        assertNotNull(RoomKey(SiteID.chaturbate, "ab"))
    }

    @Test
    fun testSiteIDRejectsTrailingNewlineAndKelvinLowercase() {
        assertFalse(SiteID.isValidID("cb\n"))
        assertFalse(SiteID.cam4.isValidName("ab\n"))
        assertNull(RoomKey.parsing("cb\n:alice"))
        assertNull(RoomKey(SiteID.cam4, "\u212Aa"))
        assertNull(vela.core.sync.SyncKeys.roomKey("\u212Aa"))
        assertNull(vela.core.sync.SyncKeys.roomKey("c4:\u212Aa"))
        assertFalse(SiteKeys.disk("xx\n:ab").startsWith("xx\n:"))
    }

    @Test
    fun testSiteModeDirectRoom() {
        assertEquals("Alice", SiteMode.chaturbate.directRoom("Alice")?.ref)
        assertEquals("Alice", SiteMode.mixed.directRoom("Alice")?.ref)
        assertEquals("c4:alice", SiteMode.cam4.directRoom("Alice")?.ref)
        assertEquals("c4:alice", SiteMode.chaturbate.directRoom("c4:Alice")?.ref)
        assertNull(SiteMode.cam4.directRoom("x"))
    }

    @Test
    fun testCodableRoundTripAndNormalizeRef() {
        val key = RoomKey.parsing("cb:Alice")
        assertNotNull(key)
        val data = json.encodeToString(RoomKey.serializer(), key)
        assertEquals("\"cb:Alice\"", data)
        assertEquals(key, json.decodeFromString(RoomKey.serializer(), data))
        assertEquals(RoomKey.parsing("alice"), json.decodeFromString(RoomKey.serializer(), "\"alice\""))
        assertEquals("alice", RoomKey.normalizeRef("Alice"))
        assertEquals("alice", RoomKey.normalizeRef("cb:Alice"))
        assertEquals("alice", RoomKey.normalizeRef("CB:Alice"))
        assertEquals("c4:bob", RoomKey.normalizeRef("c4:Bob"))
        assertEquals("not a key", RoomKey.normalizeRef("  not a key  "))
    }

    @Test
    fun testRoomIdentityKeepsChaturbateIDAndSeparatesCam4() {
        val cb = Room(username = "Alice")
        assertEquals(SiteID.chaturbate, cb.site)
        assertEquals(cb.username, cb.id)
        assertNull(cb.stream)
        assertNull(cb.thumbnailExpires)
        assertTrue(cb.blockedRegions.isEmpty())
        val c4 = Room(username = "Alice", site = SiteID.cam4)
        assertEquals("c4:alice", c4.id)
        assertEquals("c4:alice", c4.ref)
        val merged = dedupeRooms(
            listOf(
                Room(username = "alice", show = ShowState.publicShow),
                Room(username = "Alice", show = ShowState.publicShow),
                Room(username = "alice", show = ShowState.publicShow, site = SiteID.cam4),
            ),
        )
        assertEquals(listOf("alice", "c4:alice"), merged.map { it.id })
        assertEquals("sample_one", Room.direct("sample_one")?.id)
        assertEquals("c4:sample_one", Room.direct(RoomKey.parsing("c4:sample_one")!!).id)
        assertNull(Room.direct("x"))
    }

    @Test
    fun testSiteModeRestore() {
        assertEquals(SiteMode.chaturbate, SiteMode.restore(null))
        assertEquals(SiteMode.chaturbate, SiteMode.restore(""))
        assertEquals(SiteMode.chaturbate, SiteMode.restore("nope"))
        assertEquals(SiteMode.chaturbate, SiteMode.restore("chaturbate"))
        assertEquals(SiteMode.cam4, SiteMode.restore("cam4"))
        assertEquals(SiteMode.mixed, SiteMode.restore("mixed"))
        assertEquals(listOf(SiteID.chaturbate, SiteID.cam4), SiteMode.mixed.sites)
        assertEquals(listOf(SiteID.cam4), SiteMode.cam4.sites)
    }

    @Test
    fun testCurationAcceptsThreeSpellingsAndEncodesRaw() {
        val curation = Curation(favorites = listOf("Alice"), blocked = emptyList()).favoriting("c4:Bob")
        assertTrue(curation.isFavorite("alice"))
        assertTrue(curation.isFavorite("cb:Alice"))
        assertTrue(curation.isFavorite("CB:alice"))
        assertTrue(curation.isFavorite("c4:bob"))
        assertFalse(curation.isFavorite("c4:alice"))
        val text = json.encodeToString(Curation.serializer(), curation)
        assertTrue(text.contains("\"keyVersion\":2"), text)
        assertTrue(text.contains("cb:Alice"), text)
        assertTrue(text.contains("c4:bob"), text)
        val decoded = json.decodeFromString(Curation.serializer(), text)
        assertEquals(curation.favorites, decoded.favorites)
        assertTrue(decoded.isFavorite("alice"))

        val legacy = """{"favorites":["Alice","cb:Carol","c4:Bob"],"blocked":["dave"]}""".encodeToByteArray()
        val old = json.decodeFromString(Curation.serializer(), legacy.decodeToString())
        assertTrue(old.isFavorite("alice"))
        assertTrue(old.isFavorite("carol"))
        assertTrue(old.isFavorite("c4:bob"))
        assertTrue(old.isBlocked("dave"))
        assertFalse(old.isBlocked("c4:dave"))
    }

    @Test
    fun testFavoriteExchangeWritesRawAndReadsBoth() {
        val data = FavoriteExchange.encode(listOf("alpha", "c4:sample_one"))
        val text = data.decodeToString()
        assertTrue(text.contains("cb:alpha"), text)
        assertTrue(text.contains("c4:sample_one"), text)
        assertEquals(FavoriteExchange.Imported(listOf("alpha", "c4:sample_one"), 0), FavoriteExchange.decode(data))
        val mixed = """["ok_1"," cb:padded ","c4:sample_two","bad name","x"]""".encodeToByteArray()
        assertEquals(
            FavoriteExchange.Imported(listOf("ok_1", "padded", "c4:sample_two"), 2),
            FavoriteExchange.decode(mixed),
        )
    }

    @Test
    fun testTasteAndLedgerRoundTripOldAndNewKeys() {
        val taste = Taste()
            .marking("Alice", Instant.ofEpochSecond(50))
            .marking("c4:Bob", Instant.ofEpochSecond(80))
        val encoded = tasteJson.encodeToString(Taste.serializer(), taste)
        assertTrue(encoded.contains("cb:alice"), encoded)
        assertTrue(encoded.contains("c4:bob"), encoded)
        val back = tasteJson.decodeFromString(Taste.serializer(), encoded)
        assertEquals(taste.lastSeen("alice"), back.lastSeen("cb:Alice"))
        assertEquals(taste.lastSeen("c4:bob"), back.lastSeen("C4:Bob"))
        val legacy = """{"seen":{"alice":50,"c4:bob":80},"beliefs":{"tag:talk":{"hit":1,"miss":0,"at":1,"hits":1,"misses":0,"vetoed":false}}}"""
        val old = tasteJson.decodeFromString(Taste.serializer(), legacy)
        assertEquals(Instant.ofEpochSecond(50), old.lastSeen("CB:alice"))
        assertEquals(1.0, old.beliefs["tag:talk"]?.hit)

        var ledger = WatchLedger().recording(username = "alice", seconds = 120.0, start = 10.0, now = 130.0)
        ledger = ledger.recording(username = "c4:bob", seconds = 30.0, start = 200.0, now = 230.0)
        val ledgerData = ledgerJson.encodeToString(WatchLedger.serializer(), ledger)
        assertEquals(ledger, ledgerJson.decodeFromString(WatchLedger.serializer(), ledgerData))
        assertTrue(ledgerData.contains("cb:alice"), ledgerData)
        val oldLedger = """{"entries":{"alice":{"totalSeconds":120,"visitCount":1,"lastVisitStart":10,"suggested":false,"lastUsed":130},"cb:alice":{"totalSeconds":5,"visitCount":0,"suggested":false,"lastUsed":1}}}"""
        val merged = ledgerJson.decodeFromString(WatchLedger.serializer(), oldLedger)
        assertEquals(1, merged.entries.size)
        assertEquals(120.0, merged.entries["alice"]?.totalSeconds)
    }

    @Test
    fun testFeedAndLanesKeepCam4BesideTheSameChaturbateName() {
        val cb = Room(username = "alice", viewers = 1, show = ShowState.publicShow)
        val c4 = Room(username = "alice", viewers = 9, show = ShowState.publicShow, site = SiteID.cam4)
        val bob = Room(username = "bob", viewers = 3, show = ShowState.publicShow)
        val store = FeedStore()
        store.replace(CatalogPage(rooms = listOf(cb, c4, bob), total = 3, nextOffset = 3))
        assertEquals(listOf("alice", "c4:alice", "bob"), store.rooms.map { it.id })
        store.setBlocked(setOf("alice"))
        assertEquals(listOf("c4:alice", "bob"), store.rooms.map { it.id })
        assertFalse(RoomQuery(blocked = setOf("alice")).matches(cb))
        assertTrue(RoomQuery(blocked = setOf("alice")).matches(c4))
        assertFalse(RoomQuery(blocked = setOf("c4:alice")).matches(c4))
        assertTrue(RoomQuery(blocked = setOf("cb:alice")).matches(c4))
        store.setBlocked(emptySet())
        store.setQuery(RoomQuery(text = "c4:"))
        assertEquals(listOf("c4:alice"), store.rooms.map { it.id })
        store.setQuery(RoomQuery())
        assertTrue(store.focus("alice"))
        assertEquals("c4:alice", store.step(1)?.id)
        assertTrue(store.reveal(bob))
        assertEquals("bob", store.current?.id)
        assertEquals(setOf("alice", "c4:alice", "bob"), store.rooms.map { it.id }.toSet())

        // 安卓选房目前是双路，没有第三路。同一份名单上证明 `alice` 与 `c4:alice` 不会被当成同一间。
        val names = listOf("alice", "c4:alice", "bob", "cara")
        val before = LaneSelection.Snapshot(
            rooms = names,
            primary = LaneCursor(username = "alice"),
            secondary = LaneCursor(username = "c4:alice"),
            focus = LaneID.PRIMARY,
        )
        val moved = LaneSelection.resolve(
            before = before,
            afterRooms = names,
            proposedPrimary = null,
            intent = LaneSelection.Intent.Move(LaneID.PRIMARY, 1),
        )
        assertEquals("bob", moved.primary.username)
        assertEquals("c4:alice", moved.secondary?.username)
        val blocked = LaneSelection.resolve(
            before = before,
            afterRooms = listOf("c4:alice", "bob", "cara"),
            proposedPrimary = "c4:alice",
            intent = LaneSelection.Intent.Filter,
        )
        assertEquals("bob", blocked.primary.username)
        assertEquals("c4:alice", blocked.secondary?.username)
        val searched = LaneSelection.resolve(
            before = before,
            afterRooms = listOf("c4:alice", "cara"),
            proposedPrimary = "c4:alice",
            intent = LaneSelection.Intent.SearchInstall,
        )
        assertEquals("c4:alice", searched.primary.username)
        assertEquals("cara", searched.secondary?.username)
        assertNotEquals(searched.primary.username, searched.secondary?.username)
        val revealed = LaneSelection.resolve(
            before = before,
            afterRooms = names,
            proposedPrimary = "c4:alice",
            intent = LaneSelection.Intent.Reveal("c4:alice"),
        )
        assertEquals(LaneID.SECONDARY, revealed.focus)
        assertEquals("alice", revealed.primary.username)
        assertEquals("c4:alice", revealed.secondary?.username)
    }

    @Test
    fun testRelativePlaylistResolutionDropsMasterQuery() {
        val master = java.net.URI.create("https://cdn.example/live/master.m3u8?token=abc")
        val cases = listOf(
            "media/720.m3u8" to "https://cdn.example/live/media/720.m3u8",
            "/hls/720.m3u8" to "https://cdn.example/hls/720.m3u8",
            "../other/720.m3u8" to "https://cdn.example/other/720.m3u8",
            "720.m3u8?rendition=1" to "https://cdn.example/live/720.m3u8?rendition=1",
            "https://other.example/a.m3u8?x=1" to "https://other.example/a.m3u8?x=1",
        )
        for ((reference, expected) in cases) {
            val playlist = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
                $reference
            """.trimIndent()
            val selected = vela.core.base.play.StreamRendition.select(playlist, master, Double.POSITIVE_INFINITY)
            assertNotNull(selected, reference)
            assertEquals(expected, selected.video.toString(), reference)
            assertFalse(selected.isUnselected)
            val ladder = vela.core.hls.PlaylistLadder.mediaURL(playlist, master, Double.POSITIVE_INFINITY)
            assertNotNull(ladder, reference)
            assertEquals(expected, ladder.toString(), reference)
        }
        val single = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
            720/index.m3u8
        """.trimIndent()
        val only = vela.core.base.play.StreamRendition.select(single, master, 1.0)
        assertNotNull(only)
        assertEquals("https://cdn.example/live/720/index.m3u8", only.video.toString())
        assertFalse(only.video.toString().contains("token"))
    }
}
