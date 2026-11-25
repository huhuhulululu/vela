package vela.app.sync

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vela.core.curation.Curation
import vela.core.sync.SyncClock
import vela.core.sync.SyncCode
import vela.core.sync.SyncCycleResult
import vela.core.sync.SyncDocument
import vela.core.sync.SyncFlowResult
import vela.core.sync.SyncHalt
import vela.core.sync.SyncLocalData
import vela.core.sync.SyncLocalOp
import vela.core.sync.SyncOpOutcome
import vela.core.sync.SyncOps
import vela.core.sync.SyncPhase
import vela.core.sync.SyncProjection
import vela.core.sync.SyncPutRequest
import vela.core.sync.SyncReplica
import vela.core.sync.SyncServerResponse
import vela.core.sync.SyncStartupAction
import vela.core.sync.SyncState
import vela.core.sync.SyncStateRead
import vela.core.sync.SyncStateStore
import vela.core.sync.SyncTransport
import vela.core.sync.SyncKeychainRead
import vela.core.sync.deriveSyncKeys
import vela.core.sync.sha256
import vela.core.taste.Taste

/**
 * 验收 5：sync.md 第 12.1 节启动检查全部组合、第 12.2 节投影落盘屏障、
 * 第 23 节第 13 条加入提交四条机制（提交锁、写前快照与原子换回、「已开启」最后写、「需要修复」状态）、
 * 409 水位检查、周期单飞与文案准确性。
 */
class SyncServiceLifecycleAndJoinTest {
    private val keyBytes = ByteArray(32) { (it + 5).toByte() }
    private val derived = deriveSyncKeys(keyBytes)
    private val origin = "https://sync.example"
    private val epoch = "0123456789abcdef0123456789abcdef"
    private val code = SyncCode.format(keyBytes, origin, ::sha256)!!
    private val nowMs = 1_800_000_000_000L
    private val localCuration = Curation(favorites = listOf("alice"), blocked = listOf("mallory"))

    private fun remoteBlob(
        favorites: List<String>,
        ver: Long,
        conflict: Boolean = false,
        blobEpoch: String = epoch,
    ): SyncServerResponse {
        var replica = SyncReplica(SyncDocument.empty(), SyncClock(dev = 99L))
        var pt = 1_799_999_000_000L
        for (f in favorites) {
            pt += 10
            val out = SyncOps.apply(SyncLocalOp.Favorite(f), replica, pt) as SyncOpOutcome.Applied
            replica = out.replica
        }
        val codec = SyncCryptoCodec(derived)
        val env = codec.seal(replica.doc.encoded(), ver)!!
        return if (conflict) {
            SyncServerResponse.Conflict(ver, derived.pubB64, env, blobEpoch, nowMs)
        } else {
            SyncServerResponse.Blob(ver, derived.pubB64, env, blobEpoch, nowMs)
        }
    }

    private fun rig(): Rig {
        val r = Rig(keyBytes, derived, origin, nowMs)
        r.sink.curation = localCuration
        r.sink.settings["publicOnly"] = true
        r.transport.gets.add(remoteBlob(listOf("zed"), 3L))
        r.transport.puts.add(SyncServerResponse.Stored(4L, epoch, nowMs))
        return r
    }

    private fun assertNotJoined(r: Rig) {
        assertEquals(SyncStateRead.None, r.store.load())
        assertNull(r.keys.entry)
        assertEquals(localCuration, r.sink.curation)
        assertEquals(mapOf("publicOnly" to true), r.sink.settings)
        assertFalse(r.journal.exists)
        assertFalse(r.service.isActive)
        assertFalse("提交锁在任何结局下都必须释放", r.service.isCommittingJoin)
    }

