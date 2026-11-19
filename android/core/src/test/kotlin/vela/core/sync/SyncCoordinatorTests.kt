package vela.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking

class SyncCoordinatorTests {
    class Wall(var now: Long)

    class Device(
        val coordinator: SyncCoordinator,
        val store: MemoryStateStore,
        val transport: MemoryTransport,
        val wall: Wall,
    )

    private val server = MemoryServer()

    private fun device(
        dev: Long,
        sizeLimit: Int = SyncLimits.plaintextBytes,
        state: SyncState? = null,
        codec: PlainCodec = PlainCodec(),
    ): Device {
        val s = state ?: SyncState(
            SyncPhase.ACTIVE,
            "https://sync.example.org",
            "a".repeat(64),
            SyncClock(0, 0, dev),
        )
        val store = MemoryStateStore(s)
        val transport = MemoryTransport(server)
        val wall = Wall(server.now)
        val c = SyncCoordinator(s, store, transport, codec, { wall.now }, sizeLimit)
        return Device(c, store, transport, wall)
    }

    private fun favorites(doc: SyncDocument?) = doc?.let { SyncProjection(it).curation.favorites } ?: emptyList()

    @Test
    fun testCreatesOn404ThenUpToDate() = runBlocking {
        val a = device(1)
        a.coordinator.perform(SyncLocalOp.Favorite("Alice"))
        assertEquals(SyncCycleResult.Created, a.coordinator.runCycle())
        assertEquals(1L, server.ver)
        assertEquals(0L, server.puts.first().base)
        assertTrue(server.puts.first().includesPub)
        assertEquals(listOf("Alice"), favorites(server.document))
        assertEquals(1L, a.coordinator.state.lastSeenVer)
        assertEquals(a.coordinator.state.gen, a.coordinator.state.pushedGen)
        assertNull(a.coordinator.state.inflight)
        assertEquals(SyncCycleResult.UpToDate, a.coordinator.runCycle())
        assertEquals(1, server.puts.size, "没有改动不做无意义的 PUT")
        assertEquals(a.coordinator.state, a.store.stored, "状态已落盘")
    }

    @Test
    fun testCreatesEvenWhenEmpty() = runBlocking {
        val a = device(1)
        assertEquals(SyncCycleResult.Created, a.coordinator.runCycle())
        assertEquals(SyncDocument.empty(), server.document)
    }

    @Test
    fun testTwoDevicesConverge() = runBlocking {
        val a = device(1)
        val b = device(2)
        a.coordinator.perform(SyncLocalOp.Favorite("alice"))
        a.coordinator.runCycle()
        b.coordinator.perform(SyncLocalOp.Block("bob"))
        assertEquals(SyncCycleResult.Pushed, b.coordinator.runCycle())
        assertEquals(SyncCycleResult.UpToDate, a.coordinator.runCycle())
        assertEquals(b.coordinator.state.doc, a.coordinator.state.doc)
        assertEquals(server.document, a.coordinator.state.doc)
    }

    @Test
    fun testNoChangeGetAdvancesWatermark() = runBlocking {
        val a = device(1)
        val b = device(2)
        a.coordinator.perform(SyncLocalOp.Favorite("alice"))
        a.coordinator.runCycle()
        val changed = ArrayList<SyncDocument>()
        b.coordinator.onDocumentChanged = { changed.add(it) }
        assertEquals(SyncCycleResult.UpToDate, b.coordinator.runCycle())
        assertEquals(1L, b.coordinator.state.lastSeenVer)
        assertEquals(sha256Hex(server.data!!.toByteArray(Charsets.UTF_8)), b.coordinator.state.lastSeenHash)
        assertEquals(1, server.puts.size, "只读到、不推送")
        assertEquals(1, changed.size, "文档变了才更新投影")
        assertEquals(a.coordinator.state.clock.l, b.coordinator.state.clock.l, "收到远端后时钟推进")
    }

    @Test
    fun testLocalEditDuringGetIsMergedAndPushed() = runBlocking {
        val a = device(1)
        val b = device(2)
        a.coordinator.perform(SyncLocalOp.Favorite("alice"))
        a.coordinator.runCycle()
        b.transport.beforeGetReturns = { b.coordinator.perform(SyncLocalOp.Favorite("carol")) }
        assertEquals(SyncCycleResult.Pushed, b.coordinator.runCycle())
        assertEquals(setOf("alice", "carol"), favorites(server.document).toSet())
    }

