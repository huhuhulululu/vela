package vela.app.sync

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vela.core.curation.Curation
import vela.core.sync.SyncClock
import vela.core.sync.SyncCode
import vela.core.sync.SyncCoordinator
import vela.core.sync.SyncCycleResult
import vela.core.sync.SyncDerived
import vela.core.sync.SyncDocument
import vela.core.sync.SyncFlowResult
import vela.core.sync.SyncHalt
import vela.core.sync.SyncJoinPreview
import vela.core.sync.SyncLocalData
import vela.core.sync.SyncLocalOp
import vela.core.sync.SyncOpOutcome
import vela.core.sync.SyncOps
import vela.core.sync.SyncPhase
import vela.core.sync.SyncProjection
import vela.core.sync.SyncPutRequest
import vela.core.sync.SyncReplica
import vela.core.sync.SyncServerResponse
import vela.core.sync.SyncStateFileStore
import vela.core.sync.SyncStateRead
import vela.core.sync.SyncTransport
import vela.core.sync.deriveSyncKeys
import vela.core.sync.sha256
import vela.core.taste.Taste

/**
 * S6 同步接线对照测试：逐条覆盖 Swift `Tests/VelaSyncTests/` 中原标注 `deferred:S6`
 * 且安卓具备对应行为的用例（集成流程、加入事务、提交锁冲突取消、单飞不饿死线程池）。
 */