    @Test
    fun testStartupCheckTableAllRowsAndProjectionBarrier() = runBlocking {
        // 1. 无状态 + 无条目 -> NotEnabled
        run {
            val r = rig()
            assertEquals(SyncStartResult.NotEnabled, r.service.start({ SyncLocalData() }, r.sink))
        }
        // 2. 无状态 + 有条目（无快照）-> 删孤儿条目 -> NotEnabled
        run {
            val r = rig()
            r.keys.entry = SyncKeyEntry(keyBytes, 7L, derived.blobID)
            assertEquals(SyncStartResult.NotEnabled, r.service.start({ SyncLocalData() }, r.sink))
            assertNull(r.keys.entry)
        }
        // 3. creating + 无条目 -> 删状态 -> NotEnabled
        run {
            val r = rig()
            r.store.save(SyncState(SyncPhase.CREATING, origin, derived.blobID, SyncClock(dev = 7L)))
            assertEquals(SyncStartResult.NotEnabled, r.service.start({ SyncLocalData() }, r.sink))
            assertEquals(SyncStateRead.None, r.store.load())
        }
        // 4. creating + 有条目一致 -> 完成迁移盖章并激活
        run {
            val r = rig()
            r.store.save(SyncState(SyncPhase.CREATING, origin, derived.blobID, SyncClock(dev = 7L)))
            r.keys.entry = SyncKeyEntry(keyBytes, 7L, derived.blobID)
            assertEquals(SyncStartResult.Active, r.service.start({ SyncLocalData(curation = localCuration) }, r.sink))
            assertTrue(r.service.isActive)
            assertEquals(SyncPhase.ACTIVE, r.service.state?.phase)
            assertTrue(r.service.state?.migrated == true)
        }
        // 5. active + 无条目 / Keystore 永久失效 -> KeyLost
        run {
            val r = rig()
            r.store.save(SyncState(SyncPhase.ACTIVE, origin, derived.blobID, SyncClock(dev = 7L)))
            assertEquals(SyncStartResult.Attention(SyncStartupAction.KeyLost), r.service.start({ SyncLocalData() }, r.sink))

            r.keys.lookupOverride = SyncKeyLookup.Invalidated
            assertEquals(SyncStartResult.Attention(SyncStartupAction.KeyLost), r.service.start({ SyncLocalData() }, r.sink))
        }
        // 6. Keystore 暂时不可用 -> Unavailable（不做任何判断）
        run {
            val r = rig()
            r.store.save(SyncState(SyncPhase.ACTIVE, origin, derived.blobID, SyncClock(dev = 7L)))
            r.keys.lookupOverride = SyncKeyLookup.Unavailable
            assertEquals(SyncStartResult.Unavailable, r.service.start({ SyncLocalData() }, r.sink))
        }
        // 7. active + blobID 不一致 -> Mismatch
        run {
            val r = rig()
            r.store.save(SyncState(SyncPhase.ACTIVE, origin, derived.blobID, SyncClock(dev = 7L)))
            r.keys.entry = SyncKeyEntry(keyBytes, 7L, "ff".repeat(32))
            assertEquals(SyncStartResult.Attention(SyncStartupAction.Mismatch), r.service.start({ SyncLocalData() }, r.sink))
        }
        // 8. disabling -> 先跑投影落盘屏障再完成关闭（且优先于 blobID 不一致）
        run {
            val r = rig()
            val doc = SyncDocument.empty().also {
                val applied = SyncOps.apply(
                    SyncLocalOp.Favorite("carol"),
                    SyncReplica(it, SyncClock(dev = 7L)),
                    nowMs,
                ) as SyncOpOutcome.Applied
                it.rooms.putAll(applied.replica.doc.rooms)
            }
            r.store.save(SyncState(SyncPhase.DISABLING, origin, derived.blobID, SyncClock(dev = 7L), doc = doc))
            r.keys.entry = SyncKeyEntry(keyBytes, 7L, "ff".repeat(32))
            assertEquals(SyncStartResult.NotEnabled, r.service.start({ SyncLocalData() }, r.sink))
            assertEquals(listOf("carol"), r.sink.curation.favorites)
            assertEquals(SyncStateRead.None, r.store.load())
            assertNull(r.keys.entry)
        }
        // 9. disabling 时屏障失败 -> 状态与密钥不动
        run {
            val r = rig()
            r.store.save(SyncState(SyncPhase.DISABLING, origin, derived.blobID, SyncClock(dev = 7L)))
            r.keys.entry = SyncKeyEntry(keyBytes, 7L, derived.blobID)
            r.sink.failOnSave = 1
            assertEquals(SyncStartResult.Failed(SyncFlowResult.LocalSaveFailed), r.service.start({ SyncLocalData() }, r.sink))
            assertNotNull(r.keys.entry)
            assertTrue(r.store.load() is SyncStateRead.Present)
        }
        // 10. clearing + 有条目 -> 屏障 + 远端 delete + 清理本地
        run {
            val r = rig()
            r.transport.deletes.add(SyncServerResponse.Deleted)
            r.store.save(SyncState(SyncPhase.CLEARING, origin, derived.blobID, SyncClock(dev = 7L), epoch = epoch, lastSeenVer = 2L))
            r.keys.entry = SyncKeyEntry(keyBytes, 7L, derived.blobID)
            assertEquals(SyncStartResult.NotEnabled, r.service.start({ SyncLocalData() }, r.sink))
            assertNull(r.keys.entry)
            assertEquals(SyncStateRead.None, r.store.load())
        }
        // 11. clearing + 无条目 -> 屏障 + 完成本地关闭
        run {
            val r = rig()
            r.store.save(SyncState(SyncPhase.CLEARING, origin, derived.blobID, SyncClock(dev = 7L), epoch = epoch))
            assertEquals(SyncStartResult.NotEnabled, r.service.start({ SyncLocalData() }, r.sink))
            assertEquals(SyncStateRead.None, r.store.load())
        }
        // 12. 状态损坏 -> StateCorrupted 且绝不删 K
        run {
            val r = rig()
            r.store.corrupted = true
            r.keys.entry = SyncKeyEntry(keyBytes, 7L, derived.blobID)
            assertEquals(SyncStartResult.Attention(SyncStartupAction.StateCorrupted), r.service.start({ SyncLocalData() }, r.sink))
            assertNotNull(r.keys.entry)
        }
    }

