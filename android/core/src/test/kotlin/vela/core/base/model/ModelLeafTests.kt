package vela.core.base.model

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Swift 断言里没单列、但叶子类型必须稳住的行为。 */
class ModelLeafTests {
    @Test
    fun prefsAndKeysMatchSwift() {
        assertEquals(3.0 * 86_400, Prefs.halfLife)
        assertEquals(500, Prefs.maxWeights)
        assertEquals(1_000, Prefs.maxSeen)
        assertEquals(24.0 * 3_600, Prefs.seenRetention)
        assertEquals(20.0, Prefs.likeAfter)
        assertEquals(3.0, Prefs.skipBelow)
        assertEquals(30.0, Prefs.relatedAfter)
        assertEquals(8, Prefs.maxTagsPerRoom)
        assertEquals(64, Prefs.maxNameLength)
        assertEquals(1_000_000, Prefs.maxFileBytes)
        assertEquals("t:jazz", TasteKeys.tagKey("Jazz"))
        assertEquals("t:" + "a".repeat(64), TasteKeys.tagKey("A".repeat(80)))
        assertEquals("g:f", TasteKeys.genderKey(Gender.female))
        assertEquals("g:t", TasteKeys.genderKey(Gender.trans))
        assertEquals("g:c", TasteKeys.genderKey(Gender.couple))
        assertEquals("alpha", CurationKeys.key("  Alpha \n"))
    }

    @Test
    fun genderRegionAndShowState() {
        assertEquals(Gender.female, Gender.fromApi("F"))
        assertEquals(Gender.trans, Gender.fromApi("s"))
        assertEquals(Gender.trans, Gender.fromApi("T"))
        assertNull(Gender.fromApi("x"))
        assertNull(Gender.fromApi(null))
        assertEquals(ShowState.publicShow, ShowState.fromApi("PUBLIC"))
        assertEquals(ShowState.password, ShowState.fromApi("password protected"))
        assertEquals(ShowState.unknown, ShowState.fromApi(null))
        assertTrue(ShowState.publicShow.isPlayable)
        assertFalse(ShowState.away.isPlayable)
        assertEquals(listOf("AS", "ER", "NA", "SA", "O"), Region.entries.map { it.siteCode })
        val json = Json
        assertEquals("\"f\"", json.encodeToString(Gender.serializer(), Gender.female))
        assertEquals(Gender.couple, json.decodeFromString(Gender.serializer(), "\"c\""))
        assertEquals("\"europe_russia\"", json.encodeToString(Region.serializer(), Region.europeRussia))
        assertEquals(Region.northAmerica, json.decodeFromString(Region.serializer(), "\"northamerica\""))
    }

    @Test
    fun roomDisplayNameAgeAndDirect() {
        assertEquals("alpha", Room(username = "alpha").displayName)
        assertEquals("alpha", Room(username = "alpha", displayName = "  ").displayName)
        assertEquals("Bob", Room(username = "alpha", displayName = " Bob ").displayName)
        assertNull(Room(username = "a", age = 17).age)
        assertEquals(18, Room(username = "ab", age = 18).age)
        assertEquals(80, Room(username = "ab", age = 80).age)
        assertNull(Room(username = "ab", age = 81).age)
        assertTrue(Room.isValidUsername("Alpha_9"))
        assertFalse(Room.isValidUsername("a"))
        assertFalse(Room.isValidUsername("has space"))
        assertFalse(Room.isValidUsername("a".repeat(31)))
        assertFalse(Room.isValidUsername("名字很长"))
        val direct = Room.direct("  alpha_1 ")
        assertEquals("alpha_1", direct?.username)
        assertEquals("alpha_1", direct?.displayName)
        assertEquals(ShowState.publicShow, direct?.show)
        assertEquals("thumb.live.mmcdn.com", direct?.thumbnailURL?.host)
        assertNull(Room.direct("nope name"))
    }

