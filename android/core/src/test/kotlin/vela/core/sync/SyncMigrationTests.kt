package vela.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import vela.core.curation.Curation
import vela.core.taste.Taste

class SyncMigrationTests {
    private val dev = 7L
    private fun clock() = SyncClock(0, 0, dev)

    @Test
    fun testCanEnableOnlyAfterCleanLoads() {
        assertTrue(SyncMigration.canEnable(PreferenceLoadOutcome.LOADED, PreferenceLoadOutcome.MISSING))
        assertTrue(!SyncMigration.canEnable(PreferenceLoadOutcome.QUARANTINED, PreferenceLoadOutcome.LOADED))
        assertTrue(!SyncMigration.canEnable(PreferenceLoadOutcome.LOADED, PreferenceLoadOutcome.READ_ONLY))
        assertTrue(!SyncMigration.canEnable(null, PreferenceLoadOutcome.LOADED))
    }

    @Test
    fun testCreatorStampsInOrderAndPreservesProjection() {
        val curation = Curation(favorites = listOf("Newest", "middle", "oldest", "x"), blocked = listOf("Spam1", "spam2"))
        val local = SyncLocalData(curation, listOf("t:zeta", "g:m", "t:alpha", Taste.tagKey("big tits")), mapOf("publicOnly" to false, "volume" to true))
        val (r, report, fits) = SyncMigration.stampCreator(local, clock(), 1_000)
        assertTrue(fits)
        assertEquals(listOf("x"), report.invalidNames)
        assertEquals(listOf(Taste.tagKey("big tits")), report.localOnlyVetoes)
        val p = SyncProjection(r.doc)
        assertEquals(listOf("Newest", "middle", "oldest"), p.curation.favorites)
        assertEquals(listOf("Spam1", "spam2"), p.curation.blocked)
        assertEquals(Hlc(1000, 0, dev), r.doc.rooms["cb:oldest"]?.f?.hlc)
        assertEquals(Hlc(1000, 2, dev), r.doc.rooms["cb:newest"]?.f?.hlc)
        assertEquals(Hlc(1000, 3, dev), r.doc.rooms["cb:spam2"]?.b?.hlc)
        assertEquals(Hlc(1000, 4, dev), r.doc.rooms["cb:spam1"]?.b?.hlc)
        assertEquals(Hlc(1000, 5, dev), r.doc.veto["g:m"]?.hlc)
        assertEquals(Hlc(1000, 6, dev), r.doc.veto["t:alpha"]?.hlc)
        assertEquals(Hlc(1000, 7, dev), r.doc.veto["t:zeta"]?.hlc)
        assertEquals(SyncRegister.of(Hlc(1000, 8, dev), "0"), r.doc.settings["publicOnly"])
        assertNull(r.doc.settings["volume"])
        assertNull(r.doc.rooms["cb:middle"]?.b)
        assertEquals("Newest", r.doc.rooms["cb:newest"]?.n?.payload)
        assertNull(r.doc.rooms["cb:middle"]?.n)
        assertEquals(Hlc(1000, 8, dev), r.clock.current)
    }

    @Test
    fun testCreatorVetoQuotas() {
        val synced = (0 until 455).map { "t:k%03d".format(it) }
        val localOnly = (0 until 52).map { Taste.tagKey("x %02d".format(it)) }
        val (r, report, _) = SyncMigration.stampCreator(SyncLocalData(vetoedKeys = synced + localOnly), clock(), 1)
        assertEquals(450, r.doc.veto.size)
        assertEquals((450 until 455).map { "t:k%03d".format(it) }, report.droppedSyncVetoes)
        assertEquals(50, report.localOnlyVetoes.size)
        assertEquals(listOf(Taste.tagKey("x 50"), Taste.tagKey("x 51")), report.droppedLocalOnlyVetoes)
    }

    private fun remote(): SyncDocument {
        val d = SyncDocument.empty()
        d.rooms["cb:shared"] = SyncRoom(f = SyncFixture.reg(5_000, "+", dev = 9))
        d.rooms["cb:theyblock"] = SyncRoom(b = SyncFixture.reg(5_000, "+", dev = 9))
        d.rooms["cb:tomb"] = SyncRoom(f = SyncFixture.reg(6_000, "-", dev = 9))
        d.veto["t:on"] = SyncFixture.reg(5_000, "+", dev = 9)
        d.veto["t:off"] = SyncFixture.reg(5_000, "-", dev = 9)
        d.settings["publicOnly"] = SyncFixture.reg(5_000, "1", dev = 9)
        return d
    }

    @Test
    fun testJoinStampRulesAndCounts() {
        val local = SyncLocalData(
            Curation(favorites = listOf("shared", "theyblock", "tomb", "mine"), blocked = listOf("blk")),
            listOf("t:on", "t:off", "t:new"),
            mapOf("publicOnly" to false, "infoAlwaysOn" to true),
        )
        val stamped = SyncMigration.stampJoin(local, remote(), clock(), 1_000)
        assertTrue(stamped.fits)
        assertEquals(SyncJoinPreview(n = 2, m = 1, v = 2, z = 1), stamped.preview)
        val p = SyncProjection(stamped.replica.doc)
        assertEquals(setOf("shared", "tomb", "mine"), p.curation.favorites.toSet())
        assertEquals(setOf("theyblock", "blk"), p.curation.blocked.toSet())
        assertEquals(9L, stamped.replica.doc.rooms["cb:shared"]?.f?.hlc?.dev)
        assertEquals(mapOf("t:on" to true, "t:off" to true, "t:new" to true), p.veto)
        assertEquals(mapOf("publicOnly" to true, "infoAlwaysOn" to true), p.settings)
        assertTrue(stamped.replica.doc.rooms.getValue("cb:mine").f!!.hlc > Hlc(6_000, 0, 9))
    }

    @Test
    fun testJoinCountsOverflow() {
        val far = SyncDocument.empty()
        for (i in 0 until 300) far.rooms["cb:r%03d".format(i)] = SyncRoom(f = SyncFixture.reg((100 + i).toLong(), "+", dev = 9))
        val local = SyncLocalData(Curation(favorites = (0 until 5).map { "l%03d".format(it) }))
        val stamped = SyncMigration.stampJoin(local, far, clock(), 1)
        assertEquals(5, stamped.preview.n)
        assertEquals(5, stamped.preview.x)
        assertEquals(300, SyncProjection(stamped.replica.doc).favorites.size)
        assertNull(stamped.replica.doc.rooms["cb:r000"])
    }
}