    @Test
    fun testLocalEditDuringPutStaysPending() = runBlocking {
        val a = device(1)
        a.coordinator.perform(SyncLocalOp.Favorite("alice"))
        a.transport.beforePutReturns = { a.coordinator.perform(SyncLocalOp.Favorite("dave")) }
        a.coordinator.runCycle()
        a.transport.beforePutReturns = null
        assertTrue(a.coordinator.state.gen > a.coordinator.state.pushedGen, "请求期间的新操作留到下一周期")
        assertEquals(listOf("alice"), favorites(server.document))
        assertEquals(SyncCycleResult.Pushed, a.coordinator.runCycle())
        assertEquals(listOf("dave", "alice"), favorites(server.document))
        assertEquals(a.coordinator.state.gen, a.coordinator.state.pushedGen)
    }

    @Test
    fun testPutCommittedButResponseLostIsConfirmedNextCycle() = runBlocking {
        val a = device(1)
        a.coordinator.runCycle()
        a.coordinator.perform(SyncLocalOp.Favorite("alice"))
        a.transport.dropNextPutResponse = true
        assertEquals(SyncCycleResult.Transient, a.coordinator.runCycle())
        assertEquals(2L, server.ver, "服务端已提交")
        assertEquals(2L, a.coordinator.state.inflight?.next, "在途请求保留")
        assertTrue(a.store.stored?.inflight != null, "在途请求在 PUT 之前落盘")
        assertTrue(a.coordinator.state.gen > a.coordinator.state.pushedGen)
        assertEquals(SyncCycleResult.UpToDate, a.coordinator.runCycle(), "确认推送成功，不再重推")
        assertNull(a.coordinator.state.inflight)
        assertEquals(a.coordinator.state.gen, a.coordinator.state.pushedGen)
        assertEquals(2, server.puts.size)
    }

    @Test
    fun testLostPutOverwrittenByOtherDeviceIsNotConfirmed() = runBlocking {
        val a = device(1)
        val b = device(2)
        a.coordinator.runCycle()
        a.coordinator.perform(SyncLocalOp.Favorite("alice"))
        a.transport.dropNextPutResponse = true
        a.coordinator.runCycle()
        b.coordinator.perform(SyncLocalOp.Favorite("bob"))
        b.coordinator.runCycle()
        val gen = a.coordinator.state.gen
        assertTrue(a.coordinator.state.pushedGen < gen)
        assertEquals(SyncCycleResult.Pushed, a.coordinator.runCycle(), "版本不是 inflight.next：不确认，重新合并推送（安全）")
        assertNull(a.coordinator.state.inflight)
        assertEquals(gen, a.coordinator.state.pushedGen)
        assertEquals(setOf("alice", "bob"), favorites(server.document).toSet())
    }

    @Test
    fun testConflictMergesAndRetries() = runBlocking {
        val a = device(1)
        val b = device(2)
        a.coordinator.runCycle()
        b.coordinator.runCycle()
        a.coordinator.perform(SyncLocalOp.Favorite("alice"))
        var once = true
        a.transport.beforeGetReturns = {
            if (once) {
                once = false
                b.coordinator.perform(SyncLocalOp.Favorite("bob"))
                b.coordinator.runCycle()
            }
        }
        assertEquals(SyncCycleResult.Pushed, a.coordinator.runCycle())
        assertEquals(setOf("alice", "bob"), favorites(server.document).toSet())
        assertEquals(2, server.puts.count { it.base == 1L }, "第一次 PUT 409，用响应合并后重推")
    }

    @Test
    fun testConflictLimitBacksOffKeepingPending() = runBlocking {
        val a = device(1)
        val b = device(2)
        a.coordinator.runCycle()
        b.coordinator.runCycle()
        a.coordinator.perform(SyncLocalOp.Favorite("alice"))
        var n = 0
        a.transport.beforePutSends = {
            n += 1
            b.coordinator.perform(SyncLocalOp.Favorite("b%03d".format(n)))
            b.coordinator.runCycle()
        }
        assertEquals(SyncCycleResult.ConflictLimit, a.coordinator.runCycle())
        assertTrue(a.coordinator.state.gen > a.coordinator.state.pushedGen, "未推送的改动保留")
        assertEquals(4, n, "首次加 3 次重试")
        a.transport.beforePutSends = null
        assertEquals(SyncCycleResult.Pushed, a.coordinator.runCycle())
        assertTrue(SyncProjection(server.document!!).isFavorite("cb:alice"))
    }

    private suspend fun seeded(): Device {
        val a = device(1)
        a.coordinator.perform(SyncLocalOp.Favorite("alice"))
        a.coordinator.runCycle()
        a.coordinator.perform(SyncLocalOp.Favorite("bob"))
        a.coordinator.runCycle()
        assertEquals(2L, a.coordinator.state.lastSeenVer)
        return a
    }