    @Test
    fun testMechanism1_CommitLockRejectsLocalWritesAndHoldsUntilActivate() = runBlocking {
        val r = rig()
        val ticket = (r.service.prepareJoin(code, SyncLocalData(localCuration)) as SyncJoinPrepared.Preview).ticket
        assertTrue(r.service.localWriteAllowed)

        var lockedWhenTakingLocal = false
        var lockedDuringPut = false
        r.transport.onPut = {
            lockedDuringPut = r.service.isCommittingJoin && !r.service.localWriteAllowed
        }
        val result = r.service.commitJoin(
            ticket,
            takeLocal = {
                lockedWhenTakingLocal = r.service.isCommittingJoin
                SyncLocalData(localCuration)
            },
            sink = r.sink,
            holdLockAfterJoin = true,
        )
        assertEquals(SyncJoinCommit.Joined, result)
        assertTrue("取本机数据必须在提交锁内", lockedWhenTakingLocal)
        assertTrue("云端 PUT 期间本机写入必须被拒", lockedDuringPut)
        assertTrue("holdLockAfterJoin=true 时激活前锁仍保持", r.service.isCommittingJoin)
        assertFalse(r.service.localWriteAllowed)
        r.service.releaseJoinCommit()
        assertFalse(r.service.isCommittingJoin)
        assertTrue(r.service.localWriteAllowed)
        assertFalse("成功后删掉快照", r.journal.exists)
    }

