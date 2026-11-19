package vela.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import vela.core.taste.Taste

class SyncOpsTests {
    private val dev = 0xa1b2c3d4L

    private fun replica(doc: SyncDocument = SyncDocument.empty()) = SyncReplica(doc, SyncClock(0, 0, dev))

    private fun applied(op: SyncLocalOp, r: SyncReplica, pt: Long = 1_000): SyncReplica {
        val outcome = SyncOps.apply(op, r, pt)
        return (outcome as? SyncOpOutcome.Applied)?.replica ?: fail("expected applied: $op")
    }

    private fun h(l: Long, c: Int = 0) = Hlc(l, c, dev)

    @Test
    fun testFavoriteWritesPairAndDisplayName() {
        val r = applied(SyncLocalOp.Favorite(" Alice "), replica())
        assertEquals(
            SyncRoom(f = SyncRegister.of(h(1000), "+"), b = SyncRegister.of(h(1000), "-"), n = SyncRegister.of(h(1000), "Alice")),
            r.doc.rooms["cb:alice"],
        )
        assertNull(applied(SyncLocalOp.Favorite("bob"), replica()).doc.rooms["cb:bob"]?.n)
        val again = applied(SyncLocalOp.Favorite("alice"), r, 1_001)
        assertEquals("alice", again.doc.rooms["cb:alice"]?.n?.payload)
    }

    @Test
    fun testBlockWritesPair() {
        val r = applied(SyncLocalOp.Block("Bob"), applied(SyncLocalOp.Favorite("bob"), replica()))
        assertEquals(SyncRegister.of(h(1000, 1), "+"), r.doc.rooms["cb:bob"]?.b)
        assertEquals(SyncRegister.of(h(1000, 1), "-"), r.doc.rooms["cb:bob"]?.f)
        assertTrue(SyncProjection(r.doc).isBlocked("cb:bob"))
    }

    @Test
    fun testPairedBookkeepingCoversUnseenOlderBlock() {
        val remote = SyncDocument.empty()
        remote.rooms["cb:xx"] = SyncRoom(b = SyncFixture.reg(500, "+", dev = 9))
        var mine = applied(SyncLocalOp.Favorite("xx"), replica())
        mine = applied(SyncLocalOp.Unfavorite("xx"), mine, 1_001)
        val merged = SyncProjection(SyncMerge.merge(mine.doc, remote))
        assertFalse(merged.isBlocked("cb:xx"))
        assertFalse(merged.isFavorite("cb:xx"))
    }

    @Test
    fun testPreconditionsNoWrite() {
        val r = replica()
        assertEquals(SyncOpOutcome.Unchanged, SyncOps.apply(SyncLocalOp.Unfavorite("alice"), r, 1))
        assertEquals(SyncOpOutcome.Unchanged, SyncOps.apply(SyncLocalOp.Unblock("alice"), r, 1))
        assertEquals(SyncOpOutcome.Unchanged, SyncOps.apply(SyncLocalOp.Favorite("a"), r, 1))
        val blocked = applied(SyncLocalOp.Block("alice"), r)
        assertEquals(SyncOpOutcome.Unchanged, SyncOps.apply(SyncLocalOp.RestoreFavorite("alice"), blocked, 2_000))
        val fav = applied(SyncLocalOp.Favorite("alice"), r)
        assertEquals(SyncOpOutcome.Unchanged, SyncOps.apply(SyncLocalOp.RestoreFavorite("alice"), fav, 2_000))
        assertEquals(SyncOpOutcome.Unchanged, SyncOps.apply(SyncLocalOp.Unveto("t:asian"), r, 1))
        assertEquals(SyncOpOutcome.Unchanged, SyncOps.apply(SyncLocalOp.ClearVetoes, r, 1))
        assertEquals(SyncOpOutcome.Unchanged, SyncOps.apply(SyncLocalOp.Setting("muted", true), r, 1))
        assertEquals(SyncClock(0, 0, dev), r.clock)
    }

