package vela.app.sync

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import vela.app.MainActivity
import vela.app.infra.Settings
import vela.app.stage.StageIntent
import vela.app.stage.StageViewModel
import vela.core.base.lane.LaneEndReason
import vela.core.base.model.Gender
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult
import vela.core.base.site.CatalogPage
import vela.core.curation.Curation
import vela.core.ledger.DwellTracker
import vela.core.sync.SyncClock
import vela.core.sync.SyncCode
import vela.core.sync.SyncDocument
import vela.core.sync.SyncFlowResult
import vela.core.sync.SyncLocalData
import vela.core.sync.SyncLocalOp
import vela.core.sync.SyncOpOutcome
import vela.core.sync.SyncOps
import vela.core.sync.SyncPhase
import vela.core.sync.SyncProjection
import vela.core.sync.SyncPutRequest
import vela.core.sync.SyncReplica
import vela.core.sync.SyncServerResponse
import vela.core.sync.SyncState
import vela.core.sync.SyncStateRead
import vela.core.sync.SyncTransport
import vela.core.sync.deriveSyncKeys
import vela.core.sync.sha256

/**
 * 返工 A-SYNC-4 回归测试：
 * 1. 创建（enable / createFromJoin）在发起首轮网络请求前连续完成本机激活与同步码发布，
 *    首轮网络等待期间的本机收藏/不想看/设置写入进入权威同步文档，不被旧文档投影覆盖；
 * 2. 所有中间与待恢复状态（启动恢复中、创建未激活完、加入提交中、需要修复、清除中、清除失败待重试）
 *    统一阻断本机偏好写入，防止随后恢复屏障覆盖新编辑，同时观看账本照常记录；
 * 3. 投影落盘屏障传播同步设置 SharedPreferences.Editor.commit() 失败（加入、关闭、清除），
 *    且内存值被改写后重试绝不因内存相等而跳过重新 commit()；
 * 4. 真实 MainActivity 在系统凭据验证期间经过 onStop/onBackground 再回到前台时，
 *    RESULT_OK 仍正确更新 Hub 的 revealedCode，RESULT_CANCELED 拒绝展示。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class SyncIntermediateStateAndBarrierTest {
    private lateinit var context: Context
    private lateinit var dir: Path
    private lateinit var scope: CoroutineScope
    private val origin = "https://sync.example"
    private val epoch = "0123456789abcdef0123456789abcdef"
    private val nowMs = 1_800_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(AndroidSyncKeyStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).applicationInfo?.metaData = null
        dir = Files.createTempDirectory("vela-sync-a4-")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).applicationInfo?.metaData = null
        scope.cancel()
        dir.toFile().deleteRecursively()
    }

    private fun makeStage(settings: Settings = Settings(context)): StageViewModel = StageViewModel(
        settings = settings,
        http = object : HTTPClient {
            override suspend fun send(request: HTTPRequest): HTTPResult = HTTPResult(200, ByteArray(0))
        },
        directory = dir,
        dispatcher = Dispatchers.Unconfined,
        context = context,
    ).apply {
        ageConfirmed = true
    }

    private fun sampleRoom(name: String = "zoe"): Room = Room(
        username = name,
        displayName = name,
        gender = Gender.female,
        tags = listOf("jazz"),
        viewers = 100,
        show = ShowState.publicShow,
    )

    @Test
    fun testEnableAndCreateFromJoinActivateBeforeFirstNetworkAndPreserveEdits() = runBlocking {
        // 1. startEnable()：首轮网络未返回前已 Active + 发布 savedCode，期间舞台编辑写入权威文档不丢失
        val stage = makeStage()
        try {
            val getStarted = CompletableDeferred<Unit>()
            val releaseGet = CompletableDeferred<SyncServerResponse>()
            val transport = object : SyncTransport {
                override suspend fun get(): SyncServerResponse {
                    getStarted.complete(Unit)
                    return releaseGet.await()
                }

                override suspend fun put(request: SyncPutRequest): SyncServerResponse =
                    SyncServerResponse.Stored(request.next, epoch, nowMs)

                override suspend fun delete(base: Long, epoch: String?): SyncServerResponse =
                    SyncServerResponse.Deleted
            }
            val service = SyncService(
                SyncService.Environment(
                    stateStore = TestMemoryStateStore(),
                    keyStore = TestMemoryKeyStore(),
                    makeTransport = { _, _ -> transport },
                    wallClock = { nowMs },
                ),
            )
            val prefs = SyncPrefs(stage.settings.preferences).apply { experimentSync = true }
            val hub = SyncHub(
                service = service,
                prefs = prefs,
                bridge = StageSyncBridge(stage, prefs),
                scope = scope,
                sdkInt = 34,
                capability = SyncCapability.FULL,
                configuredOrigin = origin,
                wallClock = { nowMs },
            )
            stage.syncHub = hub
            hub.attach()

            val firstCycleJob = hub.startEnable()
            assertNotNull(firstCycleJob)
            withTimeout(2_000L) { getStarted.await() }

            // 首轮网络尚未返回：Hub 必须已经 Active 且发布 savedCode！
            assertTrue("首轮网络前服务必须已激活", service.isActive)
            assertEquals("首轮网络前 Hub 必须已处于 Active", SyncUiPhase.Active, hub.uiState.value.phase)
            assertTrue(hub.isOn)
            assertNotNull("首轮网络前必须已发布 savedCode", hub.uiState.value.savedCode)

            // 首轮网络等待期间在舞台做收藏、不想看与设置写入
            stage.dispatch(StageIntent.AddFavorite("during_first_cycle"))
            stage.dispatch(StageIntent.VetoTaste("t:during_first_cycle"))
            stage.dispatch(StageIntent.SetPublicOnly(false))

            // 放行首轮网络
            releaseGet.complete(SyncServerResponse.NotFound(epoch, nowMs))
            withTimeout(2_000L) { firstCycleJob!!.join() }

            // 首轮完成后，期间编辑绝不得被旧文档覆盖，必须在权威文档与本机视图中同时保留
            val doc = service.state!!.doc
            val proj = SyncProjection(doc)
            assertTrue("首轮网络期间收藏必须保留在同步文档中", proj.curation.isFavorite("during_first_cycle"))
            assertEquals(true, proj.veto["t:during_first_cycle"])
            assertEquals(false, proj.settings[Settings.KEY_PUBLIC_ONLY])
            assertTrue(stage.curation.isFavorite("during_first_cycle"))
            assertTrue(stage.taste.vetoedKeys.contains("t:during_first_cycle"))
            assertFalse(stage.publicOnly)
        } finally {
            stage.close()
        }

        // 2. startCreateFromJoin()：同样在首轮网络前连续激活，期间编辑不丢失
        val stage2 = makeStage()
        try {
            val createGetStarted = CompletableDeferred<Unit>()
            val releaseCreateGet = CompletableDeferred<SyncServerResponse>()
            var calls = 0
            val transport2 = object : SyncTransport {
                override suspend fun get(): SyncServerResponse {
                    calls += 1
                    return if (calls == 1) {
                        SyncServerResponse.NotFound(epoch, nowMs)
                    } else {
                        createGetStarted.complete(Unit)
                        releaseCreateGet.await()
                    }
                }

                override suspend fun put(request: SyncPutRequest): SyncServerResponse =
                    SyncServerResponse.Stored(request.next, epoch, nowMs)

                override suspend fun delete(base: Long, epoch: String?): SyncServerResponse =
                    SyncServerResponse.Deleted
            }
            val service2 = SyncService(
                SyncService.Environment(
                    stateStore = TestMemoryStateStore(),
                    keyStore = TestMemoryKeyStore(),
                    makeTransport = { _, _ -> transport2 },
                    wallClock = { nowMs },
                ),
            )
            val prefs2 = SyncPrefs(stage2.settings.preferences).apply { experimentSync = true }
            val hub2 = SyncHub(
                service = service2,
                prefs = prefs2,
                bridge = StageSyncBridge(stage2, prefs2),
                scope = scope,
                sdkInt = 34,
                capability = SyncCapability.FULL,
                configuredOrigin = origin,
                wallClock = { nowMs },
            )
            stage2.syncHub = hub2
            hub2.attach()
            val codeText = SyncCode.format(ByteArray(32) { (it + 13).toByte() }, origin, ::sha256)!!
            hub2.prepareJoin(codeText)
            assertTrue(hub2.uiState.value.joinStage is SyncUiJoinStage.NotFound)

            val job2 = hub2.startCreateFromJoin()
            assertNotNull(job2)
            withTimeout(2_000L) { createGetStarted.await() }
            assertEquals("404 创建在首轮网络前必须已 Active", SyncUiPhase.Active, hub2.uiState.value.phase)
            stage2.dispatch(StageIntent.VetoTaste("t:from_join_create"))

            releaseCreateGet.complete(SyncServerResponse.NotFound(epoch, nowMs))
            withTimeout(2_000L) { job2!!.join() }

            assertEquals(true, SyncProjection(service2.state!!.doc).veto["t:from_join_create"])
            assertTrue(stage2.taste.vetoedKeys.contains("t:from_join_create"))
        } finally {
            stage2.close()
        }
    }

    @Test
    fun testAllIntermediateStatesBlockLocalWritesUntilResolved() = runBlocking {
        val stage = makeStage()
        try {
            val room = sampleRoom()
            stage.feed = stage.feed.copy().also {
                it.replace(CatalogPage(rooms = listOf(room), total = 1, nextOffset = 1))
            }

            fun assertWritesBlocked(hub: SyncHub, label: String) {
                stage.syncHub = hub
                assertFalse("$label 期间 localWriteAllowed 必须为 false", hub.localWriteAllowed)
                val favBefore = stage.curation.favorites
                val tasteBefore = stage.taste
                val pubBefore = stage.publicOnly
                val infoBefore = stage.infoAlwaysOn
                val ledgerBefore = stage.watchLedger.entries[room.username]?.totalSeconds ?: 0.0

                stage.dispatch(StageIntent.AddFavorite("blocked_$label"))
                stage.dispatch(StageIntent.VetoTaste("t:blocked_$label"))
                stage.dispatch(StageIntent.SetPublicOnly(!pubBefore))
                stage.dispatch(StageIntent.ToggleInfo)
                stage.recordSettlement(
                    settled = DwellTracker.Settled(
                        room = room,
                        dwell = 45.0,
                        ledgerSeconds = 45.0,
                        start = Instant.ofEpochMilli(nowMs - 45_000L),
                    ),
                    endReason = LaneEndReason.SWIPE,
                    userInitiated = true,
                )

                assertEquals("$label 不得接受新收藏", favBefore, stage.curation.favorites)
                assertEquals("$label 不得修改口味", tasteBefore, stage.taste)
                assertEquals("$label 不得修改 publicOnly", pubBefore, stage.publicOnly)
                assertEquals("$label 不得修改 infoAlwaysOn", infoBefore, stage.infoAlwaysOn)
                val ledgerAfter = stage.watchLedger.entries[room.username]?.totalSeconds ?: 0.0
                assertTrue("$label 观看账本仍应照常记录", ledgerAfter > ledgerBefore)
            }

            val prefs = SyncPrefs(stage.settings.preferences).apply { experimentSync = true }
            val keyBytes = ByteArray(32) { (it + 21).toByte() }
            val derived = deriveSyncKeys(keyBytes)

            // 状态 1 & 2：清除进行中 -> 清除失败待重试（CLEARING 留盘）
            val deleteStarted = CompletableDeferred<Unit>()
            val releaseDelete = CompletableDeferred<SyncServerResponse>()
            val clearingStore = TestMemoryStateStore().apply {
                stored = SyncState(
                    phase = SyncPhase.ACTIVE,
                    origin = origin,
                    blobID = derived.blobID,
                    clock = SyncClock(dev = 4L),
                    epoch = epoch,
                    lastSeenVer = 1L,
                )
            }
            val clearingKeyStore = TestMemoryKeyStore().apply {
                entry = SyncKeyEntry(keyBytes, 4L, derived.blobID)
            }
            val clearingService = SyncService(
                SyncService.Environment(
                    stateStore = clearingStore,
                    keyStore = clearingKeyStore,
                    makeTransport = { _, _ ->
                        object : SyncTransport {
                            override suspend fun get(): SyncServerResponse = SyncServerResponse.Transient
                            override suspend fun put(request: SyncPutRequest): SyncServerResponse = SyncServerResponse.Transient
                            override suspend fun delete(base: Long, epoch: String?): SyncServerResponse {
                                deleteStarted.complete(Unit)
                                return releaseDelete.await()
                            }
                        }
                    },
                    wallClock = { nowMs },
                ),
            )
            val clearingHub = SyncHub(
                service = clearingService,
                prefs = prefs,
                bridge = StageSyncBridge(stage, prefs),
                scope = scope,
                sdkInt = 34,
                capability = SyncCapability.FULL,
                configuredOrigin = origin,
                wallClock = { nowMs },
            )
            clearingHub.attach()
            assertEquals(SyncUiPhase.Active, clearingHub.uiState.value.phase)

            val clearJob = clearingHub.startClearCloud()
            assertNotNull(clearJob)
            withTimeout(2_000L) { deleteStarted.await() }
            // (a) 清除进行中：必须拒绝本机偏好写入
            assertTrue(clearingService.isClearing)
            assertWritesBlocked(clearingHub, "clearing_in_progress")

            // 放行并模拟网络失败 -> 磁盘留 CLEARING -> 进入 Attention
            releaseDelete.complete(SyncServerResponse.Transient)
            withTimeout(2_000L) { clearJob!!.join() }
            assertEquals(SyncPhase.CLEARING, clearingStore.stored?.phase)
            assertTrue(clearingHub.uiState.value.phase is SyncUiPhase.Attention)

            // (b) 清除失败待重试：持续阻断本机偏好写入，防止随后恢复屏障覆盖
            assertTrue(clearingService.hasPendingRecovery)
            assertWritesBlocked(clearingHub, "clearing_failed_pending")

            // 用户点「关闭同步」彻底收尾回到 Off 后，恢复允许本机写入
            clearingHub.disable()
            assertEquals(SyncUiPhase.Off, clearingHub.uiState.value.phase)
            assertTrue("收尾回到 Off 后应重新允许本机写入", clearingHub.localWriteAllowed)
            stage.dispatch(StageIntent.AddFavorite("after_clear_disabled"))
            assertTrue(stage.curation.isFavorite("after_clear_disabled"))

            // 状态 3：清除取消后（CancellationException）同样持续阻断
            val cancelStore = TestMemoryStateStore().apply {
                stored = SyncState(
                    phase = SyncPhase.ACTIVE,
                    origin = origin,
                    blobID = derived.blobID,
                    clock = SyncClock(dev = 6L),
                    epoch = epoch,
                    lastSeenVer = 1L,
                )
            }
            val cancelService = SyncService(
                SyncService.Environment(
                    stateStore = cancelStore,
                    keyStore = TestMemoryKeyStore().apply { entry = SyncKeyEntry(keyBytes, 6L, derived.blobID) },
                    makeTransport = { _, _ ->
                        object : SyncTransport {
                            override suspend fun get(): SyncServerResponse = SyncServerResponse.Transient
                            override suspend fun put(request: SyncPutRequest): SyncServerResponse = SyncServerResponse.Transient
                            override suspend fun delete(base: Long, epoch: String?): SyncServerResponse {
                                throw CancellationException("cancelled delete")
                            }
                        }
                    },
                    wallClock = { nowMs },
                ),
            )
            val cancelHub = SyncHub(
                service = cancelService,
                prefs = prefs,
                bridge = StageSyncBridge(stage, prefs),
                scope = scope,
                sdkInt = 34,
                capability = SyncCapability.FULL,
                configuredOrigin = origin,
                wallClock = { nowMs },
            )
            cancelHub.attach()
            cancelHub.clearCloud()
            assertTrue(cancelHub.uiState.value.phase is SyncUiPhase.Attention)
            assertWritesBlocked(cancelHub, "clearing_cancelled_pending")

            // 状态 4：启动恢复中（磁盘留 CLEARING，重启时 recover 挂起网络）
            val startupStarted = CompletableDeferred<Unit>()
            val releaseStartup = CompletableDeferred<SyncServerResponse>()
            val restartStore = TestMemoryStateStore().apply {
                stored = SyncState(
                    phase = SyncPhase.CLEARING,
                    origin = origin,
                    blobID = derived.blobID,
                    clock = SyncClock(dev = 8L),
                    epoch = epoch,
                    lastSeenVer = 1L,
                )
            }
            val restartService = SyncService(
                SyncService.Environment(
                    stateStore = restartStore,
                    keyStore = TestMemoryKeyStore().apply { entry = SyncKeyEntry(keyBytes, 8L, derived.blobID) },
                    makeTransport = { _, _ ->
                        object : SyncTransport {
                            override suspend fun get(): SyncServerResponse = SyncServerResponse.Transient
                            override suspend fun put(request: SyncPutRequest): SyncServerResponse = SyncServerResponse.Transient
                            override suspend fun delete(base: Long, epoch: String?): SyncServerResponse {
                                startupStarted.complete(Unit)
                                return releaseStartup.await()
                            }
                        }
                    },
                    wallClock = { nowMs },
                ),
            )
            val restartHub = SyncHub(
                service = restartService,
                prefs = prefs,
                bridge = StageSyncBridge(stage, prefs),
                scope = scope,
                sdkInt = 34,
                capability = SyncCapability.FULL,
                configuredOrigin = origin,
                wallClock = { nowMs },
            )
            restartHub.attach()
            withTimeout(2_000L) { startupStarted.await() }
            assertWritesBlocked(restartHub, "startup_recovering_clearing")
            // 放行恢复失败 -> 仍然阻断
            releaseStartup.complete(SyncServerResponse.Transient)
            assertWritesBlocked(restartHub, "startup_recover_failed")

            // 状态 5：创建未激活完（写 ACTIVE 失败留下 CREATING 状态）
            var saveCount = 0
            val failingActiveStore = object : vela.core.sync.SyncStateStore {
                var saved: SyncState? = null
                override fun load(): SyncStateRead = saved?.let { SyncStateRead.Present(it.deepCopy()) } ?: SyncStateRead.None
                override fun save(state: SyncState): Boolean {
                    saveCount += 1
                    if (saveCount == 1) {
                        saved = state.deepCopy()
                        return true
                    }
                    return false
                }
                override fun delete(): Boolean {
                    saved = null
                    return true
                }
            }
            val failCreateService = SyncService(
                SyncService.Environment(
                    stateStore = failingActiveStore,
                    keyStore = TestMemoryKeyStore(),
                    makeTransport = { _, _ -> TestScriptedTransport() },
                    wallClock = { nowMs },
                ),
            )
            val failCreateHub = SyncHub(
                service = failCreateService,
                prefs = prefs,
                bridge = StageSyncBridge(stage, prefs),
                scope = scope,
                sdkInt = 34,
                capability = SyncCapability.FULL,
                configuredOrigin = origin,
                wallClock = { nowMs },
            )
            failCreateHub.attach()
            assertNull(failCreateHub.startEnable())
            assertEquals(SyncPhase.CREATING, failingActiveStore.saved?.phase)
            assertWritesBlocked(failCreateHub, "creating_unactivated")

            // 状态 6：加入提交中 & 需要修复（快照换回失败后停在 NeedsRepair）
            val repairJournal = TestMemoryJournal(TestMemorySink()).apply {
                snapshot()
                failRestore = true
            }
            val repairService = SyncService(
                SyncService.Environment(
                    stateStore = TestMemoryStateStore(),
                    keyStore = TestMemoryKeyStore(),
                    makeTransport = { _, _ -> TestScriptedTransport() },
                    journal = repairJournal,
                    wallClock = { nowMs },
                ),
            )
            val repairHub = SyncHub(
                service = repairService,
                prefs = prefs,
                bridge = StageSyncBridge(stage, prefs),
                scope = scope,
                sdkInt = 34,
                capability = SyncCapability.FULL,
                configuredOrigin = origin,
                wallClock = { nowMs },
            )
            repairHub.attach()
            assertWritesBlocked(repairHub, "join_needs_repair")
        } finally {
            stage.close()
        }
    }

    @Test
    fun testBarrierPropagatesSettingsCommitFailureAcrossJoinDisableClearAndRetry() = runBlocking {
        val rawPrefs = context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val flakyPrefs = MutatingFlakyCommitPreferences(rawPrefs)
        val settings = Settings(flakyPrefs)
        val stage = makeStage(settings)
        try {
            val prefs = SyncPrefs(flakyPrefs)
            val bridge = StageSyncBridge(stage, prefs)

            val keyBytes = ByteArray(32) { (it + 61).toByte() }
            val derived = deriveSyncKeys(keyBytes)
            val codec = SyncCryptoCodec(derived)
            var docRep = SyncReplica(SyncDocument.empty(), SyncClock(dev = 55L))
            docRep = (SyncOps.apply(SyncLocalOp.Setting(Settings.KEY_PUBLIC_ONLY, false), docRep, nowMs) as SyncOpOutcome.Applied).replica
            docRep = (SyncOps.apply(SyncLocalOp.Setting(Settings.KEY_INFO_ALWAYS, true), docRep, nowMs + 10L) as SyncOpOutcome.Applied).replica
            val remoteEnv = codec.seal(docRep.doc.encoded(), 1L)!!

            // 1. 加入提交时设置 commit() 失败（且模拟内存 map 已被改写）：必须拒绝提交 ACTIVE、回滚并保留未加入状态
            val joinStore = TestMemoryStateStore()
            val joinKeyStore = TestMemoryKeyStore()
            val joinJournal = TestMemoryJournal(TestMemorySink())
            val joinService = SyncService(
                SyncService.Environment(
                    stateStore = joinStore,
                    keyStore = joinKeyStore,
                    makeTransport = { _, _ ->
                        TestScriptedTransport(
                            gets = mutableListOf(SyncServerResponse.Blob(1L, derived.pubB64, remoteEnv, epoch, nowMs)),
                            puts = mutableListOf(SyncServerResponse.Stored(2L, epoch, nowMs)),
                        )
                    },
                    journal = joinJournal,
                    wallClock = { nowMs },
                ),
            )
            val codeText = SyncCode.format(keyBytes, origin, ::sha256)!!
            val ticket = (joinService.prepareJoin(codeText, SyncLocalData()) as SyncJoinPrepared.Preview).ticket

            flakyPrefs.failCommit = true
            val joinRes = joinService.commitJoin(ticket, { SyncLocalData() }, bridge)
            assertTrue("设置 commit() 失败时加入提交不得成功，实际 $joinRes", joinRes is SyncJoinCommit.Failed)
            assertFalse("设置落盘失败时服务不得激活", joinService.isActive)
            assertNull("设置落盘失败时不得写入 ACTIVE 状态", joinStore.stored)
            assertNull("设置落盘失败回滚后应删除密钥条目", joinKeyStore.entry)

            // 2. 关闭同步时设置 commit() 失败：返回 LocalSaveFailed、保留状态与密钥；随后重试即使内存值已相等仍须重新 commit() 并成功
            val activeState = SyncState(
                phase = SyncPhase.ACTIVE,
                origin = origin,
                blobID = derived.blobID,
                clock = docRep.clock,
                doc = docRep.doc,
                epoch = epoch,
                lastSeenVer = 1L,
            )
            val disableStore = TestMemoryStateStore().apply { stored = activeState.deepCopy() }
            val disableKeyStore = TestMemoryKeyStore().apply { entry = SyncKeyEntry(keyBytes, 55L, derived.blobID) }
            val disableService = SyncService(
                SyncService.Environment(
                    stateStore = disableStore,
                    keyStore = disableKeyStore,
                    makeTransport = { _, _ -> TestScriptedTransport() },
                    wallClock = { nowMs },
                ),
            )
            disableService.activate(activeState.deepCopy(), derived)

            // 让 commit 失败（但 MutatingFlakyCommitPreferences 会改写底层内存 map 为 publicOnly=false, infoAlwaysOn=true）
            flakyPrefs.failCommit = true
            flakyPrefs.commitCalls = 0
            assertEquals(SyncFlowResult.LocalSaveFailed, disableService.disable(bridge))
            assertTrue("屏障设置失败后必须保留激活状态", disableService.isActive)
            assertNotNull("屏障设置失败后不得删除同步状态", disableStore.stored)
            assertNotNull("屏障设置失败后不得删除密钥条目", disableKeyStore.entry)
            assertTrue(flakyPrefs.commitCalls >= 1)

            // 重试关闭：尽管 prefs 内存值已等于目标值，仍必须重新调用 commit() 并落盘成功！
            val commitsBeforeRetry = flakyPrefs.commitCalls
            flakyPrefs.failCommit = false
            assertEquals(SyncFlowResult.Done, disableService.disable(bridge))
            assertTrue("重试关闭必须真正重新调用 commit() 而非按内存相等跳过", flakyPrefs.commitCalls > commitsBeforeRetry)
            assertFalse(disableService.isActive)
            assertNull(disableStore.stored)
            assertNull(disableKeyStore.entry)
            assertFalse(stage.publicOnly)
            assertTrue(stage.infoAlwaysOn)

            // 3. 清除云端时设置 commit() 失败：返回 LocalSaveFailed，不发 DELETE、不写 CLEARING、保持激活
            val clearStore = TestMemoryStateStore().apply { stored = activeState.deepCopy() }
            val clearKeyStore = TestMemoryKeyStore().apply { entry = SyncKeyEntry(keyBytes, 55L, derived.blobID) }
            var deleteCalled = 0
            val clearService = SyncService(
                SyncService.Environment(
                    stateStore = clearStore,
                    keyStore = clearKeyStore,
                    makeTransport = { _, _ ->
                        object : SyncTransport {
                            override suspend fun get(): SyncServerResponse = SyncServerResponse.Transient
                            override suspend fun put(request: SyncPutRequest): SyncServerResponse = SyncServerResponse.Transient
                            override suspend fun delete(base: Long, epoch: String?): SyncServerResponse {
                                deleteCalled += 1
                                return SyncServerResponse.Deleted
                            }
                        }
                    },
                    wallClock = { nowMs },
                ),
            )
            clearService.activate(activeState.deepCopy(), derived)
            flakyPrefs.failCommit = true
            assertEquals(SyncFlowResult.LocalSaveFailed, clearService.clear(bridge))
            assertEquals("屏障设置失败时不得发起远端 DELETE", 0, deleteCalled)
            assertEquals(SyncPhase.ACTIVE, clearStore.stored?.phase)
            assertTrue(clearService.isActive)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testMainActivityStopDisposeDuringAuthStillRevealsSyncCodeOnOkAndRejectsCancel() {
        shadowOf(context.packageManager)
            .getInternalMutablePackageInfo(context.packageName)
            .applicationInfo!!
            .metaData = Bundle().apply { putString(SyncConfig.MANIFEST_META_KEY, origin) }
        SyncPrefs(context).setExperimentSync(true)

        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            ShadowLooper.idleMainLooper()
            assertNotNull("MainActivity 启动路径应创建 SyncHub", activity.syncHub)

            // Robolectric JVM 没有硬件 AndroidKeyStore 提供程序，这里把内存密钥库的活跃 Hub 接到真实 LifecycleWiring 上，
            // 完整验证真实 MainActivity 的 onStop/onBackground/onStart/onResume 与 onSyncRevealResult 协作路径。
            val keyBytes = ByteArray(32) { (it + 71).toByte() }
            val derived = deriveSyncKeys(keyBytes)
            val expectedCode = SyncCode.format(keyBytes, origin, ::sha256)!!
            val memStateStore = TestMemoryStateStore().apply {
                stored = SyncState(
                    phase = SyncPhase.ACTIVE,
                    origin = origin,
                    blobID = derived.blobID,
                    clock = SyncClock(dev = 15L),
                    epoch = epoch,
                )
            }
            val memKeyStore = TestMemoryKeyStore().apply {
                entry = SyncKeyEntry(keyBytes, 15L, derived.blobID)
            }
            val memPrefs = SyncPrefs(activity.stage.settings.preferences).apply { experimentSync = true }
            val memService = SyncService(
                SyncService.Environment(
                    stateStore = memStateStore,
                    keyStore = memKeyStore,
                    makeTransport = { _, _ -> TestScriptedTransport(gets = mutableListOf(SyncServerResponse.Transient)) },
                    wallClock = { nowMs },
                ),
            )
            val hub = SyncHub(
                service = memService,
                prefs = memPrefs,
                bridge = StageSyncBridge(activity.stage, memPrefs),
                scope = scope,
                sdkInt = 34,
                capability = SyncCapability.FULL,
                configuredOrigin = origin,
                wallClock = { nowMs },
            )
            activity.wiring.attachSync(hub)
            hub.attach()
            assertEquals(SyncUiPhase.Active, hub.uiState.value.phase)
            assertNull(hub.uiState.value.revealedCode)

            val km = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            shadowOf(km).setIsDeviceSecure(true)

            // 1. 触发锁屏验证，验证期间真实宿主进入 onStop（触发后台与面板销毁），返回 RESULT_CANCELED 必须拒绝
            activity.requestSyncCodeReveal(intentFactory = { Intent("android.app.action.CONFIRM_DEVICE_CREDENTIAL") }) {
                hub.revealAfterAuth()
            }
            controller.pause().stop()
            assertEquals(vela.core.lifecycle.PhoneScenePhase.BACKGROUND, activity.wiring.phase)
            controller.restart().resume()
            assertEquals(vela.core.lifecycle.PhoneScenePhase.ACTIVE, activity.wiring.phase)
            activity.onSyncRevealResult(Activity.RESULT_CANCELED)
            assertNull("验证取消不得显示同步码", hub.uiState.value.revealedCode)

            // 2. 再次触发锁屏验证，验证期间经历真实 onStop -> 回到前台 -> RESULT_OK 必须写入 Hub 的 revealedCode
            activity.requestSyncCodeReveal(intentFactory = { Intent("android.app.action.CONFIRM_DEVICE_CREDENTIAL") }) {
                hub.revealAfterAuth()
            }
            controller.pause().stop()
            assertEquals(vela.core.lifecycle.PhoneScenePhase.BACKGROUND, activity.wiring.phase)
            controller.restart().resume()
            assertEquals(vela.core.lifecycle.PhoneScenePhase.ACTIVE, activity.wiring.phase)
            activity.onSyncRevealResult(Activity.RESULT_OK)
            assertEquals("宿主经 onStop 重建后 RESULT_OK 仍应展示完整同步码", expectedCode, hub.uiState.value.revealedCode)

            // 3. 之后再切到后台应自动清空已展示的明文同步码
            controller.pause().stop()
            assertEquals(vela.core.lifecycle.PhoneScenePhase.BACKGROUND, activity.wiring.phase)
            assertNull("退到后台必须清空已展示的同步码", hub.uiState.value.revealedCode)
        }
    }

    /**
     * 模拟 Android SharedPreferencesImpl 在 commit() 写盘失败时仍可能先更新内存映射的真实行为，
     * 用来验证重试落盘绝不因内存值相等而跳过 commit()。
     */
    private class MutatingFlakyCommitPreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        @Volatile
        var failCommit: Boolean = false
        @Volatile
        var commitCalls: Int = 0

        override fun edit(): SharedPreferences.Editor {
            val inner = delegate.edit()
            return object : SharedPreferences.Editor by inner {
                override fun commit(): Boolean {
                    commitCalls += 1
                    inner.commit()
                    return !failCommit
                }
            }
        }
    }
}
