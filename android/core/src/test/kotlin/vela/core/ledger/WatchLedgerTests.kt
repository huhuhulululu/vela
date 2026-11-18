package vela.core.ledger

import java.nio.file.Files
import java.util.Locale
import vela.core.base.model.Prefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchLedgerTests {
    private val t0: Double = 1_700_000_000.0

    @Test
    fun testTotalTenMinutesTriggersSuggest() {
        var ledger = WatchLedger()
        val start = t0
        ledger = ledger.recording(username = "alice", seconds = 400.0, start = start, now = t0 + 400)
        assertFalse(ledger.shouldSuggest(username = "alice", favorited = false, blocked = false))
        ledger = ledger.recording(username = "alice", seconds = 250.0, start = t0 + 5000, now = t0 + 5250)
        assertTrue(ledger.shouldSuggest(username = "alice", favorited = false, blocked = false))
    }

    @Test
    fun testLiveSecondsReachTenMinutes() {
        var ledger = WatchLedger()
        ledger = ledger.recording(username = "bob", seconds = 500.0, start = t0, now = t0 + 500)
        assertTrue(
            ledger.shouldSuggest(
                username = "bob",
                favorited = false,
                blocked = false,
                liveSeconds = 100.0,
                liveStart = t0 + 600,
            ),
        )
    }

    @Test
    fun testThreeVisitsWithGapAndDuration() {
        var ledger = WatchLedger()
        ledger = ledger.recording(username = "c", seconds = 70.0, start = t0, now = t0 + 70)
        assertFalse(ledger.shouldSuggest(username = "c", favorited = false, blocked = false))
        ledger = ledger.recording(username = "c", seconds = 80.0, start = t0 + 2000, now = t0 + 2080)
        assertFalse(ledger.shouldSuggest(username = "c", favorited = false, blocked = false))
        ledger = ledger.recording(username = "c", seconds = 90.0, start = t0 + 4000, now = t0 + 4090)
        assertTrue(ledger.shouldSuggest(username = "c", favorited = false, blocked = false))
    }

    @Test
    fun testVisitGapBelowThirtyMinutesDoesNotIncrement() {
        var ledger = WatchLedger()
        ledger = ledger.recording(username = "d", seconds = 90.0, start = t0, now = t0 + 90)
        assertEquals(1, ledger.entries["d"]?.visitCount)
        ledger = ledger.recording(username = "d", seconds = 90.0, start = t0 + 600, now = t0 + 690)
        assertEquals(1, ledger.entries["d"]?.visitCount)
        ledger = ledger.recording(username = "d", seconds = 90.0, start = t0 + 1900, now = t0 + 1990)
        assertEquals(2, ledger.entries["d"]?.visitCount)
    }

    @Test
    fun testVisitUnderSixtySecondsDoesNotCount() {
        var ledger = WatchLedger()
        ledger = ledger.recording(username = "e", seconds = 59.0, start = t0, now = t0 + 59)
        assertEquals(0, ledger.entries["e"]?.visitCount)
        assertEquals(59.0, ledger.entries["e"]?.totalSeconds)
    }

    @Test
    fun testFavoritedBlockedOrSuggestedDoNotTrigger() {
        var ledger = WatchLedger()
        ledger = ledger.recording(username = "f", seconds = 700.0, start = t0, now = t0 + 700)
        assertFalse(ledger.shouldSuggest(username = "f", favorited = true, blocked = false))
        assertFalse(ledger.shouldSuggest(username = "f", favorited = false, blocked = true))
        ledger = ledger.markingSuggested("f", now = t0 + 800)
        assertFalse(ledger.shouldSuggest(username = "f", favorited = false, blocked = false))
    }

    @Test
    fun testMarkingSuggested() {
        var ledger = WatchLedger()
        ledger = ledger.markingSuggested("g", now = t0)
        assertTrue(ledger.entries["g"]?.suggested == true)
    }

    @Test
    fun testCapsAtFiveHundredEntries() {
        var ledger = WatchLedger()
        for (index in 0 until 520) {
            val name = String.format(Locale.ROOT, "u%04d", index)
            ledger = ledger.recording(
                username = name,
                seconds = 1.0,
                start = t0 + index.toDouble(),
                now = t0 + index.toDouble(),
            )
        }
        assertTrue(ledger.entries.size <= WatchLedger.maxEntries)
        assertNull(ledger.entries["u0000"])
        assertNotNull(ledger.entries["u0519"])
    }

    @Test
    fun testRoundTripJSON() {
        var ledger = WatchLedger()
        ledger = ledger.recording(username = "h", seconds = 120.0, start = t0, now = t0 + 120)
        val data = ledgerJson.encodeToString(WatchLedger.serializer(), ledger)
        val decoded = ledgerJson.decodeFromString(WatchLedger.serializer(), data)
        assertEquals(ledger, decoded)
    }

    @Test
    fun testOversizeFileTreatedAsEmpty() {
        val dir = Files.createTempDirectory("vela-ledger-")
        try {
            val url = dir.resolve("watch-ledger.json")
            val blob = ByteArray(Prefs.maxFileBytes + 1) { 0x20 }
            Files.write(url, blob)
            val store = WatchLedgerStore(url)
            assertEquals(WatchLedger(), store.load())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun testSecondsUntilTotalSuggest() {
        var ledger = WatchLedger()
        ledger = ledger.recording(username = "i", seconds = 100.0, start = t0, now = t0 + 100)
        assertEquals(
            500.0,
            ledger.secondsUntilTotalSuggest(
                username = "i",
                favorited = false,
                blocked = false,
                liveSeconds = 0.0,
                liveStart = null,
            ),
        )
        assertNull(
            ledger.secondsUntilTotalSuggest(
                username = "i",
                favorited = false,
                blocked = false,
                liveSeconds = 500.0,
                liveStart = t0 + 200,
            ),
        )
    }
}
