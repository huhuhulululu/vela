package vela.core.sync

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import vela.core.curation.Curation
import vela.core.taste.Taste

class SyncLifecycleTests {
    private val blob = "a".repeat(64)
    private val other = "b".repeat(64)

    private fun state(phase: SyncPhase, doc: SyncDocument = SyncDocument.empty()) =
        SyncState(phase, "https://sync.example.org", blob, SyncClock(0, 0, 1), doc)

    private fun sampleDoc(): SyncDocument {
        val d = SyncDocument.empty()
        d.rooms["cb:alice"] = SyncRoom(f = SyncFixture.reg(5, "+"), n = SyncFixture.reg(5, "Alice"))
        d.rooms["c4:bob"] = SyncRoom(f = SyncFixture.reg(6, "+"))
        d.veto["t:asian"] = SyncFixture.reg(4, "+")
        d.settings["publicOnly"] = SyncFixture.reg(3, "0")
        return d
    }

    @Test
    fun testStartupTableEveryRow() {
        val present = SyncKeychainRead.Present(blob)
        val cases = listOf(
            Triple(SyncStateRead.None, SyncKeychainRead.None, SyncStartupAction.NotEnabled),
            Triple(SyncStateRead.None, present, SyncStartupAction.OrphanEntry),
            Triple(SyncStateRead.Present(state(SyncPhase.CREATING)), SyncKeychainRead.None, SyncStartupAction.DiscardCreating),
            Triple(SyncStateRead.Present(state(SyncPhase.CREATING)), present, SyncStartupAction.ResumeCreating),
            Triple(SyncStateRead.Present(state(SyncPhase.ACTIVE)), SyncKeychainRead.None, SyncStartupAction.KeyLost),
            Triple(SyncStateRead.Present(state(SyncPhase.ACTIVE)), SyncKeychainRead.Invalidated, SyncStartupAction.KeyLost),
            Triple(SyncStateRead.Present(state(SyncPhase.ACTIVE)), present, SyncStartupAction.Normal),
            Triple(SyncStateRead.Present(state(SyncPhase.DISABLING)), SyncKeychainRead.None, SyncStartupAction.FinishDisabling),
            Triple(SyncStateRead.Present(state(SyncPhase.DISABLING)), present, SyncStartupAction.FinishDisabling),
            Triple(SyncStateRead.Present(state(SyncPhase.CLEARING)), present, SyncStartupAction.FinishClearing),
            Triple(SyncStateRead.Present(state(SyncPhase.CLEARING)), SyncKeychainRead.None, SyncStartupAction.FinishClearingLocally),
            Triple(SyncStateRead.Present(state(SyncPhase.ACTIVE)), SyncKeychainRead.Present(other), SyncStartupAction.Mismatch),
            Triple(SyncStateRead.Present(state(SyncPhase.CREATING)), SyncKeychainRead.Present(other), SyncStartupAction.Mismatch),
            Triple(SyncStateRead.Present(state(SyncPhase.CLEARING)), SyncKeychainRead.Present(other), SyncStartupAction.Mismatch),
            Triple(SyncStateRead.Present(state(SyncPhase.ACTIVE)), SyncKeychainRead.Unavailable, SyncStartupAction.Skip),
            Triple(SyncStateRead.None, SyncKeychainRead.Unavailable, SyncStartupAction.Skip),
            Triple(SyncStateRead.Corrupted, present, SyncStartupAction.StateCorrupted),
        )
        for ((s, k, expected) in cases) {
            assertEquals(expected, SyncLifecycle.startupAction(s, k), "$s / $k")
        }
    }

    private suspend fun recover(
        s: SyncState?,
        k: SyncKeychainRead,
        sink: MemorySink = MemorySink(),
        server: MemoryServer = MemoryServer(),
    ): Quad {
        val store = MemoryStateStore(s)
        val keychain = MemoryKeychain(k)
        val r = SyncLifecycle.recover(store, keychain, sink, MemoryTransport(server))
        return Quad(r.first, r.second, store, keychain)
    }

    private class Quad(val action: SyncStartupAction, val result: SyncFlowResult?, val store: MemoryStateStore, val keychain: MemoryKeychain)

    @Test
    fun testRecoverDiscardsUnfinishedCreate() = runBlocking {
        val q = recover(state(SyncPhase.CREATING), SyncKeychainRead.None)
        assertEquals(SyncFlowResult.Done, q.result, "${q.action}")
        assertNull(q.store.stored)
    }

    /** 加入是一次性事务：只剩钥匙串条目没有状态，删掉条目即未加入。 */
    @Test
    fun testRecoverDeletesOrphanEntry() = runBlocking {
        val q = recover(null, SyncKeychainRead.Present(blob))
        assertEquals(SyncStartupAction.OrphanEntry, q.action)
        assertEquals(SyncFlowResult.Done, q.result)
        assertNull(q.store.stored)
        assertEquals(SyncKeychainRead.None, q.keychain.entry)
    }