    @Test
    fun queryFiltersAndDedupe() {
        val jazz = Room(username = "A", tags = listOf("Jazz"), gender = Gender.female, show = ShowState.publicShow)
        assertFalse(RoomQuery(blocked = setOf("a")).matches(jazz))
        assertTrue(RoomQuery(blocked = setOf("A"), publicOnly = false).matches(jazz))
        assertFalse(RoomQuery(only = setOf("b")).matches(jazz))
        assertFalse(RoomQuery(hidden = setOf(TasteKeys.tagKey("jazz"))).matches(jazz))
        assertFalse(RoomQuery(hidden = setOf(TasteKeys.genderKey(Gender.female))).matches(jazz))
        val tags = (1..10).map { "t$it" }
        val many = Room(username = "room", tags = tags, show = ShowState.publicShow)
        assertTrue(RoomQuery(hidden = setOf(TasteKeys.tagKey("t9"))).matches(many))
        assertFalse(RoomQuery(hidden = setOf(TasteKeys.tagKey("t8"))).matches(many))
        assertTrue(RoomQuery(text = "  ").matches(jazz))
        assertTrue(RoomQuery(text = "JAZZ").matches(jazz))
        assertEquals("0", ViewerCount.string(-3))
        assertEquals("1万", ViewerCount.string(10_000))
        assertNull(BroadcastClock.string(null))
        assertNull(BroadcastClock.string(-1))
        val rooms = dedupeRooms(
            listOf(Room(username = "A", subject = "first"), Room(username = "a", subject = "second"), Room(username = "B")),
        )
        assertEquals(listOf("A", "B"), rooms.map { it.username })
        assertEquals("first", rooms[0].subject)
    }

    @Test
    fun secureImageAndEpochSeconds() {
        assertNull(secureImageURL(null))
        assertNull(secureImageURL(""))
        assertNull(secureImageURL("ftp://files.example/a.jpg"))
        assertEquals("https", secureImageURL("https://images.example/alpha.jpg")?.scheme)
        assertEquals("https://images.example/alpha.jpg", secureImageURL("https://images.example/alpha.jpg")?.toString())
        assertEquals("https", secureImageURL("http://images.example/b.jpg")?.scheme)
        assertEquals("images.example", secureImageURL("http:\\/\\/images.example\\/b.jpg")?.host)
        assertEquals(10_000.0, Instant.ofEpochSecond(10_000).timeIntervalSince1970())
        assertEquals(10_000.5, Instant.ofEpochSecond(10_000, 500_000_000).timeIntervalSince1970())
        assertEquals(-1.5, Instant.ofEpochSecond(-2, 500_000_000).timeIntervalSince1970())
    }

    @Test
    fun flexIntAcceptsJsonShapes() {
        val json = Json
        assertEquals(24, json.decodeFromString(FlexInt.serializer(), "24").value)
        assertEquals(1, json.decodeFromString(FlexInt.serializer(), "1.9").value)
        assertEquals(-1, json.decodeFromString(FlexInt.serializer(), "-1.9").value)
        assertEquals(12, json.decodeFromString(FlexInt.serializer(), "\"12\"").value)
        assertNull(json.decodeFromString(FlexInt.serializer(), "\"12.5\"").value)
        assertNull(json.decodeFromString(FlexInt.serializer(), "null").value)
        assertNull(json.decodeFromString(FlexInt.serializer(), "true").value)
        assertNull(json.decodeFromString(FlexInt.serializer(), "{}").value)
    }

    @Test
    fun feedSourceRestoreAndPaging() {
        assertEquals(FeedSource.guess, FeedSource.restore("recommended"))
        assertEquals(FeedSource.guess, FeedSource.restore("guess"))
        assertEquals(FeedSource.favorites, FeedSource.restore("favorites"))
        assertNull(FeedSource.restore("bogus"))
        assertNull(FeedSource.restore(null))
        assertEquals(40, FeedSource.popular.pageLimit)
        assertEquals(90, FeedSource.guess.pageLimit)
        assertEquals(90, FeedSource.fresh.pageLimit)
        assertEquals(1, FeedSource.popular.pageCount)
        assertEquals(3, FeedSource.guess.pageCount)
        assertEquals(3, FeedSource.fresh.pageCount)
        assertTrue(FeedSource.fresh.usesNewCams)
        assertFalse(FeedSource.guess.usesNewCams)
        assertFalse(FeedSource.popular.usesNewCams)
        assertFalse(FeedSource.favorites.usesNewCams)
        assertTrue(FeedSource.entries.all { it.pageLimit <= 90 })
    }
}
