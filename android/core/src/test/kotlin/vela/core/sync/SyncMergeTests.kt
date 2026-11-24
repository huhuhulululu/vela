package vela.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncMergeTests {
    private fun room(f: SyncRegister? = null, b: SyncRegister? = null, n: SyncRegister? = null) = SyncRoom(f, b, n)

    @Test
    fun testUnionTakesLargerRegisterPerField() {
        val a = SyncDocument.empty()
        val b = SyncDocument.empty()
        a.rooms["cb:x"] = room(f = SyncFixture.reg(5, "+"), n = SyncFixture.reg(5, "X"))
        b.rooms["cb:x"] = room(f = SyncFixture.reg(4, "-"), b = SyncFixture.reg(6, "+"))
        b.rooms["cb:y"] = room(b = SyncFixture.reg(1, "+"))
        a.settings["publicOnly"] = SyncFixture.reg(3, "0")
        b.settings["publicOnly"] = SyncFixture.reg(3, "1")
        val m = SyncMerge.merge(a, b)
        assertEquals(room(f = SyncFixture.reg(5, "+"), b = SyncFixture.reg(6, "+"), n = SyncFixture.reg(5, "X")), m.rooms["cb:x"])
        assertEquals(room(b = SyncFixture.reg(1, "+")), m.rooms["cb:y"])
        assertEquals(SyncFixture.reg(3, "1"), m.settings["publicOnly"])
        assertTrue(m.rooms.getValue("cb:x").isBlocked)
    }

    @Test
    fun testSameHLCDeleteWins() {
        val a = SyncDocument.empty()
        val b = SyncDocument.empty()
        a.rooms["cb:x"] = room(f = SyncFixture.reg(5, "+"))
        b.rooms["cb:x"] = room(f = SyncFixture.reg(5, "-"))
        a.veto["g:m"] = SyncFixture.reg(2, "+")
        b.veto["g:m"] = SyncFixture.reg(2, "-")
        val m = SyncMerge.merge(a, b)
        assertEquals(SyncFixture.reg(5, "-"), m.rooms["cb:x"]?.f)
        assertEquals(SyncFixture.reg(2, "-"), m.veto["g:m"])
    }

    @Test
    fun testFavoriteVersusBlockTieBlockWins() {
        assertTrue(room(f = SyncFixture.reg(5, "+"), b = SyncFixture.reg(5, "+")).isBlocked)
        assertFalse(room(f = SyncFixture.reg(5, "+"), b = SyncFixture.reg(5, "+")).isFavorite)
        assertTrue(room(f = SyncFixture.reg(5, "+", c = 1), b = SyncFixture.reg(5, "+")).isFavorite)
        assertTrue(room(f = SyncFixture.reg(5, "+"), b = SyncFixture.reg(5, "+", dev = 2)).isBlocked)
        assertFalse(room(f = SyncFixture.reg(5, "-"), b = SyncFixture.reg(4, "-")).isFavorite)
    }

    @Test
    fun testMergeIsCommutativeAndIdempotent() {
        val a = SyncDocument.empty()
        val b = SyncDocument.empty()
        a.rooms["cb:x"] = room(f = SyncFixture.reg(5, "+"))
        b.rooms["cb:x"] = room(b = SyncFixture.reg(5, "+", dev = 3))
        b.veto["t:a"] = SyncFixture.reg(9, "+")
        assertEquals(SyncMerge.merge(b, a), SyncMerge.merge(a, b))
        val m = SyncMerge.merge(a, b)
        assertEquals(m, SyncMerge.merge(m, m))
        assertEquals(m, SyncMerge.merge(m, SyncDocument.empty()))
    }

    @Test
    fun testTrimFavoritesKeepsNewestThenKeyBytes() {
        val d = SyncDocument.empty()
        for (i in 0 until 305) d.rooms["cb:u%03d".format(i)] = room(f = SyncFixture.reg((1000 - i).toLong(), "+"))
        d.rooms["cb:tie_b"] = room(f = SyncFixture.reg(1000 - 299L, "+"))
        d.rooms["cb:tie_a"] = room(f = SyncFixture.reg(1000 - 299L, "+"))
        val t = SyncMerge.trim(d)
        assertEquals(300, t.rooms.values.count { it.isFavorite })
        assertNotNull(t.rooms["cb:tie_a"])
        assertNull(t.rooms["cb:tie_b"])
        assertNull(t.rooms["cb:u299"])
        assertNull(t.rooms["cb:u300"])
        assertNotNull(t.rooms["cb:u298"])
    }

    @Test
    fun testTrimBlockedAndTombstonesSeparately() {
        val d = SyncDocument.empty()
        for (i in 0 until 302) d.rooms["cb:b%03d".format(i)] = room(b = SyncFixture.reg((i + 1).toLong(), "+"))
        for (i in 0 until 1_003) {
            val name = "T%04d".format(i)
            d.rooms["cb:t%04d".format(i)] = room(f = SyncFixture.reg((i + 1).toLong(), "-"), n = SyncFixture.reg((i + 2).toLong(), name))
        }
        val t = SyncMerge.trim(d)
        assertNull(t.rooms["cb:b000"])
        assertNull(t.rooms["cb:b001"])
        assertNotNull(t.rooms["cb:b002"])
        assertEquals(1_000, t.rooms.keys.count { it.startsWith("cb:t") })
        assertNull(t.rooms["cb:t0002"])
        assertNotNull(t.rooms["cb:t0003"])
        assertTrue(SyncMerge.withinCounts(t))
    }

    @Test
    fun testTrimVetoCaps() {
        val d = SyncDocument.empty()
        for (i in 0 until 452) d.veto["t:on%03d".format(i)] = SyncFixture.reg((i + 1).toLong(), "+")
        for (i in 0 until 501) d.veto["t:off%03d".format(i)] = SyncFixture.reg(7, "-")
        val t = SyncMerge.trim(d)
        assertEquals(450, t.veto.values.count { it.isOn })
        assertNull(t.veto["t:on000"])
        assertNull(t.veto["t:on001"])
        assertEquals(500, t.veto.values.count { !it.isOn })
        assertNull(t.veto["t:off500"])
        assertNotNull(t.veto["t:off499"])
    }

    @Test
    fun testTrimNeverWrites() {
        val d = SyncDocument.empty()
        for (i in 0 until 310) {
            d.rooms["cb:u%03d".format(i)] = room(f = SyncFixture.reg((i + 1).toLong(), "+"), n = SyncFixture.reg((i + 1).toLong(), "U%03d".format(i)))
        }
        val t = SyncMerge.trim(d)
        for ((key, value) in t.rooms) assertEquals(d.rooms[key], value)
    }

    private fun bigDoc(tombstones: Int, activeTagBytes: Int = 0): SyncDocument {
        val d = SyncDocument.empty()
        for (i in 0 until tombstones) {
            d.veto["t:" + "%06d".format(i) + "x".repeat(100)] = SyncFixture.reg((i + 1).toLong(), "-")
        }
        if (activeTagBytes > 0) {
            for (i in 0 until 450) {
                d.veto["t:on" + "%03d".format(i) + "y".repeat(activeTagBytes)] = SyncFixture.reg(10_000, "+")
            }
        }
        return d
    }

    @Test
    fun testEncodedSizeMatchesEncoding() {
        val d = SyncDocument.empty()
        assertEquals(d.encoded().size, d.encodedSize)
        d.rooms["cb:a"] = room(n = SyncFixture.reg(1, "A"))
        assertEquals(d.encoded().size, d.encodedSize)
        d.rooms["cb:bb"] = room(f = SyncFixture.reg(1, "+"), b = SyncFixture.reg(2, "-"), n = SyncFixture.reg(3, "BB"))
        d.veto["t:\u00E9t\u00E9"] = SyncFixture.reg(4, "+")
        assertEquals(d.encoded().size, d.encodedSize)
        d.veto["g:m"] = SyncFixture.reg(4, "-")
        d.settings["publicOnly"] = SyncFixture.reg(5, "1")
        d.settings["infoAlwaysOn"] = SyncFixture.reg(5, "0")
        assertEquals(d.encoded().size, d.encodedSize)
    }

    @Test
    fun testFitSizeWithinLimitIsUntouched() {
        val d = bigDoc(10)
        val fitted = SyncMerge.fitSize(d)
        assertTrue(fitted.fits)
        assertEquals(d, fitted.doc)
        assertEquals(d.encoded().size, fitted.bytes)
    }

    @Test
    fun testFitSizeDropsOldestTombstonesFirst() {
        val d = bigDoc(400)
        d.rooms["cb:old"] = room(f = SyncFixture.reg(1, "-"))
        val full = d.encoded().size
        val limit = full - 300
        val fitted = SyncMerge.fitSize(d, limit)
        assertTrue(fitted.fits)
        assertTrue(fitted.bytes <= limit)
        assertEquals(fitted.doc.encoded().size, fitted.bytes)
        assertNull(fitted.doc.rooms["cb:old"])
        assertNull(fitted.doc.veto["t:000000" + "x".repeat(100)])
        assertNull(fitted.doc.veto["t:000001" + "x".repeat(100)])
        assertNotNull(fitted.doc.veto["t:000399" + "x".repeat(100)])
        assertEquals(2, d.veto.size - fitted.doc.veto.size)
    }

    @Test
    fun testFitSizeStillOverWhenOnlyActiveEntries() {
        val d = bigDoc(5, 100)
        val fitted = SyncMerge.fitSize(d, 10_000)
        assertFalse(fitted.fits)
        assertTrue(fitted.doc.veto.values.all { it.isOn })
        assertEquals(450, fitted.doc.veto.size)
    }
}