    @Test
    fun testMechanism2And3_EveryLocalStepFailureOrCancelRollsBackAndStateWrittenLast() = runBlocking {
        for (step in listOf("snapshot", "keychain", "barrier", "state", "cancel-after-keychain", "cancel-after-barrier")) {
            val r = rig()
            val ticket = (r.service.prepareJoin(code, SyncLocalData(localCuration)) as SyncJoinPrepared.Preview).ticket
            val writeOrder = ArrayList<String>()
            r.journal.onSnapshot = { writeOrder.add("snapshot") }
            r.keys.onWrite = {
                writeOrder.add("keystore")
                if (step == "cancel-after-keychain") ticket.cancel()
            }
            r.sink.onSave = {
                if ("barrier" !in writeOrder) writeOrder.add("barrier")
                if (step == "cancel-after-barrier") ticket.cancel()
            }
            r.store.onSave = { writeOrder.add("state") }
            when (step) {
                "snapshot" -> r.journal.failSnapshot = true
                "keychain" -> r.keys.failWrite = true
                "barrier" -> r.sink.failOnSave = 2
                "state" -> r.store.failSave = true
            }
            val result = r.service.commitJoin(ticket, { SyncLocalData(localCuration) }, r.sink, holdLockAfterJoin = true)
            if (step.startsWith("cancel")) {
                assertEquals(step, SyncJoinCommit.Cancelled, result)
            } else {
                assertTrue("$step 应返回 Failed，实际 $result", result is SyncJoinCommit.Failed)
            }
            if (step == "state") {
                assertEquals(listOf("snapshot", "keystore", "barrier", "state"), writeOrder)
            }
            assertNotJoined(r)
        }
    }

    @Test
    fun testMechanism4_RollbackFailureEntersNeedsRepairAndBlocksWritesAndCreate() = runBlocking {
        val r = rig()
        val ticket = (r.service.prepareJoin(code, SyncLocalData(localCuration)) as SyncJoinPrepared.Preview).ticket
        r.sink.failOnSave = 2
        r.journal.failRestore = true

        val res = r.service.commitJoin(ticket, { SyncLocalData(localCuration) }, r.sink)
        assertTrue("回滚失败必须进入 NeedsRepair，实际 $res", res is SyncJoinCommit.NeedsRepair)
        assertTrue(r.journal.exists)
        assertTrue(r.service.joinRepairPending)
        assertFalse("待修复期间本机写操作继续被拒", r.service.localWriteAllowed)

        // 重启后继续报 NeedsRepair
        val again = r.service.start({ SyncLocalData() }, r.sink)
        assertTrue("重启后仍需修复，实际 $again", again is SyncStartResult.NeedsRepair)
        assertFalse(r.service.localWriteAllowed)

        // 新建流程不得绕过待修复快照
        val created = r.service.create(origin, SyncLocalData(localCuration))
        assertTrue(created is SyncCreateResult.Failed)

        // 修复后「关闭同步」即重试恢复
        r.journal.failRestore = false
        r.sink.failOnSave = null
        assertEquals(SyncFlowResult.Done, r.service.disable(r.sink))
        assertTrue(r.service.localWriteAllowed)
        assertNotJoined(r)
    }

    @Test
    fun testStartupSettlesInterruptedJoinSnapshot() = runBlocking {
        // 无「已开启」状态 + pending 快照 -> 换回并删密钥
        val r = rig()
        assertTrue(r.journal.snapshot())
        r.sink.curation = Curation(favorites = listOf("alice", "zed"))
        r.keys.entry = SyncKeyEntry(keyBytes, 7L, derived.blobID)
        assertEquals(SyncStartResult.NotEnabled, r.service.start({ SyncLocalData() }, r.sink))
        assertTrue(r.service.rolledBackAtStartup)
        assertNotJoined(r)

        // 已有「已开启」状态 + pending 快照 -> 提交已定局，只删快照、正常激活
        val done = rig()
        assertTrue(done.journal.snapshot())
        val activeState = SyncState(SyncPhase.ACTIVE, origin, derived.blobID, SyncClock(dev = 7L), epoch = epoch)
        done.store.save(activeState)
        done.keys.entry = SyncKeyEntry(keyBytes, 7L, derived.blobID)
        assertEquals(SyncStartResult.Active, done.service.start({ SyncLocalData() }, done.sink))
        assertFalse(done.journal.exists)
        assertNotNull(done.keys.entry)
    }

