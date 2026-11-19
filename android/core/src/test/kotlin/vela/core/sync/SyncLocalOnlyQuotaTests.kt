package vela.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncLocalOnlyQuotaTests {
    private val keys = (0 until 60).map { "t:\uD83D\uDE00$it" }

    @Test
    fun testExcessIsRecomputedFromCurrentVetoes() {
        val excess = SyncQuota.localOnlyExcess((keys + listOf("t:asian", "g:m")).toSet())
        assertEquals(10, excess.size, "同步域内的键不计入本机专用额度")
        assertFalse(excess.contains("t:asian"))
        val kept = keys.toSet() - excess.toSet()
        assertEquals(50, kept.size)
        for (e in excess) {
            val eb = e.toByteArray(Charsets.UTF_8)
            for (k in kept) {
                assertTrue(utf8Precedes(k.toByteArray(Charsets.UTF_8), eb))
            }
        }
        assertEquals(emptyList<String>(), SyncQuota.localOnlyExcess(keys.toSet() - excess.toSet()), "落实之后再算为空")
    }

    @Test
    fun testJoinTicketListsExcessForConfirmPage() {
        val local = SyncLocalData(vetoedKeys = keys)
        val remote = SyncDocument.empty()
        remote.rooms["cb:zed"] = SyncRoom(f = SyncFixture.reg(3, "+"))
        val stamped = SyncMigration.stampJoin(local, remote, SyncClock(dev = 1), 1_000)
        assertEquals(10, stamped.report.droppedLocalOnlyVetoes.size)
    }

    private fun utf8Precedes(a: ByteArray, b: ByteArray): Boolean {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val c = (a[i].toInt() and 0xFF).compareTo(b[i].toInt() and 0xFF)
            if (c != 0) return c < 0
        }
        return a.size < b.size
    }
}