    @Test
    fun testRestoreFavoriteWritesLikeFavorite() {
        val r = applied(SyncLocalOp.RestoreFavorite("Alice"), replica())
        assertTrue(SyncProjection(r.doc).isFavorite("cb:alice"))
        assertEquals("-", r.doc.rooms["cb:alice"]?.b?.payload)
    }

    @Test
    fun testUnfavoriteAndUnblockWriteMinus() {
        var r = applied(SyncLocalOp.Favorite("alice"), replica())
        r = applied(SyncLocalOp.Unfavorite("ALICE"), r)
        assertEquals(SyncRegister.of(h(1000, 1), "-"), r.doc.rooms["cb:alice"]?.f)
        r = applied(SyncLocalOp.Block("bob"), r)
        r = applied(SyncLocalOp.Unblock("bob"), r)
        assertEquals("-", r.doc.rooms["cb:bob"]?.b?.payload)
        assertEquals("-", r.doc.rooms["cb:bob"]?.f?.payload)
    }

    @Test
    fun testDeleteThenAddInSameMillisecondKeepsAdd() {
        var r = applied(SyncLocalOp.Favorite("alice"), replica())
        r = applied(SyncLocalOp.Unfavorite("alice"), r)
        r = applied(SyncLocalOp.Favorite("alice"), r)
        assertTrue(SyncProjection(r.doc).isFavorite("cb:alice"))
    }

    @Test
    fun testVetoMappingAndQuota() {
        var r = applied(SyncLocalOp.Veto(Taste.tagKey("Asian")), replica())
        assertEquals("+", r.doc.veto["t:asian"]?.payload)
        r = applied(SyncLocalOp.Unveto("t:asian"), r)
        assertEquals("-", r.doc.veto["t:asian"]?.payload)
        assertEquals(SyncOpOutcome.LocalOnly, SyncOps.apply(SyncLocalOp.Veto(Taste.tagKey("big tits")), r, 1))
        assertEquals(SyncOpOutcome.LocalOnlyQuotaFull, SyncOps.apply(SyncLocalOp.Veto(Taste.tagKey("big tits")), r, 1, localOnlyVetoCount = 50))
        val full = SyncDocument.empty()
        for (i in 0 until 450) full.veto["t:k%03d".format(i)] = SyncFixture.reg(1, "+")
        assertEquals(SyncOpOutcome.VetoQuotaFull, SyncOps.apply(SyncLocalOp.Veto("t:new"), replica(full), 5))
        assertTrue(SyncOps.apply(SyncLocalOp.Veto("t:k001"), replica(full), 5) is SyncOpOutcome.Applied)
    }

    @Test
    fun testClearVetoesSharesOneTick() {
        var r = applied(SyncLocalOp.Veto("t:a"), replica())
        r = applied(SyncLocalOp.Veto("g:m"), r)
        r = applied(SyncLocalOp.Unveto("g:m"), r)
        r = applied(SyncLocalOp.Veto("t:b"), r)
        r = applied(SyncLocalOp.ClearVetoes, r, 2_000)
        assertEquals(SyncRegister.of(h(2000), "-"), r.doc.veto["t:a"])
        assertEquals(SyncRegister.of(h(2000), "-"), r.doc.veto["t:b"])
        assertEquals(h(1000, 2), r.doc.veto["g:m"]?.hlc)
    }

    @Test
    fun testSettingWrites() {
        val r = applied(SyncLocalOp.Setting("infoAlwaysOn", true), replica())
        assertEquals(SyncRegister.of(h(1000), "1"), r.doc.settings["infoAlwaysOn"])
    }

    @Test
    fun testImportWindowIsFixedByFileContent() {
        val names = listOf("a", "Alice", "alice", "bob") + (0 until 400).map { "u%03d".format(it) }
        val window = SyncOps.importWindow(names)
        assertEquals(300, window.size)
        assertEquals(listOf("cb:alice", "cb:bob", "cb:u000"), window.take(3).map { it.second })
        assertEquals("cb:u297", window.last().second)
    }