    @Test
    fun testJoin409WatermarkCheckReconfirmAndCancel() = runBlocking {
        // 1. 版本回退或同版本换信封 -> 拒绝加入
        for ((ver, label) in listOf(2L to "版本回退", 3L to "同版本换信封")) {
            val r = rig()
            val ticket = (r.service.prepareJoin(code, SyncLocalData(localCuration)) as SyncJoinPrepared.Preview).ticket
            r.transport.puts.clear()
            r.transport.puts.add(remoteBlob(listOf("zed", "extra"), ver, conflict = true))
            val res = r.service.commitJoin(ticket, { SyncLocalData(localCuration) }, r.sink)
            assertTrue("$label 应失败，实际 $res", res is SyncJoinCommit.Failed)
            assertNotJoined(r)
        }
        // 2. 409 计数不变 -> 自动重试成功
        run {
            val r = rig()
            val ticket = (r.service.prepareJoin(code, SyncLocalData(localCuration)) as SyncJoinPrepared.Preview).ticket
            r.transport.puts.clear()
            r.transport.puts.add(remoteBlob(listOf("zed", "yan"), 4L, conflict = true))
            r.transport.puts.add(SyncServerResponse.Stored(5L, epoch, nowMs))
            assertEquals(SyncJoinCommit.Joined, r.service.commitJoin(ticket, { SyncLocalData(localCuration) }, r.sink))
            assertEquals(setOf("alice", "zed", "yan"), r.sink.curation.favorites.toSet())
        }
        // 3. 409 计数变化 -> 回到确认页（不写本机）
        run {
            val r = rig()
            val ticket = (r.service.prepareJoin(code, SyncLocalData(localCuration)) as SyncJoinPrepared.Preview).ticket
            r.transport.puts.clear()
            r.transport.puts.add(remoteBlob(listOf("zed", "alice"), 4L, conflict = true))
            val res = r.service.commitJoin(ticket, { SyncLocalData(localCuration) }, r.sink)
            assertTrue("计数变化应回确认页，实际 $res", res is SyncJoinCommit.Reconfirm)
            assertEquals(0, (res as SyncJoinCommit.Reconfirm).ticket.preview.n)
            assertNotJoined(r)
        }
        // 4. M11: 重算期间被取消 -> 返回 Cancelled，不返回新票据
        run {
            val r = rig()
            val ticket = (r.service.prepareJoin(code, SyncLocalData(localCuration)) as SyncJoinPrepared.Preview).ticket
            val res = r.service.commitJoin(
                ticket,
                takeLocal = {
                    ticket.cancel()
                    SyncLocalData(Curation(favorites = listOf("alice", "bob"), blocked = listOf("mallory")))
                },
                sink = r.sink,
            )
            assertEquals(SyncJoinCommit.Cancelled, res)
            assertNotJoined(r)
        }
    }

    @Test
    fun testConcurrentCyclesAreSingleFlight() = runBlocking {
        val r = rig()
        r.transport.gets.clear()
        r.transport.puts.clear()
        r.transport.gets.add(SyncServerResponse.NotFound(epoch, nowMs))
        r.transport.puts.add(SyncServerResponse.Stored(1L, epoch, nowMs))
        val created = r.service.create(origin, SyncLocalData(Curation(favorites = listOf("alice"))))
        assertTrue(created is SyncCreateResult.Created)

        val remote = remoteBlob(listOf("zed"), 2L)
        r.transport.gets.add(remote)
        r.transport.gets.add(remote)
        r.transport.puts.add(SyncServerResponse.Stored(3L, epoch, nowMs))
        r.transport.delayMs = 50L
        val results = listOf(
            async { r.service.runCycle() },
            async { r.service.runCycle() },
        ).awaitAll()
        assertEquals(1, r.transport.maxInFlight)
        assertTrue(results.all { it != null })
        assertEquals(3L, r.service.state?.lastSeenVer)
        assertNull(r.service.state?.inflight)
    }