    @Test
    fun testRollbackToOlderVersionHalts() = runBlocking {
        val a = seeded()
        val old = PlainCodec.envelope(SyncDocument.empty(), 1)
        a.transport.scriptedGets.add(SyncServerResponse.Blob(1, "PUB", old, server.epoch, server.now))
        assertEquals(SyncCycleResult.Halted(SyncHalt.Rollback), a.coordinator.runCycle())
        assertEquals(listOf("bob", "alice"), favorites(a.coordinator.state.doc), "不合并")
        assertEquals(SyncHalt.Rollback, a.store.stored?.halt)
        val gets = server.gets
        assertEquals(SyncCycleResult.Halted(SyncHalt.Rollback), a.coordinator.runCycle())
        assertEquals(gets, server.gets, "停写后不再发请求")
    }

    @Test
    fun testSameVersionDifferentEnvelopeIsRollback() = runBlocking {
        val a = seeded()
        a.transport.scriptedGets.add(SyncServerResponse.Blob(2, "PUB", PlainCodec.envelope(SyncDocument.empty(), 2), server.epoch, server.now))
        assertEquals(SyncCycleResult.Halted(SyncHalt.Rollback), a.coordinator.runCycle())
    }

    @Test
    fun testEpochChangeHalts() = runBlocking {
        val a = seeded()
        server.epoch = "e0000000000000000000000000000002"
        assertEquals(SyncCycleResult.Halted(SyncHalt.EpochChanged), a.coordinator.runCycle())
    }

    @Test
    fun testGoneHalts() = runBlocking {
        val a = seeded()
        server.deletedAt = 1_700_000_000_000
        assertEquals(SyncCycleResult.Halted(SyncHalt.Gone(1_700_000_000_000)), a.coordinator.runCycle())
        assertEquals(listOf("bob", "alice"), favorites(a.coordinator.state.doc), "本地数据保留")
    }

    @Test
    fun testNotFoundAfterSeenHalts() = runBlocking {
        val a = seeded()
        a.transport.scriptedGets.add(SyncServerResponse.NotFound(server.epoch, server.now))
        assertEquals(SyncCycleResult.Halted(SyncHalt.Missing), a.coordinator.runCycle())
        assertEquals(2, server.puts.size, "不自动创建")
    }

    @Test
    fun testNotFoundWithInflightCreateHalts() = runBlocking {
        val a = device(1)
        a.transport.dropNextPutResponse = true
        a.coordinator.runCycle()
        assertEquals(0L, a.coordinator.state.inflight?.base)
        a.transport.scriptedGets.add(SyncServerResponse.NotFound(server.epoch, server.now))
        assertEquals(SyncCycleResult.Halted(SyncHalt.Missing), a.coordinator.runCycle(), "结果未知的创建之后遇 404 停写")
    }

    @Test
    fun testForbiddenHalts() = runBlocking {
        val a = seeded()
        a.coordinator.perform(SyncLocalOp.Favorite("carol"))
        a.transport.putOverride = SyncServerResponse.Forbidden
        assertEquals(SyncCycleResult.Halted(SyncHalt.Forbidden), a.coordinator.runCycle())
        assertTrue(SyncProjection(a.coordinator.state.doc).isFavorite("cb:carol"), "保留一切")
    }

    @Test
    fun testPubMismatchHalts() = runBlocking {
        seeded()
        val codec = PlainCodec()
        codec.publicKey = "OTHER"
        val b = device(2, codec = codec)
        assertEquals(SyncCycleResult.Halted(SyncHalt.PubMismatch), b.coordinator.runCycle())
    }

    @Test
    fun testNewerSchemaIsReadOnly() = runBlocking {
        val a = seeded()
        val newer = PlainCodec().seal("{\"schema\":3}".toByteArray(Charsets.UTF_8), 3)!!
        a.transport.scriptedGets.add(SyncServerResponse.Blob(3, "PUB", newer, server.epoch, server.now))
        a.coordinator.perform(SyncLocalOp.Favorite("carol"))
        assertEquals(SyncCycleResult.ReadOnly, a.coordinator.runCycle())
        assertEquals(2, server.puts.size, "只读不推送")
        assertEquals(2L, a.coordinator.state.lastSeenVer, "未通过校验不推进水位")
    }

    @Test
    fun testUnknownEnvelopePrefixIsReadOnly() = runBlocking {
        val a = seeded()
        a.transport.scriptedGets.add(SyncServerResponse.Blob(3, "PUB", "e9.xxxx", server.epoch, server.now))
        assertEquals(SyncCycleResult.ReadOnly, a.coordinator.runCycle())
    }

    @Test
    fun testInvalidRemoteIsNotMerged() = runBlocking {
        val a = seeded()
        val bad = PlainCodec().seal("{\"schema\":2,\"rooms\":{},\"veto\":{}}".toByteArray(Charsets.UTF_8), 3)!!
        a.transport.scriptedGets.add(SyncServerResponse.Blob(3, "PUB", bad, server.epoch, server.now))
        val before = a.coordinator.state.doc.deepish()
        assertEquals(SyncCycleResult.InvalidRemote, a.coordinator.runCycle())
        assertEquals(before, a.coordinator.state.doc)
        assertNull(a.coordinator.state.halt, "不停用，保留现场")
    }

