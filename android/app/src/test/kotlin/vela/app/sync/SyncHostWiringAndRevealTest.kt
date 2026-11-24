package vela.app.sync

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowKeyguardManager
import org.robolectric.shadows.ShadowLooper
import vela.app.MainActivity
import vela.app.infra.Settings
import vela.app.stage.LifecycleSession
import vela.app.stage.LifecycleWiring
import vela.app.stage.StageIntent
import vela.app.stage.StageSurface
import vela.app.stage.StageUiState
import vela.app.stage.StageViewModel
import vela.app.stage.recordSettlement
import vela.app.ui.StageScreen
import vela.app.ui.TasteSheet
import vela.core.base.lane.LaneEndReason
import vela.core.base.lane.RoomTarget
import vela.core.base.model.FeedSource
import vela.core.base.model.Gender
import vela.core.base.model.Room
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult
import vela.core.curation.Curation
import vela.core.curation.FavoriteExchange
import vela.core.ledger.DwellTracker
import vela.core.lifecycle.LifecycleEpoch
import vela.core.lifecycle.PhoneScenePhase
import vela.core.sync.SyncClock
import vela.core.sync.SyncCode
import vela.core.sync.SyncDocument
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
import vela.core.sync.SyncTransport
import vela.core.sync.deriveSyncKeys
import vela.core.sync.sha256