    @Test
    fun testStatusTextMatchesSpec() {
        assertFalse(SyncStatusText.flow(SyncFlowResult.StorageFailed).contains("删除"))
        assertTrue(SyncStatusText.flow(SyncFlowResult.LocalSaveFailed).contains("本机保存失败"))
        assertEquals("", SyncStatusText.flow(SyncFlowResult.Done))
        assertTrue(SyncStatusText.flow(SyncFlowResult.RemotePending).contains("清除尚未完成"))
        val gone = SyncStatusText.halt(SyncHalt.Gone(1_800_000_000_000L))
        assertTrue(gone.contains("被清除"))
        assertTrue(gone.contains("本机数据都还在"))
        assertTrue(SyncStatusText.cycle(SyncCycleResult.QuotaFull)!!.contains("服务器空间已满"))
        assertTrue(SyncStatusText.cycle(SyncCycleResult.RateLimited(60))!!.contains("频繁"))
    }

    private class Rig(
        keyBytes: ByteArray,
        derived: vela.core.sync.SyncDerived,
        origin: String,
        nowMs: Long,
    ) {
        val transport = TestScriptedTransport()
        val store = TestMemoryStateStore()
        val keys = TestMemoryKeyStore()
        val sink = TestMemorySink()
        val journal = TestMemoryJournal(sink)
        val service = SyncService(
            SyncService.Environment(
                stateStore = store,
                keyStore = keys,
                makeTransport = { _, _ -> transport },
                journal = journal,
                wallClock = { nowMs },
                random = { n -> if (n == 32) keyBytes.copyOf() else ByteArray(n) { 7 } },
            ),
        )
    }
}

internal class TestMemoryStateStore : SyncStateStore {
    var stored: SyncState? = null
    var corrupted: Boolean = false
    var failSave: Boolean = false
    var onSave: ((SyncState) -> Unit)? = null

    override fun load(): SyncStateRead = when {
        corrupted -> SyncStateRead.Corrupted
        stored != null -> SyncStateRead.Present(stored!!.deepCopy())
        else -> SyncStateRead.None
    }

    override fun save(state: SyncState): Boolean {
        onSave?.invoke(state)
        if (failSave || corrupted) return false
        stored = state.deepCopy()
        return true
    }

    override fun delete(): Boolean {
        stored = null
        corrupted = false
        return true
    }
}

internal class TestMemoryKeyStore : SyncKeyStore {
    var entry: SyncKeyEntry? = null
    var lookupOverride: SyncKeyLookup? = null
    var failWrite: Boolean = false
    var failDelete: Boolean = false
    var onWrite: (() -> Unit)? = null

    override fun readEntry(): SyncKeyLookup {
        lookupOverride?.let { return it }
        return entry?.let { SyncKeyLookup.Present(it) } ?: SyncKeyLookup.None
    }

    override fun write(entry: SyncKeyEntry): SyncKeyWrite {
        onWrite?.invoke()
        if (failWrite) return SyncKeyWrite.FAILED
        this.entry = entry
        return SyncKeyWrite.OK
    }

    override fun read(): SyncKeychainRead = when (val l = readEntry()) {
        SyncKeyLookup.None -> SyncKeychainRead.None
        is SyncKeyLookup.Present -> SyncKeychainRead.Present(l.entry.blobID)
        SyncKeyLookup.Unavailable -> SyncKeychainRead.Unavailable
        SyncKeyLookup.Invalidated -> SyncKeychainRead.Invalidated
    }