    @Test
    fun testRecoverLeavesUserChoicesAlone() = runBlocking {
        val pairs = listOf(
            state(SyncPhase.ACTIVE) to SyncKeychainRead.None,
            state(SyncPhase.ACTIVE) to SyncKeychainRead.Present(other),
            state(SyncPhase.CREATING) to SyncKeychainRead.Present(blob),
            state(SyncPhase.ACTIVE) to SyncKeychainRead.Unavailable,
        )
        for ((s, k) in pairs) {
            val q = recover(s, k)
            assertNull(q.result)
            assertEquals(s, q.store.stored)
            assertEquals(k, q.keychain.entry)
        }
    }

    @Test
    fun testRecoverFinishDisablingRunsBarrierFirst() = runBlocking {
        val sink = MemorySink()
        val q = recover(state(SyncPhase.DISABLING, sampleDoc()), SyncKeychainRead.Present(blob), sink)
        assertEquals(SyncStartupAction.FinishDisabling, q.action)
        assertEquals(SyncFlowResult.Done, q.result)
        assertEquals(listOf("Alice"), sink.curation?.favorites)
        assertNull(q.store.stored)
        assertEquals(SyncKeychainRead.None, q.keychain.entry)
    }

    @Test
    fun testRecoverFinishClearingDeletesRemote() = runBlocking {
        val server = MemoryServer()
        server.ver = 4
        server.pub = "PUB"
        server.data = PlainCodec.envelope(SyncDocument.empty(), 4)
        val s = state(SyncPhase.CLEARING, sampleDoc())
        s.lastSeenVer = 3
        val q = recover(s, SyncKeychainRead.Present(blob), server = server)
        assertEquals(SyncStartupAction.FinishClearing, q.action)
        assertEquals(SyncFlowResult.Done, q.result)
        assertTrue(server.deletedAt != null)
        assertNull(q.store.stored)
        assertEquals(SyncKeychainRead.None, q.keychain.entry)
    }

    @Test
    fun testRecoverClearingWithoutEntryFinishesLocally() = runBlocking {
        val sink = MemorySink()
        val server = MemoryServer()
        val q = recover(state(SyncPhase.CLEARING, sampleDoc()), SyncKeychainRead.None, sink, server)
        assertEquals(SyncStartupAction.FinishClearingLocally, q.action)
        assertEquals(SyncFlowResult.Done, q.result)
        assertNull(q.store.stored)
        assertEquals(listOf("Alice"), sink.curation?.favorites)
        assertEquals(0, server.gets, "没有钥匙串就不碰云端")
    }

    @Test
    fun testRecoverBarrierFailureLeavesEverything() = runBlocking {
        for (phase in listOf(SyncPhase.DISABLING, SyncPhase.CLEARING)) {
            val sink = MemorySink()
            sink.failTaste = true
            val s = state(phase, sampleDoc())
            val q = recover(s, SyncKeychainRead.Present(blob), sink)
            assertEquals(SyncFlowResult.LocalSaveFailed, q.result)
            assertEquals(s, q.store.stored, "同步状态不动")
            assertEquals(SyncKeychainRead.Present(blob), q.keychain.entry, "钥匙串不动")
        }
    }

    @Test
    fun testBarrierProjectsAndSaves() {
        val sink = MemorySink()
        val t0 = Instant.ofEpochSecond(0)
        sink.taste = Taste()
            .vetoing("t:milf", t0)
            .vetoing(Taste.tagKey("big tits"), t0)
        assertTrue(SyncLifecycle.barrier(sampleDoc(), sink))
        assertEquals(1, sink.cancelled, "先取消延迟保存")
        assertEquals(Curation(favorites = listOf("Alice")), sink.curation, "未知站点不投影")
        assertTrue(sink.taste.isVetoed("t:asian"))
        assertFalse(sink.taste.isVetoed("t:milf"))
        assertTrue(sink.taste.isVetoed(Taste.tagKey("big tits")))
        assertEquals(mapOf("publicOnly" to false), sink.settings)
        assertTrue(SyncLifecycle.barrier(sampleDoc(), sink), "可重复执行")
    }

    @Test
    fun testDisableBarrierFailureChangesNothing() {
        for (failCuration in listOf(true, false)) {
            val sink = MemorySink()
            sink.failCuration = failCuration
            sink.failTaste = !failCuration
            val s = state(SyncPhase.ACTIVE, sampleDoc())
            val store = MemoryStateStore(s)
            val keychain = MemoryKeychain(SyncKeychainRead.Present(blob))
            assertEquals(SyncFlowResult.LocalSaveFailed, SyncLifecycle.disable(s, store, keychain, sink))
            assertEquals(s, store.stored)
            assertEquals(0, store.saves)
            assertEquals(SyncKeychainRead.Present(blob), keychain.entry)
        }
    }

