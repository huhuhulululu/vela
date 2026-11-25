package vela.app.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.infra.Settings
import vela.app.stage.StageIntent
import vela.app.stage.StageViewModel
import vela.app.stage.recordSettlement
import vela.core.base.lane.LaneEndReason
import vela.core.base.model.Gender
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult
import vela.core.base.site.CatalogPage
import vela.core.curation.Curation
import vela.core.curation.CurationStore
import vela.core.ledger.DwellTracker
import vela.core.sync.SyncClock
import vela.core.sync.SyncCode
import vela.core.sync.SyncDocument
import vela.core.sync.SyncLocalOp
import vela.core.sync.SyncOpOutcome
import vela.core.sync.SyncOps
import vela.core.sync.SyncPhase
import vela.core.sync.SyncProjection
import vela.core.sync.SyncReplica
import vela.core.sync.SyncServerResponse
import vela.core.sync.SyncState
import vela.core.sync.SyncStateRead
import vela.core.sync.SyncStateStore
import vela.core.sync.deriveSyncKeys
import vela.core.sync.sha256
import vela.core.taste.Taste
import vela.core.taste.TasteStore

/**
 * 终轮审查修复（A-SYNC-5）回归测试：
 * - 要点 1：恢复屏障与关闭屏障落盘后，在释放锁前重读并刷新内存里的舞台状态（收藏、口味、设置）；
 * - 要点 2：创建写 CREATING + Keystore 后写 ACTIVE 失败（或回删失败）时切入 Attention 暴露修复入口，
 *   关闭未迁移 CREATING 不得用空文档覆盖本机数据，重启后自动收敛。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class SyncAuditRound3Test {
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
        dir = Files.createTempDirectory("vela-sync-a5-")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
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

    private fun docWith(
        favorites: List<String> = emptyList(),
        blocked: List<String> = emptyList(),
        vetoes: List<String> = emptyList(),
        settings: Map<String, Boolean> = emptyMap(),
    ): SyncDocument {
        var rep = SyncReplica(SyncDocument.empty(), SyncClock(dev = 42L))
        var pt = nowMs - 10_000L
        for (f in favorites) {
            pt += 10L
            rep = (SyncOps.apply(SyncLocalOp.Favorite(f), rep, pt) as SyncOpOutcome.Applied).replica
        }
        for (b in blocked) {
            pt += 10L
            rep = (SyncOps.apply(SyncLocalOp.Block(b), rep, pt) as SyncOpOutcome.Applied).replica
        }
        for (v in vetoes) {
            pt += 10L
            rep = (SyncOps.apply(SyncLocalOp.Veto(v), rep, pt) as SyncOpOutcome.Applied).replica
        }
        for ((k, v) in settings) {
            pt += 10L
            rep = (SyncOps.apply(SyncLocalOp.Setting(k, v), rep, pt) as SyncOpOutcome.Applied).replica
        }
        return rep.doc
    }

    @Test
    fun testStartupRecoveryBarrierRefreshesInMemoryStageAndSurvivesNextEditAndSwipe() {
        // 1. 磁盘先有旧版收藏/口味（模拟 2 秒 DeferredSaver 未刷盘时进程重启）
        assertTrue(CurationStore(CurationStore.fileIn(dir)).save(Curation(favorites = listOf("old_fav"))))
        assertTrue(TasteStore(TasteStore.defaultURL(dir)).save(Taste(), Instant.ofEpochMilli(nowMs)))

        // 2. StageViewModel 启动先把旧磁盘读进内存
        val stage = makeStage()
        try {
            val room = sampleRoom()
            stage.feed = stage.feed.copy().also {
                it.replace(CatalogPage(rooms = listOf(room), total = 1, nextOffset = 1))
            }
            assertEquals(listOf("old_fav"), stage.curation.favorites)
            assertTrue(stage.taste.vetoedKeys.isEmpty())
            assertTrue(stage.publicOnly)
            assertFalse(stage.infoAlwaysOn)

            // 3. 磁盘同步状态停在 DISABLING，文档里含最新收藏、屏蔽、不想看与双设置
            val keyBytes = ByteArray(32) { (it + 3).toByte() }
            val derived = deriveSyncKeys(keyBytes)
            val syncedDoc = docWith(
                favorites = listOf("old_fav", "synced_fav"),
                blocked = listOf("synced_block"),
                vetoes = listOf("t:synced_veto"),
                settings = mapOf(
                    Settings.KEY_PUBLIC_ONLY to false,
                    Settings.KEY_INFO_ALWAYS to true,
                ),
            )
            val stateStore = TestMemoryStateStore().apply {
                stored = SyncState(
                    phase = SyncPhase.DISABLING,
                    origin = origin,
                    blobID = derived.blobID,
                    clock = SyncClock(dev = 42L),
                    doc = syncedDoc,
                    epoch = epoch,
                )
            }
            val keyStore = TestMemoryKeyStore().apply {
                entry = SyncKeyEntry(keyBytes, 42L, derived.blobID)
            }
            val prefs = SyncPrefs(stage.settings.preferences).apply { experimentSync = true }
            val service = SyncService(
                SyncService.Environment(
                    stateStore = stateStore,
                    keyStore = keyStore,
                    makeTransport = { _, _ -> TestScriptedTransport() },
                    wallClock = { nowMs },
                ),
            )
            var writeAllowedDuringSave = true
            val realBridge = StageSyncBridge(stage, prefs)
            val observingBridge = object : SyncLocalBridge by realBridge {
                override fun saveTaste(taste: Taste): Boolean {
                    writeAllowedDuringSave = service.localWriteAllowed
                    return realBridge.saveTaste(taste)
                }
            }
            val hub = SyncHub(
                service = service,
                prefs = prefs,
                bridge = observingBridge,
                scope = scope,
                sdkInt = 34,
                capability = SyncCapability.FULL,
                configuredOrigin = origin,
                wallClock = { nowMs },
            )
            stage.syncHub = hub
            hub.attach()

            assertEquals(SyncUiPhase.Off, hub.uiState.value.phase)
            assertFalse("恢复屏障落盘期间不得提前释放写锁", writeAllowedDuringSave)
            assertTrue("恢复完成后必须恢复允许本机写入", hub.localWriteAllowed)

            // 4. 屏障完成后内存舞台必须已同步刷新（不必等重启或再操作）
            assertTrue("内存收藏必须刷新出 synced_fav", stage.curation.isFavorite("synced_fav"))
            assertTrue("内存屏蔽必须刷新出 synced_block", stage.curation.isBlocked("synced_block"))
            assertTrue("内存口味必须刷新出 t:synced_veto", stage.taste.vetoedKeys.contains("t:synced_veto"))
            assertFalse("内存 publicOnly 必须刷新为 false", stage.publicOnly)
            assertTrue("内存 infoAlwaysOn 必须刷新为 true", stage.infoAlwaysOn)
            assertTrue(stage.state.value.curation.isFavorite("synced_fav"))
            assertFalse(stage.state.value.publicOnly)
            assertTrue(stage.state.value.infoAlwaysOn)

            // 5. 随后首次划房停留结算 + 新增收藏并刷盘：不得用旧内存覆盖同步屏障保存的数据
            stage.recordSettlement(
                settled = DwellTracker.Settled(
                    room = room,
                    dwell = 30.0,
                    ledgerSeconds = 30.0,
                    start = Instant.ofEpochMilli(nowMs - 30_000L),
                ),
                endReason = LaneEndReason.SWIPE,
                userInitiated = true,
            )
            stage.dispatch(StageIntent.AddFavorite("after_recover"))
            stage.tasteSaver.flush()
            stage.curationSaver.flush()

            val diskCuration = CurationStore(CurationStore.fileIn(dir)).load()
            val diskTaste = TasteStore(TasteStore.defaultURL(dir)).load(Instant.ofEpochMilli(nowMs))
            assertTrue("首次编辑后磁盘仍须保留 synced_fav", diskCuration.isFavorite("synced_fav"))
            assertTrue("首次编辑后磁盘仍须保留 synced_block", diskCuration.isBlocked("synced_block"))
            assertTrue("首次编辑后磁盘须保留新收藏", diskCuration.isFavorite("after_recover"))
            assertTrue("首次划房后磁盘仍须保留 t:synced_veto", diskTaste.vetoedKeys.contains("t:synced_veto"))
        } finally {
            stage.close()
        }
    }

    @Test
    fun testDisableBarrierFromAttentionRefreshesStageAndFeedsFreshDataIntoPrepareJoin() = runBlocking {
        // 场景 A：Attention(KeyLost) 下点击关闭同步 -> 屏障刷新内存，后续划房不覆盖
        assertTrue(CurationStore(CurationStore.fileIn(dir)).save(Curation(favorites = listOf("old_only"))))
        assertTrue(TasteStore(TasteStore.defaultURL(dir)).save(Taste(), Instant.ofEpochMilli(nowMs)))
        val stageA = makeStage()
        try {
            val room = sampleRoom()
            val keyBytes = ByteArray(32) { (it + 11).toByte() }
            val derived = deriveSyncKeys(keyBytes)
            val syncedDoc = docWith(
                favorites = listOf("old_only", "synced_keylost_fav"),
                vetoes = listOf("t:synced_keylost_veto"),
                settings = mapOf(Settings.KEY_INFO_ALWAYS to true),
            )
            val storeA = TestMemoryStateStore().apply {
                stored = SyncState(
                    phase = SyncPhase.ACTIVE,
                    origin = origin,
                    blobID = derived.blobID,
                    clock = SyncClock(dev = 9L),
                    doc = syncedDoc,
                    epoch = epoch,
                )
            }
            val prefsA = SyncPrefs(stageA.settings.preferences).apply { experimentSync = true }
            val serviceA = SyncService(
                SyncService.Environment(
                    stateStore = storeA,
                    keyStore = TestMemoryKeyStore(), // 无密钥 -> KeyLost -> Attention
                    makeTransport = { _, _ -> TestScriptedTransport() },
                    wallClock = { nowMs },
                ),
            )
            val hubA = SyncHub(
                service = serviceA,
                prefs = prefsA,
                bridge = StageSyncBridge(stageA, prefsA),
                scope = scope,
                sdkInt = 34,
                capability = SyncCapability.FULL,
                configuredOrigin = origin,
                wallClock = { nowMs },
            )
            stageA.syncHub = hubA
            hubA.attach()
            assertTrue(hubA.uiState.value.phase is SyncUiPhase.Attention)
            assertFalse("关闭前内存仍是旧数据", stageA.curation.isFavorite("synced_keylost_fav"))

            hubA.disable()
            assertEquals(SyncUiPhase.Off, hubA.uiState.value.phase)
            assertTrue("关闭屏障后内存必须立即刷新出 synced_keylost_fav", stageA.curation.isFavorite("synced_keylost_fav"))
            assertTrue("关闭屏障后内存必须立即刷新出 t:synced_keylost_veto", stageA.taste.vetoedKeys.contains("t:synced_keylost_veto"))
            assertTrue("关闭屏障后内存 infoAlwaysOn 必须刷新为 true", stageA.infoAlwaysOn)

            stageA.recordSettlement(
                settled = DwellTracker.Settled(room, 25.0, 25.0, Instant.ofEpochMilli(nowMs - 25_000L)),
                endReason = LaneEndReason.SWIPE,
                userInitiated = true,
            )
            stageA.tasteSaver.flush()
            val diskTaste = TasteStore(TasteStore.defaultURL(dir)).load(Instant.ofEpochMilli(nowMs))
            assertTrue(diskTaste.vetoedKeys.contains("t:synced_keylost_veto"))
        } finally {
            stageA.close()
        }

        // 场景 B：Attention 态直接走 startPrepareJoin -> disable 屏障必须先刷新内存再调用 localData() 参与合并
        val dirB = Files.createTempDirectory("vela-sync-a5-join-")
        try {
            context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
            assertTrue(CurationStore(CurationStore.fileIn(dirB)).save(Curation(favorites = listOf("old_only"))))
            assertTrue(TasteStore(TasteStore.defaultURL(dirB)).save(Taste(), Instant.ofEpochMilli(nowMs)))
            val stageB = StageViewModel(
                settings = Settings(context),
                http = object : HTTPClient {
                    override suspend fun send(request: HTTPRequest): HTTPResult = HTTPResult(200, ByteArray(0))
                },
                directory = dirB,
                dispatcher = Dispatchers.Unconfined,
                context = context,
            ).apply { ageConfirmed = true }
            try {
                val oldKey = ByteArray(32) { (it + 17).toByte() }
                val oldDerived = deriveSyncKeys(oldKey)
                val newKey = ByteArray(32) { (it + 29).toByte() }
                val newDerived = deriveSyncKeys(newKey)
                val newCode = SyncCode.format(newKey, origin, ::sha256)!!
                val remoteDoc = docWith(favorites = listOf("remote_room"))
                val remoteEnv = SyncCryptoCodec(newDerived).seal(remoteDoc.encoded(), 1L)!!

                val storeB = TestMemoryStateStore().apply {
                    stored = SyncState(
                        phase = SyncPhase.ACTIVE,
                        origin = origin,
                        blobID = oldDerived.blobID,
                        clock = SyncClock(dev = 5L),
                        doc = docWith(
                            favorites = listOf("old_only", "barrier_only_fav"),
                            vetoes = listOf("t:barrier_only_veto"),
                        ),
                        epoch = epoch,
                    )
                }
                val prefsB = SyncPrefs(stageB.settings.preferences).apply { experimentSync = true }
                val serviceB = SyncService(
                    SyncService.Environment(
                        stateStore = storeB,
                        keyStore = TestMemoryKeyStore(), // KeyLost
                        makeTransport = { _, _ ->
                            TestScriptedTransport(
                                gets = mutableListOf(
                                    SyncServerResponse.Blob(1L, newDerived.pubB64, remoteEnv, epoch, nowMs),
                                ),
                            )
                        },
                        wallClock = { nowMs },
                    ),
                )
                val hubB = SyncHub(
                    service = serviceB,
                    prefs = prefsB,
                    bridge = StageSyncBridge(stageB, prefsB),
                    scope = scope,
                    sdkInt = 34,
                    capability = SyncCapability.FULL,
                    configuredOrigin = origin,
                    wallClock = { nowMs },
                )
                stageB.syncHub = hubB
                hubB.attach()
                assertTrue(hubB.uiState.value.phase is SyncUiPhase.Attention)

                hubB.prepareJoin(newCode)
                val joinStage = hubB.uiState.value.joinStage
                assertTrue("应进入加入预览，实际 $joinStage", joinStage is SyncUiJoinStage.Preview)
                val ticket = (joinStage as SyncUiJoinStage.Preview).ticket
                val mergedProj = SyncProjection(ticket.replica.doc)
                assertTrue("加入合并必须带入关闭屏障保存的收藏", mergedProj.curation.isFavorite("barrier_only_fav"))
                assertEquals(true, mergedProj.veto["t:barrier_only_veto"])
                assertEquals("应上传 2 个本机收藏（含屏障数据）", 2, ticket.preview.n)
                assertEquals("应上传 1 个本机不想看（含屏障数据）", 1, ticket.preview.v)
            } finally {
                stageB.close()
            }
        } finally {
            dirB.toFile().deleteRecursively()
        }
    }

    @Test
    fun testCreateSaveActiveFailureEntersAttentionAllowsSafeDisableAndConvergesOnRestart() = runBlocking {
        // 预置本机收藏、屏蔽与不想看
        assertTrue(
            CurationStore(CurationStore.fileIn(dir)).save(
                Curation(favorites = listOf("keep_me"), blocked = listOf("keep_block")),
            ),
        )
        assertTrue(
            TasteStore(TasteStore.defaultURL(dir)).save(
                Taste().vetoing("t:keep_veto", Instant.ofEpochMilli(nowMs)),
                Instant.ofEpochMilli(nowMs),
            ),
        )
        val stage = makeStage()
        try {
            val prefs = SyncPrefs(stage.settings.preferences).apply { experimentSync = true }

            // 1. startEnable：第 1 次 save(CREATING) 成功 + Keystore 成功，第 2 次 save(ACTIVE) 失败
            val flakyStore1 = FlakySaveStateStore(failOnSaveIndex = 2)
            val keyStore1 = TestMemoryKeyStore()
            val service1 = SyncService(
                SyncService.Environment(
                    stateStore = flakyStore1,
                    keyStore = keyStore1,
                    makeTransport = { _, _ ->
                        TestScriptedTransport(
                            gets = mutableListOf(SyncServerResponse.NotFound(epoch, nowMs)),
                            puts = mutableListOf(SyncServerResponse.Stored(1L, epoch, nowMs)),
                        )
                    },
                    wallClock = { nowMs },
                ),
            )
            val hub1 = SyncHub(
                service = service1,
                prefs = prefs,
                bridge = StageSyncBridge(stage, prefs),
                scope = scope,
                sdkInt = 34,
                capability = SyncCapability.FULL,
                configuredOrigin = origin,
                wallClock = { nowMs },
            )
            stage.syncHub = hub1
            hub1.attach()
            assertEquals(SyncUiPhase.Off, hub1.uiState.value.phase)

            assertNull(hub1.startEnable())
            assertEquals(SyncPhase.CREATING, flakyStore1.stored?.phase)
            assertNotNull(keyStore1.entry)
            assertTrue(service1.hasPendingRecovery)
            assertFalse(hub1.localWriteAllowed)
            assertTrue(
                "写 ACTIVE 失败留盘 CREATING 时界面不得停在 Off，必须切为 Attention，实际 ${hub1.uiState.value.phase}",
                hub1.uiState.value.phase is SyncUiPhase.Attention,
            )

            // 1a. 在 Attention 下点击「关闭同步」：必须安全清理半成品且绝不拿空 CREATING 文档覆盖本机数据
            hub1.disable()
            assertEquals(SyncUiPhase.Off, hub1.uiState.value.phase)
            assertTrue("关闭清理后必须恢复本机写入", hub1.localWriteAllowed)
            assertEquals(SyncStateRead.None, flakyStore1.load())
            assertNull(keyStore1.entry)
            val diskAfterDisable = CurationStore(CurationStore.fileIn(dir)).load()
            val tasteAfterDisable = TasteStore(TasteStore.defaultURL(dir)).load(Instant.ofEpochMilli(nowMs))
            assertTrue("关闭未迁移 CREATING 不得清空本机收藏", diskAfterDisable.isFavorite("keep_me"))
            assertTrue("关闭未迁移 CREATING 不得清空本机屏蔽", diskAfterDisable.isBlocked("keep_block"))
            assertTrue("关闭未迁移 CREATING 不得清空本机不想看", tasteAfterDisable.vetoedKeys.contains("t:keep_veto"))
            assertTrue(stage.curation.isFavorite("keep_me"))

            // 2. 同故障不点关闭，直接模拟重启 -> 经 ResumeCreating 收敛为 Active 且保留本机数据
            val flakyStore2 = FlakySaveStateStore(failOnSaveIndex = 2)
            val keyStore2 = TestMemoryKeyStore()
            val service2 = SyncService(
                SyncService.Environment(
                    stateStore = flakyStore2,
                    keyStore = keyStore2,
                    makeTransport = { _, _ ->
                        TestScriptedTransport(
                            gets = mutableListOf(SyncServerResponse.NotFound(epoch, nowMs)),
                            puts = mutableListOf(SyncServerResponse.Stored(1L, epoch, nowMs)),
                        )
                    },
                    wallClock = { nowMs },
                ),
            )
            val hub2 = SyncHub(
                service = service2,
                prefs = prefs,
                bridge = StageSyncBridge(stage, prefs),
                scope = scope,
                sdkInt = 34,
                capability = SyncCapability.FULL,
                configuredOrigin = origin,
                wallClock = { nowMs },
            )
            hub2.attach()
            assertNull(hub2.startEnable())
            assertTrue(hub2.uiState.value.phase is SyncUiPhase.Attention)

            // 模拟重启（磁盘恢复可写）
            flakyStore2.failOnSaveIndex = null
            val restartedService = SyncService(
                SyncService.Environment(
                    stateStore = flakyStore2,
                    keyStore = keyStore2,
                    makeTransport = { _, _ ->
                        TestScriptedTransport(
                            gets = mutableListOf(SyncServerResponse.NotFound(epoch, nowMs)),
                            puts = mutableListOf(SyncServerResponse.Stored(1L, epoch, nowMs)),
                        )
                    },
                    wallClock = { nowMs },
                ),
            )
            val restartedHub = SyncHub(
                service = restartedService,
                prefs = prefs,
                bridge = StageSyncBridge(stage, prefs),
                scope = scope,
                sdkInt = 34,
                capability = SyncCapability.FULL,
                configuredOrigin = origin,
                wallClock = { nowMs },
            )
            restartedHub.attach()
            assertEquals("重启后应从 CREATING+Key 收敛到 Active", SyncUiPhase.Active, restartedHub.uiState.value.phase)
            assertTrue(restartedService.isActive)
            assertTrue(SyncProjection(restartedService.state!!.doc).curation.isFavorite("keep_me"))

            // 3. startCreateFromJoin（404 后仍用此码创建）写 ACTIVE 失败 -> 同样切为 Attention，且可安全关闭
            val flakyStore3 = FlakySaveStateStore(failOnSaveIndex = 2)
            val keyStore3 = TestMemoryKeyStore()
            val service3 = SyncService(
                SyncService.Environment(
                    stateStore = flakyStore3,
                    keyStore = keyStore3,
                    makeTransport = { _, _ ->
                        TestScriptedTransport(
                            gets = mutableListOf(SyncServerResponse.NotFound(epoch, nowMs)),
                        )
                    },
                    wallClock = { nowMs },
                ),
            )
            val hub3 = SyncHub(
                service = service3,
                prefs = prefs,
                bridge = StageSyncBridge(stage, prefs),
                scope = scope,
                sdkInt = 34,
                capability = SyncCapability.FULL,
                configuredOrigin = origin,
                wallClock = { nowMs },
            )
            hub3.attach()
            val unknownCode = SyncCode.format(ByteArray(32) { (it + 37).toByte() }, origin, ::sha256)!!
            hub3.prepareJoin(unknownCode)
            assertTrue(hub3.uiState.value.joinStage is SyncUiJoinStage.NotFound)

            assertNull(hub3.startCreateFromJoin())
            assertTrue(
                "404 创建写 ACTIVE 失败必须切为 Attention，实际 ${hub3.uiState.value.phase}",
                hub3.uiState.value.phase is SyncUiPhase.Attention,
            )
            hub3.disable()
            assertEquals(SyncUiPhase.Off, hub3.uiState.value.phase)
            assertTrue(CurationStore(CurationStore.fileIn(dir)).load().isFavorite("keep_me"))

            // 4. Keystore 写失败且回删 CREATING 状态也失败 -> 同样切为 Attention，重启后经 DiscardCreating 收敛为 Off
            val stuckDeleteStore = FlakySaveStateStore(failOnSaveIndex = null, failDelete = true)
            val failKeyStore = TestMemoryKeyStore().apply { failWrite = true }
            val service4 = SyncService(
                SyncService.Environment(
                    stateStore = stuckDeleteStore,
                    keyStore = failKeyStore,
                    makeTransport = { _, _ -> TestScriptedTransport() },
                    wallClock = { nowMs },
                ),
            )
            val hub4 = SyncHub(
                service = service4,
                prefs = prefs,
                bridge = StageSyncBridge(stage, prefs),
                scope = scope,
                sdkInt = 34,
                capability = SyncCapability.FULL,
                configuredOrigin = origin,
                wallClock = { nowMs },
            )
            hub4.attach()
            assertNull(hub4.startEnable())
            assertTrue(hub4.uiState.value.phase is SyncUiPhase.Attention)
            assertEquals(SyncPhase.CREATING, stuckDeleteStore.stored?.phase)
            assertNull(failKeyStore.entry)

            // 重启后磁盘可删 -> DiscardCreating -> Off
            stuckDeleteStore.failDelete = false
            val service4Restart = SyncService(
                SyncService.Environment(
                    stateStore = stuckDeleteStore,
                    keyStore = failKeyStore,
                    makeTransport = { _, _ -> TestScriptedTransport() },
                    wallClock = { nowMs },
                ),
            )
            val hub4Restart = SyncHub(
                service = service4Restart,
                prefs = prefs,
                bridge = StageSyncBridge(stage, prefs),
                scope = scope,
                sdkInt = 34,
                capability = SyncCapability.FULL,
                configuredOrigin = origin,
                wallClock = { nowMs },
            )
            hub4Restart.attach()
            assertEquals(SyncUiPhase.Off, hub4Restart.uiState.value.phase)
            assertEquals(SyncStateRead.None, stuckDeleteStore.load())
        } finally {
            stage.close()
        }
    }

    @Test
    fun testJoinConflictRetryOversizeReportsDataFullNotRandomFailure() = runBlocking {
        val keyBytes = ByteArray(32) { (it + 31).toByte() }
        val derived = deriveSyncKeys(keyBytes)
        val code = SyncCode.format(keyBytes, origin, ::sha256)!!
        val local = vela.core.sync.SyncLocalData(
            curation = Curation(favorites = listOf("local_room_alpha", "local_room_beta")),
        )
        val remoteV1 = docWith(favorites = listOf("remote_room_one"))
        val remoteV2 = docWith(favorites = listOf("remote_room_one", "remote_room_two"))
        val mergedV1Bytes = vela.core.sync.SyncMigration.stampJoin(local, remoteV1, SyncClock(dev = 7L), nowMs)
            .replica.doc.encoded().size
        val mergedV2Bytes = vela.core.sync.SyncMigration.stampJoin(local, remoteV2, SyncClock(dev = 7L), nowMs)
            .replica.doc.encoded().size
        val remoteV2Bytes = remoteV2.encoded().size
        val sizeLimit = maxOf(mergedV1Bytes, remoteV2Bytes) + 12
        assertTrue(
            "测试前提：初次合并($mergedV1Bytes)与远端v2($remoteV2Bytes)须小于上限($sizeLimit)，二次合并($mergedV2Bytes)须超限",
            sizeLimit < mergedV2Bytes,
        )

        val codec = SyncCryptoCodec(derived, sizeLimit)
        val envV1 = codec.seal(remoteV1.encoded(), 1L)!!
        val envV2 = codec.seal(remoteV2.encoded(), 2L)!!
        var putCalls = 0
        val transport = TestScriptedTransport(
            gets = mutableListOf(
                SyncServerResponse.Blob(1L, derived.pubB64, envV1, epoch, nowMs),
            ),
            puts = mutableListOf(
                SyncServerResponse.Conflict(2L, derived.pubB64, envV2, epoch, nowMs),
                SyncServerResponse.Stored(3L, epoch, nowMs),
            ),
        ).apply { onPut = { putCalls += 1 } }
        val service = SyncService(
            SyncService.Environment(
                stateStore = TestMemoryStateStore(),
                keyStore = TestMemoryKeyStore(),
                makeTransport = { _, _ -> transport },
                wallClock = { nowMs },
                sizeLimit = sizeLimit,
            ),
        )
        val prepared = service.prepareJoin(code, local) as SyncJoinPrepared.Preview
        val stage = makeStage()
        try {
            val prefs = SyncPrefs(stage.settings.preferences)
            val commit = service.commitJoin(prepared.ticket, { local }, StageSyncBridge(stage, prefs))
            assertTrue("409 合并超限必须返回 Failed，实际 $commit", commit is SyncJoinCommit.Failed)
            val message = (commit as SyncJoinCommit.Failed).message
            assertTrue("合并超限必须提示同步数据已满，实际：$message", message.contains("同步数据已满"))
            assertFalse("不得误报成系统随机源不可用，实际：$message", message.contains("随机源"))
            assertEquals("超限复检必须在第二次 PUT 之前拦截", 1, putCalls)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testJoinConflictRetryClockSkewRejectedBeforeSecondPut() = runBlocking {
        val keyBytes = ByteArray(32) { (it + 37).toByte() }
        val derived = deriveSyncKeys(keyBytes)
        val code = SyncCode.format(keyBytes, origin, ::sha256)!!
        val local = vela.core.sync.SyncLocalData(
            curation = Curation(favorites = listOf("local_room_skew")),
        )
        val remoteV1 = docWith(favorites = listOf("remote_skew_one"))
        val remoteV2 = docWith(favorites = listOf("remote_skew_one", "remote_skew_two"))
        val codec = SyncCryptoCodec(derived)
        val envV1 = codec.seal(remoteV1.encoded(), 1L)!!
        val envV2 = codec.seal(remoteV2.encoded(), 2L)!!
        val skewedServerNow = nowMs + vela.core.sync.SyncLimits.clockSkewMillis + 60_000L
        var putCalls = 0
        val transport = TestScriptedTransport(
            gets = mutableListOf(
                SyncServerResponse.Blob(1L, derived.pubB64, envV1, epoch, nowMs),
            ),
            puts = mutableListOf(
                SyncServerResponse.Conflict(2L, derived.pubB64, envV2, epoch, skewedServerNow),
                SyncServerResponse.Stored(3L, epoch, skewedServerNow),
            ),
        ).apply { onPut = { putCalls += 1 } }
        val service = SyncService(
            SyncService.Environment(
                stateStore = TestMemoryStateStore(),
                keyStore = TestMemoryKeyStore(),
                makeTransport = { _, _ -> transport },
                wallClock = { nowMs },
            ),
        )
        val prepared = service.prepareJoin(code, local) as SyncJoinPrepared.Preview
        val stage = makeStage()
        try {
            val prefs = SyncPrefs(stage.settings.preferences)
            val commit = service.commitJoin(prepared.ticket, { local }, StageSyncBridge(stage, prefs))
            assertTrue("409 时钟偏移超限必须返回 Failed，实际 $commit", commit is SyncJoinCommit.Failed)
            val message = (commit as SyncJoinCommit.Failed).message
            assertTrue("必须提示本机时间不准，实际：$message", message.contains("时间不准"))
            assertEquals("时钟偏移复检必须在第二次 PUT 之前拦截", 1, putCalls)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testSyncSettingRevertsMemoryAndPrefsAndShowsToastWhenSpaceFull() {
        val stage = makeStage()
        try {
            val prefs = SyncPrefs(stage.settings.preferences).apply { experimentSync = true }
            val keyBytes = ByteArray(32) { (it + 41).toByte() }
            val derived = deriveSyncKeys(keyBytes)
            // infoAlwaysOn 显式为 true，publicOnly 保持未设置（默认 true）
            val activeDoc = docWith(
                favorites = listOf("room_full_a", "room_full_b"),
                settings = mapOf(Settings.KEY_INFO_ALWAYS to true),
            )
            val tightLimit = activeDoc.encoded().size - 1
            val stateStore = TestMemoryStateStore().apply {
                stored = SyncState(
                    phase = SyncPhase.ACTIVE,
                    origin = origin,
                    blobID = derived.blobID,
                    clock = SyncClock(dev = 55L),
                    doc = activeDoc,
                    epoch = epoch,
                )
            }
            val keyStore = TestMemoryKeyStore().apply {
                entry = SyncKeyEntry(keyBytes, 55L, derived.blobID)
            }
            val service = SyncService(
                SyncService.Environment(
                    stateStore = stateStore,
                    keyStore = keyStore,
                    makeTransport = { _, _ -> TestScriptedTransport() },
                    wallClock = { nowMs },
                    sizeLimit = tightLimit,
                ),
            )
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

            assertEquals(SyncUiPhase.Active, hub.uiState.value.phase)
            assertTrue(stage.publicOnly)
            assertFalse("初始 publicOnly 应为未设置状态", prefs.hasExplicitValue(Settings.KEY_PUBLIC_ONLY))
            assertTrue(stage.infoAlwaysOn)
            assertEquals(true, prefs.explicitBool(Settings.KEY_INFO_ALWAYS))

            // 1. 尝试修改未设置的 publicOnly -> 空间已满，必须回退内存与偏好（不得污染为显式键）并提示已满
            stage.dispatch(StageIntent.SetPublicOnly(false))
            assertTrue("空间满时内存 publicOnly 不得停留在新值 false", stage.publicOnly)
            assertTrue("空间满时 UI 状态 publicOnly 必须保持 true", stage.state.value.publicOnly)
            assertTrue("空间满时 SharedPreferences publicOnly 必须保持 true", stage.settings.publicOnly)
            assertFalse("回退未设置的 publicOnly 不得写入显式键", prefs.hasExplicitValue(Settings.KEY_PUBLIC_ONLY))
            assertEquals(SyncHub.FULL_MESSAGE, stage.state.value.toast)

            // 2. 尝试切换已显式同步的 infoAlwaysOn -> 空间已满，必须回退到同步文档里的 true 并提示已满
            stage.dispatch(StageIntent.ToggleInfo)
            assertTrue("空间满时内存 infoAlwaysOn 必须回退到 true", stage.infoAlwaysOn)
            assertTrue("空间满时 UI 状态 infoAlwaysOn 必须保持 true", stage.state.value.infoAlwaysOn)
            assertEquals(true, prefs.explicitBool(Settings.KEY_INFO_ALWAYS))
            assertEquals(SyncHub.FULL_MESSAGE, stage.state.value.toast)
        } finally {
            stage.close()
        }
    }

    private class FlakySaveStateStore(
        var failOnSaveIndex: Int?,
        var failDelete: Boolean = false,
    ) : SyncStateStore {
        var stored: SyncState? = null
        private var saveCount = 0

        override fun load(): SyncStateRead = stored?.let { SyncStateRead.Present(it.deepCopy()) } ?: SyncStateRead.None

        override fun save(state: SyncState): Boolean {
            saveCount += 1
            if (failOnSaveIndex == saveCount) return false
            stored = state.deepCopy()
            return true
        }

        override fun delete(): Boolean {
            if (failDelete) return false
            stored = null
            return true
        }
    }
}