    override fun delete(): Boolean {
        if (failDelete) return false
        entry = null
        return true
    }
}

internal class TestMemorySink : SyncJoinSink {
    var curation = Curation()
    var taste = Taste()
    val settings = linkedMapOf<String, Boolean>()
    var failOnSave: Int? = null
    var saveCount = 0
    var onSave: (() -> Unit)? = null

    override fun cancelPendingSaves() = Unit
    override fun currentTaste(): Taste = taste

    override fun saveCuration(curation: Curation): Boolean {
        saveCount += 1
        onSave?.invoke()
        if (failOnSave == saveCount) return false
        this.curation = curation
        return true
    }

    override fun saveTaste(taste: Taste): Boolean {
        saveCount += 1
        onSave?.invoke()
        if (failOnSave == saveCount) return false
        this.taste = taste
        return true
    }

    override fun applySettings(settings: Map<String, Boolean>) {
        this.settings.putAll(settings)
    }

    override fun persistPending(): Boolean = true
    override fun reloadAfterRollback() = Unit
}

internal class TestMemoryJournal(private val sink: TestMemorySink) : SyncJoinJournal {
    private var snapCuration: Curation? = null
    private var snapTaste: Taste? = null
    private var snapSettings: Map<String, Boolean>? = null
    private var currentStatus = SyncJoinJournalStatus.NONE

    var failSnapshot = false
    var failRestore = false
    var onSnapshot: (() -> Unit)? = null

    override val status: SyncJoinJournalStatus get() = currentStatus

    override fun snapshot(): Boolean {
        onSnapshot?.invoke()
        if (failSnapshot) return false
        snapCuration = sink.curation
        snapTaste = sink.taste
        snapSettings = sink.settings.toMap()
        currentStatus = SyncJoinJournalStatus.PENDING
        return true
    }

    override fun markCommitted(): Boolean {
        if (currentStatus == SyncJoinJournalStatus.PENDING) currentStatus = SyncJoinJournalStatus.COMMITTED
        return currentStatus == SyncJoinJournalStatus.COMMITTED
    }

    override fun restore(): Boolean {
        if (currentStatus != SyncJoinJournalStatus.PENDING || failRestore) return false
        snapCuration?.let { sink.curation = it }
        snapTaste?.let { sink.taste = it }
        snapSettings?.let {
            sink.settings.clear()
            sink.settings.putAll(it)
        }
        return true
    }

    override fun discard(): Boolean {
        snapCuration = null
        snapTaste = null
        snapSettings = null
        currentStatus = SyncJoinJournalStatus.NONE
        return true
    }
}

internal class TestScriptedTransport(
    val gets: MutableList<SyncServerResponse> = mutableListOf(),
    val puts: MutableList<SyncServerResponse> = mutableListOf(),
    val deletes: MutableList<SyncServerResponse> = mutableListOf(),
) : SyncTransport {
    var delayMs: Long = 0L
    var onPut: (() -> Unit)? = null
    private val active = AtomicInteger(0)
    @Volatile
    var maxInFlight = 0

    private suspend fun <T> tracked(block: () -> T): T {
        val now = active.incrementAndGet()
        if (now > maxInFlight) maxInFlight = now
        return try {
            if (delayMs > 0) delay(delayMs)
            block()
        } finally {
            active.decrementAndGet()
        }
    }

    override suspend fun get(): SyncServerResponse = tracked {
        if (gets.isEmpty()) SyncServerResponse.Transient else gets.removeAt(0)
    }

    override suspend fun put(request: SyncPutRequest): SyncServerResponse = tracked {
        onPut?.invoke()
        if (puts.isEmpty()) SyncServerResponse.Transient else puts.removeAt(0)
    }

    override suspend fun delete(base: Long, epoch: String?): SyncServerResponse = tracked {
        if (deletes.isEmpty()) SyncServerResponse.Transient else deletes.removeAt(0)
    }
}