    @Test
    fun testDecryptFailureHaltsOnlyAfterTwoCycles() = runBlocking {
        val codec = PlainCodec()
        val a = device(1, codec = codec)
        a.coordinator.runCycle()
        codec.failOpen = true
        assertEquals(SyncCycleResult.DecryptFailed, a.coordinator.runCycle())
        assertNull(a.coordinator.state.halt)
        codec.failOpen = false
        a.coordinator.runCycle()
        assertEquals(0, a.coordinator.state.decryptFailures, "成功一次清零")
        codec.failOpen = true
        a.coordinator.runCycle()
        assertEquals(SyncCycleResult.Halted(SyncHalt.DecryptFailed), a.coordinator.runCycle())
    }

    @Test
    fun testOnlineClockSkewPausesWrites() = runBlocking {
        val a = device(1)
        a.coordinator.runCycle()
        a.wall.now = server.now + 2 * 3_600_000
        a.coordinator.perform(SyncLocalOp.Favorite("alice"))
        assertEquals(SyncCycleResult.ClockSkew, a.coordinator.runCycle())
        assertEquals(1, server.puts.size)
    }

    @Test
    fun testOfflineFastClockIsRestampedBeforeSend() = runBlocking {
        val a = device(1)
        a.coordinator.runCycle()
        a.wall.now = server.now + 5 * 3_600_000
        a.coordinator.perform(SyncLocalOp.Unfavorite("nobody"))
        a.coordinator.perform(SyncLocalOp.Favorite("alice"))
        a.wall.now = server.now + 1_000
        assertEquals(SyncCycleResult.Pushed, a.coordinator.runCycle())
        val sent = server.document!!
        assertTrue(sent.rooms.getValue("cb:alice").f!!.hlc.l <= server.now + 3_600_000)
        assertEquals(sent.rooms.getValue("cb:alice").f!!.hlc.l, a.coordinator.state.maxSentL)
    }

    @Test
    fun testOversizeMergeStopsPushing() = runBlocking {
        val b = device(2)
        for (i in 0 until 40) b.coordinator.perform(SyncLocalOp.Favorite("user%03d".format(i)))
        b.coordinator.runCycle()
        val limit = server.document!!.encodedSize + 200
        val a = device(1, sizeLimit = limit)
        for (i in 0 until 10) a.coordinator.perform(SyncLocalOp.Favorite("mine%03d".format(i)))
        assertEquals(SyncCycleResult.Oversize, a.coordinator.runCycle())
        assertTrue(a.coordinator.state.oversize)
        assertEquals(50, SyncProjection(a.coordinator.state.doc).favorites.size, "合并结果保留在本机")
        assertEquals(1, server.puts.size, "停止推送")
    }

    @Test
    fun testOversizeLocalOpIsRejected() {
        val a = device(1, sizeLimit = 200)
        val first = a.coordinator.perform(SyncLocalOp.Favorite("averyveryverylongname_0001"))
        if (first.outcome !is SyncOpOutcome.Applied) fail("第一个放得下")
        val before = a.coordinator.state.deepCopy()
        val full = a.coordinator.perform(SyncLocalOp.Favorite("averyveryverylongname_0002"))
        assertEquals(SyncOpOutcome.Full, full.outcome)
        assertEquals(before, a.coordinator.state.deepCopy(), "拒绝的操作：文档、时钟与 gen 都不变")
    }

    @Test
    fun testCancelledResponseIsDropped() = runBlocking {
        val a = device(1)
        a.coordinator.perform(SyncLocalOp.Favorite("alice"))
        a.transport.beforeGetReturns = { a.coordinator.cancel() }
        assertEquals(SyncCycleResult.Cancelled, a.coordinator.runCycle())
        assertEquals(0, server.puts.size)
        assertNull(a.coordinator.state.epoch, "迟到响应不写状态")
    }

    @Test
    fun testStateSaveFailureBlocksPut() = runBlocking {
        val a = device(1)
        a.store.failSaves = true
        val local = a.coordinator.perform(SyncLocalOp.Favorite("alice"))
        assertEquals(false, local.saved, "保存失败要提示")
        assertTrue(SyncProjection(a.coordinator.state.doc).isFavorite("cb:alice"), "操作在内存中仍生效")
        assertEquals(SyncCycleResult.StorageFailed, a.coordinator.runCycle())
        assertEquals(0, server.puts.size, "在途请求没落盘就不发 PUT")
    }

    private fun SyncDocument.deepish(): SyncDocument =
        (SyncDocument.decode(encoded()) as SyncDecodeResult.Valid).doc
}
