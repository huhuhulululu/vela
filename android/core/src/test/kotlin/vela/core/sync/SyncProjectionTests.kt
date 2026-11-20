package vela.core.sync

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import vela.core.base.model.FeedSource
import vela.core.base.model.Room
import vela.core.taste.Taste

class SyncProjectionTests {
    @Test
    fun testOrderNewestFirstThenKeyBytes() {
        val d = SyncDocument.empty()
        d.rooms["cb:bbb"] = SyncRoom(f = SyncFixture.reg(5, "+"))
        d.rooms["cb:aaa"] = SyncRoom(f = SyncFixture.reg(5, "+"))
        d.rooms["cb:new"] = SyncRoom(f = SyncFixture.reg(9, "+"), n = SyncFixture.reg(9, "NeW"))
        d.rooms["cb:old"] = SyncRoom(f = SyncFixture.reg(1, "+"))
        val p = SyncProjection(d)
        assertEquals(listOf("cb:new", "cb:aaa", "cb:bbb", "cb:old"), p.favorites.map { it.key })
        assertEquals(listOf("NeW", "aaa", "bbb", "old"), p.curation.favorites)
    }

    @Test
    fun testBothOnLargerHLCWinsTieBlocks() {
        val d = SyncDocument.empty()
        d.rooms["cb:fav"] = SyncRoom(f = SyncFixture.reg(6, "+"), b = SyncFixture.reg(5, "+"))
        d.rooms["cb:blk"] = SyncRoom(f = SyncFixture.reg(5, "+"), b = SyncFixture.reg(6, "+"))
        d.rooms["cb:tie"] = SyncRoom(f = SyncFixture.reg(5, "+"), b = SyncFixture.reg(5, "+"))
        d.rooms["cb:gone"] = SyncRoom(f = SyncFixture.reg(7, "-"), b = SyncFixture.reg(7, "-"), n = SyncFixture.reg(7, "Gone"))
        val c = SyncProjection(d).curation
        assertEquals(listOf("fav"), c.favorites)
        assertEquals(setOf("blk", "tie"), c.blocked.toSet())
        assertFalse(c.isFavorite("gone") || c.isBlocked("gone"))
    }

    @Test
    fun testUnknownSiteKeptButNotProjected() {
        val d = SyncDocument.empty()
        d.rooms["c4:alice"] = SyncRoom(f = SyncFixture.reg(5, "+"))
        d.rooms["cb:alice"] = SyncRoom(b = SyncFixture.reg(4, "+"))
        val p = SyncProjection(d)
        assertEquals(listOf("c4:alice"), p.favorites.map { it.key })
        assertEquals(emptyList(), p.curation.favorites)
        assertEquals(listOf("alice"), p.curation.blocked)
        assertNotNull(SyncMerge.merge(d, SyncDocument.empty()).rooms["c4:alice"], "未知前缀照常参与合并")
    }

    @Test
    fun testTasteProjectionKeepsEvidenceAndLocalOnly() {
        val now = Instant.ofEpochSecond(1_800_000_000)
        var taste = Taste().recording(Room(username = "a", tags = listOf("asian", "milf")), 200.0, FeedSource.guess, now)
        taste = taste.vetoing("t:milf", now).vetoing(Taste.tagKey("big tits"), now)
        val d = SyncDocument.empty()
        d.veto["t:asian"] = SyncFixture.reg(3, "+")
        d.veto["g:m"] = SyncFixture.reg(3, "-")
        val projected = SyncProjection(d).applying(taste)
        assertTrue(projected.isVetoed("t:asian"))
        assertEquals(taste.beliefs["t:asian"]?.hit, projected.beliefs["t:asian"]?.hit)
        assertFalse(projected.isVetoed("t:milf"), "同步域内、文档里不在的撤销")
        assertNotNull(projected.beliefs["t:milf"], "证据仍在，条目保留")
        assertTrue(projected.isVetoed(Taste.tagKey("big tits")), "本机专用不动")
        assertNull(projected.beliefs["g:m"])
        assertEquals(projected, SyncProjection(d).applying(projected), "重复投影不变")
    }

    @Test
    fun testSettingsProjection() {
        val d = SyncDocument.empty()
        d.settings["publicOnly"] = SyncFixture.reg(1, "0")
        assertEquals(mapOf("publicOnly" to false), SyncProjection(d).settings)
    }
}