/**
 * 返工 A-SYNC-2 验收测试：
 * - 查看同步码前必须完成一次设备身份验证，未提供验证器时默认拒绝（fail closed）
 * - MainActivity 启动路径在配置服务端地址后创建并挂上 SyncHub，未配置时不创建
 * - LifecycleWiring 正确转发前后台到 SyncHub
 * - StageViewModel 收藏、口味、设置、停留写入入口全部经过提交锁与 SyncHub.perform
 * - StageScreen 透传 SyncHub 与验证回调至 TasteSheet
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class SyncHostWiringAndRevealTest {
    @get:Rule
    val compose = createComposeRule()

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
        dir = Files.createTempDirectory("vela-sync-host-")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).applicationInfo?.metaData = null
        scope.cancel()
        dir.toFile().deleteRecursively()
    }

    private fun activeHub(stage: StageViewModel): Triple<SyncHub, SyncService, String> {
        val keyBytes = ByteArray(32) { (it + 19).toByte() }
        val derived = deriveSyncKeys(keyBytes)
        val expectedCode = SyncCode.format(keyBytes, origin, ::sha256)!!
        val stateStore = TestMemoryStateStore().apply {
            stored = SyncState(
                phase = SyncPhase.ACTIVE,
                origin = origin,
                blobID = derived.blobID,
                clock = SyncClock(dev = 9L),
                epoch = epoch,
            )
        }
        val keyStore = TestMemoryKeyStore().apply {
            entry = SyncKeyEntry(keyBytes, 9L, derived.blobID)
        }
        val transport = TestScriptedTransport(
            gets = mutableListOf(SyncServerResponse.NotFound(epoch, nowMs)),
            puts = mutableListOf(SyncServerResponse.Stored(1L, epoch, nowMs)),
        )
        val prefs = SyncPrefs(stage.settings.preferences).apply { experimentSync = true }
        val bridge = StageSyncBridge(stage, prefs)
        val service = SyncService(
            SyncService.Environment(
                stateStore = stateStore,
                keyStore = keyStore,
                makeTransport = { _, _ -> transport },
                wallClock = { nowMs },
            ),
        )
        val hub = SyncHub(
            service = service,
            prefs = prefs,
            bridge = bridge,
            scope = scope,
            sdkInt = 34,
            capability = SyncCapability.FULL,
            configuredOrigin = origin,
            wallClock = { nowMs },
        )
        stage.syncHub = hub
        hub.attach()
        return Triple(hub, service, expectedCode)
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
    fun testRevealCodeFailsClosedWithoutAuthenticatorAndRequiresGrantedAuth() {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val shadowKm: ShadowKeyguardManager = shadowOf(km)
        shadowKm.setIsDeviceSecure(true)

        val stage = makeStage()
        try {
            val (hub, _, expectedCode) = activeHub(stage)
            assertEquals(SyncUiPhase.Active, hub.uiState.value.phase)

            var authMode = "none" // none -> 未传回调; deny -> 拒绝; grant -> 通过
            var authRequested = 0
            val handlerState = androidx.compose.runtime.mutableStateOf<((( () -> Unit) -> Unit)?)>(null)

            compose.setContent {
                Box(Modifier.requiredSize(360.dp, 800.dp)) {
                    TasteSheet(
                        state = StageUiState(),
                        dispatch = {},
                        onDismiss = {},
                        syncHub = hub,
                        onRequestSyncReveal = handlerState.value,
                    )
                }
            }

            // 1. 未提供认证处理器（null）：即使设备已设锁屏密码，也必须拒绝显示同步码（fail closed）
            compose.onNodeWithTag("sync-reveal-code").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals("未提供认证处理器不得显示完整同步码", 0, compose.onAllNodesWithText(expectedCode).fetchSemanticsNodes().size)
            assertNotNull("应提示无法验证身份", hub.uiState.value.notice)

            // 2. 提供认证处理器但用户取消/失败（不调用 onGranted）：不得显示同步码
            authMode = "deny"
            handlerState.value = { onGranted ->
                authRequested += 1
                if (authMode == "grant") onGranted()
            }
            compose.waitForIdle()
            compose.onNodeWithTag("sync-reveal-code").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(1, authRequested)
            assertEquals("验证未通过不得显示同步码", 0, compose.onAllNodesWithText(expectedCode).fetchSemanticsNodes().size)

            // 3. 验证通过（调用 onGranted）后才显示完整同步码
            authMode = "grant"
            compose.onNodeWithTag("sync-reveal-code").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(2, authRequested)
            compose.onNodeWithTag("sync-copy-code").performScrollTo().assertIsDisplayed()
            assertEquals(1, compose.onAllNodesWithText(expectedCode).fetchSemanticsNodes().size)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testMainActivityStartupCreatesAttachesSyncAndForwardsLifecycle() {
        // 1. 未配置服务端地址时，不创建 SyncHub
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            assertNull("未配置地址时不应创建 SyncHub", activity.syncHub)
            assertNull(activity.stage.syncHub)
        }

        // 2. 配置服务端地址且打开实验开关时，MainActivity 创建并挂接 SyncHub、转发前后台与身份验证结果
        shadowOf(context.packageManager)
            .getInternalMutablePackageInfo(context.packageName)
            .applicationInfo!!
            .metaData = Bundle().apply { putString(SyncConfig.MANIFEST_META_KEY, origin) }
        val prefs = SyncPrefs(context)
        prefs.setExperimentSync(true)

        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            ShadowLooper.idleMainLooper()
            val hub = activity.syncHub
            assertNotNull("配置服务端地址后 MainActivity 必须创建 SyncHub", hub)
            assertSame(hub, activity.stage.syncHub)
            assertTrue(hub!!.visible)
            assertTrue(hub.uiState.value.experimentEnabled)
            assertSame(hub, activity.wiring.syncHub)

            // 验证前后台转发：先切到 BACKGROUND 再回 ACTIVE，观察 LifecycleWiring 真实转发
            var fgCount = 0
            var bgCount = 0
            activity.wiring.onSyncForeground = { fgCount += 1 }
            activity.wiring.onSyncBackground = { bgCount += 1 }
            controller.pause().stop()
            assertEquals(1, bgCount)
            controller.start().resume()
            assertEquals(1, fgCount)

            // 验证 MainActivity 身份验证结果处理：非 RESULT_OK 拒绝、RESULT_OK 放行一次
            val km = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            shadowOf(km).setIsDeviceSecure(true)
            var granted = 0
            activity.requestSyncCodeReveal(intentFactory = { Intent("android.app.action.CONFIRM_DEVICE_CREDENTIAL") }) {
                granted += 1
            }
            activity.onSyncRevealResult(Activity.RESULT_CANCELED)
            assertEquals("验证取消不得放行", 0, granted)

            activity.requestSyncCodeReveal(intentFactory = { Intent("android.app.action.CONFIRM_DEVICE_CREDENTIAL") }) {
                granted += 1
            }
            activity.onSyncRevealResult(Activity.RESULT_OK)
            assertEquals("验证通过应放行一次", 1, granted)
            // 重复回调不得二次放行
            activity.onSyncRevealResult(Activity.RESULT_OK)
            assertEquals(1, granted)
        }
    }

    @Test
    fun testStageWritePathsRouteThroughSyncPerformAndCommitLock() = runBlocking {
        val stage = makeStage()
        try {
            stage.ageConfirmed = true
            val room = Room(
                username = "zoe",
                displayName = "Zoe",
                gender = Gender.female,
                tags = listOf("jazz"),
                viewers = 100,
                show = vela.core.base.model.ShowState.publicShow,
            )
            stage.feed = stage.feed.copy().also {
                it.replace(vela.core.base.site.CatalogPage(rooms = listOf(room), total = 1, nextOffset = 1))
            }
            val (hub, service, _) = activeHub(stage)

            // 1. 收藏与屏蔽写入走 SyncHub.perform
            stage.dispatch(StageIntent.AddFavorite("alice"))
            assertTrue(SyncProjection(service.state!!.doc).curation.isFavorite("alice"))

            stage.dispatch(StageIntent.RemoveFavorite("alice"))
            assertFalse(SyncProjection(service.state!!.doc).curation.isFavorite("alice"))

            stage.dispatch(StageIntent.RestoreFavorite("alice"))
            assertTrue(SyncProjection(service.state!!.doc).curation.isFavorite("alice"))

            stage.dispatch(StageIntent.BlockCurrent(RoomTarget.primary("zoe", stage.laneFace.generation)))
            assertTrue(SyncProjection(service.state!!.doc).curation.isBlocked("zoe"))

            stage.dispatch(StageIntent.Unblock("zoe"))
            assertFalse(SyncProjection(service.state!!.doc).curation.isBlocked("zoe"))

            stage.dispatch(StageIntent.ImportFavorites(FavoriteExchange.encode(listOf("bob"))))
            assertTrue(SyncProjection(service.state!!.doc).curation.isFavorite("bob"))

            // 2. 口味不想看写入走 SyncHub.perform
            stage.dispatch(StageIntent.VetoTaste("t:jazz"))
            assertEquals(true, SyncProjection(service.state!!.doc).veto["t:jazz"])

            stage.dispatch(StageIntent.RestoreTaste("t:jazz"))
            assertEquals(false, SyncProjection(service.state!!.doc).veto["t:jazz"])

            stage.dispatch(StageIntent.VetoTaste("t:rock"))
            assertEquals(true, SyncProjection(service.state!!.doc).veto["t:rock"])
            stage.dispatch(StageIntent.ResetTaste)
            assertEquals(false, SyncProjection(service.state!!.doc).veto["t:rock"])

            // 3. 两个同步设置走 SyncHub.perform，且远端投影不回声
            stage.dispatch(StageIntent.SetPublicOnly(false))
            assertEquals(false, SyncProjection(service.state!!.doc).settings[Settings.KEY_PUBLIC_ONLY])

            stage.dispatch(StageIntent.ToggleInfo)
            assertEquals(true, SyncProjection(service.state!!.doc).settings[Settings.KEY_INFO_ALWAYS])

            val genBeforeProjection = service.state!!.gen
            var remoteRep = SyncReplica(service.state!!.doc, SyncClock(l = nowMs + 100L, dev = 77L))
            remoteRep = (SyncOps.apply(SyncLocalOp.Setting(Settings.KEY_PUBLIC_ONLY, true), remoteRep, nowMs + 200L) as SyncOpOutcome.Applied).replica
            StageSyncBridge(stage, SyncPrefs(stage.settings.preferences)).applyProjection(remoteRep.doc)
            assertTrue(stage.state.value.publicOnly)
            assertEquals("远端设置投影不得回声触发本地写入", genBeforeProjection, service.state!!.gen)

            // 先证明解锁状态下停留结算会修改口味
            val tasteBeforeUnlockedSettle = stage.taste
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
            assertTrue("解锁状态下停留结算应更新口味", stage.taste != tasteBeforeUnlockedSettle)

            // 4. 提交锁 / 待修复期间：全部收藏、口味、设置及停留口味写入必须被拒，观看账本仍记录
            val repairJournal = TestMemoryJournal(TestMemorySink()).apply { snapshot() }
            val lockedService = SyncService(
                SyncService.Environment(
                    stateStore = TestMemoryStateStore(),
                    keyStore = TestMemoryKeyStore(),
                    makeTransport = { _, _ -> TestScriptedTransport() },
                    journal = repairJournal,
                ),
            )
            assertTrue(lockedService.joinRepairPending)
            assertFalse(lockedService.localWriteAllowed)
            val lockedHub = SyncHub(
                service = lockedService,
                prefs = SyncPrefs(stage.settings.preferences),
                bridge = StageSyncBridge(stage, SyncPrefs(stage.settings.preferences)),
                scope = scope,
                sdkInt = 34,
                capability = SyncCapability.FULL,
                configuredOrigin = origin,
            )
            stage.syncHub = lockedHub

            val favBefore = stage.curation.favorites
            val pubBefore = stage.publicOnly
            val infoBefore = stage.infoAlwaysOn
            val tasteBefore = stage.taste
            stage.dispatch(StageIntent.AddFavorite("blocked_during_join"))
            stage.dispatch(StageIntent.VetoTaste("t:blocked"))
            stage.dispatch(StageIntent.SetPublicOnly(!pubBefore))
            stage.dispatch(StageIntent.ToggleInfo)
            stage.dispatch(StageIntent.SetLearningPaused(true))
            assertEquals(favBefore, stage.curation.favorites)
            assertEquals(pubBefore, stage.publicOnly)
            assertEquals(infoBefore, stage.infoAlwaysOn)
            assertEquals(tasteBefore, stage.taste)
            assertEquals(SyncHub.COMMITTING_MESSAGE, stage.state.value.toast)

            // 停留结算：待修复期间不改 taste，但观看账本照常累计时长
            val ledgerSecondsBefore = stage.watchLedger.entries["zoe"]?.totalSeconds ?: 0.0
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
            assertEquals("待修复期间停留结算不得修改口味", tasteBefore, stage.taste)
            val ledgerSecondsAfter = stage.watchLedger.entries["zoe"]?.totalSeconds ?: 0.0
            assertTrue("观看账本不在快照内，应照常累计时长", ledgerSecondsAfter > ledgerSecondsBefore)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testStageScreenThreadsSyncHubAndRevealHandlerToTasteSheet() {
        val stage = makeStage()
        try {
            stage.ageConfirmed = true
            stage.surface = StageSurface.Taste
            stage.publish()
            val (hub, _, _) = activeHub(stage)
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            shadowOf(km).setIsDeviceSecure(true)
            var revealCalls = 0
            compose.setContent {
                Box(Modifier.requiredSize(360.dp, 800.dp)) {
                    StageScreen(
                        stage = stage,
                        syncHub = hub,
                        onRequestSyncReveal = { revealCalls += 1 },
                    )
                }
            }
            compose.onNodeWithTag("sync-experiment-toggle").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("sync-reveal-code").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(1, revealCalls)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testLifecycleWiringForwardsBackgroundAndForegroundToSyncHub() {
        val stage = makeStage()
        try {
            val keyBytes = ByteArray(32) { (it + 23).toByte() }
            val derived = deriveSyncKeys(keyBytes)
            val transport = StatefulMemoryTransport(derived.pubB64, epoch, nowMs)
            val stateStore = TestMemoryStateStore().apply {
                stored = SyncState(
                    phase = SyncPhase.ACTIVE,
                    origin = origin,
                    blobID = derived.blobID,
                    clock = SyncClock(dev = 11L),
                    epoch = epoch,
                )
            }
            val keyStore = TestMemoryKeyStore().apply {
                entry = SyncKeyEntry(keyBytes, 11L, derived.blobID)
            }
            val prefs = SyncPrefs(stage.settings.preferences).apply { experimentSync = true }
            val service = SyncService(
                SyncService.Environment(
                    stateStore = stateStore,
                    keyStore = keyStore,
                    makeTransport = { _, _ -> transport },
                    wallClock = { nowMs },
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
            hub.attach()
            assertEquals(1L, service.state?.lastSeenVer)

            val wiring = LifecycleWiring(stage, NoopSession)
            wiring.attachSync(hub)
            wiring.moveTo(PhoneScenePhase.ACTIVE)

            // 本地修改产生未推送改动 -> 进后台应触发补推
            hub.perform(SyncLocalOp.Favorite("dora"))
            assertTrue(hub.uiState.value.hasPending)
            wiring.moveTo(PhoneScenePhase.BACKGROUND)
            assertFalse("进后台应通过 LifecycleWiring 触发未推送改动补推", hub.uiState.value.hasPending)
            assertEquals(2L, service.state?.lastSeenVer)
            assertEquals(2, transport.putCount)

            // 回到前台（即便后台前无活跃播放路、经过 INACTIVE）也应触发 FOREGROUND 同步拉取
            val getsBefore = transport.getCount
            wiring.moveTo(PhoneScenePhase.INACTIVE)
            wiring.moveTo(PhoneScenePhase.ACTIVE)
            assertEquals("回前台应触发一次同步拉取", getsBefore + 1, transport.getCount)
        } finally {
            stage.close()
        }
    }

    private class StatefulMemoryTransport(
        private val pubB64: String,
        private val epoch: String,
        private val nowMs: Long,
    ) : SyncTransport {
        var ver: Long = 0L
        var envelope: String? = null
        var getCount: Int = 0
        var putCount: Int = 0

        override suspend fun get(): SyncServerResponse {
            getCount += 1
            val current = envelope ?: return SyncServerResponse.NotFound(epoch, nowMs)
            return SyncServerResponse.Blob(ver, pubB64, current, epoch, nowMs)
        }

        override suspend fun put(request: SyncPutRequest): SyncServerResponse {
            putCount += 1
            if (request.base != ver) {
                val current = this.envelope
                return if (current == null) {
                    SyncServerResponse.NotFound(epoch, nowMs)
                } else {
                    SyncServerResponse.Conflict(ver, pubB64, current, epoch, nowMs)
                }
            }
            ver = request.next
            this.envelope = request.envelope
            return SyncServerResponse.Stored(ver, epoch, nowMs)
        }

        override suspend fun delete(base: Long, epoch: String?): SyncServerResponse {
            ver = 0L
            envelope = null
            return SyncServerResponse.Deleted
        }
    }

    private object NoopSession : LifecycleSession {
        override fun closeGate() = Unit
        override fun onEpoch(epoch: LifecycleEpoch) = Unit
        override fun suspendLanes() = Unit
        override fun deactivateAudio() = Unit
        override fun prepareAudio() = Unit
        override fun stopListener() = Unit
        override fun ensureLane() = Unit
        override fun restoreUi() = Unit
    }
}
