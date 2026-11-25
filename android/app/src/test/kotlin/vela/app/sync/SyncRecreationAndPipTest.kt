package vela.app.sync

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
import vela.app.pip.PiPCoordinator
import vela.app.pip.PiPEvent
import vela.app.pip.PictureInPictureControlling
import vela.app.stage.LifecycleSession
import vela.app.stage.LifecycleWiring
import vela.app.stage.StageIntent
import vela.app.stage.StageViewModel
import vela.core.base.model.FeedSource
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult
import vela.core.base.site.CatalogPage
import vela.core.lifecycle.LifecycleEpoch
import vela.core.lifecycle.PhoneScenePhase
import vela.core.selection.LaneSelection
import vela.core.sync.SyncClock
import vela.core.sync.SyncPhase
import vela.core.sync.SyncProjection
import vela.core.sync.SyncPutRequest
import vela.core.sync.SyncScheduler
import vela.core.sync.SyncServerResponse
import vela.core.sync.SyncState
import vela.core.sync.SyncTransport
import vela.core.sync.deriveSyncKeys
import vela.core.sync.sha256

/**
 * 终轮审查修复（A-SYNC-5）回归测试：
 * - 要点 6：进入画中画时不得无条件触发同步后台网络请求；画中画与后台期间遵循 sync.md 规则。
 * - 要点 3：同步长事务作用域与状态归属于跨 Activity 重建保留的 owner，配置变化重建不取消在途事务、不丢结果与凭据验证状态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class SyncRecreationAndPipTest {
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
        dir = Files.createTempDirectory("vela-sync-pip-recreate-")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).applicationInfo?.metaData = null
        scope.cancel()
        dir.toFile().deleteRecursively()
    }

    private fun makeStage(directory: Path = dir): StageViewModel = StageViewModel(
        settings = Settings(context),
        http = object : HTTPClient {
            override suspend fun send(request: HTTPRequest): HTTPResult = HTTPResult(200, ByteArray(0))
        },
        directory = directory,
        dispatcher = Dispatchers.Unconfined,
        context = context,
    ).apply {
        ageConfirmed = true
        val rooms = listOf("alice", "bob").map { Room(username = it, viewers = 50, show = ShowState.publicShow) }
        updateFeed(installing = FeedSource.popular, intent = LaneSelection.Intent.Reload) { feed ->
            feed.replace(CatalogPage(rooms = rooms, total = rooms.size, nextOffset = rooms.size))
        }
        notePlaying("alice")
    }

    private class NoopSession : LifecycleSession {
        override fun closeGate() = Unit
        override fun onEpoch(epoch: LifecycleEpoch) = Unit
        override fun suspendLanes() = Unit
        override fun deactivateAudio() = Unit
        override fun prepareAudio() = Unit
        override fun stopListener() = Unit
        override fun ensureLane() = Unit
        override fun restoreUi() = Unit
    }

    private class StubPiP(override var canAutoEnter: Boolean = true) : PictureInPictureControlling {
        override var autoEnter: Boolean = canAutoEnter
        override fun stop() = Unit
    }

    private class CountingTransport(
        private val pubB64: String,
        private val epoch: String,
        private val nowMs: () -> Long,
        var rateLimitedSeconds: Int? = null,
    ) : SyncTransport {
        var getCalls = 0
        var putCalls = 0
        var deleteCalls = 0
        private var ver = 0L
        private var lastEnvelope: String? = null

        override suspend fun get(): SyncServerResponse {
            getCalls += 1
            rateLimitedSeconds?.let { return SyncServerResponse.RateLimited(it) }
            val env = lastEnvelope ?: return SyncServerResponse.NotFound(epoch, nowMs())
            return SyncServerResponse.Blob(ver, pubB64, env, epoch, nowMs())
        }

        override suspend fun put(request: SyncPutRequest): SyncServerResponse {
            putCalls += 1
            ver = request.next
            lastEnvelope = request.envelope
            return SyncServerResponse.Stored(ver, epoch, nowMs())
        }

        override suspend fun delete(base: Long, epoch: String?): SyncServerResponse {
            deleteCalls += 1
            ver = 0L
            lastEnvelope = null
            return SyncServerResponse.Deleted
        }

        fun resetCounts() {
            getCalls = 0
            putCalls = 0
            deleteCalls = 0
        }
    }

    private fun setupActiveHub(
        stage: StageViewModel,
        transportFactory: (pubB64: String) -> CountingTransport,
        clockMs: () -> Long = { nowMs },
    ): Triple<SyncHub, SyncService, CountingTransport> {
        val keyBytes = ByteArray(32) { (it + 53).toByte() }
        val derived = deriveSyncKeys(keyBytes)
        val transport = transportFactory(derived.pubB64)
        val stateStore = TestMemoryStateStore().apply {
            stored = SyncState(
                phase = SyncPhase.ACTIVE,
                origin = origin,
                blobID = derived.blobID,
                clock = SyncClock(dev = 12L),
                epoch = epoch,
            )
        }
        val keyStore = TestMemoryKeyStore().apply {
            entry = SyncKeyEntry(keyBytes, 12L, derived.blobID)
        }
        val prefs = SyncPrefs(stage.settings.preferences).apply { experimentSync = true }
        val service = SyncService(
            SyncService.Environment(
                stateStore = stateStore,
                keyStore = keyStore,
                makeTransport = { _, _ -> transport },
                wallClock = clockMs,
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
            wallClock = clockMs,
        )
        stage.syncHub = hub
        hub.attach()
        return Triple(hub, service, transport)
    }

    @Test
    fun testEnteringPipBackgroundMakesZeroSyncRequestsAndResumesOnForeground() {
        var clock = nowMs
        val stage = makeStage()
        try {
            val (hub, service, transport) = setupActiveHub(stage, { pub -> CountingTransport(pub, epoch, { clock }) }, { clock })
            assertEquals(SyncUiPhase.Active, hub.uiState.value.phase)
            transport.resetCounts()

            val wiring = LifecycleWiring(stage, NoopSession())
            val pip = StubPiP(canAutoEnter = true)
            val coordinator = PiPCoordinator(stage, wiring, audio = { null }).apply {
                this.pip = pip
                playerAdvancing = { true }
            }
            wiring.attachPictureInPicture(coordinator)
            wiring.attachSync(hub)
            wiring.moveTo(PhoneScenePhase.ACTIVE)
            transport.resetCounts()

            // 1. 制造未推送的本地改动（gen > pushedGen），然后经 auto-enter PiP 进后台
            clock += 20_000L
            stage.dispatch(StageIntent.AddFavorite("pip_pending_fav"))
            assertTrue("应有待推送改动", service.state!!.gen > service.state!!.pushedGen)
            assertTrue("本地编辑应排起 5 秒去抖任务", hub.hasScheduledEdit)

            wiring.moveTo(PhoneScenePhase.BACKGROUND)
            assertEquals("自动画中画路径必须只放行焦点路", vela.core.base.lane.PlaybackLaneRole.PRIMARY, stage.lifecycleEpoch.allowedLane)
            assertFalse("进入画中画必须取消排队的 5 秒去抖任务", hub.hasScheduledEdit)
            assertEquals("进入画中画时不得发起同步 GET 请求", 0, transport.getCalls)
            assertEquals("进入画中画时不得发起同步 PUT 请求", 0, transport.putCalls)

            // 画中画期间即便收到离散触发（面板/去抖编辑），也不得发同步请求
            clock += 70_000L
            hub.trigger(SyncScheduler.Trigger.LOCAL_EDIT)
            hub.trigger(SyncScheduler.Trigger.PANEL)
            assertEquals("画中画期间不得发起同步 GET", 0, transport.getCalls)
            assertEquals("画中画期间不得发起同步 PUT", 0, transport.putCalls)

            // 回到前台（甚至在 DidStop 到达之前）：恢复同步并把待推送改动推上云端
            clock += 15_000L
            wiring.moveTo(PhoneScenePhase.ACTIVE)
            assertEquals("回到前台后应补跑同步周期", 1, transport.getCalls)
            assertEquals("回到前台后应推送画中画前积压的改动", 1, transport.putCalls)
            assertEquals(service.state!!.gen, service.state!!.pushedGen)
            assertTrue(SyncProjection(service.state!!.doc).curation.isFavorite("pip_pending_fav"))

            // 2. Android 12+ 自动画中画路径：WillStart -> DidStart 直接切入 BACKGROUND，同样零同步请求
            transport.resetCounts()
            clock += 20_000L
            stage.dispatch(StageIntent.AddFavorite("pip_api31_fav"))
            assertTrue(service.state!!.gen > service.state!!.pushedGen)
            assertTrue(hub.hasScheduledEdit)

            wiring.onPictureInPictureEvent(PiPEvent.WillStart)
            wiring.onPictureInPictureEvent(PiPEvent.DidStart)
            assertEquals(PhoneScenePhase.BACKGROUND, wiring.phase)
            assertTrue(stage.pictureInPicture)
            assertFalse("Android 12+ 自动画中画必须取消排队的去抖任务", hub.hasScheduledEdit)
            assertEquals("Android 12+ 自动画中画不得发起同步 GET", 0, transport.getCalls)
            assertEquals("Android 12+ 自动画中画不得发起同步 PUT", 0, transport.putCalls)

            // 从画中画回到前台（DidStop 尚未到达）：仍须正常推送
            clock += 20_000L
            wiring.moveTo(PhoneScenePhase.ACTIVE)
            assertEquals(1, transport.getCalls)
            assertEquals(1, transport.putCalls)
            assertEquals(service.state!!.gen, service.state!!.pushedGen)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testNonPipBackgroundFollowsSyncMdAndBlocksLateTriggersWhileBackgrounded() {
        var clock = nowMs
        val stage = makeStage()
        try {
            val (hub, service, transport) = setupActiveHub(stage, { pub -> CountingTransport(pub, epoch, { clock }) }, { clock })
            assertEquals(SyncUiPhase.Active, hub.uiState.value.phase)
            transport.resetCounts()

            val wiring = LifecycleWiring(stage, NoopSession())
            val pip = StubPiP(canAutoEnter = false)
            val coordinator = PiPCoordinator(stage, wiring, audio = { null }).apply {
                this.pip = pip
                playerAdvancing = { true }
            }
            wiring.attachPictureInPicture(coordinator)
            wiring.attachSync(hub)
            wiring.moveTo(PhoneScenePhase.ACTIVE)
            transport.resetCounts()

            // 1. 非画中画后台且无待推改动（gen == pushedGen）：零请求
            assertEquals(service.state!!.gen, service.state!!.pushedGen)
            wiring.moveTo(PhoneScenePhase.BACKGROUND)
            assertEquals("非画中画后台无待推改动时不得发 GET", 0, transport.getCalls)
            assertEquals("非画中画后台无待推改动时不得发 PUT", 0, transport.putCalls)

            // 后台停留期间的迟到去抖/面板触发也不得联网
            clock += 70_000L
            hub.trigger(SyncScheduler.Trigger.LOCAL_EDIT)
            hub.trigger(SyncScheduler.Trigger.PANEL)
            assertEquals("后台停留期间迟到触发不得发请求", 0, transport.getCalls)

            // 2. 回前台新增改动（gen > pushedGen），非画中画进后台：按 sync.md §11.3 尽力推一次
            clock += 20_000L
            wiring.moveTo(PhoneScenePhase.ACTIVE)
            transport.resetCounts()
            clock += 20_000L
            stage.dispatch(StageIntent.AddFavorite("bg_push_fav"))
            assertTrue(service.state!!.gen > service.state!!.pushedGen)
            assertTrue(hub.hasScheduledEdit)

            wiring.moveTo(PhoneScenePhase.BACKGROUND)
            assertFalse("进后台推完必须清理去抖任务", hub.hasScheduledEdit)
            assertEquals("非画中画后台有待推改动时应尽力同步一次", 1, transport.getCalls)
            assertEquals(1, transport.putCalls)
            assertEquals(service.state!!.gen, service.state!!.pushedGen)

            // 3. 硬退避期（429 Retry-After）进入非画中画后台：不得绕过退避，必须取消排队的去抖任务，且后台期间不得再触发
            clock += 20_000L
            wiring.moveTo(PhoneScenePhase.ACTIVE)
            transport.resetCounts()
            transport.rateLimitedSeconds = 120
            clock += 20_000L
            hub.syncNow() // 触发 429，进入 120s 硬退避
            assertEquals(1, transport.getCalls)
            transport.resetCounts()
            transport.rateLimitedSeconds = null

            stage.dispatch(StageIntent.AddFavorite("bg_backoff_fav"))
            assertTrue(hub.hasScheduledEdit)
            wiring.moveTo(PhoneScenePhase.BACKGROUND)
            assertFalse("退避期进后台也必须取消排队的去抖任务", hub.hasScheduledEdit)
            assertEquals("退避期进后台应跳过推送", 0, transport.getCalls)
            // 退避过期后仍处后台，不得再触发
            clock += 180_000L
            hub.trigger(SyncScheduler.Trigger.LOCAL_EDIT)
            assertEquals("仍在后台时退避过期也不得自动联网", 0, transport.getCalls)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testMainActivityConfigurationChangeRetainsSyncHubAndDecouplesFromActivityScope() {
        shadowOf(context.packageManager)
            .getInternalMutablePackageInfo(context.packageName)
            .applicationInfo!!
            .metaData = Bundle().apply { putString(SyncConfig.MANIFEST_META_KEY, origin) }
        SyncPrefs(context).setExperimentSync(true)

        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity1 = controller.get()
            ShadowLooper.idleMainLooper()
            val hub1 = activity1.syncHub
            val stage1 = activity1.stage
            assertNotNull("配置地址后 MainActivity 应创建 SyncHub", hub1)
            assertTrue(activity1.lifecycleScope.isActive)

            // 触发未列入 configChanges 的深色模式配置变化，强制销毁并重建 MainActivity
            val darkConfig = Configuration(activity1.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
            }
            controller.configurationChange(darkConfig)
            ShadowLooper.idleMainLooper()

            val activity2 = controller.get()
            assertTrue("旧 Activity 必须已销毁", activity1.isDestroyed)
            assertFalse("旧 Activity 的 lifecycleScope 必须已取消", activity1.lifecycleScope.isActive)
            assertNotSame("重建必须产生新的 MainActivity 实例", activity1, activity2)
            assertNotSame("重建必须产生新的 StageViewModel 实例", stage1, activity2.stage)
            assertSame("重建后必须复用同一个跨 Activity 保留的 SyncHub", hub1, activity2.syncHub)
            assertSame("新 StageViewModel 必须绑定到保留的 SyncHub", hub1, activity2.stage.syncHub)
            assertSame("新 LifecycleWiring 必须绑定到保留的 SyncHub", hub1, activity2.wiring.syncHub)
        }
    }

    @Test
    fun testMainActivityConfigChangePreservesInFlightEnableJoinClearAndRevealAuth() {
        shadowOf(context.packageManager)
            .getInternalMutablePackageInfo(context.packageName)
            .applicationInfo!!
            .metaData = Bundle().apply { putString(SyncConfig.MANIFEST_META_KEY, origin) }
        val prefs = SyncPrefs(context).apply { setExperimentSync(true) }
        val activitySyncDir = Paths.get(context.filesDir.absolutePath, "Vela")

        val stateStore = TestMemoryStateStore()
        val keyStore = TestMemoryKeyStore()
        var enableGate = CompletableDeferred<Unit>()
        var joinPutGate = CompletableDeferred<Unit>()
        var clearGate = CompletableDeferred<Unit>()
        var mode = "enable"
        var remoteDocEnvelope = ""
        var remotePub = ""

        val gatedTransport = object : SyncTransport {
            override suspend fun get(): SyncServerResponse = when (mode) {
                "enable" -> {
                    enableGate.await()
                    SyncServerResponse.NotFound(epoch, nowMs)
                }
                "join" -> SyncServerResponse.Blob(1L, remotePub, remoteDocEnvelope, epoch, nowMs)
                else -> SyncServerResponse.NotFound(epoch, nowMs)
            }

            override suspend fun put(request: SyncPutRequest): SyncServerResponse = when (mode) {
                "join" -> {
                    joinPutGate.await()
                    SyncServerResponse.Stored(request.next, epoch, nowMs)
                }
                else -> SyncServerResponse.Stored(request.next, epoch, nowMs)
            }

            override suspend fun delete(base: Long, epoch: String?): SyncServerResponse {
                clearGate.await()
                return SyncServerResponse.Deleted
            }
        }

        val hubScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val placeholderStage = makeStage(activitySyncDir)
            val bridgeHolder = RebindableSyncBridge(StageSyncBridge(placeholderStage, prefs))
            placeholderStage.close()

            val service = SyncService(
                SyncService.Environment(
                    stateStore = stateStore,
                    keyStore = keyStore,
                    makeTransport = { _, _ -> gatedTransport },
                    wallClock = { nowMs },
                ),
            )
            val hub = SyncHub(
                service = service,
                prefs = prefs,
                bridge = bridgeHolder,
                scope = hubScope,
                sdkInt = 34,
                capability = SyncCapability.FULL,
                configuredOrigin = origin,
                wallClock = { nowMs },
            )
            SyncHub.retainForTest(context, activitySyncDir, origin, hub, bridgeHolder, hubScope, sdkInt = 34)

            Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
                val activity1 = controller.get()
                ShadowLooper.idleMainLooper()
                assertSame(hub, activity1.syncHub)
                assertEquals(SyncUiPhase.Off, hub.uiState.value.phase)

                // 1. 发起开启同步（首轮周期挂起在网络层），期间发生深色模式配置变化重建 Activity
                mode = "enable"
                val enableJob = hub.startEnable()
                assertNotNull(enableJob)
                assertTrue("首轮同步周期应在途挂起", enableJob!!.isActive)
                val savedCodeBeforeRecreate = hub.uiState.value.savedCode
                assertNotNull("开启后必须立即保存 savedCode", savedCodeBeforeRecreate)

                val darkConfig = Configuration(activity1.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
                }
                controller.configurationChange(darkConfig)
                ShadowLooper.idleMainLooper()

                val activity2 = controller.get()
                assertTrue(activity1.isDestroyed)
                assertFalse(activity1.lifecycleScope.isActive)
                assertSame(hub, activity2.syncHub)
                assertTrue("Activity 重建不得取消在途开启事务", enableJob.isActive)
                assertEquals("Activity 重建不得丢失 savedCode", savedCodeBeforeRecreate, hub.uiState.value.savedCode)

                // 放行首轮网络，在途事务在重建后的宿主上正常收尾
                enableGate.complete(Unit)
                ShadowLooper.idleMainLooper()
                assertFalse(enableJob.isActive)
                assertEquals(SyncUiPhase.Active, hub.uiState.value.phase)
                assertEquals(savedCodeBeforeRecreate, hub.uiState.value.savedCode)
                assertEquals(service.state!!.gen, service.state!!.pushedGen)
                hub.dismissSavedCode()

                // 2. 查看同步码：在 activity2 发起锁屏凭据验证，验证返回前再次重建到 activity3，
                //    activity2.onDestroy 清空其回调后，activity3 收到 RESULT_OK 仍须通过 Hub 恢复展示同步码
                val km = activity2.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                shadowOf(km).setIsDeviceSecure(true)
                activity2.requestSyncCodeReveal(intentFactory = { Intent("android.app.action.CONFIRM_DEVICE_CREDENTIAL") }) {
                    hub.revealAfterAuth()
                }
                val lightConfig = Configuration(activity2.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
                }
                controller.configurationChange(lightConfig)
                ShadowLooper.idleMainLooper()

                val activity3 = controller.get()
                assertTrue(activity2.isDestroyed)
                assertSame(hub, activity3.syncHub)
                assertNull("结果未返回前不得提前露出同步码", hub.uiState.value.revealedCode)

                activity3.onSyncRevealResult(Activity.RESULT_OK)
                assertEquals("跨重建恢复的凭据结果必须放行一次", savedCodeBeforeRecreate, hub.uiState.value.revealedCode)

                // 再次收到的重复 RESULT_OK 不得二次放行
                hub.dismissRevealedCode()
                activity3.onSyncRevealResult(Activity.RESULT_OK)
                assertNull("凭据验证结果消费后不得重复放行", hub.uiState.value.revealedCode)

                // 3. 发起清除云端（DELETE 挂起），期间重建到 activity4，放行后在 activity4 上正常完成到 Off
                mode = "clear"
                val clearJob = hub.startClearCloud()
                assertNotNull(clearJob)
                assertTrue("清除云端应在途挂起", clearJob!!.isActive)

                controller.configurationChange(darkConfig)
                ShadowLooper.idleMainLooper()
                val activity4 = controller.get()
                assertTrue(activity3.isDestroyed)
                assertSame(hub, activity4.syncHub)
                assertTrue("Activity 重建不得取消在途清除事务", clearJob.isActive)

                clearGate.complete(Unit)
                ShadowLooper.idleMainLooper()
                assertFalse(clearJob.isActive)
                assertEquals(SyncUiPhase.Off, hub.uiState.value.phase)

                // 4. 发起加入同步（PUT 挂起），期间重建到 activity5，放行后远端收藏必须投影到新的 activity5.stage
                val joinKey = ByteArray(32) { (it + 73).toByte() }
                val joinDerived = deriveSyncKeys(joinKey)
                val joinCode = vela.core.sync.SyncCode.format(joinKey, origin, ::sha256)!!
                val remoteRep = (vela.core.sync.SyncOps.apply(
                    vela.core.sync.SyncLocalOp.Favorite("recreated_remote_fav"),
                    vela.core.sync.SyncReplica(vela.core.sync.SyncDocument.empty(), SyncClock(dev = 88L)),
                    nowMs - 1000L,
                ) as vela.core.sync.SyncOpOutcome.Applied).replica
                remotePub = joinDerived.pubB64
                remoteDocEnvelope = SyncCryptoCodec(joinDerived).seal(remoteRep.doc.encoded(), 1L)!!

                mode = "join"
                hub.startPrepareJoin(joinCode)
                ShadowLooper.idleMainLooper()
                assertTrue("应进入加入确认页", hub.uiState.value.joinStage is SyncUiJoinStage.Preview)

                val joinJob = hub.startConfirmJoin()
                assertNotNull(joinJob)
                assertTrue("加入提交应在途挂起", joinJob!!.isActive)

                controller.configurationChange(lightConfig)
                ShadowLooper.idleMainLooper()
                val activity5 = controller.get()
                assertTrue(activity4.isDestroyed)
                assertSame(hub, activity5.syncHub)
                assertTrue("配置变化重建不得取消在途加入提交", joinJob.isActive)

                joinPutGate.complete(Unit)
                ShadowLooper.idleMainLooper()
                assertFalse(joinJob.isActive)
                assertEquals(SyncUiPhase.Active, hub.uiState.value.phase)
                assertTrue(
                    "重建后完成的加入投影必须落到新 Activity 的 StageViewModel",
                    activity5.stage.curation.isFavorite("recreated_remote_fav"),
                )
                assertTrue(activity5.stage.state.value.curation.isFavorite("recreated_remote_fav"))
            }
        } finally {
            hubScope.cancel()
        }
    }
}
