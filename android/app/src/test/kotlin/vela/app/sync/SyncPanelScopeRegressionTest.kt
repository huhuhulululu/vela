package vela.app.sync

import android.app.KeyguardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import java.nio.file.Path
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import vela.app.infra.Settings
import vela.app.stage.StageUiState
import vela.app.stage.StageViewModel
import vela.app.ui.TasteSheet
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult
import vela.core.curation.Curation
import vela.core.sync.SyncClock
import vela.core.sync.SyncCode
import vela.core.sync.SyncCycleResult
import vela.core.sync.SyncDocument
import vela.core.sync.SyncFlowResult
import vela.core.sync.SyncLocalData
import vela.core.sync.SyncLocalOp
import vela.core.sync.SyncOpOutcome
import vela.core.sync.SyncOps
import vela.core.sync.SyncPhase
import vela.core.sync.SyncPutRequest
import vela.core.sync.SyncReplica
import vela.core.sync.SyncServerResponse
import vela.core.sync.SyncState
import vela.core.sync.SyncTransport
import vela.core.sync.deriveSyncKeys
import vela.core.sync.sha256

/**
 * 返工 A-SYNC-3 回归测试：
 * - SyncPanel 不得把开启、加入（检查/确认/404创建）、清除云端、查看同步码挂在 Composable 作用域；
 *   离开页面、旋转屏幕或界面重建不得中途打断长事务，也不得丢掉刚生成的同步码或锁屏验证结果；
 * - 用户主动取消加入必须走既定取消语义；
 * - SyncService.create() 在 activate() 后若首周期被取消仍返回 Created(..., Transient)，
 *   SyncService.clear() 在 detach() 后若网络被取消仍返回 RemotePending 并推进界面状态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class SyncPanelScopeRegressionTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var context: Context
    private lateinit var dir: Path
    private lateinit var hubScope: CoroutineScope
    private val origin = "https://sync.example"
    private val epoch = "0123456789abcdef0123456789abcdef"
    private val nowMs = 1_800_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(AndroidSyncKeyStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        dir = Files.createTempDirectory("vela-sync-scope-")
        hubScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        hubScope.cancel()
        dir.toFile().deleteRecursively()
    }

    private fun makeStage(): StageViewModel = StageViewModel(
        settings = Settings(context),
        http = object : HTTPClient {
            override suspend fun send(request: HTTPRequest): HTTPResult = HTTPResult(200, ByteArray(0))
        },
        directory = dir,
        dispatcher = Dispatchers.Unconfined,
        context = context,
    )

    @Test
    fun testEnableSurvivesPanelDisposeAndShowsSavedCodeWhenReopened() = runBlocking {
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
                scope = hubScope,
                sdkInt = 34,
                capability = SyncCapability.FULL,
                configuredOrigin = origin,
                wallClock = { nowMs },
            )
            hub.attach()
            assertEquals(SyncUiPhase.Off, hub.uiState.value.phase)

            val showPanel = mutableStateOf(true)
            compose.setContent {
                if (showPanel.value) {
                    Box(Modifier.requiredSize(360.dp, 800.dp)) {
                        TasteSheet(state = StageUiState(), dispatch = {}, onDismiss = {}, syncHub = hub)
                    }
                }
            }

            // 点「在这台设备上开启同步」->「同意并开启」
            compose.onNodeWithTag("sync-create-entry").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("sync-enable-confirm").performScrollTo().performClick()

            withTimeout(2_000L) { getStarted.await() }
            assertTrue("create 应已激活服务并正等待首周期网络", service.isActive)
            assertTrue(hub.uiState.value.busy)

            // 首周期挂起期间关闭面板（销毁整个 Compose 树）
            showPanel.value = false
            compose.waitForIdle()

            // 放行网络响应
            releaseGet.complete(SyncServerResponse.NotFound(epoch, nowMs))
            compose.waitForIdle()

            // 即使面板被销毁，开启事务不得被取消，必须进入 Active 且保留同步码
            assertFalse(hub.uiState.value.busy)
            assertEquals(SyncUiPhase.Active, hub.uiState.value.phase)
            val savedCode = hub.uiState.value.savedCode
            assertNotNull("离开面板后不得丢失刚生成的同步码", savedCode)
            assertEquals(1L, service.state?.lastSeenVer)

            // 重新打开面板应立刻显示「保存你的同步码」卡片
            showPanel.value = true
            compose.waitForIdle()
            assertEquals(1, compose.onAllNodesWithText(savedCode!!).fetchSemanticsNodes().size)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testCreateReturnsCreatedEvenIfFirstCycleCancelled() = runBlocking {
        val cancellingTransport = object : SyncTransport {
            override suspend fun get(): SyncServerResponse {
                throw CancellationException("first cycle cancelled")
            }

            override suspend fun put(request: SyncPutRequest): SyncServerResponse =
                SyncServerResponse.Transient

            override suspend fun delete(base: Long, epoch: String?): SyncServerResponse =
                SyncServerResponse.Transient
        }
        val service = SyncService(
            SyncService.Environment(
                stateStore = TestMemoryStateStore(),
                keyStore = TestMemoryKeyStore(),
                makeTransport = { _, _ -> cancellingTransport },
                wallClock = { nowMs },
            ),
        )
        val result = service.create(origin, SyncLocalData())
        assertTrue("activate() 后首周期取消仍须返回 Created", result is SyncCreateResult.Created)
        val created = result as SyncCreateResult.Created
        assertEquals(SyncCycleResult.Transient, created.firstCycle)
        assertTrue(service.isActive)
    }

    @Test
    fun testCreateFromJoinAndClearCloudSurvivePanelDispose() = runBlocking {
        val stage = makeStage()
        try {
            val createGetStarted = CompletableDeferred<Unit>()
            val releaseCreateGet = CompletableDeferred<SyncServerResponse>()
            val deleteStarted = CompletableDeferred<Unit>()
            val releaseDelete = CompletableDeferred<SyncServerResponse>()
            var getCalls = 0
            val transport = object : SyncTransport {
                override suspend fun get(): SyncServerResponse {
                    getCalls += 1
                    return if (getCalls == 1) {
                        // prepareJoin -> 404 NotFound
                        SyncServerResponse.NotFound(epoch, nowMs)
                    } else {
                        // createFromJoin 的首周期 GET 挂起
                        createGetStarted.complete(Unit)
                        releaseCreateGet.await()
                    }
                }

                override suspend fun put(request: SyncPutRequest): SyncServerResponse =
                    SyncServerResponse.Stored(request.next, epoch, nowMs)

                override suspend fun delete(base: Long, epoch: String?): SyncServerResponse {
                    deleteStarted.complete(Unit)
                    return releaseDelete.await()
                }
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
                scope = hubScope,
                sdkInt = 34,
                capability = SyncCapability.FULL,
                configuredOrigin = origin,
                wallClock = { nowMs },
            )
            hub.attach()

            val showPanel = mutableStateOf(true)
            compose.setContent {
                if (showPanel.value) {
                    Box(Modifier.requiredSize(360.dp, 800.dp)) {
                        TasteSheet(state = StageUiState(), dispatch = {}, onDismiss = {}, syncHub = hub)
                    }
                }
            }

            // 1. 404 后点击「仍用此码创建」，网络途中关闭面板
            val codeText = SyncCode.format(ByteArray(32) { (it + 5).toByte() }, origin, ::sha256)!!
            hub.prepareJoin(codeText)
            assertTrue(hub.uiState.value.joinStage is SyncUiJoinStage.NotFound)
            compose.waitForIdle()
            compose.onNodeWithTag("sync-join-create-anyway").performScrollTo().performClick()

            withTimeout(2_000L) { createGetStarted.await() }
            showPanel.value = false
            compose.waitForIdle()

            releaseCreateGet.complete(SyncServerResponse.NotFound(epoch, nowMs))
            compose.waitForIdle()

            assertFalse(hub.uiState.value.busy)
            assertEquals(SyncUiPhase.Active, hub.uiState.value.phase)
            assertEquals(SyncUiJoinStage.Idle, hub.uiState.value.joinStage)
            assertTrue(service.isActive)

            // 2. 重开面板点击「清除云端…」->「确定清除」，DELETE 途中关闭面板
            showPanel.value = true
            compose.waitForIdle()
            compose.onNodeWithTag("sync-clear-prompt").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("sync-clear-confirm").performScrollTo().performClick()

            withTimeout(2_000L) { deleteStarted.await() }
            showPanel.value = false
            compose.waitForIdle()

            releaseDelete.complete(SyncServerResponse.Deleted)
            compose.waitForIdle()

            assertFalse(hub.uiState.value.busy)
            assertEquals(SyncUiPhase.Off, hub.uiState.value.phase)
            assertFalse(service.isActive)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testClearReturnsRemotePendingAndUpdatesUiWhenNetworkCancelled() = runBlocking {
        val stage = makeStage()
        try {
            val keyBytes = ByteArray(32) { (it + 31).toByte() }
            val derived = deriveSyncKeys(keyBytes)
            val cancellingDeleteTransport = object : SyncTransport {
                override suspend fun get(): SyncServerResponse = SyncServerResponse.Transient
                override suspend fun put(request: SyncPutRequest): SyncServerResponse = SyncServerResponse.Transient
                override suspend fun delete(base: Long, epoch: String?): SyncServerResponse {
                    throw CancellationException("delete cancelled")
                }
            }
            val stateStore = TestMemoryStateStore().apply {
                stored = SyncState(
                    phase = SyncPhase.ACTIVE,
                    origin = origin,
                    blobID = derived.blobID,
                    clock = SyncClock(dev = 3L),
                    epoch = epoch,
                    lastSeenVer = 1L,
                )
            }
            val keyStore = TestMemoryKeyStore().apply {
                entry = SyncKeyEntry(keyBytes, 3L, derived.blobID)
            }
            val prefs = SyncPrefs(stage.settings.preferences).apply { experimentSync = true }
            val service = SyncService(
                SyncService.Environment(
                    stateStore = stateStore,
                    keyStore = keyStore,
                    makeTransport = { _, _ -> cancellingDeleteTransport },
                    wallClock = { nowMs },
                ),
            )
            val hub = SyncHub(
                service = service,
                prefs = prefs,
                bridge = StageSyncBridge(stage, prefs),
                scope = hubScope,
                sdkInt = 34,
                capability = SyncCapability.FULL,
                configuredOrigin = origin,
                wallClock = { nowMs },
            )
            hub.attach()
            assertEquals(SyncUiPhase.Active, hub.uiState.value.phase)

            hub.clearCloud()
            assertFalse(service.isActive)
            assertTrue(
                "clear 在网络取消后不得让界面停在 Active，应切到 Attention",
                hub.uiState.value.phase is SyncUiPhase.Attention,
            )
        } finally {
            stage.close()
        }
    }

    @Test
    fun testJoinPrepareAndConfirmSurvivePanelDisposeAndExplicitCancelRollsBack() = runBlocking {
        val stage = makeStage()
        try {
            val keyBytes = ByteArray(32) { (it + 41).toByte() }
            val derived = deriveSyncKeys(keyBytes)
            val codeText = SyncCode.format(keyBytes, origin, ::sha256)!!
            val codec = SyncCryptoCodec(derived)
            var remoteRep = SyncReplica(SyncDocument.empty(), SyncClock(dev = 99L))
            remoteRep = (SyncOps.apply(SyncLocalOp.Favorite("remote_fav"), remoteRep, nowMs) as SyncOpOutcome.Applied).replica
            val remoteEnv = codec.seal(remoteRep.doc.encoded(), 1L)!!

            // A. 检查阶段与确认提交阶段在关闭/重建面板时不得被打断
            val getStarted = CompletableDeferred<Unit>()
            val releaseGet = CompletableDeferred<SyncServerResponse>()
            val putStarted = CompletableDeferred<Unit>()
            val releasePut = CompletableDeferred<SyncServerResponse>()
            val transportA = object : SyncTransport {
                override suspend fun get(): SyncServerResponse {
                    getStarted.complete(Unit)
                    return releaseGet.await()
                }

                override suspend fun put(request: SyncPutRequest): SyncServerResponse {
                    putStarted.complete(Unit)
                    return releasePut.await()
                }

                override suspend fun delete(base: Long, epoch: String?): SyncServerResponse =
                    SyncServerResponse.Deleted
            }
            val serviceA = SyncService(
                SyncService.Environment(
                    stateStore = TestMemoryStateStore(),
                    keyStore = TestMemoryKeyStore(),
                    makeTransport = { _, _ -> transportA },
                    wallClock = { nowMs },
                ),
            )
            val prefs = SyncPrefs(stage.settings.preferences).apply { experimentSync = true }
            val hubA = SyncHub(
                service = serviceA,
                prefs = prefs,
                bridge = StageSyncBridge(stage, prefs),
                scope = hubScope,
                sdkInt = 34,
                capability = SyncCapability.FULL,
                configuredOrigin = origin,
                wallClock = { nowMs },
            )
            hubA.attach()

            val showPanel = mutableStateOf(true)
            compose.setContent {
                if (showPanel.value) {
                    Box(Modifier.requiredSize(360.dp, 800.dp)) {
                        TasteSheet(state = StageUiState(), dispatch = {}, onDismiss = {}, syncHub = hubA)
                    }
                }
            }

            // 进入加入页 -> 输入码 -> 点「下一步」
            compose.onNodeWithTag("sync-join-entry").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("sync-join-input").performScrollTo().performTextInput(codeText)
            compose.onNodeWithTag("sync-join-next").performScrollTo().performClick()

            withTimeout(2_000L) { getStarted.await() }
            // 检查途中关闭面板，不得取消 prepareJoin
            showPanel.value = false
            compose.waitForIdle()
            releaseGet.complete(SyncServerResponse.Blob(1L, derived.pubB64, remoteEnv, epoch, nowMs))
            compose.waitForIdle()
            assertTrue("离开面板不得作废进行中的加入检查", hubA.uiState.value.joinStage is SyncUiJoinStage.Preview)

            // 重开面板应直接展示确认加入页 -> 点「确认加入」
            showPanel.value = true
            compose.waitForIdle()
            compose.onNodeWithTag("sync-join-confirm").performScrollTo().performClick()

            withTimeout(2_000L) { putStarted.await() }
            assertTrue(hubA.uiState.value.committing)
            assertFalse(serviceA.localWriteAllowed)

            // 提交途中关闭面板，不得打断 commitJoin
            showPanel.value = false
            compose.waitForIdle()
            releasePut.complete(SyncServerResponse.Stored(2L, epoch, nowMs))
            compose.waitForIdle()

            assertEquals(SyncUiPhase.Active, hubA.uiState.value.phase)
            assertFalse(hubA.uiState.value.committing)
            assertFalse(hubA.uiState.value.busy)
            assertTrue(serviceA.isActive)
            assertTrue(serviceA.localWriteAllowed)
            assertTrue(stage.state.value.curation.isFavorite("remote_fav"))

            // B. 用户主动点击「取消」时，必须走取消语义（中止提交、释放提交锁、不激活）
            val stageB = makeStage()
            try {
                val putStartedB = CompletableDeferred<Unit>()
                val neverCompletePut = CompletableDeferred<SyncServerResponse>()
                val transportB = object : SyncTransport {
                    override suspend fun get(): SyncServerResponse =
                        SyncServerResponse.Blob(1L, derived.pubB64, remoteEnv, epoch, nowMs)

                    override suspend fun put(request: SyncPutRequest): SyncServerResponse {
                        putStartedB.complete(Unit)
                        return neverCompletePut.await()
                    }

                    override suspend fun delete(base: Long, epoch: String?): SyncServerResponse =
                        SyncServerResponse.Deleted
                }
                val serviceB = SyncService(
                    SyncService.Environment(
                        stateStore = TestMemoryStateStore(),
                        keyStore = TestMemoryKeyStore(),
                        makeTransport = { _, _ -> transportB },
                        wallClock = { nowMs },
                    ),
                )
                val prefsB = SyncPrefs(stageB.settings.preferences).apply { experimentSync = true }
                val hubB = SyncHub(
                    service = serviceB,
                    prefs = prefsB,
                    bridge = StageSyncBridge(stageB, prefsB),
                    scope = hubScope,
                    sdkInt = 34,
                    capability = SyncCapability.FULL,
                    configuredOrigin = origin,
                    wallClock = { nowMs },
                )
                hubB.attach()
                hubB.prepareJoin(codeText)
                val ticket = (hubB.uiState.value.joinStage as SyncUiJoinStage.Preview).ticket

                val confirmJob = hubB.startConfirmJoin()
                withTimeout(2_000L) { putStartedB.await() }
                assertFalse(serviceB.localWriteAllowed)

                // 用户主动取消
                hubB.cancelJoin()
                assertNotNull(confirmJob)
                withTimeout(2_000L) { confirmJob!!.join() }

                assertTrue(ticket.isCancelled)
                assertFalse(serviceB.isActive)
                assertTrue("取消后必须释放提交锁", serviceB.localWriteAllowed)
                assertEquals(SyncUiPhase.Off, hubB.uiState.value.phase)
                assertEquals(SyncUiJoinStage.Idle, hubB.uiState.value.joinStage)
                assertFalse(hubB.uiState.value.busy)
                assertFalse(hubB.uiState.value.committing)
            } finally {
                stageB.close()
            }
        } finally {
            stage.close()
        }
    }

    @Test
    fun testRevealCodeSurvivesPanelDisposeDuringDeviceAuth() {
        val stage = makeStage()
        try {
            val keyBytes = ByteArray(32) { (it + 53).toByte() }
            val derived = deriveSyncKeys(keyBytes)
            val expectedCode = SyncCode.format(keyBytes, origin, ::sha256)!!
            val stateStore = TestMemoryStateStore().apply {
                stored = SyncState(
                    phase = SyncPhase.ACTIVE,
                    origin = origin,
                    blobID = derived.blobID,
                    clock = SyncClock(dev = 7L),
                    epoch = epoch,
                )
            }
            val keyStore = TestMemoryKeyStore().apply {
                entry = SyncKeyEntry(keyBytes, 7L, derived.blobID)
            }
            val prefs = SyncPrefs(stage.settings.preferences).apply { experimentSync = true }
            val service = SyncService(
                SyncService.Environment(
                    stateStore = stateStore,
                    keyStore = keyStore,
                    makeTransport = { _, _ -> TestScriptedTransport(gets = mutableListOf(SyncServerResponse.Transient)) },
                    wallClock = { nowMs },
                ),
            )
            val hub = SyncHub(
                service = service,
                prefs = prefs,
                bridge = StageSyncBridge(stage, prefs),
                scope = hubScope,
                sdkInt = 34,
                capability = SyncCapability.FULL,
                configuredOrigin = origin,
                wallClock = { nowMs },
            )
            hub.attach()

            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            shadowOf(km).setIsDeviceSecure(true)

            val showPanel = mutableStateOf(true)
            var pendingAuthGrant: (() -> Unit)? = null
            compose.setContent {
                if (showPanel.value) {
                    Box(Modifier.requiredSize(360.dp, 800.dp)) {
                        TasteSheet(
                            state = StageUiState(),
                            dispatch = {},
                            onDismiss = {},
                            syncHub = hub,
                            onRequestSyncReveal = { onGranted -> pendingAuthGrant = onGranted },
                        )
                    }
                }
            }

            // 点「查看同步码」-> 触发系统锁屏验证
            compose.onNodeWithTag("sync-reveal-code").performScrollTo().performClick()
            compose.waitForIdle()
            assertNotNull(pendingAuthGrant)

            // 锁屏验证期间模拟界面销毁重建（如旋转屏幕 / 系统验证页回来重组）
            showPanel.value = false
            compose.waitForIdle()
            hub.onBackground()
            showPanel.value = true
            compose.waitForIdle()

            // 系统返回 RESULT_OK -> 放行
            pendingAuthGrant!!.invoke()
            compose.waitForIdle()

            // 重建后的界面必须显示完整同步码
            compose.onNodeWithTag("sync-copy-code").performScrollTo().assertIsDisplayed()
            assertEquals(1, compose.onAllNodesWithText(expectedCode).fetchSemanticsNodes().size)

            // 之后真正切后台时清空已展示的明文同步码
            hub.onBackground()
            compose.waitForIdle()
            assertNull(hub.uiState.value.revealedCode)
            assertEquals(0, compose.onAllNodesWithText(expectedCode).fetchSemanticsNodes().size)
        } finally {
            stage.close()
        }
    }
}
