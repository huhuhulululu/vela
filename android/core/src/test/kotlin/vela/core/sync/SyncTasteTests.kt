package vela.core.sync

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import vela.core.base.model.FeedSource
import vela.core.base.model.Room
import vela.core.taste.Belief
import vela.core.taste.Taste

class SyncTasteTests {
    private val now = Instant.ofEpochSecond(1_800_000_000)

    @Test
    fun testSettingSyncedVetoCreatesBareBelief() {
        val t = Taste().settingSyncedVeto("t:asian", true)
        assertEquals(Belief(vetoed = true), t.beliefs["t:asian"])
    }

    @Test
    fun testSettingSyncedVetoKeepsEvidence() {
        val base = Taste().recording(Room(username = "a", tags = listOf("asian")), 200.0, FeedSource.guess, now)
        val before = base.beliefs.getValue("t:asian")
        val vetoed = base.settingSyncedVeto("t:asian", true)
        assertEquals(before.copy(vetoed = true), vetoed.beliefs["t:asian"])
        val back = vetoed.settingSyncedVeto("t:asian", false)
        assertEquals(before, back.beliefs["t:asian"])
        assertEquals(base.seen, back.seen)
        assertEquals(base.daily, back.daily)
    }

    @Test
    fun testSettingSyncedVetoFalseDropsEmptyEntry() {
        val t = Taste().settingSyncedVeto("g:m", true).settingSyncedVeto("g:m", false)
        assertNull(t.beliefs["g:m"])
        assertEquals(Taste(), Taste().settingSyncedVeto("g:m", false))
    }

    @Test
    fun testSettingSyncedVetoIsIdempotent() {
        val once = Taste().settingSyncedVeto("t:x", true)
        assertEquals(once, once.settingSyncedVeto("t:x", true))
    }

    @Test
    fun testTrimmedTieBreaksByKeyBytesAscending() {
        val beliefs = linkedMapOf<String, Belief>()
        for (i in 0 until 600) beliefs["t:k%03d".format(i)] = Belief(hit = 1.0, at = now.epochSecond.toDouble())
        val capped = Taste(beliefs = beliefs).capped(now)
        val expected = (0 until 500).map { "t:k%03d".format(it) }.toSet()
        assertEquals(expected, capped.beliefs.keys)
    }

    @Test
    fun testTrimmedVetoedTieBreaksByKeyBytes() {
        val beliefs = linkedMapOf<String, Belief>()
        for (i in 0 until 501) beliefs["t:v%03d".format(i)] = Belief(vetoed = true)
        val capped = Taste(beliefs = beliefs).capped(now)
        assertNull(capped.beliefs["t:v500"])
        assertEquals(500, capped.beliefs.size)
    }
}
