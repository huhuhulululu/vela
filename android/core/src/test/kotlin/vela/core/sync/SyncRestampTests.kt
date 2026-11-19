package vela.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class SyncRestampTests {
    private val dev = 5L
    private val hour = 3_600_000L
    private val n = 10_000_000_000L

    private fun replicaWithFastOps(): SyncReplica {
        val r = SyncReplica(SyncDocument.empty(), SyncClock(0, 0, dev))
        r.doc.rooms["cb:peer"] = SyncRoom(f = SyncFixture.reg(n - 50, "+", dev = 9))
        val fast = n + 3 * hour
        val a = SyncOps.apply(SyncLocalOp.Favorite("Alice"), r, fast) as? SyncOpOutcome.Applied ?: fail("a")
        val b = SyncOps.apply(SyncLocalOp.Veto("t:x"), a.replica, fast) as? SyncOpOutcome.Applied ?: fail("b")
        return (SyncOps.apply(SyncLocalOp.Setting("publicOnly", true), b.replica, fast + 1) as SyncOpOutcome.Applied).replica
    }

    private fun snap(r: SyncReplica): SyncReplica {
        val doc = (SyncDocument.decode(r.doc.encoded()) as SyncDecodeResult.Valid).doc
        return SyncReplica(doc, SyncClock(r.clock.l, r.clock.c, r.clock.dev))
    }

    @Test
    fun testRestampsUnsentFastRegistersInOrder() {
        val r = replicaWithFastOps()
        val pt = n + 10
        assertEquals(SyncRestamp.Result.Restamped(5), SyncRestamp.presend(r, n, 0, pt))
        val room = r.doc.rooms.getValue("cb:alice")
        assertEquals(Hlc(pt, 0, dev), room.f?.hlc)
        assertEquals(room.f?.hlc, room.b?.hlc)
        assertEquals(room.f?.hlc, room.n?.hlc)
        assertEquals("+", room.f?.payload)
        assertEquals("-", room.b?.payload)
        assertEquals(Hlc(pt, 1, dev), r.doc.veto["t:x"]?.hlc)
        assertEquals(SyncRegister.of(Hlc(pt, 2, dev), "1"), r.doc.settings["publicOnly"])
        assertEquals(9L, r.doc.rooms["cb:peer"]?.f?.hlc?.dev)
        assertEquals(Hlc(pt, 2, dev), r.clock.current)
        assertEquals(SyncRestamp.Result.Unchanged, SyncRestamp.presend(r, n, 0, pt))
    }

    @Test
    fun testRestampFloorIsMaxOfOthersAndServerTime() {
        val r = replicaWithFastOps()
        r.doc.rooms["cb:late"] = SyncRoom(b = SyncFixture.reg(n + 100, "+", dev = 9))
        SyncRestamp.presend(r, n, 0, n - 5)
        assertEquals(Hlc(n + 100, 1, dev), r.doc.rooms["cb:alice"]?.f?.hlc)
    }

    @Test
    fun testAlreadySentRegistersAreNeverRewritten() {
        val r = replicaWithFastOps()
        val before = snap(r)
        assertEquals(SyncRestamp.Result.BlockedBySent, SyncRestamp.presend(r, n, n + 3 * hour, n))
        assertEquals(before.doc, r.doc)
        assertEquals(before.clock, r.clock)
    }

    @Test
    fun testWithinOneHourIsUnchanged() {
        val r = SyncReplica(SyncDocument.empty(), SyncClock(0, 0, dev))
        val a = SyncOps.apply(SyncLocalOp.Favorite("alice"), r, n + hour) as? SyncOpOutcome.Applied ?: fail("applied")
        assertEquals(SyncRestamp.Result.Unchanged, SyncRestamp.presend(a.replica, n, 0, n))
        assertEquals(n + hour, a.replica.doc.rooms["cb:alice"]?.f?.hlc?.l)
    }

    @Test
    fun testRestampedDeleteStillBeatsEarlierAdd() {
        val r = SyncReplica(SyncDocument.empty(), SyncClock(0, 0, dev))
        r.doc.rooms["cb:alice"] = SyncRoom(f = SyncFixture.reg(n - 10, "+", dev = 9), b = SyncFixture.reg(n - 10, "-", dev = 9))
        r.clock.receive(r.doc.maxHlc)
        val a = SyncOps.apply(SyncLocalOp.Unfavorite("alice"), r, n + 5 * hour) as? SyncOpOutcome.Applied ?: fail("applied")
        SyncRestamp.presend(a.replica, n, 0, n)
        assertFalse(SyncProjection(a.replica.doc).isFavorite("cb:alice"))
        assertTrue(a.replica.doc.rooms.getValue("cb:alice").f!!.hlc.l <= n + hour)
    }
}