    @Test
    fun testImportFileOrderIsNewestAndIdempotent() {
        var r = applied(SyncLocalOp.Favorite("old"), replica(), 500)
        r = applied(SyncLocalOp.Importing(listOf("carol", "dave", "old", "erin")), r)
        assertEquals(listOf("cb:carol", "cb:dave", "cb:erin", "cb:old"), SyncProjection(r.doc).favorites.map { it.key })
        assertEquals(SyncOpOutcome.Unchanged, SyncOps.apply(SyncLocalOp.Importing(listOf("carol", "dave", "old", "erin")), r, 2_000))
        val removed = applied(SyncLocalOp.Unfavorite("dave"), r)
        val again = applied(SyncLocalOp.Importing(listOf("carol", "dave", "old", "erin")), removed, 3_000)
        assertTrue(SyncProjection(again.doc).isFavorite("cb:dave"))
    }

    @Test
    fun testImportSkipsBlockedAndPushesOutOldFavorites() {
        var r = replica()
        for (i in 0 until 300) r = applied(SyncLocalOp.Favorite("old%03d".format(i)), r, (100 + i).toLong())
        r = applied(SyncLocalOp.Block("blk"), r, 900)
        val fresh = (0 until 10).map { "new%03d".format(it) } + "blk"
        r = applied(SyncLocalOp.Importing(fresh), r, 1_000)
        val p = SyncProjection(r.doc)
        assertEquals(300, p.favorites.size)
        assertEquals((0 until 10).map { "cb:new%03d".format(it) }, p.favorites.take(10).map { it.key })
        assertFalse(p.isFavorite("cb:old000"))
        assertTrue(p.isBlocked("cb:blk"))
    }

    @Test
    fun testHonestMaximumDocumentStaysBelowP() {
        val d = SyncDocument.empty()
        fun user(p: String, i: Int) = p + "%04d".format(i) + "x".repeat(25)
        val tag = "\u4E00".repeat(41)
        for (i in 0 until 300) d.rooms["abcdefgh:" + user("f", i)] = SyncRoom(f = SyncFixture.reg(9, "+"), b = SyncFixture.reg(9, "-"), n = SyncFixture.reg(9, user("F", i)))
        for (i in 0 until 300) d.rooms["abcdefgh:" + user("b", i)] = SyncRoom(f = SyncFixture.reg(9, "-"), b = SyncFixture.reg(9, "+"), n = SyncFixture.reg(9, user("B", i)))
        for (i in 0 until 1_000) d.rooms["abcdefgh:" + user("t", i)] = SyncRoom(f = SyncFixture.reg(9, "-"), b = SyncFixture.reg(9, "-"), n = SyncFixture.reg(9, user("T", i)))
        for (i in 0 until 450) d.veto["t:" + "%02d".format(i % 100) + (i / 100).toString() + tag] = SyncFixture.reg(9, "+")
        for (i in 0 until 500) d.veto["t:z" + "%03d".format(i) + tag] = SyncFixture.reg(9, "-")
        assertTrue(SyncMerge.withinCounts(d))
        assertEquals(d.encoded().size, d.encodedSize)
        assertTrue(d.encodedSize < SyncLimits.plaintextBytes)
        println("数量上限下的最大文档：${d.encodedSize} 字节")
    }

    @Test
    fun testLocalOpRejectedWhenFull() {
        var r = replica()
        r = (SyncOps.apply(SyncLocalOp.Favorite("averyveryverylongname_0001"), r, 1, sizeLimit = 200) as SyncOpOutcome.Applied).replica
        assertEquals(SyncOpOutcome.Full, SyncOps.apply(SyncLocalOp.Favorite("averyveryverylongname_0002"), r, 2, sizeLimit = 200))
        val two = (SyncOps.apply(SyncLocalOp.Unfavorite("averyveryverylongname_0001"), r, 3, sizeLimit = 200) as SyncOpOutcome.Applied).replica
        val three = SyncOps.apply(SyncLocalOp.Favorite("averyveryverylongname_0002"), two, 4, sizeLimit = 200) as? SyncOpOutcome.Applied
            ?: fail("墓碑裁掉后放得下")
        assertNull(three.replica.doc.rooms["cb:averyveryverylongname_0001"])
    }
}