class SyncParityTest {
    private lateinit var tempDir: Path
    private val logicalOrigin = "https://sync.example"
    private val epoch = "0123456789abcdef0123456789abcdef"
    private val baseNowMs = 1_800_000_000_000L
    private val keyBytes = ByteArray(32) { (it + 9).toByte() }
    private val derived = deriveSyncKeys(keyBytes)
    private val fixtureCode = SyncCode.format(keyBytes, logicalOrigin, ::sha256)!!
    private val localCuration = Curation(favorites = listOf("alice"), blocked = listOf("mallory"))

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("vela-sync-parity-")
    }

    @After
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    private inner class IntegrationDevice(
        name: String,
        server: FakeSyncServer,
    ) {
        var clock: Long = baseNowMs
        var signingOverride: SyncDerived? = null
        val stateStore = SyncMarkedStateStore(SyncStateFileStore(tempDir.resolve("$name-state.json")))
        val keyStore = TestMemoryKeyStore()
        val sink = TestMemorySink()
        val journal = TestMemoryJournal(sink)
        val service = SyncService(
            SyncService.Environment(
                stateStore = stateStore,
                keyStore = keyStore,
                makeTransport = { _, keys -> SyncHttpTransport(server.baseUrl, signingOverride ?: keys) },
                journal = journal,
                wallClock = { clock },
            ),
        )

        val doc: SyncDocument? get() = service.state?.doc

        fun tick(ms: Long) {
            clock += ms
        }

        fun local(favorites: List<String> = emptyList(), blocked: List<String> = emptyList()): SyncLocalData =
            SyncLocalData(Curation(favorites = favorites, blocked = blocked))
    }

    private suspend fun createdCode(
        device: IntegrationDevice,
        favorites: List<String> = listOf("alice"),
    ): String {
        val res = device.service.create(logicalOrigin, device.local(favorites = favorites))
        assertTrue("创建失败：$res", res is SyncCreateResult.Created)
        val created = res as SyncCreateResult.Created
        assertEquals(SyncCycleResult.Created, created.firstCycle)
        assertEquals(9, created.fingerprint.length)
        return created.code
    }

    private fun remoteBlob(
        favorites: List<String>,
        ver: Long,
        conflict: Boolean = false,
    ): SyncServerResponse {
        var replica = SyncReplica(SyncDocument.empty(), SyncClock(dev = 99L))
        var pt = 1_799_999_000_000L
        for (f in favorites) {
            pt += 10L
            replica = (SyncOps.apply(SyncLocalOp.Favorite(f), replica, pt) as SyncOpOutcome.Applied).replica
        }
        val codec = SyncCryptoCodec(derived)
        val env = codec.seal(replica.doc.encoded(), ver)!!
        return if (conflict) {
            SyncServerResponse.Conflict(ver, derived.pubB64, env, epoch, baseNowMs)
        } else {
            SyncServerResponse.Blob(ver, derived.pubB64, env, epoch, baseNowMs)
        }
    }

    private inner class TransactionRig {
        val transport = TestScriptedTransport()
        val store = TestMemoryStateStore()
        val keys = TestMemoryKeyStore()
        val sink = TestMemorySink()
        val journal = TestMemoryJournal(sink)
        val originalTaste: Taste
        val service: SyncService

        init {
            sink.curation = localCuration
            sink.taste = Taste().vetoing("t:asian", Instant.ofEpochSecond(1_000L))
            originalTaste = sink.taste
            transport.gets.add(remoteBlob(listOf("zed"), 3L))
            service = SyncService(
                SyncService.Environment(
                    stateStore = store,
                    keyStore = keys,
                    makeTransport = { _, _ -> transport },
                    journal = journal,
                    wallClock = { baseNowMs },
                    random = { n -> if (n == 32) keyBytes.copyOf() else ByteArray(n) { 7 } },
                ),
            )
        }

        val localData: SyncLocalData
            get() = SyncLocalData(curation = localCuration, vetoedKeys = listOf("t:asian"))

        fun assertUntouched() {
            assertEquals(SyncStateRead.None, store.load())
            assertNull(keys.entry)
            assertEquals(localCuration, sink.curation)
            assertEquals(originalTaste, sink.taste)
            assertFalse(service.isActive)
            assertFalse(journal.exists)
            assertFalse(service.isCommittingJoin)
        }
    }

    // --- SyncIntegrationTests ---

    // parity: Tests/VelaSyncTests/SyncIntegrationTests.swift#testCreateThenJoinShowsPreviewAndBothDevicesConverge
    @Test
    fun testCreateThenJoinShowsPreviewAndBothDevicesConverge() = runBlocking {
        FakeSyncServer(nowMs = { baseNowMs }).use { server ->
            val a = IntegrationDevice("a", server)
            val b = IntegrationDevice("b", server)
            val code = createdCode(a, favorites = listOf("alice", "Bob_X"))
            assertEquals(code, a.service.currentCode())

            b.tick(10L)
            val prepared = b.service.prepareJoin(
                code,
                b.local(favorites = listOf("carol", "alice"), blocked = listOf("dave")),
            )
            assertTrue("加入预览失败：$prepared", prepared is SyncJoinPrepared.Preview)
            val ticket = (prepared as SyncJoinPrepared.Preview).ticket
            assertEquals("sync.example", ticket.host)
            assertEquals(SyncCode.fingerprint(a.service.state!!.blobID), ticket.fingerprint)
            assertEquals(SyncJoinPreview(n = 1, m = 1, v = 0, x = 0, y = 0, z = 0), ticket.preview)

            val pushed = b.service.commitJoin(
                ticket,
                takeLocal = { b.local(favorites = listOf("carol", "alice"), blocked = listOf("dave")) },
                sink = b.sink,
            )
            assertEquals(SyncJoinCommit.Joined, pushed)
            assertEquals(SyncPhase.ACTIVE, b.service.state?.phase)

            a.tick(10L)
            assertEquals(SyncCycleResult.UpToDate, a.service.runCycle())
            assertEquals(a.doc, b.doc)
            assertEquals(
                listOf("Bob_X", "alice", "carol").sorted(),
                SyncProjection(a.doc!!).curation.favorites.sorted(),
            )
            assertEquals(listOf("dave"), SyncProjection(a.doc!!).curation.blocked)
        }
    }

    // parity: Tests/VelaSyncTests/SyncIntegrationTests.swift#testPushThenPullCarriesLocalEdits
    @Test
    fun testPushThenPullCarriesLocalEdits() = runBlocking {
        FakeSyncServer(nowMs = { baseNowMs }).use { server ->
            val a = IntegrationDevice("a", server)
            val b = IntegrationDevice("b", server)
            val code = createdCode(a)
            val ticket = (b.service.prepareJoin(code, b.local()) as SyncJoinPrepared.Preview).ticket
            assertEquals(SyncJoinCommit.Joined, b.service.commitJoin(ticket, { b.local() }, b.sink))

            a.tick(1_000L)
            assertNotNull(a.service.perform(SyncLocalOp.Block("mallory")))
            assertEquals(SyncCycleResult.Pushed, a.service.runCycle())
            b.tick(1_000L)
            assertEquals(SyncCycleResult.UpToDate, b.service.runCycle())
            assertEquals(listOf("mallory"), SyncProjection(b.doc!!).curation.blocked)
            val putsBefore = server.requests.count { it.method == "PUT" }
            assertEquals(SyncCycleResult.UpToDate, b.service.runCycle())
            assertEquals("无改动周期不得发 PUT", putsBefore, server.requests.count { it.method == "PUT" })
        }
    }

    // parity: Tests/VelaSyncTests/SyncIntegrationTests.swift#testConflictMergesAndRetries
    @Test
    fun testConflictMergesAndRetries() = runBlocking {
        FakeSyncServer(nowMs = { baseNowMs }).use { server ->
            val a = IntegrationDevice("a", server)
            val b = IntegrationDevice("b", server)
            val code = createdCode(a)
            val ticket = (b.service.prepareJoin(code, b.local()) as SyncJoinPrepared.Preview).ticket
            assertEquals(SyncJoinCommit.Joined, b.service.commitJoin(ticket, { b.local() }, b.sink))
            assertEquals(SyncCycleResult.UpToDate, a.service.runCycle())

            a.tick(500L)
            assertNotNull(a.service.perform(SyncLocalOp.Favorite("erin")))
            b.tick(600L)
            assertNotNull(b.service.perform(SyncLocalOp.Favorite("frank")))
            assertEquals(SyncCycleResult.Pushed, a.service.runCycle())

            val keys = b.service.keys!!
            val direct = SyncHttpTransport(server.baseUrl, keys)
            val codec = SyncCryptoCodec(keys)
            val oldVer = b.service.state!!.lastSeenVer
            val stale = SyncPutRequest(
                base = oldVer,
                next = oldVer + 1L,
                envelope = codec.seal(b.doc!!.encoded(), oldVer + 1L)!!,
                epoch = b.service.state!!.epoch,
            )
            val staleResp = direct.put(stale)
            assertTrue("旧版本应得 409，实际 $staleResp", staleResp is SyncServerResponse.Conflict)
            assertEquals(oldVer + 1L, (staleResp as SyncServerResponse.Conflict).ver)

            a.tick(500L)
            assertNotNull(a.service.perform(SyncLocalOp.Favorite("grace")))
            var fired = false
            var conflicts = 0
            val racing = object : SyncTransport {
                override suspend fun get(): SyncServerResponse = direct.get()
                override suspend fun put(request: SyncPutRequest): SyncServerResponse {
                    if (!fired) {
                        fired = true
                        a.service.runCycle()
                    }
                    val resp = direct.put(request)
                    if (resp is SyncServerResponse.Conflict) conflicts += 1
                    return resp
                }

                override suspend fun delete(base: Long, epoch: String?): SyncServerResponse =
                    direct.delete(base, epoch)
            }
            val coordinator = SyncCoordinator(
                state = b.service.state!!,
                store = b.stateStore,
                transport = racing,
                codec = codec,
                wallClock = { b.clock },
            )
            assertEquals(SyncCycleResult.Pushed, coordinator.runCycle())
            assertEquals(1, conflicts)
            val favorites = SyncProjection(coordinator.state.doc).curation.favorites
            for (name in listOf("alice", "erin", "frank", "grace")) {
                assertTrue("合并后应包含 $name", favorites.contains(name))
            }
        }
    }

    // parity: Tests/VelaSyncTests/SyncIntegrationTests.swift#testForbiddenHaltsWrites
    @Test
    fun testForbiddenHaltsWrites() = runBlocking {
        FakeSyncServer(nowMs = { baseNowMs }).use { server ->
            val a = IntegrationDevice("a", server)
            createdCode(a)
            val wrong = deriveSyncKeys(ByteArray(32) { 7 })
            val real = a.service.keys!!
            a.signingOverride = SyncDerived(
                blobID = real.blobID,
                encKey = real.encKey,
                signCounter = 0,
                d = wrong.d,
                pub = real.pub,
            )
            assertEquals(SyncStartResult.Active, a.service.start({ a.local() }, a.sink))
            a.tick(100L)
            assertNotNull(a.service.perform(SyncLocalOp.Favorite("trudy")))
            assertEquals(SyncCycleResult.Halted(SyncHalt.Forbidden), a.service.runCycle())
            assertEquals(SyncHalt.Forbidden, a.service.state?.halt)

            val requestsBefore = server.requests.size
            assertEquals(SyncCycleResult.Halted(SyncHalt.Forbidden), a.service.runCycle())
            assertEquals("停写后不得再发请求", requestsBefore, server.requests.size)
        }
    }

    // parity: Tests/VelaSyncTests/SyncIntegrationTests.swift#testClearCloudMakesOtherDeviceSeeGone
    @Test
    fun testClearCloudMakesOtherDeviceSeeGone() = runBlocking {
        FakeSyncServer(nowMs = { baseNowMs }).use { server ->
            val a = IntegrationDevice("a", server)
            val b = IntegrationDevice("b", server)
            val code = createdCode(a)
            val ticket = (b.service.prepareJoin(code, b.local()) as SyncJoinPrepared.Preview).ticket
            assertEquals(SyncJoinCommit.Joined, b.service.commitJoin(ticket, { b.local() }, b.sink))
            assertEquals(SyncCycleResult.UpToDate, a.service.runCycle())

            assertEquals(SyncFlowResult.Done, a.service.clear(a.sink))
            assertFalse(a.service.isActive)
            assertNull(a.keyStore.entry)
            assertEquals(listOf("alice"), a.sink.curation.favorites)

            b.tick(100L)
            val result = b.service.runCycle()
            assertTrue("应 410，实际 $result", result is SyncCycleResult.Halted && result.halt is SyncHalt.Gone)
            val gone = (result as SyncCycleResult.Halted).halt as SyncHalt.Gone
            assertTrue(gone.deletedAt > 0L)
            val message = SyncStatusText.halt(gone)
            assertTrue(message, message.contains("被清除"))
            assertTrue(message, message.contains("本机数据都还在"))
            assertEquals(listOf("alice"), SyncProjection(b.doc!!).curation.favorites)

            val c = IntegrationDevice("c", server)
            assertEquals(SyncJoinPrepared.Gone, c.service.prepareJoin(code, c.local()))
            assertEquals(SyncStateRead.None, c.stateStore.load())
        }
    }

    // parity: Tests/VelaSyncTests/SyncIntegrationTests.swift#testJoinUnknownCodeOffersCreate
    @Test
    fun testJoinUnknownCodeOffersCreate() = runBlocking {
        FakeSyncServer(nowMs = { baseNowMs }).use { server ->
            val c = IntegrationDevice("c", server)
            val k = ByteArray(32) { 3 }
            val code = SyncCode.format(k, logicalOrigin, ::sha256)!!
            val prepared = c.service.prepareJoin(code, c.local(favorites = listOf("zed")))
            assertTrue(prepared is SyncJoinPrepared.NotFound)
            val created = c.service.createWithCode(
                (prepared as SyncJoinPrepared.NotFound).code,
                c.local(favorites = listOf("zed")),
            )
            assertTrue(created is SyncCreateResult.Created)
            assertEquals(SyncCycleResult.Created, (created as SyncCreateResult.Created).firstCycle)
            assertEquals(code, c.service.currentCode())
        }
    }

    // parity: Tests/VelaSyncTests/SyncIntegrationTests.swift#testInvalidCodeIsRejectedWithoutState
    @Test
    fun testInvalidCodeIsRejectedWithoutState() = runBlocking {
        FakeSyncServer(nowMs = { baseNowMs }).use { server ->
            val c = IntegrationDevice("c", server)
            assertEquals(SyncJoinPrepared.InvalidCode, c.service.prepareJoin("vela2:nope", c.local()))
            assertEquals(SyncStateRead.None, c.stateStore.load())
        }
    }

    // --- SyncJoinCommitLockTests (缺失用例) ---

    // parity: Tests/VelaSyncTests/SyncJoinCommitLockTests.swift#testM11_cancelDuringConflictedPutReturnsCancelled
    @Test
    fun testM11_cancelDuringConflictedPutReturnsCancelled() = runBlocking {
        val r = TransactionRig()
        val ticket = (r.service.prepareJoin(fixtureCode, r.localData) as SyncJoinPrepared.Preview).ticket
        r.transport.puts.add(remoteBlob(listOf("zed", "alice"), 4L, conflict = true))
        r.transport.onPut = { ticket.cancel() }
        val result = r.service.commitJoin(ticket, { r.localData }, r.sink)
        assertEquals(SyncJoinCommit.Cancelled, result)
        r.assertUntouched()
    }

    // --- SyncJoinTransactionTests ---

    // parity: Tests/VelaSyncTests/SyncJoinTransactionTests.swift#testPreviewPersistsNothing
    @Test
    fun testPreviewPersistsNothing() = runBlocking {
        val r = TransactionRig()
        val prepared = r.service.prepareJoin(fixtureCode, r.localData)
        assertTrue(prepared is SyncJoinPrepared.Preview)
        val ticket = (prepared as SyncJoinPrepared.Preview).ticket
        assertEquals(1, ticket.preview.n)
        assertEquals(0, r.sink.saveCount)
        assertNull(r.store.stored)
        assertNull(r.keys.entry)
        r.assertUntouched()
    }

    // parity: Tests/VelaSyncTests/SyncJoinTransactionTests.swift#testCancelAfterPreviewLeavesEverythingAsIs
    @Test
    fun testCancelAfterPreviewLeavesEverythingAsIs() = runBlocking {
        val r = TransactionRig()
        val ticket = (r.service.prepareJoin(fixtureCode, r.localData) as SyncJoinPrepared.Preview).ticket
        var putCalled = false
        r.transport.onPut = { putCalled = true }
        ticket.cancel()
        assertEquals(SyncJoinCommit.Cancelled, r.service.commitJoin(ticket, { r.localData }, r.sink))
        assertFalse("已取消的加入不写云端", putCalled)
        r.assertUntouched()
    }

    // parity: Tests/VelaSyncTests/SyncJoinTransactionTests.swift#testCancelDuringCommitLeavesLocalAsIs
    @Test
    fun testCancelDuringCommitLeavesLocalAsIs() = runBlocking {
        val r = TransactionRig()
        val ticket = (r.service.prepareJoin(fixtureCode, r.localData) as SyncJoinPrepared.Preview).ticket
        r.transport.puts.add(SyncServerResponse.Stored(4L, epoch, baseNowMs))
        r.transport.onPut = { ticket.cancel() }
        assertEquals(SyncJoinCommit.Cancelled, r.service.commitJoin(ticket, { r.localData }, r.sink))
        r.assertUntouched()
    }

    // parity: Tests/VelaSyncTests/SyncJoinTransactionTests.swift#testRemoteWriteFailureWritesNothingLocally
    @Test
    fun testRemoteWriteFailureWritesNothingLocally() = runBlocking {
        for (response in listOf(
            SyncServerResponse.Transient,
            SyncServerResponse.Forbidden,
            SyncServerResponse.Gone(1L),
        )) {
            val r = TransactionRig()
            val ticket = (r.service.prepareJoin(fixtureCode, r.localData) as SyncJoinPrepared.Preview).ticket
            r.transport.puts.add(response)
            val result = r.service.commitJoin(ticket, { r.localData }, r.sink)
            assertTrue("$response 应返回 Failed，实际 $result", result is SyncJoinCommit.Failed)
            r.assertUntouched()
        }
    }

    // parity: Tests/VelaSyncTests/SyncJoinTransactionTests.swift#testSuccessfulCommitPersistsWatermarkAndProjection
    @Test
    fun testSuccessfulCommitPersistsWatermarkAndProjection() = runBlocking {
        val r = TransactionRig()
        val ticket = (r.service.prepareJoin(fixtureCode, r.localData) as SyncJoinPrepared.Preview).ticket
        r.transport.puts.add(SyncServerResponse.Stored(4L, epoch, baseNowMs))
        assertEquals(SyncJoinCommit.Joined, r.service.commitJoin(ticket, { r.localData }, r.sink))
        assertTrue(r.service.isActive)
        val read = r.store.load()
        assertTrue(read is SyncStateRead.Present)
        val s = (read as SyncStateRead.Present).state
        assertEquals(SyncPhase.ACTIVE, s.phase)
        assertEquals(epoch, s.epoch)
        assertEquals(4L, s.lastSeenVer)
        assertNotNull(s.lastSeenHash)
        assertEquals(s.gen, s.pushedGen)
        assertNotNull(r.keys.entry)
        assertEquals(setOf("alice", "zed"), r.sink.curation.favorites.toSet())
        assertEquals(listOf("mallory"), r.sink.curation.blocked)
    }

    // --- SyncSingleFlightTests (缺失用例) ---

    // parity: Tests/VelaSyncTests/SyncSingleFlightTests.swift#testWaitingCyclesDoNotStarveTheThreadPool
    @Test
    fun testWaitingCyclesDoNotStarveTheThreadPool() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<SyncServerResponse>()
        var getCount = 0
        val transport = object : SyncTransport {
            override suspend fun get(): SyncServerResponse {
                getCount += 1
                return if (getCount == 1) {
                    SyncServerResponse.NotFound(epoch, baseNowMs)
                } else if (getCount == 2) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                } else {
                    SyncServerResponse.Transient
                }
            }

            override suspend fun put(request: SyncPutRequest): SyncServerResponse =
                SyncServerResponse.Stored(1L, epoch, baseNowMs)

            override suspend fun delete(base: Long, epoch: String?): SyncServerResponse =
                SyncServerResponse.Deleted
        }
        val service = SyncService(
            SyncService.Environment(
                stateStore = TestMemoryStateStore(),
                keyStore = TestMemoryKeyStore(),
                makeTransport = { _, _ -> transport },
                wallClock = { baseNowMs },
            ),
        )
        assertTrue(service.create(logicalOrigin, SyncLocalData()) is SyncCreateResult.Created)

        // 使用仅 2 条线程的受限线程池，同时排入 12 个周期，验证等待单飞 Mutex 挂起而不占线程。
        val pool = Executors.newFixedThreadPool(2)
        val dispatcher = pool.asCoroutineDispatcher()
        try {
            val jobs = List(12) {
                launch(dispatcher) {
                    service.runCycle()
                }
            }
            withTimeout(2_000L) { firstStarted.await() }
            val probe = CompletableDeferred<Unit>()
            launch(dispatcher) { probe.complete(Unit) }
            withTimeout(2_000L) { probe.await() }

            releaseFirst.complete(SyncServerResponse.Transient)
            withTimeout(2_000L) { jobs.forEach { it.join() } }
        } finally {
            dispatcher.close()
            pool.shutdownNow()
        }
    }
}