    @Test
    fun testDisableOrderAndInterruptedRecovery() = runBlocking {
        val s = state(SyncPhase.ACTIVE, sampleDoc())
        val store = MemoryStateStore(s)
        val keychain = MemoryKeychain(SyncKeychainRead.Present(blob))
        val sink = MemorySink()
        keychain.failDelete = true
        assertEquals(SyncFlowResult.StorageFailed, SyncLifecycle.disable(s, store, keychain, sink))
        assertEquals(SyncPhase.DISABLING, store.stored?.phase, "删除同步状态放在最后一步")
        keychain.failDelete = false
        val r = SyncLifecycle.recover(store, keychain, sink, MemoryTransport(MemoryServer()))
        assertEquals(SyncStartupAction.FinishDisabling, r.first)
        assertEquals(SyncFlowResult.Done, r.second)
        assertNull(store.stored)
        assertEquals(SyncKeychainRead.None, keychain.entry)
    }

    /** 还没有 epoch 不能证明空间不存在：先 GET，404 或 410 才本机关闭；空间存在就按返回的版本删除。 */
    @Test
    fun testClearWithoutEpochChecksServerFirst() = runBlocking {
        val s = state(SyncPhase.ACTIVE, sampleDoc())
        assertNull(s.epoch)
        run {
            val store = MemoryStateStore(s)
            val keychain = MemoryKeychain(SyncKeychainRead.Present(blob))
            val transport = MemoryTransport(MemoryServer())
            val result = SyncLifecycle.clear(s, store, keychain, MemorySink(), transport)
            assertEquals(SyncFlowResult.Done, result)
            assertEquals(emptyList<Long>(), transport.deletes)
            assertNull(store.stored)
        }
        run {
            val server = MemoryServer()
            server.ver = 5
            server.pub = "PUB"
            server.data = PlainCodec.envelope(SyncDocument.empty(), 5)
            val store = MemoryStateStore(s)
            val keychain = MemoryKeychain(SyncKeychainRead.Present(blob))
            val transport = MemoryTransport(server)
            val result = SyncLifecycle.clear(s, store, keychain, MemorySink(), transport)
            assertEquals(SyncFlowResult.Done, result)
            assertEquals(listOf(5L), transport.deletes)
            assertTrue(server.deletedAt != null)
        }
        run {
            val store = MemoryStateStore(s)
            val keychain = MemoryKeychain(SyncKeychainRead.Present(blob))
            val transport = MemoryTransport(MemoryServer())
            transport.scriptedGets.add(SyncServerResponse.Transient)
            val result = SyncLifecycle.clear(s, store, keychain, MemorySink(), transport)
            assertEquals(SyncFlowResult.RemotePending, result)
            assertEquals(SyncPhase.CLEARING, store.stored?.phase)
            assertEquals(SyncKeychainRead.Present(blob), keychain.entry)
        }
    }

    @Test
    fun testClearKeepsClearingWhenNetworkFails() = runBlocking {
        val server = MemoryServer()
        server.ver = 2
        server.pub = "PUB"
        server.data = PlainCodec.envelope(SyncDocument.empty(), 2)
        val s = state(SyncPhase.ACTIVE, sampleDoc())
        s.lastSeenVer = 2
        val store = MemoryStateStore(s)
        val keychain = MemoryKeychain(SyncKeychainRead.Present(blob))
        val sink = MemorySink()
        val transport = MemoryTransport(server)
        transport.failDeletes = 1
        assertEquals(SyncFlowResult.RemotePending, SyncLifecycle.clear(s, store, keychain, sink, transport))
        assertEquals(SyncPhase.CLEARING, store.stored?.phase)
        assertEquals(SyncKeychainRead.Present(blob), keychain.entry)
        val second = SyncLifecycle.recover(store, keychain, sink, transport)
        assertEquals(SyncFlowResult.Done, second.second)
        assertTrue(server.deletedAt != null)
        val again = SyncLifecycle.finishClear(s, MemoryStateStore(s), MemoryKeychain(SyncKeychainRead.Present(blob)), transport)
        assertEquals(SyncFlowResult.Done, again)
    }

    @Test
    fun testCompleteCreateStampsAll() {
        val local = SyncLocalData(Curation(favorites = listOf("alice", "x")), listOf("g:m"))
        val (created, report) = SyncLifecycle.completeCreate(state(SyncPhase.CREATING), local, 100)
        assertEquals(SyncPhase.ACTIVE, created.phase)
        assertEquals(listOf("x"), report.invalidNames)
        assertEquals(mapOf("g:m" to true), SyncProjection(created.doc).veto)
    }
}
